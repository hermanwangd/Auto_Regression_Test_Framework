# 16. Implementation Plan

Status: v0.2 Implementation In Progress - Not Yet Accepted

This plan implements Auto Regression Test Framework v0.2 as a product-agnostic full pre-release execution framework while keeping Product/RP/RU interpretation outside the framework core. Product Repo readiness and Agent Skill translation remain important, but v0.2 framework implementation is judged by the generic execution contract: DSL, Execution Profiles, Provider Instances, Provider Contracts, Environment Bindings, suite selection, fixtures, provider runtime, verify catalog, evidence, result schema, secret guardrails, and plugin contracts.

This is an implementation plan, not the framework verification strategy itself. Framework Verification is defined in `docs/07-validation-evidence/07_regression_test_plan.md`, including unit/component tests and sample generated-artifact integration verification. Real downstream RP regression execution remains a release-package validation flow after owner-provided RP artifacts are translated into framework-readable artifacts.

Passing current Maven tests or documentation gates does not mean v0.2 is complete. It means the repository has a verification harness and partial runtime coverage that can be used to drive the remaining implementation slices. v0.2 is accepted only after the slices below are implemented, verified, and reviewed against AC-001 through AC-018.

## Entry Criteria

- Product, RP, and RU responsibilities are accepted.
- RP-level AC are the release coverage denominator.
- ADR-005 is accepted: Framework Verification and RP Regression Execution are separate execution lines.
- ADR-006 and ADR-013 are accepted: heterogeneous RP support is handled through Provider Contracts, Provider Instances, Environment Bindings, a provider capability registry, reusable built-in providers, and a governed external runner escape hatch.
- ADR-008 is accepted: framework core is product-topology agnostic and consumes generated suite/run/Execution Profile/Provider Instance/Provider Contract/Environment Binding artifacts.
- ADR-009 is accepted: new DSL metadata uses `labels` and `source_refs` instead of required product-specific `traceability.*` fields.
- ADR-010 is accepted: v0.2 is the feature-complete pre-release execution framework contract, not a minimum MVP.
- ADR-012 is accepted: the framework public interface is documented before runtime implementation and test-plan expansion, with controlled breaking changes allowed before v1.0.
- Minimum RP artifacts are defined: `package.yaml`, `rp_feature_spec.md`, `rp_ru_mapping.yaml`, `acceptance_criteria.md`, `tests/`, `expected-results/`, `traceability.md`, and `evidence_index.md`.
- Architecture design defines Spring Boot 3.x / Java 17+ AP-level components, extension points, provider types, internal package boundaries, CLI commands, storage paths, execution modes, failure handling, and AC coverage.
- Execution-focused DSL v0.2 is defined in the artifact contracts and must be validated and proven through CLI `run`, standard result JSON, evidence collection, and `report --batch-id` before provider runtime expansion is accepted.
- Before any DSL, AP, provider runtime, evidence, or report implementation slice starts, the feature/spec, architecture design, artifact contract, AC, implementation plan, and test plan must be aligned on behavior, non-goals, required/conditional/prohibited fields, AP ownership, happy/failure/boundary paths, and verification evidence.

## Staged Readiness

Ready to implement now for framework maturity:

- F007/F008/F011 framework foundation: execution-focused DSL v0.2 parser/validator, Execution Profile resolver, Provider Instance resolver, Provider Contract validator, Environment Binding resolver, suite selection, parameter binding, fixture lifecycle, provider capability registry, plugin contract validation, secret guardrail, result schema, evidence schema, and run/report consumption.
- Provider public-interface verification for request/response, messaging, DB fixture, deployment readiness, file/batch, and external runner escape-hatch behavior using local/CI mock-stub-ephemeral or injectable framework fixtures, with `runtime_mode` recorded in dry-run and evidence.
- Framework verification hardening so `./mvnw test` and `./mvnw verify` prove AC-001 through AC-018 without requiring SIT/UAT deployment or Phase 2 Agent Skill completion.

Ready in the next stage after framework contracts are stable:

- F001 Product Repo Bootstrap, RU Repo Discovery, and Readiness Agent Skill hardening.
- F002 Release Package Creation Guide and Completeness Agent Skill.
- F003 RP Feature Spec and AC Intake Agent Skill for owner-authored and draft/proposed specs.
- F004 Agent Skill Product Mapping Translation, using pilot mappings to generate framework-readable artifacts.
- F005 Agent AC and Execution Context Readiness with DSL Test Drafting.
- F006 Agent Expected Result Drafting.
- F007/F008 downstream RP execution and coverage using real pilot RP artifacts.

Pilot RP owner must supply RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, expected-result approval owner, provider intent, dependency graph, required provider capabilities, and any approved external runner escape-hatch need. The Agent Skill must translate these into `suite_manifest.yaml`, `run_plan.yaml`, Execution Profiles, Provider Instances, Environment Bindings, Provider Contracts, parameter set refs when needed, `traceability_map.yaml`, and `mapping_explanation.md` before downstream RP execution invokes the framework core.

## v0.2 Implementation Slices

These slices supersede the earlier minimum M1/v1 execution-slice framing.

| Slice | Scope | Acceptance |
|---|---|---|
| S01 DSL and Validation Core | `test_case_dsl.v0.2.schema.yaml`, required/conditional/prohibited fields, compatibility path, secret guardrail | AC-001, AC-014, AC-017 |
| S02 Profile, Provider Resolution, Binding, and Suite Selection | Execution Profile schema, Provider Instance schema, Provider Contract schema, Environment Binding schema, runtime mode validation, local/CI dependency substitution policy, selected profile compatibility, test/suite/tag/profile CLI selection | AC-002, AC-015, AC-018 |
| S03 Parameter and Variable Resolution | operation-level `parameters[].ref`, `parameters[].bind_as`, per-parameter result/evidence, source-ref coverage de-duplication | AC-003 |
| S04 Fixture Lifecycle | Fixture catalog, scope, cleanup policy, setup/cleanup evidence, unsafe fixture blocking | AC-004 |
| S05 Provider Public Interface and Runtime Catalog | `shell_command`, `rest_client`, `grpc_client`, `jdbc_database`, `nats_messaging`, `kafka_messaging`, `kubernetes_runtime`, `vm_runtime`, and `external_runner` Provider Contracts, Provider Instances, Environment Bindings, runtime modes, and provider_type registry metadata | AC-005, AC-006, AC-007, AC-008, AC-016 |
| S06 Verify and Polling Engine | Basic, structure, collection, numeric/time, file, state, event, custom verify types; DB/event polling | AC-009, AC-010, AC-011, AC-016 |
| S07 Expected Results, Evidence, and Result Schema | Expected-result types, evidence collector, masking, result JSON, technical failure classification | AC-012, AC-013, AC-014 |
| S08 Report and Evidence Review | `regress report` coverage and evidence review over standard results without topology interpretation | AC-012, AC-013, AC-017 |
| S09 Framework Verification Harness | Unit/component tests, sample generated-artifact integration tests, plugin catalog tests, CLI smoke | AC-001 through AC-018 |

