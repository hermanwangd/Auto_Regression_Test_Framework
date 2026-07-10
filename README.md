# Auto Regression Test Framework

Spec Driven Auto Regression is a Spring Boot 3.x / Java 17+ test framework for executing regression suites from stable contract artifacts: Suite Manifest, DSL Test Case, Provider Contract, Provider Instance, Env_Profile, Result JSON, and Evidence Index.

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

Provider support is tracked by provider type and `support_status`, not by server ownership. See [provider_support_matrix.md](docs/09-operations/provider_support_matrix.md).

## Usage Kit vs Source

The source repo contains framework code, tests, docs, contracts, schemas, scripts, and samples. Release users should start from the usage-kit release asset plus the matching jar.

## Evidence Boundary

Framework mock/local samples produce framework provider capability evidence only. Downstream SIT/preprod release evidence requires owner-provided product/RP artifacts, real Env_Profiles, and approved runtime dependencies.

## External Runtime Setup

External JDBC, Kafka, IBM MQ, and similar dependencies are supplied by the runner environment. JDBC vendor drivers are never bundled; use `--driver-path`, `--driver-dir`, `REGRESS_DRIVER_PATH`, or `usage-kit/drivers/`.

## Extending Providers

Start with a Provider Contract, Provider Instance shape, Env_Profile binding keys, runtime implementation, evidence contract, sample suite, and release verification coverage.

## Release Verification

Release readiness uses Maven verification, contract/schema drift gates, support matrix checks, security scans, usage-kit verification, and provider sample execution.
