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

## Staged Readiness

Ready to implement now:

- F001 Product Repo Bootstrap CLI and Readiness Agent Skill.
- F002 Release Package Creation Guide and Completeness Check.
- F004 RP/RU Mapping Intake and Completeness Check, using sample or pilot mappings.

Ready after pilot RP artifacts exist:

- F003 RP Feature Spec and AC Intake.
- F005 Agent AC and Execution Context Readiness with DSL Test Drafting.
- F006 Agent Expected Result Drafting.
- F007 Release Package DSL Test Execution.
- F008 Coverage and Evidence Package.

Pilot RP owner must supply RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, expected-result approval owner, adapter mode, dependency graph, adapter/provider contracts, any required binding, fixture, oracle, assertion, or observation provider capability, and any approved external runner escape-hatch need.

## Current Implementation Status Snapshot

This snapshot separates framework verification progress from pilot acceptance progress. Update it when a provider runtime or verification case changes.

| Area | Current Status | Evidence / Gate | Next Work |
|---|---|---|---|
| Product Repo and RP skeleton | Implemented for framework verification | CLI tests and sample fixture verification | Harden cross-artifact readiness as pilot artifacts arrive. |
| AC intake and DSL drafting | Implemented for framework readiness/draft flows | Unit/component and integration tests | Apply to owner-provided pilot RP AC. |
| Batch/run evidence and coverage | Implemented for sample and CLI flows | `./mvnw verify` and report tests | Validate against real pilot RP batch evidence. |
| File/batch runtime | Supported | Provider registry dispatch and shell/file tests | Keep as reusable provider. |
| REST/gRPC request-response runtime | Supported for REST and native descriptor-driven gRPC unary calls | Request/response provider, native gRPC invoker, runtime registry, CLI preflight, and evidence tests | Add richer response assertions and pilot endpoint evidence. |
| Messaging runtime | Supported for local/mock plus native Kafka/NATS publish, consume/observe, and bounded cleanup drain dispatch | Messaging provider contract, runtime registry, native transport, CLI preflight, and evidence tests | Add owner broker-backed pilot validation and persistent broker purge only if the selected RP requires it. |
| DB fixture runtime | Supported for JDBC fixture lifecycle | DB setup/query/cleanup tests with `sql_ref`, cleanup strategy, and isolation key | Add DB-row oracle/assertion types if pilot requires them. |
| Deployment readiness runtime | Supported for local/mock plus native K8s/VM bounded readiness probes, K8s pod log capture, and VM SSH/WinRM command probes | Readiness provider, runtime registry, and provider contract tests with version, timeout, target refs, bounded log tail refs, command refs, and output refs | Add direct kube API and real pilot environment validation. |
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

Implement draft package-neutral DSL test skeleton and draft executable DSL test artifact writing under `tests/draft/`. Generated executable drafts must include `dsl_version` and all required DSL fields. Detect existing `tests/approved/` artifacts for the same RP AC and create update proposals instead of overwriting.

Verification:

```bash
regress generate-tests --root <product-repo> --rp-id <rp-id> --mode draft
```

Done when checked-in approved DSL tests are protected and generated drafts include source refs, source fingerprint, revision, and status.

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

Resolve expected-result refs, data selection strategy, parameterized cases, dependencies, name-keyed package input bindings, runtime paths, environment refs, and step output placeholders from approved test cases. The initial file/batch path supports `input_file`, `dataset`, and `db_seed`. For the selected heterogeneous pilot, bindings required by selected provider families, such as `api_payload`, `message_event`, `config_file`, `env_var`, and `existing_state`, must become supported when their provider contracts are implemented. Binding types outside the selected or implemented provider families must fail as unsupported before execution.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --dry-run
```

Done when supported pilot bindings resolve into the execution plan, and unresolved bindings, data selection, parameter cases, or dependencies fail fast with file path, test case ID, AC ID, parameter case when applicable, binding or section name, binding type when applicable, provider family when applicable, and owner action.

### T010 - Provider Contract Registry and Dispatch

Related feature: F007
Acceptance: AC-007, AC-008
Modules: `provider`, `schema`

Introduce the provider capability registry and contract validator before adding more provider runtimes. The registry must define supported `provider_family` and `provider_type` combinations, required fields, supported actions, allowed execution modes, runtime support status, evidence outputs, and safety policy. Resolve validated provider contracts from provider defaults, RP-level overrides, and RU-level overrides. Dispatch adapter/action, `bind_as`, fixture action, oracle type, assertion type, and observation type through the registry-selected runtime. Fail before execution when a contract is missing, ambiguous, unsupported, unsafe, or only available through an unapproved escape hatch.

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

Current framework verification support is narrower than the pilot target: REST and native descriptor-driven gRPC unary calls are supported, local/mock messaging is supported, native Kafka/NATS publish, consume/observe, and bounded cleanup drain dispatch is supported, JDBC DB fixture is supported, local/mock plus native K8s/VM bounded deployment readiness, K8s pod log capture, and VM SSH/WinRM command probes are supported, file/batch is supported, and approved command-runner external runner is supported as an escape hatch. Direct kube API, richer assertion/oracle providers, persistent broker purge if needed, and real pilot environment evidence require separate implementation slices before they can satisfy full pilot acceptance.

Verification:

```bash
regress run --root <product-repo> --rp-id <pilot-rp-id> --env ci_ephemeral
```

Done when the pilot provider set can execute or validate one approved test per required provider family, preserve provider results and timeout state, and emit actual outputs under the run evidence directory.

### T013 - Oracle and Assertion Engine

Related feature: F007
Acceptance: AC-007
Modules: `oracle`, `assertion`

Implement M1 oracle loading and assertion types required by the pilot, starting with `golden_file` and `expected_result_artifact` where approved expected-result artifacts are used. For the selected heterogeneous pilot, promote required oracle and assertion types such as response status or JSON path checks, `query_result` DB checks, `schema`, `contract`, `invariant`, `tolerance`, or `absence` only when their provider family and assertion implementation are selected and verified.

Oracle or assertion types outside selected or implemented provider families are rejected as unsupported before assertion evaluation.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --test-case <tc-id>
```

Done when selected pilot assertion types produce pass/fail results, and failures include oracle ref or inline rule, expected ref when applicable, actual ref, decision rule, provider family when applicable, diff or mismatch summary, and test case trace.

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
T000 -> T001 -> T002 -> T003 -> T004
                  |
                  +-> T005 -> T006 -> T007
                  |
                  +-> T008 -> T009 -> T010 -> T011 -> T012 -> T013 -> T014 -> T014A
                                                                  |
                                                                  v
                                                                 T015 -> T016 -> T017
```

Parallelizable after T003:

- T004 mapping validation.
- T005 AC readiness.
- T007 expected-result manager.
- T008 environment resolver.
- T015 report formatting skeleton.

## Implementation Gates

Gate 1 - Foundation ready:

- T000, T001, T002, T003, and T004 complete.
- F001/F002/F004 can be used to initialize and check Product Repo and RP readiness.

Gate 2 - Generation ready:

- T005, T006, and T007 complete.
- Pilot RP has owner-authored AC and mapping.
- Draft tests and expected results are reviewable but not regression truth until approved.

Gate 3 - Execution ready:

- T008 through T013 complete.
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