Implementation should proceed slice by slice. A slice is not ready for code until baseline, feature spec, artifact contracts, AC, implementation plan, and test plan describe the same v0.2 behavior.

## Current Implementation Status Snapshot

This snapshot separates framework verification progress from accepted v0.2 delivery progress. Update it when a provider runtime or verification case changes. Treat `Implemented` here as forbidden unless the slice is code-complete, verified, and accepted against its AC; use `Partial` for current framework gates, sample fixtures, or local/mock-only support.

| Area | Current Status | Evidence / Gate | Next Work |
|---|---|---|---|
| Product Repo and RP skeleton | Partial - framework verification fixture only | CLI tests and sample fixture verification | Do not count as v0.2 framework runtime completion; harden only when Phase 2 Agent Skill work resumes. |
| AC intake and DSL drafting | Partial - support flows plus initial execution-focused DSL consumption | Unit/component tests, CLI run/report test, DSL v0.2 contract review, runtime evidence metadata, and framework maturity coverage gate | Complete S01 through S03 before claiming DSL/runtime readiness. |
| S02 profile, provider resolution, binding, and suite selection | Partial - `regress run` filters approved tests by `--test-case`, `--tag`, and generated `suite_manifest.yaml` `--suite`, blocks empty selections, blocks incompatible `compatible_profiles`, blocks missing Provider Instances, missing Provider Contracts, missing Environment Bindings, missing required binding keys, generated Execution Profiles missing required fields, unsupported execution modes, and `sit`/`preprod` targets without readiness refs | Existing CLI selection tests plus new Provider Contract / Provider Instance / Environment Binding validation tests | Continue S02 with suite manifest v0.2 schema alignment and full AC-002 review before marking accepted. |
| Framework maturity gates | Partial - gates now require 100% AC path automation mapping, explicit Maven JaCoCo bundle thresholds for instruction 99%, branch 90%, line 98%, method 100%, and class 100%, an explicit Spring launcher exclusion for `RegressionApplication`, and a separate v0.2 delivery profile for critical-class line/branch scope; passing the interim gate is not delivery acceptance while implementation slices and critical-class line coverage remain incomplete | `FrameworkMaturityCoverageGateTest`, `FrameworkPublicInterfaceContractTest`, `./mvnw test`, `./mvnw verify`, `./mvnw -Pv02-delivery-coverage verify`, and current JaCoCo progress metrics | Keep interim gates green while replacing gate-only coverage with real slice implementation evidence and making the v0.2 delivery coverage profile pass only after critical framework class line coverage reaches the committed goal. |
| Batch/run evidence and coverage | Partial - sample and CLI flows only | `./mvnw verify` and report tests | Validate evidence behavior against accepted v0.2 DSL, generated artifacts, and pilot-like scenarios. |
| File/batch runtime | Partial - local/framework verification support | Provider registry dispatch and shell/file tests | Confirm contract completeness during S05/S07. |
| REST/gRPC request-response runtime | Partial - framework verification support for REST and descriptor-driven gRPC unary calls | Request/response provider, native gRPC invoker, runtime registry, CLI preflight, response assertion, schema/contract assertion, and evidence tests | Re-audit against S05/S06/S07 before marking accepted. |
| Messaging runtime | Partial - local/mock plus native Kafka/NATS paths under framework tests, including public-run heterogeneous evidence for Kafka publish/observe/cleanup and NATS request/reply/observe/cleanup | Messaging provider contract, runtime registry, native transport, CLI preflight, `HeterogeneousProviderRuntimeIT`, and evidence tests | Re-audit unsupported modes, real pilot broker validation, and coverage gates before marking accepted. |
| DB fixture runtime | Partial - JDBC fixture/query/count support under framework tests | DB setup/query/cleanup tests with `sql_ref`, cleanup strategy, isolation key, query-result expected-result readiness, and DB verify evidence | Re-audit cleanup guarantees and evidence during S04/S06/S07. |
| Deployment readiness runtime | Partial - local/mock plus bounded K8s/VM probes under framework tests | Readiness provider, runtime registry, and provider contract tests with version, timeout, target refs, API server refs, bounded log tail refs, command refs, and output refs | Re-audit environment binding and failure evidence during S02/S05/S07. |
| External runner escape hatch | Partial - governed escape-hatch path under tests | Contract gating and mapped evidence tests | Re-audit policy, isolation, and evidence before accepted support. |
| Heterogeneous pilot validation | Not started | Requires owner-provided Product/RP artifacts plus generated framework artifacts | Run T017 after pilot artifacts are translated. |

## Task Backlog

### T000 - Test Boundary and Verification Plan Alignment

Related feature: Cross-cutting verification governance
Acceptance: AC-012, AC-013, AC-017
Modules: docs, build lifecycle

Keep ADR, spec, AC, architecture, test plan, and implementation plan aligned on the difference between Framework Verification and RP Regression Execution.

Verification:

```bash
rg "Framework Verification|RP Regression Execution|mvnw test|mvnw verify|regress run" docs
```

Done when the docs consistently state that `./mvnw test` and `./mvnw verify` validate this framework, while downstream Product/RP validation first generates framework-readable artifacts and then invokes the generic runner to write RP evidence.

### T000A - Pre-Implementation Documentation Alignment Gate

Related feature: Cross-cutting DSL/AP/provider governance
Acceptance: AC-001 through AC-018
Modules: docs, planning

Before changing DSL validation, generation, execution, evidence, reporting, provider runtime, or AP boundaries, update and review the feature/spec, architecture design, artifact contract, AC, implementation plan, and test plan. The review must confirm consistent field ownership, required vs conditional field rules, prohibited governance and legacy fields, AP consumers, provider contract boundary, acceptance behavior, and verification evidence.

Verification:

```bash
rg -n "call_ru|target_ru_id|package_inputs|oracles|approval_status|release_gate|risk_approval" docs/01-specs docs/02-architecture docs/03-acceptance docs/04-planning docs/07-validation-evidence
rg -n "dsl_version|targets|setup|execute|expected_results|verify|evidence|required|conditional|prohibited" docs/01-specs docs/02-architecture docs/03-acceptance docs/07-validation-evidence
```

Done when legacy terms appear only in migration/prohibited-field contexts, governance-heavy fields are absent from DSL examples, and the next implementation task can point to specific AC and verification cases.

### T000B - Framework Public Interface Contract

