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

- **Bundle ID**: `technology.breez.glow` (release → TestFlight / App Store), `technology.breez.glow.dev` (debug). Per-configuration split in `ios/App/App.xcodeproj/project.pbxproj` (Debug config → `.dev`, Release config → `.glow`).
- **Entitlements**: `webcredentials:keys.breez.technology` (Associated Domains). The `.dev` bundle ID moves to the dev RP: see "Passkey relying party (RP) split".
- **Minimum deployment**: iOS 18 (passkey PRF requires it, and the App Store must not offer the app where the primary onboarding path cannot run). The plugin keeps its `@available(iOS 18.0, *)` guards so it stays consumable by iOS 15 hosts if it is ever published standalone.
- **AASA caching**: Apple caches `apple-app-site-association` aggressively. If passkeys fail on a new bundle ID, go to Settings → Developer → Associated Domains Development → toggle to force refresh.

### Android

- **Application ID**: base `technology.breez.glow` with `applicationIdSuffix ".dev"` on the debug buildType → debug APKs = `technology.breez.glow.dev`, release AABs = `technology.breez.glow`. Split via buildType suffix (no product flavors needed). Enables debug + release side-by-side installs on the same device.
- **Asset Links**: the RP's `/.well-known/assetlinks.json` must list the app's package + signing cert. Release goes on `keys.breez.technology`; `.dev` belongs on the dev RP, never the production one (see "Android debug signing")
- **Minimum SDK**: 24 (passkey PRF requires API 28+, runtime check)
- **Physical device required** — emulators can't complete WebAuthn registration

### `cap sync` vs `cap copy`

`cap sync` regenerates `CapApp-SPM/Package.swift` and may strip manual edits. However, our plugin's `Package.swift` is at the plugin root, so Capacitor auto-discovers it correctly. Use `make sync` (which runs `cap copy`) for just copying web assets without touching native configs.

## Troubleshooting

**"RP ID cannot be validated" (Android)**: The app's package name + signing cert must be in `keys.breez.technology/.well-known/assetlinks.json`. For local dev, use `technology.breez.glow.dev` which already has the debug signing cert registered.

**Swift BigNumber build error**: The SDK's `Swift-BigInt` dependency has a compatibility issue with Swift 6.3 when building via `swift build` on macOS. This doesn't affect Xcode builds for iOS — the plugin builds fine through `xcodebuild`.

**"Could not find web assets directory: ./www"**: You're running `cap` commands from the wrong directory. Always run from the glow-app root.

**WASM build fails with linked SDK**: `npm link` causes Vite polyfill resolution issues. Use the tgz install approach instead (`make web` handles this).

## Continuous integration

### Trigger model (build-on-demand, static-analysis-by-default)

The previous "every PR + every push builds everything" model burned
~2.9k billed minutes across a single 48-hour release cycle during
Phase 4C's release-0.0.1 → release-0.0.2 iteration. Most of that work
was redundant with what devs had already run locally before pushing.

Current model:

| Trigger | What runs | Billed cost |
|---|---|---|
| PR open/push | `web` only — tsc + lint + vitest + `cap doctor` + glow-web-submodule-pushed-to-origin check | ~3–5 min Linux |
| Push to `main` | **nothing** — admin-merged PRs already passed their gate | 0 |
| `release-*` tag push | Full release pipeline: warm-sdk-{linux,macos} + `web` + `android-release` (AAB to Play internal + closed + open testing) + `ios-release` (IPA → TestFlight) + `release-github` (draft GH Release) | ~450 min |
| `preview-*` / `rc-*` tag push | Firebase App Distribution: warm-sdk-{linux,macos} + `web` + `ios-preview` + `android-preview` | ~250 min |
| `workflow_dispatch` | Dev picks `target` × `distribution` × `version` × `dry_run` inputs (see table below) | varies |

**Trade-off**: a broken Android or iOS build can merge to `main` without
CI catching it. Release-tag push + explicit dispatch both catch it before
users see it. Devs own local build validation via `make deploy-ios` /
`make deploy-android` before pushing.

### CI dispatch reference (self-serve builds)

