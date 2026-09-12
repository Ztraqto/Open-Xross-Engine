#!/bin/sh
set -eu
cd "$(dirname "$0")"
VERSION="8.14.4"
WRAPPER="gradle/wrapper/gradle-wrapper.jar"
EXPECTED="7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"
URL="https://raw.githubusercontent.com/gradle/gradle/v${VERSION}/gradle/wrapper/gradle-wrapper.jar"
mkdir -p gradle/wrapper
if [ ! -f "$WRAPPER" ] || [ "$(sha256sum "$WRAPPER" | awk '{print $1}')" != "$EXPECTED" ]; then
  command -v curl >/dev/null 2>&1 || { echo "curl is required to bootstrap Gradle Wrapper." >&2; exit 1; }
  curl -fL --retry 3 -o "$WRAPPER" "$URL"
fi
ACTUAL="$(sha256sum "$WRAPPER" | awk '{print $1}')"
[ "$ACTUAL" = "$EXPECTED" ] || { echo "Gradle wrapper checksum mismatch." >&2; rm -f "$WRAPPER"; exit 1; }
echo "Gradle wrapper JAR verified."
