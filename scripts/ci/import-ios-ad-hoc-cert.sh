#!/usr/bin/env bash
# import-ios-ad-hoc-cert.sh — set up a temporary macOS keychain
# and import the ad-hoc distribution cert + provisioning profile
# for the ios-preview CI job.
#
# Reads three secrets from the environment:
#   P12_BASE64                  base64-encoded .p12 distribution cert
#   KEYCHAIN_PASSWORD           unlock password for the temp keychain
#   PROVISIONING_PROFILE_BASE64 base64-encoded .mobileprovision
#
# Outputs:
#   - A keychain at $RUNNER_TEMP/glow-ci.keychain-db that xcodebuild
#     can use for signing.
#   - The provisioning profile installed under
#     ~/Library/MobileDevice/Provisioning Profiles/ so xcodebuild
#     can auto-select it.
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
echo -n "$P12_BASE64" | base64 --decode --output "$CERT_PATH"
echo -n "$PROVISIONING_PROFILE_BASE64" | base64 --decode --output "$PROFILE_PATH"

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
UUID="$(security cms -D -i "$PROFILE_PATH" | plutil -extract UUID xml1 -o - - | sed -n 's/.*<string>\(.*\)<\/string>.*/\1/p')"
cp "$PROFILE_PATH" "$PROFILES_DIR/$UUID.mobileprovision"

echo "==> iOS ad-hoc signing artifacts installed"
echo "    Keychain: $KEYCHAIN_PATH"
echo "    Profile UUID: $UUID"

# Emit GitHub Actions outputs if running in a GHA step.
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "keychain-path=$KEYCHAIN_PATH" >> "$GITHUB_OUTPUT"
  echo "profile-uuid=$UUID"           >> "$GITHUB_OUTPUT"
fi