All build artifacts are produced on explicit dev intent, not auto-
triggered. Dispatch via **GitHub UI** (Actions → CI → Run workflow)
or `gh workflow run`:

**Inputs**

| Input | Values | Required | Notes |
|---|---|---|---|
| `target` | `ios` \| `android` \| `both` | yes | Platform(s) to build. `both` fires the matching iOS + Android jobs in parallel. |
| `distribution` | `none` \| `firebase` \| `store` | yes | `none` = unsigned compile/debug build, no upload. `firebase` = ad-hoc/debug → Firebase App Distribution (`.dev` bundle). `store` = app-store-signed → TestFlight (iOS) + Play internal/closed/open testing (Android). |
| `version` | `MAJOR.MINOR.PATCH` | only when `distribution=store` | Marketing version (e.g. `0.0.2`). Must match semver regex. |
| `dry_run` | `true` \| `false` | no (default `false`) | Only meaningful for `distribution=store`. Builds + signs + assembles artifacts but skips the TestFlight / Play upload step. Use to verify a release pipeline end-to-end without shipping. |

**Preset scenarios** (old `job` enum → new input combo)

| Intent | `target` | `distribution` | `version` | `dry_run` |
|---|---|---|---|---|
| iOS compile-check (was `ios-unsigned`) | `ios` | `none` | — | — |
| iOS Firebase (was `ios-firebase`) | `ios` | `firebase` | — | — |
| iOS TestFlight hotfix (was `ios-testflight`) | `ios` | `store` | `0.0.2` | — |
| Android debug APK (was `android-debug`) | `android` | `none` | — | — |
| Android Firebase (was `android-firebase`) | `android` | `firebase` | — | — |
| Android Play testing tracks (was `android-internal`) | `android` | `store` | `0.0.2` | — |
| Full release dry-run (was `full-release`) | `both` | `store` | `0.0.3` | `true` |
| Full release (pre-tag verification) | `both` | `store` | `0.0.3` | `false` |

The `web` static-analysis job (tsc + lint + vitest) fires alongside
every distribution-bound dispatch (`distribution=firebase` or
`distribution=store`, any target). `distribution=none` compile-check
dispatches skip it — their only job is to confirm the platform build
still compiles.

Example invocations:

```bash
# iOS hotfix TestFlight build for marketing version 0.0.2
gh workflow run ci.yml --ref main \
  -f target=ios -f distribution=store -f version=0.0.2

# Validate a PR branch builds on iOS before merging (no upload)
gh workflow run ci.yml --ref feat/my-branch \
  -f target=ios -f distribution=none

# Dry-run the full release pipeline from main (build + sign, no upload)
gh workflow run ci.yml --ref main \
  -f target=both -f distribution=store -f version=0.0.3 -f dry_run=true
```

### Running iOS validation on a PR

Add the `run-ios` label (UI or `gh pr edit N --add-label run-ios`).
That triggers `warm-sdk-macos` + `ios` (unsigned) so the PR gets an
iOS compile-check without needing a separate dispatch.

### Cutting a preview build

`preview-*` and `rc-*` tag pushes fire `ios-preview` + `android-preview`
(both → Firebase App Distribution). Manual equivalent via dispatch:
`target=ios distribution=firebase` or `target=android distribution=firebase`.

```bash
git tag preview-$(date +%Y%m%d)
git push origin preview-$(date +%Y%m%d)
```

Delete one-off test tags afterwards: `git push origin :refs/tags/preview-test1`.

Both jobs upload via the `firebase_app_distribution` fastlane
plugin (the approach Firebase's official docs recommend for
[iOS][fad-ios] and [Android][fad-android]). Each platform owns a
`:upload_firebase` lane at `<platform>/fastlane/Fastfile`; the
build step runs upstream (xcodebuild for iOS, Gradle for Android)
and fastlane handles only the upload.

[fad-ios]: https://firebase.google.com/docs/app-distribution/ios/distribute-fastlane
[fad-android]: https://firebase.google.com/docs/app-distribution/android/distribute-fastlane?apptype=apk

### Dependabot + CodeQL

- **Dependabot** (`.github/dependabot.yml`) — weekly npm (×4), Gradle,
  and `github-actions` update PRs.
