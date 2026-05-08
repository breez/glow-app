import Capacitor
import Foundation
import BreezSdkSpark

/// GPM indexes new passkeys asynchronously: an immediate assertion
/// fires before the credential is discoverable and the picker omits
/// GPM. Hold the next derive call when GPM was the registering
/// provider. Mirrors `POST_CREATE_GRACE_TOTAL_MS` in the Android
/// plugin.
private actor PostCreateGraceTracker {
    private var deadline: Date?

    func arm(after interval: TimeInterval) {
        deadline = Date().addingTimeInterval(interval)
    }

    func consume() async {
        guard let d = deadline else { return }
        deadline = nil
        let remaining = d.timeIntervalSinceNow
        if remaining > 0 {
            try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
        }
    }
}

@objc(PasskeyPrfPlugin)
public class PasskeyPrfPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PasskeyPrfPlugin"
    public let jsName = "PasskeyPrf"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isPrfAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createPasskey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "derivePrfSeed", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "derivePrfSeeds", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeKnownCredentialId", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkDomainAssociation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getKnownCredentialIds", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearKnownCredentialIds", returnType: CAPPluginReturnPromise),
    ]

    private let graceTracker = PostCreateGraceTracker()

    /// AAGUID `ea9b8d66-4d01-1d21-3ce4-b6b48cb575d4`.
    private static let gpmAaguid: Data = Data([
        0xea, 0x9b, 0x8d, 0x66, 0x4d, 0x01, 0x1d, 0x21,
        0x3c, 0xe4, 0xb6, 0xb4, 0x8c, 0xb5, 0x75, 0xd4,
    ])

    private static let postCreateGraceTotal: TimeInterval = 0.8

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
                    let registered = try await provider.createPasskey(
                        excludeCredentialIds: excludeIds
                    )
                    let credentialIdBase64 = registered.credentialId.base64EncodedString()
                    // Persist synchronously so the next derivePrfSeed call
                    // sees the new ID in allowCredentialIds and iOS auto-routes
                    // to the registering provider instead of showing a picker.
                    KnownCredentialsStore.add(credentialId: credentialIdBase64, rpId: rpId)
                    if let aaguid = registered.aaguid, aaguid == Self.gpmAaguid {
                        await graceTracker.arm(after: Self.postCreateGraceTotal)
                    }
                    call.resolve([
                        "credentialId": credentialIdBase64,
                        "aaguid": registered.aaguid?.base64EncodedString() as Any,
                        "backupEligible": registered.backupEligible as Any,
                    ])
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

    @objc func removeKnownCredentialId(_ call: CAPPluginCall) {
        guard let credentialId = call.getString("credentialId"), !credentialId.isEmpty else {
            call.reject("Missing required parameter: credentialId", "INVALID_ARGUMENT")
            return
        }
        if #available(iOS 18.0, *) {
            let rpId = call.getString("rpId") ?? "keys.breez.technology"
            KnownCredentialsStore.remove(credentialId: credentialId, rpId: rpId)
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
            // Capture the asserted credential ID so the result can carry
            // it back to JS. The plugin caller (glow-web) uses this to
            // update per-cred metadata (last-sign-in timestamp, active
            // cred pin) on every successful sign-in, mirroring the web
            // SDK's onAssertionCredentialId path. Wraps the existing
            // KnownCredentialsStore.add callback set in makeProvider.
            let credIdBox = AssertedCredIdBox()
            let originalCallback = provider.onAssertionCredentialId
            provider.onAssertionCredentialId = { credentialId in
                credIdBox.set(credentialId.base64EncodedString())
                originalCallback?(credentialId)
            }
            Task {
                do {
                    await graceTracker.consume()
                    let seedData = try await provider.derivePrfSeed(salt: salt)
                    let credIdValue: Any = credIdBox.get() ?? NSNull()
                    call.resolve([
                        "seed": seedData.base64EncodedString(),
                        "credentialId": credIdValue,
                    ])
                } catch {
                    call.reject(error.localizedDescription, errorCode(error))
                }
            }
        } else {
            call.reject("Passkey PRF requires iOS 18.0+", "PRF_NOT_SUPPORTED")
        }
    }

    @objc func derivePrfSeeds(_ call: CAPPluginCall) {
        guard let salts = call.getArray("salts", String.self), !salts.isEmpty else {
            call.reject("Missing or empty required parameter: salts", "INVALID_ARGUMENT")
            return
        }

        if #available(iOS 18.0, *) {
            let provider = makeProvider(call)
            // See derivePrfSeed: capture the asserted credentialId for
            // the response so glow-web can keep per-cred metadata in
            // sync. Bulk PRF runs one assertion per pair of salts, but
            // they all share the same credential, so a single credId
            // covers the whole batch.
            let credIdBox = AssertedCredIdBox()
            let originalCallback = provider.onAssertionCredentialId
            provider.onAssertionCredentialId = { credentialId in
                credIdBox.set(credentialId.base64EncodedString())
                originalCallback?(credentialId)
            }
            Task {
                do {
                    await graceTracker.consume()
                    let outputs = try await provider.derivePrfSeeds(salts: salts)
                    let base64 = outputs.map { $0.base64EncodedString() }
                    let credIdValue: Any = credIdBox.get() ?? NSNull()
                    call.resolve([
                        "seeds": base64,
                        "credentialId": credIdValue,
                    ])
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
        // Caller-supplied allowCredentialIds let glow-web pin follow-up
        // sign-in derives (listLabels, saveLabel, label switch) to the
        // active cred so iOS auto-picks via
        // preferImmediatelyAvailableCredentials when only one matches.
        // Empty / absent → fully discoverable (initial sign-in).
        let allowedIds: [Data] = (call.getArray("allowCredentialIds") as? [String] ?? [])
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
            // Detach: this callback fires inside the assertion delegate
            // which then resumes the continuation that drives the JS
            // promise. A blocking Keychain write here is on the
            // user-perceived critical path. Add() is idempotent so
            // ordering against subsequent calls doesn't matter.
            Task.detached(priority: .utility) {
                KnownCredentialsStore.add(credentialId: base64, rpId: rpId)
            }
        }
        return provider
    }

    /// Tiny reference holder for the credentialId captured during a
    /// derive ceremony. The SDK's `onAssertionCredentialId` closure
    /// fires synchronously inside the assertion delegate, before the
    /// awaiting Task resumes from `derivePrfSeed(salt:)`. Using a
    /// reference type avoids the value-semantics gotcha of capturing
    /// a `var` from an escaping closure, and the explicit lock keeps
    /// the read+write atomic if the SDK ever moves the callback off
    /// the assertion thread.
    private final class AssertedCredIdBox {
        private let lock = NSLock()
        private var _value: String?
        func set(_ value: String) {
            lock.lock(); defer { lock.unlock() }
            _value = value
        }
        func get() -> String? {
            lock.lock(); defer { lock.unlock() }
            return _value
        }
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
        case .CredentialAlreadyExists: return "CREDENTIAL_ALREADY_EXISTS"
        case .Generic: return "GENERIC_ERROR"
        }
    }
}