Related feature: F007, F008, F011
Acceptance: AC-015, AC-016, AC-012, AC-013
Modules: docs, CLI contract, DSL contract, Provider Contract, Provider Instance, Environment Binding, artifact contracts

Document the public interface used to invoke and configure the framework before runtime/provider implementation starts. The interface contract must define stable commands, required options, optional options, exit-code semantics, stable stdout keys, DSL/test definition fields, Execution Profile fields, Provider Contract fields, Provider Instance fields, Environment Binding fields, input artifact locations, output evidence locations, and which Product Repo/Agent Skill commands are next-stage support commands rather than framework runtime gates.

Verification:

```bash
rg -n "Framework Public Interface|Stable Interface Families|DSL and Test Definition Interface|Provider Runtime Configuration" docs/02-architecture/contracts/framework_usage_interface.v0.2.md
rg -n "FWK-013|framework_usage_interface|Framework Public Interface" docs/01-specs docs/02-architecture docs/04-planning docs/07-validation-evidence
```

Done when implementation slices and framework verification tests reference the documented public interface instead of inventing command names, option names, DSL fields, Provider Contract / Provider Instance / Environment Binding fields, output keys, or evidence paths.

### T000C - Track A Framework Contract Baseline

Related feature: F007, F008, F011
Acceptance: AC-001, AC-002, AC-012, AC-013, AC-014, AC-015, AC-016, AC-017, AC-018
Modules: docs, contract schemas, samples

Complete Track A before provider runtime expansion. Track A must align feature/spec, architecture, artifact contracts, AC, test plan, user guide, ADRs, and samples around the DSL Test Case -> Provider Instance -> Provider Contract -> Environment Binding model. It must define `regress validate`, `regress run --dry-run`, `regress report`, validation error taxonomy, secret guardrails, P0 provider/verify catalog, result/evidence contract, and sample artifacts. Track A must not implement full WireMock, JDBC, NATS, K8s, VM, external-runner runtime, or Phase 2 Agent Skill behavior.

Verification:

```bash
ruby -e 'require "yaml"; Dir["docs/02-architecture/contracts/**/*.yaml","samples/**/*.yaml"].each { |f| YAML.load_file(f) }'
ruby -e 'require "json"; Dir["samples/**/*.json"].each { |f| JSON.parse(File.read(f)) }'
rg -n "Provider Contract|Provider Instance|Environment Binding|regress validate|run --dry-run" docs/00-intake-scope docs/01-specs docs/02-architecture docs/03-acceptance docs/04-planning docs/05-decisions-adr docs/07-validation-evidence docs/09-operations samples
git diff --check
```

Done when contract docs and samples parse, user-facing terminology is Provider-based, and Track A is clearly described as contract-complete but runtime-not-complete.

### T000D - Track B Golden E2E Implementation Plan

Related feature: F007, F008, F011
Acceptance: AC-001, AC-002, AC-004, AC-005, AC-009, AC-012, AC-013, AC-014, AC-015, AC-016, AC-017
Modules: docs, CLI contract, sample artifacts, framework verification

Plan the Golden E2E implementation slice that proves one complete framework lifecycle through public CLI commands and checked-in sample artifacts. Track B uses only the framework-owned `sample_fake_provider`; it must not implement WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, external runner, Oracle, DB2, SIT, downstream product deployment, Phase 2 Agent Skill, or release governance.

Verification:

```bash
test -f docs/04-planning/18_track_b_golden_e2e_implementation_plan.md
test -f samples/golden_e2e/suite_manifest.yaml
test -f samples/golden_e2e/provider_contracts/sample_fake_provider.yaml
ruby -e 'require "yaml"; Dir["samples/golden_e2e/**/*.yaml"].each { |f| YAML.load_file(f) }'
ruby -e 'require "json"; Dir["samples/golden_e2e/**/*.json"].each { |f| JSON.parse(File.read(f)) }'
```

Done when the plan lists Golden E2E flow, fake provider boundary, sample artifacts, CLI behavior, runtime lifecycle, result/evidence requirements, happy/failure tests, DoR, DoD, first PR plan, work breakdown, risks, open questions, task breakdown, file path proposal, acceptance criteria, and first PR checklist.

### T000E - Track C Provider Capability Implementation Plan

Related feature: F007, F008, F011
Acceptance: AC-004, AC-006, AC-007, AC-008, AC-009, AC-010, AC-011, AC-012, AC-013, AC-014, AC-015, AC-016
Modules: docs, provider capability samples, provider runtime planning

Plan the selected v0.2 P0 provider capability runtime after Track B proves the reusable lifecycle. Track C includes only WireMock, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and evidence. Track C must not implement MockServer, Oracle/DB2 Testcontainers, container runner, K8s job runner, ReportPortal, Pact, OpenAPI/AsyncAPI generation, Flyway/Liquibase, release governance, waiver workflow, release gate, Phase 2 Agent Skill, RP/RU topology interpretation, or non-P0 providers.

Verification:

```bash
test -f docs/04-planning/19_track_c_provider_capability_implementation_plan.md
test -f samples/provider_capability/suite_manifest.yaml
test -f samples/provider_capability/wiremock/provider_contract.yaml
test -f samples/provider_capability/jdbc/provider_contract.yaml
test -f samples/provider_capability/nats/provider_contract.yaml
ruby -e 'require "yaml"; Dir["samples/provider_capability/**/*.yaml"].each { |f| YAML.load_file(f) }'
ruby -e 'require "json"; Dir["samples/provider_capability/**/*.json"].each { |f| JSON.parse(File.read(f)) }'
```

Done when the plan lists P0 capability inventory, WireMock/JDBC/NATS/compare/polling/evidence scope, CLI behavior, sample artifacts, happy/failure tests, DoR, DoD, first PR plan, work breakdown, risks, open questions, task breakdown, file path proposal, acceptance criteria, immediate first PR checklist, and P1/P2 backlog split.

### T001 - Product Repo Bootstrap, RU Repo Discovery, and Readiness Agent Skill

Related feature: F001
Support AC: SUP-AC-001
Support evidence: EVD-001
Modules: `cli`, `productrepo`, readiness agent skill

Implement `regress init-product-repo --root <path>` to create the agreed lifecycle folders and starter locations. Implement `regress check-readiness --root <path> --format yaml|json` to emit a machine-readable readiness report. Provide a readiness agent skill that reads the report and explains status, missing items, owner actions, and next steps. The CLI must be idempotent and must not overwrite existing content. The agent skill may scan explicitly provided RU implementation repos and create draft/proposed docs or spec artifacts only when invoked with an explicit write action. It must preserve source refs, assumptions, confidence notes, and review status, and it must not invent formal RP scope, RP AC, or RP/RU membership.

Verification:

