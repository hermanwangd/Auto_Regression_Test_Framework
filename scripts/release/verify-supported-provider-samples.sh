#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"
REQUIRE_EXTERNAL_MESSAGING="${REQUIRE_EXTERNAL_MESSAGING:-false}"
REQUIRE_EXTERNAL_JDBC="${REQUIRE_EXTERNAL_JDBC:-false}"
JDBC_EXTERNAL_DIALECT="${JDBC_EXTERNAL_DIALECT:-oracle}"
external_env_names=(NATS_CONNECTION KAFKA_BOOTSTRAP_SERVERS IBM_MQ_CONN_NAME IBM_MQ_CREDENTIAL)

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for provider sample verification: $JAR" >&2
  exit 1
fi

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

# External profiles are always schema-checked in public CI, but CI does not own
# broker, queue-manager, or database credentials. Supply isolated, non-secret
# placeholders only for validation; native execution still requires real values.
validate_external_profile() {
  local suite="$1"
  local profile="$2"
  NATS_CONNECTION="${NATS_CONNECTION:-nats://contract-validation.invalid:4222}" \
  KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-contract-validation.invalid:9092}" \
  IBM_MQ_CONN_NAME="${IBM_MQ_CONN_NAME:-CONTRACT.VALIDATION}" \
  IBM_MQ_CREDENTIAL="${IBM_MQ_CREDENTIAL:-contract-validation-placeholder}" \
  JDBC_CONNECTION="${JDBC_CONNECTION:-contract-validation-placeholder}" \
    run_cli validate --suite "$suite" --profile "$profile"
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
  "samples/20-provider-capability-p0/verification/artifact_compare/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/verification/common_verify/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/verification/polling_observer/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml local_v03"
  "samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml local_v03"
)

for entry in "${supported_local_suites[@]}"; do
  # shellcheck disable=SC2086
  run_suite $entry
done

echo "compatibility_sample_verification: dummy_rest"
run_suite samples/90-compatibility/dummy_rest/suite_manifest.yaml local_dummy

validate_external_profile samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml external_kafka
validate_external_profile samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml external_ibm_mq
validate_external_profile samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml external_nats
validate_external_profile samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml external_oracle
validate_external_profile samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml external_db2

jdbc_external_suite=""
jdbc_external_profile=""
case "$JDBC_EXTERNAL_DIALECT" in
  oracle)
    jdbc_external_suite="samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml"
    jdbc_external_profile="external_oracle"
    ;;
  db2)
    jdbc_external_suite="samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml"
    jdbc_external_profile="external_db2"
    ;;
  *)
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "invalid_external_jdbc_dialect: ${JDBC_EXTERNAL_DIALECT}" >&2
    echo "owner_action: Set JDBC_EXTERNAL_DIALECT to oracle or db2." >&2
    exit 1
    ;;
esac

if [[ "$REQUIRE_EXTERNAL_JDBC" == "true" ]]; then
  if [[ -z "${JDBC_CONNECTION:-}" ]]; then
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "missing_external_jdbc_env: JDBC_CONNECTION" >&2
    echo "owner_action: Configure JDBC_CONNECTION and JDBC_EXTERNAL_DIALECT, or leave REQUIRE_EXTERNAL_JDBC=false when native external JDBC runtime evidence is not required." >&2
    exit 1
  fi
  run_suite "$jdbc_external_suite" "$jdbc_external_profile"
  echo "external_jdbc_runtime_verification: passed"
else
  echo "external_jdbc_runtime_verification: not_configured"
  echo "owner_action: Configure JDBC_CONNECTION, set JDBC_EXTERNAL_DIALECT=oracle|db2, and set REQUIRE_EXTERNAL_JDBC=true when native external JDBC runtime evidence is required."
fi

external_env_state

if [[ "$REQUIRE_EXTERNAL_MESSAGING" == "true" || "${#present_external_envs[@]}" -gt 0 ]]; then
  if [[ "${#missing_external_envs[@]}" -gt 0 ]]; then
    echo "external_messaging_runtime_verification: blocked" >&2
    echo "missing_external_messaging_env: ${missing_external_envs[*]}" >&2
    echo "owner_action: Configure all NATS, Kafka, and IBM MQ external messaging secrets, or leave all of them unset so CI runs only release-verifiable local/provider samples." >&2
    exit 1
  fi
  require_env NATS_CONNECTION
  require_env KAFKA_BOOTSTRAP_SERVERS
  require_env IBM_MQ_CONN_NAME
  require_env IBM_MQ_CREDENTIAL
  run_suite samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml external_nats
  run_suite samples/20-provider-capability-p0/messaging/kafka/suite_manifest.yaml external_kafka
  run_suite samples/20-provider-capability-p0/messaging/ibm_mq/suite_manifest.yaml external_ibm_mq
  echo "external_messaging_runtime_verification: passed"
else
  echo "external_messaging_runtime_verification: not_configured"
  echo "owner_action: Configure NATS, Kafka, and IBM MQ external messaging secrets and set REQUIRE_EXTERNAL_MESSAGING=true when native external runtime evidence is required."
  echo "supported_provider_sample_verification_status: passed_ci_verifiable_external_messaging_not_configured"
  exit 0
fi

echo "supported_provider_sample_verification_status: passed"
