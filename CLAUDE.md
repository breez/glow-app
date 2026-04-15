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
- **Logcat sanity check on Honor device**: re-grep `adb logcat` for "mnemonic" / "seed" / wallet words after a fresh Test 1 run to confirm the `loggingBehavior: 'none'` fix is actually working end-to-end. Was queued during STEP 3 device verification but the device disconnected before the check could run.

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
