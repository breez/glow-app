package technology.breez.glow.nativevault

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
 *   - Android Keystore for the AES-GCM key (hardware-backed when the
 *     device supports it; falls back to TEE on others). F3 marks the
 *     key as `setUserAuthenticationRequired(true)` with
 *     `AUTH_BIOMETRIC_STRONG`, so `Cipher.init(...)` against it is
 *     unusable until the caller has wrapped it in a
 *     `BiometricPrompt.CryptoObject` and obtained a biometric auth.
 *     The key is also `setInvalidatedByBiometricEnrollment(true)`, so
 *     any new biometric enrollment voids it and forces re-onboarding
 *     (Apple's recommended failsafe pattern, replicated for Android).
 *
 *   - SharedPreferences for the encrypted ciphertext + IV blob. Wiped
 *     on app uninstall (unlike the Keystore key, which also gets wiped
 *     on uninstall, but we wipe prefs explicitly when clearing the
 *     seed for defense-in-depth).
 *
 * F3 architecture note: the store/retrieve flows are split into
 * `prepareX` / `finishX` halves. `Cipher.init(mode, key)` against a
 * `setUserAuthenticationRequired(true)` key succeeds without biometric
 * auth — it's the first `doFinal` / `update` call that throws
 * `UserNotAuthenticatedException`. So the plugin can:
 *
 *   1. Call `prepareEncryptCipher` / `prepareDecryptCipher` to get
 *      an initialized but not-yet-used Cipher.
 *   2. Wrap it in a `BiometricPrompt.CryptoObject` and show the
 *      system biometric prompt.
 *   3. On successful auth, `BiometricPrompt.AuthenticationCallback`
 *      hands back a CryptoObject whose Cipher is now authorized for
 *      exactly one cryptographic operation.
 *   4. Call `finishEncryptAndStore` / `finishDecrypt` with the
 *      authenticated Cipher to run the single `doFinal` call.
 *
 * This is the idiomatic Android API shape for biometric-bound
 * keys. iOS's `KeychainSeedVault` is architecturally simpler because
 * the Keychain `SecAccessControl` handles the biometric prompt inline,
 * so it does NOT need a prepare/finish split. This divergence is
 * intentional — each platform matches its OS-native pattern.
 */
class KeystoreSeedVault(context: Context) : SeedVaultProviding {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        SHARED_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun hasStoredSeed(): Boolean {
        return prefs.contains(PREFS_KEY_CIPHERTEXT)
    }

    override fun prepareEncryptCipher(): SeedVaultResult<Cipher> {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            SeedVaultResult.Ok(cipher)
        } catch (e: UserNotAuthenticatedException) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.USER_NOT_AUTHENTICATED,
                "Encrypt cipher init requires fresh biometric auth: ${e.message ?: "unknown"}",
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The Keystore key was wiped (new biometric enrollment,
            // lock-screen credential change, etc.). Wipe any stale
            // ciphertext + the key entry so the next call regenerates.
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Keystore key invalidated: ${e.message ?: "unknown"}",
            )
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "Crypto init failure: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "prepareEncryptCipher failed: ${e.message ?: "unknown"}")
        }
    }

    override fun finishEncryptAndStore(seed: String, cipher: Cipher): SeedVaultResult<Unit> {
        return try {
            val iv = cipher.iv
            val ciphertext = seed.toByteArray(Charsets.UTF_8).useThenZero { cipher.doFinal(it) }

            // Write iv + ciphertext atomically. Atomicity protects the
            // invariant that whenever PREFS_KEY_CIPHERTEXT is present,
            // the matching IV is also present.
            //
            // commit(), not apply(): this is the only copy of the seed, and
            // apply() returns before the disk write and swallows its result,
            // so a full disk or IO error would resolve the JS promise as a
            // success and the seed would be gone on next launch.
            val committed = prefs.edit()
                .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREFS_KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()

            if (!committed) {
                return SeedVaultResult.Error(
                    NativeVaultErrorCode.UNKNOWN,
                    "Failed to persist encrypted seed to disk",
                )
            }

            SeedVaultResult.Ok(Unit)
        } catch (e: UserNotAuthenticatedException) {
            SeedVaultResult.Error(
                NativeVaultErrorCode.USER_NOT_AUTHENTICATED,
                "Encrypt requires fresh biometric auth: ${e.message ?: "unknown"}",
            )
        } catch (e: KeyPermanentlyInvalidatedException) {
            clearStoredCiphertextOnly()
            try { deleteKey() } catch (_: Exception) { /* best-effort */ }
            SeedVaultResult.Error(
                NativeVaultErrorCode.KEY_INVALIDATED,
                "Keystore key invalidated during encrypt: ${e.message ?: "unknown"}",
            )
        } catch (e: GeneralSecurityException) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "Encrypt failure: ${e.message ?: "unknown"}")
        } catch (e: Exception) {
            SeedVaultResult.Error(NativeVaultErrorCode.UNKNOWN, "finishEncryptAndStore failed: ${e.message ?: "unknown"}")
        }
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
            SeedVaultResult.Ok(plaintext.useThenZero { String(it, Charsets.UTF_8) })
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
            val ciphertext = seed.toByteArray(Charsets.UTF_8).useThenZero { cipher.doFinal(it) }

            // commit(), not apply(): see finishEncryptAndStore. This tier is
            // the shipped default, so it is the copy that matters most.
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
            SeedVaultResult.Ok(plaintext.useThenZero { String(it, Charsets.UTF_8) })
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

    /** Returns the existing key, or generates a fresh one if missing. */
    private fun getOrCreateKey(): SecretKey {
        getKey()?.let { return it }
        return generateKey()
    }

    /**
     * Generate the F3 AES-GCM key. Time-based BIOMETRIC_STRONG auth
     * (any successful biometric within KEY_AUTH_GRACE_SECONDS) plus
     * invalidate-on-enrollment-change.
     */
    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        )
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                KEY_AUTH_GRACE_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(KEY_AUTH_GRACE_SECONDS)
        }

        keyGenerator.init(builder.build())
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
     * Generate the device-only AES-GCM key. Mirrors `generateKey()` but
     * WITHOUT the biometric-auth flags:
     *
     *   - No `setUserAuthenticationRequired(true)` — `Cipher.doFinal`
     *     runs without a `BiometricPrompt.CryptoObject`.
     *   - No `setInvalidatedByBiometricEnrollment(true)` — a new
     *     biometric enrollment does NOT wipe this key.
     *   - No `setUserAuthenticationParameters(...)` — nothing to
     *     authenticate against.
     *
     * The key is still hardware-backed AES-256-GCM on devices that
     * support it, so the ciphertext at rest is still protected by
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

    /**
     * Run [block] over these bytes, then overwrite them, so plaintext seed
     * material is not left for a heap dump to find on a rooted device.
     *
     * Only covers buffers this class owns. The `String` on either side of
     * the boundary is immutable and stays in the heap until GC: keeping
     * plaintext off the bridge entirely is the real fix (see CLAUDE.md).
     */
    private inline fun <T> ByteArray.useThenZero(block: (ByteArray) -> T): T =
        try {
            block(this)
        } finally {
            fill(0)
        }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "technology.breez.glow.native-vault.seed-key"
        private const val KEY_ALIAS_DEVICE_ONLY = "technology.breez.glow.native-vault.seed-key.device-only"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128

        /** Grace window after a BIOMETRIC_STRONG auth, in seconds. */
        private const val KEY_AUTH_GRACE_SECONDS = 30

        private const val SHARED_PREFERENCES_NAME = "technology.breez.glow.NativeVault"
        private const val PREFS_KEY_IV = "wallet_seed_iv"
        private const val PREFS_KEY_CIPHERTEXT = "wallet_seed_ciphertext"
        private const val PREFS_KEY_IV_DEVICE_ONLY = "wallet_seed_iv_device_only"
        private const val PREFS_KEY_CIPHERTEXT_DEVICE_ONLY = "wallet_seed_ciphertext_device_only"
    }
}
