#!/usr/bin/env bash
# build-ios-ipa.sh — produce an ad-hoc signed IPA for Firebase App
# Distribution.
#
# Expects import-ios-ad-hoc-cert.sh to have been run first (keychain
# + provisioning profile installed). Writes:
#   build/App.xcarchive/ — archive output
#   build/App.ipa        — exported IPA
#
# Arguments:
#   $1  configuration (default: Debug)

set -euo pipefail

CONFIGURATION="${1:-Debug}"
BUILD_DIR="build"
ARCHIVE_PATH="$BUILD_DIR/App.xcarchive"
EXPORT_OPTIONS_PATH="$BUILD_DIR/exportOptions.plist"

mkdir -p "$BUILD_DIR"

# Minimal ad-hoc exportOptions.plist. The provisioning profile
# installed by import-ios-ad-hoc-cert.sh is auto-picked by
# xcodebuild when method=ad-hoc is set here.
cat > "$EXPORT_OPTIONS_PATH" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>ad-hoc</string>
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

echo "==> xcodebuild archive ($CONFIGURATION)"
xcodebuild archive \
  -project ios/App/App.xcodeproj \
  -scheme App \
  -configuration "$CONFIGURATION" \
  -destination "generic/platform=iOS" \
  -archivePath "$ARCHIVE_PATH" \
  -allowProvisioningUpdates \
  | xcpretty || true  # xcpretty is optional; fall through on missing

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
