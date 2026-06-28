# 16. Implementation Plan

Status: Implementation-Ready Draft for M1 staged delivery

This plan implements the Product/RP/RU baseline without changing product scope or authoring RP acceptance criteria. It starts with the framework foundation, then enables generation, execution, and evidence once pilot RP artifacts exist.

This is an implementation plan, not the framework verification strategy itself. Framework Verification is defined in `docs/07-validation-evidence/07_regression_test_plan.md`, including unit/component tests and sample Product/RP/RU integration verification. Real downstream RP regression execution remains a release-package validation flow after owner-provided RP artifacts exist.

## Entry Criteria

- Product, RP, and RU responsibilities are accepted.
- RP-level AC are the release coverage denominator.
- ADR-005 is accepted: Framework Verification and RP Regression Execution are separate execution lines.
- ADR-006 is accepted: heterogeneous RP support is handled through provider contracts, a provider capability registry, reusable built-in providers, and a governed external runner escape hatch.
- Minimum RP artifacts are defined: `package.yaml`, `rp_feature_spec.md`, `rp_ru_mapping.yaml`, `acceptance_criteria.md`, `tests/`, `expected-results/`, `traceability.md`, and `evidence_index.md`.
- Architecture design defines Spring Boot 3.x / Java 17+ AP-level components, extension points, provider families, internal package boundaries, CLI commands, storage paths, execution modes, failure handling, and AC coverage.
- Execution-focused DSL v1 is defined in the artifact contracts and must be validated and proven through CLI `run` plus `report --batch-id` before F007 provider runtime expansion.
- Before any DSL, AP, provider runtime, evidence, or report implementation slice starts, the feature/spec, architecture design, artifact contract, AC, implementation plan, and test plan must be aligned on behavior, non-goals, required/conditional/prohibited fields, AP ownership, happy/failure/boundary paths, and verification evidence.

## Staged Readiness

Ready to implement now:

- F001 Product Repo Bootstrap CLI and Readiness Agent Skill.
- F002 Release Package Creation Guide and Completeness Check.
- F004 RP/RU Mapping Intake and Completeness Check, using sample or pilot mappings.
- Execution-focused DSL v1 parser/validator, generator guard, and run/report consumption gate before provider runtime migration.

Ready after pilot RP artifacts exist:

- F003 RP Feature Spec and AC Intake.
- F005 Agent AC and Execution Context Readiness with DSL Test Drafting.
- F006 Agent Expected Result Drafting.
- F007 Release Package DSL Test Execution.
- F008 Coverage and Evidence Package.

Pilot RP owner must supply RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, expected-result approval owner, adapter mode, dependency graph, adapter/provider contracts, any required target/binding, fixture, expected-result, verify, or evidence/observation provider capability, and any approved external runner escape-hatch need.

## Current Implementation Status Snapshot

This snapshot separates framework verification progress from pilot acceptance progress. Update it when a provider runtime or verification case changes.

