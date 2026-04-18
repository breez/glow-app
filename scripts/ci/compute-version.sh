#!/usr/bin/env bash
# Phase 4C — derive VERSION_NAME / VERSION_CODE from a release tag.
#
# Tag format: release-MAJOR.MINOR.PATCH (e.g. release-0.1.0).
# Outputs are appended to $GITHUB_ENV so downstream steps in the
# same job can read them via env. The script also echoes the values
# for log visibility.
#
# versionCode formula (D3 in glow-app-phase-4c.md):
#   MAJOR * 10_000_000 + MINOR * 100_000 + PATCH * 1_000
#                      + (GITHUB_RUN_NUMBER % 1000)
#
# Why the run-number suffix:
#   Play requires versionCode monotonicity across uploads. If we
#   re-tag (`release-0.1.0` deleted then re-pushed) or ship a
#   hotfix rebuild on the same semver, the bare semver-derived
#   code would collide. RUN_NUMBER % 1000 gives ~1000 rebuild
#   slots per semver before collision, which is overkill in
#   practice but cheap insurance.
#
# Override precedence:
#   1. $GLOW_RELEASE_TAG  — workflow-settable. MUST be used when a CI
#      step needs to impersonate a release-tag push (iOS-only hotfix
#      dispatch is the canonical case). GitHub Actions silently ignores
#      attempts to set GITHUB_REF_NAME via $GITHUB_ENV because it's a
#      default env var — which broke the first iOS hotfix dispatch and
#      shipped a CFBundleShortVersionString of 0.0.0 to TestFlight.
#      Prefer this var for anything that wants to lie about the ref.
#   2. $GITHUB_REF_NAME   — what GitHub exports natively on tag pushes.
#   3. release-0.0.0-dev  — local dev fallback.
#
# Local invocation:
#   GITHUB_REF_NAME=release-0.1.0 GITHUB_RUN_NUMBER=42 ./scripts/ci/compute-version.sh
#   GLOW_RELEASE_TAG=release-0.1.0 ./scripts/ci/compute-version.sh

set -euo pipefail

TAG="${GLOW_RELEASE_TAG:-${GITHUB_REF_NAME:-release-0.0.0-dev}}"

if [[ "$TAG" =~ ^release-([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  MAJOR="${BASH_REMATCH[1]}"
  MINOR="${BASH_REMATCH[2]}"
  PATCH="${BASH_REMATCH[3]}"
else
  echo "Tag '$TAG' is not release-MAJOR.MINOR.PATCH — using placeholder 0.0.0-dev" >&2
  MAJOR=0
  MINOR=0
  PATCH=0
fi

RUN="${GITHUB_RUN_NUMBER:-0}"

VERSION_NAME="${MAJOR}.${MINOR}.${PATCH}"
VERSION_CODE=$(( MAJOR * 10000000 + MINOR * 100000 + PATCH * 1000 + (RUN % 1000) ))

echo "VERSION_NAME=$VERSION_NAME"
echo "VERSION_CODE=$VERSION_CODE"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "VERSION_NAME=$VERSION_NAME"
    echo "VERSION_CODE=$VERSION_CODE"
  } >> "$GITHUB_ENV"
fi
