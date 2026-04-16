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
  - iOS: Swift, wraps SDK's `PlatformPasskeyPrfProvider` (ASAuthorization + PRF extension)
  - Android: Kotlin, wraps SDK's `CredentialManagerPrfProvider` (CredentialManager + PRF extension)
  - Native-only — no web fallback; on web, glow-web uses the SDK's `WebAuthnPrfProvider` directly
- Integration with glow-web via `PasskeyPrfProvider` interface — runtime detection swaps native vs. browser provider
- `plugins/capacitor-native-vault/` — Local Capacitor plugin for biometric-bound seed storage
  - iOS: Swift, Keychain `SecAccessControl` with `.biometryCurrentSet`
  - Android: Kotlin, Keystore AES-GCM with `setUserAuthenticationRequired(true)` + `BiometricPrompt.CryptoObject`
  - See "Phase 3: Native Secure Seed Storage" below for the full architecture

## Phase 3: Native Secure Seed Storage

Stores the wallet seed in iOS Keychain / Android Keystore with biometric binding at the OS layer (Face ID / Touch ID / BiometricPrompt). On native, this replaces the plaintext localStorage mnemonic path and the per-launch passkey PRF roundtrip with a single biometric unlock. On web the abstraction is a no-op — the existing localStorage / passkey re-derive flow runs unchanged.

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

- `PasskeyPrf.derivePrfSeed` returns the 32-byte PRF entropy as base64.
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

Tracked in `~/.claude/plans/delightful-sleeping-marshmallow.md`:

- **Plaintext seeds across the Capacitor bridge**: even with `loggingBehavior: 'none'`, `NativeVault.storeSeed` still passes the JSON-encoded mnemonic through Capacitor's bridge as a plain method argument. The proper fix is an opaque-handle pattern: have `capacitor-passkey-prf` keep the PRF entropy on the native side and expose only an opaque handle to JS, then have `capacitor-native-vault` accept a passkey-derived handle directly so the seed never crosses the bridge in plaintext at all. Until that lands, the `loggingBehavior` config pin is the only thing keeping bridge traces out of system logs — defense-in-depth would make the config choice non-load-bearing.
- **Logcat sanity check on a debug Android build**: re-grep `adb logcat` for "mnemonic" / "seed" / wallet words after a fresh onboarding run to confirm the `loggingBehavior: 'none'` fix is working end-to-end. Was queued during Phase 3 device verification but interrupted before the check could run.

## Phase 4A: App Polish

Makes the Capacitor shell feel like a first-class native app. Ships on
the `feat/phase-4a-app-polish` branch. See `PLAN.md` for the full
feature-by-feature breakdown; this section is the architecture-level
pointer list a maintainer needs to navigate the code.

### Branding + native shell

- **Native asset pipeline** — `scripts/prepare-native-assets.mjs`
  (sharp) + `npx capacitor-assets generate` via `make assets`. Source
  image: `glow-web/public/assets/Glow_Logo.png`. Regenerates every
  Android mipmap + drawable density and every iOS AppIcon / Splash
  slot. Adaptive icon background set to spark-void (`#0a0a0f`).
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

Two node_modules patches that re-apply on every `npm install`:

- **`patches/@capacitor+keyboard+8.0.3.patch`** — applies
  [ionic-team/capacitor-keyboard#30](https://github.com/ionic-team/capacitor-keyboard/issues/30)
  / [PR #60](https://github.com/ionic-team/capacitor-keyboard/pull/60)
  (unmerged upstream as of 2026-04-16). Makes the plugin's
  `setOnApplyWindowInsetsListener` call
  `possiblyResizeChildOfContent(showingKeyboard)` unconditionally,
  so the FrameLayout is restored on keyboard-hide events that arrive
  via the inset path (app switch, programmatic hide, symbol-keyboard
  fluctuation) — not just via the animation callback. Without this,
  the grey gap appears when the animation callback is unreliable on
  a given device.

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
- `scripts/resolve-spark-sdk.sh` — idempotent. Clones spark-sdk
  at the pin into `../spark-sdk/` when missing; verifies the
  HEAD matches when present. `SPARK_SDK_ALLOW_DRIFT=1` bypasses
  the verify for local SDK-side development. Exit 0 = ready,
  exit 1 = drift detected.
- `Makefile` exposes `make resolve-sdk`, and `make sdk` now
  depends on it so fresh checkouts auto-clone the SDK.

### CI workflows

- `.github/workflows/ci.yml` — four jobs:
  - `web` (ubuntu) — `tsc --noEmit` + `npm run lint` + `npm run
    test` against the submodule's pinned SHA. Runs on every PR.
  - `android` (ubuntu) — `gradle :app:assembleDebug`, uploads
    the APK artifact (7d retention) + Gradle reports on failure.
    Runs on every PR.
  - `ios` (macos-15) — unsigned `xcodebuild` device build.
    Label-gated on PRs via the `run-ios` label to contain
    macOS-minute burn; always runs on `main` pushes, `preview-*`
    / `rc-*` tags, and `workflow_dispatch`.
  - `ios-preview` (macos-15) — ad-hoc signed IPA uploaded to
    Firebase App Distribution. Tag-triggered (`preview-*` /
    `rc-*`) + manually dispatchable. Not on every main push.
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
   WASM tgz) keyed on `runner.os + platforms + SHA`. Cache
   hit → `make sdk-*` is skipped entirely.
2. `Swatinem/rust-cache@v2` as a second-line fallback keyed on
   the same SHA; makes `target/` rebuilds incremental on
   cache-miss cold runs.

Jobs request the SDK platforms they need via the `platforms`
input (e.g., `wasm` for `web`, `wasm,android` for the Android
job).

### iOS preview signing scripts

Two bash scripts at `scripts/ci/` handle the ad-hoc signing
the `ios-preview` job needs:

- `import-ios-ad-hoc-cert.sh` — creates a temp keychain,
  imports the base64-encoded `.p12` distribution cert,
  installs the provisioning profile into
  `~/Library/MobileDevice/Provisioning Profiles/`. Reads
  three env vars: `P12_BASE64`, `P12_PASSWORD`,
  `PROVISIONING_PROFILE_BASE64`.
- `build-ios-ipa.sh` — `xcodebuild archive` +
  `xcodebuild -exportArchive` with a `method=ad-hoc`
  exportOptions.plist. Writes `build/App.ipa`.

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

| Secret | Purpose |
|--------|---------|
| `GRADLE_ENCRYPTION_KEY` | Gradle config-cache encryption (optional) |
| `FIREBASE_IOS_APP_ID` | Firebase App Distribution iOS app id |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | Firebase service-account JSON |
| `IOS_PREVIEW_KEYCHAIN_PASSWORD` | temp keychain unlock pw |
| `IOS_PREVIEW_CERT_P12_BASE64` | ad-hoc distribution cert (.p12 → base64) |
| `IOS_PREVIEW_CERT_P12_PASSWORD` | .p12 import passphrase |
| `IOS_PREVIEW_PROFILE_BASE64` | ad-hoc provisioning profile (base64) |

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
if the numbers track the estimate. Escape hatches documented
in plan-4b Step 10.

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
