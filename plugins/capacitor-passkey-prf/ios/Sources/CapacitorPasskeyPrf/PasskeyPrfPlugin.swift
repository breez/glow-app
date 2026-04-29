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

            var excludeIds: [Data] = []
            if let base64Array = call.getArray("excludeCredentialIds", String.self) {
                excludeIds = base64Array.compactMap { Data(base64Encoded: $0) }
            }

            Task {
                do {
                    let credentialId = try await provider.createPasskey(
                        excludeCredentialIds: excludeIds
                    )
                    call.resolve(["credentialId": credentialId.base64EncodedString()])
                } catch {
                    call.reject(error.localizedDescription, errorCode(error))
                }
            }
        } else {
            call.reject("Passkey PRF requires iOS 18.0+", "PRF_NOT_SUPPORTED")
        }
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
        PasskeyProvider(
            rpId: call.getString("rpId") ?? "keys.breez.technology",
            rpName: call.getString("rpName") ?? "Glow",
            userName: call.getString("userName"),
            userDisplayName: call.getString("userDisplayName"),
            autoRegister: call.getBool("autoRegister") ?? true
        )
    }

    private func errorCode(_ error: Error) -> String {
        guard let prfError = error as? PasskeyPrfError else { return "UNKNOWN_ERROR" }
        switch prfError {
        case .PrfNotSupported: return "PRF_NOT_SUPPORTED"
        case .UserCancelled: return "USER_CANCELLED"
        case .CredentialNotFound: return "CREDENTIAL_NOT_FOUND"
        case .AuthenticationFailed: return "AUTHENTICATION_FAILED"
        case .PrfEvaluationFailed: return "PRF_EVALUATION_FAILED"
        case .ConfigurationError: return "CONFIGURATION_ERROR"
        case .Generic: return "GENERIC_ERROR"
        }
    }
}
