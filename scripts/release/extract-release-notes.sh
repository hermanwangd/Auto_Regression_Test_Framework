#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
OUT="${2:-target/release-notes.md}"
mkdir -p "$(dirname "$OUT")"

awk -v version="$VERSION" '
  $0 ~ "^## " version "$" { capture=1; print; next }
  capture && /^## / { exit }
  capture { print }
' CHANGELOG.md > "$OUT"

if [[ ! -s "$OUT" ]]; then
  {
    echo "## ${VERSION}"
    echo
    echo "Release notes were not found in CHANGELOG.md."
  } > "$OUT"
fi

echo "release_notes: $OUT"
