#!/usr/bin/env bash
# Assert a release AAB carries the expected signing certificate.
#
# The release buildType falls back to debug signing off CI, and an
# incomplete signing environment can otherwise yield a bundle that looks
# like a release but is not the one we mean to ship. Reading the
# certificate straight out of the finished bundle is the only check that
# reflects what was actually produced rather than what was configured.
#
# Runs between `bundleRelease` and both `publishReleaseBundle` and the
# artifact upload, so a bundle that fails it reaches neither.
#
# Usage:
#   scripts/ci/verify-aab-signature.sh <path-to-aab>
#
# Expected certificate, first match wins:
#   1. EXPECTED_UPLOAD_CERT_SHA256: a fixed value, e.g. "A1:B2:..." or
#      "a1b2...". Set it as a repo variable to compare against something
#      independent of the keystore CI was handed. Certificates are public,
#      so this is a variable rather than a secret.
#   2. The certificate inside RELEASE_KEYSTORE_PATH under RELEASE_KEY_ALIAS.
#      Needs no setup and still catches an unsigned bundle, a debug-signed
#      one, or one signed by anything other than the keystore provided.
#
# Neither available means the job has no signing identity to check against,
# which is itself worth stopping for. Exit 1.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }

AAB=${1:-}
[[ -n "$AAB" ]] || die "usage: verify-aab-signature.sh <path-to-aab>"
[[ -f "$AAB" ]] || die "AAB not found: $AAB"

command -v keytool >/dev/null || die "keytool not on PATH (needs a JDK)"

# Normalise to lowercase hex, no colons, so a pinned value can be pasted in
# either of the two formats keytool and Play Console hand out.
normalise() { tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'; }

# First SHA256 line only. `-printcert -jarfile` prints a block per signer;
# `-list -v -alias` prints exactly one cert. Both label it "SHA256:".
first_sha256() { grep -m1 -o 'SHA256:[[:space:]]*[0-9A-Fa-f:]\{32,\}' | cut -d: -f2- | normalise; }

# keytool prints "Not a signed jar file" and still exits 0 on an unsigned
# archive, so the exit status alone does not distinguish "unsigned" from
# "read it fine". Check the text, and treat a missing fingerprint as unsigned
# too. Both mean the same thing here, and the operator needs to be told to
# look at the signing secrets rather than at this script.
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