- **CodeQL code scanning**: intentionally not shipped. Requires GitHub
  Advanced Security, not included in Breez-org's plan tier. Revisit if
  GHAS becomes available.
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

glow-app uses the existing Breez Firebase project for preview
distribution on both platforms — Ad Hoc IPA (iOS) + Debug APK
(Android) shipped to Firebase App Distribution.

| Thing | Value |
|-------|-------|
| Firebase project ID | `breez-technology` |
| Project number | `463327817067` |
| iOS app ("Glow Debug") — bundle ID | `technology.breez.glow.dev` |
| iOS app — Firebase App ID (stored in `FIREBASE_IOS_APP_ID_PREVIEW`) | `1:463327817067:ios:6a85ef20ff5f9860b2b02e` |
| Android app ("Glow Debug") — package | `technology.breez.glow.dev` |
| Android app — Firebase App ID (stored in `FIREBASE_ANDROID_APP_ID_PREVIEW`) | `1:463327817067:android:a89e8ebd1b81c7f1b2b02e` |
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
2. **Grant the service account `roles/firebaseappdistro.admin`**
   at project scope. GCP Console → IAM & Admin → IAM → find the
   principal by `client_email` from the JSON → Add role →
   "Firebase App Distribution Admin". Without this the upload
   fails with `HTTP 403 — The caller does not have permission`.
   Per-app scoping is not needed. Also confirm the Firebase
   Management API is enabled (APIs & Services → Enabled APIs) —
   a disabled API returns 403, not 404, a common red herring.
3. **Add testers** to the `internal` group:
   ```bash
   firebase appdistribution:testers:add \
     --project breez-technology \
     --group-aliases internal \
     tester1@example.com tester2@example.com
   ```
   Or via the Firebase console → App Distribution → Testers & Groups.

