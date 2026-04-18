#!/usr/bin/env bash
# build-ios-ipa.sh — produce a signed IPA.
#
# Expects import-ios-cert.sh to have been run first (keychain +
# provisioning profile installed). Writes:
#   build/App.xcarchive/ — archive output
#   build/App.ipa        — exported IPA
#
# Arguments:
#   $1  configuration — Debug | Release (default: Debug)
#   $2  export method — ad-hoc | app-store (default: ad-hoc)
#
# Export method maps to downstream distribution:
#   ad-hoc     → Firebase App Distribution (ios-preview job)
#   app-store  → TestFlight (ios-release job)
# The installed provisioning profile MUST match the chosen method
# (ad-hoc profile for ad-hoc export, app-store profile for app-store
# export) or xcodebuild will fail to find a usable profile.

set -euo pipefail

CONFIGURATION="${1:-Debug}"
METHOD="${2:-ad-hoc}"

case "$METHOD" in
  ad-hoc|app-store) ;;
  *) echo "error: unsupported method '$METHOD' (expected ad-hoc | app-store)" >&2; exit 2 ;;
esac

BUILD_DIR="build"
ARCHIVE_PATH="$BUILD_DIR/App.xcarchive"
EXPORT_OPTIONS_PATH="$BUILD_DIR/exportOptions.plist"

mkdir -p "$BUILD_DIR"

# Minimal exportOptions.plist. The provisioning profile installed
# by import-ios-cert.sh is auto-picked by xcodebuild once method
# here matches the profile type.
cat > "$EXPORT_OPTIONS_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>${METHOD}</string>
  <key>compileBitcode</key>
  <false/>
  <key>signingStyle</key>
  <string>manual</string>
  <key>stripSwiftSymbols</key>
  <true/>
  <key>thinning</key>
  <string>&lt;none&gt;</string>
</dict>
</plist>
EOF

echo "==> xcodebuild archive ($CONFIGURATION, method=$METHOD)"

# Manual signing via the pre-installed profile. import-ios-cert.sh
# (upstream CI step) extracts the profile Name + Team ID from the
# .mobileprovision and exports them to GITHUB_ENV; we read them here
# and pass them as xcodebuild build-setting overrides.
#
# The committed pbxproj has `CODE_SIGN_STYLE = Automatic` (Xcode
# default for new Capacitor projects) + no DEVELOPMENT_TEAM, so
# xcodebuild fails without these overrides:
#
#   "Signing for App requires a development team. Select a
#    development team in the Signing & Capabilities editor."
#
# We DON'T touch the pbxproj — this pattern lets devs keep the
# project open in Xcode with automatic signing for local dev while
# CI forces deterministic manual signing.
#
# -allowProvisioningUpdates is intentionally NOT passed: we want CI
# to fail loudly if the installed profile doesn't match, rather than
# silently downloading a new one from Apple (which would require
# ASC credentials we're not always providing).
XCODE_BUILD_SETTINGS=(
  "CODE_SIGN_STYLE=Manual"
  "DEVELOPMENT_TEAM=${DEVELOPMENT_TEAM:?DEVELOPMENT_TEAM must be set (run import-ios-cert.sh first)}"
  "PROVISIONING_PROFILE_SPECIFIER=${PROVISIONING_PROFILE_SPECIFIER:?PROVISIONING_PROFILE_SPECIFIER must be set (run import-ios-cert.sh first)}"
  # "Apple Distribution" matches both ad-hoc and app-store certs;
  # our single cert is of this exact identity.
  "CODE_SIGN_IDENTITY=Apple Distribution"
)

# Pipe through xcpretty when available for human-readable output, but
# don't mask xcodebuild's exit code when it's missing. The previous
# `| xcpretty || true` swallowed both xcpretty-missing AND archive
# failures, hiding real errors.
if command -v xcpretty >/dev/null 2>&1; then
  set -o pipefail
  xcodebuild archive \
    -project ios/App/App.xcodeproj \
    -scheme App \
    -configuration "$CONFIGURATION" \
    -destination "generic/platform=iOS" \
    -archivePath "$ARCHIVE_PATH" \
    "${XCODE_BUILD_SETTINGS[@]}" \
    | xcpretty
else
  xcodebuild archive \
    -project ios/App/App.xcodeproj \
    -scheme App \
    -configuration "$CONFIGURATION" \
    -destination "generic/platform=iOS" \
    -archivePath "$ARCHIVE_PATH" \
    "${XCODE_BUILD_SETTINGS[@]}"
fi

echo "==> xcodebuild -exportArchive"
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportOptionsPlist "$EXPORT_OPTIONS_PATH" \
  -exportPath "$BUILD_DIR"

# exportArchive names the IPA after the scheme by default.
if [[ -f "$BUILD_DIR/App.ipa" ]]; then
  echo "==> IPA: $BUILD_DIR/App.ipa"
else
  echo "error: IPA not produced at $BUILD_DIR/App.ipa" >&2
  ls -la "$BUILD_DIR" >&2
  exit 1
fi
