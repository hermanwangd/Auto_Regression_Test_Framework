# 07. Framework Verification Test Plan

This plan defines how to verify the regression test framework itself. It covers unit/component verification and sample Product/RP/RU integration verification.

It does not define downstream product-feature release AC and does not replace a real RP release regression plan. Real Product/RP regression evidence is produced later by `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` against owner-provided RP artifacts.

## 7.1 Verification Objective

Framework verification shall prove that the framework can:

- Run fast unit/component tests for parsers, validators, resolvers, CLI behavior, evidence writers, and reporting logic.
- Run an end-to-end framework integration flow against a sample Product Repo fixture.
- Use sample Product/RP/RU artifacts to validate RP discovery, RP/RU mapping, checked-in execution-focused DSL test discovery, adapter execution, verification, batch evidence, run evidence, and coverage report generation.
- Verify provider capability registry and provider-family contract behavior required by the selected heterogeneous pilot without treating mock or sample provider evidence as downstream RP release evidence.
- Keep sample fixture evidence clearly separate from downstream Product/RP release evidence.

## 7.2 Verification Levels

| Level | Subject Under Test | Command | Fixture Source | Evidence |
|---|---|---|---|---|
| Unit/component framework verification | Framework modules and CLI behavior | `./mvnw test` | Temp directories and small local test data | Maven Surefire reports. |
| Sample Product/RP/RU integration verification | Framework end-to-end behavior | `./mvnw verify` | `src/test/resources/framework-verification/sample-product-repo/` | Maven Failsafe reports and generated temporary sample evidence. |
| Provider-family contract verification | Provider capability registry validation, provider contract parsing, dry-run dispatch, unsupported capability blocking, unapproved escape-hatch blocking, and normalized evidence shape | `./mvnw verify` | Local/mock provider fixtures plus injectable native messaging transport coverage for request/response, Kafka/NATS publish, NATS request/reply, consume/observe, cleanup, DB fixture, deployment readiness, file/batch, and escape-hatch contract validation when declared | Maven Failsafe reports and generated temporary provider evidence. |
| Packaged CLI smoke verification | Spring Boot jar CLI entrypoint | `java -jar target/spec-driven-auto-regression-0.1.0-SNAPSHOT.jar check-readiness --root .` | Current repository Product Repo structure | CLI output and exit code. |

`./mvnw test` must stay fast and deterministic. `./mvnw verify` may execute a local shell adapter through the sample fixture, but must not require SIT/UAT deployment.
Provider-family contract verification may use local mocks, stub servers, temp files, temp schemas, fake topics, or command stubs. It must not require real SIT/UAT endpoints, real K8s clusters, real VMs, production data, or committed secrets. External runner verification is limited to contract validation and approved escape-hatch behavior unless an explicit runner implementation slice is selected later.

## 7.3 Unit and Component Verification

Unit/component tests live in `src/test/java` and use the `*Test` suffix so Maven Surefire runs them during `./mvnw test`.

They shall cover:

- Product Repo bootstrap and readiness checks.
- RP skeleton and artifact completeness checks.
- RP/RU mapping parser, dependency graph, and missing-field handling.
- DSL schema validation for `traceability`, `targets`, `setup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime`, plus execution lifecycle status checks and unsupported capability blocking.
- Expected-result approval gating.
- Environment resolver behavior for `local_fixture`, `ci_ephemeral`, `sit_deployed`, and `evidence_only`.
- Binding, provider, fixture, execution, assertion, evidence, and coverage-report behavior.
- Provider-family metadata for request/response, messaging, DB fixture, deployment readiness, file/batch, and approved escape-hatch contracts.
- Unsupported provider families, missing contracts, missing cleanup policy, and missing readiness evidence fail before adapter/provider execution.
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

Provider-family verification cases use a separate local/mock provider fixture set when one shell adapter fixture is not enough to prove registry behavior. That fixture set must include valid, missing-field, unsupported, ambiguous, and unapproved escape-hatch provider contracts for the selected provider families.

The integration flow shall:

```text
copy sample Product Repo fixture to temp directory
-> check-rp --strict-schema
-> run --rp-id RP-FWK-SAMPLE --env ci_ephemeral
-> report --batch-id BATCH-001
-> assert batch evidence, run evidence, and coverage evidence
```

The integration suite shall expose AC traceability through `FrameworkVerificationIT` display names and this mapping:

