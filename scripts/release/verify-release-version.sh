#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

REQUESTED="${1:-}"
REQUESTED="${REQUESTED#v}"

POM_VERSION="$(awk '
  /<artifactId>spec-driven-auto-regression<\/artifactId>/ { in_project=1; next }
  in_project && /<version>/ {
    gsub(/.*<version>|<\/version>.*/, "");
    print;
    exit
  }
' pom.xml)"

if [[ -z "$POM_VERSION" ]]; then
  echo "Could not read project version from pom.xml." >&2
  exit 1
fi

if [[ "$POM_VERSION" == *SNAPSHOT* ]]; then
  echo "Release version must be immutable; found SNAPSHOT version: ${POM_VERSION}" >&2
  exit 1
fi

if [[ -n "$REQUESTED" && "$REQUESTED" != "$POM_VERSION" ]]; then
  echo "Requested release version ${REQUESTED} does not match pom.xml version ${POM_VERSION}." >&2
  exit 1
fi

echo "$POM_VERSION"
