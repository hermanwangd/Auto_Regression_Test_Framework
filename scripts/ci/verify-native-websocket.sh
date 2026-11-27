#!/usr/bin/env bash
# Exercise a real JDK WebSocket connection and independent HTTP application change.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
version=$("$root/scripts/release/verify-release-version.sh")
jar="$root/target/spec-driven-auto-regression-${version}.jar"
test -s "$jar"
scratch=$(mktemp -d)
pid=""
finish() {
  if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  rm -rf -- "$scratch"
}
trap finish EXIT
cd "$scratch"
cp -R "$root/samples/20-provider-capability-p0/messaging/websocket" .
python3 "$root/scripts/ci/websocket-order-fixture.py" server.port closes &
pid=$!
for attempt in {1..50}; do
  if [[ -s server.port ]]; then break; fi
  kill -0 "$pid"
  sleep 0.1
done
test -s server.port
export FRAMEWORK_WEBSOCKET_URI="ws://127.0.0.1:$(cat server.port)/events"
export REST_BASE_URL="http://127.0.0.1:$(cat server.port)"
cli() { java -Xmx384m -jar "$jar" "$@"; }
run_case() {
  local expected=$1 exit_code=$2 result actual=0
  cli run --suite websocket/suite_manifest.yaml --profile external_websocket > "$expected.log" || actual=$?
  cat "$expected.log"
  [[ $actual == "$exit_code" ]]
  result=$(sed -n 's/^result_json: //p' "$expected.log")
  cli validate-evidence --result "$result"
  jq -e --arg status "$expected" '
    .status == $status and .cleanup_failures == [] and
    any(.step_results[]; .operation == "websocket_subscribe" and .outputs.opened == true) and
    any(.step_results[]; .operation == "http_request" and .status == "passed") and
    any(.step_results[]; .operation == "websocket_await_event" and .status == $status
        and .outputs.matched == ($status == "passed") and .outputs.inspected_events == 3) and
    any(.step_results[]; .operation == "websocket_close" and .outputs.was_open == true and .outputs.closed == true) and
    ($status == "passed" or .failure.code == "WEBSOCKET_NO_MATCH")' "$result" >/dev/null
  local run_dir
  run_dir=$(dirname "$result")
  if grep -R -F 'ws-fixture-frame-private-marker' "$run_dir"; then return 1; fi
  if grep -R -F "$FRAMEWORK_WEBSOCKET_URI" "$run_dir/provider-evidence/websocket"; then return 1; fi
}
run_case passed 0
sed 's/expected_event: order.changed/expected_event: order.cancelled/' websocket/test_cases/order_changed.yaml > wrong.yaml
mv wrong.yaml websocket/test_cases/order_changed.yaml
run_case failed 1
[[ "$(grep -c '^normal-close$' closes)" == 2 ]]
echo "WebSocket native sample matched a correlated event, rejected an independent wrong expectation and confirmed both close handshakes."