**Smoke test the SA locally** before dispatching CI:

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/tmp/fad-sa.json  # paste FIREBASE_SERVICE_ACCOUNT_KEY
firebase appdistribution:groups:list --project breez-technology
```

Should print at least `internal`. A 403 means the IAM grant from
step 2 hasn't landed — both `*-preview` jobs will surface the
same error when fastlane tries to upload.

### iOS preview signing — Apple Developer portal (one-time + ongoing)

`ios-preview` signs with an **Ad Hoc** provisioning profile
covering bundle ID `technology.breez.glow.dev` + every registered
tester UDID. The profile is held base64-encoded in
`IOS_PREVIEW_PROFILE_BASE64`. Do not use a Development or App
Store profile here — xcodebuild will fail with "not an 'iOS Ad
Hoc' profile" at the export step.

**One-time bootstrap** (first run after setting up FAD):

1. Grab your own UDID — one device is enough. Via Finder
   (connect → click device in sidebar → click text under model
   name to cycle to UDID → copy) or `idevice_id -l` with
   `libimobiledevice` installed.
2. Apple Developer portal → Devices → `+`, register under team
   `F7R2LZH3W5`.
3. Portal → Profiles → `+` → **Ad Hoc** (Distribution → Ad Hoc).
   App ID `technology.breez.glow.dev`, certificate = existing
   Apple Distribution cert (same bytes as
   `IOS_PREVIEW_CERT_P12_BASE64`), devices = all currently
   registered. Name it `Glow Dev Ad Hoc`. Download.
4. Rotate the secret:
   ```bash
   base64 -i "Glow_Dev_Ad_Hoc.mobileprovision" | \
     gh secret set IOS_PREVIEW_PROFILE_BASE64 --body -
   ```
   Cert + keychain secrets don't change.

**Ongoing new-tester loop**:

1. Invite by email (Firebase console or `firebase
   appdistribution:testers:add`). They click the invite on their
   iOS device → install the Firebase tester profile → Firebase
   auto-collects their UDID.
2. Firebase console → App Distribution → Testers & Groups → All
   testers → **Export Apple UDIDs** → download CSV.
3. Apple Developer portal → Devices → **Register Multiple
   Devices** → upload the CSV. (Or automate via fastlane's
   `register_devices` action against the same CSV.)
4. Portal → Profiles → edit `Glow Dev Ad Hoc` → tick new devices
   → regenerate → download.
5. Rotate `IOS_PREVIEW_PROFILE_BASE64` as in bootstrap step 4.
6. Re-dispatch or retag a preview build.

Apple Distribution cert expires 2026-12-23 — a cert rotation
forces a profile regeneration too. See "Certificate rotation"
below.

### Required repository secrets

Set via `gh secret set <NAME>` or the Settings → Secrets UI.

| Secret | Used by | Purpose |
|--------|---------|---------|
| `VITE_BREEZ_API_KEY` | android, ios, ios-preview, android-preview | Required. Breez SDK API key baked into the glow-web bundle at build time. Without it the SDK fails to init and mnemonic-based onboarding can't reach the wallet. Mirror the value from `glow-web/.env`. |
| `GRADLE_ENCRYPTION_KEY` | android, android-preview | Optional. Encrypts the Gradle configuration cache shared across runs. Falls back to unencrypted cache if unset. |
| `FIREBASE_IOS_APP_ID_PREVIEW` | ios-preview | Firebase App Distribution iOS app id (`1:NNNN:ios:HASH`). |
| `FIREBASE_ANDROID_APP_ID_PREVIEW` | android-preview | Firebase App Distribution Android app id (`1:NNNN:android:HASH`). |
| `FIREBASE_SERVICE_ACCOUNT_KEY` | ios-preview, android-preview | Firebase service-account JSON (raw, not base64). The SA MUST hold `roles/firebaseappdistro.admin` on the `breez-technology` project — fastlane surfaces a 403 otherwise. |
| `IOS_PREVIEW_KEYCHAIN_PASSWORD` | ios-preview | Arbitrary string; unlocks the temp keychain on the CI runner. |
| `IOS_PREVIEW_CERT_P12_BASE64` | ios-preview | Apple Distribution cert (`.p12`) base64-encoded. `base64 -i cert.p12 \| pbcopy` on macOS. Same bytes as `IOS_RELEASE_CERT_P12_BASE64`. |
| `IOS_PREVIEW_CERT_P12_PASSWORD` | ios-preview | `.p12` export passphrase (set when exporting from Keychain). |
| `IOS_PREVIEW_PROFILE_BASE64` | ios-preview | **Ad Hoc** provisioning profile (`.mobileprovision`) base64-encoded, covering bundle ID `technology.breez.glow.dev`. Must NOT be a Development or App Store profile. See "iOS preview signing — Apple Developer portal" above for the one-time + ongoing profile-regeneration loop. |

### Android debug signing

**Never commit a keystore to this repo** — release or debug.
`android/debug.keystore` used to be committed here; it has been
removed, and the cert it held is retired (see below).

By default, debug builds are signed with AGP's per-machine
`~/.android/debug.keystore`, auto-generated on first build. No
setup, no shared secret, and every developer has a different
cert.

When a *stable* debug cert is genuinely needed — the
`android-preview` CI job ships a debug APK to Firebase App
Distribution, and testers need passkey flows to work in it —
`signingConfigs.debug` in `android/app/build.gradle` reads one
from the environment, the same way `signingConfigs.release`
already does:

```
DEBUG_KEYSTORE_PATH
DEBUG_KEYSTORE_PASSWORD
DEBUG_KEY_ALIAS
DEBUG_KEY_PASSWORD
```

All four must be set or the block is skipped. CI decodes the
keystore from a GitHub secret to a `$RUNNER_TEMP` path and
exports these; the file never lands in the working tree. Treat
that keystore with the same care as the release upload key.

#### Why a debug cert is not "just a test fixture"

The rationale this section used to carry — "debug keystores can
only sign debug builds, so committing one is safe" — reasoned
only about the **Play-update boundary**: an attacker can't push
an update over a Play-installed release build. That's true, but
it's the wrong boundary for this app.

The one that matters is the **passkey RP boundary**. A signing
cert listed in an RP's `assetlinks.json` is a credential on that
RP, because this app derives its seed from a passkey PRF
evaluation gated on `(package name, signing cert)`. So a cert
registered on the RP carries the same weight as any other
production credential, not that of a shareable fixture. Two rules
follow:

1. **Debug and release must not share a passkey RP.** `.dev`
   builds use a dedicated dev RP so a debug cert never sits in the
   same file as production credentials. See "Passkey relying party
   (RP) split" below.
2. **Adding a `(package, cert)` pair to a passkey RP is a security
   decision, not a config change**, and should be reviewed as one.

The debug cert formerly committed here is **retired**: never
re-register it on any RP, and discard any copy recovered from git
history rather than reusing it. Removing the file from the repo is
not sufficient by itself; what closes it is the cert no longer
being trusted by any RP.

**Generating a replacement stable debug keystore** (only if
`android-preview` passkey testing actually needs one — otherwise
skip it and let each machine use its own):

1. Generate it **outside the repo**, with a real passphrase:
   ```bash
   keytool -genkeypair -v \
     -keystore ~/glow-debug-preview.jks \
     -alias glow-preview -keyalg RSA -keysize 4096 -validity 3650 \
     -dname "CN=glow-app Preview Debug, O=Breez, C=US"
   ```
2. Read its SHA-256:
   ```bash
   keytool -list -v -keystore ~/glow-debug-preview.jks -alias glow-preview | grep SHA256
   ```
3. Store it as GitHub secrets (`base64 -i ~/glow-debug-preview.jks`
   into `DEBUG_KEYSTORE_BASE64`, plus the password/alias secrets).
   Never add it to the working tree.
4. Register the fingerprint on the **dev RP only**
   (`keys-dev.breez.technology`), never on
   `keys.breez.technology`. Registering it on the production RP
   re-creates the exposure this section exists to prevent.

### Passkey relying party (RP) split

`.dev` builds are meant to run against a dedicated passkey RP,
`keys-dev.breez.technology`, so that a debug or preview signing
cert never appears in the same `assetlinks.json` as production
users' credentials. See "Android debug signing" above for why
this matters.

The app-side plumbing is in place; **the dev RP itself is not
serving yet**, so the wiring is staged and currently resolves to
the production RP (today's behaviour, nothing changed at runtime).

How it is wired:

- `glow-web` reads `VITE_NATIVE_PASSKEY_RP_ID` at build time
  (`src/services/passkeyPrfProvider.ts`). Unset or empty falls
  back to `keys.breez.technology`.
- `make web-dev` sets that var from `DEV_PASSKEY_RP_ID` in the
  `Makefile`. That variable is the single flip point.
- `make android` / `make ios` (both debug, both `.dev`) depend on
  `web-dev`. The four `.dev` CI jobs (`android`, `ios`,
  `ios-preview`, `android-preview`) run `make web-dev`; the two
  release jobs keep plain `make web`.

**Flip checklist**, once `keys-dev.breez.technology` serves both
well-known files:

1. Set `DEV_PASSKEY_RP_ID ?= keys-dev.breez.technology` in the
   `Makefile`. That covers local dev and all four CI jobs.
2. Add `webcredentials:keys-dev.breez.technology` to
   `ios/App/App/App.entitlements`. Associated Domains accepts
   multiple entries, so no per-configuration entitlements split is
   needed: the Release build declares the domain but cannot
   validate against it, because the dev RP's
   `apple-app-site-association` lists only `.dev` bundle IDs.
   Do not add this before the domain resolves.
3. Confirm the dev RP's `assetlinks.json` lists only `.dev`
   packages, and that the production RP lists no `.dev` package.

**Existing `.dev` passkeys are abandoned, not migrated.** A
passkey is cryptographically bound to its RP ID, and the seed is
derived from a PRF evaluation against that credential, so a
credential on the new RP derives a *different* seed, i.e. entirely
separate funds. There is no in-place migration and no way to add one. The
existing web passkey-migration flow does not apply either; it is
gated on `!Capacitor.isNativePlatform()`.

This is acceptable because `.dev` builds hold test funds only.
Anyone with a balance in a `.dev` build should back up the
recovery phrase and restore it into the new build by hand. Say so
in the release notes for the preview build that carries the flip.

## Release signing (Phase 4C)

Phase 4C wires release distribution to Play Store internal
testing + TestFlight via tag-triggered CI. The in-repo
plumbing landed on `feat/phase-4c-release`; activating it
requires a one-time external setup pass (Play app, ASC app,
upload keystore, iOS distribution cert + app-store profile, ASC
API key or legacy Apple ID creds).

### Android release signing

We use **Play App Signing** with Google holding the release key
(generated by Play during first AAB upload). We hold only the
**upload key** — easier to rotate via Play Console support if
ever lost or compromised.

#### Generate the upload keystore (one time)

```bash
keytool -genkey -v \
  -keystore glow-upload.jks \
  -storepass "$STRONG_PASSWORD" \
  -keypass "$STRONG_PASSWORD" \
  -alias glow-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Glow Upload Key, O=Breez, L=<city>, C=<country>"
