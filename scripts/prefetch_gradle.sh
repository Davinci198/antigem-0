#!/usr/bin/env bash
# Seed the Gradle wrapper cache with the distribution it wants, fetched with retries.
#
# Why: the wrapper resolves `services.gradle.org`, which redirects to the GitHub
# Releases CDN. That CDN answers HTTP 504 for minutes at a time, and the wrapper
# has no retry of its own — one bad minute fails an otherwise healthy build.
# This script downloads the same official file (same URL, no third-party mirror)
# with backoff, unpacks it into the wrapper cache directory the wrapper would
# have used, and lets `./gradlew` continue from there.
#
# Usage: scripts/prefetch_gradle.sh
set -euo pipefail

cd "$(dirname "$0")/.."

PROPS="gradle/wrapper/gradle-wrapper.properties"
DIST=$(sed -n 's/^distributionUrl=.*\/gradle-\(.*\)-bin\.zip$/\1/p' "$PROPS" | sed 's/\\//g')
if [ -z "$DIST" ]; then
  echo "::error::cannot parse distributionUrl from $PROPS"
  exit 1
fi
echo "Gradle distribution: $DIST"

URL="https://services.gradle.org/distributions/gradle-$DIST-bin.zip"
ZIP="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-$DIST-bin.zip"
DEST_ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-dist"

for attempt in 1 2 3 4 5 6 7 8; do
  echo "attempt $attempt: $URL"
  if curl -fL --retry 3 --retry-delay 10 --retry-all-errors \
          --connect-timeout 30 --max-time 600 \
          -o "$ZIP" "$URL"; then
    break
  fi
  if [ "$attempt" = 8 ]; then
    echo "::error::Gradle distribution download failed after 8 attempts"
    exit 1
  fi
  sleep 30
done

rm -rf "$DEST_ROOT"
unzip -q -o "$ZIP" -d "$DEST_ROOT"
GRADLE_HOME=$(find "$DEST_ROOT" -maxdepth 1 -name 'gradle-*' -type d | head -1)
if [ -z "$GRADLE_HOME" ]; then
  echo "::error::distribution zip did not contain a gradle-* directory"
  exit 1
fi
echo "unpacked: $GRADLE_HOME"

# The wrapper names its cache dir with the base36 of MD5(distributionUrl).
HASH=$(PROPS="$PROPS" python3 - <<'PY'
import hashlib, os, pathlib
props = pathlib.Path(os.environ["PROPS"])
url = next(l.split("=", 1)[1].strip() for l in props.read_text().splitlines()
           if l.startswith("distributionUrl=")).replace("\\", "")
value = int.from_bytes(hashlib.md5(url.encode()).digest(), "big")
digits, base = "", "0123456789abcdefghijklmnopqrstuvwxyz"
while value:
    value, rem = divmod(value, 36)
    digits = base[rem] + digits
print(digits)
PY
)

CACHE_DIR="$HOME/.gradle/wrapper/dists/gradle-$DIST-bin/$HASH"
mkdir -p "$CACHE_DIR"
cp "$ZIP" "$CACHE_DIR/"
cp -R "$GRADLE_HOME" "$CACHE_DIR/"
touch "$CACHE_DIR/gradle-$DIST-bin.zip.ok"
echo "seeded $CACHE_DIR"
ls "$CACHE_DIR"
