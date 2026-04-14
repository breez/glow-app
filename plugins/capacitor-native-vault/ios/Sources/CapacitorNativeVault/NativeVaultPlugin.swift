import Capacitor
import Foundation

/// Capacitor plugin entry point for `NativeVault`. Exposes five methods to
/// JS (`checkBiometry`, `hasStoredSeed`, `storeSeed`, `retrieveSeed`,
/// `clearSeed`) and delegates each one to a dedicated provider:
///
///   - `BiometricAuthProviding` for the biometric prompt
///   - `SeedVaultProviding` for the actual Keychain read/write
///
/// Splitting the work across protocol-conforming providers (instead of
/// stuffing everything into the plugin class) keeps each concern testable
/// in isolation and makes the F3 hardening work surgical: F3 swaps the
/// providers' internal implementation (binding biometric to the Keychain
/// access control flag) without touching the plugin class or the JS API.
@objc(NativeVaultPlugin)
public class NativeVaultPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativeVaultPlugin"
    public let jsName = "NativeVault"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkBiometry", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasStoredSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "storeSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "retrieveSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearSeed", returnType: CAPPluginReturnPromise),
    ]

    // Trait-style providers. Pinned to concrete types here; swap the
    // assignments to switch implementations (e.g. for unit tests).
    private let biometric: BiometricAuthProviding = LocalAuthBiometricAuth()
    private let vault: SeedVaultProviding = KeychainSeedVault()

    // MARK: - Biometric capability

    @objc func checkBiometry(_ call: CAPPluginCall) {
        let capability = biometric.checkCapability()
        call.resolve([
            "available": capability.available,
            "biometryType": capability.type.rawValue,
        ])
    }

    // MARK: - Storage

    @objc func hasStoredSeed(_ call: CAPPluginCall) {
        call.resolve(["stored": vault.hasStoredSeed()])
    }

    @objc func storeSeed(_ call: CAPPluginCall) {
        guard let seed = call.getString("seed") else {
            call.reject("Missing required parameter: seed", NativeVaultErrorCode.unknown.rawValue)
            return
        }
        switch vault.storeSeed(seed) {
        case .ok:
            call.resolve()
        case .notFound:
            // `notFound` is a non-sensical outcome for a write; treat as unknown.
            call.reject("Seed vault returned notFound on storeSeed", NativeVaultErrorCode.unknown.rawValue)
        case .error(let code, let message):
            call.reject(message, code.rawValue)
        }
    }

    @objc func retrieveSeed(_ call: CAPPluginCall) {
        // 1. Pre-flight: if no entry exists, fail fast with NO_STORED_SEED
        //    *before* showing a biometric prompt. Otherwise the user would
        //    authenticate just to be told "nothing stored".
        guard vault.hasStoredSeed() else {
            call.reject(
                "No seed is currently persisted in secure storage.",
                NativeVaultErrorCode.noStoredSeed.rawValue
            )
            return
        }

        // 2. Prompt for biometric authentication. The closure is called on
        //    an arbitrary thread; hop back to main before resolving the
        //    PluginCall so any UI updates triggered downstream happen on
        //    the right thread.
        biometric.authenticate(
            reason: "Unlock your Glow wallet",
            onSuccess: { [weak self] in
                guard let self = self else { return }
                DispatchQueue.main.async {
                    self.completeRetrieveAfterAuth(call)
                }
            },
            onFailure: { code, message in
                DispatchQueue.main.async {
                    call.reject(message, code.rawValue)
                }
            }
        )
    }

    @objc func clearSeed(_ call: CAPPluginCall) {
        switch vault.clearSeed() {
        case .ok, .notFound:
            // Idempotent — clearing a missing entry is success.
            call.resolve()
        case .error(let code, let message):
            call.reject(message, code.rawValue)
        }
    }

    // MARK: - Internal

    /// Second half of `retrieveSeed`: runs after biometric auth has
    /// succeeded. Reads the actual Keychain entry and resolves / rejects
    /// the outstanding `PluginCall`.
    private func completeRetrieveAfterAuth(_ call: CAPPluginCall) {
        switch vault.retrieveSeed() {
        case .ok(let seed):
            call.resolve(["seed": seed])
        case .notFound:
            // Race: the entry was cleared between the pre-flight check and
            // the biometric callback. Surface it as NO_STORED_SEED so the
            // caller falls through to onboarding.
            call.reject(
                "No seed is currently persisted in secure storage.",
                NativeVaultErrorCode.noStoredSeed.rawValue
            )
        case .error(let code, let message):
            call.reject(message, code.rawValue)
        }
    }
}