```

Store the resulting `.jks` + passwords in the Breez keystore
recovery location (1Password vault or equivalent). Loss of the
upload key is RECOVERABLE via Play support but inconvenient —
treat it like any other production secret.

#### GitHub secrets to set

| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_BASE64` | `base64 -i glow-upload.jks \| pbcopy` |
| `RELEASE_KEYSTORE_PASSWORD` | store password |
| `RELEASE_KEY_ALIAS` | `glow-upload` |
| `RELEASE_KEY_PASSWORD` | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service account JSON, "Release manager" role on Play Console |
| `FIREBASE_ANDROID_APP_ID_PREVIEW` | Firebase Android app id (`1:NNNN:android:HASH`) — reuses `FIREBASE_SERVICE_ACCOUNT_KEY` from 4B |

#### Local sanity build (signed)

```bash
RELEASE_KEYSTORE_PATH=/abs/path/to/glow-upload.jks \
RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=glow-upload \
RELEASE_KEY_PASSWORD=... \
cd android && ./gradlew :app:bundleRelease
```

Without those four envs exported, the release signingConfig
falls back to the committed debug keystore so the build still
produces an installable AAB you can sanity-test (just not one
you can ship to Play).

#### One-time Play App Signing enrollment

After the first signed AAB exists:

1. Play Console → Internal testing → Create new release →
   Upload the AAB (UI). Play prompts to opt into Play App
   Signing → choose "Use Google-generated key".
