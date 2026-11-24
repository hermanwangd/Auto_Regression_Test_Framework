# JDBC Driver Setup

Oracle and DB2 JDBC drivers are not bundled in public release assets. Supply
approved internal driver jars at runtime.

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
