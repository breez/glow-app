import Foundation
import Security

/// `SeedVaultProviding` implementation backed by the iOS Keychain.
///
/// Stores the seed blob as a generic password item keyed by `(service,
/// account)`. The accessibility class is pinned to
/// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, which means:
///
///   - The item is unreadable while the device is locked.
///   - The item never syncs to iCloud Keychain.
///   - The item is NOT included in encrypted iTunes / Finder backups.
///   - Restoring from a different device's backup will NOT restore this
///     item (intentional — the wallet stays on the device that created it).
///
/// In F2 the biometric step happens externally (the plugin calls
/// `BiometricAuthProviding.authenticate` before invoking `retrieveSeed`).
/// F3 will replace the access constant with a `SecAccessControl` that has
/// `.biometryCurrentSet`, so the Keychain itself triggers `LAContext` and
/// the cryptographic operation is bound to a specific biometric
/// enrollment.
final class KeychainSeedVault: SeedVaultProviding {
    /// Service identifier scoped to this app. Stable across builds so a
    /// reinstall finds the same Keychain entries (which `WhenUnlockedThisDeviceOnly`
    /// preserves on iOS).
    private let service = "technology.breez.glow.native-vault"
    private let account = "wallet-seed"

    func hasStoredSeed() -> Bool {
        var query = baseQuery()
        query[kSecReturnData as String] = false
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess
    }

    func storeSeed(_ seed: String) -> SeedVaultResult<Void> {
        guard let data = seed.data(using: .utf8) else {
            return .error(.unknown, "Failed to encode seed payload as UTF-8")
        }

        // First try to update an existing entry. If none exists, fall
        // through to add. Doing it this way (instead of delete-then-add)
        // preserves the access control attributes that may have been
        // tightened in a future migration.
        let updateAttributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(
            baseQuery() as CFDictionary,
            updateAttributes as CFDictionary
        )

        if updateStatus == errSecSuccess {
            return .ok(())
        }

        if updateStatus == errSecItemNotFound {
            var addQuery = baseQuery()
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
            if addStatus == errSecSuccess {
                return .ok(())
            }
            return .error(mapKeychainStatus(addStatus), "Keychain SecItemAdd failed (status \(addStatus))")
        }

        return .error(mapKeychainStatus(updateStatus), "Keychain SecItemUpdate failed (status \(updateStatus))")
    }

    func retrieveSeed() -> SeedVaultResult<String> {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        if status == errSecItemNotFound {
            return .notFound
        }
        if status != errSecSuccess {
            return .error(mapKeychainStatus(status), "Keychain SecItemCopyMatching failed (status \(status))")
        }
        guard let data = item as? Data else {
            return .error(.keyInvalidated, "Keychain returned an unexpected payload type")
        }
        guard let seed = String(data: data, encoding: .utf8) else {
            return .error(.keyInvalidated, "Stored seed payload is not valid UTF-8")
        }
        return .ok(seed)
    }

    func clearSeed() -> SeedVaultResult<Void> {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        if status == errSecSuccess || status == errSecItemNotFound {
            return .ok(())
        }
        return .error(mapKeychainStatus(status), "Keychain SecItemDelete failed (status \(status))")
    }

    /// Common attributes that uniquely identify our single Keychain entry.
    private func baseQuery() -> [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

/// Map a Keychain `OSStatus` to a `NativeVaultErrorCode`. Most failures are
/// mapped to `.unknown` since they shouldn't happen in practice — the only
/// "expected" failure mode is `errSecItemNotFound`, which the callers
/// translate to `.notFound` before reaching here.
private func mapKeychainStatus(_ status: OSStatus) -> NativeVaultErrorCode {
    switch status {
    case errSecAuthFailed, errSecInteractionNotAllowed:
        // The device is locked or the user dismissed an OS auth UI.
        return .userCancelled
    case errSecDecode:
        // Stored value can't be decoded — treat as invalidated so the
        // caller wipes and re-onboards.
        return .keyInvalidated
    default:
        return .unknown
    }
}