| Integration Case | AC Coverage | Scenario | Required Assertions |
|---|---|---|---|
| FWK-IT-001 | AC-002, AC-004, AC-007, AC-009, AC-010 | Happy path `check-rp`, `run`, and `report` complete without SIT/UAT deployment | Complete RP artifacts, valid RP/RU mapping, durable batch/run evidence, 100% sample coverage, and sample evidence boundary. |
| FWK-IT-002 | AC-002, AC-004, AC-010 | Artifact readiness failure | Package schema and RP/RU mapping gaps are reported before execution evidence is written. |
| FWK-IT-003 | AC-004, AC-008, AC-010 | Provider contract failure | Missing adapter command blocks before adapter execution and writes blocked run evidence. |
| FWK-IT-004 | AC-006, AC-008, AC-010 | Truth-source approval failure | Unapproved expected result blocks before adapter execution or assertion evaluation. |
| FWK-IT-005 | AC-007, AC-008, AC-010 | Test inventory boundary | Missing execution-eligible checked-in DSL test case blocks before adapter execution. |
| FWK-IT-006 | AC-007, AC-009 | Execution/assertion failure | Adapter execution starts, assertion fails, run evidence is failed, and report is not review-ready. |
| FWK-IT-007 | AC-001, AC-010 | Product Repo bootstrap and readiness | Bootstrap creates lifecycle folders, readiness changes from fail to pass, rerun is idempotent, and no RP scope is invented. |
| FWK-IT-008 | AC-003, AC-010 | AC readiness intake | Owner-authored RP AC remains unchanged while readiness output preserves stable AC ID, classification, and owner-authored truth flag. |
| FWK-IT-009 | AC-005, AC-010 | Test drafting readiness gates | Ready AC produces executable draft, existing approved tests produce update proposals, ambiguous AC blocks, and incomplete context produces skeleton only. |
| FWK-IT-010 | AC-004, AC-007, AC-008 | Provider-family contract dry-run | Local/mock provider contracts for selected provider families resolve through the capability registry with provider family, provider type, registry status, and contract path, while missing, ambiguous, unsupported, or unapproved escape-hatch provider contracts block with affected RU and owner action. |
| FWK-IT-011 | AC-007, AC-008, AC-009 | Provider-family evidence normalization | Mock provider results are normalized into run evidence with provider family, provider type, registry status, provider contract path, adapter/provider result, assertion result, cleanup result, and final status. |

Generated sample evidence must stay in the test temp directory. It shall not be committed and shall not count as real Product/RP release evidence.

## 7.5 Provider-Family Contract Verification

Provider-family verification proves that the framework can validate provider capability registry entries, plan, dispatch, block, and normalize evidence for heterogeneous RP execution boundaries. It does not prove a real Product/RP release.

The contract verification suite shall cover:

| Provider Family | Positive Verification | Failure Verification | Evidence Requirement |
|---|---|---|---|
| Request/response | Resolve a REST or gRPC-style action contract, payload binding, response mapping, timeout, and assertion reference | Missing endpoint/service ref, unsupported action, or missing payload binding blocks before invocation | Provider family, action, request binding ref, response evidence ref, assertion result |
| Messaging | Resolve publish/request_reply/consume/observe/cleanup contract with topic or subject ref, payload binding for publish/request, correlation id when required, timeout, observation rule, cleanup strategy, and max cleanup count | Missing topic/subject ref, missing publish/request payload binding, missing cleanup strategy/count, missing correlation id where required, unsupported mode, or unsupported serialization blocks before dispatch | Provider family, topic/subject ref, action mode, correlation id, observed message/event ref, request/reply output ref, cleanup result |
| DB fixture | Resolve seed/query/cleanup contract with connection ref, isolation key, and cleanup strategy | Mutating setup without cleanup, missing connection ref, or unsafe query ref blocks before setup | Seed ref, query result ref, cleanup result, postcondition evidence |
| Deployment readiness | Resolve K8s and VM readiness contracts, version/deployment refs, readiness probes, bounded K8s direct API readiness, bounded K8s pod log capture, and bounded VM SSH/WinRM command probes | Missing deployment ref, API server ref, pod selector/log tail bound, VM command ref, readiness probe, environment ref, or provider capability blocks affected RU before execution | Deployment provider family, readiness status, deployment/version ref, affected RU, captured pod log ref or VM command output ref when configured |
| File/batch | Resolve command, working directory, input refs, output refs, logs, success codes, and timeout | Missing command, missing output ref, timeout, or non-success exit code produces failed run evidence | stdout/stderr refs, actual output ref, exit code, timeout flag |
| External runner escape hatch | Resolve only when explicit approval metadata, reason, owner, command/container ref, inputs, outputs, timeout, and evidence map are present | Missing approval, unbounded timeout, unsafe output path, unsafe evidence-map path, missing evidence map, or available built-in provider alternative blocks before runner invocation; missing mapped evidence artifact fails the run after invocation | Escape-hatch status, approval ref, runner command/container ref, exit/timeout result, mapped evidence artifacts, and mapped-artifact existence status |

