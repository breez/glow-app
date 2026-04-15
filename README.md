# Glow App

Native iOS/Android wrapper for [Glow](https://github.com/breez/glow-web) PWA.

Built with [Capacitor](https://capacitorjs.com/) to wrap the existing Glow PWA in a native shell with:
- **Native passkey PRF** — biometric authentication via ASAuthorization (iOS) and CredentialManager (Android), since WebAuthn is unavailable in WebViews
- **Native secure vault** — wallet seed stored in iOS Keychain / Android Keystore with biometric binding enforced by the OS, not just a JS-side gate. Adding a new Face ID / Touch ID / fingerprint enrollment voids the stored seed automatically
- **Dedicated unlock screen** — cancelling the biometric prompt lands on a retry/abandon flow instead of dropping the user onto onboarding, where they could accidentally create a new passkey label and orphan the stored wallet
- **Push notifications** (planned) — notifications for Lightning address payments

## Architecture

```
glow-app/
  glow-web/                    # git submodule (breez/glow-web)
  ios/                         # Xcode project
  android/                     # Android Studio project
  plugins/
    capacitor-passkey-prf/     # Native passkey PRF bridge (ASAuthorization / CredentialManager)
    capacitor-native-vault/    # Biometric-bound Keychain / Keystore seed storage
  capacitor.config.ts          # Capacitor configuration
```

The web app (glow-web) is built with Vite and the output (`glow-web/dist/`) is loaded into native WebViews by Capacitor. Two in-house Capacitor plugins bridge to native APIs that aren't available in WebViews: `capacitor-passkey-prf` for passkey + PRF extension, and `capacitor-native-vault` for biometric-bound secure storage.

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

| Phase | Status | Notes |
|-------|--------|-------|
| 1. Capacitor Scaffold | Complete | |
| 2. Passkey PRF Plugin | Complete | Merged in #1 |
| 3. Native Secure Vault | Complete | Merged in #2 (initial aparajita-backed version) and #3 (in-house `capacitor-native-vault` plugin, OS-enforced biometric binding, dedicated unlock screen, Capacitor `loggingBehavior` security fix) |
| 4. Polish & Distribution | Not Started | App icons, splash screen, CI, TestFlight / Play Store internal testing |
