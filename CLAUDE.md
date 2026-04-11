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
# Build web app
cd glow-web && npm run build && cd ..

# Sync to native projects
npx cap sync

# Open in IDE
npx cap open ios      # Xcode
npx cap open android  # Android Studio
```

## Architecture

- `capacitor.config.ts` — Capacitor config, webDir points to `glow-web/dist`
- `plugins/capacitor-passkey-prf/` — Local Capacitor plugin bridging native passkey PRF APIs
  - iOS: Swift, based on Spark SDK's `PlatformPasskeyPrfProvider` (ASAuthorization + PRF extension)
  - Android: Kotlin, based on Spark SDK's `CredentialManagerPrfCore` (CredentialManager + PRF extension)
  - Web fallback: delegates to `navigator.credentials` WebAuthn API
- Integration with glow-web via `PasskeyPrfProvider` interface — factory pattern detects native vs. web

## Key References

- Spark SDK PR #781 (`pr/passkey-core`) — native PRF provider implementations we reuse
- Default RP ID: `keys.breez.technology` — cross-platform credential sharing domain
- iOS requires: Associated Domains entitlement `webcredentials:keys.breez.technology`, iOS 18+
- Android requires: `assetlinks.json` on RP domain, minSdk 28 with Play Services

## Common Tasks

### After glow-web changes
```bash
cd glow-web && git pull && npm install && npm run build && cd .. && npx cap sync
```

### Update glow-web submodule to latest main
```bash
cd glow-web && git checkout main && git pull && cd .. && git add glow-web && git commit -m "chore: update glow-web submodule"
```