Every provider-family case must also verify dry-run output. Dry-run must name provider family, provider type, registry status, capability, affected RU, provider contract path, AP gate, and owner action for missing, ambiguous, unsupported, or unapproved escape-hatch capabilities.

## 7.5.1 Execution-Focused DSL v1 Verification

Framework verification must prove the v1 DSL contract before implementation claims runtime completion:

This is a pre-provider-runtime gate. FWK-008 must be green before sample fixture migration, provider dispatch expansion, or native runtime work can claim support for the new execution-focused DSL shape. FWK-008 includes parser/generator validation and one CLI run/report proof that the same v1 artifact is usable by execution and reporting.

| DSL Area | Positive Verification | Failure Verification |
|---|---|---|
| Identity and traceability | Reads `dsl_version`, `test_case_id`, `status`, `revision`, and `traceability.package_id` / `acceptance_criteria_id` / `source` | Missing traceability or unsupported `status` blocks before execution. |
| Targets | Resolves multiple named `targets` with `type`, `runner`, and `environment` | Missing target or execute step referencing an unknown target blocks before provider dispatch. |
| Setup | Resolves `setup.fixtures` and `cleanup_ref` for state-mutating fixtures | State-changing fixture without cleanup reference reports a fixture-policy gap. |
| Execute | Resolves `execute[].operation`, `target`, `with`, and `outputs` | `call_ru`, `target_ru_id`, missing outputs, or unsupported operation blocks before execution. |
| Expected results | Resolves `expected_results` refs used by verification | Duplicated legacy oracle/expected references or missing expected ref blocks before assertion evaluation. |
| Verify | Supports explicit actual/expected checks plus `selector`, `db_record_exists`, and `event_published` semantics | Missing `actual`/`expected`, query ref, event ref, or unsupported verify type blocks with verify ID. |
| Evidence and runtime | Resolves `evidence.required`, `runtime.timeout`, and `runtime.retry.max_attempts` | Evidence refs that do not point to execute/verify outputs or unbounded runtime policy block before execution. |
| Run/report consumption | `run` executes one active v1 approved test and `report --batch-id` produces review-ready traceability and coverage | A run that passes but cannot be included in a review-ready batch report fails the gate. |
| Prohibited fields | Accepts execution-focused fields only in new artifacts | `rp_id`, `ac_id`, `execution_target`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, `policy`, approval fields, waiver fields, release gate fields, or risk approval fields block before execution. |
| Legacy compatibility | Legacy sample artifacts remain readable through an explicit compatibility path until migrated | Legacy-only input must not be silently promoted as a new execution-focused artifact. |

Minimum FWK-008 test cases:

- Valid execution-focused DSL with multiple targets, setup fixture, `run_batch`, captured outputs, expected result, verify rules, evidence refs, timeout, and retry passes validation.
- Missing traceability, unsupported status, missing target, unknown execute target, missing execute outputs, missing expected result ref, invalid verify rule, invalid evidence ref, or unbounded runtime policy blocks before provider dispatch.
- Legacy operation/field names such as `call_ru`, `target_ru_id`, `package_inputs`, and `oracles` are rejected in new execution-focused artifacts.
- Governance-heavy fields such as approval, waiver, release gate, or risk approval state are rejected in DSL test cases.
- Generator output for `draft_executable_test_case` emits the v1 execution-focused field set and does not overwrite checked-in approved tests.
- CLI dry-run reports DSL validation gaps with AP, field path, test case ID, AC ID, reason, and owner action.
- CLI `run` accepts one `tests/approved/` v1 test with `status: active`, writes run and batch evidence, and CLI `report --batch-id` returns review-ready coverage with traceability to RP ID, AC ID, test case ID, and run ID.

This verification may initially run against parser/generator tests, CLI dry-run tests, compatibility translation tests, and one CLI run/report consumption test. It must be green before migrating the sample fixture or changing provider runtime dispatch.

## 7.6 Required Framework Verification Cases

