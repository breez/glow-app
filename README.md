# Glow App

Native iOS/Android wrapper for [Glow](https://github.com/breez/glow-web) PWA.

Built with [Capacitor](https://capacitorjs.com/) to wrap the existing Glow PWA in a native shell with:
- **Native passkey PRF** — biometric authentication via ASAuthorization (iOS) and CredentialManager (Android), since WebAuthn is unavailable in WebViews
- **Keychain seed storage** — derived seed cached in iOS Keychain / Android Keystore with biometric protection for instant app launches
- **Push notifications** (planned) — notifications for Lightning address payments

## Architecture

```
glow-app/
  glow-web/                    # git submodule (breez/glow-web)
  ios/                         # Xcode project
  android/                     # Android Studio project
  plugins/
    capacitor-passkey-prf/     # Local Capacitor plugin for native passkey PRF
  capacitor.config.ts          # Capacitor configuration
```

The web app (glow-web) is built with Vite and the output (`glow-web/dist/`) is loaded into native WebViews by Capacitor. The passkey PRF plugin bridges to native passkey APIs since `navigator.credentials` is not available in WebViews.

## Prerequisites

- Node.js 22+
- For iOS: macOS with Xcode 16+ (iOS 18+ required for passkey PRF)
- For Android: Android Studio with SDK 28+ (API 28 = Android 9)

## Setup

```bash
# Clone with submodule
git clone --recursive https://github.com/breez/glow-app.git
cd glow-app

# If already cloned without --recursive:
git submodule update --init --recursive

# Install dependencies
npm install

# Build the web app
cd glow-web && npm install && npm run build && cd ..

# Sync web assets to native projects
npx cap sync
```

## Development

The passkey PRF plugin depends on local Spark SDK builds. See **[DEVELOPMENT.md](DEVELOPMENT.md)** for full setup instructions, or:

```bash
make setup    # full first-time setup
make ios      # build for iOS
make android  # build for Android
```

## Implementation Status

See [PLAN.md](PLAN.md) for the full implementation plan and current status.

| Phase | Status |
|-------|--------|
| 1. Capacitor Scaffold | Complete |
| 2. Passkey PRF Plugin | Complete |
| 3. Keychain Storage | Not Started |
| 4. Polish & Distribution | Not Started |
