#!/usr/bin/env bash
# Install a build tool from a pinned release archive, checksum first.
#
#   scripts/ci/install-pinned-tool.sh <url> <sha256>
#
# Replaces the `curl … | sh` installers the SDK warmers used to run. Those
# installed whatever the URL served at the time, so the toolchain a build used
# was not reproducible and not recorded anywhere. Naming an exact archive and
# refusing to proceed unless its bytes hash to the expected value removes the
# moving part: the pin is the artifact, not a URL that happens to resolve to
# it today.
#
# Archives are the upstream projects' own release builds. Every executable in
# the extracted directory lands in ~/.cargo/bin, since these tools ship helper
# binaries alongside the main one and expect to find them on PATH.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }

URL=${1:-}
WANT=${2:-}
[[ -n "$URL" && -n "$WANT" ]] || die "usage: install-pinned-tool.sh <url> <sha256>"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

curl --retry 3 -L --proto '=https' --tlsv1.2 -sSf -o "$WORK/archive" "$URL" \
  || die "download failed: $URL"

# sha256sum on Linux runners, shasum on macOS ones.
if command -v sha256sum >/dev/null; then
  got=$(sha256sum "$WORK/archive" | cut -d' ' -f1)
else
  got=$(shasum -a 256 "$WORK/archive" | cut -d' ' -f1)
fi

if [[ "$got" != "$WANT" ]]; then
  die "checksum mismatch for $URL
  expected: $WANT
  actual:   $got
Refusing to install. Either the pin is stale or the artifact changed underneath it."
fi

mkdir -p "$WORK/x" "$HOME/.cargo/bin"
tar -xzf "$WORK/archive" -C "$WORK/x" || die "could not extract $URL"

installed=0
while IFS= read -r bin; do
  # These archives mark their README and licences executable too, so the
  # mode alone is not enough to tell a binary from a text file.
  case $(file -b --mime-type "$bin") in text/*) continue ;; esac
  install -m 755 "$bin" "$HOME/.cargo/bin/$(basename "$bin")"
  installed=$((installed + 1))
done < <(find "$WORK/x" -type f -perm -u+x)

[[ $installed -gt 0 ]] || die "no executables found in $URL"
echo "installed $installed executable(s) from $(basename "$URL") (sha256 verified)"