| Test ID | AC Coverage | Scenario | Command Level | Priority | Automation |
|---|---|---|---|---|---|
| FWK-001 | AC-001 through AC-010 | Unit/component suite validates parsers, readiness checks, CLI behavior, resolvers, execution services, evidence writers, and reporters | `./mvnw test` | P1 | Auto |
| FWK-002 | AC-001 through AC-010 | Sample Product/RP/RU fixture runs AC-linked framework integration cases through CLI commands without SIT/UAT deployment | `./mvnw verify` | P1 | Auto |
| FWK-003 | AC-010 | Sample fixture evidence is marked as framework verification evidence and is not counted as downstream RP release evidence | `./mvnw verify` | P1 | Auto |
| FWK-004 | AC-002, AC-003, AC-004, AC-005, AC-006, AC-007, AC-008, AC-009, AC-010 | Artifact readiness gaps, provider contract gaps, AC readiness gaps, drafting gates, missing expected results, missing execution-eligible DSL tests, or failed verify rules block or fail with actionable evidence | `./mvnw verify` | P1 | Auto |
| FWK-005 | AC-010 | Packaged jar delegates CLI arguments to the framework command layer and returns meaningful exit codes | `./mvnw test` plus packaged CLI smoke | P1 | Auto / CLI |
| FWK-006 | AC-004, AC-007, AC-008, AC-009, AC-010 | Provider-family contract verification covers request/response, messaging, DB fixture, deployment readiness, file/batch provider behavior, and escape-hatch contract gating with local/mock fixtures | `./mvnw verify` | P1 | Auto |
| FWK-007 | AC-007, AC-008, AC-010 | Provider-family negative cases block before unsafe execution and report provider family, provider type, registry status, escape-hatch approval status when applicable, capability, affected RU, provider contract path, AP gate, and owner action | `./mvnw verify` | P1 | Auto |
| FWK-008 | AC-005, AC-006, AC-007, AC-008, AC-009 | Execution-focused DSL v1 parser/generator/translator verifies `targets/setup/execute/expected_results/verify/evidence/runtime`, blocks invalid DSL before provider dispatch, rejects legacy-only or governance-heavy DSL fields, and proves one v1 approved test can pass CLI `run` plus review-ready `report --batch-id` | `./mvnw test` | P1 | Auto |

### 7.6.1 Current Coverage Snapshot

This snapshot records what the current framework verification suite is intended to prove. It must be updated whenever provider runtime support changes.

| Area | Current Java Evidence | Status | Remaining Pilot Gap |
|---|---|---|---|
| Framework CLI, readiness, generation, run, report, batch/run evidence | `RegressionCommandTest`, `ProductRepoServiceTest`, `ReleasePackageServiceTest`, `AcIntakeServiceTest`, `TestCaseLifecycleServiceTest`, `CoverageReportService` tests through CLI flows | Covered by unit/component tests for legacy DSL flow; execution-focused DSL v1 parser/generator validation is in place; v1 run/report consumption is the next required gate before runtime migration | Real RP evidence still requires owner artifacts. |
| Sample Product Repo integration | `FrameworkVerificationIT`, `PackagedCliSmokeIT` | Covered by `./mvnw verify` | Does not count as downstream RP release evidence. |
| File/batch provider | `RegressionCommandTest`, `FrameworkVerificationIT`, `ExecutionEngineTest` | Supported with bounded shell/file execution, logs, output refs, timeout, success code, and evidence | Native package-specific adapters are out of scope unless reusable. |
| REST/gRPC request/response provider | `RegressionCommandTest`, `ProviderCapabilityRegistryTest`, `RequestResponseProviderTest`, and `DefaultGrpcClientInvokerTest` request/response cases | Supported for REST and native descriptor-driven gRPC unary calls with endpoint/service refs, actions, payload binding, timeout, output ref, and evidence | Pilot endpoint validation remains target provider work. |
| Response assertion provider | `ExecutionEngineTest` response assertion cases, `RequestResponseProviderTest` HTTP evidence cases, and `AssertionEngineTest` schema/contract assertion cases | Supported for HTTP/status field checks, JSON path equality and absence assertions, numeric tolerance assertions, schema/contract assertions, multi-assertion aggregation, and durable assertion evidence over provider actual-output JSON/YAML plus request-response evidence | Add invariant or custom comparator assertions only if selected by the pilot. |
| Messaging provider | `RegressionCommandTest`, `ProviderCapabilityRegistryTest`, `MessagingProviderTest`, `DefaultMessagingTransportTest`, and `ExecutionEngineTest` messaging cases | Supported for local/mock messaging and native Kafka/NATS publish, NATS request/reply, consume/observe, and bounded cleanup drain runtime with connection refs, topic/subject refs, publish/request payload binding, action mode, timeout, output ref, correlation checks, cleanup strategy/count validation, transport dispatch, and evidence normalization | Real pilot broker validation remains target provider work; Kafka request/reply and persistent broker purge are future work only if the selected RP requires them. |
| DB fixture provider | `RegressionCommandTest`, `ExecutionEngineTest`, and `OracleReadinessServiceTest` DB fixture/assertion cases | Supported for JDBC setup/query/cleanup with `sql_ref`, cleanup strategy, isolation key, query-result oracle readiness, DB row count assertions, and evidence | Richer DB-row match modes remain future provider/assertion work only if selected by the pilot. |
| Deployment readiness provider | `RegressionCommandTest`, `ProviderCapabilityRegistryTest`, `DeploymentReadinessProviderTest`, and `ExecutionEngineTest` readiness cases | Supported for local/mock readiness and native K8s/VM bounded probes with deployment/version refs, kube API or VM target refs, timeout, output ref, bounded K8s direct API deployment availability, bounded K8s pod log capture, VM SSH/WinRM command probes, dispatch, and readiness evidence | Real pilot K8s/VM validation remains target provider work. |
| External runner escape hatch | `RegressionCommandTest`, `FrameworkVerificationIT` escape-hatch cases | Supported for approved command-runner contracts, safe outputs/evidence map, built-in alternative blocking, and mapped-artifact checks | Broader runner schema/content checks are future hardening. |
| Heterogeneous pilot validation | Not covered by framework Maven tests | Pending owner-provided RP | Requires real `package.yaml`, `rp_feature_spec.md`, `acceptance_criteria.md`, `rp_ru_mapping.yaml`, approved tests, approved truth sources, and selected provider contracts. |

