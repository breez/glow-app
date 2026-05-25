# Glow App — Native Capacitor Wrapper

Capacitor wrapper around the glow-web React + WASM Bitcoin wallet,
distributed natively on iOS and Android. Architectural detail lives
in [CLAUDE.md](CLAUDE.md); this file tracks what's next.

## Status

| Phase | Status | Notes |
|-------|--------|-------|
| 1. Capacitor scaffold | ✅ | |
| 2. Passkey PRF plugin | ✅ | Thin bridge over the Spark SDK's platform-native `PasskeyProvider`. |
| 3. Native secure vault | ✅ | In-house `capacitor-native-vault`, OS-enforced biometric binding, dedicated unlock screen. |
| 4A. App polish | ✅ | Branding, native shell, soft keyboard, Android back button, biometric stuck-state recovery. |
| 4B. CI | ✅ | GitHub Actions (PR + tag-triggered), Spark SDK pinned via `.spark-sdk-ref`, Dependabot. |
| 4C. Distribution (internal) | ✅ | TestFlight + Play Internal Testing on `release-*` tags. |
| 5. Push notifications | 🔜 | Lightning address payment notifications. |
| 6. Public store publishing | 🔜 | App Store review + Play Production. |

## Phase 5: Push Notifications

- `@capacitor/push-notifications` + APNs / FCM setup, channel /
  category config, deep-link handler into the payment detail.
- Server contract: address server (or whatever hosts `breez.tips`)
  emits one push per inbound Lightning-address payment.
- In-app permission prompt with a "Not now" escape; settings toggle
  to disable.

## Phase 6: Public Store Publishing

- App Store: TestFlight → external testers → review. Privacy
  nutrition labels, age rating, screenshots, marketing text, support
  URL; recheck export-compliance if crypto primitives changed.
- Play Store: Play Internal → Production. Store listing, content
  rating, data-safety form, target-API audit.
- Extend `scripts/ci/release-notes.sh` to populate ASC release notes
  alongside Play.
- Crash-reporting gate (Sentry / Crashlytics) wired on both platforms
  before public release.

## Carry-overs

- **Opaque-handle bridge** — keep PRF entropy on the native side and
  pass an opaque handle to JS so the seed never crosses the bridge
  in plaintext. Replaces the `loggingBehavior: 'none'` defense-in-depth
  pin in CLAUDE.md.
- **Publish in-house plugins** — `capacitor-passkey-prf` +
  `capacitor-native-vault` as standalone npm packages once the API
  surface is glow-agnostic.
- **Upstream patch-package patches** — drop
  `patches/@capacitor+keyboard+8.0.3.patch` when ionic-team/capacitor-keyboard#60 lands.
- **Deep links** — `bitcoin:` / `lightning:` / `glow://` schemes.
- **Haptics** — `@capacitor/haptics` on payment success / QR scan / copy.
