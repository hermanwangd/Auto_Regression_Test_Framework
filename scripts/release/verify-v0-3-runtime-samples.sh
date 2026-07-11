#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="${REGRESS_JAR:-${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar}"

if [[ -z "${REGRESS_JAR:-}" ]]; then
  echo "building_release_jar: ${JAR}"
  MAVEN_OPTS="${MAVEN_OPTS:--Xmx1024m -XX:MaxMetaspaceSize=384m}" ./mvnw -q -DskipTests package
else
  echo "using_external_regress_jar: ${JAR}"
fi

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for v0.3 runtime sample verification: $JAR" >&2
  exit 1
fi

if ! jar tf "$JAR" | grep -q 'com/specdriven/regression/contract/v03/V03RuntimeExecutionService.class'; then
  echo "Release jar does not contain v0.3 runtime execution classes: $JAR" >&2
  exit 1
fi

V03_P0_SUITES=(
  "samples/00-getting-started/golden_e2e/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/verification/artifact_compare/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/verification/common_verify/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/verification/polling_observer/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml:local_v03"
  "samples/20-provider-capability-p0/verification/multi_test_shared_env/suite_manifest.yaml:local_v03"
  "samples/30-cross-provider-groups/mixed_provider_e2e/suite_manifest.yaml:local_v03"
)

V03_SUITE_GROUPS=(
  "samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml:local_v03"
)

V03_NEGATIVE_SUITES=(
  "samples/80-negative/target-resolution/unknown_target/suite_manifest.yaml:local_v03:invalid_target_ref"
  "samples/80-negative/target-resolution/missing_provider_contract/suite_manifest.yaml:local_v03:missing_provider_contract"
  "samples/80-negative/bindings/missing_required_binding/suite_manifest.yaml:local_v03:missing_required_binding_key"
  "samples/80-negative/bindings/unknown_binding_key/suite_manifest.yaml:local_v03:unknown_binding_key"
  "samples/80-negative/operations/unsupported_operation/suite_manifest.yaml:local_v03:unsupported_operation"
  "samples/80-negative/operations/unsupported_input/suite_manifest.yaml:local_v03:unsupported_input"
  "samples/80-negative/refs/invalid_artifact_ref/suite_manifest.yaml:local_v03:invalid_artifact_ref"
  "samples/80-negative/secrets/raw_secret_dsl/suite_manifest.yaml:local_v03:raw_secret"
  "samples/80-negative/secrets/raw_secret_env/suite_manifest.yaml:local_v03:raw_secret"
)

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

assert_output_contains() {
  local output="$1"
  local expected="$2"
  local context="$3"
  if ! grep -Fq "$expected" <<<"$output"; then
    echo "Expected ${context} output to contain: ${expected}" >&2
    printf '%s\n' "$output" >&2
    exit 1
  fi
}

for entry in "${V03_P0_SUITES[@]}"; do
  suite="${entry%%:*}"
  profile="${entry##*:}"
  echo "v0_3_runtime_suite: ${suite}"
  run_cli validate --suite "$suite" --profile "$profile"
  dry_run_output="$(run_cli run --suite "$suite" --profile "$profile" --dry-run)"
  printf '%s\n' "$dry_run_output"
  assert_output_contains "$dry_run_output" "run_status: dry_run_ready" "${suite} dry-run"
  assert_output_contains "$dry_run_output" "provider_runtime_invoked: false" "${suite} dry-run"
  run_output="$(run_cli run --suite "$suite" --profile "$profile")"
  printf '%s\n' "$run_output"
  result_json="$(printf '%s\n' "$run_output" | awk -F': ' '/^result_json:/ {print $2; exit}')"
  if [[ -z "$result_json" || ! -s "$result_json" ]]; then
    echo "v0.3 sample did not produce result_json: $suite" >&2
    printf '%s\n' "$run_output" >&2
    exit 1
  fi
  run_cli report --result "$result_json"
  run_cli validate-evidence --result "$result_json"
done

for entry in "${V03_SUITE_GROUPS[@]}"; do
  suite="${entry%%:*}"
  profile="${entry##*:}"
  echo "v0_3_runtime_suite_group: ${suite}"
  run_cli validate --suite "$suite" --profile "$profile"
  dry_run_output="$(run_cli run --suite "$suite" --profile "$profile" --dry-run)"
  printf '%s\n' "$dry_run_output"
  assert_output_contains "$dry_run_output" "run_status: dry_run_ready" "${suite} dry-run"
  assert_output_contains "$dry_run_output" "provider_runtime_invoked: false" "${suite} dry-run"
  run_output="$(run_cli run --suite "$suite" --profile "$profile")"
  printf '%s\n' "$run_output"
  suite_summary_json="$(printf '%s\n' "$run_output" | awk -F': ' '/^suite_summary_json:/ {print $2; exit}')"
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
done

for entry in "${V03_NEGATIVE_SUITES[@]}"; do
  suite="${entry%%:*}"
  rest="${entry#*:}"
  profile="${rest%%:*}"
  reason="${rest##*:}"
  echo "v0_3_negative_runtime_suite: ${suite}"
  set +e
  validate_output="$(run_cli validate --suite "$suite" --profile "$profile" 2>&1)"
  validate_exit=$?
  set -e
  printf '%s\n' "$validate_output"
  if [[ "$validate_exit" -eq 0 ]]; then
    echo "Expected negative v0.3 suite validation to fail: $suite" >&2
    exit 1
  fi
  assert_output_contains "$validate_output" "validation_status: failed" "${suite} negative validation"
  assert_output_contains "$validate_output" "reason: ${reason}" "${suite} negative validation"
  assert_output_contains "$validate_output" "owner_action:" "${suite} negative validation"
done

echo "v0_3_runtime_samples_status: passed"
echo "v0_3_runtime_samples_count: ${#V03_P0_SUITES[@]}"
echo "v0_3_runtime_suite_groups_count: ${#V03_SUITE_GROUPS[@]}"
echo "v0_3_negative_samples_count: ${#V03_NEGATIVE_SUITES[@]}"
