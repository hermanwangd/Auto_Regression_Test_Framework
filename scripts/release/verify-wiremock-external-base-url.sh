#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

VERSION="$("${ROOT_DIR}/scripts/release/verify-release-version.sh" "${1:-}")"
JAR="${ROOT_DIR}/target/spec-driven-auto-regression-${VERSION}.jar"

if [[ ! -s "$JAR" ]]; then
  echo "Missing release jar for WireMock Admin API external base_url verification: $JAR" >&2
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
    echo "WireMock Admin API external base_url verification missing expected text: $expected" >&2
    echo "file: $file" >&2
    exit 1
  fi
}

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq "$unexpected" "$file"; then
    echo "WireMock Admin API external base_url verification found unexpected text: $unexpected" >&2
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
mappings = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def _json(self, status, payload):
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        if self.path == "/__admin/mappings":
            self._json(200, {"mappings": mappings})
            return
        if self.path == "/__admin/requests":
            self._json(200, {"requests": requests})
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == "/__admin/reset":
            requests.clear()
            mappings.clear()
            self._json(200, {"status": "reset"})
            return
        if self.path == "/__admin/mappings":
            mappings.append(self._read_json())
            self._json(201, {"status": "created"})
            return
        if self.path == "/payments":
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8") if length else ""
            requests.append({
                "request": {
                    "method": "POST",
                    "url": "/payments",
                    "body": body,
                }
            })
            self._json(200, {"status": "ACCEPTED", "provider": "wiremock"})
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
  echo "WireMock-compatible external server did not start." >&2
  exit 1
fi

BASE_URL="$(cat "$BASE_URL_FILE")"
SUITE_ROOT="$WORK_DIR/wiremock_external"
COMPATIBILITY_SUITE="$ROOT_DIR/samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock"
if [[ ! -d "$COMPATIBILITY_SUITE" ]]; then
  echo "Missing v0.2 WireMock compatibility suite: $COMPATIBILITY_SUITE" >&2
  echo "owner_action: Restore the compatibility fixture or remove this compatibility verifier from the release gate." >&2
  exit 1
fi
cp -R "$COMPATIBILITY_SUITE" "$SUITE_ROOT"

python3 - "$SUITE_ROOT/env_profiles/local_wiremock.yaml" "$BASE_URL" <<'PY'
from pathlib import Path
import sys

env_profile = Path(sys.argv[1])
base_url = sys.argv[2]
original = env_profile.read_text(encoding="utf-8")
updated = original.replace(
    """    runtime_mode: mock
    bindings:
      port_strategy: dynamic
      mappings_ref:
        ref: fixtures/
""",
    f"""    runtime_mode: external
    bindings:
      base_url:
        value: {base_url}
      mappings_ref:
        ref: fixtures/
""",
)
if updated == original:
    raise SystemExit("failed to patch WireMock Env_Profile for external base_url")
env_profile.write_text(updated, encoding="utf-8")
PY

run_cli validate --suite "$SUITE_ROOT/suite_manifest.yaml" --profile local_wiremock
RUN_STDOUT="$WORK_DIR/run_stdout.txt"
run_cli run --suite "$SUITE_ROOT/suite_manifest.yaml" --profile local_wiremock | tee "$RUN_STDOUT"
RESULT_JSON="$(result_path_from_stdout < "$RUN_STDOUT")"
if [[ -z "$RESULT_JSON" || ! -s "$RESULT_JSON" ]]; then
  echo "WireMock Admin API external base_url verification did not produce result_json." >&2
  exit 1
fi

run_cli report --result "$RESULT_JSON" --format text
run_cli report --result "$RESULT_JSON" --format json > "$WORK_DIR/report.json"
run_cli validate-evidence --result "$RESULT_JSON"

assert_contains "$RESULT_JSON" "\"provider_id\": \"wiremock-payment-api\""
assert_contains "$RESULT_JSON" "\"provider_type\": \"wiremock_http_mock\""
assert_contains "$RESULT_JSON" "\"runtime_mode\": \"external\""
assert_contains "$RESULT_JSON" "\"base_url\": \"$BASE_URL\""
assert_contains "$RESULT_JSON" "\"framework_started_wiremock\": false"
assert_contains "$RESULT_JSON" "\"status\": \"passed\""
assert_not_contains "$RESULT_JSON" "\"provider_type\": \"rest_client\""
assert_not_contains "$RESULT_JSON" "\"framework_started_wiremock\": true"

echo "wiremock_admin_api_external_base_url_compatibility_verification: passed"
echo "framework_consumed_project_dependencies: true"
echo "framework_consumption_status: framework_wiremock_admin_api_external_base_url_consumed"
