#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

DEPENDENCY_CHECK_VERSION="${DEPENDENCY_CHECK_VERSION:-12.2.2}"

if [[ -z "${NVD_API_KEY:-}" && "${ALLOW_SLOW_NVD_WITHOUT_API_KEY:-false}" != "true" ]]; then
  echo "dependency_security_scan_status: blocked" >&2
  echo "Owner action: set NVD_API_KEY for CI/release, or explicitly set ALLOW_SLOW_NVD_WITHOUT_API_KEY=true for a slow local bootstrap scan." >&2
  exit 2
fi

ARGS=(
  -B
  -ntp
  -DskipTests
  "org.owasp:dependency-check-maven:${DEPENDENCY_CHECK_VERSION}:check"
  -DfailBuildOnCVSS=7
  -Dformat=ALL
  -DdataDirectory=target/dependency-check-data
)

if [[ -n "${NVD_API_KEY:-}" ]]; then
  ARGS+=("-DnvdApiKey=${NVD_API_KEY}")
fi

MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx1536m"}" ./mvnw "${ARGS[@]}"

echo "dependency_security_scan_status: passed"
