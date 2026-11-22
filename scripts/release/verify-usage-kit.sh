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
  "usage-kit/docs/02-architecture/contracts/provider-contracts/README.md"
  "usage-kit/docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/jdbc.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/nats.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/kafka.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/ibm_mq.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/sample_fake_provider_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/artifact_compare_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/common_verify_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/grpc_client_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/grpc_mock_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/http_mock_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/ibm_mq_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/jdbc_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/kafka_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/nats_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/polling_observer_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/rest_client_v0_3.yaml"
  "usage-kit/docs/02-architecture/contracts/provider-contracts/soap_mock_v0_3.yaml"
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
  "usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml"
  "usage-kit/samples/00-getting-started/golden_e2e/test_cases/golden_success.yaml"
  "usage-kit/samples/00-getting-started/golden_e2e/env_profiles/local_v03.yaml"
  "usage-kit/samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/http/rest_client_with_wiremock/test_cases/payment_success.yaml"
  "usage-kit/samples/20-provider-capability-p0/http/rest_client_with_wiremock/env_profiles/local_v03.yaml"
  "usage-kit/samples/20-provider-capability-p0/http/rest_client_external/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/http/rest_client_external/env_profiles/external_native.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/rpc/grpc_client_external/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/rpc/grpc_client_external/env_profiles/external_native.yaml"
  "usage-kit/samples/20-provider-capability-p0/verification/artifact_compare/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/verification/common_verify/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/verification/polling_observer/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/verification/multi_test_shared_env/suite_manifest.yaml"
  "usage-kit/samples/00-getting-started/golden_e2e/suite_manifest.yaml"
  "usage-kit/samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/suite_manifest.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/env_profiles/external_oracle.yaml"
  "usage-kit/samples/20-provider-capability-p0/data/jdbc/env_profiles/external_db2.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/nats/env_profiles/external_nats.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/kafka/env_profiles/external_kafka.yaml"
  "usage-kit/samples/20-provider-capability-p0/messaging/ibm_mq/env_profiles/external_ibm_mq.yaml"
  "usage-kit/samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml"
  "usage-kit/samples/30-cross-provider-groups/mixed_provider_e2e/suite_manifest.yaml"
  "usage-kit/samples/40-evidence-reporting/evidence_hardening/valid_result.json"
  "usage-kit/samples/80-negative/suite_manifest.yaml"
  "usage-kit/samples/80-negative/target-resolution/unknown_target/suite_manifest.yaml"
  "usage-kit/samples/90-compatibility/README.md"
  "usage-kit/samples/90-compatibility/DEPRECATED_PATHS.md"
  "usage-kit/samples/90-compatibility/dummy_rest/suite_manifest.yaml"
  "usage-kit/samples/90-compatibility/dummy_rest/DEPRECATED.md"
)

for path in "${required_paths[@]}"; do
  if [[ ! -e "${WORK_DIR}/${path}" ]]; then
    echo "Usage kit missing required path: ${path}" >&2
    exit 1
  fi
done

for forbidden in '.DS_Store' 'target' '.dependency-check-data' 'docs/idea' 'docs/template' 'docs/99-archive'; do
  if (
    cd "${WORK_DIR}/usage-kit"
    find . \( -path "./${forbidden}" -o -path "./${forbidden}/*" -o -path "*/${forbidden}" -o -path "*/${forbidden}/*" \) -print -quit
  ) | grep -q .; then
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

if ! grep -q "^sample_layout_version: v3$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing sample_layout_version: v3." >&2
  exit 1
fi

if ! grep -q "^sample_public_interface: v0.3$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing sample_public_interface: v0.3." >&2
  exit 1
fi

if ! grep -q "^contract_versions: \\[v0.2, v0.3\\]$" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing contract_versions: [v0.2, v0.3]." >&2
  exit 1
fi

if ! grep -q "^default_sample_roots:" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing default_sample_roots." >&2
  exit 1
fi

if ! grep -q "old_path: samples/v0_3_dsl/negative/target_resolution/unknown_target" "${WORK_DIR}/usage-kit/usage-kit-manifest.yaml"; then
  echo "Usage kit manifest is missing negative sample path rewrites." >&2
  exit 1
fi

if ! grep -q "samples/v0_3_dsl/negative/target_resolution/unknown_target" "${WORK_DIR}/usage-kit/samples/90-compatibility/DEPRECATED_PATHS.md"; then
  echo "Usage kit deprecated path guide is missing negative sample rewrites." >&2
  exit 1
fi

if ! grep -q "docs/02-architecture/contracts/provider-contracts/README.md" "${WORK_DIR}/usage-kit/README.md"; then
  echo "Usage kit README is missing Provider Contract catalog guidance." >&2
  exit 1
fi

if ! grep -q "docs/02-architecture/contracts/provider-contracts/README.md" "${WORK_DIR}/usage-kit/samples/README.md"; then
  echo "Usage kit samples README is missing Provider Contract catalog guidance." >&2
  exit 1
fi

if ! grep -q "jdbc.v0.3" "${WORK_DIR}/usage-kit/docs/02-architecture/contracts/provider-contracts/README.md"; then
  echo "Usage kit Provider Contract catalog is missing v0.3 contract examples." >&2
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

