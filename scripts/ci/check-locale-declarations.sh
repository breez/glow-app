#!/usr/bin/env bash
# Assert the native locale declarations match the languages the web layer will
# actually serve.
#
# The two are read by different things and drift silently. glow-web refuses to
# serve a translation nobody has reviewed and falls back to English. iOS and
# Android read their own declarations to build the per-app language picker, and
# the App Store reads the iOS one to list the languages on the product page.
# Declare a language on either side without adding it to the shipping list and
# the result is a language someone can pick, and select, and then read in
# English. That looks like a broken translation rather than an absent one, and
# nothing else in the build catches it.
#
# Two sides to keep in step, so this fails on either direction of drift.
#
# Usage:
#   scripts/ci/check-locale-declarations.sh
#
# LOCALE_CHECK_ROOT overrides the tree to inspect; the test uses it.
#
# Exit 0 when they agree, 1 when they do not.

set -euo pipefail

# Overridable so the test can point at a fixture tree instead of the repo.
repo_root="${LOCALE_CHECK_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
shipping_json="$repo_root/glow-web/src/locales/shipping.json"
locales_xml="$repo_root/android/app/src/main/res/xml/locales_config.xml"
info_plist="$repo_root/ios/App/App/Info.plist"

if [[ ! -f "$shipping_json" ]]; then
  echo "error: $shipping_json is missing." >&2
  echo "       The glow-web submodule is probably not checked out." >&2
  exit 1
fi

# Sorted, newline-separated, so the three lists compare as plain text.
sorted() { tr -d '[:space:]' | tr ',' '\n' | tr -d '"[]' | grep -v '^$' | LC_ALL=C sort -u; }

shipping="$(sorted < "$shipping_json")"
android="$(grep -o 'android:name="[^"]*"' "$locales_xml" | sed 's/.*="\(.*\)"/\1/' | LC_ALL=C sort -u)"

# CFBundleLocalizations is an array of <string> under that key. Read it with
# PlistBuddy where available (macOS) and fall back to a scoped text scrape so
# the check still runs on the Linux jobs.
if command -v /usr/libexec/PlistBuddy >/dev/null 2>&1; then
  ios="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleLocalizations' "$info_plist" 2>/dev/null \
    | sed -n 's/^ *\([a-zA-Z-]*\)$/\1/p' | LC_ALL=C sort -u)"
else
  ios="$(awk '/<key>CFBundleLocalizations<\/key>/{f=1} f&&/<\/array>/{exit} f' "$info_plist" \
    | grep -o '<string>[^<]*</string>' | sed 's/<[^>]*>//g' | LC_ALL=C sort -u)"
fi

fail=0
report() {
  echo "error: $1 declares [$(echo "$2" | tr '\n' ' ')]" >&2
  echo "       shipping.json says   [$(echo "$shipping" | tr '\n' ' ')]" >&2
  echo "       $3" >&2
  fail=1
}

[[ "$android" == "$shipping" ]] || report "$locales_xml" "$android" \
  "Add or remove <locale android:name=\"...\"/> entries so they match."
[[ "$ios" == "$shipping" ]] || report "$info_plist" "$ios" \
  "Add or remove <string> entries under CFBundleLocalizations so they match."

# An .lproj folder declares its language to iOS on its own, with or without a
# plist entry, so a stray one advertises a language the plist never mentions.
while IFS= read -r lproj; do
  lang="$(basename "$lproj" .lproj)"
  [[ "$lang" == "Base" ]] && continue
  if ! grep -qx "$lang" <<< "$shipping"; then
    echo "error: $lproj declares '$lang' to iOS by existing, but it is not shipping." >&2
    echo "       Remove the folder, or add '$lang' to shipping.json." >&2
    fail=1
  fi
done < <(find "$repo_root/ios/App/App" -maxdepth 1 -name '*.lproj' -type d)

if [[ $fail -eq 0 ]]; then
  echo "locale declarations agree: [$(echo "$shipping" | tr '\n' ' ')]"
fi
exit $fail
