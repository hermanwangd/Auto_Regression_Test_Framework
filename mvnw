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
    curl -fsSL "$DIST_URL" -o "$DIST_ZIP"
  fi
  unzip -q "$DIST_ZIP" -d "$WRAPPER_DIR"
fi

exec "$MVN_BIN" "$@"
