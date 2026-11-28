#!/usr/bin/env bash
set -euo pipefail
dialect=${1:?Expected oracle or db2}
root=$(cd "$(dirname "$0")/../.." && pwd)
fixtures="$root/scripts/ci/native-jdbc"
case "$dialect" in
  oracle) source "$fixtures/oracle.sh" ;;
  db2) source "$fixtures/db2.sh" ;;
  *) exit 2 ;;
esac
version=$("$root/scripts/release/verify-release-version.sh")
jar="$root/target/spec-driven-auto-regression-${version}.jar"
test -s "$jar"
output="$root/target/native-jdbc/$dialect"
revision=${GITHUB_SHA:-$(git -C "$root" rev-parse HEAD)}
job_run=${GITHUB_RUN_ID:-local}
attempt=${GITHUB_RUN_ATTEMPT:-1}
scratch=$(mktemp -d)
server=""
finish() {
  if [[ -n "$server" ]]; then docker rm -fv "$server" >/dev/null || true; fi
  rm -rf -- "$scratch"
}
trap finish EXIT
cd "$scratch"
mkdir receipt
cp -R "$root/samples/20-provider-capability-p0/data/jdbc" suite
cp "$fixtures/seed_order.sql" suite/fixtures/seed_order.sql
driver="$scratch/$driver_file"
curl --fail --silent --show-error --location --retry 3 --max-time 90 "$driver_url" -o "$driver"
printf '%s  %s\n' "$driver_sha256" "$driver" | shasum -a 256 -c -
cli() { java -Xmx384m -jar "$jar" "$@"; }
cli doctor drivers --driver-path "$driver" > doctor.log
grep -Fx "${dialect}_driver_loadable: true" doctor.log

start_database
[[ "$JDBC_CONNECTION" == "jdbc:$dialect:"* ]]
engine=$(db_version)
[[ -n "$engine" && "$(db_rows)" == 'other-run KEEP' ]]
binding="$dialect:JDBC_CONNECTION"
seed=5368a630a858cfbf1dd0f6e36d5d8219afcdb4560c262ea9fab072fcedf7291d
query=8732f08dcf92d1b1d1ea3908622ab1fa3243633ca549c1ae1dbe640b11f5a5c9
cleanup=77f943082ad8e1044bcd20d270749d0ac4d5a67cac02a00ce4fd30326dec6419
export REGRESS_JDBC_APPROVED_SQL_SHA256="$binding:db_seed:$seed,$binding:db_query:$query,$binding:db_record_exists:$query,$binding:db_cleanup:$cleanup"

run_case() {
  local expected=$1 expected_exit=$2 count=$3 actual_exit=0 order_id result digest remaining
  order_id="ORD-$(openssl rand -hex 12)"
  sed -e "s/NATIVE-ORDER-ID/$order_id/g" -e "s/^      expected: 1$/      expected: $count/" \
    "$fixtures/order.yaml" > suite/test_cases/order_query_success.yaml
  cli run --suite suite/suite_manifest.yaml --profile "external_$dialect" --driver-path "$driver" > "$expected.log" || actual_exit=$?
  result=$(sed -n 's/^result_json: //p' "$expected.log")
  if [[ "$actual_exit" != "$expected_exit" || ! -f "$result" ]]; then
    if [[ -f "$result" ]]; then jq '{status,reason:.failure.code,steps:[.step_results[]|{id,status}]}' "$result"; fi
    echo "Expected $expected run to exit $expected_exit, observed $actual_exit" >&2
    return 1
  fi
  cli validate-evidence --result "$result"
  remaining=$(db_rows)
  [[ "$remaining" == 'other-run KEEP' ]]
  cp "$result" "receipt/$expected.json"
  digest=$(shasum -a 256 "$result" | cut -d' ' -f1)
  jq -n --arg id "$order_id" --arg digest "$digest" --arg remaining "$remaining" \
    '{order_id:$id,result_sha256:$digest,remaining_rows:[$remaining],evidence_validation:"validated"}' > "$expected-observation.json"
}
run_case passed 0 1
run_case failed 1 2
jq -n --arg dialect "$dialect" --arg engine "$engine" --arg image "$image" --arg driver "$driver_file" \
  --arg pin "$driver_sha256" --arg revision "$revision" --arg run "$job_run" --arg attempt "$attempt" \
  --slurpfile passed passed-observation.json --slurpfile failed failed-observation.json \
  '{schema_version:"native-jdbc.v1",dialect:$dialect,engine_version:$engine,image:$image,driver:$driver,
    driver_sha256:$pin,revision:$revision,job_run:$run,job_attempt:$attempt,
    cases:{passed:$passed[0],failed:$failed[0]}}' > receipt/receipt.json
python3 -B "$root/scripts/ci/native_jdbc_evidence.py" receipt "$dialect" "$revision" "$job_run" "$attempt"
mkdir -p "$output"
cp receipt/{receipt,passed,failed}.json "$output/"
