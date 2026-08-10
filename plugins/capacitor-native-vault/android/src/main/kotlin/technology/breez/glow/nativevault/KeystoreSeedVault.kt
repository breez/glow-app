package technology.breez.glow.nativevault

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * `SeedVaultProviding` implementation backed by:
 *
 *   - Android Keystore for the AES-GCM keys (hardware-backed when the
 *     device supports it; falls back to TEE on others).
 *
 *   - SharedPreferences for the encrypted ciphertext + IV blob. Wiped
 *     on app uninstall (unlike the Keystore key, which also gets wiped
 *     on uninstall, but we wipe prefs explicitly when clearing the
 *     seed for defense-in-depth).
 *
 * The device-only tier is the shipped default. The biometric-bound tier
 * is read-only: its key authorizes on a time basis rather than per
 * operation, so a decrypt can succeed on the strength of an earlier,
 * unrelated authentication. A key's parameters are fixed at generation,
 * so `NativeVaultPlugin` prompts before every decrypt rather than
 * relying on the key to require it.
 */
class KeystoreSeedVault(context: Context) : SeedVaultProviding {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        SHARED_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun hasStoredSeed(): Boolean {
        return prefs.contains(PREFS_KEY_CIPHERTEXT)
    }

    override fun prepareDecryptCipher(): SeedVaultResult<Cipher> {
        if (!hasStoredSeed()) {
            return SeedVaultResult.NotFound
        }

        return try {
            val key = getKey() ?: return SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Stored ciphertext exists but Keystore key is missing",
            )

            val ivBase64 = prefs.getString(PREFS_KEY_IV, null)
                ?: return SeedVaultResult.Error(
                    NativeVaultErrorCode.KEY_INVALIDATED,
                    "Stored ciphertext exists but IV is missing",
                )

            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            SeedVaultResult.Ok(cipher)
        } catch (e: UserNotAuthenticatedException) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.USER_NOT_AUTHENTICATED,
                "Decrypt cipher init requires fresh biometric auth: ${e.message ?: "unknown"}",
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Keystore key invalidated: ${e.message ?: "unknown"}",
            )
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "Decrypt init failure: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "prepareDecryptCipher failed: ${e.message ?: "unknown"}")
        }
    }

    override fun finishDecrypt(cipher: Cipher): SeedVaultResult<String> {
        val ciphertextBase64 = prefs.getString(PREFS_KEY_CIPHERTEXT, null)
            ?: return SeedVaultResult.NotFound

        return try {
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
            val plaintext = cipher.doFinal(ciphertext)
            SeedVaultResult.Ok(String(plaintext, Charsets.UTF_8))
        } catch (e: UserNotAuthenticatedException) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.USER_NOT_AUTHENTICATED,
                "Decrypt requires fresh biometric auth: ${e.message ?: "unknown"}",
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Keystore key invalidated during decrypt: ${e.message ?: "unknown"}",
            )
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "Decrypt failure: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "finishDecrypt failed: ${e.message ?: "unknown"}")
        }
    }

    override fun clearSeed(): SeedVaultResult<Unit> {
        return try {
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Ok(Unit)
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "clearSeed failed: ${e.message ?: "unknown"}")
        }
    }

    // MARK: - Device-only tier (encrypted at rest, no biometric gate)

    override fun hasStoredSeedDeviceOnly(): Boolean {
        return prefs.contains(PREFS_KEY_CIPHERTEXT_DEVICE_ONLY)
    }

    override fun storeSeedDeviceOnly(seed: String): SeedVaultResult<Unit> {
        return try {
            val key = getOrCreateDeviceOnlyKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(seed.toByteArray(Charsets.UTF_8))

            // Write iv + ciphertext atomically, so that whenever the
            // ciphertext is present the matching IV is too.
            //
            // commit(), not apply(): this tier holds the only copy of the
            // seed. apply() returns before the disk write and swallows its
            // result, so a full disk or an IO error would resolve the JS
            // promise as a success and the seed would be gone on next
            // launch.
            val committed = prefs.edit()
                .putString(PREFS_KEY_IV_DEVICE_ONLY, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREFS_KEY_CIPHERTEXT_DEVICE_ONLY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()

            if (!committed) {
                return SeedVaultResult.Error(
                    NativeVaultErrorCode.UNKNOWN,
                    "Failed to persist encrypted seed to disk",
                )
            }

            SeedVaultResult.Ok(Unit)
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.UNKNOWN,
                "Device-only encrypt failure: ${e.message ?: "unknown"}",
            )
        } catch (e: Exception) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.UNKNOWN,
                "storeSeedDeviceOnly failed: ${e.message ?: "unknown"}",
            )
        }
    }

    override fun retrieveSeedDeviceOnly(): SeedVaultResult<String> {
        val ciphertextBase64 = prefs.getString(PREFS_KEY_CIPHERTEXT_DEVICE_ONLY, null)
            ?: return SeedVaultResult.NotFound
        val ivBase64 = prefs.getString(PREFS_KEY_IV_DEVICE_ONLY, null)
            ?: return SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Device-only ciphertext exists but IV is missing",
            )

        return try {
            val key = getDeviceOnlyKey() ?: return SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Device-only ciphertext exists but Keystore key is missing",
            )
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            SeedVaultResult.Ok(String(plaintext, Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.UNKNOWN,
                "Device-only decrypt failure: ${e.message ?: "unknown"}",
            )
        } catch (e: Exception) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.UNKNOWN,
                "retrieveSeedDeviceOnly failed: ${e.message ?: "unknown"}",
            )
        }
    }

    override fun clearSeedDeviceOnly(): SeedVaultResult<Unit> {
        return try {
            prefs.edit()
                .remove(PREFS_KEY_IV_DEVICE_ONLY)
                .remove(PREFS_KEY_CIPHERTEXT_DEVICE_ONLY)
                .apply()
            try { deleteDeviceOnlyKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Ok(Unit)
        } catch (e: Exception) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.UNKNOWN,
                "clearSeedDeviceOnly failed: ${e.message ?: "unknown"}",
            )
        }
    }

    // MARK: - Keystore helpers

    private fun keyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    /** Returns the existing AES key, or null if it doesn't exist. */
    private fun getKey(): SecretKey? {
        val ks = keyStore()
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    private fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
        }
    }

    private fun clearStoredCiphertextOnly() {
        prefs.edit()
            .remove(PREFS_KEY_IV)
            .remove(PREFS_KEY_CIPHERTEXT)
            .apply()
    }

    // MARK: - Device-only key management

    /** Returns the existing device-only AES key, or null if it doesn't exist. */
    private fun getDeviceOnlyKey(): SecretKey? {
        val ks = keyStore()
        val entry = ks.getEntry(KEY_ALIAS_DEVICE_ONLY, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    /** Returns the existing device-only key, or generates a fresh one if missing. */
    private fun getOrCreateDeviceOnlyKey(): SecretKey {
        getDeviceOnlyKey()?.let { return it }
        return generateDeviceOnlyKey()
    }

    /**
     * Generate the device-only AES-GCM key: no auth binding, and no
     * invalidate-on-enrollment-change, so a new biometric enrollment
     * does not wipe it. Still hardware-backed AES-256-GCM where the
     * device supports it, so the ciphertext at rest stays protected by
     * Keystore.
     */
    private fun generateDeviceOnlyKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS_DEVICE_ONLY,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun deleteDeviceOnlyKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS_DEVICE_ONLY)) {
            ks.deleteEntry(KEY_ALIAS_DEVICE_ONLY)
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "technology.breez.glow.native-vault.seed-key"
        private const val KEY_ALIAS_DEVICE_ONLY = "technology.breez.glow.native-vault.seed-key.device-only"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128

        private const val SHARED_PREFERENCES_NAME = "technology.breez.glow.NativeVault"
        private const val PREFS_KEY_IV = "wallet_seed_iv"
        private const val PREFS_KEY_CIPHERTEXT = "wallet_seed_ciphertext"
        private const val PREFS_KEY_IV_DEVICE_ONLY = "wallet_seed_iv_device_only"
        private const val PREFS_KEY_CIPHERTEXT_DEVICE_ONLY = "wallet_seed_ciphertext_device_only"
    }
}
