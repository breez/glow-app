import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.breez.spark.glow',
  appName: 'Glow',
  webDir: 'glow-web/dist',
  // SECURITY: pin Capacitor's bridge logging to "production" in every build.
  //
  // The default is "debug" in Debug builds, which writes every plugin call's
  // method data AND every plugin return value to logcat (Android) / NSLog
  // (iOS) at verbose level. Several plugin calls in this app pass wallet
  // seed material through the bridge:
  //
  //   - PasskeyPrf.derivePrfSeed returns the 32-byte PRF entropy.
  //   - SecureStorage.internalSetItem receives the plaintext mnemonic JSON
  //     blob produced by NativeSecureStorage.storeSeed.
  //
  // The verbose bridge log treats those payloads like any other and writes
  // them in the clear to a system log file readable by any process with the
  // READ_LOGS permission (granted to many OEM apps on Android, and to
  // Console.app on iOS). "production" suppresses the verbose plugin call
  // and result traces in every build configuration but leaves
  // console.warn / console.error WebView messages bridged so debugging
  // failure paths in the structured logger remains possible.
  loggingBehavior: 'production',
  server: {
    // HTTPS scheme required for SharedArrayBuffer support in Android WebView
    androidScheme: 'https',
    // COOP/COEP headers required by the WASM module (Breez Spark SDK)
    headers: {
      'Cross-Origin-Embedder-Policy': 'require-corp',
      'Cross-Origin-Opener-Policy': 'same-origin',
    },
  },
};

export default config;