| Area | Current Status | Evidence / Gate | Next Work |
|---|---|---|---|
| Product Repo and RP skeleton | Implemented for framework verification | CLI tests and sample fixture verification | Harden cross-artifact readiness as pilot artifacts arrive. |
| AC intake and DSL drafting | Implemented for legacy framework readiness/draft flows and execution-focused DSL v1 parser/generator/run/report consumption | Unit/component tests, CLI run/report test, DSL v1 contract review, and `dsl_runtime` evidence metadata | Replace inline `explicit_cases` parameter expansion with `parameters.ref` / `parameters.bind_as`, then continue provider-family and pilot-environment hardening without regressing T003A/T003B gates. |
| Batch/run evidence and coverage | Implemented for sample and CLI flows | `./mvnw verify` and report tests | Validate against real pilot RP batch evidence. |
| File/batch runtime | Supported | Provider registry dispatch and shell/file tests | Keep as reusable provider. |
| REST/gRPC request-response runtime | Supported for REST and native descriptor-driven gRPC unary calls plus HTTP/status field, JSON path equality/absence, numeric tolerance, and schema/contract response assertions | Request/response provider, native gRPC invoker, runtime registry, CLI preflight, response assertion, schema/contract assertion, and evidence tests | Add pilot endpoint evidence when required. |
| Messaging runtime | Supported for local/mock plus native Kafka/NATS publish, NATS request/reply, consume/observe, and bounded cleanup drain dispatch | Messaging provider contract, runtime registry, native transport, CLI preflight, and evidence tests | Add owner broker-backed pilot validation and persistent broker purge only if the selected RP requires it. |
| DB fixture runtime | Supported for JDBC fixture lifecycle and DB row count assertions | DB setup/query/cleanup tests with `sql_ref`, cleanup strategy, isolation key, query-result expected-result readiness, and DB verify evidence | Add richer DB-row match modes only if pilot requires them. |
| Deployment readiness runtime | Supported for local/mock plus native K8s/VM bounded readiness probes, K8s direct API deployment availability, K8s pod log capture, and VM SSH/WinRM command probes | Readiness provider, runtime registry, and provider contract tests with version, timeout, target refs, API server refs, bounded log tail refs, command refs, and output refs | Add real pilot environment validation. |
| External runner escape hatch | Supported as governed escape hatch | Contract gating and mapped evidence tests | Add content/schema validation only if pilot needs it. |
| Heterogeneous pilot validation | Pending | Requires owner-provided Product/RP artifacts | Run T017 after pilot artifacts exist. |

## Task Backlog

### T000 - Test Boundary and Verification Plan Alignment

Related feature: Cross-cutting verification governance
Acceptance: AC-010
Modules: docs, build lifecycle

Keep ADR, spec, AC, architecture, test plan, and implementation plan aligned on the difference between Framework Verification and RP Regression Execution.

Verification:

```bash
rg "Framework Verification|RP Regression Execution|mvnw test|mvnw verify|regress run" docs
```

Done when the docs consistently state that `./mvnw test` and `./mvnw verify` validate this framework, while `regress run --root <product-repo> --rp-id <rp-id> --env <mode>` validates a downstream Product/RP and writes RP evidence.

### T000A - Pre-Implementation Documentation Alignment Gate

Related feature: Cross-cutting DSL/AP/provider governance
Acceptance: AC-005, AC-006, AC-007, AC-008, AC-009, AC-010
Modules: docs, planning

Before changing DSL validation, generation, execution, evidence, reporting, provider runtime, or AP boundaries, update and review the feature/spec, architecture design, artifact contract, AC, implementation plan, and test plan. The review must confirm consistent field ownership, required vs conditional field rules, prohibited governance and legacy fields, AP consumers, provider contract boundary, acceptance behavior, and verification evidence.

Verification:

```bash
rg -n "call_ru|target_ru_id|package_inputs|oracles|approval_status|release_gate|risk_approval" docs/01-specs docs/02-architecture docs/03-acceptance docs/04-planning docs/07-validation-evidence
rg -n "dsl_version|targets|setup|execute|expected_results|verify|evidence|required|conditional|prohibited" docs/01-specs docs/02-architecture docs/03-acceptance docs/07-validation-evidence
```

Done when legacy terms appear only in migration/prohibited-field contexts, governance-heavy fields are absent from DSL examples, and the next implementation task can point to specific AC and verification cases.

### T001 - Product Repo Bootstrap CLI and Readiness Agent Skill

Related feature: F001
Acceptance: AC-001
Modules: `cli`, `productrepo`, readiness agent skill

Implement `regress init-product-repo --root <path>` to create the agreed lifecycle folders and starter locations. Implement `regress check-readiness --root <path> --format yaml|json` to emit a machine-readable readiness report. Provide a readiness agent skill that reads the report and explains status, missing items, owner actions, and next steps. The CLI must be idempotent and must not overwrite existing content. The agent skill must not mutate repo artifacts or invent RP scope, RP AC, or RP/RU membership.

Verification:

```bash
regress init-product-repo --root <tmp-product-repo>
regress check-readiness --root <tmp-product-repo> --format yaml
regress check-readiness --root <tmp-product-repo> --rp-id <rp-id> --write-report
agent product-repo-readiness --report <tmp-product-repo>/docs/08-release/release-packages/<rp-id>/evidence/readiness/readiness.yaml
```

Done when missing folders are created or reported, readiness output includes pass/fail status, missing items, owner action, and next required step, and the agent skill translates the readiness report into owner-actionable guidance.

### T002 - RP Skeleton and Completeness Check

Related feature: F002
Acceptance: AC-002
Modules: `cli`, `discovery`, `schema`

Implement `regress init-rp` and `regress check-rp` for the RP folder contract under `docs/08-release/release-packages/<rp_id>/`.

Verification:

```bash
regress init-rp --root <product-repo> --rp-id <pilot-rp-id> --package-type <package-type>
regress check-rp --root <product-repo> --rp-id <pilot-rp-id>
```

Done when required RP files and folders are present or reported as completeness gaps with owner action.

### T003 - Artifact Schema Parser

Related features: F002, F003, F004
Acceptance: AC-002, AC-003, AC-004
Modules: `schema`

Implement typed parsers for `package.yaml`, `rp_ru_mapping.yaml`, AC entries, DSL test cases, expected results, provider contracts, and evidence records. Start with YAML/Markdown front matter or embedded YAML blocks supported by the artifact contracts. DSL parsing must validate `dsl_version`, required fields, conditionally required fields, and allowed enum values. Provider contract parsing must require explicit `provider_family` and `provider_type` for executable contracts and validate supported actions, required references, secret refs, cleanup strategy, evidence outputs, escape-hatch approval metadata when applicable, and unsupported configuration.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id> --strict-schema
```

Done when schema errors identify file path, field path, severity, owner action, and whether the error blocks generation, execution, or release evidence.

### T003A - Execution-Focused DSL v1 Validation Gate

Related features: F005, F006, F007
Acceptance: AC-005, AC-006, AC-007, AC-008
Modules: `schema`, `testcase`, `cli`

Implement the execution-focused DSL v1 validation gate before provider runtime migration. The validator must accept the v1 semantic field set from `docs/02-architecture/06_artifact_contracts.md`, preserve legacy sample readability through an explicit compatibility path, and block new execution-focused artifacts that contain legacy-only fields or governance-heavy fields.

The gate validates:

- Always-required fields: `dsl_version`, `test_case_id`, `status`, `revision`, `traceability`, `targets`, `scenario`, `execute`, `verify`, `evidence`, and `runtime`.
- Conditional fields: `setup.fixtures` when precondition data or mutated state is needed, `expected_results` when verify rules use approved artifacts or reusable truth sources, `setup.fixtures.<name>.cleanup_ref` for state mutation, exactly one M1 `execute[]` item, `execute[].with`, `execute[].outputs`, `verify[].actual` when a verify rule reads captured output, `verify[].selector` for `json_path_equals`, `json_path_absent`, and structured `numeric_tolerance` checks, provider metadata for metadata-backed rules such as `response_status_equals`, `verify[].target/query/event`, and `verify[].options`.
- Parameterization fields when used: `parameters.ref`, `parameters.bind_as`, a readable reviewed parameter set, unique case IDs in the referenced set, non-empty case values, and resolvable `${param.<bind_as>.<field>}` references.
- Supported operations: `run_batch`, `execute_command`, `call_api`, `execute_sql`, `publish_message`, `consume_message`, `request_reply_message`, and `run_application`.
- Supported verify rules: basic, structure, collection, numeric, file, state, and event checks defined in the artifact contract.
- Prohibited fields: legacy-only fields such as `call_ru`, `target_ru_id`, `package_inputs`, and `oracles`, plus approval, waiver, release gate, and risk approval fields.

Verification:

```bash
./mvnw -q -Dtest='DslTestCaseValidatorTest,TestCaseLifecycleServiceTest,RegressionCommandTest' test
./mvnw test
```

Done when valid DSL v1 artifacts pass, invalid DSL blocks before provider dispatch with AP/field/test/AC/owner-action details, generated executable drafts use execution-focused fields, and legacy artifacts remain readable only through compatibility behavior.

The response status metadata gate includes `response_status_equals` with request/response provider HTTP status metadata, blocking the metadata shortcut when no request/response execute target exists, and requiring `actual` plus `selector` when the status is read from captured structured output.

### T003B - Execution-Focused DSL v1 Run and Report Consumption Gate

Related features: F007, F008
Acceptance: AC-007, AC-009
Modules: `cli`, `execution`, `evidence`, `report`

Prove that the same execution-focused DSL v1 artifact can be consumed by execution and reporting before provider runtime expansion. The test artifact must live under `tests/approved/`, use `status: active`, and contain only v1 sections: `traceability`, `targets`, `scenario`, `setup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime`.

Required behavior:

- `run` derives RP ID and AC ID from v1 `traceability.*` fields.
- `run` resolves v1 targets, setup fixtures, execute outputs, expected-result refs, verify rules, evidence refs, timeout, and retry into durable run and batch evidence.
- `report --batch-id` calculates coverage from the selected batch using v1 traceability and normalized run evidence.
- The selected batch becomes review-ready when the v1 test passes and covers the automatable RP AC.
- A passing run that cannot be reported as review-ready is treated as an incomplete F007/F008 implementation.

Verification:

```bash
./mvnw -q -Dtest='RegressionCommandTest#runExecutesExecutionFocusedDslV1AndProducesReviewReadyBatchReport' test
./mvnw -q -Dtest='RegressionCommandTest,DslTestCaseValidatorTest,TestCaseLifecycleServiceTest' test
```

Done when an active v1 approved test can pass CLI `run`, write run and batch evidence, and pass CLI `report --batch-id` with review-ready traceability to RP ID, AC ID, test case ID, batch ID, and run ID.

### T004 - RP/RU Mapping Validator

Related feature: F004
Acceptance: AC-004
Modules: `mapping`, `environment`

Validate that each owner-authored RU entry declares repo, owner, unit type, version reference, validation boundary, execution mode, deployment requirement, environment reference, adapter or adapter mode, evidence responsibility, dependencies, and adapter/provider contracts when execution is required. Mapping validation must not infer provider ownership from file order when multiple RUs could match.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id>
```

