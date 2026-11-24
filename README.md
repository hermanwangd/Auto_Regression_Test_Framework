# Auto Regression Test Framework

Spec Driven Auto Regression is a Spring Boot 4.0.x / Java 17+ test framework for executing regression suites from stable contract artifacts: Suite Manifest, DSL Test Case, Provider Contract, Provider Instance, Env_Profile, Result JSON, and Evidence Index.

## What This Framework Solves

The framework lets product and release-package teams define executable regression suites without embedding endpoint URLs, credentials, topics, queues, or database connection strings in test cases. Runtime values are supplied by Env_Profile and provider bindings, while result/evidence output stays reviewable and masked.

## 5-Minute Quick Start

```bash
./mvnw test
./mvnw -DskipTests package
java -jar target/spec-driven-auto-regression-0.2.7.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar target/spec-driven-auto-regression-0.2.7.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_golden
java -jar target/spec-driven-auto-regression-0.2.7.jar report --result <generated_result_json>
```

## Public Interface

The supported runtime commands are `validate`, `run`, `report`, `validate-evidence`, and `doctor drivers`. See [test_framework_user_guide.md](docs/09-operations/test_framework_user_guide.md) and [framework_usage_interface.v0.2.md](docs/02-architecture/contracts/framework_usage_interface.v0.2.md).

## Provider Support

Legacy RP database fixture setup, verification, cleanup and the `db_row_matches` assertion are retired.
The runner also blocks v0.2 suites that select JDBC, including mixed suites and groups containing a JDBC child.
Archived v0.2 manifests remain available for static validation and dry-run.
Use the [v0.3 JDBC suite](samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml) for database cases.

Provider support is tracked by provider type and `support_status`, not by server ownership. See [provider_support_matrix.md](docs/09-operations/provider_support_matrix.md).

## Usage Kit vs Source

The source repo contains framework code, tests, docs, contracts, schemas, scripts, and samples. Release users should start from the usage-kit release asset plus the matching jar.

## Evidence Boundary

Framework mock/local samples produce framework provider capability evidence only. Downstream SIT/preprod release evidence requires owner-provided product/RP artifacts, real Env_Profiles, and approved runtime dependencies.

The optional v0.3 [SIT readiness gate](docs/09-operations/sit-readiness.md) checks prerequisites through replaceable read-only Java plugins before provider phases. Try the [local fixture and external JAR examples](samples/40-sit-readiness/README.md). The bundled fixture verifies framework behavior only; it does not observe SIT.

The [Swarm product-testing module](docs/09-operations/product-testing-module.md) adds a separate application-composed caller for reviewed local product tests. It uses the existing runner and canonical evidence, with independently pinned inputs, an explicit contract snapshot and optional shared-state policy. [Synthetic examples](samples/41-product-testing/README.md) include a local test requiring no Cognee metadata, a required-knowledge receipt, and a real assertion failure.


## External Runtime Setup

External JDBC, Kafka, IBM MQ, and similar dependencies are supplied by the runner environment. JDBC vendor drivers are never bundled; use `--driver-path`, `--driver-dir`, `REGRESS_DRIVER_PATH`, or `usage-kit/drivers/`.

For external Oracle and Db2 suites the runner must set `REGRESS_JDBC_APPROVED_SQL_SHA256` from SQL files reviewed independently of the suite under test. Each comma-separated entry has the form `dialect:connection_environment_variable:operation:sha256`. The operation is one of `db_query`, `db_record_exists`, `db_seed`, and `db_cleanup`. Use the lowercase SHA-256 of the exact UTF-8 file bytes, including line endings. For example, `oracle:JDBC_CONNECTION:db_query:<64 lowercase hex digits>` authorizes one query file for that runner connection. Missing, malformed or mismatched approvals block the external operation before driver loading. Keep approvals in the runner's protected environment. Local in-memory H2 suites use their existing profile.


Dependency bundling rules are documented in [provider_dependency_policy.md](docs/09-operations/provider_dependency_policy.md).

For ordinary v0.3 REST requests, the runner owner can set `REGRESS_REST_ALLOWED_ORIGINS` to a comma-separated list of exact HTTP(S) origins, for example `https://api.example.test,https://reports.example.test:8443`. Embedded Java callers can set `-Dregress.rest.allowedOrigins=...`, which takes precedence. When neither setting is present, the origin of the runner's existing `REST_BASE_URL` is authorized. An explicitly empty list grants no external origins. The environment profile selects the base URL and may include a base path. The request target must stay on that origin. Framework-owned mock endpoints and reviewed local reads retain their scoped authorization.

Use `request.body` for literal text or structured data and `request.body_ref` for a declared body file, such as `artifact://fixtures/order.json`. The runner confines file reads to the declared asset root. Supplying both body inputs fails the request.

## Extending Providers

Start with a Provider Contract, Provider Instance shape, Env_Profile binding keys, runtime implementation, evidence contract, sample suite, and release verification coverage.

## Release Verification

Release readiness uses Maven verification, contract/schema drift gates, support matrix checks, security scans, usage-kit verification, and provider sample execution.
