package technology.breez.glow.nativevault

/**
 * A typed result for seed vault operations. Methods that produce a string
 * (`retrieve`) return `Ok(seed)` on hit, `NotFound` if no entry exists, or
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
 * which uses Android Keystore + EncryptedSharedPreferences-style ciphertext
 * storage). Splitting the interface keeps the plugin class small and lets
 * future providers (e.g. StrongBox-backed) slot in without touching the
 * JS-facing layer.
 */
interface SeedVaultProviding {
    /** True if a seed blob is currently persisted. Does NOT prompt biometric. */
    fun hasStoredSeed(): Boolean

    /**
     * Persist a seed blob, replacing any existing entry. Does NOT prompt
     * biometric — the caller is expected to gate the call externally.
     */
    fun storeSeed(seed: String): SeedVaultResult<Unit>

    /**
     * Retrieve the persisted seed blob. Does NOT prompt biometric — the
     * caller is expected to have already authenticated. Returns
     * `SeedVaultResult.NotFound` when no entry exists (mapped to
     * `NO_STORED_SEED` by the plugin).
     */
    fun retrieveSeed(): SeedVaultResult<String>

    /** Delete the persisted seed blob. Idempotent. */
    fun clearSeed(): SeedVaultResult<Unit>
}
