#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

ZIP_PATH="${1:-}"
VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${2:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"

if [[ -z "$ZIP_PATH" ]]; then
  echo "Usage: scripts/release/verify-usage-kit.sh <usage-kit-zip> [version]" >&2
  exit 2
fi

if [[ "$ZIP_PATH" != /* ]]; then
  ZIP_PATH="${ROOT_DIR}/${ZIP_PATH}"
fi

if [[ ! -s "$ZIP_PATH" ]]; then
  echo "Missing usage kit: $ZIP_PATH" >&2
  exit 1
fi

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for usage kit verification: $JAR" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${ROOT_DIR}/target/verify-usage-kit.XXXXXX")"
cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

(
  cd "$WORK_DIR"
  jar --extract --file "$ZIP_PATH"
)

required_paths=(
  "usage-kit/README.md"
  "usage-kit/QUICKSTART.md"
  "usage-kit/TROUBLESHOOTING.md"
  "usage-kit/DRIVER_SETUP.md"
  "usage-kit/EXTERNAL_RUNTIME_SETUP.md"
  "usage-kit/CHANGELOG.md"
  "usage-kit/usage-kit-manifest.yaml"
  "usage-kit/drivers/README.md"
  "usage-kit/drivers/oracle/put-ojdbc-here.txt"
  "usage-kit/drivers/db2/put-db2-jcc-here.txt"
  "usage-kit/samples/README.md"
  "usage-kit/release/verification_commands.md"
  "usage-kit/docs/09-operations/test_framework_user_guide.md"
  "usage-kit/docs/09-operations/provider_support_matrix.md"
  "usage-kit/docs/08-release/framework/framework_release_readiness.md"
  "usage-kit/docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/jdbc.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/nats.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/kafka.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/ibm_mq.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/sample_fake_provider_v0_3.yaml"
  "usage-kit/schemas/test_case_dsl.v0.2.schema.yaml"
  "usage-kit/schemas/suite_manifest.v0.2.schema.yaml"
  "usage-kit/schemas/test_case_dsl.v0.3.schema.yaml"
  "usage-kit/schemas/suite_manifest.v0.3.schema.yaml"
  "usage-kit/schemas/env_profile.v0.3.schema.yaml"
  "usage-kit/schemas/provider_contract.v0.3.schema.yaml"
  "usage-kit/docs/02-architecture/contracts/test_case_dsl.v0.3.schema.yaml"
  "usage-kit/docs/02-architecture/contracts/suite_manifest.v0.3.schema.yaml"
  "usage-kit/docs/02-architecture/contracts/env_profile.v0.3.schema.yaml"
  "usage-kit/docs/02-architecture/contracts/provider_contract.v0.3.schema.yaml"
  "usage-kit/docs/v0.3/05_dsl_v0_3_no_provider_instance_spec.md"
  "usage-kit/docs/v0.3/08_dsl_v0_3_no_provider_instance_architecture.md"
  "usage-kit/docs/v0.3/05_dsl_v0_3_acceptance_criteria.md"
  "usage-kit/docs/v0.3/11_dsl_v0_3_test_plan.md"
  "usage-kit/samples/v0_3_dsl/README.md"
  "usage-kit/samples/v0_3_dsl/golden/suite_manifest.yaml"
  "usage-kit/samples/v0_3_dsl/golden/test_cases/golden_success.yaml"
  "usage-kit/samples/v0_3_dsl/golden/env_profiles/local_v03.yaml"
  "usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml"
  "usage-kit/samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/env_profiles/external_jdbc_oracle_env_secret_ref.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/env_profiles/external_jdbc_db2_env_secret_ref.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/kafka/env_profiles/ci_kafka_external.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/ibm_mq/env_profiles/ci_ibm_mq_external.yaml"
  "usage-kit/samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml"
  "usage-kit/samples/40-evidence-reporting/evidence_hardening/valid_result.json"
  "usage-kit/samples/90-compatibility/dummy_rest/suite_manifest.yaml"
  "usage-kit/samples/LEGACY_COMPATIBILITY_README.md"
  "usage-kit/samples/golden_e2e/suite_manifest.yaml"
  "usage-kit/samples/golden_e2e/DEPRECATED_PATH.md"
  "usage-kit/samples/contract_baseline/suite_manifest.yaml"
  "usage-kit/samples/contract_baseline/DEPRECATED_PATH.md"
  "usage-kit/samples/provider_capability/suite_manifest.yaml"
  "usage-kit/samples/provider_capability/DEPRECATED_PATH.md"
  "usage-kit/samples/provider_capability/jdbc/suite_manifest_external_oracle.yaml"
  "usage-kit/samples/provider_capability/jdbc/suite_manifest_external_db2.yaml"
  "usage-kit/samples/provider_capability/jdbc/env_profiles/external_jdbc_oracle_env_secret_ref.yaml"
  "usage-kit/samples/provider_capability/jdbc/env_profiles/external_jdbc_db2_env_secret_ref.yaml"
  "usage-kit/samples/provider_capability/kafka/env_profiles/ci_kafka_external.yaml"
  "usage-kit/samples/provider_capability/ibm_mq/env_profiles/ci_ibm_mq_external.yaml"
  "usage-kit/samples/provider_capability/mock_server_cross_verify/suite_manifest.yaml"
  "usage-kit/samples/provider_capability/dummy_rest/suite_manifest.yaml"
  "usage-kit/samples/evidence_hardening/valid_result.json"
  "usage-kit/samples/evidence_hardening/DEPRECATED_PATH.md"
)

for path in "${required_paths[@]}"; do
  if [[ ! -e "${WORK_DIR}/${path}" ]]; then
    echo "Usage kit missing required path: ${path}" >&2
    exit 1
  fi
done

for forbidden in \
  '.DS_Store' \
  'target' \
  '.dependency-check-data' \
  'docs/idea' \
  'docs/template' \
  'docs/99-archive'
do
  if (cd "${WORK_DIR}/usage-kit" && find . -path "*${forbidden}*" -print -quit) | grep -q .; then
    echo "Usage kit contains forbidden path matching: ${forbidden}" >&2
    exit 1
  fi
done

if ! grep -q "^usage_kit_version: ${VERSION}$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest version does not match ${VERSION}." >&2
  exit 1
fi

if ! grep -q "^version: ${VERSION}$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest public version does not match ${VERSION}." >&2
  exit 1
fi

if ! grep -q "^sample_layout_version: v2$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing sample_layout_version: v2." >&2
  exit 1
fi

if ! grep -q "^contract_versions: \\[v0.2, v0.3\\]$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing contract_versions: [v0.2, v0.3]." >&2
  exit 1
fi

if grep -R "{{VERSION}}" \
  "${WORK_DIR}/usage-kit/QUICKSTART.md" \
  "${WORK_DIR}/usage-kit/TROUBLESHOOTING.md" \
  "${WORK_DIR}/usage-kit/DRIVER_SETUP.md" \
  "${WORK_DIR}/usage-kit/EXTERNAL_RUNTIME_SETUP.md" \
  "${WORK_DIR}/usage-kit/docs/09-operations/quickstart.md" \
  "${WORK_DIR}/usage-kit/docs/09-operations/troubleshooting.md" \
  "${WORK_DIR}/usage-kit/docs/09-operations/driver_setup.md" \
  "${WORK_DIR}/usage-kit/docs/09-operations/external_runtime_setup.md" >/dev/null; then
  echo "Usage kit contains unrendered version placeholder." >&2
  exit 1
fi

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

(
  cd "$WORK_DIR/usage-kit"
  run_cli validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
  run_cli run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --dry-run
  run_cli validate --suite samples/20-provider-capability-p0/suite_manifest.yaml
  run_cli validate --suite samples/v0_3_dsl/golden/suite_manifest.yaml --profile local_v03
  run_cli run --suite samples/v0_3_dsl/golden/suite_manifest.yaml --profile local_v03 --dry-run
  run_cli run --suite samples/v0_3_dsl/golden/suite_manifest.yaml --profile local_v03
  run_cli report --result target/provider-capability/golden-e2e/GOLDEN-E2E-v0.3/BATCH-GOLDEN-E2E-001/RUN-GOLDEN-E2E-001/result.json
  run_cli validate-evidence --result samples/40-evidence-reporting/evidence_hardening/valid_result.json
  run_cli validate --suite samples/golden_e2e/suite_manifest.yaml
  run_cli validate --suite samples/provider_capability/suite_manifest.yaml
  run_cli validate-evidence --result samples/evidence_hardening/valid_result.json
)

echo "usage_kit_verification_status: passed"
echo "usage_kit_file: ${ZIP_PATH}"
