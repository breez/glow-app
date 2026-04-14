import Foundation

/// A typed result for seed vault operations. Methods that produce a string
/// (`retrieve`) return `.found(seed)` on hit, `.notFound` if no entry exists,
/// or `.error(code, message)` if the underlying store threw. The plugin
/// class translates these into `PluginCall.resolve` / `reject`.
enum SeedVaultResult<T> {
    case ok(T)
    case notFound
    case error(NativeVaultErrorCode, String)
}

/// Internal trait/protocol for biometric-gated seed storage. The plugin
/// class delegates to a concrete provider (currently `KeychainSeedVault`,
/// which uses iOS Keychain). Splitting the interface keeps the plugin class
/// small and lets future providers (e.g. a Secure Enclave-backed one) slot
/// in without touching the JS-facing layer.
protocol SeedVaultProviding {
    /// True if a seed blob is currently persisted. Does NOT prompt biometric.
    func hasStoredSeed() -> Bool

    /// Persist a seed blob, replacing any existing entry. Does NOT prompt
    /// biometric — the caller is expected to gate the call externally.
    func storeSeed(_ seed: String) -> SeedVaultResult<Void>

    /// Retrieve the persisted seed blob. Does NOT prompt biometric — the
    /// caller is expected to have already authenticated. Returns `.notFound`
    /// when no entry exists (mapped to `NO_STORED_SEED` by the plugin).
    func retrieveSeed() -> SeedVaultResult<String>

    /// Delete the persisted seed blob. Idempotent.
    func clearSeed() -> SeedVaultResult<Void>
}
