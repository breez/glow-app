#!/usr/bin/env bash
# warmup-gradle-wrapper.sh — pre-fetch the Gradle wrapper distribution
# with retries, absorbing transient 5xx errors from services.gradle.org
# / github.com/gradle/gradle-distributions mirror.
#
# `./gradlew --version` triggers the wrapper-distribution download
# without running any build work, so the expensive bundleRelease /
# assembleDebug command that follows finds the zip already in
# $GRADLE_USER_HOME/wrapper/dists and skips the network.
#
# Argument: directory containing the gradle wrapper (default: .)
#
# Seen on Android release run 24609004514:
#   Exception in thread "main" java.io.IOException: Server returned
#   HTTP response code: 504 for URL: https://github.com/gradle/
#   gradle-distributions/releases/download/v8.14.3/gradle-8.14.3-all.zip
# — canonical transient, resolved by retry.

set -euo pipefail

DIR="${1:-.}"
cd "$DIR"
echo "==> Warming up Gradle wrapper distribution in $(pwd)"

for attempt in 1 2 3 4 5; do
  if ./gradlew --version --no-daemon; then
    echo "==> Gradle wrapper ready on attempt $attempt"
    exit 0
  fi
  SLEEP=$((attempt * 15))
  echo "==> Attempt $attempt failed; sleeping ${SLEEP}s before retry..." >&2
  sleep "$SLEEP"
done

echo "==> Gradle wrapper setup failed after 5 attempts — giving up" >&2
exit 1
