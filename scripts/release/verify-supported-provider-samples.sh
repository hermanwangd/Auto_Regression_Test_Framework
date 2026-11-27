#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"
REQUIRE_EXTERNAL_MESSAGING="${REQUIRE_EXTERNAL_MESSAGING:-false}"
REQUIRE_EXTERNAL_JDBC="${REQUIRE_EXTERNAL_JDBC:-false}"
REQUIRE_EXTERNAL_CLIENTS="${REQUIRE_EXTERNAL_CLIENTS:-false}"
JDBC_EXTERNAL_DIALECT="${JDBC_EXTERNAL_DIALECT:-oracle}"
jdbc_sql_approvals="${REGRESS_JDBC_APPROVED_SQL_SHA256:-}"
unset REGRESS_JDBC_APPROVED_SQL_SHA256
external_env_names=(NATS_CONNECTION KAFKA_BOOTSTRAP_SERVERS IBM_MQ_CONN_NAME IBM_MQ_CREDENTIAL)
external_client_env_names=(REST_BASE_URL GRPC_TARGET)

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
  REST_BASE_URL="${REST_BASE_URL:-http://contract-validation.invalid}" \
  GRPC_TARGET="${GRPC_TARGET:-contract-validation.invalid:443}" \
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

external_client_env_state() {
  missing_external_client_envs=()
  present_external_client_envs=()
  for name in "${external_client_env_names[@]}"; do
    if [[ -z "${!name:-}" ]]; then
      missing_external_client_envs+=("$name")
    else
      present_external_client_envs+=("$name")
    fi
  done
}

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
  mariadb)
    jdbc_external_suite="samples/20-provider-capability-p0/data/jdbc/suite_manifest_mariadb.yaml"
    jdbc_external_profile="external_mariadb"
    ;;
  *)
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "invalid_external_jdbc_dialect: ${JDBC_EXTERNAL_DIALECT}" >&2
    echo "owner_action: Set JDBC_EXTERNAL_DIALECT to oracle, db2 or mariadb." >&2
    exit 1
    ;;
esac

if [[ "$REQUIRE_EXTERNAL_JDBC" == "true" ]]; then
  if [[ -z "${JDBC_CONNECTION:-}" ]]; then
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "missing_external_jdbc_env: JDBC_CONNECTION" >&2
    echo "owner_action: Configure JDBC_CONNECTION for the selected native database." >&2
    exit 1
  fi
  if [[ -z "${jdbc_sql_approvals//[[:space:]]/}" ]]; then
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "missing_external_jdbc_env: REGRESS_JDBC_APPROVED_SQL_SHA256" >&2
    echo "owner_action: Supply independently reviewed seed, query and cleanup SQL digests for this dialect and JDBC_CONNECTION." >&2
    exit 1
  fi
  if [[ "$JDBC_CONNECTION" != "jdbc:${JDBC_EXTERNAL_DIALECT}:"* ]]; then
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "owner_action: Supply a native JDBC_CONNECTION for the selected JDBC_EXTERNAL_DIALECT." >&2
    exit 1
  fi
fi

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
validate_external_profile samples/20-provider-capability-p0/data/jdbc/suite_manifest_mariadb.yaml external_mariadb
validate_external_profile samples/20-provider-capability-p0/http/rest_client_external/suite_manifest.yaml external_native
validate_external_profile samples/20-provider-capability-p0/rpc/grpc_client_external/suite_manifest.yaml external_native

if [[ "$REQUIRE_EXTERNAL_JDBC" == "true" ]]; then
  # Verify the entire release sample before seed can change the native database.
  if ! REGRESS_JDBC_APPROVED_SQL_SHA256="$jdbc_sql_approvals" python3 -I - "$JDBC_EXTERNAL_DIALECT" "${jdbc_external_suite%/*}" <<'PY_SQL'
import hashlib
import os
from pathlib import Path
import re
import sys

dialect, suite_root = sys.argv[1:]
entries = {entry.strip(" \t\r\n\f\v") for entry in os.environ["REGRESS_JDBC_APPROVED_SQL_SHA256"].split(",")}
entry_format = re.compile(r"(oracle|db2|mariadb):[A-Z_][A-Z0-9_]*:(db_seed|db_query|db_record_exists|db_cleanup):[0-9a-f]{64}")
if not all(entry_format.fullmatch(entry) for entry in entries):
    sys.exit("invalid_external_jdbc_sql_approval_format")

required = [
    ("db_seed", "fixtures/seed_order.sql"),
    ("db_query", "queries/find_order_by_id.sql"),
    ("db_cleanup", "fixtures/cleanup_order.sql"),
]
if dialect == "mariadb":
    required.extend([("db_query", "queries/new_mariadb_order_id.sql"),
                     ("db_record_exists", "queries/find_order_by_id.sql")])
for operation, ref in required:
    try:
        digest = hashlib.sha256()
        with (Path(suite_root) / ref).open("rb") as source:
            for chunk in iter(lambda: source.read(65536), b""):
                digest.update(chunk)
    except OSError:
        sys.exit(f"unreadable_external_jdbc_sql: {operation}")
    if f"{dialect}:JDBC_CONNECTION:{operation}:{digest.hexdigest()}" not in entries:
        sys.exit(f"unapproved_external_jdbc_sql: {operation}")
PY_SQL
  then
    echo "external_jdbc_runtime_verification: blocked" >&2
    echo "owner_action: Verify the independently supplied approvals for every native sample operation and SQL file." >&2
    exit 1
  fi
  REGRESS_JDBC_APPROVED_SQL_SHA256="$jdbc_sql_approvals" run_suite "$jdbc_external_suite" "$jdbc_external_profile"
  echo "external_jdbc_runtime_verification: passed"
else
  echo "external_jdbc_runtime_verification: not_configured"
  echo "owner_action: Configure JDBC_CONNECTION and REGRESS_JDBC_APPROVED_SQL_SHA256, set JDBC_EXTERNAL_DIALECT=oracle|db2|mariadb, and set REQUIRE_EXTERNAL_JDBC=true when native external JDBC runtime evidence is required."
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
fi

external_client_env_state

if [[ "$REQUIRE_EXTERNAL_CLIENTS" == "true" || "${#present_external_client_envs[@]}" -gt 0 ]]; then
  if [[ "${#missing_external_client_envs[@]}" -gt 0 ]]; then
    echo "external_client_runtime_verification: blocked" >&2
    echo "missing_external_client_env: ${missing_external_client_envs[*]}" >&2
    echo "owner_action: Configure both REST_BASE_URL and GRPC_TARGET, or leave both unset so CI validates external profiles without invoking caller-provided services." >&2
    exit 1
  fi
  require_env REST_BASE_URL
  require_env GRPC_TARGET
  run_suite samples/20-provider-capability-p0/http/rest_client_external/suite_manifest.yaml external_native
  run_suite samples/20-provider-capability-p0/rpc/grpc_client_external/suite_manifest.yaml external_native
  echo "external_client_runtime_verification: passed"
else
  echo "external_client_runtime_verification: not_configured"
  echo "owner_action: Configure REST_BASE_URL and GRPC_TARGET and set REQUIRE_EXTERNAL_CLIENTS=true when caller-owned external client evidence is required."
fi

echo "supported_provider_sample_verification_status: passed"