Done when missing mapping fields block execution, dependency graph errors are reported, and the report does not infer RP membership.

### T005 - AC Intake and Readiness Classifier

Related features: F003, F005
Acceptance: AC-003, AC-005
Modules: `readiness`, `schema`

Read owner-authored RP AC, preserve stable AC IDs, classify each AC as `automatable`, `manual_only`, `partial`, `waived`, or `not_ready_for_generation`, and identify whether inputs, actions, expected outputs, side effects, and pass/fail rules are explicit.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id> --include-ac-readiness
```

Done when ambiguous AC are blocked from executable test drafting and never rewritten by the framework.

### T006 - Test Case Lifecycle Manager

Related feature: F005
Acceptance: AC-005
Modules: `testcase`

Implement draft package-neutral DSL test skeleton and draft executable DSL test artifact writing under `tests/draft/`. Generated executable drafts must use execution-focused DSL v1 fields: `dsl_version`, `test_case_id`, `status`, `revision`, `traceability`, `targets`, `scenario`, `setup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime`. Detect existing checked-in test artifacts for the same RP AC and create update proposals instead of overwriting.

Verification:

```bash
regress generate-tests --root <product-repo> --rp-id <rp-id> --mode draft
```

Done when checked-in DSL tests are protected and generated drafts include traceability source, revision, execution status, targets, execute outputs, expected_results, verify rules, evidence refs, and runtime policy.

### T007 - Expected Result Manager

Related feature: F006
Acceptance: AC-006
Modules: `expectedresult`

Draft expected-result artifacts from explicit RP AC, RP feature spec, package inputs, and source context. Enforce statuses `draft`, `blocked`, and `approved_for_regression`.

Verification:

```bash
regress draft-expected-results --root <product-repo> --rp-id <rp-id>
regress check-rp --root <product-repo> --rp-id <rp-id> --include-expected-results
```

Done when only approved expected results are eligible as regression truth.

### T008 - Execution Environment Resolver

Related features: F004, F007
Acceptance: AC-004, AC-007, AC-008
Modules: `environment`

Resolve `local_fixture`, `ci_ephemeral`, `sit_deployed`, and `evidence_only` execution modes from `rp_ru_mapping.yaml`. Block SIT execution unless deployment and environment readiness evidence exist.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env sit_deployed
```

