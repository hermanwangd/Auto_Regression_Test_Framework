#!/usr/bin/env bash
# Exercise the packaged public samples against disposable services, one at a time.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
version=$("$root/scripts/release/verify-release-version.sh")
jar="$root/target/spec-driven-auto-regression-${version}.jar"
test -s "$jar"
scratch=$(mktemp -d)
server=""
stop_server() {
  if [[ -n "$server" ]]; then docker rm -fv "$server" >/dev/null; server=""; fi
}
finish() { stop_server; rm -rf -- "$scratch"; }
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
verify_mongodb() {
  cp -R "$root/samples/20-provider-capability-p0/data/mongodb" .
  server=$(docker run -d --memory 768m --cpus 1 -p 127.0.0.1::27017 \
    mongo:7.0.16@sha256:c630c59342c1493d50345136df2af14a76b9e827dd5316bfabee07a0880a5f3a --wiredTigerCacheSizeGB 0.25)
  local addr
  addr=$(docker port "$server" 27017/tcp)
  printf -v MONGODB_URI '%s://%s/swarm_fixture' mongodb "$addr"
  export MONGODB_URI
  mongo_eval() { docker exec "$server" mongosh --quiet swarm_fixture --eval "$1"; }
  ready mongo_eval 'db.runCommand({ping:1}).ok'
  mongo_eval 'db.swarm_fixture_documents.insertOne({_id:"control",payload:"keep"})' >/dev/null
  mongo_clean() { [[ "$(mongo_eval 'db.swarm_fixture_documents.countDocuments({}) === 1 && db.swarm_fixture_documents.findOne({_id:"control"}).payload === "keep"')" == true ]]; }
  run_case mongodb passed mongo_read 0
  mongo_clean
  printf '%s\n' '{"status":"different"}' > mongodb/expected_results/confirmed_order.json
  run_case mongodb failed mongo_seed 1
  mongo_clean
}
verify_redis() {
  cp -R "$root/samples/20-provider-capability-p0/data/redis" .
  server=$(docker run -d --memory 160m --cpus 1 -p 127.0.0.1::6379 \
    redis:7.4.7-alpine@sha256:02f2cc4882f8bf87c79a220ac958f58c700bdec0dfb9b9ea61b62fb0e8f1bfcf)
  local addr
  addr=$(docker port "$server" 6379/tcp)
  printf -v FRAMEWORK_REDIS_URI '%s://%s/0' redis "$addr"
  export FRAMEWORK_REDIS_URI
  redis_cmd() { docker exec "$server" redis-cli --raw "$@"; }
  ready redis_cmd ping
  redis_cmd set control keep >/dev/null
  redis_clean() { [[ "$(redis_cmd dbsize)" == 1 && "$(redis_cmd get control)" == keep ]]; }
  run_case redis passed redis_ttl 0
  redis_clean
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
  printf 'keep' | mc pipe fixture/fixture-bucket/control
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
  local app_pid=$! result
  ready test -s app.port
  export REST_BASE_URL="http://127.0.0.1:$(cat app.port)"
  cli run --suite s3/suite_manifest_app_export.yaml --profile external_export > export.log
  wait "$app_pid"
  cat export.log
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
    redis) verify_redis ;;
    s3) verify_s3 ;;
    *) exit 2 ;;
  esac
  echo "$provider native sample passed with cleanup and independent isolation checks"
  stop_server
done
