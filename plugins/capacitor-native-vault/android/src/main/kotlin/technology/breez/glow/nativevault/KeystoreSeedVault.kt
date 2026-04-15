package technology.breez.glow.nativevault

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
            val ciphertext = cipher.doFinal(seed.toByteArray(Charsets.UTF_8))

            // Write iv + ciphertext atomically. Atomicity protects the
            // invariant that whenever PREFS_KEY_CIPHERTEXT is present,
            // the matching IV is also present.
            prefs.edit()
                .putString(PREFS_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREFS_KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .apply()

            SeedVaultResult.Ok(Unit)
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
     * Generate the F3 AES-GCM key. The three flags that matter:
     *
     *   - `setUserAuthenticationRequired(true)` — every cryptographic
     *     operation against this key requires a fresh biometric auth
     *     (via `BiometricPrompt.CryptoObject`). Without this, anyone
     *     with access to the app's process while the device is
     *     unlocked could decrypt the seed.
     *
     *   - `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`
     *     (API 30+) — `0` means the authentication authorizes a
     *     SINGLE crypto operation (not a time window), and
     *     `AUTH_BIOMETRIC_STRONG` requires Class 3 biometrics (Face /
     *     Touch) without the lock-screen credential fallback. On
     *     API < 30 we fall back to the legacy `setUserAuthenticationValidityDurationSeconds(-1)`
     *     which has the same single-operation semantics.
     *
     *   - `setInvalidatedByBiometricEnrollment(true)` — if the user
     *     adds a new fingerprint / face enrollment, the key is wiped.
     *     This forces re-onboarding with a fresh wallet, following
     *     Apple's recommended "BiometryCurrentSet" failsafe pattern.
     *     Without this, an attacker who could enroll their own
     *     biometric (e.g. with a device unlock code) could then
     *     decrypt the stored seed.
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

        // Post-R (API 30+) we can explicitly opt into the single-
        // operation, biometric-strong authentication policy. On older
        // devices, `-1` seconds achieves the single-operation semantics
        // but without the explicit biometric-strong binding; we rely
        // on BiometricPrompt's runtime `AUTH_BIOMETRIC_STRONG`
        // allowed-authenticators flag to enforce the class there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0, // timeout seconds; 0 = authorize a single crypto op
                KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
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
