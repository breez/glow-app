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
///
/// Two storage tiers are exposed side-by-side:
///
///   - The biometric-bound tier (`hasStoredSeed` / `storeSeed` /
///     `retrieveSeed` / `clearSeed`) — guarded by `SecAccessControl`
///     with `.biometryCurrentSet`. Used by passkey-mode users.
///
///   - The device-only tier (the `...DeviceOnly` family) — encrypted
///     at rest and pinned to `whenUnlockedThisDeviceOnly` but WITHOUT
///     biometric binding. Used by non-passkey users who opted out of
///     the passkey flow during onboarding. Still stronger than plain
///     localStorage (no iCloud sync, no encrypted backups, locked at
///     rest) but doesn't gate reads behind a biometric prompt.
protocol SeedVaultProviding {
    // Biometric-bound tier

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

    // Device-only tier (encrypted at rest, no biometric gate)

    /// True if a device-only seed blob is currently persisted. Does NOT prompt.
    func hasStoredSeedDeviceOnly() -> Bool

    /// Persist a seed blob in the device-only tier, replacing any existing
    /// entry. Does NOT prompt biometric — the Keychain item's access control
    /// uses only `whenUnlockedThisDeviceOnly`, not biometric binding.
    func storeSeedDeviceOnly(_ seed: String) -> SeedVaultResult<Void>

    /// Retrieve the device-only seed blob. Does NOT prompt biometric.
    /// Returns `.notFound` when no entry exists.
    func retrieveSeedDeviceOnly() -> SeedVaultResult<String>

    /// Delete the device-only seed blob. Idempotent.
    func clearSeedDeviceOnly() -> SeedVaultResult<Void>
}
