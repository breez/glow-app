#!/usr/bin/env bash
# Self-check for verify-aab-signature.sh. Needs a JDK (keytool + jarsigner).
#
#   scripts/ci/verify-aab-signature.test.sh
#
# An AAB is a jar as far as signature verification goes, so stub jars
# signed with throwaway keystores exercise the real code path.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
VERIFY="$HERE/verify-aab-signature.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

pass=0 fail=0
ok()   { pass=$((pass + 1)); echo "  ok   : $1"; }
bad()  { fail=$((fail + 1)); echo "  FAIL : $1"; }

expect_pass() { if "$VERIFY" "$@" >/dev/null 2>&1; then ok "$DESC"; else bad "$DESC (expected exit 0)"; fi; }
expect_fail() { if "$VERIFY" "$@" >/dev/null 2>&1; then bad "$DESC (expected non-zero exit)"; else ok "$DESC"; fi; }

make_keystore() { # <path> <alias>
  keytool -genkeypair -keystore "$1" -alias "$2" -storepass changeit -keypass changeit \
    -keyalg RSA -keysize 2048 -validity 1 -dname "CN=$2" >/dev/null 2>&1
}

echo "building fixtures…"
make_keystore "$WORK/upload.jks" upload
make_keystore "$WORK/other.jks" other

echo stub > "$WORK/stub.txt"
(cd "$WORK" && jar cf unsigned.aab stub.txt)
cp "$WORK/unsigned.aab" "$WORK/good.aab"
cp "$WORK/unsigned.aab" "$WORK/wrong.aab"
jarsigner -keystore "$WORK/upload.jks" -storepass changeit "$WORK/good.aab" upload >/dev/null 2>&1
jarsigner -keystore "$WORK/other.jks" -storepass changeit "$WORK/wrong.aab" other >/dev/null 2>&1

upload_sha=$(printf 'changeit\n' | keytool -list -v -keystore "$WORK/upload.jks" -alias upload 2>/dev/null \
  | grep -m1 -o 'SHA256:[[:space:]]*[0-9A-Fa-f:]\{32,\}' | cut -d: -f2- | tr -d ':[:space:]')

echo "running cases…"

# No fixed value configured: expected cert read from the keystore.
export RELEASE_KEYSTORE_PATH="$WORK/upload.jks" RELEASE_KEY_ALIAS=upload RELEASE_KEYSTORE_PASSWORD=changeit
unset EXPECTED_UPLOAD_CERT_SHA256 || true

DESC="signed by the upload key passes"                ; expect_pass "$WORK/good.aab"
DESC="signed by a different key fails"                ; expect_fail "$WORK/wrong.aab"
DESC="unsigned bundle fails"                          ; expect_fail "$WORK/unsigned.aab"
DESC="missing file fails"                             ; expect_fail "$WORK/nope.aab"

# Unset here, not in a subshell: a subshell's counter updates would not survive.
saved_path=$RELEASE_KEYSTORE_PATH
unset RELEASE_KEYSTORE_PATH RELEASE_KEY_ALIAS RELEASE_KEYSTORE_PASSWORD
DESC="no signing identity at all fails"                ; expect_fail "$WORK/good.aab"
export RELEASE_KEYSTORE_PATH="$saved_path" RELEASE_KEY_ALIAS=upload RELEASE_KEYSTORE_PASSWORD=changeit

# A fixed value takes precedence over the keystore.
export EXPECTED_UPLOAD_CERT_SHA256="$upload_sha"
DESC="matching explicit pin passes"                   ; expect_pass "$WORK/good.aab"
DESC="pin accepts colon-separated uppercase"          ; EXPECTED_UPLOAD_CERT_SHA256=$(echo "$upload_sha" | tr '[:lower:]' '[:upper:]' | sed 's/../&:/g;s/:$//') expect_pass "$WORK/good.aab"
DESC="pin mismatch fails even with a valid keystore"  ; EXPECTED_UPLOAD_CERT_SHA256=$(printf 'a%.0s' {1..64}) expect_fail "$WORK/good.aab"

echo
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
