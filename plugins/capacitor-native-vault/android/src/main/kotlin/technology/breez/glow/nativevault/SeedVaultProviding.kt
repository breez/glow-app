package technology.breez.glow.nativevault

import javax.crypto.Cipher

/**
 * A typed result for seed vault operations. Methods that produce a value
 * return `Ok(value)` on hit, `NotFound` if no entry exists, or
 * `Error(code, message)` if the underlying store threw. The plugin class
 * translates these into `PluginCall.resolve` / `reject`.
 */
sealed class SeedVaultResult<out T> {
    data class Ok<T>(val value: T) : SeedVaultResult<T>()
    object NotFound : SeedVaultResult<Nothing>()
    data class Error(val code: NativeVaultErrorCode, val message: String) : SeedVaultResult<Nothing>()
}

/**
 * Internal trait/interface for biometric-gated seed storage. The plugin
 * class delegates to a concrete provider (currently `KeystoreSeedVault`,
 * which uses Android Keystore + a SharedPreferences-backed ciphertext
 * store).
 *
 * F3 shape: the store/retrieve flows are split into a `prepareX` /
 * `finishX` pair so that the plugin can insert a biometric prompt in
 * the middle (via `BiometricPrompt.CryptoObject`). This is how Android's
 * `setUserAuthenticationRequired(true)` keys work: the key's Cipher
 * cannot run until it's been authenticated against a biometric, and
 * `BiometricPrompt` is the only API that produces an authenticated
 * Cipher.
 *
 * iOS's `KeychainSeedVault` is architecturally simpler because the
 * Keychain `SecAccessControl` handles the biometric prompt inline,
 * so it does NOT need a prepare/finish split. This divergence is
 * intentional — each platform matches its OS-native pattern.
 */
interface SeedVaultProviding {
    /** True if a seed blob is currently persisted. Does NOT prompt biometric. */
    fun hasStoredSeed(): Boolean

    /**
     * First half of the store flow: initialize a `Cipher` in ENCRYPT_MODE
     * against the Keystore key. The returned Cipher must then be wrapped
     * in a `BiometricPrompt.CryptoObject` and authenticated; only after
     * `BiometricPrompt.onAuthenticationSucceeded` fires is it legal to
     * call `finishEncryptAndStore` with that same Cipher.
     *
     * This call may generate the AES-GCM key if it doesn't already exist.
     * Key generation itself does NOT require biometric — only the
     * `Cipher.init(ENCRYPT_MODE, key)` after generation does.
     *
     * Returns `Error(KEY_INVALIDATED)` if the stored key has been wiped
     * (e.g. new biometric enrollment) and needs to be regenerated. In
     * that case the caller should retry once.
     */
    fun prepareEncryptCipher(): SeedVaultResult<Cipher>

    /**
     * Second half of the store flow: `cipher.doFinal(seed)` and persist
     * the resulting ciphertext + IV to SharedPreferences. Requires the
     * cipher to have been successfully authenticated via BiometricPrompt
     * since the last `prepareEncryptCipher` call.
     */
    fun finishEncryptAndStore(seed: String, cipher: Cipher): SeedVaultResult<Unit>

    /**
     * First half of the retrieve flow: read the stored IV from
     * SharedPreferences and initialize a `Cipher` in DECRYPT_MODE
     * against the Keystore key with `GCMParameterSpec(iv)`. The
     * returned Cipher must then be wrapped in a
     * `BiometricPrompt.CryptoObject` and authenticated before
     * `finishDecrypt` is called.
     *
     * Returns `NotFound` if there's no persisted ciphertext or IV.
     */
    fun prepareDecryptCipher(): SeedVaultResult<Cipher>

    /**
     * Second half of the retrieve flow: `cipher.doFinal(ciphertext)`
     * and decode the plaintext as UTF-8. Requires the cipher to have
     * been successfully authenticated via BiometricPrompt since the
     * last `prepareDecryptCipher` call.
     */
    fun finishDecrypt(cipher: Cipher): SeedVaultResult<String>

    /** Delete the persisted seed blob and the underlying Keystore key. Idempotent. */
    fun clearSeed(): SeedVaultResult<Unit>
}