```bash
regress init-product-repo --root <tmp-product-repo>
regress check-readiness --root <tmp-product-repo> --format yaml
regress check-readiness --root <tmp-product-repo> --rp-id <rp-id> --write-report
agent product-repo-readiness --report <tmp-product-repo>/docs/08-release/release-packages/<rp-id>/evidence/readiness/readiness.yaml
agent product-repo-bootstrap --root <tmp-product-repo> --scan-ru-repo <ru-repo> --write-draft-spec
```

Done when missing folders are created or reported, readiness output includes pass/fail status, missing items, owner action, and next required step, and the agent skill translates the readiness report plus optional RU repo scan evidence into owner-actionable guidance and draft/proposed docs/spec artifacts with source refs.

### T002 - RP Skeleton and Completeness Agent Skill

Related feature: F002
Support AC: SUP-AC-002
Support evidence: EVD-002
Modules: `cli`, `discovery`, `schema`

Implement `regress init-rp` and `regress check-rp` for the RP folder contract under `docs/08-release/release-packages/<rp_id>/`. Provide an agent skill path that can scaffold RP artifact drafts from owner inputs and optional RU repo scan findings while keeping every generated artifact in draft/proposed status until reviewed.

Verification:

```bash
regress init-rp --root <product-repo> --rp-id <pilot-rp-id> --package-type <package-type>
regress check-rp --root <product-repo> --rp-id <pilot-rp-id>
agent rp-create --root <product-repo> --rp-id <pilot-rp-id> --from-owner-input <input.yaml> --write-drafts
```

Done when required RP files and folders are present or reported as completeness gaps with owner action.

### T003 - Framework Artifact Schema Parser

Related features: F007, F008, F011
Acceptance: AC-001, AC-002, AC-013, AC-016
Modules: `schema`, `artifact`, `validation`

Implement typed parsers for framework-runtime artifacts: DSL test cases, `suite_manifest.yaml`, `run_plan.yaml`, Execution Profiles, Provider Instances, Provider Contracts, Environment Bindings, expected results, traceability maps, result JSON, and evidence records. Start with YAML/Markdown front matter or embedded YAML blocks supported by the artifact contracts. DSL parsing must validate `dsl_version`, required fields, conditionally required fields, allowed enum values, `targets.<target>.provider_id`, `targets.<target>.profile`, operation `parameters[].ref`, operation `parameters[].bind_as`, and output refs. Provider Contract parsing must require explicit `provider_type` and validate allowed runtime modes, allowed operations, allowed `bind_as` values, required binding keys, output refs, evidence outputs, failure codes, cleanup strategy, escape-hatch approval metadata when applicable, and unsupported configuration. Provider Instance parsing must validate that the instance conforms to the referenced Provider Contract shape. Execution Profile parsing must validate local/CI dependency provisioning policy, including Testcontainers or equivalent provisioner, dependency type, image/catalog ref, startup timeout, readiness check, cleanup scope, and output binding keys. Environment Binding parsing must validate selected `runtime_mode`, local/CI mock-stub-ephemeral replacement refs, generated binding outputs, and SIT/preprod native-dependency restrictions.

Product-side readiness artifacts such as `package.yaml`, `rp_feature_spec.md`, and `rp_ru_mapping.yaml` are parsed by the next-stage Phase 2 support path unless they are used only as sample fixture inputs for framework verification.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id> --strict-schema
```

Done when schema errors identify file path, field path, severity, owner action, and whether the error blocks generation, execution, or release evidence.

### T003A - Execution-Focused DSL v0.2 Validation Gate

Related features: F007, F008, F011
Acceptance: AC-001, AC-003, AC-014, AC-016, AC-017
Modules: `schema`, `testcase`, `cli`

Implement the execution-focused DSL v0.2 validation gate before provider runtime migration. The validator must accept the v0.2 semantic field set from `docs/02-architecture/06_artifact_contracts.md`, preserve legacy sample readability through an explicit compatibility path, and block new execution-focused artifacts that contain legacy-only fields, raw secrets, unsupported plugin refs, or governance-heavy fields.

The gate validates:

- Always-required fields: `dsl_version`, `test_case_id`, `status`, `revision`, `source_refs.acceptance_criteria`, `targets`, `scenario`, `execute`, `verify`, `evidence`, and `runtime`; `labels` and `compatible_profiles` are optional unless the selected report or profile needs them.
- Conditional fields: `setup.fixtures` when precondition data or mutated state is needed, `expected_results` when verify rules use approved artifacts or reusable truth sources, `setup.fixtures.<name>.cleanup_ref` for state mutation, explicit and uniquely named `execute[]` items, `execute[].with`, `execute[].outputs`, `verify[].actual` when a verify rule reads captured output, `verify[].selector` for structured checks, provider metadata for metadata-backed rules such as `response_status_equals`, `verify[].target/query/event`, `verify[].options`, selected profile, environment binding, and result/evidence refs.
- Parameterization fields when used: `parameters.ref`, `parameters.bind_as`, a readable reviewed parameter set, unique case IDs in the referenced set, non-empty case values, and resolvable `${param.<bind_as>.<field>}` references.
- Supported operations must come from the referenced Provider Contract, such as `run_batch`, `execute_command`, `http_request`, `unary_call`, `publish_message`, `consume_message`, `request_reply_message`, `execute_script`, `query`, `check_deployment_ready`, `run_command`, `run_and_collect`, `load_stubs`, and `verify_requests`.
- Supported verify rules: basic, structure, collection, numeric, file, state, and event checks defined in the artifact contract.
- Prohibited fields: legacy-only fields such as `call_ru`, `target_ru_id`, `package_inputs`, and `oracles`, plus approval, waiver, release gate, and risk approval fields.

Verification:

```bash
./mvnw -q -Dtest='DslTestCaseValidatorTest,TestCaseLifecycleServiceTest,RegressionCommandTest' test
./mvnw test
```

Done when valid DSL v0.2 artifacts pass, invalid DSL blocks before provider dispatch with AP/field/test/source-ref/owner-action details, generated executable drafts use execution-focused fields, and legacy artifacts remain readable only through compatibility behavior.

The response status metadata gate includes `response_status_equals` with request/response provider HTTP status metadata, blocking the metadata shortcut when no request/response execute target exists, and requiring `actual` plus `selector` when the status is read from captured structured output.

### T003B - Execution-Focused DSL v0.2 Run and Report Consumption Gate

Related features: F007, F008
Acceptance: AC-001, AC-002, AC-012, AC-013, AC-015, AC-017, AC-018
Modules: `cli`, `execution`, `evidence`, `report`

Prove that the same execution-focused DSL v0.2 artifact can be consumed by execution and reporting before provider runtime expansion. The test artifact must live under `tests/approved/`, use `status: active`, and contain only v0.2 sections: `dsl_version`, `test_case_id`, `status`, `revision`, optional tags, `source_refs`, optional `labels`, optional `compatible_profiles`, `parameters`, `targets`, `scenario`, `setup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime`.

Required behavior:

- `run` derives the reviewed AC source from `source_refs.acceptance_criteria` and copies optional report labels from `labels` or generated `traceability_map.yaml`.
- `run` resolves v0.2 targets, setup fixtures, execute outputs, expected-result refs, verify rules, evidence refs, selected profile, environment binding, timeout, retry, plugin contracts, and secret policy into durable result, run, and batch evidence.
- `report --batch-id` calculates coverage from the selected batch using v0.2 source refs, optional labels, generated `traceability_map.yaml`, standard result JSON, and normalized run evidence.
- The selected batch becomes review-ready when the v0.2 test passes and covers the automatable RP AC through a resolvable source ref.
- A passing run that cannot be reported as review-ready is treated as an incomplete F007/F008 implementation.

Verification:

```bash
./mvnw -q -Dtest='RegressionCommandTest#runExecutesExecutionFocusedDslV02AndProducesReviewReadyBatchReport' test
./mvnw -q -Dtest='RegressionCommandTest,DslTestCaseValidatorTest,TestCaseLifecycleServiceTest' test
```

Done when an active v0.2 approved test can pass CLI `run`, write run and batch evidence, and pass CLI `report --batch-id` with review-ready source ref, labels or traceability-map labels when provided, test case ID, batch ID, and run ID.

### T004 - Agent Product Mapping Translation Contract

Related feature: F004
Support AC: SUP-AC-004
Support evidence: EVD-004
Modules: Agent Skill, generated artifact schemas, `environment`, `provider`

Define and validate the boundary where Product/RP/RU mapping is translated into framework-readable artifacts. The Agent Skill reads owner-authored mapping and product context, then emits `suite_manifest.yaml`, `run_plan.yaml`, Execution Profiles, Environment Bindings, Provider Contracts, Provider Instances, `traceability_map.yaml`, and a mapping explanation report. Framework core validation checks generated artifacts only and must not infer provider ownership from Product/RP/RU topology, file order, language, or naming.

Verification:

```bash
agent product-mapping-translate --root <product-repo> --rp-id <rp-id> --out docs/08-release/release-packages/<rp-id>/generated-framework/
regress check-rp --root <product-repo> --rp-id <rp-id> --strict-schema
regress run --root <product-repo> --rp-id <rp-id> --env ci --dry-run
```

Done when incomplete product mapping blocks Agent Skill translation, generated artifact schema errors block framework execution, target dependency graph errors are reported, strategy selection rationale is reviewable, and the framework report does not infer RP membership or RU technology.

### T005 - RP Feature Spec and AC Intake Classifier

Related features: F003, F005
Support AC: SUP-AC-003, SUP-AC-005
Support evidence: EVD-003, EVD-005
Modules: `readiness`, `schema`

Read owner-authored RP feature specs and AC, plus draft/proposed specs created by F001/F002 Agent Skill flows. Preserve source refs, scan evidence refs, review status, and stable AC IDs. Classify each AC as `automatable`, `manual_only`, `partial`, `waived`, or `not_ready_for_generation`, and identify whether inputs, actions, expected outputs, side effects, and pass/fail rules are explicit. Draft/proposed specs may guide owner review, but only owner-authored or owner-reviewed specs and AC can become formal generation truth.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id> --include-ac-readiness
agent rp-spec-intake --root <product-repo> --rp-id <rp-id> --include-draft-specs
```