Done when the command blocks before adapter execution if SIT readiness evidence is missing.

### T009 - Planning, Parameter, and Binding Resolver

Related feature: F007
Acceptance: AC-007, AC-008
Modules: `binding`

Resolve execution-focused DSL v1 `targets`, `setup.fixtures`, `execute[].with`, `execute[].outputs`, `expected_results`, `verify`, `evidence.required`, and `runtime` into an execution plan. The initial compatibility path may still read legacy `package_inputs`, `steps`, `oracles`, `assertions`, `evidence_required`, and `policy`, but new generation and new framework tests must target the execution-focused fields. Binding types or operations outside the selected or implemented provider families must fail as unsupported before execution.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --dry-run
```

Done when supported pilot targets, setup fixtures, execute inputs/outputs, expected results, verify rules, evidence refs, and runtime policy resolve into the execution plan, and unresolved fields fail fast with file path, test case ID, AC ID, section name, field path, provider family when applicable, and owner action.

### T009A - Parameter Set Reference Binding

Related feature: F007
Acceptance: AC-007, AC-008, AC-009
Modules: `binding`, `execution`, `evidence`, `report`

Implement the M1 parameterization subset for execution-focused DSL v1 using `parameters.ref` and `parameters.bind_as`. `parameters.ref` must point to a reviewed parameter set artifact. `parameters.bind_as` declares the namespace for `${param.<bind_as>.<field>}` references. Each referenced case must have a unique case ID and non-empty values. Runtime may resolve `${param.<bind_as>.<field>}` references from setup fixtures, execute inputs, expected results, verify rules, and evidence refs. Each case produces a separate run ID and run evidence with `parameter_case_id`; coverage counts the traced AC once per batch.

Verification:

```bash
./mvnw -q -Dtest='RegressionCommandTest#runExecutesExecutionFocusedDslV1ParameterSetRefAsSeparateRuns' test
./mvnw -q -Dtest='RegressionCommandTest,BindingResolverTest,DslTestCaseValidatorTest,CoverageReportServiceTest' test
```

Done when one DSL test with `parameters.ref` and `parameters.bind_as` resolves a reviewed two-case parameter set, produces two run evidence directories with the same test case ID and AC ID, distinct run IDs, recorded `parameter_case_id`, resolved safe `${param.<bind_as>.<field>}` refs, and batch/report coverage that counts the AC once. Inline `parameters.strategy`, inline `parameters.cases`, and `${parameters.<name>}` references are accepted only through an explicit legacy compatibility path until migrated.

### T010 - Provider Contract Registry and Dispatch

Related feature: F007
Acceptance: AC-007, AC-008
Modules: `provider`, `schema`

Introduce the provider capability registry and contract validator before adding more provider runtimes. The registry must define supported `provider_family` and `provider_type` combinations, required fields, supported actions, allowed execution modes, runtime support status, evidence outputs, and safety policy. Resolve validated provider contracts from provider defaults, RP-level overrides, and RU-level overrides. Dispatch `targets.<target_id>.runner`, `execute[].operation`, `setup.fixtures.<name>.type`, `expected_results.<name>.type`, `verify[].type`, and `evidence.required[]` through the registry-selected runtime. Fail before execution when a contract is missing, ambiguous, unsupported, unsafe, or only available through an unapproved escape hatch.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --dry-run
```

