import BreezSdkSpark
import Capacitor
import Foundation

/// Capacitor bridge over `BreezSdkSpark.PasskeyClient`.
///
/// Owns a single `PasskeyClient` per session (built in `initialize`)
/// plus a process-lifetime `KeychainCredentialRegistry`.
@objc(PasskeyPlugin)
public class PasskeyPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PasskeyPlugin"
    public let jsName = "Passkey"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "initialize", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkAvailability", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "register", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "signIn", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connectWithPasskey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listLabels", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "storeLabel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getKnownCredentialIds", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeKnownCredentialId", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearKnownCredentialIds", returnType: CAPPluginReturnPromise),
    ]

    private let registry: Any? = {
        if #available(iOS 18.0, *) { return KeychainCredentialRegistry() }
        return nil
    }()
    private var client: PasskeyClient?
    /// RP the client was initialized with; needed to key the registry.
    private var rpId: String?

    /// RP IDs this app may run a ceremony against. Must stay in step with
    /// the `webcredentials:` entries in App.entitlements (and Android's
    /// assetlinks.json): a domain here the app is not associated with
    /// dead-ends the OS sheet.
    private static let allowedRpIds: Set<String> = ["keys.breez.technology"]

    @objc func initialize(_ call: CAPPluginCall) {
        guard #available(iOS 18.0, *) else {
            call.reject("Passkey requires iOS 18.0+", "PRF_NOT_SUPPORTED")
            return
        }
        guard let rpId = call.getString("rpId"), let rpName = call.getString("rpName") else {
            call.reject("rpId and rpName are required", "INVALID_ARGUMENT")
            return
        }
        // The RP is ours to decide, not the WebView's. Settle it here, before
        // a ceremony can put a domain in front of the user.
        guard Self.allowedRpIds.contains(rpId) else {
            call.reject("rpId '\(rpId)' is not an associated domain of this app", "INVALID_ARGUMENT")
            return
        }
        self.rpId = rpId
        let provider = PasskeyProvider(
            options: PasskeyProviderOptions(
                rpId: rpId,
                rpName: rpName,
                userName: call.getString("userName"),
                userDisplayName: call.getString("userDisplayName")
            )
        )
        let config = call.getString("defaultLabel").map { PasskeyConfig(defaultLabel: $0) }
        do {
            client = try PasskeyClient(
                prfProvider: provider, breezApiKey: call.getString("breezApiKey"), config: config)
        } catch {
            call.reject(error.localizedDescription, errorCode(error))
            return
        }
        call.resolve()
    }

    @objc func checkAvailability(_ call: CAPPluginCall) {
        guard let c = client else {
            call.resolve(["type": "prfUnsupported"])
            return
        }
        Task {
            do {
                call.resolve(Self.encodeAvailability(try await c.checkAvailability()))
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func register(_ call: CAPPluginCall) {
        guard let c = client else {
            call.reject("Plugin not initialized", "NOT_INITIALIZED")
            return
        }
        let request = RegisterRequest(
            label: call.getString("label"),
            excludeCredentials: parseB64Array(call, key: "excludeCredentials")
        )
        Task {
            do {
                let response = try await c.register(request: request)
                await self.recordCredential(response.credential)
                var out: [String: Any] = ["wallet": Self.encodeWallet(response.wallet)]
                out["credential"] = response.credential.map { Self.encodeCredential($0) } ?? NSNull()
                call.resolve(out)
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func signIn(_ call: CAPPluginCall) {
        guard let c = client else {
            call.reject("Plugin not initialized", "NOT_INITIALIZED")
            return
        }
        let request = SignInRequest(
            label: call.getString("label"),
            allowCredentials: parseB64Array(call, key: "allowCredentials"),
            preferImmediatelyAvailableCredentials: call.getBool("preferImmediatelyAvailableCredentials")
        )
        Task {
            do {
                let response = try await c.signIn(request: request)
                await self.recordCredential(response.credential)
                var out: [String: Any] = [
                    "wallet": Self.encodeWallet(response.wallet),
                    "labels": response.labels,
                ]
                out["credentialId"] = response.credential?.credentialId.base64EncodedString() ?? NSNull()
                call.resolve(out)
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func connectWithPasskey(_ call: CAPPluginCall) {
        guard let c = client else {
            call.reject("Plugin not initialized", "NOT_INITIALIZED")
            return
        }
        let request = ConnectWithPasskeyRequest(
            label: call.getString("label"),
            allowCredentials: parseB64Array(call, key: "allowCredentials"),
            excludeCredentials: parseB64Array(call, key: "excludeCredentials")
        )
        Task {
            do {
                let response = try await c.connectWithPasskey(request: request)
                await self.recordCredential(response.credential)
                var out: [String: Any] = [
                    "wallet": Self.encodeWallet(response.wallet),
                    // Discovery labels for the returning multi-wallet case;
                    // empty on the register path (glow-web#309).
                    "labels": response.labels,
                ]
                if let cred = response.credential {
                    out["registeredCredential"] = Self.encodeCredential(cred)
                } else {
                    out["registeredCredential"] = NSNull()
                }
                call.resolve(out)
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func listLabels(_ call: CAPPluginCall) {
        guard let c = client else {
            call.reject("Plugin not initialized", "NOT_INITIALIZED")
            return
        }
        Task {
            do {
                call.resolve(["labels": try await c.labels().list()])
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func storeLabel(_ call: CAPPluginCall) {
        guard let label = call.getString("label") else {
            call.reject("Missing required parameter: label", "INVALID_ARGUMENT")
            return
        }
        guard let c = client else {
            call.reject("Plugin not initialized", "NOT_INITIALIZED")
            return
        }
        Task {
            do {
                try await c.labels().store(label: label)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func getKnownCredentialIds(_ call: CAPPluginCall) {
        guard #available(iOS 18.0, *),
              let reg = registry as? KeychainCredentialRegistry,
              let rp = rpId else {
            call.resolve(["credentialIds": [String]()])
            return
        }
        Task {
            do {
                let ids = try await reg.read(rpId: rp)
                call.resolve(["credentialIds": ids.map { $0.base64EncodedString() }])
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func removeKnownCredentialId(_ call: CAPPluginCall) {
        guard let idString = call.getString("credentialId"), !idString.isEmpty,
              let id = Data(base64Encoded: idString) else {
            call.reject("Missing or invalid credentialId", "INVALID_ARGUMENT")
            return
        }
        guard #available(iOS 18.0, *),
              let reg = registry as? KeychainCredentialRegistry,
              let rp = rpId else {
            call.resolve()
            return
        }
        Task {
            do {
                try await reg.remove(rpId: rp, credentialId: id)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    @objc func clearKnownCredentialIds(_ call: CAPPluginCall) {
        guard #available(iOS 18.0, *),
              let reg = registry as? KeychainCredentialRegistry,
              let rp = rpId else {
            call.resolve()
            return
        }
        Task {
            do {
                try await reg.clear(rpId: rp)
                call.resolve()
            } catch {
                call.reject(error.localizedDescription, errorCode(error))
            }
        }
    }

    /// Best-effort: record a credential ID in the synced store.
    private func recordCredential(_ cred: PasskeyCredential?) async {
        guard #available(iOS 18.0, *),
              let cred,
              let reg = registry as? KeychainCredentialRegistry,
              let rp = rpId else { return }
        try? await reg.add(rpId: rp, credentialId: cred.credentialId)
    }

    private func parseB64Array(_ call: CAPPluginCall, key: String) -> [Data] {
        (call.getArray(key) as? [String] ?? []).compactMap { Data(base64Encoded: $0) }
    }

    private static func encodeWallet(_ wallet: Wallet) -> [String: Any] {
        return [
            "seed": encodeSeed(wallet.seed),
            "label": wallet.label,
        ]
    }

    private static func encodeSeed(_ seed: Seed) -> [String: Any] {
        switch seed {
        case .mnemonic(let mnemonic, let passphrase):
            var out: [String: Any] = ["type": "mnemonic", "mnemonic": mnemonic]
            out["passphrase"] = passphrase ?? NSNull()
            return out
        case .entropy(let data):
            return ["type": "entropy", "entropy": data.base64EncodedString()]
        }
    }

    private static func encodeCredential(_ cred: PasskeyCredential) -> [String: Any] {
        var out: [String: Any] = [
            "credentialId": cred.credentialId.base64EncodedString(),
        ]
        out["userId"] = cred.userId?.base64EncodedString() ?? NSNull()
        out["aaguid"] = cred.aaguid?.base64EncodedString() ?? NSNull()
        out["backupEligible"] = cred.backupEligible ?? NSNull()
        return out
    }

    private static func encodeAvailability(_ a: PasskeyAvailability) -> [String: Any] {
        switch a {
        case .available: return ["type": "available"]
        case .prfUnsupported: return ["type": "prfUnsupported"]
        case .notAssociated(let source, let reason):
            return ["type": "notAssociated", "source": source, "reason": reason]
        case .skipped(let reason):
            return ["type": "skipped", "reason": reason]
        }
    }

    private func errorCode(_ error: Error) -> String {
        if let passkey = error as? PasskeyError {
            if case .Prf(let inner) = passkey { return prfErrorCode(inner) }
            switch passkey {
            case .Prf: return "GENERIC_ERROR"  // unreachable; handled above
            case .RelayConnectionFailed: return "RELAY_CONNECTION_FAILED"
            case .NostrWriteFailed: return "NOSTR_WRITE_FAILED"
            case .NostrReadFailed: return "NOSTR_READ_FAILED"
            case .KeyDerivationError: return "KEY_DERIVATION_ERROR"
            case .InvalidPrfOutput: return "INVALID_PRF_OUTPUT"
            case .MnemonicError: return "MNEMONIC_ERROR"
            case .InvalidSalt: return "INVALID_SALT"
            // A passkey exists on the device even though the ceremony
            // failed. Its own code so the web layer signs in with it
            // rather than offering a create that would strand it.
            case .CreatedButNotDerived: return "CREATED_BUT_NOT_DERIVED"
            case .InvalidConfig: return "CONFIGURATION_ERROR"
            case .Generic: return "GENERIC_ERROR"
            }
        }
        if let prf = error as? PrfProviderError { return prfErrorCode(prf) }
        return "UNKNOWN_ERROR"
    }

    private func prfErrorCode(_ prf: PrfProviderError) -> String {
        switch prf {
        case .PrfNotSupported: return "PRF_NOT_SUPPORTED"
        case .UserCancelled: return "USER_CANCELLED"
        case .UserTimedOut: return "USER_TIMED_OUT"
        case .CredentialNotFound: return "CREDENTIAL_NOT_FOUND"
        case .AuthenticationFailed: return "AUTHENTICATION_FAILED"
        case .PrfEvaluationFailed: return "PRF_EVALUATION_FAILED"
        case .Configuration: return "CONFIGURATION_ERROR"
        case .CredentialAlreadyExists: return "CREDENTIAL_ALREADY_EXISTS"
        case .Generic: return "GENERIC_ERROR"
        }
    }
}
