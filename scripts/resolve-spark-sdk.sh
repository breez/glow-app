#!/usr/bin/env bash
# resolve-spark-sdk.sh — materialize the pinned spark-sdk checkout.
#
# Reads the commit SHA from .spark-sdk-ref (first non-comment,
# non-blank line). Then:
#
#   1. If $SPARK_SDK_DIR (default: ../spark-sdk) does not exist:
#      clone https://github.com/breez/spark-sdk.git and check out
#      the pinned SHA. Uses --filter=blob:none for a faster clone
#      on CI runners — we only care about the working tree of one
#      commit.
#
#   2. If $SPARK_SDK_DIR exists: verify its HEAD matches the pinned
#      SHA. If not, exit with a loud error listing the drift. This
#      catches the common case where a dev hand-bumps ../spark-sdk
#      without updating .spark-sdk-ref. Set SPARK_SDK_ALLOW_DRIFT=1
#      to suppress the check for intentional SDK-side development.
#
# Environment overrides:
#   SPARK_SDK_DIR           default ../spark-sdk (relative to cwd)
#   SPARK_SDK_REMOTE_URL    default https://github.com/breez/spark-sdk.git
#   SPARK_SDK_ALLOW_DRIFT   set to 1 to skip the HEAD-matches-pin check
#
# Exit codes:
#   0  success (clone happened, or existing checkout matches pin)
#   1  drift detected and not allowed
#   2  .spark-sdk-ref missing or malformed
#   3  git operations failed

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REF_FILE="$REPO_ROOT/.spark-sdk-ref"

if [[ ! -f "$REF_FILE" ]]; then
  echo "error: $REF_FILE not found" >&2
  exit 2
fi

# Read the first non-comment, non-blank line as the SHA. grep -v's
# order matters — strip comments first, then blanks, then take one.
PINNED_SHA="$(grep -vE '^\s*(#|$)' "$REF_FILE" | head -n 1 | tr -d '[:space:]')"
if [[ -z "$PINNED_SHA" ]]; then
  echo "error: no SHA found in $REF_FILE" >&2
  exit 2
fi
# Sanity-check the SHA shape (40 hex chars); abbreviations are rejected
# because downstream `git checkout` on abbreviations is ambiguous.
if [[ ! "$PINNED_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: malformed SHA in $REF_FILE: '$PINNED_SHA'" >&2
  echo "       (expected a 40-character lowercase-hex git commit SHA)" >&2
  exit 2
fi

SPARK_SDK_DIR="${SPARK_SDK_DIR:-$REPO_ROOT/../spark-sdk}"
SPARK_SDK_REMOTE_URL="${SPARK_SDK_REMOTE_URL:-https://github.com/breez/spark-sdk.git}"

if [[ ! -d "$SPARK_SDK_DIR" ]]; then
  echo "==> $SPARK_SDK_DIR missing — cloning $SPARK_SDK_REMOTE_URL @ $PINNED_SHA"
  # --filter=blob:none gives a partial clone: commits + trees but
  # no blobs until `git checkout` pulls them. Cuts the clone time
  # roughly in half for a large repo like spark-sdk.
  git clone --filter=blob:none "$SPARK_SDK_REMOTE_URL" "$SPARK_SDK_DIR"
  git -C "$SPARK_SDK_DIR" checkout "$PINNED_SHA"
  echo "==> spark-sdk ready at $SPARK_SDK_DIR"
  exit 0
fi

if ! git -C "$SPARK_SDK_DIR" rev-parse --git-dir >/dev/null 2>&1; then
  echo "error: $SPARK_SDK_DIR exists but is not a git checkout" >&2
  exit 3
fi

CURRENT_SHA="$(git -C "$SPARK_SDK_DIR" rev-parse HEAD)"
if [[ "$CURRENT_SHA" == "$PINNED_SHA" ]]; then
  echo "==> spark-sdk @ $SPARK_SDK_DIR already at pinned SHA"
  exit 0
fi

if [[ "${SPARK_SDK_ALLOW_DRIFT:-}" == "1" ]]; then
  echo "==> spark-sdk HEAD drifted from pin (SPARK_SDK_ALLOW_DRIFT=1, allowing)"
  echo "    pinned:  $PINNED_SHA"
  echo "    current: $CURRENT_SHA"
  exit 0
fi

cat >&2 <<EOF
error: spark-sdk HEAD does not match the pin in $REF_FILE.

  pinned:  $PINNED_SHA
  current: $CURRENT_SHA
  dir:     $SPARK_SDK_DIR

To fix, one of:
  - Check out the pinned SHA:
      git -C "$SPARK_SDK_DIR" fetch && \\
      git -C "$SPARK_SDK_DIR" checkout $PINNED_SHA
  - Or bump the pin to the current SHA (intentional SDK bump):
      echo $CURRENT_SHA > $REF_FILE
      git add $REF_FILE
  - Or set SPARK_SDK_ALLOW_DRIFT=1 to skip this check for local dev.
EOF
exit 1
