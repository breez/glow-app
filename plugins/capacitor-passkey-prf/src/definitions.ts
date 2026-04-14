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
}
