import Capacitor
import Foundation

/// Capacitor plugin entry point for `NativeVault`. Exposes five methods to
/// JS (`checkBiometry`, `hasStoredSeed`, `storeSeed`, `retrieveSeed`,
/// `clearSeed`) and delegates each one to a dedicated provider:
///
///   - `BiometricAuthProviding` for `checkBiometry`
///   - `SeedVaultProviding` for the actual Keychain read/write
///
/// Splitting the work across protocol-conforming providers (instead of
/// stuffing everything into the plugin class) keeps each concern testable
/// in isolation.
///
/// F3 note: on iOS, the biometric prompt is triggered inline by the
/// Keychain itself via `SecAccessControl` with `.biometryCurrentSet`.
/// This plugin class therefore does NOT call `BiometricAuthProviding.authenticate`
/// around `retrieveSeed` / `storeSeed` — the crypto layer (Keychain)
/// owns the prompt. `BiometricAuthProviding` is only used for the
/// synchronous `checkBiometry` capability query.
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
        // F3: `KeychainSeedVault.storeSeed` may trigger a biometric
        // prompt (the first write creates the SecAccessControl-protected
        // item). That prompt can block on the main thread; move the
        // call off it so we don't jank the webview. The `Task.detached`
        // runs on a concurrent background queue, then we hop back to
        // main to resolve the PluginCall.
        Task.detached { [weak self] in
            guard let self = self else { return }
            let result = self.vault.storeSeed(seed)
            await MainActor.run {
                switch result {
                case .ok:
                    call.resolve()
                case .notFound:
                    // `notFound` is a non-sensical outcome for a write; treat as unknown.
                    call.reject("Seed vault returned notFound on storeSeed", NativeVaultErrorCode.unknown.rawValue)
                case .error(let code, let message):
                    call.reject(message, code.rawValue)
                }
            }
        }
    }

    @objc func retrieveSeed(_ call: CAPPluginCall) {
        // F3: No explicit biometric pre-step. `KeychainSeedVault.retrieveSeed`
        // triggers the biometric prompt inline as part of
        // `SecItemCopyMatching` against the `.biometryCurrentSet`-guarded
        // item. The Keychain call blocks the current thread until the
        // user either authenticates or dismisses the prompt, so we must
        // run it on a background thread to avoid freezing the webview.
        Task.detached { [weak self] in
            guard let self = self else { return }
            let result = self.vault.retrieveSeed()
            await MainActor.run {
                switch result {
                case .ok(let seed):
                    call.resolve(["seed": seed])
                case .notFound:
                    // Entry missing. Either there was never one, or it
                    // was cleared between `hasStoredSeed` and here.
                    call.reject(
                        "No seed is currently persisted in secure storage.",
                        NativeVaultErrorCode.noStoredSeed.rawValue
                    )
                case .error(let code, let message):
                    call.reject(message, code.rawValue)
                }
            }
        }
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
}
