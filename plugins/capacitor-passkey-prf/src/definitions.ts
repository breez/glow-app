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
   */
  createPasskey(options: {
    rpId?: string;
    rpName?: string;
    userName?: string;
    userDisplayName?: string;
  }): Promise<void>;

  /**
   * Derive a 32-byte seed from passkey PRF with the given salt.
   * If no credential exists, auto-registers one first.
   * Triggers one or two biometric/passkey prompts.
   *
   * @returns Base64-encoded 32-byte seed.
   */
  derivePrfSeed(options: {
    rpId?: string;
    salt: string;
  }): Promise<{ seed: string }>;

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
}
