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
#
# Hard fail: if neither is set (or the value is not release-X.Y.Z), the
# script exits 1. An earlier version fell back to `0.0.0-dev` silently —
# on 2026-04-18 an ios-hotfix dispatch with a malformed tag slipped past
# this fallback and shipped a 0.0.0 IPA to TestFlight. The fallback is
# removed so every version-bearing build has to name a real release.
#
# Local invocation:
#   GITHUB_REF_NAME=release-0.1.0 GITHUB_RUN_NUMBER=42 ./scripts/ci/compute-version.sh
#   GLOW_RELEASE_TAG=release-0.1.0 ./scripts/ci/compute-version.sh

set -euo pipefail

TAG="${GLOW_RELEASE_TAG:-${GITHUB_REF_NAME:-}}"

if [[ -z "$TAG" ]]; then
  echo "::error::compute-version.sh: neither GLOW_RELEASE_TAG nor GITHUB_REF_NAME is set." >&2
  echo "::error::Set one to release-MAJOR.MINOR.PATCH (e.g. release-0.1.0). The previous" >&2
  echo "::error::silent 0.0.0-dev fallback has been removed (it shipped a 0.0.0 IPA to" >&2
  echo "::error::TestFlight on 2026-04-18). For local smoke testing, export GLOW_RELEASE_TAG." >&2
  exit 1
fi

if ! [[ "$TAG" =~ ^release-([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "::error::compute-version.sh: tag '$TAG' does not match release-MAJOR.MINOR.PATCH." >&2
  echo "::error::Got: '$TAG'. Expected format: release-0.1.0 (no suffix, pre-release, or build metadata)." >&2
  exit 1
fi

MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"

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
