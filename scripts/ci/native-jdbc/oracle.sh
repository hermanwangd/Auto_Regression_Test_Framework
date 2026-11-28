#!/usr/bin/env bash
# Sourced by the native JDBC runner, which owns container cleanup.
# The pinned image index includes both Linux amd64 and arm64 builds.
image=gvenzl/oracle-free:23.26.4-slim@sha256:48a3d9db7fa7c2a2fdbdf5fff7502c93e807349a2d625718fe61668cc3daa844
driver_file=ojdbc11-23.26.4.0.0.jar
driver_url="https://repo.maven.apache.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.4.0.0/$driver_file"
driver_sha256=df977e9291499cd4ceee7d577462c285987dd2ad0216637c0865eb98956a9e6e

# SQL*Plus uses OS authentication inside the fixture. Its output is an
# independent check on rows written through the packaged JDBC provider.
oracle_sql() {
  {
    cat <<'SQL'
whenever oserror exit failure rollback
whenever sqlerror exit failure rollback
set heading off feedback off pagesize 0 linesize 250 trimout on tab off
alter session set container=FREEPDB1;
SQL
    cat
    printf '\nexit success\n'
  } | docker exec -i "$server" timeout 20s sqlplus -L -s / as sysdba
}

start_database() {
  local admin_password app_password address deadline state
  admin_password=$(openssl rand -hex 12)
  app_password=$(openssl rand -hex 12)
  # Pass credentials by environment name. A supplied admin password also
  # prevents the image from logging an auto-generated administrator password.
  server=$(ORACLE_PASSWORD="Db7${admin_password}" APP_USER_PASSWORD="Db7${app_password}" \
    docker run -d --memory=3g --memory-swap=3g --cpus=2 --shm-size=512m \
    -p 127.0.0.1::1521 \
    -e ORACLE_PASSWORD -e APP_USER=REGRESS_FIXTURE -e APP_USER_PASSWORD "$image")

  deadline=$((SECONDS + 300))
  while true; do
    state=$(docker inspect --format '{{.State.Status}}' "$server")
    if [[ "$state" == running ]] &&
        [[ "$(oracle_sql <<< "select username from dba_users where username = 'REGRESS_FIXTURE';" 2>/dev/null)" == REGRESS_FIXTURE ]]; then
      break
    fi
    if [[ "$state" != running || $SECONDS -ge $deadline ]]; then
      printf 'Oracle FREEPDB1 did not become ready (container state %s).\n' "$state" >&2
      return 1
    fi
    sleep 2
  done

  oracle_sql <<'SQL'
create table REGRESS_FIXTURE.ORDERS (ORDER_ID varchar2(64) primary key, STATUS varchar2(32));
insert into REGRESS_FIXTURE.ORDERS (ORDER_ID, STATUS) values ('other-run', 'KEEP');
commit;
SQL
  address=$(docker port "$server" 1521/tcp)
  printf -v JDBC_CONNECTION 'jdbc:%s:thin:REGRESS_FIXTURE/Db7%s@//%s/FREEPDB1' oracle "$app_password" "$address"
  export JDBC_CONNECTION
}

db_rows() {
  oracle_sql <<< "select ORDER_ID || ' ' || STATUS from REGRESS_FIXTURE.ORDERS order by ORDER_ID;"
}

db_version() {
  oracle_sql <<< "select 'Oracle Database ' || VERSION_FULL from v\$instance;"
}
