/**
 * Result of a domain-association verification check against the platform's
 * out-of-band domain verification source (iOS AASA / Android assetlinks).
 *
 * Passkey operations on iOS and Android both depend on platform-level
 * domain verification that caches independently of the app. When the
 * verification is missing or stale, subsequent WebAuthn ceremonies fail
 * with opaque platform errors that callers cannot reliably distinguish
 * from "no credential found" or "user cancelled".
 *
 * `checkDomainAssociation` lets the caller actively verify the app's
 * identity against the platform's verification source before starting
 * any WebAuthn flow, so UX can gate onboarding/discovery on a reliable
 * signal.
 *
 * Shape mirrors the Rust `DomainAssociation` enum from the SDK so
 * cross-platform callers handle it uniformly.
 */
export type DomainAssociation =
  | { kind: 'Associated' }
  | { kind: 'NotAssociated'; source: string; reason: string }
  | { kind: 'Skipped'; reason: string };

export interface PasskeyPrfPlugin {
  /**
   * Check if native passkey PRF is available on this device.
   * iOS: requires iOS 18.0+. Android: requires API 28+.
   */
  isPrfAvailable(): Promise<{ available: boolean }>;

  /**
   * Register a new passkey with PRF support.
   * Triggers exactly one biometric/passkey prompt.
   *
   * @returns Credential ID plus AAGUID (provider identifier) and the
   *   backup-eligibility flag, all base64-encoded where applicable.
   *   AAGUID and `backupEligible` are null when the platform doesn't
   *   surface enough authenticator data to extract them.
   */
  createPasskey(options: {
    rpId?: string;
    rpName?: string;
    userName?: string;
    userDisplayName?: string;
    excludeCredentialIds?: string[];
  }): Promise<{ credentialId: string; aaguid: string | null; backupEligible: boolean | null }>;

  /**
   * Derive a 32-byte seed from passkey PRF with the given salt.
   * If autoRegister is true (default) and no credential exists,
   * auto-registers one first. Triggers one or two biometric/passkey prompts.
   *
   * @param allowCredentialIds Optional base64-encoded credential IDs to
   *   constrain the assertion. When non-empty, the OS picker shows only
   *   these credentials (single-row auto-pick on iOS via
   *   preferImmediatelyAvailableCredentials). When empty / omitted,
   *   sign-in is fully discoverable.
   *
   * @returns Base64-encoded 32-byte seed.
   */
  derivePrfSeed(options: {
    rpId?: string;
    salt: string;
    autoRegister?: boolean;
    allowCredentialIds?: string[];
  }): Promise<{ seed: string }>;

  /**
   * Bulk derive: collapses multiple PRF salts into as few biometric
   * ceremonies as the authenticator supports (1 ceremony per pair on
   * iOS via dual-salt PRF, sequential elsewhere). Output ordering
   * matches input.
   *
   * Same `allowCredentialIds` semantics as `derivePrfSeed`.
   */
  derivePrfSeeds(options: {
    rpId?: string;
    salts: string[];
    autoRegister?: boolean;
    allowCredentialIds?: string[];
  }): Promise<{ seeds: string[] }>;

  /**
   * Verify the app's identity against the platform's out-of-band domain
   * verification source (iOS AASA CDN / Android Digital Asset Links API).
   *
   * Intended to be called **once per session**, before the first WebAuthn
   * ceremony. Callers should gate their onboarding / discovery UX on the
   * result:
   *
   * - `Associated` → safe to proceed with WebAuthn calls.
   * - `NotAssociated` → surface a dedicated error state; WebAuthn calls
   *   will fail for configuration reasons, not a UX recoverable state.
   * - `Skipped` → verification was not performed (offline, endpoint
   *   timeout, test context). Proceed with WebAuthn as normal — this is
   *   **not** a negative signal.
   *
   * Delegates to the underlying SDK provider's `check_domain_association`
   * method. Never throws — verification-level failures surface as
   * `Skipped`, and domain-misconfiguration surfaces as `NotAssociated`.
   */
  checkDomainAssociation(options?: {
    rpId?: string;
  }): Promise<DomainAssociation>;

  /**
   * Read the persisted list of base64-encoded credential IDs for `rpId`.
   *
   * The list is backed by the platform's synced keychain (iCloud Keychain
   * on iOS, Block Store on Android once implemented) so it survives app
   * uninstall + reinstall. Used by the app to populate
   * `excludeCredentialIds` on subsequent createPasskey calls without
   * relying on localStorage (which is wiped on uninstall).
   *
   * Returns an empty array if storage is missing, invalid, or the RP has
   * never registered a credential on this device.
   */
  getKnownCredentialIds(options?: {
    rpId?: string;
  }): Promise<{ credentialIds: string[] }>;

  /**
   * Clear the persisted list of credential IDs for `rpId`.
   *
   * Called by the deletion-recovery flow when a sign-in attempt returns
   * `CREDENTIAL_NOT_FOUND` for a device that has previously registered:
   * the user has manually deleted the passkey from
   * Settings → Passwords, so the stale list is no longer meaningful.
   */
  clearKnownCredentialIds(options?: {
    rpId?: string;
  }): Promise<void>;
}
