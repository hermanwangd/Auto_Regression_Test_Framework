#!/usr/bin/env bash
# Exercise the packaged public samples against disposable services, one at a time.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
version=$("$root/scripts/release/verify-release-version.sh")
jar="$root/target/spec-driven-auto-regression-${version}.jar"
test -s "$jar"
scratch=$(mktemp -d)
server=""
redis_pid=""
app_pid=""
stop_server() {
  if [[ -n "$redis_pid" ]]; then kill "$redis_pid" 2>/dev/null || true; wait "$redis_pid" 2>/dev/null || true; redis_pid=""; fi
  if [[ -n "$server" ]]; then docker rm -fv "$server" >/dev/null; server=""; fi
}
finish() {
  if [[ -n "$app_pid" ]]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  stop_server
  rm -rf -- "$scratch"
}
trap finish EXIT
cd "$scratch"
cli() { java -Xmx384m -jar "$jar" "$@"; }
ready() {
  for attempt in {1..45}; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Fixture did not become ready" >&2
  return 1
}
run_case() {
  local provider=$1 expected=$2 checked_op=$3 expected_code=$4
  local log="$provider-$expected.log" actual_code=0 result operations mismatch
  case "$provider" in
    mongodb) operations='["mongo_seed","mongo_read","mongo_find","mongo_exists","mongo_cleanup"]'; mismatch=MONGO_DOCUMENT_MISMATCH ;;
    redis) operations='["redis_set","redis_get","redis_ttl","redis_delete"]'; mismatch=REDIS_VALUE_MISMATCH ;;
    s3) operations='["s3_put_object","s3_head_object","s3_verify_object","s3_delete_object"]'; mismatch=S3_CHECKSUM_MISMATCH ;;
  esac
  cli run --suite "$provider/suite_manifest.yaml" --profile "external_$provider" > "$log" || actual_code=$?
  cat "$log"
  [[ $actual_code == "$expected_code" ]]
  result=$(sed -n 's/^result_json: //p' "$log")
  cli validate-evidence --result "$result"
  jq -e --arg p "$provider" --arg expected "$expected" --arg op "$checked_op" --arg mismatch "$mismatch" --argjson operations "$operations" '
    .status == $expected and .cleanup_failures == [] and
    any(.provider_results[]; .provider_type == $p) and
    any(.step_results[]; .operation == $op and .status == "passed") and
    any(.step_results[]; .phase == "cleanup" and .status == "passed") and
    (if $expected == "passed" then ($operations - [.step_results[] | select(.status == "passed") | .operation]) == []
     else .failure.code == $mismatch end)' "$result" >/dev/null
}
verify_mongodb_outage() {
  local result id owner status=0
  # The seed uses update/upsert. Interrupt only the following read and cleanup on runner connections.
  [[ "$(mongo_eval 'db.adminCommand({configureFailPoint:"failCommand", mode:"alwaysOn",
    data:{appName:"framework-mongo-fixture", failCommands:["find","delete"], closeConnection:true}}).ok')" == 1 ]]
  cli run --suite mongodb/suite_manifest.yaml --profile external_mongodb > mongo-outage.log || status=$?
  [[ "$(mongo_eval 'db.adminCommand({configureFailPoint:"failCommand", mode:"off"}).ok')" == 1 ]]
  cat mongo-outage.log
  [[ $status == 1 ]]
  # The pinned server logs 20431 when it actively closes a connection for a command.
  mongo_eval 'const lines = db.adminCommand({getLog:"global"}).log;
    print(JSON.stringify(lines.map(line => JSON.parse(line)).filter(e => e.id === 20431).map(e => e.attr.command)))' |
    jq -e 'index("find") != null and index("delete") != null' >/dev/null
  result=$(sed -n 's/^result_json: //p' mongo-outage.log)
  cli validate-evidence --result "$result"
  jq -e '
    .status == "failed" and .failure.code == "MONGO_OPERATION_FAILED" and
    .failure.classification == "PROVIDER_RUNTIME_ERROR" and (.failure.owner_action | contains("read_order")) and
    any(.steps[]; .id == "seed_order" and .status == "passed" and .outputs.acknowledged == true) and
    any(.steps[]; .id == "read_order" and .status == "failed" and .failure_code == "MONGO_OPERATION_FAILED") and
    any(.steps[]; .id == "delete_order" and .phase == "cleanup" and .status == "failed" and .failure_code == "MONGO_OPERATION_FAILED") and
    (.cleanup_failures | length == 1) and .cleanup_failures[0].step_id == "delete_order" and
    .cleanup_failures[0].failure_code == "MONGO_OPERATION_FAILED" and .failure.cleanup_failures == .cleanup_failures' "$result" >/dev/null

  id=$(jq -er '.steps[] | select(.id == "seed_order") | .outputs.document_id | select(test("^swarm-[a-f0-9]{64}$"))' "$result")
  mongo_eval 'print(JSON.stringify(db.swarm_fixture_documents.find().limit(3).toArray()))' > recovered-docs.json
  owner=$(jq -er --arg id "$id" --slurpfile expected mongodb/expected_results/confirmed_order.json '
    select(length == 2 and any(.[]; . == {"_id":"control","payload":"keep"})) |
    .[] | select(._id == $id and .payload == $expected[0]) | .owner | select(test("^[a-f0-9]{64}$"))' recovered-docs.json)
  [[ "$(mongo_eval 'db.swarm_fixture_documents.deleteOne({_id:process.env.DOC_ID,owner:process.env.DOC_OWNER}).deletedCount' \
    -e "DOC_ID=$id" -e "DOC_OWNER=$owner")" == 1 ]]
  mongo_clean
  echo "MongoDB closed find and delete connections after the acknowledged seed; recovered payload verified, owned document removed, control preserved."
}
verify_mongodb() {
  cp -R "$root/samples/20-provider-capability-p0/data/mongodb" .
  server=$(docker run -d --memory 768m --cpus 1 -p 127.0.0.1::27017 \
    mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a --wiredTigerCacheSizeGB 0.25 --setParameter enableTestCommands=1)
  local addr
  addr=$(docker port "$server" 27017/tcp)
  printf -v MONGODB_URI '%s://%s/swarm_fixture?appName=framework-mongo-fixture' mongodb "$addr"
  export MONGODB_URI
  mongo_eval() { docker exec "${@:2}" "$server" mongosh --quiet swarm_fixture --eval "$1"; }
  ready mongo_eval 'db.runCommand({ping:1}).ok'
  mongo_eval 'db.swarm_fixture_documents.insertOne({_id:"control",payload:"keep"})' >/dev/null
  mongo_clean() { [[ "$(mongo_eval 'db.swarm_fixture_documents.countDocuments({}) === 1 && db.swarm_fixture_documents.findOne({_id:"control"}).payload === "keep"')" == true ]]; }
  verify_mongodb_outage
  # Confirm the same runner can read and clean up a fresh write after recovery.
  run_case mongodb passed mongo_read 0
  mongo_clean
  printf '%s\n' '{"status":"different"}' > mongodb/expected_results/confirmed_order.json
  run_case mongodb failed mongo_seed 1
  mongo_clean
}
verify_redis_outage() {
  local port=$1 result key owner status=0
  python3 "$root/scripts/ci/redis-outage-fixture.py" "$port" "$jar" > redis-outage.log || status=$?
  cat redis-outage.log
  [[ $status == 0 ]]
  result=$(sed -n 's/^result_json: //p' redis-outage.log)
  cli validate-evidence --result "$result"
  jq -e '
    .status == "failed" and .failure.code == "PROVIDER_UNAVAILABLE" and
    .failure.classification == "PROVIDER_RUNTIME_ERROR" and (.failure.owner_action | contains("read_string")) and
    any(.step_results[]; .operation == "redis_set" and .status == "passed" and .outputs.created == true) and
    any(.step_results[]; .operation == "redis_get" and .status == "failed" and .failure_code == "PROVIDER_UNAVAILABLE") and
    any(.step_results[]; .phase == "cleanup" and .operation == "redis_delete" and .status == "failed") and
    (.cleanup_failures | length == 1) and .cleanup_failures[0].step_id == "remove_string" and
    .cleanup_failures[0].failure_code == "PROVIDER_UNAVAILABLE" and .failure.cleanup_failures == .cleanup_failures' "$result" >/dev/null
  cli report --result "$result" --format json > redis-outage-report.json
  jq -e --slurpfile run "$result" '
    [.step_timings[] | select(.operation == "redis_get" or .operation == "redis_delete")] as $timings |
    ($timings | length) == 2 and all($timings[]; . as $step |
      .duration_ms >= 1000 and any($run[0].steps[]; .id == $step.step_id and .duration_ms == $step.duration_ms))' \
    redis-outage-report.json >/dev/null
  jq -c '[.step_timings[] | {step_id, duration_ms}]' redis-outage-report.json
  key=$(jq -er '.step_results[] | select(.operation == "redis_set") | .outputs.physical_key |
    select(test("^sdr:redis:[{][a-f0-9]{64}[}]:value$"))' "$result")
  owner="${key%:value}:owner"
  [[ "$(redis_cmd get "$key")" == order-ready && "$(redis_cmd get "$owner")" =~ ^[0-9a-f]{64}:order-ready$ ]]
  [[ "$(redis_cmd get control)" == keep && "$(redis_cmd dbsize)" == 3 ]]
  [[ "$(redis_cmd del "$key" "$owner")" == 2 ]]
  redis_clean
  echo "Recovered Redis held both fixture keys and the control; removed the two fixture keys and verified the control."
}
verify_redis() {
  cp -R "$root/samples/20-provider-capability-p0/data/redis" .
  local addr port
  if [[ $1 == redis-local ]]; then
    port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
    addr="127.0.0.1:$port"
    redis-server --bind 127.0.0.1 --port "$port" --unixsocket "$scratch/redis.sock" \
      --save "" --appendonly no --maxmemory 32mb > redis-server.log 2>&1 &
    redis_pid=$!
    redis_cmd() { redis-cli --raw -s "$scratch/redis.sock" "$@"; }
  else
    server=$(docker run -d --memory 160m --cpus 1 -p 127.0.0.1::6379 \
      redis:7.4.7-alpine@sha256:02f2cc4882f8bf87c79a220ac958f58c700bdec0dfb9b9ea61b62fb0e8f1bfcf)
    addr=$(docker port "$server" 6379/tcp)
    redis_cmd() { docker exec "$server" redis-cli --raw "$@"; }
  fi
  printf -v FRAMEWORK_REDIS_URI '%s://%s/0' redis "$addr"
  export FRAMEWORK_REDIS_URI
  ready redis_cmd ping
  redis_cmd set control keep >/dev/null
  redis_clean() { [[ "$(redis_cmd dbsize)" == 1 && "$(redis_cmd get control)" == keep ]]; }
  run_case redis passed redis_ttl 0
  redis_clean
  verify_redis_outage "${addr##*:}"
  # Change only the independent expectation in the scratch copy.
  sed 's/expected_value: order-ready/expected_value: different/' redis/test_cases/owned_string.yaml > wrong.yaml
  mv wrong.yaml redis/test_cases/owned_string.yaml
  run_case redis failed redis_set 1
  redis_clean
}
verify_s3() {
  cp -R "$root/samples/20-provider-capability-p0/storage/s3" .
  local client=chainguard/minio-client@sha256:e6fa3a97652e91c8608d9a870d9b080bc38bb28b91f7f7a0f1250455ec122b8d
  MINIO_ROOT_USER=fixture
  MINIO_ROOT_PASSWORD=$(openssl rand -hex 24)
  export MINIO_ROOT_USER MINIO_ROOT_PASSWORD
  server=$(docker run -d --memory 512m --cpus 1 -p 127.0.0.1::9000 --tmpfs /data:rw,mode=1777 \
    -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD chainguard/minio@sha256:b288eaa709ba2f799a39e9a8ceda71a08fabf90109b12c90badba9afff29485c server /data)
  REGRESS_S3_ENDPOINT="http://$(docker port "$server" 9000/tcp)"
  export REGRESS_S3_ENDPOINT REGRESS_S3_BUCKET=fixture-bucket REGRESS_S3_ACCESS_KEY="$MINIO_ROOT_USER" REGRESS_S3_SECRET_KEY="$MINIO_ROOT_PASSWORD"
  export MC_HOST_fixture="http://$MINIO_ROOT_USER:$MINIO_ROOT_PASSWORD@127.0.0.1:9000"
  mc() { docker run -i --rm --memory 160m --network "container:$server" -e MC_HOST_fixture "$client" "$@"; }
  ready curl -fsS --max-time 2 "$REGRESS_S3_ENDPOINT/minio/health/ready"
  mc mb fixture/fixture-bucket
  mc version enable fixture/fixture-bucket
  # Bound the buffer for a stream whose total size mc cannot infer from stdin.
  printf 'keep' | mc pipe --part-size 8MiB fixture/fixture-bucket/control
  s3_clean() {
    mc ls --versions --recursive --json fixture/fixture-bucket > objects.jsonl
    jq -se 'length == 1 and .[0].key == "control"' objects.jsonl >/dev/null
    [[ "$(mc cat fixture/fixture-bucket/control)" == keep ]]
  }
  run_case s3 passed s3_verify_object 0
  s3_clean
  sed "s@expected_sha256: step://upload/sha256@expected_sha256: 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'@" s3/test_cases/object_roundtrip.yaml > wrong.yaml
  mv wrong.yaml s3/test_cases/object_roundtrip.yaml
  run_case s3 failed s3_put_object 1
  s3_clean

  # A separate application uploads without framework ownership metadata.
  python3 "$root/scripts/ci/s3-export-fixture.py" app.port "$server" "$client" &
  app_pid=$!
  local result
  ready test -s app.port
  export REST_BASE_URL="http://127.0.0.1:$(cat app.port)"
  cli run --suite s3/suite_manifest_app_export.yaml --profile external_export | tee export.log
  wait "$app_pid"
  app_pid=""
  result=$(sed -n 's/^result_json: //p' export.log)
  cli validate-evidence --result "$result"
  jq -e '.status == "passed" and
    any(.step_results[]; .operation == "s3_verify_object" and .outputs.content_length == 5)' "$result" >/dev/null
  mc ls --versions --recursive --json fixture/fixture-bucket > objects.jsonl
  jq -se 'length == 2 and any(.[]; .key | endswith("/exports/greeting.txt"))' objects.jsonl >/dev/null
}
providers=("$@")
if [[ ${#providers[@]} == 0 ]]; then providers=(mongodb redis s3); fi
for provider in "${providers[@]}"; do
  case "$provider" in
    mongodb) verify_mongodb ;;
    redis|redis-local) verify_redis "$provider" ;;
    s3) verify_s3 ;;
    *) exit 2 ;;
  esac
  echo "$provider native sample passed with cleanup and independent isolation checks"
  stop_server
done
