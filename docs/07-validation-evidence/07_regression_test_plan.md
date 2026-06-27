# 07. Framework Verification Test Plan

This plan defines how to verify the regression test framework itself. It covers unit/component verification and sample Product/RP/RU integration verification.

It does not define downstream product-feature release AC and does not replace a real RP release regression plan. Real Product/RP regression evidence is produced later by `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` against owner-provided RP artifacts.

## 7.1 Verification Objective

Framework verification shall prove that the framework can:

- Run fast unit/component tests for parsers, validators, resolvers, CLI behavior, evidence writers, and reporting logic.
- Run an end-to-end framework integration flow against a sample Product Repo fixture.
- Use sample Product/RP/RU artifacts to validate RP discovery, RP/RU mapping, checked-in DSL test discovery, adapter execution, assertion, batch evidence, run evidence, and coverage report generation.
- Keep sample fixture evidence clearly separate from downstream Product/RP release evidence.

## 7.2 Verification Levels

| Level | Subject Under Test | Command | Fixture Source | Evidence |
|---|---|---|---|---|
| Unit/component framework verification | Framework modules and CLI behavior | `./mvnw test` | Temp directories and small local test data | Maven Surefire reports. |
| Sample Product/RP/RU integration verification | Framework end-to-end behavior | `./mvnw verify` | `src/test/resources/framework-verification/sample-product-repo/` | Maven Failsafe reports and generated temporary sample evidence. |
| Packaged CLI smoke verification | Spring Boot jar CLI entrypoint | `java -jar target/spec-driven-auto-regression-0.1.0-SNAPSHOT.jar check-readiness --root .` | Current repository Product Repo structure | CLI output and exit code. |

`./mvnw test` must stay fast and deterministic. `./mvnw verify` may execute a local shell adapter through the sample fixture, but must not require SIT/UAT deployment.

## 7.3 Unit and Component Verification

Unit/component tests live in `src/test/java` and use the `*Test` suffix so Maven Surefire runs them during `./mvnw test`.

They shall cover:

- Product Repo bootstrap and readiness checks.
- RP skeleton and artifact completeness checks.
- RP/RU mapping parser, dependency graph, and missing-field handling.
- DSL schema validation, lifecycle status checks, and unsupported capability blocking.
- Expected-result approval gating.
- Environment resolver behavior for `local_fixture`, `ci_ephemeral`, `sit_deployed`, and `evidence_only`.
- Binding, provider, fixture, execution, assertion, evidence, and coverage-report behavior.
- Packaged CLI delegation behavior through `RegressionApplication.runCli(...)`.

## 7.4 Sample Product/RP/RU Integration Verification

The sample integration fixture is a miniature Product Repo used only to verify the framework. It must include:

- A sample Product Repo root marker that says fixture evidence is not downstream Product/RP release evidence.
- One sample RP, currently `RP-FWK-SAMPLE`.
- One sample RU mapping, currently `RU-framework-sample-adapter`.
- One checked-in approved DSL test case.
- One approved expected-result artifact.
- One provider contract using a bounded local shell adapter.
- Sample input data and expected output data.

The integration flow shall:

```text
copy sample Product Repo fixture to temp directory
-> check-rp --strict-schema
-> run --rp-id RP-FWK-SAMPLE --env ci_ephemeral
-> report --batch-id BATCH-001
-> assert batch evidence, run evidence, and coverage evidence
```

The integration suite shall cover at least these sample Product/RP/RU scenarios:

- Happy path: `check-rp`, `run`, and `report` complete with 100% sample coverage.
- Artifact readiness failure: package schema and RP/RU mapping gaps block before execution evidence is written.
- Provider contract failure: missing adapter command blocks before adapter execution and writes blocked run evidence.
- Test inventory boundary: missing approved DSL test case blocks before adapter execution.
- Execution/assertion failure: adapter execution starts, assertion fails, run evidence is failed, and report is not review-ready.

Generated sample evidence must stay in the test temp directory. It shall not be committed and shall not count as real Product/RP release evidence.

## 7.5 Required Framework Verification Cases

| Test ID | Scenario | Command Level | Priority | Automation |
|---|---|---|---|---|
| FWK-001 | Unit/component suite validates parsers, readiness checks, CLI behavior, resolvers, execution services, evidence writers, and reporters | `./mvnw test` | P1 | Auto |
| FWK-002 | Sample Product/RP/RU fixture runs happy path through `check-rp`, `run`, and `report` without SIT/UAT deployment | `./mvnw verify` | P1 | Auto |
| FWK-003 | Sample fixture evidence is marked as framework verification evidence and is not counted as downstream RP release evidence | `./mvnw verify` | P1 | Auto |
| FWK-004 | Artifact readiness gaps, provider contract gaps, missing approved DSL tests, or failed assertions block or fail with actionable evidence | `./mvnw verify` | P1 | Auto |
| FWK-005 | Packaged jar delegates CLI arguments to the framework command layer and returns meaningful exit codes | `./mvnw test` plus packaged CLI smoke | P1 | Auto / CLI |

## 7.6 Downstream RP Regression Boundary

Downstream Product/RP regression is a framework capability, but it is not the primary subject of this framework verification test plan.

When owner-provided Product/RP artifacts exist, release package regression shall be verified by a separate RP validation flow:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env <mode>
regress report --root <product-repo> --rp-id <rp-id> --batch-id <batch-id>
```

That flow produces real RP batch/run evidence under the Product Repo. It may use `local_fixture`, `ci_ephemeral`, `sit_deployed`, or `evidence_only` depending on the RP validation boundary. SIT/UAT runs require deployed RU versions and environment readiness evidence.

## 7.7 CI/CD Execution Policy

| Pipeline Stage | Required Command | Purpose |
|---|---|---|
| Pull request | `./mvnw test` | Fast framework unit/component verification. |
| Main or release branch | `./mvnw verify` | Framework integration verification with sample Product/RP/RU fixture. |
| Packaged CLI smoke | `java -jar target/spec-driven-auto-regression-0.1.0-SNAPSHOT.jar check-readiness --root .` | Verify packaged command delegation. |
| RP release pipeline | `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` | Downstream RP regression execution, outside this framework verification plan. |

All commands should be bounded and avoid memory-heavy execution. Local and CI runs should stay under the repository guidance of 8 GB RAM.

## 7.8 Out of Scope

- Formal downstream product-feature AC definition.
- Treating sample fixture evidence as real Product/RP release evidence.
- Framework-owned SIT/UAT deployment orchestration.
- Real pilot RP validation before owner-provided RP artifacts exist.
- Broad package-type plugin certification beyond the sample fixture.
