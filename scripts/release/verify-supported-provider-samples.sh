#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"
REQUIRE_EXTERNAL_MESSAGING="${REQUIRE_EXTERNAL_MESSAGING:-false}"
external_env_names=(KAFKA_BOOTSTRAP_SERVERS IBM_MQ_CONN_NAME IBM_MQ_CREDENTIAL)

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for provider sample verification: $JAR" >&2
  exit 1
fi

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

result_path_from_stdout() {
  awk -F ': ' '/^result_json:/ { print $2; exit }'
}

run_suite() {
  local suite="$1"
  local profile="$2"
  local stdout
  local result_json

  run_cli validate --suite "$suite" --profile "$profile"
  stdout="$(run_cli run --suite "$suite" --profile "$profile")"
  printf '%s\n' "$stdout"
  result_json="$(printf '%s\n' "$stdout" | result_path_from_stdout)"
  if [[ -z "$result_json" || ! -s "$result_json" ]]; then
    echo "Provider sample did not produce result_json: suite=${suite} profile=${profile}" >&2
    exit 1
  fi
  run_cli report --result "$result_json" --format text
  run_cli report --result "$result_json" --format yaml >/dev/null
  run_cli validate-evidence --result "$result_json"
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required external messaging environment variable: ${name}" >&2
    exit 1
  fi
}

external_env_state() {
  missing_external_envs=()
  present_external_envs=()
  for name in "${external_env_names[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      missing_external_envs+=("$name")
    else
      present_external_envs+=("$name")
    fi
  done
}

supported_local_suites=(
  "samples/provider_capability/compare/suite_manifest.yaml local_compare"
  "samples/provider_capability/common_verify/suite_manifest.yaml local_verify"
  "samples/provider_capability/dummy_rest/suite_manifest.yaml local_dummy"
  "samples/provider_capability/grpc_mock/suite_manifest.yaml local_grpc_mock"
  "samples/provider_capability/ibm_mq/suite_manifest.yaml local_ibm_mq"
  "samples/provider_capability/jdbc/suite_manifest.yaml local_jdbc"
  "samples/provider_capability/kafka/suite_manifest.yaml local_kafka"
  "samples/provider_capability/messaging_mixed/suite_manifest.yaml local_messaging"
  "samples/provider_capability/nats/suite_manifest.yaml local_nats"
  "samples/provider_capability/polling/suite_manifest.yaml local_polling"
  "samples/provider_capability/soap_mock/suite_manifest.yaml local_soap_mock"
  "samples/provider_capability/wiremock/suite_manifest.yaml local_wiremock"
  "samples/provider_capability/wiremock_http_request/suite_manifest.yaml local_wiremock_http"
)

for entry in "${supported_local_suites[@]}"; do
  # shellcheck disable=SC2086
  run_suite $entry
done

run_cli validate --suite samples/provider_capability/kafka/suite_manifest.yaml --profile ci_kafka_external
run_cli validate --suite samples/provider_capability/ibm_mq/suite_manifest.yaml --profile ci_ibm_mq_external

external_env_state

if [[ "$REQUIRE_EXTERNAL_MESSAGING" == "true" || "${#present_external_envs[@]}" -gt 0 ]]; then
  if [[ "${#missing_external_envs[@]}" -gt 0 ]]; then
    echo "external_messaging_runtime_verification: blocked" >&2
    echo "missing_external_messaging_env: ${missing_external_envs[*]}" >&2
    echo "owner_action: Configure all Kafka and IBM MQ external messaging secrets, or leave all of them unset so CI runs only release-verifiable local/provider samples." >&2
    exit 1
  fi
  require_env KAFKA_BOOTSTRAP_SERVERS
  require_env IBM_MQ_CONN_NAME
  require_env IBM_MQ_CREDENTIAL
  run_suite samples/provider_capability/kafka/suite_manifest.yaml ci_kafka_external
  run_suite samples/provider_capability/ibm_mq/suite_manifest.yaml ci_ibm_mq_external
  echo "external_messaging_runtime_verification: passed"
else
  echo "external_messaging_runtime_verification: not_configured"
  echo "owner_action: Configure Kafka and IBM MQ external messaging secrets and set REQUIRE_EXTERNAL_MESSAGING=true when native external runtime evidence is required."
  echo "supported_provider_sample_verification_status: passed_ci_verifiable_external_messaging_not_configured"
  exit 0
fi

echo "supported_provider_sample_verification_status: passed"
