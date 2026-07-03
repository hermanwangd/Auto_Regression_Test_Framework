#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

DEPENDENCY_CHECK_VERSION="${DEPENDENCY_CHECK_VERSION:-12.2.2}"
if [[ -n "${DEPENDENCY_CHECK_DATA_DIR:-}" ]]; then
  dependency_check_data_dir="$DEPENDENCY_CHECK_DATA_DIR"
elif [[ -n "${XDG_CACHE_HOME:-}" ]]; then
  dependency_check_data_dir="${XDG_CACHE_HOME}/spec-driven-auto-regression/dependency-check-data"
elif [[ -n "${HOME:-}" ]]; then
  dependency_check_data_dir="${HOME}/.cache/spec-driven-auto-regression/dependency-check-data"
else
  dependency_check_data_dir="${ROOT_DIR}/target/dependency-check-data"
fi

github_error() {
  local title="$1"
  local message="$2"
  message="${message//'%'/'%25'}"
  message="${message//$'\n'/'%0A'}"
  message="${message//$'\r'/'%0D'}"
  echo "::error title=${title}::${message}" >&2
}

if [[ -z "${NVD_API_KEY:-}" && "${ALLOW_SLOW_NVD_WITHOUT_API_KEY:-false}" != "true" ]]; then
  message="Set NVD_API_KEY for CI/release, or explicitly set ALLOW_SLOW_NVD_WITHOUT_API_KEY=true for a slow local bootstrap scan."
  echo "dependency_security_scan_status: blocked" >&2
  echo "Owner action: ${message}" >&2
  github_error "Dependency security scan blocked" "${message}"
  exit 2
fi

mkdir -p "$dependency_check_data_dir"
echo "dependency_check_data_dir: ${dependency_check_data_dir}"

ARGS=(
  -B
  -ntp
  -DskipTests
  "org.owasp:dependency-check-maven:${DEPENDENCY_CHECK_VERSION}:check"
  -DfailBuildOnCVSS=7
  -Dformat=ALL
  "-DdataDirectory=${dependency_check_data_dir}"
)

if [[ -n "${NVD_API_KEY:-}" ]]; then
  ARGS+=("-DnvdApiKeyEnvironmentVariable=NVD_API_KEY")
fi

if ! MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx1536m"}" ./mvnw "${ARGS[@]}"; then
  github_error "Dependency security scan failed" \
    "Review target/dependency-check-report.* for vulnerabilities or Dependency-Check update errors."
  exit 1
fi

echo "dependency_security_scan_status: passed"