Done when ambiguous AC are blocked from executable test drafting and never rewritten by the framework.

### T006 - Test Case Lifecycle Manager

Related feature: F005
Support AC: SUP-AC-005
Support evidence: EVD-005
Modules: `testcase`

Implement draft package-neutral DSL test skeleton and draft executable DSL test artifact writing under `tests/draft/`. Generated executable drafts must use execution-focused DSL v0.2 fields: `dsl_version`, `test_case_id`, `status`, `revision`, tags, `source_refs`, optional `labels`, optional `compatible_profiles`, `parameters` when needed, `targets`, `scenario`, `setup`, `execute`, `expected_results`, `verify`, `evidence`, and `runtime`. Detect existing checked-in test artifacts for the same source AC and create update proposals instead of overwriting.

Verification:

```bash
regress generate-tests --root <product-repo> --rp-id <rp-id> --mode draft
```

Done when checked-in DSL tests are protected and generated drafts include source refs, optional labels, revision, execution status, targets, execute outputs, expected_results, verify rules, evidence refs, and runtime policy.

### T007 - Expected Result Manager

Related feature: F006
Support AC: SUP-AC-006
Support evidence: EVD-006
Modules: `expectedresult`

Draft expected-result artifacts from explicit RP AC, RP feature spec, package inputs, and source context. Enforce statuses `draft`, `blocked`, and `approved_for_regression`.

Verification:

```bash
regress draft-expected-results --root <product-repo> --rp-id <rp-id>
regress check-rp --root <product-repo> --rp-id <rp-id> --include-expected-results
```

Done when only approved expected results are eligible as regression truth.

### T008 - Execution Environment Resolver

Related feature: F007
Acceptance: AC-002, AC-018
Modules: `environment`

Resolve `local`, `ci`, `sit`, and `preprod` execution modes from generated `run_plan.yaml`, selected Execution Profile, Provider Instances, Provider Contracts, and Environment Binding. Resolve each target's `runtime_mode`; local/CI may use declared mock, stub, ephemeral, Testcontainers-backed, fake-topic, embedded-broker, disposable-schema, or generated-data replacements, while SIT/preprod must default to native dependencies. For Testcontainers-backed dependencies, provision container dependencies before provider dispatch, run readiness checks, and write generated connection values into Environment Binding output keys. Block `sit` and `preprod` execution unless deployment and environment readiness evidence refs exist and mock substitution is not being used for release evidence.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env sit
```

Done when the command blocks before provider execution if SIT/preprod readiness evidence is missing.

### T009 - Planning, Parameter, and Binding Resolver

Related feature: F007
Acceptance: AC-002, AC-003, AC-015, AC-018
Modules: `binding`

Resolve execution-focused DSL v0.2 `targets.<target>.provider_id`, `targets.<target>.profile`, `setup.fixtures`, `execute[].parameters`, `execute[].outputs`, `expected_results`, `verify`, `evidence.required`, selected Execution Profile, Provider Instances, Provider Contracts, Environment Binding, and `runtime` into an execution plan. The initial compatibility path may still read legacy `package_inputs`, `steps`, `oracles`, `assertions`, `evidence_required`, and `policy`, but new generation and new framework tests must target the execution-focused fields. Binding types or operations outside the selected or implemented provider types must fail as unsupported before execution.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env ci --dry-run
```