Done when provider contract resolution reports provider family, provider type, provider name, runtime support status, source level, action/type, affected RU, test case ID, AC ID, contract path, and owner action for unsupported, missing, ambiguous, unsafe, or unapproved escape-hatch capabilities. Execution dispatch must no longer require adding a new product-specific conditional to `ExecutionEngine`.

### T011 - Fixture Lifecycle Manager

Related feature: F007
Acceptance: AC-007, AC-008
Modules: `fixture`

Implement precondition checks, fixture setup and cleanup lifecycle, and postcondition checks for local and CI runs. Use provider contracts for M1 pilot fixture behavior such as file workspace setup, database seed/query/cleanup, message publish/consume cleanup, and configuration binding when those provider families are selected by the heterogeneous pilot. Fixture behavior outside selected or implemented provider families must fail as unsupported before execution. Record cleanup evidence even when execution fails.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env local_fixture
```

Done when selected pilot fixture providers can prepare and clean their declared state, and setup, cleanup, and cleanup failure state are written to run evidence.

### T012 - Provider Runtime Foundation and Pilot Provider Set

Related feature: F007
Acceptance: AC-007, AC-008
Modules: `execution`, `adapter`, `provider`

Implement execution of a prepared plan through validated adapter/provider contract configuration. The core executor and test case DSL stay package-type-neutral; providers own package-specific calls, messages, fixture operations, readiness probes, and actual-result capture through reusable, configurable contracts. External runner invocation is implemented only after a selected, approved escape-hatch need exists.

The pilot provider set is selected from `docs/02-architecture/07_heterogeneous_rp_support_capability_matrix.md` and should include only the reusable REST/gRPC, Kafka/NATS, DB fixture, K8s and VM readiness, and shell/file capabilities required by the selected heterogeneous RP. External runner is selected only when explicitly approved as an escape hatch.

Current framework verification support is narrower than the pilot target: REST and native descriptor-driven gRPC unary calls are supported, HTTP/status field, JSON path equality/absence, numeric tolerance, schema/contract, and multi-assertion response checks are supported, local/mock messaging is supported, native Kafka/NATS publish, NATS request/reply, consume/observe, and bounded cleanup drain dispatch is supported, JDBC DB fixture and DB row count assertions are supported, local/mock plus native K8s/VM bounded deployment readiness, K8s direct API deployment availability, K8s pod log capture, and VM SSH/WinRM command probes are supported, file/batch is supported, and approved command-runner external runner is supported as an escape hatch. Persistent broker purge if needed and real pilot environment evidence require separate implementation slices before they can satisfy full pilot acceptance.

Verification:

```bash
regress run --root <product-repo> --rp-id <pilot-rp-id> --env ci_ephemeral
```

Done when the pilot provider set can execute or validate one approved test per required provider family, preserve provider results and timeout state, and emit actual outputs under the run evidence directory.

### T013 - Expected-Result and Verify Engine

Related feature: F007
Acceptance: AC-007
Modules: `oracle`, `assertion`

Implement M1 expected-result loading and verify types required by the pilot, starting with `file`, `expected_result_artifact`, HTTP/status metadata checks, HTTP/status field checks, JSON path equality and absence checks, numeric tolerance checks, schema/contract checks, and `query_result` DB row count checks where approved expected-result artifacts, reviewed query refs, provider metadata, or inline decision rules are used. For structured output checks, normalize canonical `selector` into the assertion engine path model while retaining `path` and `json_path` only as compatibility aliases. For metadata-backed checks such as `response_status_equals`, require `expected` and provider evidence metadata, while allowing `actual` plus `selector` when the status is captured in a structured output. For the selected heterogeneous pilot, promote required verify types such as `invariant` only when their provider family and assertion implementation are selected and verified.

Expected-result or verify types outside selected or implemented provider families are rejected as unsupported before verify evaluation.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --test-case <tc-id>
```

