import Foundation
import LocalAuthentication

/// `BiometricAuthProviding` implementation backed by `LocalAuthentication`'s
/// `LAContext.evaluatePolicy`.
///
/// F3 note: `NativeVaultPlugin` no longer calls `authenticate()` on iOS —
/// the biometric prompt is triggered inline by the Keychain via a
/// `SecAccessControl` with `.biometryCurrentSet`. This file therefore
/// only services `checkCapability()` for the plugin's `checkBiometry`
/// JS method. The `authenticate()` implementation is kept for protocol
/// conformance and as an escape hatch for future callers that need a
/// standalone biometric gate (e.g. unlocking a password-vault screen).
final class LocalAuthBiometricAuth: BiometricAuthProviding {
    func checkCapability() -> BiometryCapability {
        let context = LAContext()
        var error: NSError?
        let canEvaluate = context.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &error
        )

        guard canEvaluate else {
            // Keep the raw LAError visible: lockout (-8), not enrolled
            // (-7), permission denied / not available (-6) all collapse
            // to `available: false` otherwise, and the Security page
            // hides the biometric row with no way to tell why.
            let code = mapLAError(error)
            let reason = "canEvaluatePolicy=false code=\(error?.code ?? 0) "
                + "(\(code.rawValue)): "
                + (error?.localizedDescription ?? "no error description")
            NSLog("NativeVault checkBiometry unavailable: %@", reason)
            return BiometryCapability(
                available: false,
                type: .none,
                unavailabilityReason: reason,
                unavailabilityCode: code
            )
        }

        let type: NativeVaultBiometryType
        switch context.biometryType {
        case .faceID: type = .faceId
        case .touchID: type = .touchId
        case .opticID: type = .iris
        case .none: type = .none
        @unknown default: type = .none
        }
        return BiometryCapability(available: true, type: type)
    }

    func authenticate(
        reason: String,
        onSuccess: @escaping () -> Void,
        onFailure: @escaping (NativeVaultErrorCode, String) -> Void
    ) {
        // A fresh `LAContext` per call so we never reuse a previously-cached
        // authentication state across operations.
        let context = LAContext()

        // Pre-flight: surface BIOMETRIC_NOT_ENROLLED / BIOMETRIC_UNAVAILABLE
        // before showing a prompt that would just fail anyway.
        var policyError: NSError?
        if !context.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &policyError
        ) {
            let code = mapLAError(policyError as Error?)
            onFailure(code, policyError?.localizedDescription ?? "Biometric authentication unavailable")
            return
        }

        context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        ) { success, error in
            // The closure is called on an arbitrary thread; the plugin class
            // is responsible for hopping back to the main thread before
            // resolving the PluginCall.
            if success {
                onSuccess()
                return
            }
            let code = mapLAError(error)
            let message = error?.localizedDescription ?? "Biometric authentication failed"
            onFailure(code, message)
        }
    }

    func authenticateDeviceOwner(
        reason: String,
        onSuccess: @escaping () -> Void,
        onFailure: @escaping (NativeVaultErrorCode, String) -> Void
    ) {
        let context = LAContext()

        // No biometrics-availability pre-flight here, on purpose: this
        // policy is exactly for the states where biometrics can't run
        // (lockout). `deviceOwnerAuthentication` falls back to the
        // device passcode, and succeeding via passcode clears the
        // biometry lockout system-wide.
        var policyError: NSError?
        if !context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &policyError) {
            // Only fails without a device passcode set at all.
            let code = mapLAError(policyError)
            onFailure(code, policyError?.localizedDescription ?? "Device authentication unavailable")
            return
        }

        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: reason
        ) { success, error in
            if success {
                onSuccess()
                return
            }
            let code = mapLAError(error)
            let message = error?.localizedDescription ?? "Device authentication failed"
            onFailure(code, message)
        }
    }
}

/// Map an `LAError` (or any other `Error`) to a `NativeVaultErrorCode`.
/// Centralized here so both the pre-flight `canEvaluatePolicy` path and the
/// async `evaluatePolicy` callback funnel through the same logic.
private func mapLAError(_ error: Error?) -> NativeVaultErrorCode {
    guard let nsError = error as NSError?, nsError.domain == LAErrorDomain else {
        return .unknown
    }
    guard let laError = LAError.Code(rawValue: nsError.code) else {
        return .unknown
    }
    switch laError {
    case .userCancel, .systemCancel, .appCancel, .userFallback:
        return .userCancelled
    case .biometryLockout:
        return .biometricLockout
    case .biometryNotEnrolled:
        return .biometricNotEnrolled
    case .biometryNotAvailable, .passcodeNotSet:
        return .biometricUnavailable
    case .authenticationFailed, .invalidContext, .notInteractive:
        return .unknown
    @unknown default:
        return .unknown
    }
}
