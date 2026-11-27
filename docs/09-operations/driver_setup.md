# JDBC Driver Setup

Supply an approved Oracle, Db2 or MariaDB JDBC driver at runtime. The runner discovers vendor jars outside the public release assets.

## Discovery Order

```text
--driver-path > --driver-dir > REGRESS_DRIVER_PATH > usage-kit/drivers/
```

`--driver-path` may be repeated. `--driver-dir` scans only direct child `.jar`
files. `REGRESS_DRIVER_PATH` uses the platform path separator.

## External SQL approval

Before running an external suite, supply `REGRESS_JDBC_APPROVED_SQL_SHA256`
from the runner's protected environment. Review the SQL independently of the
checkout being tested. For each approved file, record the SHA-256 of its exact
UTF-8 bytes, including line endings, as 64 lowercase hexadecimal digits.
Join the entries with commas. Each entry has the form
`<dialect>:<connection environment variable>:<operation>:<sha256>`.

The following paths are relative to `samples/20-provider-capability-p0/data/jdbc/`.
For this sample use `oracle` or `db2` and the connection name `JDBC_CONNECTION`
for all three entries.

| Operation | SQL file |
| --- | --- |
| `db_seed` | `fixtures/seed_order.sql` |
| `db_query` | `queries/find_order_by_id.sql` |
| `db_cleanup` | `fixtures/cleanup_order.sql` |

The runner verifies each file before it opens the external connection for that
operation. Supply all three approvals before starting the sample.

## Oracle Example

```bash
: "${REGRESS_JDBC_APPROVED_SQL_SHA256:?Supply independently reviewed SQL approvals}"
JDBC_CONNECTION='<oracle-jdbc-url>' java -jar ../spec-driven-auto-regression-{{VERSION}}.jar run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile external_oracle \
  --driver-path ./drivers/oracle/ojdbc11.jar
```

## DB2 Example

```bash
: "${REGRESS_JDBC_APPROVED_SQL_SHA256:?Supply independently reviewed SQL approvals}"
JDBC_CONNECTION='<db2-jdbc-url>' java -jar ../spec-driven-auto-regression-{{VERSION}}.jar run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile external_db2 \
  --driver-path ./drivers/db2/jcc.jar
```

## MariaDB example

Use a dedicated test schema. Supply `JDBC_CONNECTION` from your runner secret store,
and a trusted MariaDB Connector/J jar. The first query selects a fresh UUID
for the run. Seed, query, existence check and cleanup all bind that same key.
The sample creates the ORDERS table if needed, or can use a compatible existing
table. Seed, select and cleanup use the same three files listed above.
Authorize the query file twice, for `db_query` and `db_record_exists`, in addition
to the seed and cleanup approvals. Also approve `queries/new_mariadb_order_id.sql`
for `db_query`. All five entries use dialect `mariadb` and
connection name `JDBC_CONNECTION`.

```bash
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest_mariadb.yaml \
  --profile external_mariadb --driver-path "$MARIADB_DRIVER_JAR"
```

The separate `owned-mariadb-readonly.v1` path authorizes its fixed read through
the reviewed credential carrier. Use the external profile for general JDBC operations.

## Diagnostics

```bash
java -jar ../spec-driven-auto-regression-{{VERSION}}.jar doctor drivers --driver-dir ./drivers
```

Driver diagnostics check jar discovery and class loadability only. They do not
connect to a database and must not print raw connection strings.

## SQL file references

Keep SQL files inside the suite or RP package that references them. An alias
may point to a regular file inside that root. The framework resolves the alias,
then opens each physical path component relative to its parent directory handle.
Linux and macOS use the bundled JNA library and `openat`. Linux pins the file
with `O_PATH` and uses `/proc/self/fd` to verify and read that same regular
file. macOS uses `/dev/fd`. An unavailable descriptor filesystem or unsupported
native ABI causes the operation to fail closed.