Done when supported pilot targets, setup fixtures, execute inputs/outputs, expected results, verify rules, evidence refs, and runtime policy resolve into the execution plan, and unresolved fields fail fast with file path, test case ID, acceptance-criteria source ref when available, section name, field path, provider_id, provider_type, profile when applicable, and owner action.

### T009A - Parameter Set Reference Binding

Related feature: F007
Acceptance: AC-003, AC-012, AC-013
Modules: `binding`, `execution`, `evidence`, `report`

Implement v0.2 parameterization using operation-level `parameters[].ref` and `parameters[].bind_as`. `ref` must point to a reviewed parameter set value, fixture, execute output, expected-result artifact, or environment-safe reference. `bind_as` declares the provider input location and must be allowed by the Provider Contract selected through the target Provider Instance. Each referenced parameter-set case must have a unique case ID and non-empty values. Runtime may resolve parameter refs from setup, execute, cleanup, expected results, verify rules, and evidence refs. Each case produces a separate run ID, standard result JSON, and run evidence with `parameter_case_id`; coverage counts the traced AC once per batch.

Verification:

```bash
./mvnw -q -Dtest='RegressionCommandTest#runExecutesExecutionFocusedDslV02ParameterSetRefAsSeparateRuns' test
./mvnw -q -Dtest='RegressionCommandTest,BindingResolverTest,DslTestCaseValidatorTest,CoverageReportServiceTest' test
```

Done when one DSL test with operation-level `parameters[].ref` and `parameters[].bind_as` resolves a reviewed two-case parameter set, validates `bind_as` against the Provider Contract, produces two run evidence directories with the same test case ID and acceptance-criteria source ref, distinct run IDs, recorded `parameter_case_id`, resolved safe parameter refs, and batch/report coverage that counts the AC once. Inline `parameters.strategy`, inline `parameters.cases`, and `${parameters.<name>}` references are accepted only through an explicit legacy compatibility path until migrated.

### T010 - Provider Contract Registry and Dispatch

Related feature: F007
Acceptance: AC-005, AC-006, AC-007, AC-008, AC-016
Modules: `provider`, `schema`

Introduce the provider capability registry, Provider Contract validator, Provider Instance validator, Environment Binding validator, and DSL target resolver before adding more provider runtimes. The registry must define supported `provider_type` values, supported runtime modes, required binding keys, supported operations, allowed execution modes, runtime support status, evidence outputs, and safety policy. Resolve the public runtime chain explicitly: DSL target `provider_id + profile` -> Provider Instance -> `provider_type` -> Provider Contract -> Environment Binding `runtime_mode` and values for the selected profile. Suite manifests select tests only and must not override provider fields. Dispatch `execute[].operation`, `setup[].operation`, `cleanup[].operation`, `parameters[].ref`, `parameters[].bind_as`, output refs, `verify[].type`, and `evidence.required[]` through the resolved provider runtime. Fail before execution when a Provider Contract, Provider Instance, Environment Binding, runtime mode, required binding key, operation, `bind_as`, or output ref is missing, ambiguous, unsupported, unsafe, prohibited for the selected profile, or only available through an unapproved escape hatch.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env ci --dry-run
```

Done when provider resolution reports provider_id, provider_type, profile, runtime_mode, runtime support status, source level, action/type, affected logical target, test case ID, acceptance-criteria source ref when available, Provider Contract path, Provider Instance path, Environment Binding path, and owner action for unsupported, missing, ambiguous, invalid `bind_as`, missing output ref, missing binding key, unsupported runtime mode, prohibited mock substitution, unsafe, or unapproved escape-hatch capabilities. Execution dispatch must no longer require adding a new product-specific conditional to `ExecutionEngine`.

### T011 - Fixture Lifecycle Manager

Related feature: F007
Acceptance: AC-004
Modules: `fixture`

Implement precondition checks, fixture setup and cleanup lifecycle, and postcondition checks for local and CI runs. Use Provider Contracts and Provider Instances for v0.2 pilot fixture behavior such as file workspace setup, database seed/query/cleanup, message publish/consume cleanup, and configuration binding when those provider types are selected by the heterogeneous pilot. Fixture behavior outside selected or implemented provider types must fail as unsupported before execution. Record cleanup evidence even when execution fails.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env local
```

Done when selected pilot fixture providers can prepare and clean their declared state, and setup, cleanup, and cleanup failure state are written to run evidence.

### T012 - Provider Runtime Foundation and Pilot Provider Set

Related feature: F007
Acceptance: AC-005, AC-006, AC-007, AC-008, AC-010, AC-011, AC-016
Modules: `execution`, `provider`

Implement execution of a prepared plan through validated Provider Contract, Provider Instance, and Environment Binding configuration. The core executor and test case DSL stay package-type-neutral; providers own package-specific calls, messages, fixture operations, readiness probes, and actual-result capture through reusable, configurable contracts. External runner invocation is implemented only after a selected, approved escape-hatch need exists.

The pilot provider set is selected from `docs/02-architecture/07_heterogeneous_rp_support_capability_matrix.md` and should include only the reusable REST/gRPC, Kafka/NATS, DB fixture, K8s and VM readiness, and shell/file capabilities required by the selected heterogeneous RP. External runner is selected only when explicitly approved as an escape hatch.

Current framework verification support is narrower than the pilot target: REST and native descriptor-driven gRPC unary calls are supported, HTTP/status field, JSON path equality/absence, numeric tolerance, schema/contract, and multi-assertion response checks are supported, local/mock messaging is supported, native Kafka/NATS publish, NATS request/reply, consume/observe, and bounded cleanup drain dispatch is supported, JDBC DB fixture and DB row count assertions are supported, local/mock plus native K8s/VM bounded deployment readiness, K8s direct API deployment availability, K8s pod log capture, and VM SSH/WinRM command probes are supported, file/batch is supported, and approved command-runner external runner is supported as an escape hatch. Persistent broker purge if needed and real pilot environment evidence require separate implementation slices before they can satisfy full pilot acceptance.

Verification:

```bash
regress run --root <product-repo> --rp-id <pilot-rp-id> --env ci
```

Done when the pilot provider set can execute or validate one approved test per required provider type, preserve provider results and timeout state, and emit actual outputs under the run evidence directory.

### T013 - Expected-Result and Verify Engine

Related feature: F007
Acceptance: AC-009, AC-010, AC-011
Modules: `oracle`, `assertion`

