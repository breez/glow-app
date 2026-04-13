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

- `capacitor.config.ts` — Capacitor config, webDir points to `glow-web/dist`
- `plugins/capacitor-passkey-prf/` — Local Capacitor plugin bridging native passkey PRF APIs
  - iOS: Swift, wraps SDK's `PlatformPasskeyPrfProvider` (ASAuthorization + PRF extension)
  - Android: Kotlin, wraps SDK's `CredentialManagerPrfProvider` (CredentialManager + PRF extension)
  - Native-only — no web fallback; on web, glow-web uses the SDK's `WebAuthnPrfProvider` directly
- Integration with glow-web via `PasskeyPrfProvider` interface — runtime detection swaps native vs. browser provider

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