## 7.7 Selected Heterogeneous Pilot Validation Gate

After owner-provided pilot RP artifacts exist, the downstream RP release pipeline shall validate the real heterogeneous RP using its real Product Repo artifacts and selected provider contracts.

The selected pilot validation must prove:

- At least one request/response provider path for REST or gRPC.
- At least one messaging provider path for Kafka or NATS.
- DB fixture setup, query/assertion, and cleanup with an isolation key.
- K8s and VM deployment readiness checks before deployed-environment execution.
- External runner escape hatch only when the pilot RP has an approved legacy or specialized boundary that cannot use a reusable built-in provider.
- Batch/run evidence includes provider family, provider type, registry status, provider contract path, resolved bindings, adapter/provider result, assertion result, cleanup result, and final pass/fail status.
- Dry-run blocks missing, ambiguous, unsupported, or unapproved escape-hatch selected provider families/types before unsafe execution.

This gate is not satisfied by the sample framework fixture. It requires owner-authored `package.yaml`, `rp_feature_spec.md`, `acceptance_criteria.md`, `rp_ru_mapping.yaml`, approved DSL tests, approved truth sources, selected provider contracts, and environment readiness records for the pilot RP.

## 7.8 Downstream RP Regression Boundary

Downstream Product/RP regression is a framework capability, but it is not the primary subject of this framework verification test plan.

When owner-provided Product/RP artifacts exist, release package regression shall be verified by a separate RP validation flow:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env <mode>
regress report --root <product-repo> --rp-id <rp-id> --batch-id <batch-id>
```

That flow produces real RP batch/run evidence under the Product Repo. It may use `local_fixture`, `ci_ephemeral`, `sit_deployed`, or `evidence_only` depending on the RP validation boundary. SIT/UAT runs require deployed RU versions and environment readiness evidence.

## 7.9 CI/CD Execution Policy

| Pipeline Stage | Required Command | Purpose |
|---|---|---|
| Pull request | `./mvnw test` | Fast framework unit/component verification. |
| Main or release branch | `./mvnw verify` | Framework integration verification with sample Product/RP/RU fixture. |
| Provider-family contract verification | `./mvnw verify` | Local/mock provider-family proof for heterogeneous execution boundaries without real downstream RP release evidence. |
| Packaged CLI smoke | `java -jar target/spec-driven-auto-regression-0.1.0-SNAPSHOT.jar check-readiness --root .` | Verify packaged command delegation. |
| RP release pipeline | `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` | Downstream RP regression execution, outside this framework verification plan. |

All commands should be bounded and avoid memory-heavy execution. Local and CI runs should stay under the repository guidance of 8 GB RAM.

## 7.10 Out of Scope

- Formal downstream product-feature AC definition.
- Treating sample fixture evidence as real Product/RP release evidence.
- Framework-owned SIT/UAT deployment orchestration.
- Real pilot RP validation before owner-provided RP artifacts exist.
- Broad package-type plugin certification beyond the sample fixture.
