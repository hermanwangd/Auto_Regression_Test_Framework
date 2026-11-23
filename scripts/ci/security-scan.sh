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
DEPENDENCY_CHECK_CVSS_THRESHOLD="${DEPENDENCY_CHECK_CVSS_THRESHOLD:-4}"
DEPENDENCY_CHECK_AUTO_UPDATE="${DEPENDENCY_CHECK_AUTO_UPDATE:-true}"
DEPENDENCY_CHECK_SUPPRESSION_FILE="${DEPENDENCY_CHECK_SUPPRESSION_FILE:-config/dependency-check-suppressions.xml}"
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
suppressed_findings = []
unscored_findings = []
# Qualitative bands are used only when the source supplies no numeric CVSS.
severity_floors = {"LOW": 0.1, "MEDIUM": 4.0, "HIGH": 7.0, "CRITICAL": 9.0}
for dependency in report.get("dependencies", []):
    file_name = dependency.get("fileName") or dependency.get("filePath") or "unknown-dependency"
    for vulnerability in dependency.get("suppressedVulnerabilities", []) or []:
        suppressed_findings.append((file_name, vulnerability.get("name") or "unknown-vulnerability"))
    for vulnerability in dependency.get("vulnerabilities", []) or []:
        scores = []
        for key, field in (("cvssv4", "baseScore"), ("cvssv3", "baseScore"),
                           ("cvssv2", "score"), ("cvssv2", "baseScore")):
            value = vulnerability.get(key)
            if isinstance(value, dict) and isinstance(value.get(field), (int, float)):
                scores.append(float(value[field]))
        if isinstance(vulnerability.get("cvssScore"), (int, float)):
            scores.append(float(vulnerability["cvssScore"]))
        if not scores:
            severity = str(vulnerability.get("severity") or "UNKNOWN").upper()
            floor = severity_floors.get(severity, 0.0)
            unscored_findings.append((
                file_name, vulnerability.get("name") or "unknown-vulnerability",
                severity, floor >= threshold,
            ))
            continue
        score = max(scores)
        if score >= threshold:
            findings.append({
                "dependency": file_name,
                "name": vulnerability.get("name") or vulnerability.get("source") or "unknown-vulnerability",
                "severity": vulnerability.get("severity") or "UNKNOWN",
                "score": score,
            })

findings.sort(key=lambda item: (-item["score"], item["dependency"], item["name"]))
suppressed_findings.sort()

print(f"medium_high_vulnerability_count: {len(findings)}")
print(f"cvss_threshold: {threshold:g}")
print(f"suppressed_dependency_vulnerability_count: {len(suppressed_findings)}")
for dependency, name in suppressed_findings[:10]:
    print(f"- suppressed: {dependency} | {name}")
if len(suppressed_findings) > 10:
    print(f"- ... {len(suppressed_findings) - 10} more suppressed findings")
for item in findings[:10]:
    print(
        f"- {item['dependency']} | {item['name']} | "
        f"severity={item['severity']} | cvss={item['score']:g}"
    )
if len(findings) > 10:
    print(f"- ... {len(findings) - 10} more vulnerabilities above threshold")

unscored_blocking = sum(blocks for _, _, _, blocks in unscored_findings)
print(f"unscored_vulnerability_count: {len(unscored_findings)}")
print(f"unscored_blocking_vulnerability_count: {unscored_blocking}")
for dependency, name, severity, blocks in sorted(unscored_findings):
    disposition = "blocking" if blocks else "reported"
    print(f"- {disposition}: {dependency} | {name} | severity={severity} | cvss=unscored")

sys.exit(1 if findings or unscored_blocking else 0)
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

if [[ -s "$DEPENDENCY_CHECK_SUPPRESSION_FILE" ]]; then
  ARGS+=("-DsuppressionFiles=${DEPENDENCY_CHECK_SUPPRESSION_FILE}")
fi

if [[ -n "${NVD_API_KEY:-}" ]]; then
  ARGS+=("-DnvdApiKeyEnvironmentVariable=NVD_API_KEY")
fi

# An earlier scan may have applied suppressions that have since expired.
rm -f -- target/dependency-check-report.json
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
