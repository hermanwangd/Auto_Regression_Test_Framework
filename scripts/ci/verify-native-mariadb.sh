#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
version=$("$root/scripts/release/verify-release-version.sh")
jar="$root/target/spec-driven-auto-regression-${version}.jar"
test -s "$jar"
scratch=$(mktemp -d)
server=""
finish() {
  if [[ -n "$server" ]]; then docker rm -fv "$server" >/dev/null; fi
  rm -rf -- "$scratch"
}
trap finish EXIT
cd "$scratch"
cp -R "$root/samples/20-provider-capability-p0/data/jdbc" suite
driver="$scratch/mariadb-java-client-3.5.3.jar"
curl --fail --silent --show-error --location --retry 3 --max-time 60 \
  https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.3/mariadb-java-client-3.5.3.jar -o "$driver"
printf '%s  %s\n' 85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706 "$driver" | shasum -a 256 -c -

# The credentials and database belong only to this disposable container.
export MARIADB_USER=fixture MARIADB_DATABASE=fixture
MARIADB_PASSWORD=$(openssl rand -hex 24)
export MARIADB_PASSWORD
server=$(docker run -d --memory=512m --cpus=1 -p 127.0.0.1::3306 \
  -e MARIADB_RANDOM_ROOT_PASSWORD=1 -e MARIADB_USER -e MARIADB_PASSWORD -e MARIADB_DATABASE \
  mariadb:11.8.8@sha256:24e76fcec8c003a0362d0dd53f4806e7e79458d7fdeaf47437760e19496f5a9c)
for attempt in {1..60}; do
  if docker exec "$server" healthcheck.sh --connect --innodb_initialized >/dev/null 2>&1; then break; fi
  sleep 1
done
sql() {
  docker exec -i "$server" sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb --user="$MARIADB_USER" --batch --skip-column-names "$MARIADB_DATABASE"'
}
sql <<< 'SELECT VERSION()'
address=$(docker port "$server" 3306/tcp)
printf -v JDBC_CONNECTION 'jdbc:%s://%s/%s?user=%s&password=%s' mariadb "$address" "$MARIADB_DATABASE" "$MARIADB_USER" "$MARIADB_PASSWORD"
export JDBC_CONNECTION
# Approvals of the reviewed fixture files, independent of the checkout under test.
seed=281788f4939f4845e302f3500db53c813b5da97682397d7fb0755bb61edfc37c
query=8732f08dcf92d1b1d1ea3908622ab1fa3243633ca549c1ae1dbe640b11f5a5c9
cleanup=77f943082ad8e1044bcd20d270749d0ac4d5a67cac02a00ce4fd30326dec6419
identity=b8c1bc2e41c8ef576eaa6be3977ea34b4906e15ade51e0a96c00de684a5131b1
binding=mariadb:JDBC_CONNECTION
export REGRESS_JDBC_APPROVED_SQL_SHA256="$binding:db_query:$identity,$binding:db_seed:$seed,$binding:db_query:$query,$binding:db_record_exists:$query,$binding:db_cleanup:$cleanup"

cli() { java -Xmx384m -jar "$jar" "$@"; }
cli doctor drivers --driver-path "$driver" > doctor.log
grep -Fx 'mariadb_driver_loadable: true' doctor.log
run_case() {
  local case_name=$1 expected=$2 expected_exit=$3 status=0 result
  cli run --suite suite/suite_manifest_mariadb.yaml \
    --profile external_mariadb --driver-path "$driver" > "$case_name.log" || status=$?
  cat "$case_name.log"
  [[ "$status" == "$expected_exit" ]]
  result=$(sed -n 's/^result_json: //p' "$case_name.log")
  cli validate-evidence --result "$result"
  jq -er '.step_results[] | select(.id == "new_order_id") | .outputs.sample_rows[0].ORDER_ID' "$result" > "$case_name.id"
  jq -e --arg expected "$expected" '
    .status == $expected and .cleanup_failures == [] and
    ([.step_results[] | select(.status == "passed") | .operation] ==
      ["db_query", "db_seed", "db_query", "db_record_exists", "db_cleanup"]) and
    (any(.step_results[]; .operation == "db_record_exists" and .outputs.matched == true)) and
    ($expected == "passed" or .failure.code == "ASSERTION_FAILED")' "$result" > /dev/null
}

# Hold a different run's row in the schema across both real CLI executions.
sql <<'SQL'
CREATE TABLE ORDERS (ORDER_ID VARCHAR(64) PRIMARY KEY, STATUS VARCHAR(32));
INSERT INTO ORDERS VALUES ('other-run', 'KEEP');
SQL
run_case native-run-a passed 0
remaining='SELECT ORDER_ID, STATUS FROM ORDERS ORDER BY ORDER_ID'
[[ "$(sql <<< "$remaining")" == $'other-run\tKEEP' ]]

# Change only the test expectation. All SQL and operation approvals stay identical.
sed 's/^      expected: 1$/      expected: 2/' suite/test_cases/mariadb_order.yaml > wrong-count.yaml
mv wrong-count.yaml suite/test_cases/mariadb_order.yaml
run_case native-run-b failed 1
[[ -s native-run-a.id && -s native-run-b.id ]]
if cmp -s native-run-a.id native-run-b.id; then exit 1; fi
[[ "$(sql <<< "$remaining")" == $'other-run\tKEEP' ]]
echo 'MariaDB native operations, failed assertion cleanup and row isolation passed.'
