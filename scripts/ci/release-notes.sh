#!/usr/bin/env bash
# Phase 4C — generate release notes from `git log` between the
# previous release-* tag and the current ref.
#
# Output: a bullet list of commit subjects, suitable for:
#   - Play Store (gradle-play-publisher reads from
#     android/app/src/main/play/release-notes/<locale>/<track>.txt)
#   - TestFlight (passed via fastlane upload_to_testflight changelog)
#   - GitHub Release body
#
# Heuristics:
#   - merges excluded (--no-merges): squash-merged PRs already
#     show up as direct commits on main; merge commits are noise.
#   - chore(release): commits excluded: bumps that don't represent
#     user-facing change.
#   - capped at 40 lines: TestFlight has a 4000-char limit and
#     Play has a 500-char limit per locale; long lists get
#     truncated either way, so it's better to fail fast and force
#     the maintainer to handwrite a summary in those cases.
#
# Local invocation:
#   GITHUB_REF_NAME=release-0.1.0 ./scripts/ci/release-notes.sh

set -euo pipefail

CURRENT="${GITHUB_REF_NAME:-HEAD}"

# Find the most recent release-* tag that ISN'T the current one.
# Empty when this is the first release-* tag — fall back to "all
# of HEAD" for the initial cut.
PREV=$(git tag --list 'release-*' --sort=-version:refname \
       | grep -v "^${CURRENT}\$" \
       | head -1 \
       || true)

if [[ -n "$PREV" ]]; then
  RANGE="${PREV}..HEAD"
  HEADER="Changes since ${PREV}:"
else
  RANGE="HEAD"
  HEADER="Initial release:"
fi

echo "$HEADER"
echo ""

git log "$RANGE" \
  --pretty=format:'- %s' \
  --no-merges \
  --invert-grep --grep='^chore(release):' \
  | head -40
