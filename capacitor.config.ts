import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'technology.breez.glow',
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
  plugins: {
    // Route the native WebView's fetch / XMLHttpRequest through native HTTP.
    //
    // WHY: the Breez Spark SDK runs as WASM inside the WebView and makes its
    // Bitcoin chain requests (default https://blockstream.info/api) via the
    // WebView's `fetch`. The SDK's reqwest HTTP client is built with a custom
    // `User-Agent` ("breez-sdk-spark/<version>"), which is NOT a CORS-safelisted
    // request header. On iOS (WKWebView = WebKit) the engine forwards that
    // author User-Agent onto the request, which upgrades the cross-origin GET
    // to a *preflighted* request. blockstream's `OPTIONS` preflight answers 404
    // with no `Access-Control-Allow-Headers`, so the preflight fails and the
    // request surfaces to the SDK as `TypeError: Load failed`, so onchain
    // deposits can't be claimed. Chromium (Android WebView + desktop web) silently DROPS
    // the author User-Agent, so the request stays "simple" and those platforms
    // are unaffected, which is why this reproduces only on iOS / WebKit.
    //
    // CapacitorHttp rewrites cross-origin requests to a same-origin proxy URL
    // ({serverUrl}/_capacitor_http_interceptor_?u=...) and performs the real
    // request natively, so the WebView never does a CORS preflight. This also
    // covers any other third-party endpoint whose CORS doesn't whitelist
    // User-Agent (e.g. some LNURL / Lightning-address hosts like aqua.net).
    //
    // Scope: this patch only installs inside the native WebView (via
    // native-bridge.js, gated on this `enabled` flag); the Vercel web build
    // keeps using the browser's normal `fetch`. WebSockets are NOT routed
    // natively, so the SDK's real-time event stream is unaffected. Keep
    // `loggingBehavior: 'none'` (above): with CapacitorHttp on, all HTTP now
    // crosses the Capacitor bridge, and the bridge logs call args. No seed
    // material travels over HTTP, but the logging pin stays load-bearing.
    CapacitorHttp: {
      enabled: true,
    },
    SplashScreen: {
      // Capacitor 4+ wires this into Android 12's Theme.SplashScreen API via
      // androidx.core:core-splashscreen (already a dependency in
      // android/app/build.gradle) and onto iOS's LaunchScreen.storyboard.
      launchShowDuration: 2000,
      launchAutoHide: true,
      launchFadeOutDuration: 200,
      backgroundColor: '#0f0f18',
      androidSplashResourceName: 'splash',
      // FIT_CENTER preserves the source aspect ratio (matches iOS
      // LaunchScreen storyboard behavior) so the logo renders at the
      // same on-screen size on both platforms — important because the
      // splash logo size is tuned in prepare-native-assets.mjs to
      // match HomePage's <GlowLogo sizePx={144}>. CENTER_CROP fills
      // the screen by scaling to the larger dimension, which on a
      // tall portrait phone effectively doubled the on-screen logo
      // size relative to iOS / HomePage.
      androidScaleType: 'FIT_CENTER',
      showSpinner: false,
      splashFullScreen: true,
      splashImmersive: true,
    },
    Keyboard: {
      // Route WebView resize through both Android's native
      // `windowSoftInputMode=adjustResize` (set on MainActivity) AND
      // the plugin's own FrameLayout hack. Together they cover both
      // the framework happy path and the animation-callback-missed
      // edge cases (app switch, programmatic hide, symbol-keyboard
      // fluctuation) where adjustResize alone leaves a gap.
      //
      // `resizeOnFullScreen: true` enables the plugin's backup path,
      // which is only safe because we locally patch the plugin via
      // patches/@capacitor+keyboard+8.0.3.patch to apply
      // ionic-team/capacitor-keyboard#30 / PR #60 (unmerged upstream).
      // Without that patch the plugin would fail to restore the
      // FrameLayout height on keyboard hide.
      resize: 'native',
      resizeOnFullScreen: true,
    },
  },
};

export default config;