Done when selected pilot verify types produce pass/fail results, and failures include expected-result ref or inline rule, expected ref when applicable, actual ref, decision rule, provider family when applicable, diff or mismatch summary, and test case trace.

### T014 - Evidence Writer

Related features: F007, F008
Acceptance: AC-007, AC-009
Modules: `evidence`

Write `evidence/runs/<run_id>/run.yaml`, logs, actual outputs, assertion results, observation results, postcondition results, cleanup evidence, and failure details. Evidence must include RP ID, AC ID, test case ID, run ID, RU refs, execution mode, environment ref, parameter case when applicable, resolved dependencies, resolved bindings, provider contracts used, provider family, adapter/provider result, assertion result, cleanup result, and final pass/fail status.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id>
```

Done when every execution path produces durable evidence, including failed and blocked runs.

### T014A - Batch Execution Evidence Fix

Related features: F007, F008
Acceptance: AC-007, AC-009
Modules: `cli`, `execution`, `evidence`

Fix execution evidence so one RP regression command can run multiple approved test cases without overwriting evidence. `regress run` must create one batch ID per RP execution and one unique run ID per approved test case. Run evidence remains test-case-level; batch evidence summarizes the RP execution.

Required evidence layout:

```text
evidence/batches/<batch_id>/batch.yaml
evidence/runs/<run_id>/run.yaml
```

Minimum `batch.yaml` fields:

```yaml
batch_id:
rp_id:
execution_mode:
environment_ref:
started_at:
finished_at:
status:
runs:
  - run_id:
    test_case_id:
    ac_id:
    status:
```

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env ci_ephemeral
```

Done when two approved tests in one RP produce two different run directories, no evidence files are overwritten, and CLI output includes the generated `batch_id`.

### T015 - Coverage and Traceability Reporter

Related feature: F008
Acceptance: AC-009
Modules: `report`, `evidence`

Calculate coverage as covered automatable RP-level AC divided by total automatable RP-level AC. RP release coverage is batch-level, not single-run-level. Link generated tests and evidence to RP ID, AC ID, test case ID, batch ID, and run ID. Exclude manual-only or waived AC only with approval records.

Coverage rules:

- Denominator is distinct RP AC classified as `automatable` or `partial`, plus any unapproved manual/waived exclusion that must still appear as a gap.
- Numerator is distinct AC IDs with at least one passed, traceable approved test run in the selected batch.
- Failed, blocked, missing-traceability, manual-only, waived, and duplicate AC coverage must not inflate the numerator.

Verification:

```bash
regress report --root <product-repo> --rp-id <rp-id> --batch-id <batch-id>
```

Done when coverage, traceability, evidence index, failure summary, and release review summary are review-ready for the selected batch. `--run-id` may remain available for debugging one test run, but it must not be used to determine RP release coverage.

### T016 - Maven Verify Framework and Provider-Family Integration Harness

Related features: F001-F008
Acceptance: AC-010 plus AC-001 through AC-009 as exercised through sample fixtures
Modules: Maven build, integration tests, sample Product Repo fixture, provider-family fixtures

Add the framework integration verification layer. Configure Maven Failsafe to run `*IT.java` tests during `./mvnw verify`. The integration tests shall use a sample Product Repo fixture, local/mock adapters, local/mock provider-family fixtures, and deterministic data. They shall exercise representative check, dry-run/run, report, provider capability registry validation, provider-family dry-run, unsupported-provider blocking, unapproved escape-hatch blocking, and provider evidence normalization flows without requiring SIT/UAT deployment.

Verification:

```bash
./mvnw test
./mvnw verify
```

Done when `./mvnw test` remains the fast unit/component framework suite, `./mvnw verify` runs the sample Product Repo integration suite plus provider capability registry and provider-family contract verification, and fixture or mock provider evidence is not presented as downstream Product/RP release evidence.

### T017 - Pilot RP Validation Harness

Related features: F001-F008
Acceptance: AC-001 through AC-010
Modules: all M1 modules

Run the full workflow against the selected heterogeneous pilot RP after owner-provided RP artifacts exist.

