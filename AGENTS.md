# Repository Guidelines

## Project Structure & Module Organization

This repository contains the Spring Boot 3.x / Java 17+ implementation of the Auto Regression Test Framework.

- `src/main/java/` contains CLI, contract validation, provider runtimes, evidence, and reporting code.
- `src/test/java/` contains Surefire tests for CLI behavior, provider capabilities, contracts, release scripts, and evidence validation.
- `docs/` contains scope, specs, architecture contracts, acceptance criteria, plans, release notes, and operations guides.
- `schemas/` contains packaged runtime schema copies aligned with `docs/02-architecture/contracts/`.
- `samples/` contains checked-in suite-mode examples grouped by getting started, contract baseline, provider capability, cross-provider, evidence, and compatibility.
- `scripts/` contains CI, release, security, and verification utilities.

Do not commit generated outputs, dependency caches, vendor JDBC drivers, local secrets, target artifacts, or machine-specific files.

## Build, Test, and Development Commands

Use Maven Wrapper with bounded memory:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw test
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw verify
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -DskipTests package
java -jar target/spec-driven-auto-regression-0.2.7.jar validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml
java -jar target/spec-driven-auto-regression-0.2.7.jar run --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_golden
java -jar target/spec-driven-auto-regression-0.2.7.jar report --result <generated_result_json>
```

Useful release checks:

```bash
scripts/ci/check-schema-drift.sh
scripts/ci/verify-contracts.sh
scripts/release/verify-supported-provider-samples.sh
```

## Coding Style & Naming Conventions

Use lowercase Java packages, `PascalCase` classes, `camelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. Keep provider-specific behavior inside provider runtime or capability service boundaries. Prefer structured YAML/JSON parsing over string matching for contract artifacts.

## Testing Guidelines

Place tests under `src/test/java/` near the behavior they cover. Use focused test names and include owner-actionable failure assertions for validation, runtime, evidence, and report behavior. For provider runtime changes, cover happy path, failure path, and boundary path.

## Commit & Pull Request Guidelines

Use concise imperative messages with a conventional prefix, such as `feat: add jdbc driver discovery diagnostics` or `fix: enforce schema contract drift gate`.

Pull requests should include summary, changed public interfaces, tests run, release impact, and any remaining risks. Include result/evidence paths when runtime behavior changes.

## Agent-Specific Instructions

When running commands in this repository, avoid memory-heavy jobs. Keep processes under 8 GB of RAM. Prefer bounded Maven commands, targeted tests, and incremental verification. Never commit raw secrets, vendor JDBC driver binaries, generated target outputs, or dependency caches.
