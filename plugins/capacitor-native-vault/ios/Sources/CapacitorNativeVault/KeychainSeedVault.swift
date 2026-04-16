import Foundation
import LocalAuthentication
import Security

/// `SeedVaultProviding` implementation backed by the iOS Keychain.
///
/// Stores the seed blob as a generic password item keyed by `(service,
/// account)` and protected by a `SecAccessControl` with two flags:
///
///   - `.biometryCurrentSet` — the item is readable only after a
///     successful biometric auth against the CURRENT enrollment. Adding
///     a new Face ID / fingerprint voids the item automatically (Apple's
///     recommended failsafe pattern for high-value credentials).
///   - Accessibility class `whenUnlockedThisDeviceOnly` — the item is
///     unreadable while the device is locked, never syncs to iCloud,
///     and is NOT included in encrypted iTunes / Finder backups.
///
/// F3 (this commit) moved from a plain `kSecAttrAccessible` to this
/// `SecAccessControl` construction. As a result:
///
///   - The biometric is now BOUND to the Keychain operation at the OS
///     layer. `SecItemCopyMatching` automatically triggers `LAContext`
///     and returns `errSecUserCanceled` / `errSecAuthFailed` if the
///     user dismisses or fails the prompt. The plugin class no longer
///     needs a separate `BiometricAuthProviding.authenticate` pre-step
///     on iOS.
///   - `storeSeed` also goes through the biometric gate. The first F3
///     store prompts the user for biometric auth to encrypt the item
///     with the new SecAccessControl. Subsequent reads require
///     biometric — no cryptographic operation against this item can
///     happen without a fresh biometric.
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
        // Suppress the biometric prompt during a presence check — we
        // only care whether the item exists, not whether the user can
        // unlock it right now. `kSecUseAuthenticationUI = .fail` makes
        // Keychain return `errSecInteractionNotAllowed` for items gated
        // by a biometric-bound SecAccessControl instead of triggering
        // a prompt.
        query[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail

        let status = SecItemCopyMatching(query as CFDictionary, nil)
        // Both `errSecSuccess` AND `errSecInteractionNotAllowed` mean
        // "an entry exists". `errSecInteractionNotAllowed` is the F3
        // case: the item is present but gated by biometric, and we
        // asked Keychain not to prompt.
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    func storeSeed(_ seed: String) -> SeedVaultResult<Void> {
        guard let data = seed.data(using: .utf8) else {
            return .error(.unknown, "Failed to encode seed payload as UTF-8")
        }

        guard let accessControl = makeAccessControl() else {
            return .error(.unknown, "Failed to create SecAccessControl for Keychain item")
        }

        // Overwrite-on-store semantics: delete any existing entry first,
        // then add. We deliberately do NOT use `SecItemUpdate` here
        // because we need to rebuild the SecAccessControl from scratch
        // every time. In particular, migrating from an F2 item (no
        // access control) to an F3 item (with `.biometryCurrentSet`)
        // needs a fresh `SecItemAdd` — updates can't add an access
        // control attribute that wasn't present on the original item.
        let deleteStatus = SecItemDelete(baseQuery() as CFDictionary)
        if deleteStatus != errSecSuccess && deleteStatus != errSecItemNotFound {
            return .error(
                mapKeychainStatus(deleteStatus),
                "Keychain SecItemDelete (pre-write) failed (status \(deleteStatus))"
            )
        }

        var addQuery = baseQuery()
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessControl as String] = accessControl
        // Note: we do NOT set `kSecAttrAccessible` here. When
        // `kSecAttrAccessControl` is present, the accessibility class
        // is encoded in the access control object itself, and setting
        // both returns `errSecParam` on some iOS versions.

        // Provide a LAContext with our custom prompt copy so the
        // first-time store shows the right label.
        let writeContext = LAContext()
        writeContext.localizedReason = "Protect your Glow wallet with biometric unlock"
        addQuery[kSecUseAuthenticationContext as String] = writeContext

        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        if addStatus == errSecSuccess {
            return .ok(())
        }
        return .error(mapKeychainStatus(addStatus), "Keychain SecItemAdd failed (status \(addStatus))")
    }

    func retrieveSeed() -> SeedVaultResult<String> {
        // Pre-flight: probe LAContext.canEvaluatePolicy so we can
        // distinguish "biometry is genuinely unavailable" (e.g. the user
        // denied the NSFaceIDUsageDescription system permission prompt)
        // from "user cancelled the prompt this time". Without this, the
        // Keychain call returns errSecUserCanceled in BOTH cases and we
        // map it to .userCancelled — which in glow-web leads to the
        // UnlockPage sitting silently, since cancellation is normally a
        // retry-by-tapping state. The user would tap Unlock and nothing
        // would happen because the biometric evaluation can't get off
        // the ground.
        let readContext = LAContext()
        readContext.localizedReason = "Unlock your Glow wallet"

        var policyError: NSError?
        let canEvaluate = readContext.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &policyError
        )
        if !canEvaluate {
            let code = mapLAPolicyError(policyError)
            return .error(
                code,
                policyError?.localizedDescription ?? "Biometric authentication unavailable"
            )
        }

        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        // F3: the biometric prompt is triggered inline by the Keychain
        // call itself. Provide the pre-validated LAContext so users see
        // "Unlock your Glow wallet" instead of the default generic
        // system text.
        query[kSecUseAuthenticationContext as String] = readContext

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
        // Deletion is allowed without biometric auth — `SecItemDelete`
        // does not evaluate the item's access control.
        let status = SecItemDelete(baseQuery() as CFDictionary)
        if status == errSecSuccess || status == errSecItemNotFound {
            return .ok(())
        }
        return .error(mapKeychainStatus(status), "Keychain SecItemDelete failed (status \(status))")
    }

    /// Build the F3 access control: the item is readable only after a
    /// successful biometric auth against the CURRENT enrollment, and
    /// unreadable while the device is locked. `whenUnlockedThisDeviceOnly`
    /// also prevents iCloud Keychain sync and encrypted-backup inclusion.
    private func makeAccessControl() -> SecAccessControl? {
        var error: Unmanaged<CFError>?
        let ac = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            [.biometryCurrentSet],
            &error
        )
        return ac
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

