import Foundation
import Security

/// iCloud-synced Keychain-backed store of known passkey credential IDs.
/// One generic-password item per RP, holding a JSON-encoded array of
/// base64-encoded credential IDs.
///
/// `kSecAttrSynchronizable: kCFBooleanTrue` opts the entry into iCloud
/// Keychain sync so it survives reinstall and replicates across the
/// user's signed-in devices. Service identifier kept stable
/// (`breez.spark.passkey.knownCredentials`) so existing keychain
/// entries are preserved.
@available(iOS 18.0, macOS 15.0, *)
public struct KeychainCredentialRegistry {
    private let service: String

    public init(service: String = "breez.spark.passkey.knownCredentials") {
        self.service = service
    }

    public func read(rpId: String) async throws -> [Data] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: rpId,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecReturnData as String: true,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainCredentialRegistryError.osStatus(status)
        }
        guard let array = try JSONSerialization.jsonObject(with: data) as? [String] else {
            return []
        }
        return array.compactMap { Data(base64Encoded: $0) }
    }

    public func add(rpId: String, credentialId: Data) async throws {
        var ids = (try? await read(rpId: rpId)) ?? []
        // Dedupe: identical credential bytes already present means no-op.
        if ids.contains(credentialId) { return }
        ids.append(credentialId)
        try write(rpId: rpId, ids: ids)
    }

    public func remove(rpId: String, credentialId: Data) async throws {
        let ids = (try? await read(rpId: rpId)) ?? []
        let updated = ids.filter { $0 != credentialId }
        if updated.isEmpty {
            try await clear(rpId: rpId)
        } else {
            try write(rpId: rpId, ids: updated)
        }
    }

    public func clear(rpId: String) async throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: rpId,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
        ]
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            throw KeychainCredentialRegistryError.osStatus(status)
        }
    }

    private func write(rpId: String, ids: [Data]) throws {
        let blob = try JSONSerialization.data(
            withJSONObject: ids.map { $0.base64EncodedString() }
        )
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: rpId,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
        ]
        let attrs: [String: Any] = [
            kSecValueData as String: blob,
            kSecAttrSynchronizable as String: kCFBooleanTrue as Any,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if updateStatus == errSecSuccess { return }
        if updateStatus != errSecItemNotFound {
            throw KeychainCredentialRegistryError.osStatus(updateStatus)
        }
        var add = query
        add[kSecValueData as String] = blob
        add[kSecAttrSynchronizable as String] = kCFBooleanTrue as Any
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        if addStatus != errSecSuccess {
            throw KeychainCredentialRegistryError.osStatus(addStatus)
        }
    }
}

public enum KeychainCredentialRegistryError: Error {
    case osStatus(OSStatus)
}