Verification:

```bash
regress check-readiness --root <product-repo>
regress check-rp --root <product-repo> --rp-id <pilot-rp-id>
regress generate-tests --root <product-repo> --rp-id <pilot-rp-id> --mode draft
regress run --root <product-repo> --rp-id <pilot-rp-id> --env ci_ephemeral
regress report --root <product-repo> --rp-id <pilot-rp-id> --batch-id <batch-id>
```

Done when the pilot RP evidence package shows greater than 80% coverage for automatable RP AC or reports explicit approved exclusions.

## Dependency Order

```text
T000 -> T000A -> T001 -> T002 -> T003
                  |
                  +-> T003A -> T003B -> T004 -> T008 -> T009 -> T009A -> T010 -> T011 -> T012 -> T013 -> T014 -> T014A
                  |                                                                                 |
                  |                                                                                 v
                  |                                                                                T015 -> T016 -> T017
                  |
                  +-> T005 -> T006 -> T007
```

Parallelizable after T003, while execution runtime remains blocked until T003A and T003B are green:

- T005 AC readiness.
- T007 expected-result manager.
- T015 report formatting skeleton.

## Implementation Gates

Gate 1 - Foundation ready:

- T000, T001, T002, T003, T003A, T003B, and T004 complete.
- F001/F002/F004 can be used to initialize and check Product Repo and RP readiness, and DSL v1 artifacts can be validated and consumed by run/report before provider runtime expansion.

Gate 2 - Generation ready:

- T005, T006, and T007 complete.
- Pilot RP has owner-authored AC and mapping.
- Draft tests and expected results are reviewable but not regression truth until approved.

Gate 3 - Execution ready:

- T008 through T013 complete.
- T003A and T003B remain green after any generator, sample fixture, report, or provider runtime change.
- Approved tests and expected results exist.
- Environment readiness and deployment evidence exist where required.

Gate 4 - Release evidence ready:

- T014, T014A, T015, T016, and T017 complete.
- Coverage, traceability, failures, waivers, and evidence package are review-ready.

## Risks and Controls

| Risk | Control |
|---|---|
| Agent invents AC or business behavior | F003 gate preserves owner-authored AC only. |
| Tests are regenerated on every run | Execution reads checked-in DSL tests from `tests/approved/`; generation is separate. |
| Provider runtime is implemented before the DSL contract is stable | T003A and T003B block provider runtime migration until execution-focused DSL validation, generator output, dry-run reporting, and CLI run/report consumption are verified. |
| SIT run starts before deployment readiness | Environment resolver blocks `sit_deployed` before adapter execution. |
| Multi-RU order is ambiguous | Mapping validator requires dependency graph and rejects scalar order-only execution planning. |
| RP/RU membership is inferred incorrectly | Mapping validator consumes human-authored `rp_ru_mapping.yaml` only. |
| Expected results become truth without review | Expected-result manager blocks unapproved artifacts. |
| Evidence cannot support release review | Evidence writer and reporter require RP/AC/test/run traceability. |
| Multi-test RP execution overwrites evidence | Batch execution creates one batch per RP run and one unique run ID per approved test. |
| Single-run report is mistaken for RP coverage | RP release coverage is calculated only from batch-level evidence. |
| Maven framework verification is mistaken for downstream RP release evidence | AC-010, ADR-005, ADR-006, and T016 separate Surefire/Failsafe evidence and mock provider evidence from Product Repo RP evidence. |

## Completion Criteria for M1

- Product developer can initialize/check the Product Repo and RP.
- Framework verification passes through `./mvnw test` and `./mvnw verify` without SIT/UAT deployment.
- Pilot RP artifacts are complete and human-authored where required.
- Agent drafts tests only from ready AC and execution context.
- Approved tests execute without regeneration by default.
- Evidence records batch summary, run-level inputs, outputs, logs, assertions, cleanup, and failures.
- Coverage is greater than 80% of automatable RP-level AC or exclusions are approved.
