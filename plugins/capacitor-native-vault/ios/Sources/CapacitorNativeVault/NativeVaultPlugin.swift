import Capacitor
import Foundation
import UIKit

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
        CAPPluginMethod(name: "authenticate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "authenticateDeviceOwner", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasStoredSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "storeSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "retrieveSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasStoredSeedDeviceOnly", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "storeSeedDeviceOnly", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "retrieveSeedDeviceOnly", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearSeedDeviceOnly", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "copySensitive", returnType: CAPPluginReturnPromise),
    ]

    // Trait-style providers. Pinned to concrete types here; swap the
    // assignments to switch implementations (e.g. for unit tests).
    private let biometric: BiometricAuthProviding = LocalAuthBiometricAuth()
    private let vault: SeedVaultProviding = KeychainSeedVault()

    // MARK: - Biometric capability

    @objc func checkBiometry(_ call: CAPPluginCall) {
        let capability = biometric.checkCapability()
        var result: [String: Any] = [
            "available": capability.available,
            "biometryType": capability.type.rawValue,
        ]
        if let reason = capability.unavailabilityReason {
            result["unavailabilityReason"] = reason
        }
        if let code = capability.unavailabilityCode {
            result["unavailabilityCode"] = code.rawValue
        }
        call.resolve(result)
    }

    /// Biometrics-OR-passcode prompt (`deviceOwnerAuthentication`).
    /// Succeeding via passcode clears a biometry lockout — the JS layer
    /// offers this when `checkBiometry` reports BIOMETRIC_LOCKOUT.
    @objc func authenticateDeviceOwner(_ call: CAPPluginCall) {
        let reason = call.getString("reason") ?? "Unlock Glow"
        biometric.authenticateDeviceOwner(
            reason: reason,
            onSuccess: {
                DispatchQueue.main.async { call.resolve() }
            },
            onFailure: { code, message in
                DispatchQueue.main.async { call.reject(message, code.rawValue) }
            }
        )
    }

    /// Standalone biometric gate: prompts and resolves/rejects without
    /// touching the vault. Used by the JS app-lock layer (Security page
    /// gating + lock screen), where the seed itself stays in the
    /// device-only tier so a PIN fallback can still start the app.
    @objc func authenticate(_ call: CAPPluginCall) {
        let reason = call.getString("reason") ?? "Unlock Glow"
        biometric.authenticate(
            reason: reason,
            onSuccess: {
                DispatchQueue.main.async { call.resolve() }
            },
            onFailure: { code, message in
                DispatchQueue.main.async { call.reject(message, code.rawValue) }
            }
        )
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

    // MARK: - Device-only tier (encrypted at rest, no biometric gate)

    @objc func hasStoredSeedDeviceOnly(_ call: CAPPluginCall) {
        call.resolve(["stored": vault.hasStoredSeedDeviceOnly()])
    }

    @objc func storeSeedDeviceOnly(_ call: CAPPluginCall) {
        guard let seed = call.getString("seed") else {
            call.reject("Missing required parameter: seed", NativeVaultErrorCode.unknown.rawValue)
            return
        }
        // Keychain writes can block; stay off the main thread for parity
        // with the biometric-bound path even though no prompt is shown.
        Task.detached { [weak self] in
            guard let self = self else { return }
            let result = self.vault.storeSeedDeviceOnly(seed)
            await MainActor.run {
                switch result {
                case .ok:
                    call.resolve()
                case .notFound:
                    call.reject(
                        "Seed vault returned notFound on storeSeedDeviceOnly",
                        NativeVaultErrorCode.unknown.rawValue
                    )
                case .error(let code, let message):
                    call.reject(message, code.rawValue)
                }
            }
        }
    }

    @objc func retrieveSeedDeviceOnly(_ call: CAPPluginCall) {
        Task.detached { [weak self] in
            guard let self = self else { return }
            let result = self.vault.retrieveSeedDeviceOnly()
            await MainActor.run {
                switch result {
                case .ok(let seed):
                    call.resolve(["seed": seed])
                case .notFound:
                    call.reject(
                        "No seed is currently persisted in device-only secure storage.",
                        NativeVaultErrorCode.noStoredSeed.rawValue
                    )
                case .error(let code, let message):
                    call.reject(message, code.rawValue)
                }
            }
        }
    }

    @objc func clearSeedDeviceOnly(_ call: CAPPluginCall) {
        switch vault.clearSeedDeviceOnly() {
        case .ok, .notFound:
            call.resolve()
        case .error(let code, let message):
            call.reject(message, code.rawValue)
        }
    }

    // MARK: - Sensitive clipboard

    /// Copy text the OS should treat as a secret.
    ///
    /// `expirationDate` is the point of doing this natively: the pasteboard
    /// drops the value on its own, so it does not outlive the app if the JS
    /// clear timer never runs. `localOnly` keeps it off Universal Clipboard,
    /// so it is not handed to the user's other devices.
    @objc func copySensitive(_ call: CAPPluginCall) {
        guard let value = call.getString("value") else {
            call.reject("Missing required parameter: value", NativeVaultErrorCode.unknown.rawValue)
            return
        }
        let ttl = call.getDouble("ttlSeconds") ?? 60
        DispatchQueue.main.async {
            UIPasteboard.general.setItems(
                [["public.utf8-plain-text": value]],
                options: [
                    .expirationDate: Date().addingTimeInterval(ttl),
                    .localOnly: true,
                ]
            )
            call.resolve()
        }
    }
}