/// Map an `LAError` surfaced by `LAContext.canEvaluatePolicy` to a
/// `NativeVaultErrorCode`. Duplicated here (rather than imported from
/// `LocalAuthBiometricAuth.swift`'s file-private helper) so
/// `KeychainSeedVault` can do its own pre-flight biometric probe
/// without coupling the two implementations together.
private func mapLAPolicyError(_ error: NSError?) -> NativeVaultErrorCode {
    guard let nsError = error, nsError.domain == LAErrorDomain else {
        return .biometricUnavailable
    }
    guard let laError = LAError.Code(rawValue: nsError.code) else {
        return .biometricUnavailable
    }
    switch laError {
    case .biometryNotEnrolled:
        return .biometricNotEnrolled
    case .biometryLockout:
        return .biometricLockout
    case .biometryNotAvailable, .passcodeNotSet:
        // biometryNotAvailable is what the OS returns after the user
        // denies the NSFaceIDUsageDescription prompt — the policy
        // genuinely cannot be evaluated until they re-grant permission
        // in Settings → Glow → Face ID.
        return .biometricUnavailable
    default:
        return .biometricUnavailable
    }
}

/// Map a Keychain `OSStatus` to a `NativeVaultErrorCode`.
///
/// F3 changes the set of codes we see in practice: since the biometric
/// prompt now happens inside `SecItemCopyMatching`, user-cancel and
/// auth-failed outcomes arrive as `OSStatus` codes instead of as
/// `LAError` values through a separate `evaluatePolicy` call.
private func mapKeychainStatus(_ status: OSStatus) -> NativeVaultErrorCode {
    switch status {
    case errSecUserCanceled:
        // User dismissed the biometric prompt (tapped Cancel, turned
        // away from Face ID long enough, or removed their finger from
        // the Touch ID sensor).
        return .userCancelled
    case errSecAuthFailed:
        // Biometric was shown and evaluated but was rejected (bad
        // fingerprint match, bad face match, too many attempts). We
        // surface this as `userCancelled` so the caller falls through
        // to the welcome / Unlock screen where the user can retry.
        return .userCancelled
    case errSecInteractionNotAllowed:
        // Either the device is locked, the app is backgrounded and
        // cannot show a prompt, or we explicitly asked Keychain not
        // to prompt (hasStoredSeed's presence-only check).
        return .biometricUnavailable
    case errSecDecode:
        // Stored value can't be decoded — treat as invalidated so the
        // caller wipes and re-onboards.
        return .keyInvalidated
    default:
        return .unknown
    }
}
