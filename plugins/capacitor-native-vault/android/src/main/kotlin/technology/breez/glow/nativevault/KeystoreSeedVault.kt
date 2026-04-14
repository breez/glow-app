package technology.breez.glow.nativevault

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
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
 *   - Android Keystore for the AES-GCM key (hardware-backed when the
 *     device supports it; falls back to TEE on others)
 *   - SharedPreferences for the encrypted ciphertext + IV blob
 *
 * In F2 the AES key is created without `setUserAuthenticationRequired`,
 * so any process holding the device-unlocked state can use it. F3 will
 * tighten this by adding `setUserAuthenticationRequired(true)` and
 * `setInvalidatedByBiometricEnrollment(true)`, which:
 *
 *   - Forces biometric authentication on every `Cipher.init` (binding
 *     the cryptographic operation to the auth result via `CryptoObject`)
 *   - Wipes the key automatically on any new biometric enrollment,
 *     forcing the user to re-onboard with a fresh wallet (Apple's
 *     recommended failsafe pattern, replicated for Android)
 *
 * Also in F2: `KeyPermanentlyInvalidatedException` is mapped to
 * `KEY_INVALIDATED` so the JS layer wipes the stored blob and falls
 * through to the legacy passkey path.
 */
class KeystoreSeedVault(context: Context) : SeedVaultProviding {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        SHARED_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun hasStoredSeed(): Boolean {
        return prefs.contains(PREFS_KEY_CIPHERTEXT)
    }

    override fun storeSeed(seed: String): SeedVaultResult<Unit> {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(seed.toByteArray(Charsets.UTF_8))

            // Write iv + ciphertext atomically. Atomicity protects the
            // invariant that whenever PREFS_KEY_CIPHERTEXT is present, the
            // matching IV is also present.
            prefs.edit()
                .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREFS_KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()

            SeedVaultResult.Ok(Unit)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The Keystore key was wiped (e.g. lock screen credential
            // change). The existing ciphertext is unrecoverable; clear
            // both halves so a subsequent retrieve hits NO_STORED_SEED.
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Keystore key invalidated: ${e.message ?: "unknown"}",
            )
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "Crypto failure: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "storeSeed failed: ${e.message ?: "unknown"}")
        }
    }

    override fun retrieveSeed(): SeedVaultResult<String> {
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
            val ciphertextBase64 = prefs.getString(PREFS_KEY_CIPHERTEXT, null)
                ?: return SeedVaultResult.NotFound

            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            SeedVaultResult.Ok(String(plaintext, Charsets.UTF_8))
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Wipe both halves and delete the key so the next attempt is a
            // clean fresh-install state.
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Keystore key invalidated: ${e.message ?: "unknown"}",
            )
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "Decrypt failure: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "retrieveSeed failed: ${e.message ?: "unknown"}")
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

    /** Returns the existing key, or generates a fresh one if missing. */
    private fun getOrCreateKey(): SecretKey {
        getKey()?.let { return it }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
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

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "technology.breez.glow.native-vault.seed-key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128

        private const val SHARED_PREFERENCES_NAME = "technology.breez.glow.NativeVault"
        private const val PREFS_KEY_IV = "wallet_seed_iv"
        private const val PREFS_KEY_CIPHERTEXT = "wallet_seed_ciphertext"
    }
}