Implement v0.2 expected-result loading and verify types required by the pilot, starting with `file`, `expected_result_artifact`, HTTP/status metadata checks, HTTP/status field checks, JSON path equality and absence checks, numeric tolerance checks, schema/contract checks, and `query_result` DB row count checks where approved expected-result artifacts, reviewed query refs, provider metadata, or inline decision rules are used. For structured output checks, normalize canonical `selector` into the assertion engine path model while retaining `path` and `json_path` only as compatibility aliases. For metadata-backed checks such as `response_status_equals`, require `expected` and provider evidence metadata, while allowing `actual` plus `selector` when the status is captured in a structured output. For the selected heterogeneous pilot, promote required verify types such as `invariant` only when their provider type and assertion implementation are selected and verified.

Expected-result or verify types outside selected or implemented provider types are rejected as unsupported before verify evaluation.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env ci --test-case <tc-id>
```

Done when selected pilot verify types produce pass/fail results, and failures include expected-result ref or inline rule, expected ref when applicable, actual ref, decision rule, provider_id/provider_type/profile when applicable, diff or mismatch summary, and test case trace.

### T014 - Evidence Writer

Related features: F007, F008
Acceptance: AC-012, AC-013, AC-014, AC-017
Modules: `evidence`

Write `evidence/runs/<run_id>/run.yaml`, logs, actual outputs, assertion results, observation results, postcondition results, cleanup evidence, and failure details. Evidence must include source refs, report labels when provided, test case ID, run ID, logical target refs, execution mode, environment ref, parameter case when applicable, resolved dependencies, resolved bindings, Provider Contracts used, Provider Instances used, provider_id, provider_type, profile, runtime_mode, dependency substitution evidence, release-evidence eligibility, resolved operation result, assertion result, cleanup result, and final pass/fail status.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env ci
```

Done when every execution path produces durable evidence, including failed and blocked runs.

### T014A - Batch Execution Evidence Fix

Related features: F007, F008
Acceptance: AC-012, AC-013, AC-015, AC-017
Modules: `cli`, `execution`, `evidence`

Fix execution evidence so one suite regression command can run multiple approved test cases without overwriting evidence. `regress run` must create one batch ID per suite execution and one unique run ID per approved test case. Run evidence remains test-case-level; batch evidence summarizes the suite execution with optional RP trace labels.

Required evidence layout:

```text
evidence/batches/<batch_id>/batch.yaml
evidence/runs/<run_id>/run.yaml
```

Minimum `batch.yaml` fields:

```yaml
batch_id:
labels: {}
execution_mode:
environment_ref:
started_at:
finished_at:
status:
runs:
  - run_id:
    test_case_id:
    source_refs:
      acceptance_criteria:
    status:
```

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env ci
```

Done when two approved tests in one suite produce two different run directories, no evidence files are overwritten, and CLI output includes the generated `batch_id`.

### T015 - Coverage and Traceability Reporter

Related feature: F008
Acceptance: AC-012, AC-013, AC-015, AC-017
Modules: `report`, `evidence`

Calculate coverage as covered automatable RP-level AC divided by total automatable RP-level AC. RP release coverage is batch-level, not single-run-level. Link generated tests and evidence to acceptance-criteria source refs, optional Product/RP labels, test case ID, batch ID, and run ID. Exclude manual-only or waived AC only with approval records.

Coverage rules:

- Denominator is distinct RP AC classified as `automatable` or `partial`, plus any unapproved manual/waived exclusion that must still appear as a gap.
- Numerator is distinct acceptance-criteria source refs with at least one passed, traceable approved test run in the selected batch.
- Failed, blocked, missing source refs or report labels, manual-only, waived, and duplicate AC coverage must not inflate the numerator.

Verification:

```bash
regress report --batch-id <batch-id>
```

Done when coverage, traceability, evidence index, failure summary, and release review summary are review-ready for the selected batch. `--run-id` may remain available for debugging one test run, but it must not be used to determine RP release coverage.

### T016 - Maven Verify Framework and Provider Public-Interface Integration Harness

Related features: F007, F008, F011
Acceptance: AC-001 through AC-018
Modules: Maven build, integration tests, sample Product Repo fixture, provider public-interface fixtures

Add the framework integration verification layer. Configure Maven Failsafe to run `*IT.java` tests during `./mvnw verify`. The integration tests shall use sample generated framework artifacts, local/CI mock-stub-ephemeral and Testcontainers-backed Provider Contracts, Provider Instances, Environment Bindings, and deterministic data. They shall exercise representative validation, dry-run/run, report, provider capability registry validation, runtime mode validation, dependency provisioning policy validation, provider public-interface dry-run, unsupported-provider blocking, prohibited SIT/preprod mock substitution, unapproved escape-hatch blocking, and provider evidence normalization flows without requiring SIT/UAT deployment.

Optional Phase 2 support smoke may run in separate tests, but it must not be required for Gate 1 or Gate 2 framework maturity.

Verification:

```bash
./mvnw test
./mvnw verify
```

Done when `./mvnw test` remains the fast unit/component framework suite, `./mvnw verify` runs the sample Product Repo integration suite plus provider capability registry and Provider Contract / Provider Instance / Environment Binding runtime-mode verification, and fixture, mock, stub, or ephemeral provider evidence is not presented as downstream Product/RP release evidence.

### T017 - Pilot RP Validation Harness

Related features: F007, F008 plus Phase 2 Agent Skill support prerequisites
Acceptance: AC-001 through AC-018 for framework behavior; Support AC: SUP-AC-001 through SUP-AC-006 for prerequisites; downstream RP evidence: EVD-007 and EVD-008
Modules: all v0.2 modules

Run the full workflow against the selected heterogeneous pilot RP after owner-provided RP artifacts exist.

Verification:

```bash
regress check-readiness --root <product-repo>
regress check-rp --root <product-repo> --rp-id <pilot-rp-id>
agent product-mapping-translate --root <product-repo> --rp-id <pilot-rp-id> --out generated-framework/
regress generate-tests --root <product-repo> --rp-id <pilot-rp-id> --mode draft
regress run --root <product-repo> --rp-id <pilot-rp-id> --env ci
regress report --batch-id <batch-id>
```

Done when the pilot RP evidence package shows greater than 80% coverage for automatable RP AC or reports explicit approved exclusions.

## Dependency Order

```text
Framework maturity path:
T000 -> T000A -> T000B -> T000C -> T000D -> T000E -> T003 -> T003A -> T003B -> T008 -> T009 -> T009A -> T010 -> T011 -> T012 -> T013 -> T014 -> T014A -> T015 -> T016

Phase 2 support path after framework contracts are stable:
T001 -> T002 -> T004 -> T005 -> T006 -> T007

