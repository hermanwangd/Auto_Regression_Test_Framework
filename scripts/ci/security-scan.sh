#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

mode="scan"
if [[ "${1:-}" == "--warmup" ]]; then
  mode="warmup"
  shift
fi
if [[ "$#" -ne 0 ]]; then
  echo "Usage: scripts/ci/security-scan.sh [--warmup]" >&2
  exit 2
fi

DEPENDENCY_CHECK_VERSION="${DEPENDENCY_CHECK_VERSION:-12.2.2}"
DEPENDENCY_CHECK_CVSS_THRESHOLD="${DEPENDENCY_CHECK_CVSS_THRESHOLD:-7}"
DEPENDENCY_CHECK_AUTO_UPDATE="${DEPENDENCY_CHECK_AUTO_UPDATE:-true}"
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

dependency_check_summary() {
  local report="${1:-target/dependency-check-report.json}"
  local threshold="${2:-7}"
  if [[ ! -s "$report" ]]; then
    echo "Dependency-Check JSON report was not generated at ${report}."
    return 2
  fi

  python3 - "$report" "$threshold" <<'PY'
import json
import sys

report_path = sys.argv[1]
threshold = float(sys.argv[2])

with open(report_path, "r", encoding="utf-8") as handle:
    report = json.load(handle)

findings = []
for dependency in report.get("dependencies", []):
    file_name = dependency.get("fileName") or dependency.get("filePath") or "unknown-dependency"
    for vulnerability in dependency.get("vulnerabilities", []) or []:
        scores = []
        for key in ("cvssv4", "cvssv3", "cvssv2"):
            value = vulnerability.get(key)
            if isinstance(value, dict) and isinstance(value.get("baseScore"), (int, float)):
                scores.append(float(value["baseScore"]))
        if isinstance(vulnerability.get("cvssScore"), (int, float)):
            scores.append(float(vulnerability["cvssScore"]))
        score = max(scores) if scores else 0.0
        if score >= threshold:
            findings.append({
                "dependency": file_name,
                "name": vulnerability.get("name") or vulnerability.get("source") or "unknown-vulnerability",
                "severity": vulnerability.get("severity") or "UNKNOWN",
                "score": score,
            })

findings.sort(key=lambda item: (-item["score"], item["dependency"], item["name"]))

print(f"high_vulnerability_count: {len(findings)}")
print(f"cvss_threshold: {threshold:g}")
for item in findings[:10]:
    print(
        f"- {item['dependency']} | {item['name']} | "
        f"severity={item['severity']} | cvss={item['score']:g}"
    )
if len(findings) > 10:
    print(f"- ... {len(findings) - 10} more vulnerabilities above threshold")

sys.exit(1 if findings else 0)
PY
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

if [[ "$mode" == "warmup" ]]; then
  ARGS=(
    -B
    -ntp
    -DskipTests
    "org.owasp:dependency-check-maven:${DEPENDENCY_CHECK_VERSION}:update-only"
    "-DdataDirectory=${dependency_check_data_dir}"
    -DautoUpdate=true
  )

  if [[ -n "${NVD_API_KEY:-}" ]]; then
    ARGS+=("-DnvdApiKeyEnvironmentVariable=NVD_API_KEY")
  fi

  echo "dependency_security_cache_warmup_status: started"
  MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx1536m"}" ./mvnw "${ARGS[@]}"
  echo "dependency_security_cache_warmup_status: passed"
  exit 0
fi

ARGS=(
  -B
  -ntp
  -DskipTests
  "org.owasp:dependency-check-maven:${DEPENDENCY_CHECK_VERSION}:check"
  -DfailBuildOnCVSS=11
  -Dformat=ALL
  "-DdataDirectory=${dependency_check_data_dir}"
  "-DautoUpdate=${DEPENDENCY_CHECK_AUTO_UPDATE}"
)

if [[ -n "${NVD_API_KEY:-}" ]]; then
  ARGS+=("-DnvdApiKeyEnvironmentVariable=NVD_API_KEY")
fi

maven_exit_code=0
MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx1536m"}" ./mvnw "${ARGS[@]}" || maven_exit_code=$?

summary_file="$(mktemp)"
summary_exit_code=0
dependency_check_summary target/dependency-check-report.json "$DEPENDENCY_CHECK_CVSS_THRESHOLD" > "$summary_file" || summary_exit_code=$?
cat "$summary_file"

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "## Dependency security scan"
    echo
    echo '```text'
    cat "$summary_file"
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [[ "$maven_exit_code" -ne 0 ]]; then
  github_error "Dependency security scan failed" "$(cat "$summary_file")"
  exit "$maven_exit_code"
fi

if [[ "$summary_exit_code" -eq 1 ]]; then
  github_error "Dependency vulnerabilities exceed CVSS threshold" "$(cat "$summary_file")"
  exit 1
fi

if [[ "$summary_exit_code" -ne 0 ]]; then
  github_error "Dependency security scan report missing" "$(cat "$summary_file")"
  exit "$summary_exit_code"
fi

echo "dependency_security_scan_status: passed"