2. Play Console → Setup → App integrity → App signing key
   certificate → copy the SHA-256 of the **release** cert.
3. Open a PR on `keys.bt.webauthn` to APPEND that SHA to the
   `technology.breez.glow.dev` entry in
   `.well-known/assetlinks.json`. Do NOT touch the
   `technology.breez.glow` entry.
4. Wait for the assetlinks file to redeploy + caches to flush
   (~5 min) before installing the release AAB on a device for
   passkey smoke-testing.

After enrollment, all subsequent uploads are CI-driven via
`gradle-play-publisher` 3.12.1 (pinned to the last 3.x release
because GPP 4.0.0 requires Android Gradle Plugin 9 and we
ship AGP 8).

### iOS release signing — direct-secrets pattern

Phase 4C uses the same direct-secrets pattern Phase 4B established
for ad-hoc (`ios-preview`): distribution cert + provisioning
profile live as base64-encoded GitHub secrets, imported into a
temp keychain on the CI runner by `scripts/ci/import-ios-cert.sh`.
No fastlane match, no cert git repo, no encryption-passphrase
secret. fastlane is retained ONLY for `upload_to_testflight`
(wraps altool + sets the ASC changelog post-upload).

The same Apple Distribution cert (SHA-1 `6C:97:AD:24…A5:BA`,
team `F7R2LZH3W5`) signs both ad-hoc and app-store exports;
only the `.mobileprovision` differs between the two flows. The
script is method-neutral — the calling CI step maps the right
secrets (`IOS_PREVIEW_*` for ad-hoc, `IOS_RELEASE_*` for
app-store) onto the script's generic env vars.

#### One-time setup (local, per person managing releases)