Pilot adoption path:
Gate 2 framework runtime ready + Gate 3 Phase 2 support ready -> T017
```

Parallelizable work:

- T015 report formatting skeleton may start after the result/evidence shape is stable, but it cannot close before T014/T014A.
- T001/T002 Phase 2 support tasks may proceed independently for usability, but they are not required for Gate 1 or Gate 2 framework maturity.
- T005/T006/T007 remain next-stage Phase 2 tasks and must not block framework runtime readiness.

## Implementation Gates

Gate 1 - Framework contract ready:

- T000, T000A, T000B, T000C, T000D, T000E, T003, T003A, T003B, T008, T009, T009A, T010, and the contract portions of T011 complete.
- Framework-owned contract files exist for DSL, Execution Profile, Environment Binding, suite manifest, Provider Contract, Provider Instance, provider capability registry, provider/verify plugin contracts, result schema, and evidence schema.
- Framework public interface is documented for current-stage runtime commands, DSL/test definition fields, Provider Contract fields, Provider Instance fields, Environment Binding fields, Execution Profile fields, and result/evidence outputs before test-plan expansion.
- AC-001 through AC-018 have named happy, failure, and boundary verification coverage in the test plan.

Gate 2 - Framework runtime ready:

- T011 through T016 complete.
- `./mvnw test` and `./mvnw verify` are green.
- Track B Golden E2E passes through `regress validate --suite samples/golden_e2e/suite_manifest.yaml`, `regress run --suite samples/golden_e2e/suite_manifest.yaml --profile local_golden`, and `regress report --result <generated_result_json>`.
- Track C Provider Capability suite passes through `regress validate --suite samples/provider_capability/suite_manifest.yaml`, `regress run --suite samples/provider_capability/suite_manifest.yaml --profile local_provider`, and `regress report --result <generated_result_json>` for selected P0 provider capabilities.
- Sample generated-artifact integration, Provider Contract / Provider Instance / Environment Binding verification, blocked/failed classification, result JSON, evidence, and report are proven without SIT/UAT deployment.

Gate 3 - Phase 2 support ready:

- T001, T002, T004, T005, T006, and T007 complete after framework contracts are stable.
- Pilot RP has owner-authored AC and mapping.
- Draft tests and expected results are reviewable but not regression truth until approved.

Gate 4 - Pilot release evidence ready:

- T017 completes against owner-provided pilot artifacts.
- Coverage, traceability, failures, waivers, and evidence package are review-ready.

## Risks and Controls

| Risk | Control |
|---|---|
| Agent invents AC or business behavior | F003 gate treats draft/proposed specs as review inputs only and preserves formal AC as owner-authored or owner-reviewed truth. |
| Tests are regenerated on every run | Execution reads checked-in DSL tests from `tests/approved/`; generation is separate. |
| Provider runtime is implemented before the DSL contract is stable | T003A and T003B block provider runtime migration until execution-focused DSL validation, generator output, dry-run reporting, and CLI run/report consumption are verified. |
| SIT run starts before deployment readiness | Environment resolver blocks `sit` before provider execution. |
| Product topology order is ambiguous | Agent Skill translation requires generated target dependency graph and rejects scalar order-only execution planning. |
| RP/RU membership is inferred incorrectly | Framework core consumes generated artifacts only; Agent Skill consumes human-authored `rp_ru_mapping.yaml`. |
| Expected results become truth without review | Expected-result manager blocks unapproved artifacts. |
| Evidence cannot support release review | Evidence writer and reporter require RP/AC/test/run traceability. |
| Multi-test RP execution overwrites evidence | Batch execution creates one batch per RP run and one unique run ID per approved test. |
| Single-run report is mistaken for RP coverage | RP release coverage is calculated only from batch-level evidence. |
| Maven framework verification is mistaken for downstream RP release evidence | AC-012, AC-013, AC-017, T016, T017, and EVD-000/EVD-007/EVD-008 separate Surefire/Failsafe evidence and mock provider evidence from Product Repo RP release evidence. |

## Coverage Gate Scope

The normal `./mvnw verify` JaCoCo check is an interim framework health gate. It keeps the suite above bundle-level instruction 99%, branch 90%, line 98%, method 100%, and class 100% while implementation slices are still moving.

Critical-class line coverage gate for v0.2 delivery is executed explicitly with `./mvnw -Pv02-delivery-coverage verify`. This gate applies class-level LINE 1.00 and BRANCH 0.90 to framework-owned public interface and runtime logic:

- CLI: `RegressionCommand`
- DSL and generated artifacts: `DslTestCaseValidator`, `DslTestCaseNormalizer`, `GeneratedRuntimeArtifacts`
- Resolution and lifecycle: `BindingResolver`, `ParameterSetResolver`, `ExecutionEnvironmentResolver`, `FixtureLifecycleService`
- Runtime and providers: `ExecutionEngine`, `ProviderRuntimeRegistry`, `ProviderCapabilityRegistry`, `ProviderContractResolver`, `RequestResponseProvider`, `MessagingProvider`, `DatabaseFixtureProvider`, `DeploymentReadinessProvider`
- Verification and evidence: `AssertionEngine`, `OracleResolver`, `EvidenceWriter`, `CoverageReportService`, `ArtifactSchemaValidator`

Allowed delivery-gate exclusions are limited to DTO/record-style result/report/gap/request/context/target carriers, the Spring launcher `RegressionApplication`, sample fixtures, and documented unreachable defensive branches. Exclusions do not waive missing behavior tests for CLI, DSL, Provider Contract / Provider Instance / Environment Binding config, generated runtime artifacts, evidence/report format, or heterogeneous e2e execution.

## Delivery Acceptance Criteria for v0.2

Framework v0.2 is not complete when only documentation gates or sample Maven tests pass. v0.2 delivery acceptance requires all of the following:

- Framework verification passes through `./mvnw test` and `./mvnw verify` without SIT/UAT deployment, and the AC traceability matrix maps framework AC-001 through AC-018 to automated happy, failure, and boundary paths.
- Framework test coverage meets the committed v0.2 goal; lower interim JaCoCo gates and AC mapping gates are progress indicators only and do not mean v0.2 is accepted.
- Framework-owned contract files are present and aligned with AC-001 through AC-018.
- Implementation slices S01 through S09 are code-complete, reviewed, and verified against their mapped AC.
- Approved v0.2 DSL tests execute without regeneration by default.
- Evidence records batch summary, run-level inputs, outputs, logs, assertions, cleanup, result JSON, and failures.
- Coverage/report output is review-ready for sample generated-artifact fixtures and clearly marked as framework verification evidence only.

Next-stage Phase 2 and pilot completion:

- Product developer can initialize/check the Product Repo and RP.
- Pilot RP artifacts are complete and human-authored where required.
- Agent drafts tests only from ready AC and execution context.
- Downstream RP coverage is greater than 80% of automatable RP-level AC or exclusions are approved.
