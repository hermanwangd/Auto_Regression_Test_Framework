# 07. Framework Verification and RP Regression Test Plan

This plan defines two execution lines:

- Framework Verification proves this regression framework works correctly.
- RP Regression Execution uses the framework to validate a downstream Product Release Package.

It does not define downstream product-feature release AC. Formal product-feature acceptance remains in RP artifacts.

## 7.1 Execution Lines

| Execution Line | Subject Under Test | Command | Evidence |
|---|---|---|---|
| Framework unit/component verification | Framework modules and CLI behavior | `./mvnw test` | Surefire reports and local test fixtures. |
| Framework integration verification | Framework end-to-end behavior with a sample Product Repo fixture | `./mvnw verify` | Failsafe reports and sample fixture evidence. |
| RP regression execution | A selected downstream Product/RP | `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` | RP batch/run evidence under the Product Repo. |
| SIT/UAT RP regression | A deployed downstream Product/RP | Release package pipeline invokes `regress run --env sit_deployed` | RP release evidence plus deployment/readiness references. |

Framework verification evidence must not be counted as downstream RP release evidence. Sample Product Repo fixture evidence proves the framework, not a real Product/RP release.

## 7.2 Framework Verification Scope

Framework verification shall cover:

- Product Repo bootstrap and readiness behavior.
- RP skeleton and artifact completeness checks.
- RP/RU mapping parser, dependency graph, and missing-field handling.
- DSL schema validation, lifecycle status checks, and unsupported capability blocking.
- Expected-result approval gating.
- Environment resolver behavior for `local_fixture`, `ci_ephemeral`, `sit_deployed`, and `evidence_only`.
- Batch/run evidence writing without overwrites.
- Coverage calculation from batch-level evidence.

`./mvnw test` should remain fast and deterministic. `./mvnw verify` may use a sample Product Repo fixture and local/mock adapters, but must not require SIT/UAT deployment.

## 7.3 RP Regression Execution Scope

RP regression execution shall cover:

- RP selection by `--rp-id`.
- Approved checked-in DSL test discovery without regeneration.
- RP/RU mapping and provider contract resolution.
- Data selection, parameterization, bindings, fixtures, adapters, oracles, assertions, observations, and cleanup.
- Environment readiness blocking before unsafe adapter/provider execution.
- One batch record per RP execution and one run record per executed test case.
- Coverage, traceability, failure summary, and release-review evidence from the selected batch.

## 7.4 Out of M1 Scope

- Product-level formal AC as release denominator.
- RU repo-owned primary specs or primary AC.
- Cross-package orchestration.
- Full dashboard-driven governance.
- Fully automated release approval.
- Broad package-type plugin support.
- Framework-owned SIT/UAT deployment orchestration.

## 7.5 Standard Framework Verification Cases

| Test ID | Scenario | Command Level | Priority | Automation |
|---|---|---|---|---|
| FWK-001 | Framework unit/component suite validates parsers, readiness checks, CLI behavior, resolvers, and evidence writers | `./mvnw test` | P1 | Auto |
| FWK-002 | Framework integration suite validates a sample Product Repo fixture through check, dry-run/run, and report flow | `./mvnw verify` | P1 | Auto |
| FWK-003 | Framework integration fixture evidence is marked as sample evidence and is not counted as downstream RP release evidence | `./mvnw verify` | P1 | Auto |
| FWK-004 | Missing sample fixture, unsupported provider, or invalid DSL blocks framework integration verification with actionable failure | `./mvnw verify` | P1 | Auto |

## 7.6 Standard RP Regression Cases

| Test ID | Feature | Scenario | Level | Priority | Automation |
|---|---|---|---|---|---|
| REG-RP-001 | F001 | Product Repo bootstrap CLI emits readiness report and readiness agent skill explains missing folders, RP artifacts, owner actions, and next steps | Readiness | P1 | Auto / Agent |
| REG-RP-002 | F002 | RP completeness check requires minimum RP artifact set | Readiness | P1 | Auto |
| REG-RP-003 | F003 | RP feature spec and AC intake preserves owner-authored AC and stable IDs | Readiness | P1 | Auto |
| REG-RP-004 | F004 | Missing RP/RU mapping fields block execution | Readiness | P1 | Auto |
| REG-RP-005 | F005 | Ambiguous AC produces `not_ready_for_generation` instead of executable test | Generation | P1 | Auto |
| REG-RP-006 | F005 | Ready AC with incomplete execution context produces only package-neutral `draft_test_skeleton` | Generation | P1 | Auto |
| REG-RP-014 | F005 / F007 | DSL validation rejects unsupported `dsl_version` or missing required fields before execution | Generation / Execution | P1 | Auto |
| REG-RP-007 | F006 | Expected-result draft includes source references, assumptions, gaps, and approval status | Generation | P1 | Auto |
| REG-RP-008 | F007 | Pilot RP DSL test executes through adapter and emits raw execution evidence | Execution | P1 | Auto |
| REG-RP-011 | F007 | Execution reuses checked-in approved RP DSL test cases without regenerating them | Execution | P1 | Auto |
| REG-RP-012 | F007 | SIT-deployed execution is blocked until deployment and environment readiness evidence exists | Execution | P1 | Auto |
| REG-RP-013 | F007 | Multi-RU execution follows the declared dependency graph and stops downstream execution on required upstream validation failure | Execution | P1 | Auto |
| REG-RP-009 | F008 | Coverage and evidence package trace to RP ID, batch ID, run ID, AC ID, and test case ID | Evidence | P1 | Auto |
| REG-RP-010 | F008 | Manual-only or waived AC require approval record before exclusion | Evidence | P1 | Auto |

## 7.7 CI/CD Execution Policy

| Pipeline Stage | Required Command | Purpose |
|---|---|---|
| Pull request | `./mvnw test` | Fast framework verification. |
| Main or release branch | `./mvnw verify` | Framework integration verification with sample fixture. |
| RP release pipeline | `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` | Downstream RP regression execution. |
| SIT/UAT release gate | `regress run --root <product-repo> --rp-id <rp-id> --env sit_deployed` | Validate already deployed RU versions when SIT/UAT is required. |

All commands should be bounded and avoid memory-heavy execution. Local and CI runs should stay under the repository guidance of 8 GB RAM.