run_v03_suite() {
  local suite="$1"
  local profile="$2"
  run_cli validate --suite "$suite" --profile "$profile"
  run_cli run --suite "$suite" --profile "$profile" --dry-run
  local run_output
  run_output="$(run_cli run --suite "$suite" --profile "$profile")"
  local result_json
  result_json="$(printf '%s\n' "$run_output" | awk -F': ' '/^result_json:/ {print $2; exit}')"
  if [[ -z "$result_json" || ! -s "$result_json" ]]; then
    echo "v0.3 sample did not produce result_json: $suite" >&2
    printf '%s\n' "$run_output" >&2
    exit 1
  fi
  run_cli report --result "$result_json"
  run_cli validate-evidence --result "$result_json"
}

run_v03_suite_group() {
  local suite="$1"
  local profile="$2"
  run_cli validate --suite "$suite" --profile "$profile"
  local dry_run_output
  dry_run_output="$(run_cli run --suite "$suite" --profile "$profile" --dry-run)"
  if ! printf '%s\n' "$dry_run_output" | grep -q "run_status: dry_run_ready"; then
    echo "v0.3 suite group dry-run was not ready: $suite" >&2
    printf '%s\n' "$dry_run_output" >&2
    exit 1
  fi
  if ! printf '%s\n' "$dry_run_output" | grep -q "provider_runtime_invoked: false"; then
    echo "v0.3 suite group dry-run invoked provider runtime: $suite" >&2
    printf '%s\n' "$dry_run_output" >&2
    exit 1
  fi

  local run_output
  run_output="$(run_cli run --suite "$suite" --profile "$profile")"
  local suite_summary_json
  suite_summary_json="$(printf '%s\n' "$run_output" | awk -F': ' '/^suite_summary_json:/ {print $2; exit}')"
  local allure_results_dir
  allure_results_dir="$(printf '%s\n' "$run_output" | awk -F': ' '/^allure_results_dir:/ {print $2; exit}')"
  if [[ -z "$suite_summary_json" || ! -s "$suite_summary_json" ]]; then
    echo "v0.3 suite group did not produce suite_summary_json: $suite" >&2
    printf '%s\n' "$run_output" >&2
    exit 1
  fi
  if [[ -z "$allure_results_dir" || ! -d "$allure_results_dir" ]]; then
    echo "v0.3 suite group did not produce allure_results_dir: $suite" >&2
    printf '%s\n' "$run_output" >&2
    exit 1
  fi
}

expect_validate_failure() {
  local suite="$1"
  local profile="$2"
  local reason="$3"
  local output
  if output="$(run_cli validate --suite "$suite" --profile "$profile" 2>&1)"; then
    echo "Expected v0.3 negative sample to fail validation: $suite" >&2
    printf '%s\n' "$output" >&2
    exit 1
  fi
  if ! printf '%s\n' "$output" | grep -q "reason: ${reason}"; then
    echo "v0.3 negative sample did not report expected reason ${reason}: $suite" >&2
    printf '%s\n' "$output" >&2
    exit 1
  fi
}

(
  cd "$WORK_DIR/usage-kit"
  run_cli validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
  run_cli run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --dry-run
  run_cli validate --suite samples/20-provider-capability-p0/suite_manifest.yaml
  run_v03_suite samples/00-getting-started/golden_e2e/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/verification/artifact_compare/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/verification/common_verify/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/verification/polling_observer/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml local_v03
  run_v03_suite samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml local_v03
  REST_BASE_URL=http://contract-validation.invalid run_cli run --suite samples/20-provider-capability-p0/http/rest_client_external/suite_manifest.yaml --profile external_native --dry-run
  GRPC_TARGET=contract-validation.invalid:443 run_cli run --suite samples/20-provider-capability-p0/rpc/grpc_client_external/suite_manifest.yaml --profile external_native --dry-run
  run_v03_suite samples/20-provider-capability-p0/verification/multi_test_shared_env/suite_manifest.yaml local_v03
  run_v03_suite_group samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml local_v03
  run_v03_suite samples/30-cross-provider-groups/mixed_provider_e2e/suite_manifest.yaml local_v03
  expect_validate_failure samples/80-negative/target-resolution/unknown_target/suite_manifest.yaml local_v03 invalid_target_ref
  run_cli validate-evidence --result samples/40-evidence-reporting/evidence_hardening/valid_result.json
)

removed_paths=(
  "usage-kit/samples/v0_3_dsl"
  "usage-kit/samples/golden_e2e"
  "usage-kit/samples/contract_baseline"
  "usage-kit/samples/provider_capability"
  "usage-kit/samples/evidence_hardening"
  "usage-kit/samples/90-compatibility/legacy-v0.2"
)

for path in "${removed_paths[@]}"; do
  if [[ -e "${WORK_DIR}/${path}" ]]; then
    echo "Usage kit contains removed v0.3 sample path: ${path}" >&2
    exit 1
  fi
done

echo "usage_kit_verification_status: passed"
echo "usage_kit_file: ${ZIP_PATH}"
