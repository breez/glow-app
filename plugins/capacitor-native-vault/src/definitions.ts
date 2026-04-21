/**
 * NativeVault — first-party Capacitor plugin for biometric-gated seed storage.
 *
 * Replaces the @aparajita/capacitor-secure-storage and
 * @aparajita/capacitor-biometric-auth plugins with an in-house implementation
 * that:
 *
 *   - Lives entirely under code we own (no third-party plugin surface).
 *   - Uses iOS Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`
 *     and Android Keystore with a hardware-backed AES-GCM key for the seed
 *     blob (F2: storage-only; F3 will tighten the key to require user
 *     authentication, binding biometric to the cryptographic operation).
 *   - Has zero transitive dependency on `@capacitor/app`, so it does NOT
 *     trip the `vite-plugin-top-level-await` chunk-boundary bug that
 *     forced the awkward `biometricAuthReadyPromise` workaround in
 *     `glow-web/src/services/secureStorage.ts`.
 *
 * Native-only — there is no web fallback. On web, glow-web's
 * `secureStorage.ts` selects `NoopSecureStorage` and never calls into this
 * plugin.
 */

/**
 * Coarse error codes returned via `PluginCall.reject(code, ...)`. The JS
 * caller maps these onto the existing `SecureStorageErrorCode` set in
 * `glow-web/src/services/secureStorage.ts`.
 */
export type NativeVaultErrorCode =
  | 'USER_CANCELLED'
  | 'BIOMETRIC_LOCKOUT'
  | 'BIOMETRIC_NOT_ENROLLED'
  | 'BIOMETRIC_UNAVAILABLE'
  | 'KEY_INVALIDATED'
  | 'NO_STORED_SEED'
  | 'UNKNOWN';

/**
 * Detected biometry type. Used by JS callers to set retry-button labels
 * ("Unlock with Face ID" vs "Unlock with fingerprint", etc).
 */
export type NativeVaultBiometryType =
  | 'none'
  | 'touchId'
  | 'faceId'
  | 'fingerprint'
  | 'face'
  | 'iris';

export interface CheckBiometryResult {
  /** True if the device has biometric hardware AND at least one credential enrolled. */
  available: boolean;
  /** The detected biometry type. `'none'` when not available. */
  biometryType: NativeVaultBiometryType;
}

export interface HasStoredSeedResult {
  /** True if a seed blob is currently persisted in the native vault. */
  stored: boolean;
}

export interface RetrieveSeedResult {
  /**
   * The exact string passed to `storeSeed`'s `seed` parameter. The JS
   * caller is responsible for any JSON parsing — this plugin treats the
   * payload as opaque bytes.
   */
  seed: string;
}

export interface NativeVaultPlugin {
  /**
   * Inspect the device's biometric capabilities. Does NOT prompt the user.
   * Used by the unlock UI to set a label like "Unlock with Face ID".
   */
  checkBiometry(): Promise<CheckBiometryResult>;

  /** Returns true if a seed blob is currently persisted. Does NOT prompt. */
  hasStoredSeed(): Promise<HasStoredSeedResult>;

  /**
   * Persist a seed blob. The string is treated as opaque bytes — the JS
   * caller is responsible for JSON-encoding any structured data.
   *
   * Does NOT prompt biometric. Storage happens with the device unlock
   * state already held by the OS.
   */
  storeSeed(options: { seed: string }): Promise<void>;

  /**
   * Retrieve the persisted seed blob. Triggers a biometric prompt before
   * returning. Rejects with one of:
   *
   *   - `USER_CANCELLED` if the user dismissed the prompt
   *   - `BIOMETRIC_LOCKOUT` if too many failed attempts
   *   - `BIOMETRIC_NOT_ENROLLED` if no biometric is enrolled
   *   - `BIOMETRIC_UNAVAILABLE` if the hardware is missing/disabled
   *   - `NO_STORED_SEED` if `hasStoredSeed()` would return false
   *   - `KEY_INVALIDATED` if the underlying key was wiped (e.g. new
   *     biometric enrollment on iOS, or `KeyPermanentlyInvalidatedException`
   *     on Android)
   *   - `UNKNOWN` for anything else
   */
  retrieveSeed(): Promise<RetrieveSeedResult>;

  /** Wipe the persisted seed. Does NOT require biometric. Idempotent. */
  clearSeed(): Promise<void>;

  // ---- Device-only tier (encrypted at rest, no biometric gate) ----
  //
  // Parallel slot used by non-passkey users who opted out of the
  // biometric flow during onboarding. The seed is still encrypted at
  // rest (iOS Keychain / Android Keystore AES-GCM) but reads are NOT
  // gated by a biometric prompt. Strictly a separate storage slot
  // from the biometric-bound family — the two never collide.

  /**
   * Returns true if a device-only seed blob is currently persisted.
   * Does NOT prompt.
   */
  hasStoredSeedDeviceOnly(): Promise<HasStoredSeedResult>;

  /**
   * Persist a seed blob in the device-only tier. Does NOT prompt
   * biometric — the storage is encrypted at rest but not biometric-gated.
   */
  storeSeedDeviceOnly(options: { seed: string }): Promise<void>;

  /**
   * Retrieve the device-only seed blob. Does NOT prompt biometric.
   * Rejects with `NO_STORED_SEED` / `KEY_INVALIDATED` / `UNKNOWN`
   * (a strict subset of the biometric-bound path — no USER_CANCELLED
   * / BIOMETRIC_* codes since there's no prompt).
   */
  retrieveSeedDeviceOnly(): Promise<RetrieveSeedResult>;

  /** Wipe the device-only seed. Idempotent. */
  clearSeedDeviceOnly(): Promise<void>;
}
