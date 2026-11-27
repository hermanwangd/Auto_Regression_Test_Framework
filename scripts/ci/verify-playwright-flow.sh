#!/usr/bin/env bash
# Exercise a real browser through the same packaged CLI and result contract a consumer uses.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/../.." && pwd)
sample="$root/samples/20-provider-capability-p0/ui/playwright"
jar=${FRAMEWORK_JAR:-"$root/target/spec-driven-auto-regression-$("$root/scripts/release/verify-release-version.sh").jar"}
jar=$(realpath "$jar")
tools=${UI_TOOLS:-"$root/target/playwright-tools"}
tools=$(realpath "$tools")
: "${UI_NODE:?Set UI_NODE to the runner-provisioned standalone Node executable}"
: "${UI_NODE_SHA256:?Set UI_NODE_SHA256 to its reviewed SHA-256 digest}"
node_path=$(realpath "$UI_NODE")
browser_args=()
if [[ -n ${UI_CHROMIUM:-} ]]; then browser_args+=(--chromium "$(realpath "$UI_CHROMIUM")"); fi
test -s "$jar"
mkdir -p "$root/target/playwright-runs"
work=$(mktemp -d "$root/target/playwright-runs/check.XXXXXXXX")
cd "$work"
printf 'browser_evidence_root: %s\n' "$work"
python3 "$sample/fixture/serve.py" > fixture.url 2> fixture.log &
fixture=$!
trap 'kill "$fixture" 2>/dev/null || true; wait "$fixture" 2>/dev/null || true' EXIT
for i in {1..100}; do
  [[ -s fixture.url ]] && break
  kill -0 "$fixture" || { cat fixture.log >&2; exit 1; }
  sleep 0.05
done
test -s fixture.url
url=$(cat fixture.url)
cli() { java -Xmx384m -jar "$jar" "$@"; }

run_case() {
  local name=$1 expected_text=$2 expected_exit=$3 actual_exit=0 result run_dir evidence
  python3 "$sample/configure.py" --node "$node_path" --node-sha256 "$UI_NODE_SHA256" \
    --tools "$tools" ${browser_args[@]+"${browser_args[@]}"} --url "$url" --out "$name" \
    --expected-after-reload "$expected_text"
  local suite="$name/suite_manifest.yaml"
  cli validate --suite "$suite" --profile local_browser
  cli run --suite "$suite" --profile local_browser > "$name.log" || actual_exit=$?
  cat "$name.log"
  result=$(sed -n 's/^result_json: //p' "$name.log")
  test -s "$result"
  run_dir=$(dirname "$result")
  local evidence_files=("$run_dir"/provider-evidence/cli_process/*.json)
  [[ ${#evidence_files[@]} == 1 ]]
  evidence=${evidence_files[0]}
  # Echo bounded, masked framework diagnostics if the run fails unexpectedly.
  jq '{operation_status, failure_code, cleanup_status, exit_code, stdout, stderr, error}' "$evidence"
  [[ $actual_exit == "$expected_exit" ]]
  cli validate-evidence --result "$result"
  cli report --result "$result" --format text
  jq -e --arg hash "$UI_NODE_SHA256" '
    .operation_status == "passed" and .cleanup_status == "complete" and
    .launched == true and .executable_sha256 == $hash' "$evidence" >/dev/null
  local trace
  trace="$(jq -er '.cwd' "$evidence")/failure-trace.zip"
  if [[ $name == positive ]]; then
    jq -e '.status == "passed" and any(.step_results[]; .id == "submit_order" and
      .outputs.exit_code == 0 and .outputs.stdout == "Order UI-1042 confirmed after reload\n")' "$result" >/dev/null
    [[ ! -e "$trace" ]]
  else
    jq -e '.status == "failed" and .failure.code == "ASSERTION_FAILED" and
      any(.step_results[]; .id == "submit_order" and .status == "passed" and .outputs.exit_code == 1 and
        .outputs.stderr == "Browser assertion failed: confirmation after reload\n")' "$result" >/dev/null
    python3 - "$trace" <<'PY'
import json
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as trace:
    if trace.testzip():
        raise SystemExit("Playwright trace ZIP is corrupt")
    events = [json.loads(line) for line in trace.read("trace.trace").splitlines()]
    actions = {e.get("method") for e in events if e.get("type") == "before"}
    if not {"click", "reload"}.issubset(actions):
        raise SystemExit("Playwright trace lacks submission or reload")
PY
    printf 'failure_trace: %s\n' "$trace"
  fi
}
run_case positive 'Order UI-1042 confirmed' 0
run_case wrong-confirmation 'Order UI-1042 declined' 1
