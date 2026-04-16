# Development Setup

This guide covers building glow-app locally with the native passkey PRF plugin. The plugin depends on [Spark SDK PR #781](https://github.com/breez/spark-sdk/pull/781) (`pr/passkey-core` branch), which requires local SDK builds until it's published.

## Prerequisites

- **Node.js** 22+
- **Rust** (latest stable) with cross-compilation targets
- **Xcode** 16+ (for iOS)
- **Android Studio** with NDK installed (for Android)
- **JDK 21** — Capacitor 8 and the Android Gradle plugin require Java 21.
  If you use Android Studio, its bundled JDK at
  `/Applications/Android Studio.app/Contents/jbr/Contents/Home` is 21+
  and the Makefile defaults `JAVA_HOME` there automatically. If you run
  Gradle outside of Android Studio, `brew install openjdk@21` and
  export `JAVA_HOME`.
- **ANDROID_HOME** environment variable set (typically `~/Library/Android/sdk`)

Install Rust iOS/Android targets:
```bash
rustup target add aarch64-apple-ios x86_64-apple-ios aarch64-apple-ios-sim
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
```

### `npm install` patches node_modules

Two Capacitor packages are patched locally via
[`patch-package`](https://www.npmjs.com/package/patch-package) on every
`npm install`:

- `patches/@capacitor+keyboard+8.0.3.patch` — applies
  [ionic-team/capacitor-keyboard#30](https://github.com/ionic-team/capacitor-keyboard/issues/30)
  /  [PR #60](https://github.com/ionic-team/capacitor-keyboard/pull/60)
  (unmerged upstream). Fixes the plugin's FrameLayout resize so the
  WebView height is restored on keyboard hide via the inset listener
  path, not just via the animation callback.
- `patches/@capacitor+android+8.3.0.patch` — patches Capacitor core's
  built-in `SystemBars` plugin to stop applying `imeInsets.bottom` as
  padding on the WebView parent. Android's
  `windowSoftInputMode="adjustResize"` already shrinks the activity
  content frame; applying IME insets again double-subtracts the
  keyboard height and leaves a visible grey gap.

The patches re-apply via the `postinstall` script in `package.json`.
If you edit node_modules directly, re-run
`npx patch-package @capacitor/keyboard` / `@capacitor/android` to
regenerate the patch file.

## Quick Start

```bash
# Full setup from scratch (see Makefile for individual targets)
make setup
```

Or step by step:

### 1. Clone and initialize

```bash
git clone --recursive https://github.com/breez/glow-app.git
cd glow-app

# Materialize the pinned spark-sdk commit alongside (sibling dir).
# Reads the SHA from .spark-sdk-ref and clones + checks out, or
# verifies an existing ../spark-sdk checkout matches the pin.
make resolve-sdk
```

#### Bumping the spark-sdk pin

`make resolve-sdk` is pinned to the exact commit recorded in
`.spark-sdk-ref` at the glow-app repo root. When you need to
move glow-app onto a newer spark-sdk commit:

```bash
# 1. Land the SDK change on its branch, note the new HEAD SHA.
cd ../spark-sdk && git rev-parse HEAD

# 2. Write it into the pin, commit in glow-app.
cd ../glow-app
echo "<new 40-char sha>" > .spark-sdk-ref
git add .spark-sdk-ref
git commit -m "chore(sdk): bump pin to <short-sha>"
```

CI keys its SDK artifact cache on `.spark-sdk-ref`, so the
first run after a pin bump rebuilds the SDK; subsequent runs
hit the cache. During local development on a new SDK branch,
`SPARK_SDK_ALLOW_DRIFT=1 make resolve-sdk` bypasses the HEAD
check so you can iterate without updating the pin on every
SDK commit.

### 2. Environment variables

```bash
cp glow-web/example.env glow-web/.env
# Edit glow-web/.env and set VITE_BREEZ_API_KEY
```

### 3. Build Spark SDK

The plugin depends on SDK native libraries built locally.

**iOS** — cross-compile + generate Swift bindings + populate xcframework:
```bash
make sdk-ios
```

**Android** — cross-compile + publish AAR to mavenLocal:
```bash
make sdk-android
```

**WASM** — build web SDK package (needed for glow-web's `WebAuthnPrfProvider` + `passkey-prf-provider` export):
```bash
make sdk-wasm
```

Or build all three:
```bash
make sdk
```

### 4. Build web app

Installs the SDK WASM tgz into glow-web and runs vite build:
```bash
make web
```

### 5. Build native apps

```bash
make ios       # build for iOS device
make android   # build Android debug APK
```

### 6. Deploy to devices

```bash
make deploy-ios       # build + install on connected iOS device
make deploy-android   # build + install on connected Android device
```

## SDK Build Details

### What gets built and where

| Platform | SDK Build Command | Output | Consumed By |
|----------|------------------|--------|-------------|
| iOS | `make sdk-ios` | Swift bindings + xcframework binary | Plugin's `Package.swift` → local SDK path |
| Android | `make sdk-android` | AAR in `~/.m2/repository` | Plugin's `build.gradle` → `mavenLocal()` |
| WASM | `make sdk-wasm` | tgz in `spark-sdk/packages/wasm/` | `glow-web/package.json` → file install |

### iOS SDK build steps (what `make sdk-ios` does)

1. Cross-compile Rust for `aarch64-apple-ios` (release)
2. Build bindings crate with uniffi feature
3. Run `uniffi-bindgen` to generate Swift from the release dylib
4. Copy generated `.swift` files into SDK Swift package
5. Copy FFI headers into xcframework
6. Copy compiled binary into xcframework

### Android SDK build steps (what `make sdk-android` does)

1. Build the SDK Android library with Gradle
2. Publish to mavenLocal as `breez_sdk_spark:bindings-android:0.1.0-local`

### WASM SDK build steps (what `make sdk-wasm` does)

1. Run `cargo xtask package wasm::all` in spark-sdk
2. Produces tgz at `spark-sdk/packages/wasm/breeztech-breez-sdk-spark-v0.1.0.tgz`

## Platform Notes

### iOS

- **Bundle ID**: `com.breez.spark.glow` (prod), `com.breez.spark.glow.dev` (dev — has debug cert in assetlinks)
- **Entitlements**: `webcredentials:keys.breez.technology` (Associated Domains)
- **Minimum deployment**: iOS 15 (passkey PRF requires iOS 18+, graceful fallback on older)
- **AASA caching**: Apple caches `apple-app-site-association` aggressively. If passkeys fail on a new bundle ID, go to Settings → Developer → Associated Domains Development → toggle to force refresh.

### Android

- **Application ID**: `com.breez.spark.glow` (prod), `com.breez.spark.glow.dev` (dev — has debug cert in assetlinks)
- **Asset Links**: `keys.breez.technology/.well-known/assetlinks.json` must list the app's package + signing cert
- **Minimum SDK**: 24 (passkey PRF requires API 28+, runtime check)
- **Physical device required** — emulators can't complete WebAuthn registration

### `cap sync` vs `cap copy`

`cap sync` regenerates `CapApp-SPM/Package.swift` and may strip manual edits. However, our plugin's `Package.swift` is at the plugin root, so Capacitor auto-discovers it correctly. Use `make sync` (which runs `cap copy`) for just copying web assets without touching native configs.

## Troubleshooting

**"RP ID cannot be validated" (Android)**: The app's package name + signing cert must be in `keys.breez.technology/.well-known/assetlinks.json`. For local dev, use `com.breez.spark.glow.dev` which already has the debug signing cert registered.

**Swift BigNumber build error**: The SDK's `Swift-BigInt` dependency has a compatibility issue with Swift 6.3 when building via `swift build` on macOS. This doesn't affect Xcode builds for iOS — the plugin builds fine through `xcodebuild`.

**"Could not find web assets directory: ./www"**: You're running `cap` commands from the wrong directory. Always run from the glow-app root.

**WASM build fails with linked SDK**: `npm link` causes Vite polyfill resolution issues. Use the tgz install approach instead (`make web` handles this).

---

<details>
<summary><strong>AI-Assisted Setup (Claude Code)</strong></summary>

Paste this prompt into Claude Code to automate the full setup:

```
Set up the glow-app development environment from scratch. Follow DEVELOPMENT.md.

Steps:
1. Ensure glow-app submodules are initialized
2. Clone spark-sdk to ../spark-sdk if not present, checkout pr/passkey-core branch
3. Create glow-web/.env from example.env (ask me for the VITE_BREEZ_API_KEY value)
4. Run `make sdk` to build Spark SDK for all platforms (iOS, Android, WASM)
5. Run `make web` to build glow-web with the SDK
6. Run `make ios` and `make android` to build native apps
7. Deploy to connected devices with `make deploy-ios` and `make deploy-android`

If any step fails, diagnose and fix before proceeding.
```

</details>
