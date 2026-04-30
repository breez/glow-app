import Capacitor
import Foundation
import BreezSdkSpark

@objc(PasskeyPrfPlugin)
public class PasskeyPrfPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PasskeyPrfPlugin"
    public let jsName = "PasskeyPrf"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isPrfAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createPasskey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "derivePrfSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkDomainAssociation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getKnownCredentialIds", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearKnownCredentialIds", returnType: CAPPluginReturnPromise),
    ]

    @objc func isPrfAvailable(_ call: CAPPluginCall) {
        if #available(iOS 18.0, *) {
            let provider = makeProvider(call)
            Task {
                do {
                    let available = try await provider.isPrfAvailable()
                    call.resolve(["available": available])
                } catch {
                    call.reject(error.localizedDescription, errorCode(error))
                }
            }
        } else {
            call.resolve(["available": false])
        }
    }

    @objc func createPasskey(_ call: CAPPluginCall) {
        if #available(iOS 18.0, *) {
            let provider = makeProvider(call)
            let rpId = call.getString("rpId") ?? "keys.breez.technology"

            // Build excludeCredentials by merging two sources:
            //   1. Caller-provided base64 IDs (e.g. localStorage list on
            //      web, kept for legacy compatibility).
            //   2. The plugin-owned, iCloud-synced keychain store. This
            //      survives app uninstall and restores from iCloud, which
            //      the localStorage list does not.
            // The platform refuses registration if any listed credential
            // is currently registered on the device, regardless of source.
            var seen = Set<String>()
            var excludeBase64: [String] = []
            if let base64Array = call.getArray("excludeCredentialIds", String.self) {
                for id in base64Array where seen.insert(id).inserted {
                    excludeBase64.append(id)
                }
            }
            for id in KnownCredentialsStore.read(rpId: rpId) where seen.insert(id).inserted {
                excludeBase64.append(id)
            }
            let excludeIds: [Data] = excludeBase64.compactMap { Data(base64Encoded: $0) }

            Task {
                do {
                    let credentialId = try await provider.createPasskey(
                        excludeCredentialIds: excludeIds
                    )
                    let credentialIdBase64 = credentialId.base64EncodedString()
                    // Persist into the synced keychain so future
                    // createPasskey calls (including post-uninstall) see
                    // this ID in their excludeCredentials list.
                    KnownCredentialsStore.add(credentialId: credentialIdBase64, rpId: rpId)
                    call.resolve(["credentialId": credentialIdBase64])
                } catch {
                    call.reject(error.localizedDescription, errorCode(error))
                }
            }
        } else {
            call.reject("Passkey PRF requires iOS 18.0+", "PRF_NOT_SUPPORTED")
        }
    }

    @objc func getKnownCredentialIds(_ call: CAPPluginCall) {
        if #available(iOS 18.0, *) {
            let rpId = call.getString("rpId") ?? "keys.breez.technology"
            let ids = KnownCredentialsStore.read(rpId: rpId)
            call.resolve(["credentialIds": ids])
        } else {
            call.resolve(["credentialIds": [String]()])
        }
    }

    @objc func clearKnownCredentialIds(_ call: CAPPluginCall) {
        if #available(iOS 18.0, *) {
            let rpId = call.getString("rpId") ?? "keys.breez.technology"
            KnownCredentialsStore.clear(rpId: rpId)
        }
        call.resolve()
    }

    @objc func derivePrfSeed(_ call: CAPPluginCall) {
        guard let salt = call.getString("salt") else {
            call.reject("Missing required parameter: salt", "INVALID_ARGUMENT")
            return
        }

        if #available(iOS 18.0, *) {
            let provider = makeProvider(call)
            Task {
                do {
                    let seedData = try await provider.derivePrfSeed(salt: salt)
                    call.resolve(["seed": seedData.base64EncodedString()])
                } catch {
                    call.reject(error.localizedDescription, errorCode(error))
                }
            }
        } else {
            call.reject("Passkey PRF requires iOS 18.0+", "PRF_NOT_SUPPORTED")
        }
    }

    @objc func checkDomainAssociation(_ call: CAPPluginCall) {
        if #available(iOS 18.0, *) {
            let provider = makeProvider(call)
            Task {
                do {
                    let result = try await provider.checkDomainAssociation()
                    call.resolve(Self.domainAssociationToDict(result))
                } catch {
                    // The SDK's checkDomainAssociation is documented to never
                    // throw — it maps verification-level failures to .skipped.
                    // Still, catch defensively so a future change in the SDK
                    // contract doesn't crash the bridge.
                    call.reject(error.localizedDescription, errorCode(error))
                }
            }
        } else {
            // Passkey PRF itself requires iOS 18+; on older iOS the app
            // will never reach this code path. Return Skipped to preserve
            // API parity if some caller probes anyway.
            call.resolve([
                "kind": "Skipped",
                "reason": "iOS version below 18.0; passkey PRF is unsupported"
            ])
        }
    }

    /// Serialize a `DomainAssociation` enum into the JSON-shaped dict the
    /// Capacitor bridge expects. Mirrors the TypeScript `DomainAssociation`
    /// tagged-union one-to-one.
    @available(iOS 18.0, *)
    private static func domainAssociationToDict(
        _ result: DomainAssociation
    ) -> [String: Any] {
        switch result {
        case .associated:
            return ["kind": "Associated"]
        case .notAssociated(let source, let reason):
            return [
                "kind": "NotAssociated",
                "source": source,
                "reason": reason,
            ]
        case .skipped(let reason):
            return [
                "kind": "Skipped",
                "reason": reason,
            ]
        }
    }

    @available(iOS 18.0, *)
    private func makeProvider(_ call: CAPPluginCall) -> PasskeyProvider {
        let rpId = call.getString("rpId") ?? "keys.breez.technology"
        // Constrain assertion to credential IDs we've registered for this
        // RP (read from the iCloud-synced keychain). This guarantees
        // deterministic seed derivation: iOS will only sign with one of
        // our tracked credentials, never picking a sibling that happens
        // to share the RP and produces a different PRF output.
        let allowedIds: [Data] = KnownCredentialsStore.read(rpId: rpId)
            .compactMap { Data(base64Encoded: $0) }
        let provider = PasskeyProvider(
            rpId: rpId,
            rpName: call.getString("rpName") ?? "Glow",
            userName: call.getString("userName"),
            userDisplayName: call.getString("userDisplayName"),
            autoRegister: call.getBool("autoRegister") ?? true,
            allowCredentialIds: allowedIds
        )
        // Capture-on-sign-in: every time the user successfully asserts
        // with a passkey we don't already track, append it to the
        // synced keychain. Migrates users whose passkey predates our
        // tracking (e.g. installed an older Glow version that didn't
        // write KnownCredentialsStore entries) — after their first
        // sign-in here, future create attempts hit the platform-level
        // "already exists" refusal correctly.
        provider.onAssertionCredentialId = { credentialId in
            let base64 = credentialId.base64EncodedString()
            KnownCredentialsStore.add(credentialId: base64, rpId: rpId)
        }
        return provider
    }

    private func errorCode(_ error: Error) -> String {
        guard let prfError = error as? PasskeyPrfError else { return "UNKNOWN_ERROR" }
        switch prfError {
        case .PrfNotSupported: return "PRF_NOT_SUPPORTED"
        case .UserCancelled: return "USER_CANCELLED"
        case .CredentialNotFound: return "CREDENTIAL_NOT_FOUND"
        case .AuthenticationFailed: return "AUTHENTICATION_FAILED"
        case .PrfEvaluationFailed: return "PRF_EVALUATION_FAILED"
        case .Configuration: return "CONFIGURATION_ERROR"
        case .Generic: return "GENERIC_ERROR"
        }
    }
}
