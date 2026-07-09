#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for WireMock external base_url verification: $JAR" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/regress-wiremock-external.XXXXXX")"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

run_cli() {
  java -Xmx512m -jar "$JAR" "$@"
}

result_path_from_stdout() {
  awk -F ': ' '/^result_json:/ { print $2; exit }'
}

assert_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq "$expected" "$file"; then
    echo "WireMock external base_url verification missing expected text: $expected" >&2
    echo "file: $file" >&2
    exit 1
  fi
}

BASE_URL_FILE="$WORK_DIR/base_url"
python3 - "$BASE_URL_FILE" <<'PY' &
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

base_url_file = sys.argv[1]
requests = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8") if length else ""
        requests.append({
            "request": {
                "method": "POST",
                "url": self.path,
                "body": body,
                "headers": {key: [value] for key, value in self.headers.items()},
            }
        })
        if self.path == "/payments":
            payload = {
                "status": "APPROVED",
                "paymentId": "PAY-HTTP-001",
                "provider": "wiremock",
            }
            encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(201)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self):
        if self.path == "/__admin/requests":
            encoded = json.dumps({"requests": requests}, separators=(",", ":")).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        self.send_response(404)
        self.end_headers()


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
with open(base_url_file, "w", encoding="utf-8") as handle:
    handle.write(f"http://127.0.0.1:{server.server_port}\n")
server.serve_forever()
PY
SERVER_PID=$!

for _ in $(seq 1 100); do
  if [[ -s "$BASE_URL_FILE" ]]; then
    break
  fi
  sleep 0.1
done

if [[ ! -s "$BASE_URL_FILE" ]]; then
  echo "WireMock external base_url verification server did not start." >&2
  exit 1
fi

BASE_URL="$(cat "$BASE_URL_FILE")"
SUITE_ROOT="$WORK_DIR/rest_wiremock_http"
cp -R "$ROOT_DIR/samples/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http" "$SUITE_ROOT"

cat > "$SUITE_ROOT/env_profiles/local_wiremock_external.yaml" <<EOF
env_profile_id: local_wiremock_external
execution_mode: local
isolation_scope: per_run
dependency_policy:
  require_readiness_evidence: false
  allow_framework_managed_dependencies: false
dependency_substitution_policy:
  allowed_runtime_modes: [mock, native]
  mock_evidence_release_claim: prohibited
dependency_provisioning_policy:
  allowed_provisioners: [project_provided]
data_policy:
  approved_expected_results_required: true
  production_data_allowed: false
  generated_data_allowed: true
  secrets_must_use_refs: true
providers:
  wiremock-payment-api:
    runtime_mode: mock
    bindings:
      base_url: "$BASE_URL"
  payment-api-client:
    runtime_mode: native
    bindings:
      base_url:
        generated_ref: generated://wiremock-payment-api.base_url
EOF

python3 - "$SUITE_ROOT/suite_manifest.yaml" "$SUITE_ROOT/test_case.yaml" <<'PY'
from pathlib import Path
import sys

manifest = Path(sys.argv[1])
test_case = Path(sys.argv[2])
manifest.write_text(
    manifest.read_text(encoding="utf-8")
    .replace("profile: local_wiremock_http", "profile: local_wiremock_external")
    .replace(
        "tests:\n  - test_case.yaml\n  - test_case_boundary.yaml",
        "tests:\n  - test_case.yaml",
    ),
    encoding="utf-8",
)
test_case.write_text(
    test_case.read_text(encoding="utf-8").replace(
        "compatible_profiles: [local_wiremock_http]",
        "compatible_profiles: [local_wiremock_external]",
    ),
    encoding="utf-8",
)
PY

run_cli validate --suite "$SUITE_ROOT/suite_manifest.yaml" --profile local_wiremock_external
RUN_STDOUT="$WORK_DIR/run_stdout.txt"
run_cli run --suite "$SUITE_ROOT/suite_manifest.yaml" --profile local_wiremock_external | tee "$RUN_STDOUT"
RESULT_JSON="$(result_path_from_stdout < "$RUN_STDOUT")"
if [[ -z "$RESULT_JSON" || ! -s "$RESULT_JSON" ]]; then
  echo "WireMock external base_url verification did not produce result_json." >&2
  exit 1
fi

run_cli report --result "$RESULT_JSON" --format text
run_cli report --result "$RESULT_JSON" --format json > "$WORK_DIR/report.json"
run_cli validate-evidence --result "$RESULT_JSON"

assert_contains "$RESULT_JSON" "\"provider_id\": \"wiremock-payment-api\""
assert_contains "$RESULT_JSON" "\"framework_started_wiremock\": false"
assert_contains "$RESULT_JSON" "\"external_base_url_consumed\": true"
assert_contains "$RESULT_JSON" "\"request_url\": \"$BASE_URL/payments\""
assert_contains "$RESULT_JSON" "\"status\": \"passed\""

echo "wiremock_external_base_url_verification: passed"
echo "framework_consumed_project_dependencies: true"
echo "framework_consumption_status: consumed"
