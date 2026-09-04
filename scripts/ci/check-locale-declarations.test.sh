#!/usr/bin/env bash
# Exercises check-locale-declarations.sh against fixture trees.
#
# The check only earns its place if it fails on each direction of drift, so
# every case here is a drift the real build could produce.
#
# Usage:
#   scripts/ci/check-locale-declarations.test.sh

set -uo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-locale-declarations.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
pass=0 fail=0

# shipping list, android locales, ios locales
build_fixture() {
  local root="$1" ship="$2" android="$3" ios="$4"
  rm -rf "$root"
  mkdir -p "$root/glow-web/src/locales" "$root/android/app/src/main/res/xml" "$root/ios/App/App/Base.lproj"
  printf '[%s]\n' "$ship" > "$root/glow-web/src/locales/shipping.json"
  {
    echo '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">'
    for l in $android; do echo "  <locale android:name=\"$l\" />"; done
    echo '</locale-config>'
  } > "$root/android/app/src/main/res/xml/locales_config.xml"
  {
    echo '<plist version="1.0"><dict>'
    echo '  <key>CFBundleLocalizations</key>'
    echo '  <array>'
    for l in $ios; do echo "    <string>$l</string>"; done
    echo '  </array>'
    echo '</dict></plist>'
  } > "$root/ios/App/App/Info.plist"
}

check() {
  local name="$1" expected="$2" root="$3"
  LOCALE_CHECK_ROOT="$root" "$script" >/dev/null 2>&1
  local got=$?
  if [[ "$got" -eq "$expected" ]]; then
    echo "  ok   $name"; pass=$((pass + 1))
  else
    echo "  FAIL $name (expected exit $expected, got $got)"; fail=$((fail + 1))
  fi
}

build_fixture "$tmp/agree" '"en"' 'en' 'en'
check "passes when all three agree" 0 "$tmp/agree"

build_fixture "$tmp/agree3" '"en","de","hu"' 'en de hu' 'en de hu'
check "passes on a larger matching set" 0 "$tmp/agree3"

build_fixture "$tmp/android_extra" '"en"' 'en hu' 'en'
check "fails when Android offers a language that is not shipping" 1 "$tmp/android_extra"

build_fixture "$tmp/ios_extra" '"en"' 'en' 'en de'
check "fails when iOS advertises a language that is not shipping" 1 "$tmp/ios_extra"

build_fixture "$tmp/native_behind" '"en","it"' 'en' 'en'
check "fails when a language ships but neither platform declares it" 1 "$tmp/native_behind"

build_fixture "$tmp/stray_lproj" '"en"' 'en' 'en'
mkdir -p "$tmp/stray_lproj/ios/App/App/hu.lproj"
check "fails on an .lproj folder for a language that is not shipping" 1 "$tmp/stray_lproj"

build_fixture "$tmp/no_submodule" '"en"' 'en' 'en'
rm "$tmp/no_submodule/glow-web/src/locales/shipping.json"
check "fails when the submodule is not checked out" 1 "$tmp/no_submodule"

echo
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
