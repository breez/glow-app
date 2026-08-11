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

# Prefer the annotated-tag message when invoked on a tag we distribute
# from AND the tag has a non-empty annotation body. Lets a maintainer
# push hand-curated notes via:
#   git tag -a release-X.Y.Z -m "What's new
#
#   * Bullet 1
#   * Bullet 2"
# preview-* and rc-* are included because those go to testers through
# Firebase App Distribution, where raw commit subjects are the wrong
# register for the audience just as they are on a store listing.
# Strips a leading PGP signature block if the tag is signed, and
# trims trailing whitespace.
if [[ "$CURRENT" == release-* || "$CURRENT" == preview-* || "$CURRENT" == rc-* ]] \
   && [[ "$(git cat-file -t "$CURRENT" 2>/dev/null || true)" == "tag" ]]; then
  # Annotated tag: %(contents) returns the user-supplied message.
  # (For lightweight tags it would fall through to the tagged commit's
  # message, which is wrong for our purposes.)
  ANNOTATION="$(git tag -l --format='%(contents)' "$CURRENT" 2>/dev/null \
    | sed -e '/^-----BEGIN PGP SIGNATURE-----$/,/^-----END PGP SIGNATURE-----$/d' \
    || true)"
  if [[ -n "$(printf '%s' "$ANNOTATION" | tr -d '[:space:]')" ]]; then
    printf '%s\n' "$ANNOTATION"
    exit 0
  fi
fi

# Find the most recent release-* tag that ISN'T the current one.
# Empty when this is the first release-* tag — fall back to "all
# of HEAD" for the initial cut.
PREV=$(git tag --list 'release-*' --sort=-version:refname \
       | grep -v "^${CURRENT}\$" \
       | head -1 \
       || true)

if [[ -n "$PREV" ]]; then
  RANGE="${PREV}..HEAD"
else
  RANGE="HEAD"
fi

echo "What's new"
echo ""

# awk instead of `head -40` because `head` closes its stdin after
# reading 40 lines, causing git log to receive SIGPIPE → exit 141.
# Combined with set -o pipefail at the top of this script, that
# aborts the whole pipeline on long histories. awk consumes the
# full stream and filters, avoiding the SIGPIPE race.
git log "$RANGE" \
  --pretty=format:'- %s' \
  --no-merges \
  --invert-grep --grep='^chore(release):' \
  | awk 'NR<=40'
