#!/usr/bin/env bash
# Phase 4C — apply VERSION_NAME / VERSION_CODE to the iOS project.
#
# Reads VERSION_NAME + VERSION_CODE from the environment (set by
# scripts/ci/compute-version.sh in the prior CI step) and writes
# them to project.pbxproj via agvtool — Apple's blessed CLI for
# bumping version numbers without hand-parsing the pbxproj.
#
# Run order in CI matters:
#   1. cap sync ios          (rewrites Pods refs; preserves versions)
#   2. apply-ios-version.sh  (this script — writes versions)
#   3. fastlane match        (signing — reads project state)
#   4. fastlane build_app    (consumes the version + signing)
#
# Local invocation:
#   VERSION_NAME=0.1.0 VERSION_CODE=10000042 ./scripts/ci/apply-ios-version.sh

set -euo pipefail

VERSION_NAME="${VERSION_NAME:?VERSION_NAME must be set}"
VERSION_CODE="${VERSION_CODE:?VERSION_CODE must be set}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT/ios/App"

echo "Setting MARKETING_VERSION = $VERSION_NAME"
agvtool new-marketing-version "$VERSION_NAME"

echo "Setting CURRENT_PROJECT_VERSION = $VERSION_CODE (-all targets)"
agvtool new-version -all "$VERSION_CODE"

echo "Done. Verifying:"
agvtool what-marketing-version -terse1
agvtool what-version -terse
