#!/usr/bin/env bash
# Assert a release AAB carries the expected signing certificate.
#
#   scripts/ci/verify-aab-signature.sh <path-to-aab>
#
# Expected certificate, first match wins:
#   1. EXPECTED_UPLOAD_CERT_SHA256, colon-separated or bare hex.
#   2. RELEASE_KEYSTORE_PATH's cert under RELEASE_KEY_ALIAS.
# Neither set is a failure: there is nothing to check against.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }

AAB=${1:-}
[[ -n "$AAB" ]] || die "usage: verify-aab-signature.sh <path-to-aab>"
[[ -f "$AAB" ]] || die "AAB not found: $AAB"

command -v keytool >/dev/null || die "keytool not on PATH (needs a JDK)"

# keytool and Play Console print different formats; compare on lowercase hex.
normalise() { tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'; }

# First SHA256 line only: -printcert prints a block per signer.
first_sha256() { grep -m1 -o 'SHA256:[[:space:]]*[0-9A-Fa-f:]\{32,\}' | cut -d: -f2- | normalise; }

# keytool exits 0 on an unsigned archive, so the status alone says nothing.
# A missing fingerprint means unsigned too.
aab_out=$(keytool -printcert -jarfile "$AAB" 2>&1 || true)
actual=$(printf '%s' "$aab_out" | first_sha256 || true)

if [[ -z "$actual" ]]; then
  die "AAB is not signed (keytool: ${aab_out//$'\n'/ }). Release signing did not run. Check that RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD are all set."
fi

if [[ -n "${EXPECTED_UPLOAD_CERT_SHA256:-}" ]]; then
  expected=$(printf '%s' "$EXPECTED_UPLOAD_CERT_SHA256" | normalise)
  source_desc="pinned EXPECTED_UPLOAD_CERT_SHA256"
elif [[ -n "${RELEASE_KEYSTORE_PATH:-}" && -n "${RELEASE_KEY_ALIAS:-}" && -n "${RELEASE_KEYSTORE_PASSWORD:-}" ]]; then
  # Password over stdin, not -storepass: keeps it out of the process list.
  if ! ks_out=$(printf '%s\n' "$RELEASE_KEYSTORE_PASSWORD" \
      | keytool -list -v -keystore "$RELEASE_KEYSTORE_PATH" -alias "$RELEASE_KEY_ALIAS" 2>&1); then
    die "could not read alias '$RELEASE_KEY_ALIAS' from the release keystore (keytool: ${ks_out//$'\n'/ })"
  fi
  expected=$(printf '%s' "$ks_out" | first_sha256 || true)
  [[ -n "$expected" ]] || die "could not read a SHA-256 fingerprint for alias '$RELEASE_KEY_ALIAS'"
  source_desc="release keystore alias '$RELEASE_KEY_ALIAS'"
else
  die "no expected signing certificate available. Set EXPECTED_UPLOAD_CERT_SHA256, or provide RELEASE_KEYSTORE_PATH + RELEASE_KEY_ALIAS + RELEASE_KEYSTORE_PASSWORD. Refusing to publish an unverified AAB."
fi

if [[ "$actual" != "$expected" ]]; then
  die "AAB signing certificate does not match the $source_desc.
  expected: $expected
  actual:   $actual
The bundle was not signed with the expected key. Do not publish this artifact."
fi

echo "AAB signature verified against $source_desc"
echo "  SHA-256: $actual"
