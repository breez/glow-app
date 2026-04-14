import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.breez.spark.glow',
  appName: 'Glow',
  webDir: 'glow-web/dist',
  // SECURITY: pin Capacitor's bridge logging to "none" in every build.
  //
  // WARNING: Capacitor's `loggingBehavior` naming is inverted from what it
  // sounds like. Reading CapConfig.java:290-304:
  //
  //   - "debug"      (default) → loggingEnabled = isDebug
  //                              (log in debuggable builds, silent in release)
  //   - "production"           → loggingEnabled = true
  //                              (ALWAYS log, including release builds — the
  //                              OPPOSITE of what the name implies)
  //   - "none"                 → loggingEnabled = false
  //                              (never log, regardless of build config)
  //
  // We must use "none" to actually suppress Capacitor's bridge traces.
  //
  // Why we care: Bridge.java:826 logs every plugin call at verbose level:
  //   V Capacitor: callback: X, pluginId: Y, methodName: Z, methodData: {...}
  // which writes the full argument payload to logcat / NSLog. Several
  // plugin calls in this app pass wallet seed material through the bridge:
  //
  //   - PasskeyPrf.derivePrfSeed returns the 32-byte PRF entropy.
  //   - NativeVault.storeSeed receives the plaintext mnemonic JSON blob
  //     from NativeSecureStorage.storeSeed.
  //
  // With any setting other than "none", those payloads are written in
  // cleartext to system log files readable by any process with READ_LOGS
  // (granted to many OEM apps on Android, and to Console.app on iOS).
  //
  // Tradeoff: "none" ALSO suppresses WebView console.* → native log
  // bridging (via BridgeWebChromeClient.onConsoleMessage → Logger.info/
  // warn/error, all gated on shouldLog()). Structured logger breadcrumbs
  // from glow-web will no longer appear in logcat. Use the in-app log
  // viewer (Settings → Share Logs) for debugging those paths.
  //
  // This is a defense-in-depth mitigation at the bridge layer. The proper
  // fix is F2 in the follow-ups plan: keep plaintext seed material on
  // the native side of the bridge entirely so this config choice is no
  // longer load-bearing.
  loggingBehavior: 'none',
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
