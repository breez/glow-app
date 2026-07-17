# Claude Code Guidelines — glow-app

## Project Overview

Native iOS/Android wrapper for Glow (Bitcoin/Lightning wallet) using Capacitor. The web app lives in the `glow-web/` git submodule.

## Key Paths

```
App wrapper:  ~/glow-app
Web app:      ~/glow-app/glow-web (submodule → breez/glow-web)
Passkey plugin: ~/glow-app/plugins/capacitor-passkey-prf
iOS project:  ~/glow-app/ios
Android project: ~/glow-app/android
SDK:          ~/Documents/GitHub/spark-sdk
```

## Build Flow

```bash
make setup       # full first-time setup (SDK + web + native)
make web         # rebuild web after glow-web changes
make sync        # copy web assets to native projects
make ios         # build iOS
make android     # build Android
make deploy-ios  # build + install on iOS device
make deploy-android  # build + install on Android device
```

See [DEVELOPMENT.md](DEVELOPMENT.md) for full setup guide including SDK build prerequisites.

## Architecture

- `capacitor.config.ts` — Capacitor config, webDir points to `glow-web/dist`. `loggingBehavior` is pinned to `'none'` — see the security note below.
- `plugins/capacitor-passkey-prf/` — Local Capacitor plugin bridging native passkey PRF APIs
  - iOS: Swift, wraps SDK's `BreezSdkSpark.PasskeyProvider` (ASAuthorization + PRF extension)
  - Android: Kotlin, wraps SDK's `technology.breez.spark.passkey.PasskeyProvider` (CredentialManager + PRF extension)
  - Native-only — no web fallback; on web, glow-web uses the SDK's `PasskeyProvider` from `@breeztech/breez-sdk-spark/passkey-prf-provider` directly.
- Integration with glow-web via the SDK's `PrfProvider` interface — runtime detection swaps native vs. browser provider
- `plugins/capacitor-native-vault/` — Local Capacitor plugin for biometric-bound seed storage
  - iOS: Swift, Keychain `SecAccessControl` with `.biometryCurrentSet`
  - Android: Kotlin, Keystore AES-GCM with `setUserAuthenticationRequired(true)` + `BiometricPrompt.CryptoObject`
  - See "Phase 3: Native Secure Seed Storage" below for the full architecture

## Phase 3: Native Secure Seed Storage

Stores the wallet seed in iOS Keychain / Android Keystore, replacing the plaintext localStorage mnemonic path and the per-launch passkey PRF roundtrip on native. On web the abstraction is a no-op — the existing localStorage / passkey re-derive flow runs unchanged.

