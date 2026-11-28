#!/bin/sh
set -eu

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
MVN_DIR="$WRAPPER_DIR/apache-maven-3.9.11"
MVN_BIN="$MVN_DIR/bin/mvn"
DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
DIST_ZIP="$WRAPPER_DIR/apache-maven-3.9.11-bin.zip"

if [ ! -x "$MVN_BIN" ]; then
  mkdir -p "$WRAPPER_DIR"
  if [ ! -f "$DIST_ZIP" ]; then
    partial=$(mktemp "$WRAPPER_DIR/maven-download.XXXXXXXX")
    trap 'rm -f "$partial"' EXIT
    curl -fsSL --connect-timeout 10 --max-time 90 --retry 2 "$DIST_URL" -o "$partial" ||
      curl -fsSL --connect-timeout 10 --max-time 90 --retry 2 \
        "https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip" -o "$partial"
    expected=03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076
    if command -v sha512sum >/dev/null 2>&1; then
      actual=$(sha512sum "$partial")
    else
      actual=$(shasum -a 512 "$partial")
    fi
    [ "${actual%% *}" = "$expected" ] || { echo "Maven distribution checksum mismatch" >&2; exit 1; }
    mv "$partial" "$DIST_ZIP"
  fi
  unzip -q "$DIST_ZIP" -d "$WRAPPER_DIR"
fi

exec "$MVN_BIN" "$@"
