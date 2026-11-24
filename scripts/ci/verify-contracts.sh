#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

scripts/ci/check-schema-drift.sh

VERSION="${FRAMEWORK_VERSION:-$(awk '
  /<artifactId>spec-driven-auto-regression<\/artifactId>/ { in_project=1; next }
  in_project && /<version>/ {
    gsub(/.*<version>|<\/version>.*/, "");
    print;
    exit
  }
' pom.xml)}"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"

if [[ ! -f "$JAR" ]]; then
  MAVEN_OPTS="${MAVEN_OPTS:-"-Xmx1024m"}" ./mvnw -B -ntp -DskipTests package
fi

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

run_cli validate --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml
run_cli validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
run_cli validate --suite samples/20-provider-capability-p0/suite_manifest.yaml
run_cli validate-evidence --result samples/40-evidence-reporting/evidence_hardening/valid_result.json
run_cli report --result samples/40-evidence-reporting/evidence_hardening/valid_result.json

# Exercise the bundled contract from outside the repository with no SQL approvals.
(
  scratch=$(mktemp -d)
  trap 'rm -rf -- "$scratch"' EXIT
  cp -R "$ROOT_DIR/samples/20-provider-capability-p0/data/jdbc" "$scratch/jdbc"
  cd "$scratch"
  unset REGRESS_JDBC_APPROVED_SQL_SHA256
  for profile in external_oracle external_db2; do
    # No address or credentials are needed to verify denial before driver loading.
    case "$profile" in
      external_oracle) export JDBC_CONNECTION='jdbc:oracle:' ;;
      external_db2) export JDBC_CONNECTION='jdbc:db2:' ;;
    esac
    status=0
    run_cli run --suite jdbc/suite_manifest.yaml --profile "$profile" > "$profile.log" || status=$?
    if [[ $status != 1 ]] || ! grep -Fxq 'failure_code: JDBC_SQL_NOT_APPROVED' "$profile.log"; then
      cat "$profile.log" >&2
      exit 1
    fi
    result=$(sed -n 's/^result_json: //p' "$profile.log")
    run_cli validate-evidence --result "$result"
    echo "External SQL denial verified for $profile"
  done
)

if grep -RInE "provider_family" docs samples schemas src/main/java; then
  echo "provider_family is not allowed in public framework artifacts." >&2
  exit 1
fi

if grep -RInE "adapter contract|adapter instance" docs/00-intake-scope docs/01-specs docs/02-architecture docs/03-acceptance docs/04-planning docs/07-validation-evidence docs/09-operations; then
  echo "User-facing Adapter terminology is not allowed in active docs." >&2
  exit 1
fi

echo "contract_consistency_status: passed"
