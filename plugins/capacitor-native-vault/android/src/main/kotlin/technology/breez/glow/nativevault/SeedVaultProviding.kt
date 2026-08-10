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
 * Internal trait/interface for seed storage. The plugin class delegates
 * to a concrete provider (currently `KeystoreSeedVault`, which uses
 * Android Keystore + a SharedPreferences-backed ciphertext store).
 */
interface SeedVaultProviding {
    // ---- Biometric-bound tier: read-only ----
    //
    // Retired. Read and clear only, so a legacy entry can be moved into
    // the device-only tier. Do not add a store counterpart.

    /** True if a legacy bound seed blob is still persisted. Does NOT prompt. */
    fun hasStoredSeed(): Boolean

    /**
     * Read the stored IV and initialize a `Cipher` in DECRYPT_MODE
     * against the legacy Keystore key with `GCMParameterSpec(iv)`.
     *
     * Returns `NotFound` if there's no persisted ciphertext or IV, and
     * `Error(USER_NOT_AUTHENTICATED)` if the key's auth window has
     * lapsed.
     */
    fun prepareDecryptCipher(): SeedVaultResult<Cipher>

    /**
     * `cipher.doFinal(ciphertext)` and decode the plaintext as UTF-8,
     * using the Cipher from the matching `prepareDecryptCipher` call.
     */
    fun finishDecrypt(cipher: Cipher): SeedVaultResult<String>

    /** Delete the persisted seed blob and the underlying Keystore key. Idempotent. */
    fun clearSeed(): SeedVaultResult<Unit>

    // ---- Device-only tier (encrypted at rest, no biometric gate) ----
    //
    // The shipped default for every seed. App lock (PIN / biometric /
    // auto-lock) gates the UI from JS instead; see appLock.ts. The key
    // is not auth-bound, so encrypt and decrypt run in a single step.

    /** True if a device-only seed blob is currently persisted. Does NOT prompt. */
    fun hasStoredSeedDeviceOnly(): Boolean

    /**
     * Persist a seed blob in the device-only tier. Generates the underlying
     * Keystore key on first use without any biometric-auth flags.
     */
    fun storeSeedDeviceOnly(seed: String): SeedVaultResult<Unit>

    /**
     * Retrieve the device-only seed blob. Does NOT prompt biometric.
     * Returns `NotFound` if nothing is stored.
     */
    fun retrieveSeedDeviceOnly(): SeedVaultResult<String>

    /** Delete the device-only seed blob and its Keystore key. Idempotent. */
    fun clearSeedDeviceOnly(): SeedVaultResult<Unit>
}
