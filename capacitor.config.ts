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
  // WebView background. Capacitor only calls webView.setBackgroundColor()
  // when this key is present (Bridge.initWebView reads
  // `android.backgroundColor` then falls back to `backgroundColor`, and
  // skips the call entirely when both are null), so leaving it unset
  // leaves the WebView on Chromium's opaque WHITE default. Any region or
  // moment the page canvas does not cover then paints white: the gap
  // between the native splash tearing down and the first web paint, and
  // any layout inset that leaves part of the WebView unpainted.
  //
  // spark-dark (#0f0f18) matches the launch splash canvas, the pre-JS
  // AppTheme.NoActionBar windowBackground, and HomePage's own background,
  // so the whole cold-start hand-off stays one flat color.
  backgroundColor: '#0f0f18',
  // `cap sync ios` regenerates ios/App/CapApp-SPM/Package.swift from the
  // app's IPHONEOS_DEPLOYMENT_TARGET, writing `platforms: [.iOS(.vNN)]`
  // (util/spm.js:89-98 in @capacitor/cli 8.3.0). Its default manifest
  // header is `swift-tools-version: 5.9`, where `.v18` does not exist yet,
  // so raising the deployment target to 18 made every sync emit a package
  // that fails to resolve ("error: 'v18' is unavailable"). This is the
  // supported override (spm.js:91); anything from 6.0 up knows `.v18`.
  //
  // Safe for the generated package: its only source file is a one-line
  // `public let isCapacitorApp = true`, so Swift 6 language mode has
  // nothing to complain about, and every dependency keeps its own tools
  // version (capacitor-passkey-prf is already on 6.0).
  experimental: {
    ios: {
      spm: {
        swiftToolsVersion: '6.0',
      },
    },
  },
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
    // DISABLED. This was enabled to route the WebView's fetch through native
    // HTTP, working around the SDK's custom User-Agent
    // ("breez-sdk-spark/<version>") tripping a CORS preflight: WebKit/iOS
    // forwards that author User-Agent, upgrading the cross-origin GET to a
    // preflighted request, and blockstream's OPTIONS preflight 404s, so
    // onchain deposits couldn't be claimed.
    //
    // The global patch was too broad: it also proxied the SDK's
    // Spark-operator calls, which native HTTP does not round-trip faithfully
    // (binary bodies / responses), breaking Lightning invoice and Bitcoin
    // address generation on iOS.
    //
    // The narrower, correct fix is app-side: glow-web strips the custom
    // User-Agent from outgoing requests (src/utils/stripUserAgentFetch.ts),
    // so every cross-origin request stays CORS-simple on all engines
    // (WebKit, Firefox, Chromium) over plain fetch, with no native routing.
    // That fixes the blockstream preflight AND any other CORS-strict host
    // (LNURL / Lightning-address hosts) without touching operator traffic.
    CapacitorHttp: {
      enabled: false,
    },
    SplashScreen: {
      // Capacitor 4+ wires this into Android 12's Theme.SplashScreen API via
      // androidx.core:core-splashscreen (already a dependency in
      // android/app/build.gradle) and onto iOS's LaunchScreen.storyboard.
      launchShowDuration: 2000,
      launchAutoHide: true,
      launchFadeOutDuration: 200,
      backgroundColor: '#0f0f18',
      // Only reached when the plugin's Android 12 compat path throws
      // and it falls back to its own ImageView overlay (on Android 12+
      // the launch splash is the system one: windowSplashScreenBackground
      // + windowSplashScreenAnimatedIcon from styles.xml). splash_logo
      // is the per-density 110dp mark from `make assets`; CENTER draws
      // it at intrinsic size on the backgroundColor above, with no
      // scaling at any screen size. The former full-screen `splash`
      // PNGs topped out at 1280x1920, so FIT_CENTER upscaled them
      // ~1.13x on 1080p/1440p phones and the logo rendered soft.
      androidSplashResourceName: 'splash_logo',
      androidScaleType: 'CENTER',
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
