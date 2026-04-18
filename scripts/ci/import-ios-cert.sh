#!/usr/bin/env bash
# import-ios-cert.sh — set up a temporary macOS keychain and import
# a distribution cert + provisioning profile for an iOS CI build.
#
# Used by BOTH `ios-preview` (ad-hoc) and `ios-release` (app-store)
# jobs — the same Apple Distribution cert signs both export methods;
# only the profile + the `method` passed to build-ios-ipa.sh differ.
# The calling CI step maps the appropriate repo secret
# (IOS_PREVIEW_* or IOS_RELEASE_*) to the generic env vars below.
#
# Reads four env vars:
#   P12_BASE64                  base64-encoded .p12 distribution cert
#   P12_PASSWORD                .p12 export passphrase (optional, default empty)
#   KEYCHAIN_PASSWORD           unlock password for the temp keychain
#   PROVISIONING_PROFILE_BASE64 base64-encoded .mobileprovision
#
# Outputs:
#   - A keychain at $RUNNER_TEMP/glow-ci.keychain-db that xcodebuild
#     can use for signing.
#   - The provisioning profile installed under
#     ~/Library/MobileDevice/Provisioning Profiles/ so xcodebuild
#     can auto-select it by bundle ID + team ID.
#
# This script runs only on macOS CI runners. It expects a fresh
# runner each invocation; no cleanup between runs is required.

set -euo pipefail

: "${P12_BASE64:?P12_BASE64 must be set}"
: "${KEYCHAIN_PASSWORD:?KEYCHAIN_PASSWORD must be set}"
: "${PROVISIONING_PROFILE_BASE64:?PROVISIONING_PROFILE_BASE64 must be set}"

RUNNER_TEMP="${RUNNER_TEMP:-$(mktemp -d)}"
KEYCHAIN_PATH="$RUNNER_TEMP/glow-ci.keychain-db"
CERT_PATH="$RUNNER_TEMP/distribution.p12"
PROFILE_PATH="$RUNNER_TEMP/glow-preview.mobileprovision"

echo "==> Decoding cert + profile"
# Strip any whitespace (newlines, spaces, tabs) before decoding —
# base64 output from macOS `base64 -i file` wraps at 76 chars, and
# GitHub secret storage may introduce LF/CRLF on set. BSD's
# `base64 --decode --output` errors with "failed to decode message"
# on whitespace-contaminated input. Stripping first is safe: valid
# base64 only uses [A-Za-z0-9+/=].
printf '%s' "$P12_BASE64" | tr -d '[:space:]' | base64 --decode --output "$CERT_PATH"
printf '%s' "$PROVISIONING_PROFILE_BASE64" | tr -d '[:space:]' | base64 --decode --output "$PROFILE_PATH"

echo "==> Creating temp keychain"
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"
security set-keychain-settings -lut 21600 "$KEYCHAIN_PATH"  # 6h lock timeout
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"

echo "==> Importing distribution cert"
security import "$CERT_PATH" \
  -P "${P12_PASSWORD:-}" \
  -A \
  -t cert \
  -f pkcs12 \
  -k "$KEYCHAIN_PATH"

# Add the temp keychain to the search list so xcodebuild finds it,
# and grant codesign access to the imported key (no password prompt).
security list-keychains -d user -s "$KEYCHAIN_PATH" $(security list-keychains -d user | tr -d '"')
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN_PATH"

echo "==> Installing provisioning profile"
PROFILES_DIR="$HOME/Library/MobileDevice/Provisioning Profiles"
mkdir -p "$PROFILES_DIR"

# Extract metadata from the decoded profile plist. xcodebuild needs
# Profile Name (or UUID) + Team ID to select the right profile when
# we switch to Manual signing (default xcodebuild errors with
# "Signing for App requires a development team" otherwise).
PROFILE_PLIST="$(security cms -D -i "$PROFILE_PATH")"
UUID="$(echo "$PROFILE_PLIST" | plutil -extract UUID xml1 -o - - | sed -n 's/.*<string>\(.*\)<\/string>.*/\1/p')"
PROFILE_NAME="$(echo "$PROFILE_PLIST" | plutil -extract Name xml1 -o - - | sed -n 's/.*<string>\(.*\)<\/string>.*/\1/p')"
TEAM_ID="$(echo "$PROFILE_PLIST" | plutil -extract TeamIdentifier.0 xml1 -o - - | sed -n 's/.*<string>\(.*\)<\/string>.*/\1/p')"
cp "$PROFILE_PATH" "$PROFILES_DIR/$UUID.mobileprovision"

echo "==> iOS signing artifacts installed"
echo "    Keychain:       $KEYCHAIN_PATH"
echo "    Profile UUID:   $UUID"
echo "    Profile Name:   $PROFILE_NAME"
echo "    Team ID:        $TEAM_ID"

# Emit GitHub Actions outputs if running in a GHA step.
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "keychain-path=$KEYCHAIN_PATH"
    echo "profile-uuid=$UUID"
    echo "profile-name=$PROFILE_NAME"
    echo "team-id=$TEAM_ID"
  } >> "$GITHUB_OUTPUT"
fi

# Also export to GITHUB_ENV so downstream xcodebuild invocations can
# read them without cross-step outputs. build-ios-ipa.sh uses these
# to construct the Manual-signing build setting overrides.
if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "PROVISIONING_PROFILE_SPECIFIER=$PROFILE_NAME"
    echo "DEVELOPMENT_TEAM=$TEAM_ID"
  } >> "$GITHUB_ENV"
fi
