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

## Continuous integration (Phase 4B)

GitHub Actions runs on every PR and on pushes to `main`. Four
jobs plus Dependabot update PRs:

| Job | Runner | When it runs | What it does |
|-----|--------|--------------|--------------|
| `web` | ubuntu | every PR / main push | `tsc --noEmit` + `npm run lint` + `npm run test` against the pinned glow-web submodule SHA |
| `android` | ubuntu | every PR / main push | `gradle :app:assembleDebug`; uploads `app-debug.apk` (7-day retention) |
| `ios` | macos-15 | main pushes + PRs labelled `run-ios` | `xcodebuild` unsigned device build |
| `ios-preview` | macos-15 | `preview-*` / `rc-*` tags + manual dispatch | Archive + export IPA, upload to Firebase App Distribution |

Plus **Dependabot** (`.github/dependabot.yml`) — weekly npm
(×4), Gradle, and `github-actions` update PRs.

> **CodeQL code scanning**: intentionally not shipped. Code
> scanning on private repos requires GitHub Advanced Security,
> which isn't included in Breez-org's plan tier. Revisit if
> GHAS becomes available.

### Cutting a preview build

iOS previews are distributed via Firebase App Distribution to
the `internal` tester group. Triggers:

```bash
# Tag-based (recommended for scheduled drops)
git tag preview-$(date +%Y%m%d)
git push origin preview-$(date +%Y%m%d)

# Or manual from the Actions UI:
#   Actions → CI → Run workflow → main → ios-preview
```

After the run, invitees receive a FAD email with an install
link. Delete one-off test tags afterwards to keep the release
list clean: `git push origin :refs/tags/preview-test1`.

### Running iOS on a PR

Add the `run-ios` label (UI or `gh pr edit N --add-label run-ios`).
Removing it mid-PR cancels the active run via concurrency. The
`ios` job "skipped" status counts as passing for branch
protection, so unlabelled PRs can still merge.

### Bumping the Spark SDK pin

The repo pins the spark-sdk commit in `.spark-sdk-ref`. CI keys
its artifact cache on that SHA. To move onto a newer SDK
commit:

```bash
# Land + push the SDK change, note the new HEAD SHA.
echo "<40-char sha>" > .spark-sdk-ref
git add .spark-sdk-ref
git commit -m "chore(sdk): bump pin to <short-sha>"
```

For active SDK-side development without updating the pin on
every commit: `SPARK_SDK_ALLOW_DRIFT=1 make resolve-sdk`.

### Firebase setup

glow-app uses the existing Breez Firebase project for ad-hoc
iOS previews.

| Thing | Value |
|-------|-------|
| Firebase project ID | `breez-technology` |
| Project number | `463327817067` |
| iOS app ("Glow Debug") — bundle ID | `com.breez.spark.glow.dev` |
| iOS app — Firebase App ID (stored in `FIREBASE_IOS_APP_ID`) | `1:463327817067:ios:6a85ef20ff5f9860b2b02e` |
| Android app ("Glow Debug") — package | `com.breez.spark.glow.dev` |
| Android app — Firebase App ID (reserved for Phase 4C) | `1:463327817067:android:a89e8ebd1b81c7f1b2b02e` |
| Tester group alias | `internal` |

To reproduce or verify with `firebase-tools`:

```bash
firebase login
firebase projects:list
firebase apps:list --project breez-technology
firebase apps:sdkconfig IOS 1:463327817067:ios:6a85ef20ff5f9860b2b02e \
  --project breez-technology   # prints the GoogleService-Info plist
```

**Remaining one-time setup** (outside the CLI):

1. **Service-account key**: Firebase console → Project Settings →
   Service accounts → Generate new private key (JSON). Store
   the raw JSON (not base64) as the `FIREBASE_SERVICE_ACCOUNT_KEY`
   GitHub secret.
2. **Add testers** to the `internal` group:
   ```bash
   firebase appdistribution:testers:add \
     --project breez-technology \
     --group-aliases internal \
     tester1@example.com tester2@example.com
   ```
   Or via the Firebase console → App Distribution → Testers & Groups.

### Required repository secrets

Set via `gh secret set <NAME>` or the Settings → Secrets UI.

