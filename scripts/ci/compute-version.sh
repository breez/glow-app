#!/usr/bin/env bash
# Derive VERSION_NAME / VERSION_CODE for every CI trigger path.
#
# Source of truth: package.json `"version"` field at repo root. That's the
# app-side basis version — maintainers bump it in-tree, then tag
# release-X.Y.Z on the bump commit. CI reads package.json and validates
# release tags against it so a "forgot to bump" or "tagged the wrong SHA"
# mistake fails loudly instead of shipping a stale version.
#
# VERSION_NAME rules:
#   release-X.Y.Z tag          → X.Y.Z (must equal package.json version)
#   GLOW_RELEASE_TAG override   → X.Y.Z (must equal package.json version) —
#                                 used when a dispatch with distribution=store
#                                 needs to impersonate a release-tag push.
#   preview-N tag               → {basis}-preview.N
#   rc-N tag                    → {basis}-rc.N
#   everything else             → {basis}-dev.{short-sha}
#   (basis is package.json's version; short-sha is $GITHUB_SHA[:7] or
#    `git rev-parse --short HEAD` locally.)
#
# VERSION_CODE:
#   GITHUB_RUN_NUMBER          (CI — monotonic, distinct per run; Play's
#                               monotonicity requirement is satisfied
#                               trivially)
#   1                          (local invocation with no GITHUB_RUN_NUMBER)
#
# Hard-fail cases (preserve the 2026-04-18 guardrail that stopped a 0.0.0 IPA
# from shipping to TestFlight):
#   - GLOW_RELEASE_TAG set but not release-X.Y.Z → caller asked for release
#     mode but fed garbage. Exit 1.
#   - release-X.Y.Z tag (via either env) whose X.Y.Z ≠ package.json version
#     → basis drift. Exit 1. This catches the "tagged 0.0.4 but forgot to
#     bump package.json" class of bug.
#   - package.json missing "version" field → exit 1 (shouldn't happen).
#
# Local invocation:
#   ./scripts/ci/compute-version.sh
#     → reads package.json, dev mode with short-sha suffix, VERSION_CODE=1
#   GLOW_RELEASE_TAG=release-0.0.3 ./scripts/ci/compute-version.sh
#     → release mode; must match package.json version
#   GITHUB_REF_NAME=preview-42 GITHUB_RUN_NUMBER=7 ./scripts/ci/compute-version.sh
#     → preview mode

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PACKAGE_JSON="$REPO_ROOT/package.json"

if [[ ! -f "$PACKAGE_JSON" ]]; then
  echo "::error::compute-version.sh: $PACKAGE_JSON not found." >&2
  exit 1
fi

# Minimal JSON parse — avoids a `jq` dependency on the runner. Matches
# `"version": "X.Y.Z"` with optional whitespace.
BASIS_VERSION="$(
  sed -nE 's/^[[:space:]]*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' \
    "$PACKAGE_JSON" | head -n1
)"

if [[ -z "$BASIS_VERSION" ]]; then
  echo "::error::compute-version.sh: package.json has no \"version\" field." >&2
  exit 1
fi

# Effective tag: GLOW_RELEASE_TAG override wins (dispatch+store path), else
# GITHUB_REF_NAME (native tag push), else empty (branch push / PR / local).
TAG="${GLOW_RELEASE_TAG:-${GITHUB_REF_NAME:-}}"

# Short SHA for dev-mode version strings. Prefer GITHUB_SHA (always present
# in CI); fall back to git locally.
SHORT_SHA=""
if [[ -n "${GITHUB_SHA:-}" ]]; then
  SHORT_SHA="${GITHUB_SHA:0:7}"
elif command -v git >/dev/null 2>&1 && git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  SHORT_SHA="$(git -C "$REPO_ROOT" rev-parse --short=7 HEAD 2>/dev/null || echo "unknown")"
else
  SHORT_SHA="unknown"
fi

validate_release_match() {
  local tag_version="$1"
  if [[ "$tag_version" != "$BASIS_VERSION" ]]; then
    # %0A = literal newline inside a single GitHub Actions annotation —
    # keeps basis-drift as ONE error block instead of 4 separate entries
    # in the run summary.
    echo "::error title=Basis-version drift::release tag version '$tag_version' does not match package.json version '$BASIS_VERSION'.%0AThe in-repo basis version is the source of truth — bump package.json to '$tag_version' in the same commit the release-$tag_version tag points at, then re-push." >&2
    exit 1
  fi
}

if [[ -n "${GLOW_RELEASE_TAG:-}" ]]; then
  # Caller explicitly asked for release mode. Malformed override = hard-fail
  # (was the class of bug that shipped a 0.0.0 IPA on 2026-04-18).
  if ! [[ "$GLOW_RELEASE_TAG" =~ ^release-([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "::error::compute-version.sh: GLOW_RELEASE_TAG='$GLOW_RELEASE_TAG' must match" >&2
    echo "::error::release-MAJOR.MINOR.PATCH (e.g. release-0.0.3)." >&2
    exit 1
  fi
  validate_release_match "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
  VERSION_NAME="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
  MODE="release"
elif [[ "$TAG" =~ ^release-([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  validate_release_match "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
  VERSION_NAME="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
  MODE="release"
elif [[ "$TAG" =~ ^preview-(.+)$ ]]; then
  VERSION_NAME="${BASIS_VERSION}-preview.${BASH_REMATCH[1]}"
  MODE="preview"
elif [[ "$TAG" =~ ^rc-(.+)$ ]]; then
  VERSION_NAME="${BASIS_VERSION}-rc.${BASH_REMATCH[1]}"
  MODE="rc"
else
  # Branch push, PR, dispatch-firebase/none, random non-release tag, local
  # dev. Not an upload path to App Store / Play — suffix is informative,
  # doesn't need to satisfy Apple's strict CFBundleShortVersionString rules.
  VERSION_NAME="${BASIS_VERSION}-dev.${SHORT_SHA}"
  MODE="dev"
fi

VERSION_CODE="${GITHUB_RUN_NUMBER:-1}"

echo "MODE=$MODE"
echo "BASIS_VERSION=$BASIS_VERSION"
echo "VERSION_NAME=$VERSION_NAME"
echo "VERSION_CODE=$VERSION_CODE"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "VERSION_NAME=$VERSION_NAME"
    echo "VERSION_CODE=$VERSION_CODE"
  } >> "$GITHUB_ENV"
fi
