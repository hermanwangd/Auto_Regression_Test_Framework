#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="target/spec-driven-auto-regression-${VERSION}.jar"

for artifact in "$JAR" target/bom.json target/bom.xml; do
  if [[ ! -s "$artifact" ]]; then
    echo "Missing release artifact: $artifact" >&2
    exit 1
  fi
done

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$JAR" target/bom.json target/bom.xml > target/checksums.sha256
else
  shasum -a 256 "$JAR" target/bom.json target/bom.xml > target/checksums.sha256
fi

echo "checksum_status: generated"
echo "checksum_file: target/checksums.sha256"
