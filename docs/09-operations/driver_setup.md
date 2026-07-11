# JDBC Driver Setup

Oracle and DB2 JDBC drivers are not bundled in public release assets. Supply
approved internal driver jars at runtime.

## Discovery Order

```text
--driver-path > --driver-dir > REGRESS_DRIVER_PATH > usage-kit/drivers/
```

`--driver-path` may be repeated. `--driver-dir` scans only direct child `.jar`
files. `REGRESS_DRIVER_PATH` uses the platform path separator.

## Oracle Example

```bash
JDBC_CONNECTION='<oracle-jdbc-url>' java -jar ../spec-driven-auto-regression-{{VERSION}}.jar run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile external_oracle \
  --driver-path ./drivers/oracle/ojdbc11.jar
```

## DB2 Example

```bash
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
