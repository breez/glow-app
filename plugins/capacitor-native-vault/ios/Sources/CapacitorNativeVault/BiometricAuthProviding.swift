import Capacitor
import Foundation

/// Coarse error codes returned to the JS layer via `PluginCall.reject(message, code)`.
/// Mirrors the `NativeVaultErrorCode` union in `src/definitions.ts`.
enum NativeVaultErrorCode: String {
    case userCancelled = "USER_CANCELLED"
    case biometricLockout = "BIOMETRIC_LOCKOUT"
    case biometricNotEnrolled = "BIOMETRIC_NOT_ENROLLED"
    case biometricUnavailable = "BIOMETRIC_UNAVAILABLE"
    case keyInvalidated = "KEY_INVALIDATED"
    case noStoredSeed = "NO_STORED_SEED"
    case unknown = "UNKNOWN"
}

/// Detected biometry type, mirrors `NativeVaultBiometryType` in TS.
enum NativeVaultBiometryType: String {
    case none
    case touchId
    case faceId
    case fingerprint
    case face
    case iris
}

struct BiometryCapability {
    let available: Bool
    let type: NativeVaultBiometryType
    /// Why `available` is false (raw platform error code + description),
    /// nil when available. Diagnostic only — surfaced to the JS logger so
    /// "the Face ID row vanished" is explainable from Share Logs.
    var unavailabilityReason: String? = nil
    /// Structured counterpart of `unavailabilityReason`: the mapped
    /// `NativeVaultErrorCode`, so JS can branch (e.g. offer passcode
    /// recovery on BIOMETRIC_LOCKOUT) without string parsing.
    var unavailabilityCode: NativeVaultErrorCode? = nil
}

/// Internal trait/protocol for biometric authentication. The plugin class
/// delegates to a concrete provider (currently `LocalAuthBiometricAuth`,
/// which uses `LAContext.evaluatePolicy`). Splitting the interface keeps
/// the plugin class small and lets future providers slot in without
/// touching the JS-facing layer.
protocol BiometricAuthProviding {
    /// Inspect the device's biometric capability without prompting.
    func checkCapability() -> BiometryCapability

    /// Prompt the user for biometric authentication. Calls `onSuccess` on
    /// pass, `onFailure(code)` on cancel/error.
    func authenticate(
        reason: String,
        onSuccess: @escaping () -> Void,
        onFailure: @escaping (NativeVaultErrorCode, String) -> Void
    )

    /// Prompt with the biometrics-OR-passcode policy
    /// (`deviceOwnerAuthentication`). Succeeding via passcode clears a
    /// biometry lockout — the recovery path for BIOMETRIC_LOCKOUT.
    func authenticateDeviceOwner(
        reason: String,
        onSuccess: @escaping () -> Void,
        onFailure: @escaping (NativeVaultErrorCode, String) -> Void
    )
}
