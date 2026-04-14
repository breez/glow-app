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
}