| Secret | Used by | Purpose |
|--------|---------|---------|
| `VITE_BREEZ_API_KEY` | android, ios, ios-preview | Required. Breez SDK API key baked into the glow-web bundle at build time. Without it the SDK fails to init and mnemonic-based onboarding can't reach the wallet. Mirror the value from `glow-web/.env`. |
| `GRADLE_ENCRYPTION_KEY` | android | Optional. Encrypts the Gradle configuration cache shared across runs. Falls back to unencrypted cache if unset. |
| `FIREBASE_IOS_APP_ID` | ios-preview | Firebase App Distribution iOS app id (`1:NNNN:ios:HASH`). |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | ios-preview | Firebase service-account JSON (raw, not base64). |
| `IOS_PREVIEW_KEYCHAIN_PASSWORD` | ios-preview | Arbitrary string; unlocks the temp keychain on the CI runner. |
| `IOS_PREVIEW_CERT_P12_BASE64` | ios-preview | Ad-hoc distribution cert (`.p12`) base64-encoded. `base64 -i cert.p12 \| pbcopy` on macOS. |
| `IOS_PREVIEW_CERT_P12_PASSWORD` | ios-preview | `.p12` export passphrase (set when exporting from Keychain). |
| `IOS_PREVIEW_PROFILE_BASE64` | ios-preview | Ad-hoc provisioning profile (`.mobileprovision`) base64-encoded. |

### Android debug signing

`android/debug.keystore` is a **shared project debug keystore**
committed to the repo. It's explicitly NOT a secret — treat it
the way you'd treat any public test fixture.

Subject line (for identification):
`CN=glow-app Shared Debug Keystore, O=Breez, C=US`

Fingerprints:

```
SHA-1   : ED:85:05:FF:85:99:23:82:4F:5A:B6:75:1D:28:AF:8F:13:17:E5:D9
SHA-256 : C8:55:12:70:A6:78:2F:1F:43:91:2F:0B:EF:F0:1F:AD:9F:54:23:67:
          9C:9D:70:74:AE:74:F4:FD:E7:86:EE:43
```

Gradle's `signingConfigs.debug` in `android/app/build.gradle`
points at this keystore, with the well-known debug credentials
(`android` / `android`). Every build — local, CI, contributor
checkout — signs with the same SHA-256, which is registered
alongside `com.breez.spark.glow.dev` in
`keys.breez.technology/.well-known/assetlinks.json` so passkey
authentication works.

**Why committing it is safe** (even when this repo goes public):

- Debug keystores can only sign debug builds. They cannot push
  an update over a Play-installed release build (different
  cert + Play App Signing protect that boundary).
- The release signing key lives in GitHub Actions secrets
  (Phase 4C), never in the repo.
- The real attack gate is "can the attacker get their APK onto
  a victim's device?" — not "can the attacker reproduce the
  debug signature?" Android App Links + passkeys bind
  `RP domain + package name + signing cert`; anyone cloning
  the repo can build an APK with the same cert, but to exploit
  the passkey flow they'd also need a victim who sideloads
  their clone.
- This is the canonical convention. Prior art:
  [CoreProc/android-debug-keystore](https://github.com/CoreProc/android-debug-keystore),
  Google's own [App Links verification guide](https://developer.android.com/training/app-links/verify-android-applinks)
  (recommends listing debug + release fingerprints
  comma-separated in assetlinks), and most OSS Android /
  Capacitor / React Native repos that ship a checked-in debug
  keystore for exactly this reason.

**Rotating the keystore**: if this keystore ever needs to
change (e.g. stronger algorithm, expiry, one-off incident):

1. Generate a new keystore:
   ```bash
   keytool -genkey -v \
     -keystore android/debug.keystore \
     -storepass android -alias androiddebugkey -keypass android \
     -keyalg RSA -keysize 2048 -validity 36500 \
     -dname "CN=glow-app Shared Debug Keystore, O=Breez, C=US"
   ```
2. Extract the new SHA-256:
   ```bash
   keytool -list -v -keystore android/debug.keystore \
     -alias androiddebugkey -storepass android | grep SHA256
   ```
3. **Add** (don't replace) the new fingerprint to the
   `com.breez.spark.glow.dev` entry in
   `keys.breez.technology/.well-known/assetlinks.json`.
   Keep the old fingerprint live for a grace period so
   devs with older checkouts can still authenticate.
4. After the grace period and all installs have picked up
   the new APK, the old fingerprint can be removed from
   assetlinks.

## Branch protection (Phase 4B)

Configure on `main` via Settings → Branches → Branch protection
rules → Add rule (pattern: `main`):

- **Require pull request reviews before merging**: 1 approval;
  dismiss stale approvals on new commits.
- **Require status checks to pass before merging**:
  - `Web (glow-web submodule)`
  - `Android (assembleDebug)`
  - `iOS (xcodebuild, unsigned)` — **skipped = passing**
    (label-gated, so docs-only / web-only PRs skip without
    blocking merge).
- **Require branches to be up to date before merging**.
- **Require conversation resolution before merging**.
- **Restrict who can push to `main`**: maintainers only.
- **Do not allow bypassing the above settings**.

Reproducible via `gh api --method PUT`:

```bash
gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  repos/breez/glow-app/branches/main/protection \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Web (glow-web submodule)",
      "Android (assembleDebug)",
      "iOS (xcodebuild, unsigned)"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "required_conversation_resolution": true,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON
```

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
