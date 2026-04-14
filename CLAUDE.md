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

- `capacitor.config.ts` — Capacitor config, webDir points to `glow-web/dist`. `loggingBehavior` is pinned to `'production'` — see the security note below.
- `plugins/capacitor-passkey-prf/` — Local Capacitor plugin bridging native passkey PRF APIs
  - iOS: Swift, wraps SDK's `PlatformPasskeyPrfProvider` (ASAuthorization + PRF extension)
  - Android: Kotlin, wraps SDK's `CredentialManagerPrfProvider` (CredentialManager + PRF extension)
  - Native-only — no web fallback; on web, glow-web uses the SDK's `WebAuthnPrfProvider` directly
- Integration with glow-web via `PasskeyPrfProvider` interface — runtime detection swaps native vs. browser provider

## Phase 3: Native Secure Seed Storage

Stores the wallet seed in iOS Keychain / Android Keystore behind a biometric gate (Face ID / Touch ID / BiometricPrompt). On native, this replaces the plaintext localStorage mnemonic path and the per-launch passkey PRF roundtrip with a quick biometric unlock. On web the abstraction is a no-op — the existing localStorage / passkey re-derive flow runs unchanged.

**Architecture:**

- `glow-web/src/services/secureStorage.ts` — the single abstraction. Exports a `SecureStorage` interface (`isSupported`, `hasStoredSeed`, `storeSeed`, `retrieveSeed`, `clearSeed`), a typed `SecureStorageError` with codes for every fallback path (`USER_CANCELLED`, `BIOMETRIC_LOCKOUT`, `BIOMETRIC_NOT_ENROLLED`, `BIOMETRIC_UNAVAILABLE`, `KEY_INVALIDATED`, `NO_STORED_SEED`, `NOT_SUPPORTED`, `UNKNOWN`), and a module-level `secureStorage` singleton that resolves to `NativeSecureStorage` on Capacitor hosts or `NoopSecureStorage` everywhere else. No plugin details leak past this file.

- Backed by two `@aparajita` Capacitor plugins installed in `glow-app/package.json` AND `glow-web/package.json` (see "aparajita plugin import pattern" below for why both):
  - `@aparajita/capacitor-secure-storage` — wraps iOS Keychain + Android Keystore AES-GCM. Pinned to `KeychainAccess.whenUnlockedThisDeviceOnly` per `set` call, so the seed is device-local, never synced to iCloud, and not in encrypted backups.
  - `@aparajita/capacitor-biometric-auth` — wraps `LAContext` (iOS) / `BiometricPrompt` (Android). Invoked before every `retrieveSeed` call.

- Integration in `glow-web/src/hooks/useBreezSdk.ts`: the mount-time `checkForExistingWallet` tries `secureStorage.retrieveSeed` before falling through to the legacy localStorage / passkey paths. `connectWallet` takes a `source: ConnectSeedSource` parameter (`'onboarding'` vs `'secureStorage'`) so the post-connect persist block skips a redundant `storeSeed` write when the seed was just retrieved from the same store. `handleLogout` calls `clearSeed` alongside the existing `clearMnemonic` / `clearPasskeyMode`.

### ⚠️ Security: `loggingBehavior: 'production'` is REQUIRED

`capacitor.config.ts` pins `loggingBehavior: 'production'`. **Do not revert this.** Capacitor's default (`'debug'` in Debug builds) writes every plugin call's method data and return value to logcat (Android) / NSLog (iOS) at verbose level. Several plugin calls in this app pass wallet seed material through the bridge:

- `PasskeyPrf.derivePrfSeed` returns the 32-byte PRF entropy as base64.
- `SecureStorage.internalSetItem` (called by `NativeSecureStorage.storeSeed`) receives the plaintext mnemonic JSON blob.

With `'debug'`, the bridge logs those payloads in the clear to system log files readable by any process with `READ_LOGS` (granted to many OEM apps on Android, Console.app on iOS). `'production'` suppresses the verbose plugin call/return traces in every build configuration while leaving WebView `console.warn` / `console.error` bridging intact for debugging the structured logger's failure paths.

This is a defense-in-depth mitigation at the bridge layer. The proper fix (tracked as F2 in the follow-ups) is to keep plaintext seed material on the native side of the bridge entirely — until that lands, the config pin is the only safety net.

### aparajita plugin import pattern

The `@aparajita/capacitor-secure-storage` and `@aparajita/capacitor-biometric-auth` plugins **must be imported directly** in `glow-web/src/services/secureStorage.ts`, not accessed through `window.Capacitor.Plugins.*`. These plugins use a two-layer architecture: the public `set`/`get`/`remove`/`authenticate` methods live only on the JS side and wrap the low-level native `internal*` methods. Without importing the package, `registerPlugin()` is never called and `window.Capacitor.Plugins.*` only exposes the auto-generated native proxy — so `plugin.set` is undefined and any attempt to use the abstraction silently fails. (The existing `capacitor-passkey-prf` plugin works via `window.Capacitor.Plugins.*` because its native side implements the public methods directly.)

### TLA workaround (follow-up F3)

`secureStorage.ts` has a `biometricAuthReadyPromise` at module init that explicitly imports `@aparajita/capacitor-biometric-auth/dist/esm/base.js` and awaits its `__tla` export. Workaround for a `vite-plugin-top-level-await` bug: biometric-auth's `base.js` transitively imports `App` from `@capacitor/app`, which has a dynamic `import('./web')` in its `registerPlugin` block that trips the TLA transform. The plugin does not propagate `__tla` across dynamic-import chunk boundaries, so Capacitor's proxy lazy-loading `native.js` sees an undefined `BiometricAuthBase` and throws `Class extends value undefined is not a constructor or null` at module evaluation. Do not remove the pre-await without first verifying that the upstream bug is fixed or that biometric-auth has dropped its `@capacitor/app` dependency.

### Debugging breadcrumbs via `VITE_CONSOLE_LOGGING`

The structured logger in `glow-web/src/services/logger.ts` gates `console.*` output behind `isConsoleLoggingEnabled()`, which defaults to `import.meta.env.DEV` (on in `vite dev`, off in `vite build`). For test builds that need `[auth]` / `[sdk]` breadcrumbs visible in logcat / Console.app, set the env var at build time:

```bash
VITE_CONSOLE_LOGGING=true make deploy-android
VITE_CONSOLE_LOGGING=true make deploy-ios
```

Bridged `console.*` output still goes through Capacitor's `loggingBehavior: 'production'`, which preserves `warn`/`error` regardless. Do NOT set `VITE_CONSOLE_LOGGING=true` in production release builds.

### Outstanding follow-ups

Tracked in `~/.claude/plans/delightful-sleeping-marshmallow.md`:

- **F1**: Add a dedicated `UnlockPage` for native `USER_CANCELLED` / `BIOMETRIC_LOCKOUT` cases instead of falling through to the welcome screen. Reuse the layout pattern from `feat/password-encrypted-seed-storage`'s `UnlockPage.tsx`.
- **F2**: Keep plaintext seed material on the native side of the Capacitor bridge (opaque handle pattern) so the `loggingBehavior` pin becomes defense-in-depth rather than the only safety layer.
- **F3**: Fix the TLA propagation upstream in `vite-plugin-top-level-await`, or replace the aparajita biometric-auth dependency with a custom in-house Capacitor plugin in `plugins/`.

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