```bash
# Distribution cert — reuse the existing Apple Distribution cert
# (team-wide, not app-specific). If it's not in your Keychain,
# the Admin exports a .p12 from their Mac and shares via 1Password.
# Already-existing .p12 at ~/Downloads/AppStore/Certificates/BreezCertificate.p12.

# App Store provisioning profile for technology.breez.glow:
#   developer.apple.com → Profiles → + → App Store → bundle ID
#   technology.breez.glow → select Apple Distribution cert →
#   download .mobileprovision. App Manager role suffices.

# Fastlane (for upload_to_testflight):
cd ios/App
bundle install   # generate Gemfile.lock — needs Ruby 3.x via rbenv/asdf
```

#### GitHub secrets to set (iOS release)

Four cert+profile secrets + auth secrets:

| Secret | Value |
|--------|-------|
| `IOS_RELEASE_CERT_P12_BASE64` | `base64 -i BreezCertificate.p12 \| pbcopy` (same bytes as `IOS_PREVIEW_CERT_P12_BASE64`) |
| `IOS_RELEASE_CERT_P12_PASSWORD` | `.p12` export passphrase (same as preview) |
| `IOS_RELEASE_KEYCHAIN_PASSWORD` | `openssl rand -hex 16` — random, unique per pipeline |
| `IOS_RELEASE_PROFILE_BASE64` | `base64 -i Glow.mobileprovision \| pbcopy` (App Store profile for `technology.breez.glow`) |

Plus TestFlight upload auth — pick ONE path:

**Preferred: App Store Connect API key** (needs ASC Admin role
to generate; App Manager can consume once set)

| Secret | Value |
|--------|-------|
| `APP_STORE_CONNECT_API_KEY_ID` | Apple `.p8` key id (e.g. `XYZ1234567`, visible in filename `AuthKey_<KEY_ID>.p8`) |
| `APP_STORE_CONNECT_API_ISSUER_ID` | ASC team-wide Issuer ID (UUID) |
| `APP_STORE_CONNECT_API_KEY_BASE64` | `base64 -i AuthKey_XYZ.p8 \| pbcopy` |

**Fallback: legacy Apple ID + app-specific password** (App Manager role sufficient)

| Secret | Value |
|--------|-------|
| `FASTLANE_USER` | Apple ID email with role on the Breez ASC team |
| `FASTLANE_PASSWORD` | App-specific password (generated at appleid.apple.com → Sign-In & Security → App-Specific Passwords) |
| `FASTLANE_SESSION` | Optional — `fastlane spaceauth -u <email>` output for 2FA changelog updates. Expires every few weeks. |

The Fastfile's `env_present?` helper auto-selects: API key if all
three `APP_STORE_CONNECT_*` are set, else `FASTLANE_USER` +
`FASTLANE_PASSWORD`, else errors. Switching between paths is a
GitHub-secrets-only change — no code edits.

#### Profile renewal (annual)

App Store provisioning profiles expire 1yr after creation. When
`IOS_RELEASE_PROFILE_BASE64` no longer validates:

1. developer.apple.com → Profiles → find the expired one → Edit → regenerate (same cert + bundle ID).
2. Download the new `.mobileprovision`.
3. `base64 -i Glow.mobileprovision | gh secret set IOS_RELEASE_PROFILE_BASE64 --body -`.

Same procedure for the ad-hoc `IOS_PREVIEW_PROFILE_BASE64` when
adding new tester devices or when it expires.

#### Certificate rotation (annual-ish)

The Apple Distribution cert also expires annually. When it does:

1. Admin generates a new CSR on their Mac + creates a replacement cert on developer.apple.com.
2. Export `.p12` via Keychain Access → share via 1Password.
3. Base64-encode + overwrite BOTH `IOS_PREVIEW_CERT_P12_BASE64` AND `IOS_RELEASE_CERT_P12_BASE64` with the new bytes.
4. Update `IOS_*_CERT_P12_PASSWORD` if the new export used a different passphrase.
5. Regenerate both profiles (ad-hoc + app-store) since they embed the cert SHA-1 — re-upload the profile secrets.

#### Privacy manifest + export compliance

Phase 4C added `ios/App/App/PrivacyInfo.xcprivacy` declaring
the required-reason API entries for `@capacitor/filesystem`
(C617.1 + E174.1) and an `ITSAppUsesNonExemptEncryption=false`
to skip the encryption-export questionnaire on every
TestFlight upload.

