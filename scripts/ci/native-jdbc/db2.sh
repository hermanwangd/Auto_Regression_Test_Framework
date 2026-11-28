#!/usr/bin/env bash
# Sourced by the native JDBC runner, which removes the container and its anonymous volumes.
image=icr.io/db2_community/db2:12.1.6.0@sha256:c59268135d4d120f4a39f0a577f48f0acd199d8dbe89c95c45b290ac41f0fd8b
driver_file=jcc-12.1.5.0.jar
driver_url="https://repo.maven.apache.org/maven2/com/ibm/db2/jcc/12.1.5.0/$driver_file"
driver_sha256=04150111a29370247d162c3f05b8e938bf1314f19a63ad9a5302d88dd478e8e8

# Use the instance owner's CLP for provisioning and independent readback.
# Keep one parent shell for CONNECT and the SQL so they share a CLP session.
db2_admin() {
  docker exec "$server" su --login --command '
    db2 connect to FIXTURE >/dev/null || exit $?
    for statement do
      db2 -x "$statement" || exit $?
    done
  ' -- db2inst1 db2-fixture "$@"
}

start_database() {
  local admin_password app_password address deadline state
  if [[ ${DB2_LICENSE_ACCEPTED:-} != true ]]; then
    echo 'Db2 requires explicit operator acceptance of the IBM image license. Set DB2_LICENSE_ACCEPTED=true after accepting it.' >&2
    return 1
  fi
  admin_password=$(openssl rand -hex 24)
  app_password=$(openssl rand -hex 24)
  # IBM documents privileged mode. Run on a disposable VM with no host mounts.
  server=$(DB2INST1_PASSWORD="$admin_password" docker run -d --platform=linux/amd64 \
    --privileged --memory=3g --memory-swap=3g --cpus=2 --shm-size=512m \
    --health-interval=5s --health-timeout=15s -p 127.0.0.1::50000 \
    -e LICENSE=accept -e DB2INSTANCE=db2inst1 -e DB2INST1_PASSWORD -e DBNAME=FIXTURE "$image")

  deadline=$((SECONDS + 600))
  while true; do
    state=$(docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' "$server")
    if [[ "$state" == 'running healthy' ]]; then break; fi
    if [[ "$state" != running\ * || $SECONDS -ge $deadline ]]; then
      printf 'Db2 did not become ready (container state %s).\n' "$state" >&2
      return 1
    fi
    sleep 3
  done

  printf 'jdbcapp:%s\n' "$app_password" | docker exec -i "$server" sh -ec 'useradd --create-home jdbcapp && chpasswd'
  db2_admin \
    'CREATE TABLE DB2INST1.ORDERS (ORDER_ID VARCHAR(64) NOT NULL PRIMARY KEY, STATUS VARCHAR(32))' \
    "INSERT INTO DB2INST1.ORDERS VALUES ('other-run', 'KEEP')" \
    'GRANT CONNECT ON DATABASE TO USER JDBCAPP' \
    'GRANT SELECT, INSERT, DELETE ON TABLE DB2INST1.ORDERS TO USER JDBCAPP'
  address=$(docker port "$server" 50000/tcp)
  printf -v JDBC_CONNECTION 'jdbc:%s://%s/FIXTURE:user=jdbcapp;password=%s;currentSchema=DB2INST1;' db2 "$address" "$app_password"
  export JDBC_CONNECTION
}

db_rows() {
  db2_admin "SELECT ORDER_ID || ' ' || STATUS FROM DB2INST1.ORDERS ORDER BY ORDER_ID" | awk 'NF { $1=$1; print }'
}

db_version() {
  db2_admin 'SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO' | awk 'NF { $1=$1; print }'
}
