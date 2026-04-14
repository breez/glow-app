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
            Task {
                do {
                    try await provider.createPasskey()
                    call.resolve()
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

    @available(iOS 18.0, *)
    private func makeProvider(_ call: CAPPluginCall) -> PlatformPasskeyPrfProvider {
        PlatformPasskeyPrfProvider(
            rpId: call.getString("rpId") ?? "keys.breez.technology",
            rpName: call.getString("rpName") ?? "Glow",
            userName: call.getString("userName"),
            userDisplayName: call.getString("userDisplayName")
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
        case .Generic: return "GENERIC_ERROR"
        }
    }
}