**Manual one-time Xcode step**: open `ios/App/App.xcodeproj`
in Xcode, drag `PrivacyInfo.xcprivacy` into the App target
(Copy items if needed = unchecked; Add to targets = App).
Without this the file isn't bundled and Apple rejects the
upload with "Missing privacy manifest" feedback.

### Cutting a release

Once H1–H8 (Play app, ASC app, upload keystore, iOS cert + app-store
profile, ASC API key or legacy Apple ID creds, Firebase app ids)
are in place:

**Before tagging, wait for the post-merge main CI run to finish**
(Actions → CI, event `push` on `main`). Every main push warms the
SDK caches that tag builds restore from; tagging before that run
completes (~40 min cold after an SDK pin bump, well under a minute
otherwise) forces the release pipeline to rebuild the SDK from
scratch instead of inheriting main's cache.

```bash
git checkout main && git pull
git tag release-0.X.Y
git push origin release-0.X.Y
```

CI then runs:

1. `web` + `android` + `ios` (label-gated, so won't run unless
   pushed from a labeled-PR commit) — same as PR runs.
2. `android-release` — Gradle bundleRelease + Play upload.
3. `ios-release` — `import-ios-cert.sh` + `build-ios-ipa.sh Release app-store` + `fastlane upload` → TestFlight.
4. `release-github` — downloads the AAB + IPA artifacts,
   composes release notes from the annotated tag message (or
   from `git log` between this and the previous `release-*`
   tag when the tag carries none), and creates the GitHub
   Release as a **draft**. Publish it by hand once the store
   rollouts are approved: publishing is what hands the
   Play-signed universal APK to Obtainium and Zapstore.

Verification checklist:

- [ ] Play Console → Internal testing → new release with
      `versionCode = M*10_000_000 + m*100_000 + p*1_000 +
      (RUN_NUMBER % 1000)` and `versionName = M.m.p`.
- [ ] App Store Connect → TestFlight → Builds → new build
      "Ready to Test" after Apple processing (~15 min).
- [ ] GitHub Releases → `release-0.X.Y` with both artifacts.
- [ ] Smoke test on physical device: passkey onboarding,
      biometric unlock, send/receive Lightning payment.

If a release tag needs re-cutting (CI flake, etc.), delete
the tag locally + remotely and re-push — the
`(RUN_NUMBER % 1000)` suffix in the versionCode formula
guarantees the new build's versionCode is unique even on the
same semver, so Play won't reject the duplicate.

Local script note: `scripts/ci/compute-version.sh` hard-fails
if neither `GLOW_RELEASE_TAG` nor `GITHUB_REF_NAME` resolves to
a well-formed `release-MAJOR.MINOR.PATCH` tag. The previous
silent `0.0.0-dev` fallback was removed after it shipped a
`0.0.0` IPA to TestFlight on 2026-04-18. CI sets these
automatically; for local smoke tests, export the tag explicitly:

```bash
GLOW_RELEASE_TAG=release-0.1.0 ./scripts/ci/compute-version.sh
```

Dispatch-level input validation runs upfront in the
`validate-inputs` job (< 30s on a Linux runner) before any
macOS runner spins up, catching invalid combinations (e.g.
`distribution=store` without `version`, malformed `version`)
before the pipeline burns minutes on doomed builds.

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

### Reviewing the automated glow-web bump

The daily bump PR moves the `glow-web` submodule and, when the web SDK
dependency has moved, the `.spark-sdk-ref` pin that decides which SDK
commit the native apps build against. The second one is easy to skim
past, so it is worth a deliberate look.

The workflow refuses to write a pin that is not a 40-character hex sha,
and refuses one that does not resolve to a commit in `breez/spark-sdk`,
so a malformed or stale value stops the run instead of opening a PR.
That confirms the value resolves, not that it is the one intended.

Two approvals on that PR covers the rest, and it is a settings change:
add a rule for `chore/bump-glow-web` (or raise the `main` rule to 2)
under Settings > Branches. When reviewing, confirm a `.spark-sdk-ref`
change is one you were expecting rather than only that CI is green.

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