**App lock is opt-in, Misty Breez-style** (glow-web `feat/optional-biometric-unlock`, July 2026, per Roy's "no login by default" decision): the seed always lives in the vault's device-only tier (encrypted at rest, no auth binding) and launch connects silently, like the PWA. A dedicated Security page (SideMenu, native only) offers an opt-in 6-digit PIN, and on top of it an optional biometric gate plus an auto-lock timeout (glow-web `services/appLock.ts`, `hooks/useAppLock.ts`, `pages/SecurityPage.tsx`, `components/LockScreen.tsx` + the plugin's standalone `authenticate()`). PIN/biometrics gate the UI only — deliberately NOT crypto binding, because a biometric-bound seed could never be released by a PIN fallback. The biometric-bound tier and its startup unlock flow (UnlockingPage / UnlockPage, `native-unlocking` / `native-locked`) survive only as a legacy migration path: pre-app-lock installs unlock once via the OS prompt, then the seed moves to the device-only tier. The F3 sections below describe that legacy bound tier.

The original Phase 3 PR (#2) used the `@aparajita/capacitor-secure-storage` and `@aparajita/capacitor-biometric-auth` packages. The current state on the `feat/native-secure-storage-followups` branch replaces those with an in-house `capacitor-native-vault` plugin and adds biometric-binding at the cryptographic layer (F2 + F3 follow-ups).

**Architecture:**

- `glow-web/src/services/secureStorage.ts` — the single abstraction. Exports a `SecureStorage` interface (`isSupported`, `hasStoredSeed`, `storeSeed`, `retrieveSeed`, `clearSeed`), a typed `SecureStorageError` with codes for every fallback path (`USER_CANCELLED`, `BIOMETRIC_LOCKOUT`, `BIOMETRIC_NOT_ENROLLED`, `BIOMETRIC_UNAVAILABLE`, `KEY_INVALIDATED`, `NO_STORED_SEED`, `NOT_SUPPORTED`, `UNKNOWN`), and a module-level `secureStorage` singleton that resolves to `NativeSecureStorage` on Capacitor hosts or `NoopSecureStorage` everywhere else. No plugin details leak past this file. glow-web does NOT depend on the plugin's npm package — it accesses the runtime global via `window.Capacitor.Plugins.NativeVault`, the same pattern used by `nativePasskeyPrfProvider.ts`.

- `plugins/capacitor-native-vault/` — in-house Capacitor plugin owning the platform-native crypto. Trait-style providers split each concern into its own file:
  - `BiometricAuthProviding` (Swift protocol / Kotlin interface) — surfaces `checkCapability()` + `authenticate()` / `authenticateWithCrypto()`.
  - `SeedVaultProviding` — surfaces `hasStoredSeed`, `storeSeed` / `prepareEncryptCipher` + `finishEncryptAndStore`, `retrieveSeed` / `prepareDecryptCipher` + `finishDecrypt`, `clearSeed`. The Android interface uses the prepare/finish split because biometric-bound Keystore keys require a `BiometricPrompt.CryptoObject` to authorize the cipher mid-flow; the iOS interface stays single-step because the Keychain handles the prompt inline.
  - The Capacitor plugin class is a thin orchestrator that delegates to the providers — easy to swap implementations for tests.

- **F3 biometric binding** (defense-in-depth at the OS layer, not just an external gate):
  - **iOS** — Keychain items are protected by a `SecAccessControl` constructed with `[.biometryCurrentSet]` and `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`. The biometric prompt is triggered inline by `SecItemCopyMatching` against the access-controlled item, not by a separate `LAContext.evaluatePolicy` call. Adding a new Face ID / Touch ID enrollment voids the item automatically (Apple's recommended failsafe pattern). `hasStoredSeed` uses `kSecUseAuthenticationUIFail` for a presence check that doesn't trigger a prompt. Both `storeSeed` and `retrieveSeed` run on `Task.detached` so the blocking Keychain call doesn't freeze the WebView thread.
  - **Android** — the AES-GCM Keystore key is generated with `setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on API 30+, and `setInvalidatedByBiometricEnrollment(true)`. Per-operation auth means the cipher must be wrapped in a `BiometricPrompt.CryptoObject` before any `doFinal()` call. The plugin orchestrates a three-step `prepare → authenticateWithCrypto → finish` flow per store/retrieve. Capacitor 8 dispatches plugin methods on the `CapacitorPlugins` worker thread — `BiometricPrompt.authenticate()` requires the main thread (uses FragmentManager transactions internally), so `BiometricPromptAuth.runAuthenticate` wraps the prompt construction + invocation in `activity.runOnUiThread { }`.

- **F1 UnlockPage** — when the secure-storage retrieve path encounters `USER_CANCELLED` or `BIOMETRIC_LOCKOUT`, `useBreezSdk` transitions `startupState` to `'native-locked'` instead of falling through to the welcome screen. The router shows a dedicated `UnlockPage` with a "Unlock with Face ID / Fingerprint" CTA (label adapts via `NativeVault.checkBiometry()`) and a "Use a different wallet" escape that wipes the vault and routes back to onboarding.

- **F3 migration** — first launch on an F3 build wipes any pre-existing F2-era vault entries via `secureStorage.clearSeed()` before any other operation, then writes a localStorage marker (`F3_MIGRATION_MARKER_KEY`) so subsequent launches skip the wipe. F2-era items would still be readable under the old access policies, which would silently bypass the new biometric binding — forced re-onboarding via the existing passkey fallback is the simplest way to ensure every install starts fresh under F3 protection.

- Integration in `glow-web/src/hooks/useBreezSdk.ts`: the mount-time `checkForExistingWallet` tries `secureStorage.retrieveSeed` before falling through to the legacy localStorage / passkey paths. `connectWallet` takes a `source: ConnectSeedSource` parameter (`'onboarding'` vs `'secureStorage'`) so the post-connect persist block skips a redundant `storeSeed` write when the seed was just retrieved from the same store. `handleLogout` calls `clearSeed` alongside the existing `clearMnemonic` / `clearPasskeyMode`. An `isSecuringSeed` flag flips around the `storeSeed` await so `PasskeyPage`'s `initializing` phase swaps its loading copy from "Starting Glow…" to "Enabling biometric unlock…" while the F3 prompt is showing — without it, the second biometric prompt during onboarding looks like an unexplained extra prompt on top of an unrelated spinner.

### ⚠️ Security: `loggingBehavior: 'none'` is REQUIRED

`capacitor.config.ts` pins `loggingBehavior: 'none'`. **Do not revert this.**

**Capacitor's `loggingBehavior` naming is inverted** — reading `CapConfig.java:290-304`:

- `'debug'` (default) → logs in debuggable builds, silent in release
- `'production'` → **always** logs, including release builds (the OPPOSITE of what the name implies)
- `'none'` → never logs in any build config

The value that actually suppresses logging is `'none'`, not `'production'`. The initial F2 "security fix" pinned `'production'` based on a misreading of the setting name and therefore **did nothing** — bridge traces were still leaking. F3 verification caught this by grepping logcat for the mnemonic after Test 1 and finding a full plaintext `storeSeed` payload. Do not trust the setting name; trust the source code.

Why we care: `Bridge.java:826` logs every plugin call's argument payload at verbose level:

```
V Capacitor: callback: X, pluginId: Y, methodName: Z, methodData: {...}
```

Several plugin calls in this app pass wallet seed material through the bridge:

- `PasskeyPrf.deriveSeeds` returns the 32-byte PRF entropy as base64.
- `NativeVault.storeSeed` receives the plaintext mnemonic JSON blob from `NativeSecureStorage.storeSeed`.

With any setting other than `'none'`, those payloads are written to logcat (Android) / NSLog (iOS) and are readable by any process with `READ_LOGS` (granted to many OEM apps on Android; Console.app on iOS).

**Tradeoff**: `'none'` also suppresses WebView `console.*` → native log bridging (via `BridgeWebChromeClient.onConsoleMessage` → `Logger.info/warn/error`, all gated on `shouldLog()`). Structured logger breadcrumbs from glow-web will no longer appear in logcat. Use the in-app log viewer (Settings → Share Logs) for debugging those paths.

This is defense-in-depth at the bridge layer. The proper fix (tracked as F2 in the follow-ups) is to keep plaintext seed material on the native side of the bridge entirely — until that lands, the config pin is the only safety net.

### Debugging breadcrumbs via `VITE_CONSOLE_LOGGING`

The structured logger in `glow-web/src/services/logger.ts` gates `console.*` output behind `isConsoleLoggingEnabled()`, which defaults to `import.meta.env.DEV` (on in `vite dev`, off in `vite build`). With `loggingBehavior: 'none'` pinned in `capacitor.config.ts`, ALL bridged WebView console output is suppressed regardless of `console.warn` / `console.error` level — so even a verbose `VITE_CONSOLE_LOGGING=true` build won't surface logger breadcrumbs in logcat / Console.app.

The recommended debugging path on this branch is the in-app log viewer:

> Settings → Share Logs

which reads the structured logger's in-memory ring buffer directly. For test builds that should retain DEV-mode console output behavior in the WebView devtools (for `chrome://inspect` debugging), still set the env var at build time:

```bash
VITE_CONSOLE_LOGGING=true make deploy-android
VITE_CONSOLE_LOGGING=true make deploy-ios
```

Do NOT set `VITE_CONSOLE_LOGGING=true` for production release builds.

### Outstanding follow-ups (post-`feat/native-secure-storage-followups`)

- **Plaintext seeds across the Capacitor bridge**: even with `loggingBehavior: 'none'`, `NativeVault.storeSeed` still passes the JSON-encoded mnemonic through Capacitor's bridge as a plain method argument. The proper fix is an opaque-handle pattern: have `capacitor-passkey-prf` keep the PRF entropy on the native side and expose only an opaque handle to JS, then have `capacitor-native-vault` accept a passkey-derived handle directly so the seed never crosses the bridge in plaintext at all. Until that lands, the `loggingBehavior` config pin is the only thing keeping bridge traces out of system logs — defense-in-depth would make the config choice non-load-bearing.
- **Logcat sanity check on a debug Android build**: re-grep `adb logcat` for "mnemonic" / "seed" / wallet words after a fresh onboarding run to confirm the `loggingBehavior: 'none'` fix is working end-to-end. Was queued during Phase 3 device verification but interrupted before the check could run.

## Phase 4A: App Polish

Makes the Capacitor shell feel like a first-class native app. Ships on
the `feat/phase-4a-app-polish` branch. This section is the
architecture-level pointer list a maintainer needs to navigate the code.

### Branding + native shell

- **Native asset pipeline** — `scripts/prepare-native-assets.mjs`
  (sharp) + `npx capacitor-assets generate` via `make assets`. Source
  image: `glow-web/public/assets/Glow_Logo.svg`. Regenerates every
  Android mipmap + drawable density and every iOS AppIcon / Splash
  slot. Adaptive icon background set to spark-void (`#0a0a0f`).
  Android launch splash is three script-emitted per-density drawables,
  not the capacitor-assets full-screen splash PNGs (those are deleted
  by a `make assets` post-step; no launch path draws them):
  `splash_icon` (288dp, `windowSplashScreenAnimatedIcon`, the Android
  12+ system splash shown for `launchShowDuration`), `splash_logo`
  (110dp, matching the PWA splash as measured on-device, inside the
  `splash_window` layer-list window background for
  pre-12 launches), and the same `splash_logo` as the plugin's
  fallback drawable (`androidSplashResourceName` + `CENTER` scale in
  `capacitor.config.ts`). Per-density output matters: a single
  mid-density bitmap gets upscaled 1.5x/2x on xxhdpi/xxxhdpi devices
  and renders blurry.
- **Launch themes** — `android/app/src/main/res/values/styles.xml`
  pins the pre-JS system bar theme dark so there's no flash of
  default chrome before `main.tsx` runs the `StatusBar` plugin init.
  `ios/App/App/Base.lproj/LaunchScreen.storyboard` hard-codes the
  background to `#0a0a0f` instead of `systemBackgroundColor`, so
  light-mode iOS devices don't flash white during launch.
- **Orientation lock** — portrait-only on both platforms:
  `android:screenOrientation="portrait"` in `AndroidManifest.xml`,
  `UISupportedInterfaceOrientations` in `Info.plist`.

### Capacitor plugins installed in this phase

- `@capacitor/splash-screen`, `@capacitor/status-bar`,
  `@capgo/capacitor-navigation-bar` — system bar styling
- `@capacitor/keyboard` — soft keyboard resize + height events
- `@capacitor/browser` — Chrome Custom Tabs / SFSafariViewController
  for Buy Bitcoin provider URLs
- `@capacitor/share` + `@capacitor/filesystem` — native share sheet
  for log export
- `@capacitor/app` — Android hardware back button events

All configured and wired via `capacitor.config.ts` (with `loggingBehavior: 'none'` preserved from Phase 3).

### Critical patches (`patch-package`, `postinstall`)

One node_modules patch that re-applies on every `npm install`.

The former `patches/@capacitor+keyboard+8.0.3.patch` was dropped once
its upstream fix
([ionic-team/capacitor-keyboard#30](https://github.com/ionic-team/capacitor-keyboard/issues/30)
/ [PR #60](https://github.com/ionic-team/capacitor-keyboard/pull/60))
shipped in `@capacitor/keyboard` 8.0.5 (the released source is
byte-identical to what the patch produced). `@capacitor/keyboard` now
floats normally and is no longer in the dependabot ignore list.

- **`patches/@capacitor+android+8.3.0.patch`** — patches
  `com.getcapacitor.plugin.SystemBars`'s inset listener to stop
  applying `imeInsets.bottom` as padding on the WebView parent.
  Android's `windowSoftInputMode="adjustResize"` already shrinks the
  activity content frame to exclude the keyboard; applying IME
  insets AGAIN as padding double-subtracts the keyboard height,
  leaving the WebView roughly one-keyboard-height shorter than the
  visible content area. Diagnosed via `adb shell uiautomator dump`:
  the parent `ViewGroup` was correctly sized (1080×1356) but the
  `WebView` child was shrunk to 1080×553 = parent − 803 ≈
  imeInsets.bottom. See the Ionic forum thread at
  /251049/4 (vosecek) for the root-cause analysis.

### Android hardware back button

- `src/utils/backButton.ts` in glow-web exposes a module-level LIFO
  handler stack + a `pushBackButtonHandler(fn)` that lazily installs
  the single `App.backButton` listener on first push.
- `src/hooks/useBackButton.ts` is the React hook components use:
  `useBackButton(dismiss, isOpen)` pushes while `isOpen` is true.
- Wired into `BottomSheetContainer` (covers every sheet in the app),
  `SideMenu` (drawer + nested logout confirm), `ConfirmDialog`
  (generic confirms), and `AppContent` (screen-navigation fallback).
- **Never calls `App.exitApp()`**. The fallthrough is
  `App.minimizeApp()`. `exitApp` destroys the activity process mid
  flight, and if a system-UI dialog (e.g. BiometricPrompt) is live
  at the time, SystemUI keeps the dialog on screen as an orphan
  with an unresponsive Cancel button that only a device reboot
  clears. The fix is "never call exitApp from any code path".

### Biometric unlock stuck-state recovery

Protects against a race where the auto-triggered biometric lands on
a non-STARTED activity (user pressed Home during the ~400ms delay
between `UnlockingPage` rendering and `retryUnlock` firing):

- **Native (Android)** — `BiometricPromptAuth.runAuthenticate` in
  `plugins/capacitor-native-vault` checks
  `activity.lifecycle.currentState.isAtLeast(STARTED)` before
  calling `prompt.authenticate`, and wraps the call in a
  try/catch for `IllegalStateException`. Without this,
  `FragmentManager.commit` would throw, the auth callback would
  never fire, and the JS Promise would hang forever.

- **JS** — `useBreezSdk` subscribes to `App.appStateChange` from
  `@capacitor/app` and re-fires `retryUnlock` when the app returns
  to the foreground while `startupState === 'native-unlocking'`.
  Guarded by `retryUnlockInFlightRef` against concurrent
  invocation.

- **iOS** — `KeychainSeedVault.retrieveSeed` now pre-checks
  `LAContext.canEvaluatePolicy` before `SecItemCopyMatching` so a
  user who denied the Face ID permission gets a targeted
  `BIOMETRIC_UNAVAILABLE` error (mapped to a helpful message on
  `UnlockPage`) instead of silent `USER_CANCELLED`.

### Soft keyboard + back button UX in glow-web

- `src/utils/keyboard.ts::dismissKeyboard()` — blurs the active
  element and calls `Keyboard.hide()` on native as belt-and-braces.
  Called from every submit handler (ContactsSubView Save,
  AmountPanel Generate Invoice, InputStep Continue, AmountStep
  Next).
- `src/components/ui/forms/index.tsx` — `FormInput` forwards
  `enterKeyHint`, `inputMode`, `autoCapitalize`, `autoCorrect`,
  `autoComplete`, `spellCheck`, `autoFocus`, `name`, `inputRef`.
- `src/components/ui/sheets/BottomSheet.tsx` — reads viewport
  height from `window.visualViewport.height` instead of computing
  `initialInnerHeight − keyboardHeight` from the plugin event.
  The computation double-subtracted the nav bar and left a
  ~128 physical-px gap; the visualViewport read is correct.

### Standalone web compatibility

Every native-only code path is guarded by
`Capacitor.isNativePlatform()` or `Capacitor.getPlatform()` checks.
`secureStorage.isSupported()` returns false on web, which means
`UnlockingPage` / `UnlockPage` are never mounted in a web build.
`useBackButton`, `useStatusBarColor`, `statusBarManager`,
`dismissKeyboard` all no-op on web. Verified via dev server +
Playwright (home, passkey home, mnemonic home, restore page all
render with zero console errors).

## Phase 4B: Continuous Integration

Every PR to `main` runs through GitHub Actions. All Phase 4B
work lives in `.github/` plus small accommodations in `Makefile`,
`DEVELOPMENT.md`, and `scripts/`.

### Spark SDK pinning

- `.spark-sdk-ref` — plain text file at repo root containing the
  pinned spark-sdk commit SHA. One SHA per line; `#` lines are
  comments. Bump the SHA when glow-app needs a newer SDK commit
  (normally in its own `chore(sdk):` commit).
- `.android-ndk-version` — CI NDK pin at repo root. Read by
  `setup-glow-app` (install + cache keys), ci.yml's preflight probe,
  and cache-maintenance's keep-alive, so the cache-key sites cannot
  drift from the installed NDK. Bump here only; see issue #86 for the
  16 KB alignment floor (r28+).
- `scripts/resolve-spark-sdk.sh` — idempotent. Clones spark-sdk
  at the pin into `../spark-sdk/` when missing; verifies the
  HEAD matches when present. `SPARK_SDK_ALLOW_DRIFT=1` bypasses
  the verify for local SDK-side development. Exit 0 = ready,
  exit 1 = drift detected.
- `Makefile` exposes `make resolve-sdk`, and `make sdk` now
  depends on it so fresh checkouts auto-clone the SDK.

### CI workflows

- `.github/workflows/ci.yml` — build-on-demand trigger model:
  - `web` (ubuntu) — `tsc --noEmit` + `npm run lint` + `npm run
    test` against the submodule's pinned SHA. Runs on every PR.
  - `android` (ubuntu) — `gradle :app:assembleDebug`, uploads
    the APK artifact (7d retention) + Gradle reports on failure.
    Dispatch-only (target=android|both, distribution=none).
  - `ios` (macos-15) — unsigned `xcodebuild` device build.
    Label-gated on PRs via the `run-ios` label to contain
    macOS-minute burn; also on `workflow_dispatch`
    (target=ios|both, distribution=none). Not on tag pushes
    (ios-preview / ios-release cover those).
  - `ios-preview` (macos-15) — Ad Hoc signed IPA uploaded to
    Firebase App Distribution via fastlane's
    `firebase_app_distribution` plugin (`:upload_firebase` lane).
    Tag-triggered (`preview-*` / `rc-*`) + manually dispatchable
    via `workflow_dispatch`. Delivers to the `internal` tester
    group. Not on every main push.

### SDK cache warming + release-tag timing

Pushes to `main` run ONLY the SDK cache warmers (`warm-sdk-linux`
+ `warm-sdk-macos`), no consumer jobs, no artifacts. GitHub cache
scoping lets any run restore default-branch caches but never a
sibling branch's, another tag's, or a PR's, so main is the
canonical warm-cache holder every ref inherits from (PR #85).
Consequences:

- **Cut release/preview tags only after the post-merge main CI
  run finishes.** A tag pushed seconds after merging an SDK pin
  bump races the ~40-min cold warm and rebuilds the SDK from
  scratch inside the release pipeline (rc-1 of 0.1.0 did exactly
  this). Seconds-cheap when the pin didn't change.
- PR warmers build wasm only (nothing on a PR consumes Android
  artifacts); full wasm+android / wasm+ios sets are built on main
  pushes, tags, and dispatches. The macOS warmer reuses the
  Linux-built WASM tgz cross-OS instead of rebuilding it.
- `.github/workflows/cache-maintenance.yml` deletes closed-PR
  caches (quota hygiene) and touches main's SDK caches Mon+Thu
  so the 7-day inactivity eviction can't discard them between
  release cycles.
- `.github/dependabot.yml` — weekly update PRs for npm (glow-app
  root + glow-web + both in-house plugins), Gradle (Android),
  and `github-actions`. `@capacitor/keyboard` and
  `@capacitor/android` are ignored because bumping them without
  re-validating the `patches/` files silently breaks keyboard
  handling.

A CodeQL code-scanning workflow is intentionally NOT shipped —
CodeQL on private repos requires GitHub Advanced Security
(GHAS), which isn't included in Breez-org's plan tier. Revisit
if GHAS becomes available.

### Shared setup: composite action

`.github/actions/setup-glow-app/action.yml` wraps the steps
every job shares: `actions/checkout@v4 --submodules`,
`actions/setup-node@v4 --version 22`, and a two-layer SDK
artifact cache:

1. `actions/cache@v4` caches the **output artifacts** (iOS
   xcframework + generated Swift sources,
   `~/.m2/repository/breez_sdk_spark` for Android, and the
   WASM tgz) keyed on `runner.os + NDK version + SDK SHA`
   (the NDK version is in the key because the Android
   artifacts embed the NDK's libc++_shared.so prebuilt, see
   issue #86). Cache hit → `make sdk-*` is skipped entirely.
2. `Swatinem/rust-cache@v2` as a second-line fallback keyed on
   the same SHA; makes `target/` rebuilds incremental on
   cache-miss cold runs.

Jobs request the SDK platforms they need via the `platforms`
input (e.g., `wasm` for `web`, `wasm,android` for the Android
job).

### iOS preview signing scripts

Two bash scripts at `scripts/ci/` handle the Ad Hoc signing the
`ios-preview` job needs. Both are shared with `ios-release`
(same cert, different profile + export method).

- `import-ios-cert.sh` — creates a temp keychain, imports the
  base64-encoded `.p12` distribution cert, installs the
  provisioning profile into
  `~/Library/MobileDevice/Provisioning Profiles/`. Reads generic
  env vars `P12_BASE64`, `P12_PASSWORD`,
  `PROVISIONING_PROFILE_BASE64`, `KEYCHAIN_PASSWORD`. Logs the
  derived profile type + provisioned-device count for
  debuggability, but does not assert — keeps the script
  permissive for local dev. A wrong-type profile will still fail,
  just later at the xcodebuild export step.
- `build-ios-ipa.sh` — `xcodebuild archive` +
  `xcodebuild -exportArchive`. Accepts `$1` configuration
  (Debug | Release) and `$2` method — canonicalised on
  `release-testing` | `app-store-connect` (Xcode 15.3+ /
  Xcode 26 names). Old `ad-hoc` / `app-store` spellings are
  accepted as deprecation aliases and remapped with a warning
  so the generated exportOptions.plist always writes the
  modern value. Writes `build/App.ipa` at the repo root.

### PR-label gating for iOS

The `ios` job's `if:` expression:

```yaml
if: >-
  github.event_name == 'push' ||
  github.event_name == 'workflow_dispatch' ||
  contains(github.event.pull_request.labels.*.name, 'run-ios')
```

Skipped PRs show a "skipped" status, not "failed". Branch
protection must be configured with "skipped = passing" so
PRs without the label can still merge — see
`DEVELOPMENT.md#branch-protection` for the exact settings.

### Required secrets

Organized by pipeline phase. Phase 4C adds `IOS_RELEASE_*`,
`FASTLANE_*` (or `APP_STORE_CONNECT_API_*`), `FIREBASE_ANDROID_APP_ID_PREVIEW`,
and Android release secrets (not yet listed — pending Android path).

| Secret | Phase | Purpose |
|--------|-------|---------|
| `VITE_BREEZ_API_KEY` | 4A | Baked into the web bundle at build time |
| `GRADLE_ENCRYPTION_KEY` | 4B | Gradle config-cache encryption (optional) |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | 4B | Firebase service-account JSON (project-scoped; covers dev + prod apps). SA must hold `roles/firebaseappdistro.admin` on `breez-technology` — the Firebase Admin SDK service-agent role is NOT sufficient. |
| `FIREBASE_IOS_APP_ID_PREVIEW` | 4B | Firebase App Distribution iOS app id (Glow Dev — for `.dev` ad-hoc uploads) |
| `FIREBASE_ANDROID_APP_ID_PREVIEW` | 4C | Firebase App Distribution Android app id (Glow Dev — for `.dev` debug APK uploads) |
| `IOS_PREVIEW_CERT_P12_BASE64` | 4B | Apple Distribution cert `.p12` (base64) — same cert as IOS_RELEASE_*, mirrored |
| `IOS_PREVIEW_CERT_P12_PASSWORD` | 4B | `.p12` import passphrase |
| `IOS_PREVIEW_KEYCHAIN_PASSWORD` | 4B | Temp keychain unlock password (random) |
| `IOS_PREVIEW_PROFILE_BASE64` | 4B | Ad-hoc `.mobileprovision` for `technology.breez.glow.dev` |
| `IOS_RELEASE_CERT_P12_BASE64` | 4C | Same Apple Distribution cert as preview — mirrored to keep the secret mapping explicit per pipeline |
| `IOS_RELEASE_CERT_P12_PASSWORD` | 4C | Same as preview |
| `IOS_RELEASE_KEYCHAIN_PASSWORD` | 4C | Independent random keychain password (different value from preview) |
| `IOS_RELEASE_PROFILE_BASE64` | 4C | App Store `.mobileprovision` for `technology.breez.glow` |
| `APP_STORE_CONNECT_API_KEY_ID` | 4C | ASC API `.p8` Key ID (preferred auth) |
| `APP_STORE_CONNECT_API_ISSUER_ID` | 4C | ASC team-wide Issuer ID |
| `APP_STORE_CONNECT_API_KEY_BASE64` | 4C | `.p8` private key (base64) |
| `FASTLANE_USER` | 4C | Apple ID email (legacy auth fallback when ASC API key is unavailable) |
| `FASTLANE_PASSWORD` | 4C | App-specific password (legacy auth fallback) |
| `FASTLANE_SESSION` | 4C | Optional — 2FA session cookie for changelog updates on legacy path |

### Cost-sensitive choices

macOS runners bill at 10x Linux on GitHub-hosted runners, so
Phase 4B is structured to minimize macOS usage within the
Breez-org's plan quota:

- iOS PRs are **label-gated**.
- iOS preview runs only on **preview-* tags** (and manual
  dispatch), not on every main push.
- Android + web run on ubuntu (1x).
- DerivedData is NOT cached (invalidation is fragile); Pods
  + `~/Library/Caches/CocoaPods` are.

Expected total: ~6,900 billed minutes/month. Fits Enterprise
Cloud easily; ~3,900-minute overage on Pro/Team at ~$30/mo
if the numbers track the estimate.

## Phase 4C: Store Distribution

In-repo plumbing for tag-triggered release distribution to Play
Store internal testing + TestFlight. Activated externally by
H1–H8 (Play app + service account, ASC app + API key or legacy
Apple ID creds, upload keystore + secrets, iOS distribution
cert + app-store profile, Firebase Android + iOS app IDs).
Lives on `feat/phase-4c-release`. See
`DEVELOPMENT.md` "Release signing (Phase 4C)" for the full
setup + release-cutting walkthrough; this section is the
maintainer pointer list.

### Bundle ID + version derivation

- **Debug/release bundle ID split** (reversed D1):
  - Debug: `technology.breez.glow.dev`. Android via
    `applicationIdSuffix ".dev"` on the `debug` buildType in
    `android/app/build.gradle`. iOS via Debug build configuration
    in `ios/App/App.xcodeproj/project.pbxproj`. Enables side-by-
    side install of debug + release on the same device.
  - Release: `technology.breez.glow`. Android from the base
    `applicationId`. iOS from the Release build configuration.
    TestFlight + Play uploads ship this one.
  - Both IDs are registered on Apple Developer portal under team
    `F7R2LZH3W5`; both are in
    `keys.breez.technology/.well-known/apple-app-site-association`.
    `assetlinks.json` only has the `.dev` entry with the debug
    keystore SHA-256; the release cert SHA from Play App Signing
    lands under `technology.breez.glow` after first enrollment.
  - Signing artifacts (distribution cert + provisioning
    profiles) live as GitHub secrets (base64-encoded), imported
    into a temp keychain on the CI runner by
    `scripts/ci/import-ios-cert.sh`. Same Apple Distribution
    cert signs both ad-hoc and app-store exports; only the
    profile + `method` flag differ (see "iOS release signing"
    below). No fastlane `match` / cert git repo.
- Version derivation: tag `release-MAJOR.MINOR.PATCH` →
  `versionName = MAJOR.MINOR.PATCH`,
  `versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000
   + (GITHUB_RUN_NUMBER % 1000)`.
- `scripts/ci/compute-version.sh` parses the tag, exports both
  to `$GITHUB_ENV`. `android/app/build.gradle` reads via
  `System.getenv()`. iOS uses `scripts/ci/apply-ios-version.sh`
  → `agvtool new-marketing-version` + `new-version -all`.

### Android release signing

- `android/app/build.gradle` has an env-driven
  `signingConfigs.release` block reading
  `RELEASE_KEYSTORE_PATH` / `RELEASE_KEYSTORE_PASSWORD` /
  `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`. CI's
  `android-release` job decodes `RELEASE_KEYSTORE_BASE64` →
  `$RUNNER_TEMP/release.keystore` + sets
  `RELEASE_KEYSTORE_PATH`.
- When envs are absent (any local `gradle assembleRelease`
  without secrets), the `release` buildType falls back to
  debug signing via the conditional ternary
  `signingConfig signingConfigs.release.storeFile ?
   signingConfigs.release : signingConfigs.debug`. Local
  smoke builds still produce installable APKs.
- **Play App Signing**: Google generates the release key
  during first-AAB enrollment. We hold only the upload key.
  Trade-off vs. self-managed release key: easier rotation via
  Play support if upload key lost; release-cert SHA only
  becomes known after first enrollment upload (must then be
  appended to `keys.bt.webauthn` assetlinks under
  `technology.breez.glow.dev`).
- `gradle-play-publisher` 3.12.1
  (`com.github.triplet.play` plugin in
  `android/app/build.gradle`) handles CI-driven Play uploads.
  Pinned to 3.x — GPP 4.0.0 needs AGP 9, we ship AGP 8.
  Release notes path:
  `android/app/src/main/play/release-notes/en-US/internal.txt`,
  overwritten per-release by `scripts/ci/release-notes.sh`.

### iOS release signing — direct-secrets (no match)

- `scripts/ci/import-ios-cert.sh` (renamed from
  `import-ios-ad-hoc-cert.sh`) is now method-neutral — it
  imports a distribution cert + provisioning profile into a
  temp keychain from generic env vars (`P12_BASE64`,
  `P12_PASSWORD`, `PROVISIONING_PROFILE_BASE64`,
  `KEYCHAIN_PASSWORD`). The calling CI step maps the right
  repo secrets (`IOS_PREVIEW_*` for ad-hoc, `IOS_RELEASE_*`
  for app-store) onto those generic env names.
- `scripts/ci/build-ios-ipa.sh` accepts two args: `$1`
  configuration (Debug | Release) and `$2` export method
  (`release-testing` | `app-store-connect`; legacy
  `ad-hoc` / `app-store` accepted as deprecation aliases
  and auto-remapped). Writes `build/App.xcarchive/` +
  `build/App.ipa` at the repo root regardless.
- Same Apple Distribution cert (SHA-1
  `6C:97:AD:24…A5:BA`) signs both ad-hoc and app-store
  exports; only the `.mobileprovision` differs. Cert expires
  2026-12-23 — rotate locally, re-base64, overwrite both
  `IOS_PREVIEW_CERT_P12_BASE64` + `IOS_RELEASE_CERT_P12_BASE64`
  secrets (same bytes).
- Profiles stored as base64 secrets:
  - `IOS_PREVIEW_PROFILE_BASE64` — ad-hoc for
    `technology.breez.glow.dev`. Regenerate whenever a new
    tester device UDID needs adding (Apple Developer portal
    → Profiles → edit → add device → download).
  - `IOS_RELEASE_PROFILE_BASE64` — app-store for
    `technology.breez.glow`. Auto-renews when the profile
    expires (1yr) — manual step to download + re-base64.
- `ios/App/Gemfile` + `ios/App/fastlane/{Appfile,Fastfile,Pluginfile}`
  — fastlane drives BOTH iOS distribution flows' upload step.
  Two lanes:
  - `:upload` (TestFlight) — wraps
    `upload_to_testflight` (altool + ASC changelog update).
  - `:upload_firebase` (Firebase App Distribution, ios-preview)
    — wraps the `firebase_app_distribution` plugin declared in
    `fastlane/Pluginfile`. This is the approach Firebase's
    [official iOS distribution docs][fad-ios-docs] recommend.
    `android/fastlane/Fastfile` mirrors this lane for Android FAD
    previews (see "android-preview fastlane" below).
  Build always happens in the shell scripts; fastlane is used
  ONLY for the upload step on both lanes. Run `bundle install`
  once locally to generate `Gemfile.lock` (system Ruby 2.6 too
  old; needs 3.x via rbenv/asdf).

[fad-ios-docs]: https://firebase.google.com/docs/app-distribution/ios/distribute-fastlane
- **Dual auth** in Fastfile — auto-selects first available:
  - App Store Connect API key (`APP_STORE_CONNECT_API_KEY_ID`
    + `ISSUER_ID` + `KEY_BASE64`) — preferred, stable on CI,
    no 2FA dance. Requires ASC Admin role to generate.
  - Legacy Apple ID + app-specific password (`FASTLANE_USER`
    + `FASTLANE_PASSWORD`, optional `FASTLANE_SESSION` 2FA
    cookie). App Manager works. Use as fallback when the
    ASC API key path is blocked.
  - `env_present?` helper treats empty string as absent so
    unset GitHub secrets (rendered as `""`) don't confuse
    the branch selection.
- `ios-release` job step order:
  `cap sync ios → compute-version → apply-ios-version →
   release-notes → import-ios-cert.sh → build-ios-ipa.sh
   Release app-store → bundle exec fastlane upload`. Sync
  first because it preserves `CURRENT_PROJECT_VERSION` /
  `MARKETING_VERSION` but rewrites Pods refs; running it
  before agvtool ensures version + signing both land on the
  post-sync project state.
- IPA path: `build-ios-ipa.sh` writes to
  `$GITHUB_WORKSPACE/build/App.ipa` (repo root). Fastfile
  reads `IPA_PATH` env override (CI sets it) since
  `working-directory: ios/App` would otherwise resolve a
  relative `build/App.ipa` to the wrong place.

### Privacy manifest + export compliance

- `ios/App/App/PrivacyInfo.xcprivacy` declares
  `NSPrivacyAccessedAPICategoryFileTimestamp` (C617.1) +
  `NSPrivacyAccessedAPICategoryDiskSpace` (E174.1) for
  `@capacitor/filesystem` (the in-app log-share flow), and
  `NSPrivacyAccessedAPICategoryUserDefaults` (CA92.1) for
  `@capacitor/preferences`. Capacitor 8 ships its own
  framework-level manifest; the app-level file aggregates with
  it. `@capacitor/preferences` ships NO manifest of its own, so
  the CA92.1 entry is load-bearing — it's the only thing
  declaring UserDefaults, and glow-web reaches for Preferences
  from `secureStorage.ts`, `appLock.ts`, and `settings.ts`.
  Keychain `SecItem*` and `ASAuthorization` are NOT in
  Apple's required-reason list, so the in-house
  `capacitor-native-vault` + `capacitor-passkey-prf` plugins
  need no manifest entries.
- `ios/App/App/Info.plist`:
  `ITSAppUsesNonExemptEncryption=false`. Glow uses only
  Apple-provided crypto (TLS, Keychain) + wallet-domain
  (BIP32/BIP39/secp256k1) which falls under the cryptocurrency
  + authentication-only exemption. Setting to `false` bypasses
  the encryption-export questionnaire on every TestFlight
  upload — required for `upload_to_testflight` to succeed
  unattended.
- **Manual one-time Xcode step**: drag
  `PrivacyInfo.xcprivacy` into the App target in Xcode
  (Add to targets = App). Hand-editing pbxproj is error-prone
  so it's a deliberate human task. Apple's "Missing privacy
  manifest" feedback on TestFlight reject is the canary.

### CI workflow additions (`.github/workflows/ci.yml`)

Three new jobs gated on `release-*` tag pushes plus a fourth
on `preview-*` / `rc-*`:

- `android-preview` — debug APK uploaded to Firebase App
  Distribution via the `firebase_app_distribution` fastlane
  plugin ([official Android docs][fad-android-docs]). Structure
  mirrors `ios-preview`: `./gradlew :app:assembleDebug` builds,
  then `bundle exec fastlane upload_firebase` uploads from
  `android/`. Uses `FIREBASE_SERVICE_ACCOUNT_KEY` +
  `FIREBASE_ANDROID_APP_ID_PREVIEW`. The SA must hold
  `roles/firebaseappdistro.admin` on `breez-technology` —
  fastlane returns a clear 403 if missing.

[fad-android-docs]: https://firebase.google.com/docs/app-distribution/android/distribute-fastlane?apptype=apk
- `android-release` — `bundleRelease` + `publishReleaseBundle`
  via gradle-play-publisher. Needs `RELEASE_KEYSTORE_*` +
  `PLAY_SERVICE_ACCOUNT_JSON` secrets. Uses
  `fetch-depth: 0` so `release-notes.sh` can find the prior
  release tag.
- `ios-release` — direct-secrets signing via
  `import-ios-cert.sh` + `build-ios-ipa.sh Release app-store`
  + `fastlane upload`. Needs `IOS_RELEASE_*` (4 secrets:
  cert p12 + password + keychain + profile) plus EITHER
  `APP_STORE_CONNECT_*` (3 secrets) OR `FASTLANE_USER` +
  `FASTLANE_PASSWORD` (+ optional `FASTLANE_SESSION`) for
  TestFlight upload auth. Same `fetch-depth: 0` for release
  notes.
- `release-github` — `needs: [android-release, ios-release]`,
  downloads both artifacts, publishes a GitHub Release with
  the AAB + IPA + git-log changelog. `permissions: contents:
  write` for the release write.

### Branch protection unchanged

`android-release` / `ios-release` / `release-github` are NOT
added as required status checks because they only run on
tag pushes, not PR events. Adding them as required would
block every PR merge. Existing `web` + `android` +
(label-gated) `ios` checks stay the gate.

## Key References

- Spark SDK PR #781 (`pr/passkey-core`) — native PRF provider implementations we reuse
- Default RP ID: `keys.breez.technology` — cross-platform credential sharing domain
- iOS requires: Associated Domains entitlement `webcredentials:keys.breez.technology`, iOS 18+
- Android requires: `assetlinks.json` on RP domain, minSdk 28 with Play Services

## SDK Dependencies

The passkey plugin depends on local Spark SDK builds (`pr/passkey-core` branch):
- **iOS**: Local Swift package dependency → `../spark-sdk/crates/breez-sdk/bindings/langs/swift`
- **Android**: `breez_sdk_spark:bindings-android:0.1.0-local` from mavenLocal
- **WASM**: tgz installed into glow-web from `spark-sdk/packages/wasm/`

Build all with `make sdk`. See DEVELOPMENT.md for details.

## Common Tasks

### After glow-web changes
```bash
make web && make sync
```

### Rebuild after SDK changes
```bash
make sdk && make web && make sync
```

### Update glow-web submodule
```bash
cd glow-web && git pull && cd .. && git add glow-web && git commit -m "chore: update glow-web submodule"
```
