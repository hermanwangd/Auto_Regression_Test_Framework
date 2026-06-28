# 03. Product Baseline and Feature Specs

## 3.1 Product Baseline

This product is a product spec-driven Release Package Regression Framework. It helps product developers define package-release scope, derive regression tests from RP-level acceptance criteria, execute repeatable package regression checks, and produce evidence for release review.

The product is not tied to one implementation repo. It uses the Product Repo as the initialized source-of-truth structure for specs, release packages, and regression evidence, and treats Release Packages as the release and regression evidence unit.

```text
Virtual Product
-> Product Specs and E2E Scenarios
-> Release Packages
-> RP/RU Mapping
-> Regression Artifacts
-> Evidence Package
```

## 3.2 Product Boundary

### In Scope

- Product Repo lifecycle structure for specs, acceptance, planning, validation, release, operations, and change control.
- Release Package creation guide and package-release source of truth.
- RP-level feature specs and formal RP acceptance criteria.
- Product-owned RP/RU repo mapping for Agent Skill translation into framework-readable execution artifacts.
- Agent-assisted generation of regression tests and expected results from RP AC.
- Generic test execution process for release packages.
- Coverage, traceability, evidence, and release review inputs.

### Out of Scope for M1

- Product-level formal AC as a release denominator.
- RU repo-owned primary specs or primary acceptance criteria.
- Full dashboard experience.
- Support for every release package type.
- Fully automated release approval.
- Unreviewed agent-generated expected results.

## 3.3 M1 Pilot Boundary

M1 validates the product baseline through one heterogeneous release package pilot.

M1 includes:

- One RP with a bounded set of release unit repos.
- Local or CI execution without requiring a persistent service.
- REST and/or gRPC request-response execution through configurable built-in provider contracts.
- Kafka and/or NATS message execution or observation through configurable built-in provider contracts.
- DB fixture setup, query, and cleanup for stateful regression scenarios.
- K8s and VM readiness checks before deployed-environment execution.
- Provider capability registry and contract validation so unsupported or incomplete provider configs block before execution.
- External runner bridge only as an approved escape hatch for legacy or specialized validation that cannot yet use a reusable built-in provider.
- Small reviewable fixtures or documented fixture generation.
- RP-level AC readiness classification.
- Agent-generated draft regression tests and expected results for ready RP AC.
- Reviewable coverage and evidence package for the pilot RP.

M1 excludes:

- Cross-package orchestration.
- Production data unless masked and approved.
- Broad package-type plugin support beyond the selected heterogeneous pilot.
- RP-specific custom scripts or harnesses as the standard extension path.
- Dashboard-driven release governance.
- Fully automated waiver, manual-only, or release approval.

### Staged Provider Boundary

M1 framework delivery is staged. The current framework verification target is not the same as the selected heterogeneous pilot target.

| Provider Area | Framework Verification Target | Heterogeneous Pilot Target |
|---|---|---|
| Request/response | REST and native descriptor-driven gRPC unary provider contracts with payload binding, timeout, output refs, and evidence. | Pilot endpoint validation remains required for release acceptance. |
| Messaging | Local/mock plus native Kafka/NATS publish, NATS request/reply, consume/observe, and bounded cleanup drain behavior with topic/subject refs, publish/request payload binding, timeout, correlation checks, cleanup strategy/count, and observed output refs. | Pilot broker validation remains required for release acceptance; Kafka request/reply and persistent broker purge are future work only if selected RP acceptance requires them. |
| DB fixture | JDBC setup, verification query, cleanup SQL refs, cleanup strategy, isolation key, and cleanup evidence. | Same contract against the selected pilot DB fixture boundary. |
| Deployment readiness | Local/mock plus native K8s/VM bounded readiness evidence with deployed version ref, timeout, output ref, K8s `kubectl` or direct API probes, K8s pod log tail bound, VM command refs where configured, and bounded probe behavior. | Owner-provided pilot K8s/VM validation remains required for release acceptance. |
| External runner | Approved command-runner escape hatch with approval metadata, bounded timeout, inputs, outputs, evidence map, and mapped-artifact checks. | Optional only when no reusable built-in provider can represent the selected boundary. |

The product shall not claim downstream RP release readiness merely because framework verification passes with local/mock providers or simulated broker tests. Native provider paths become release-accepted only after their reusable runtime, contract validation, evidence mapping, verification cases, and owner-provided pilot environment evidence are present.

## 3.4 Feature List

| Feature ID | Feature | Purpose | M1 |
|---|---|---|---:|
| F001 | Product Repo Bootstrap CLI and Readiness Agent Skill | Initialize the Product Repo structure, produce deterministic readiness reports, and use an agent skill to explain owner-actionable next steps | Yes |
| F002 | Release Package Creation Guide and Completeness Check | Tell owners how to create an RP and check whether required RP artifacts are complete | Yes |
| F003 | RP Feature Spec and AC Intake | Consume Product Owner / PM / SA-authored RP feature specs and formal acceptance criteria | Yes |
| F004 | Agent Skill: Product Mapping Translation | Translate human-authored Product/RP/RU context into framework-readable suite, run, environment, provider, and traceability artifacts | Yes |
| F005 | Agent Skill: AC and Execution Context Readiness with DSL Test Drafting | Use agent reasoning to classify AC/execution readiness and draft package-neutral DSL test skeletons or executable regression test cases | Yes |
| F006 | Agent Skill: Expected Result Drafting | Draft reviewable expected-result artifacts from explicit RP AC and source context | Yes |
| F007 | Release Package DSL Test Execution | Execute package-level regression from checked-in package-neutral DSL test cases using generic input catalog, binding, fixture lifecycle, providers/adapters, verify rules, and evidence collection | Yes |
| F008 | Coverage and Evidence Package | Report RP AC coverage, traceability, execution evidence, failures, waivers, and release review inputs | Yes |
| F009 | Advanced Spec Readiness | Detect deeper cross-artifact spec gaps and drift across product, RP, architecture, data, and change history | No |
| F010 | Release Governance Integration | Apply release gate policy, waiver workflow, and Go/No-Go records | No |
| F011 | Package Type Plugin SDK | Support additional release package types through adapter/plugin contracts | No |

## 3.5 Next-Phase Readiness

The baseline is ready for architecture design when these conditions are true:

- Product, RP, and RU responsibilities remain separated as defined in the baseline.
- Release Package remains the release unit and primary regression evidence unit.
- Formal release AC remain RP-level; product E2E scenarios provide context only.
- F001 through F008 have purpose, expected behavior, required mechanism, and acceptance criteria.
- Minimum RP artifacts, product mapping inputs, and generated framework artifact contracts are defined.
- M1 pilot scope, non-goals, success metrics, and human approval boundaries are explicit.

Implementation may start only for slices whose inputs are ready:

- F001/F002 may start with the agreed Product Repo folder structure and RP artifact checklist.
- F003/F004 require at least one pilot RP with owner-provided `rp_feature_spec.md`, `acceptance_criteria.md`, and `rp_ru_mapping.yaml`, plus Agent Skill output contracts for `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, provider contracts, and `traceability_map.yaml`.
- F005/F006 require RP AC with observable input, behavior, output, and pass/fail expectations.
- F007 requires checked-in execution-eligible DSL test artifacts, targets, setup fixtures, execute operations and outputs, expected_results, verify rules, evidence requirements, runtime policy, provider registry support, and provider contract validation.
- F008 requires RP AC inventory, execution evidence format, traceability rules, and approval records for exclusions.

Before full M1 implementation, the responsible owner shall supply the pilot RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, adapter/provider intent, required provider families, and any approved escape-hatch provider need. The Agent Skill must translate those inputs into generated framework artifacts before F007 core execution can start.

For implementation planning, a slice is ready only when its provider contract can name the provider family, provider type, required fields, evidence outputs, runtime status, AP owner, and verification case. If any of those cannot be stated, the slice remains a spec/design task rather than an implementation task.

## 3.6 CI/CD and Environment Execution Policy

RP regression can run in local, CI, or SIT depending on the RP validation boundary.

- `local_fixture`: run against local fixtures, files, mocks, generated data, or deterministic sample inputs.
- `ci_ephemeral`: run in CI against temporary services, containers, schemas, queues, or jobs provisioned by the pipeline.
- `sit_deployed`: run against already deployed RU versions in SIT when real integration behavior cannot be validated locally or ephemerally.
- `evidence_only`: collect mapped RU evidence when direct framework execution is not possible.

For RPs that include multiple RUs, the Agent Skill shall translate `rp_ru_mapping.yaml` into a framework-readable target dependency graph, selected execution mode, environment binding, deployment readiness refs, validation boundary, and evidence responsibility labels.

The framework may verify deployment readiness through generated environment bindings before SIT execution, but M1 does not own CD deployment orchestration. CD or the environment owner must deploy required product components before SIT regression starts.

## 3.7 Test Case Lifecycle

Regression test cases are managed as durable RP artifacts. The framework shall not regenerate test cases on every execution run.

The framework uses a package-neutral regression test case DSL so different Products and Release Packages can express repeatable auto regression tests through the same artifact model. The DSL describes what RP behavior must be validated, while package-specific execution remains isolated behind adapters.

The DSL is not a BDD story, shell script, provider configuration, or generated run log. It is a versioned test contract with enough structured data for the framework to validate, plan, bind, execute, assert, and report a regression test repeatedly.

The DSL must answer execution questions in a package-neutral, execution-focused way:

| Question | DSL Responsibility | Not DSL Responsibility |
|---|---|---|
| What is being validated? | `traceability.package_id`, `traceability.acceptance_criteria_id`, `traceability.source`, and `scenario` | Writing or changing RP feature truth |
| What targets are involved? | `targets` and `execute[].target` | Inferring RP/RU membership or deployment topology |
| What data or state is needed? | `setup.fixtures` and `execute[].with` | Inline secrets, physical DB connection strings, or package-specific loaders |
| What operation is performed? | `execute[].operation`, target, runtime inputs, and captured outputs | Shell scripts, endpoint URLs, queue drivers, or deployment commands embedded in the test |
| How is pass/fail decided? | `expected_results` and `verify[]` with explicit captured-output actual/expected, provider-metadata expected, or target/query/event semantics | Approving expected-result truth or inventing business rules |
| What must be retained? | `evidence.required` traceable to execution or verification outputs | Release approval or waiver approval |
| What runtime policy applies? | `runtime.timeout` and `runtime.retry` | Release gate, waiver, or risk approval workflow |

| DSL Area | Purpose | Primary AP Consumer |
|---|---|---|
| Identity and traceability | Identify DSL version, test ID, execution status, revision, package ID, AC ID, and source reference. | Definition and Validation |
| Targets and scenario | Describe named targets, scenario type, scope, description, and capabilities. | Discovery and Context / Planning and Binding |
| Setup and runtime inputs | Reference fixtures, seed data, mock setup, initial state, and operation inputs. | Planning and Binding / Fixture and State Manager |
| Parameterization | Declare a reviewed parameter set reference when one logical test must run with multiple input variants. | Planning and Binding / Evidence and Reporting |
| Execution | Declare readable operations, target IDs, runtime inputs, and captured outputs without embedding provider-specific scripts. | Execution Engine |
| Expected results and verification | Reference expected artifacts and define explicit `verify` checks over captured outputs, DB state, events, or files. | Oracle and Assertion Engine |
| Evidence and runtime | Declare concrete evidence refs, timeout, and retry. | Evidence and Reporting |

The 7 AP are the framework capability areas behind the DSL lifecycle: Definition and Validation, Discovery and Context, Planning and Binding, Fixture and State Manager, Execution Engine, Oracle and Assertion Engine, and Evidence and Reporting. Provider configuration belongs in generated provider contracts and environment bindings; DSL tests may reference provider capabilities by logical names only.

Each AP must be independently explainable in readiness and execution reports. A report that says only "DSL invalid" or "execution failed" is not sufficient; it must name the AP, field path or provider contract, affected test case, affected AC, reason, and owner action.

The DSL field set should stay minimal but complete enough to execute safely. M1 shall treat these fields as the execution-focused DSL v1 core. Top-level sections may be present as empty maps for schema stability, but content is required only when the scenario references or needs it.

| Field or Section | Requirement Rule | Why It Is Required | Consuming AP |
|---|---|---|---|
| `dsl_version` | Always required | Selects supported schema and compatibility rules. | Definition and Validation |
| `test_case_id`, `status`, `revision` | Always required | Gives every run stable artifact identity and execution lifecycle state. | Definition and Validation / Evidence and Reporting |
| `traceability.package_id`, `traceability.acceptance_criteria_id`, `traceability.source` | Always required | Gives every run stable traceability to RP-level AC and source. | Definition and Validation / Evidence and Reporting |
| `targets` | Always required | Names the application, database, event bus, file store, batch runner, or external boundary used by the test. | Discovery and Context / Planning and Binding |
| `scenario.type`, `scenario.scope`, `scenario.description`, `scenario.capabilities` | Always required | Tells the framework what kind of behavior and provider capability is needed. | Planning and Binding |
| `parameters` | Optional; required only when one test case must run multiple reviewed parameter-set variants | Declares a reviewed parameter set reference without regenerating or duplicating test artifacts. | Planning and Binding / Evidence and Reporting |
| `setup.fixtures` | Required when the scenario needs precondition data, mutated state, mock setup, seed data, or cleanup | Declares fixture lifecycle before and after execution. | Fixture and State Manager |
| `execute[]` | Always required; M1 runtime supports exactly one execute step per test case | Declares the readable operation, target ID, runtime inputs, and observable outputs. Multiple RP operations should be modeled as multiple approved test cases in one batch until multi-step orchestration is implemented. | Execution Engine |
| `expected_results` | Required when `verify[]` uses approved artifacts, payloads, schemas, contracts, or state snapshots | Declares reusable truth references used by verification. Inline deterministic expected values may live directly under `verify[].expected`. | Oracle and Assertion Engine |
| `verify[]` | Always required | Declares how pass/fail is evaluated. | Oracle and Assertion Engine |
| `evidence.required[]` | Always required | Declares what concrete execution or verification outputs must be retained. | Evidence and Reporting |
| `runtime.timeout`, `runtime.retry` | Always required | Bounds execution duration and retry behavior. | Execution Engine |

Conditional fields are required only when the scenario needs them:

| Conditional Field | Required When | Not Allowed To Contain |
|---|---|---|
| `setup.fixtures.<name>.cleanup_ref` | The test creates, mutates, seeds, publishes, or deletes state. | Hidden destructive actions without cleanup references. |
| `execute[].with` | Runtime inputs are passed to the selected operation. | Secrets, endpoint URLs, connection strings, or provider code. |
| `execute[].outputs` | Later verification or evidence references execution output. | Uncaptured implicit result paths. |
| `verify[].actual` | A verify rule reads a captured output file or captured execution value. It is not required when the verify type consumes provider metadata, such as HTTP status supplied by the request/response provider. | Hidden provider lookup rules or JSONPath expressions overloaded into the actual ref. |
| `verify[].selector` | A verify rule compares or checks a field inside a structured actual result, including `json_path_equals`, `json_path_absent`, and structured `numeric_tolerance` checks. `actual` names the captured output; `selector` names the JSON/YAML path inside it. It is not required for provider metadata checks that do not read a structured actual body. | Provider-specific parser code, captured-output refs overloaded as JSONPath expressions, or runtime-inferred selectors. |
| `verify[].target`, `verify[].query`, or `verify[].event` | The check validates DB state or published events. | Inline credentials, physical connection strings, or unreviewed destructive SQL. |
| `verify[].options` | The check needs timeout, polling, tolerance, normalization, or ignore paths. | Release gate or risk approval policy. |
| `parameters.ref`, `parameters.bind_as` | The test uses parameterization. `ref` points to a reviewed parameter set and `bind_as` names the `${param.<bind_as>.*}` namespace used by the DSL. | Inline parameter cases, dynamic data discovery, combinatorial generation, secrets, or unreviewed runtime-created cases. |

Optional fields such as tags, notes, or replacement links may improve maintenance, but governance-heavy fields must not be required for first execution.

The DSL v1 contract is a pre-implementation gate for F007 provider runtime work. Before provider dispatch, sample fixture migration, or native runtime expansion can be claimed complete, the framework must validate the execution-focused DSL shape, generator output, compatibility behavior, and CLI run/report consumption described in `docs/02-architecture/06_artifact_contracts.md`.

Validation alone is not enough. At least one checked-in `tests/approved/` execution-focused DSL v1 artifact with `status: active` must be accepted by `run`, produce run and batch evidence, and be accepted by `report --batch-id` as review-ready coverage before provider runtime expansion can claim execution-focused DSL support.

New execution-focused DSL artifacts must:

- Use readable operation names such as `run_batch`, `execute_command`, `call_api`, `execute_sql`, `publish_message`, `consume_message`, `request_reply_message`, or `run_application`.
- Use `targets`, `setup` when needed, `execute`, `expected_results` when needed, `verify`, `evidence`, and `runtime` instead of framework-internal legacy fields.
- Use one `execute[]` item per M1 test case. Multiple targets may be declared for context, state, or verify targets, but multiple executable operations in one DSL artifact are blocked until multi-step orchestration is explicitly designed and verified.
- Capture `execute[].outputs` whenever verification or evidence references execution results.
- For structured output checks, keep `actual` as the captured output reference and declare `selector` as the canonical field path. `path` and `json_path` are compatibility aliases only; new generated DSL must use `selector`.
- For provider metadata checks such as `response_status_equals`, declare the deterministic `expected` value and let request/response provider evidence supply the HTTP status. Declare `actual` plus `selector` only when the status is read from a captured structured output.
- Use `parameters.ref` and `parameters.bind_as` only when the reviewed test artifact must run the same behavior against multiple named input variants from a checked-in parameter set.
- Reference parameter values with `${param.<bind_as>.<field>}`. New DSL artifacts must not use `parameters.strategy`, inline `parameters.cases`, or `${parameters.<name>}` references.
- Keep provider implementation details in generated provider contracts or environment bindings, not inside the test case body.
- Fail validation before execution when required fields, conditional fields, or supported enum values are missing.

The DSL shall reject or block these concerns from the test case body:

- Secrets, credentials, tokens, or production data.
- Physical provider implementation details such as endpoint URLs, shell commands, queue client settings, DB connection strings, or loader code.
- Dynamic data-selection queries, combinatorial parameter generation, or unreviewed runtime-created cases in M1.
- CD deployment instructions or environment provisioning scripts.
- New RP feature behavior, AC wording, expected-result approval, waiver approval, release approval, release gate, or risk approval.
- Governance-heavy fields such as `approval_status`, `approved_by`, `approval_required`, `waiver`, `release_gate`, `risk_approval`, or governance workflow state.

M1 implements only the DSL v1 subset required for the selected heterogeneous pilot. Additional DSL v1 enum values remain reserved until their providers are implemented and verified.

The lifecycle is:

```text
RP AC / execution context ready
-> draft test skeleton or draft executable test case using execution-focused DSL v1
-> product developer review
-> checked-in RP test artifact
-> repeated execution
-> update or retire when RP AC, mapping, input, fixture, adapter, or expected result changes
```

Execution-eligible test cases should be stored in the RP `tests/` folder. In the current Product Repo model this means `docs/08-release/release-packages/<rp_id>/tests/`. If a dedicated RP repository is introduced later, the same `tests/` lifecycle applies inside that repo.

The agent may propose new or updated test cases when source artifacts change, but it shall not silently overwrite checked-in approved tests.

## 3.8 Pre-Implementation Documentation Gate

Any implementation slice that changes DSL validation, generation, execution, evidence, reporting, provider runtime, or AP boundaries must update the documents first. The slice is not implementation-ready until the feature/spec, architecture design, artifact contract, acceptance criteria, implementation plan, and framework verification test plan agree on:

- The user-visible behavior and non-goals.
- Required, conditional, optional, legacy-compatible, and prohibited DSL fields.
- Data binding, fixture setup, fixture cleanup, expected-result, verify/assert, evidence, and runtime semantics.
- Which AP consumes each DSL section and which provider contract resolves package-specific behavior.
- Happy path, failure path, and boundary path acceptance.
- Framework verification tests and downstream RP validation evidence needed to prove the change.

If any of those items cannot be stated without inventing RP behavior, the work remains a spec/design task rather than an implementation task.

## 3.9 Framework Verification and RP Regression Execution Boundary

This document uses two different execution terms:

| Execution Line | Subject Under Test | Primary Command | Evidence Meaning |
|---|---|---|---|
| Framework Verification | This regression framework product | `./mvnw test` and `./mvnw verify` | Proves framework code, contracts, and sample fixture flows behave correctly. |
| RP Regression Execution | A downstream Product Release Package | Product-aware wrapper or pipeline generates framework artifacts, then invokes the generic runner with `suite_manifest`, `run_plan`, and `environment_binding` | Produces Product Repo RP evidence for release review. |

`./mvnw test` is the fast framework unit/component verification layer. `./mvnw verify` is the framework integration verification layer and should use a sample Product Repo fixture with local or mock adapters. Neither Maven command requires SIT/UAT deployment, and neither command by itself produces downstream product release evidence.

RP Regression Execution is the runtime capability delivered by F007. Product-aware tooling first turns Product/RP/RU context into framework-readable artifacts. The framework core then reads checked-in DSL tests, `suite_manifest`, `run_plan`, `environment_binding`, provider contracts, and approved truth sources, executes or validates the selected suite, and writes batch/run evidence.

SIT/UAT regression is a release package pipeline concern. The framework may verify SIT/UAT readiness and run against an already deployed environment, but M1 does not deploy release units as part of Maven framework verification.

## F001 — Product Repo Bootstrap CLI and Readiness Agent Skill

### Purpose

Initialize the Product Repo folder structure, run deterministic readiness checks, and use an agent skill to explain whether the repo is ready for RP-level regression work.

### Expected Behavior

The platform shall provide a standard Product Repo bootstrap command and a machine-readable readiness check. The bootstrap command shall create the agreed docs lifecycle folders and starter artifact locations. The deterministic readiness check shall report missing folders, missing RP artifacts, missing stable IDs, missing product mapping input, missing generated framework artifacts when required, and missing RP AC prerequisites.

The readiness agent skill shall read the readiness report, explain the current repo state, classify whether the repo is ready for RP work, and produce owner-actionable next steps. The agent skill may explain and prioritize gaps, but it shall not invent RP scope, RP AC, or RP/RU membership.

### Required Mechanism

- Initialize the agreed docs lifecycle folders when they do not exist.
- Create starter placeholders or templates for product baseline, RP specs, RP AC, traceability, evidence, release, operations, and change-control records.
- Run a deterministic readiness check that required folders exist.
- Check that at least one RP folder or record can be discovered, or report that RP creation is the next owner action.
- Check that required RP artifacts exist or are reported missing.
- Check that RP artifacts reference stable product, RP, and AC identifiers when those artifacts exist.
- Check that product mapping input exists before Agent Skill translation or regression generation.
- Emit a machine-readable readiness report with pass/fail status, missing items, owner action, and next required step.
- Provide an agent skill that reads the readiness report and returns a human-readable readiness explanation, missing-item summary, owner actions, and next command or document to update.

## F002 — Release Package Creation Guide and Completeness Check

### Purpose

Provide clear instructions for Product Owner, PM, SA, or the responsible owner to create a Release Package in the Product Repo, then check whether the RP is complete enough for downstream regression work.

### Expected Behavior

The Product Repo shall document how to create an RP. The responsible owner follows the instructions to create the RP folder or record and required artifacts.

The readiness check shall verify that each RP has identity, owner, target release, lifecycle status, package type, scope, linked product spec context, and evidence package location.

Each RP shall define the minimum artifact set:

- `package.yaml`
- `rp_feature_spec.md`
- `rp_ru_mapping.yaml`
- `acceptance_criteria.md`
- `tests/`
- `expected-results/`
- `traceability.md`
- `evidence_index.md`

### Required Mechanism

- Provide a documented RP creation checklist.
- Instruct the owner to create one release package folder or record per RP.
- Instruct the owner to define RP metadata in `package.yaml`.
- Instruct the owner to write RP feature behavior in `rp_feature_spec.md`.
- Instruct the owner to list release unit repos in `rp_ru_mapping.yaml`.
- Instruct the owner to place formal RP AC in `acceptance_criteria.md`.
- Instruct the owner to store reviewed test cases under the RP `tests/` folder.
- Instruct the owner to store reviewed expected-result artifacts under `expected-results/`.
- Instruct the owner to create initial `traceability.md` and `evidence_index.md` placeholders.
- Check required files and fields for completeness.
- Report missing RP metadata, missing artifacts, or incomplete references as readiness gaps.

## F003 — RP Feature Spec and AC Intake

### Purpose

Consume and govern RP feature specs and formal acceptance criteria authored by Product Owner, PM, or SA.

### Expected Behavior

Formal AC shall be authored and owned by Product Owner, PM, or SA at RP level. Product-level docs may define intent and E2E scenarios, but RP AC shall be the source for generation, coverage, and release readiness.

The framework shall not invent or author primary RP feature behavior or formal RP AC. It shall consume, validate, classify, and trace those specs for downstream generation and evidence.

### Required Mechanism

- Read RP feature behavior and AC from Product Owner / PM / SA-maintained spec artifacts.
- Require stable AC IDs inside each RP.
- Validate that AC include observable inputs, actions, outputs, and allowed side effects when they are marked ready for generation.
- Classify each AC as automatable, manual-only, partial, waived, or not ready.
- Link product E2E scenarios to one or more RP AC when cross-RP context matters.
- Use RP AC IDs as the denominator for coverage and release evidence.

## F004 — Agent Skill: Product Mapping Translation

### Purpose

Translate human-authored Product/RP/RU context into framework-readable artifacts without moving product topology interpretation into the framework core.

### Expected Behavior

The framework shall not decide which RU repos are included in an RP, which RU is Java/Maven, which runner a RU should use, or how SIT topology maps to deployed components. SA, tech lead, product developer, or the responsible owner defines RP membership in `rp_ru_mapping.yaml` and related product docs.

The Agent Skill shall check that product mapping is complete enough to generate framework artifacts. It shall emit explicit `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, provider contracts, and `traceability_map.yaml`. The framework validates those generated artifacts and treats RP/RU labels as opaque traceability metadata.

### Required Mechanism

- Read owner-authored `rp_ru_mapping.yaml`, release manifest, deployment manifest, SIT topology, and related product docs.
- Check that product mapping entries have enough information to choose logical targets, runner/provider families, execution profile, environment binding, evidence responsibility, and target dependencies.
- Generate `suite_manifest.yaml` with selected DSL tests, source refs, trace labels, and coverage source refs.
- Generate `run_plan.yaml` with logical target dependency graph, selected run profile, execution mode, and suite-level runtime constraints.
- Generate `environment_binding.yaml` with logical target IDs bound to runner/provider contract names and environment refs.
- Generate provider contracts with `provider_family`, `provider_type`, required fields, evidence outputs, runtime status, and safety policy.
- Generate `traceability_map.yaml` with product/RP/RU/source labels used for reporting only.
- Report missing or incomplete product mapping as Agent Skill readiness gaps before framework execution is requested.
- Never require the framework core to inspect RP/RU membership, implementation language, release manifest semantics, or SIT topology.

## F005 — Agent Skill: AC and Execution Context Readiness with DSL Test Drafting

### Purpose

Use an agent skill to reason over RP specs, RP AC, RP/RU mapping, release context, deployment context, and generated framework artifacts before drafting package-neutral DSL regression tests.

### Expected Behavior

The agent skill shall classify both AC readiness and execution context readiness. It shall not draft tests from ambiguous AC. It may generate a draft package-neutral DSL test skeleton when AC is ready but execution context is incomplete. It may generate a draft executable DSL regression test case only when both AC and execution context are ready.

Product developers own AC clarification and manual expected-result input. QA or release owners approve manual-only and waiver classifications when those classifications affect release evidence or coverage.

### Required Mechanism

- Read `rp_feature_spec.md`, `acceptance_criteria.md`, product mapping inputs, generated `suite_manifest`, `run_plan`, `environment_binding`, target references, setup fixture references, execute operation references, expected-result references, verify rules, and validation boundaries.
- Check AC readiness: AC ID, linked RP feature, observable input, behavior, expected output, and pass/fail condition.
- Check execution context readiness: scenario type/scope/capabilities, named targets, target runner, environment binding, setup fixture or data source, cleanup reference when state is mutated, execute operation, execute input refs, execute output refs, deployment readiness ref, validation boundary, expected_results refs, verify type, captured-output actual/expected, provider-metadata expected, or target/query/event semantics, evidence refs, and runtime policy.
- Map each generated DSL section to the AP that will consume it: identity and traceability to Definition and Validation, targets/setup/execute inputs to Planning and Binding, setup fixture fields to Fixture and State Manager, execute operations to Execution Engine, expected_results/verify to Oracle and Assertion Engine, and evidence/runtime fields to Evidence and Reporting.
- Refuse executable drafting when an AP cannot be selected or when a required AP input would have to be invented by the agent.
- Mark ambiguous AC as `not_ready_for_generation`.
- Generate `draft_test_skeleton` only when AC is ready but execution context is incomplete. The skeleton shall use v1 identity/status/revision and traceability fields, plus `source_fingerprint` and `readiness_gaps`; it shall not include executable sections that require invented context.
- Generate `draft_executable_test_case` using the package-neutral test case DSL only when AC and execution context are both ready.
- Include `dsl_version` and all required DSL identity/status/revision, traceability, targets, scenario, setup, execute, expected_results, verify, evidence, and runtime fields in every generated executable draft.
- Do not emit legacy-only fields such as `rp_id`, `ac_id`, `artifact_status`, or `source_refs` in new generated test-case drafts.
- Store generated test drafts as reviewable artifacts instead of transient execution state.
- Detect existing checked-in test cases for the same RP AC before generating replacements.
- Emit update proposals when RP AC, product mapping inputs, generated framework artifacts, targets, setup fixtures, execute operations, expected_results, verify rules, evidence refs, or runtime policy change. Update proposals shall use v1 identity/status/revision and traceability fields, include `replaces`, `source_fingerprint`, and `readiness_gaps`, and avoid legacy-only fields.
- Preserve checked-in test history by creating revisions or replacement links instead of silently overwriting checked-in tests.
- Reference expected results as `pending`, `missing`, or linked to F006 output; do not generate expected-result truth in F005.
- Emit a generation readiness report listing readiness status, generated artifact type, gaps, and owner action.

## F006 — Agent Skill: Expected Result Drafting

### Purpose

Use an agent skill to draft expected-result artifacts from explicit RP AC, RP feature specs, input/fixture references, and source context.

### Expected Behavior

The agent skill may draft expected results only when observable inputs, behavior, outputs, and relevant business rules are defined. It shall not invent missing business rules or silently convert assumptions into truth.

Generated expected results shall remain draft until reviewed. Product developers review generated expected results before they are used as regression truth. Release owners may require additional approval when expected results affect release readiness.

### Required Mechanism

- Read `rp_feature_spec.md`, `acceptance_criteria.md`, input references, fixture or sample data, and linked product spec context.
- Draft expected results as separate reviewable artifacts, not hidden runtime state.
- Attach source references from expected results back to RP AC IDs and input references.
- Mark expected-result artifacts as `draft`, `blocked`, or `approved_for_regression`.
- Record assumptions and unresolved gaps inside the expected-result artifact.
- Mark generation as `blocked` when required business rules, input definitions, or output rules are missing.
- Require product developer approval before using generated expected results as regression truth.
- Preserve diffs when expected results change.

## F007 — Generic DSL Test Execution

### Purpose

Execute checked-in execution-eligible package-neutral DSL regression test cases using generated framework-readable suite, run, environment, provider, and traceability artifacts. M1 validates this process with one selected heterogeneous release package pilot, but the framework core remains product-topology agnostic.

### Expected Behavior

The framework shall resolve targets, setup fixtures, execute inputs, expected_results, verify rules, evidence refs, runtime policy, and provider contracts; confirm environment readiness; set up fixtures; execute operations through package adapters/providers; collect actual results; run verification; clean up fixtures; and emit raw execution evidence.

One suite execution is a batch. A batch may contain one or more test runs. Each test run validates one execution-eligible DSL test case and produces run-level evidence. Product/RP labels are carried as traceability metadata so F008 can package RP-level coverage, but the runner makes decisions from framework artifacts only.

The execution process and DSL shall remain package-type-neutral. M1 provider implementations shall prioritize reusable built-in providers for request/response, messaging, DB fixture, deployment readiness, and file/batch execution. External runner support is not the default extension path; it is an approved escape hatch when a legacy or specialized boundary cannot yet be represented by a reusable provider contract.

F007 defines the generic execution core used by RP Regression Execution. Framework Verification tests may exercise F007 through sample generated artifacts, but that fixture evidence is not downstream Product/RP release evidence.

F007 shall not author AC, classify AC readiness, generate tests, regenerate checked-in tests, generate expected results, approve expected results, approve waivers, or decide release readiness.

F007 implementation must not proceed directly to provider runtime expansion until the DSL v1 contract gate is green. The gate requires parser validation, generator output, dry-run blocking, compatibility behavior for execution-focused fields, legacy-only fields, and prohibited governance fields, plus a CLI run/report proof using the same execution-focused DSL v1 test artifact.

### Required Mechanism

- Read `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, checked-in DSL test cases, target definitions, setup fixture refs, execute operation refs, expected-result artifacts, verify rules, evidence refs, runtime policy, provider contracts, and `traceability_map.yaml`.
- Check that test cases declare supported `dsl_version` and include the v1 core contract: identity/status/revision, traceability, targets, scenario, one M1 execute step, verify, evidence, and runtime fields, with setup and expected-results content required when referenced or needed by the scenario.
- Reject new execution-focused DSL artifacts that still use legacy-only fields such as `rp_id`, `ac_id`, `execution_target`, `target_ru_id`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, or `policy`.
- Reject new execution-focused DSL artifacts that contain governance-heavy fields such as approval, waiver, release gate, or risk approval state.
- Check that test cases have execution lifecycle status allowed by the selected run policy.
- Check that required expected-result artifacts are eligible under the expected-result process before they are used by `verify`.
- Validate that every DSL section can be consumed by exactly one AP responsibility path before adapter execution starts.
- Validate that every DSL logical reference to a target runner, execute operation, setup fixture type, expected-result type, verify type, or evidence output has a matching provider contract or supported default.
- Validate each provider contract against a capability registry by `provider_family`, `provider_type`, required fields, supported runtime status, and execution mode before adapter/provider execution.
- Normalize execution-focused DSL v1 traceability into internal run/report metadata without requiring legacy `rp_id`, `ac_id`, `execution_target`, `package_inputs`, `oracles`, `assertions`, or `policy` fields in new test artifacts.
- Reject ambiguous logical target or provider contract resolution rather than falling back silently to the first match.
- Require explicit approval metadata before using an external runner escape hatch, including reason, owner, bounded command or container ref, timeout, inputs, outputs, and evidence map.
- Create one unique batch ID for the suite execution request.
- Create one unique run ID per execution-eligible test case in that batch.
- Resolve the execution mode from the generated run profile as `local_fixture`, `ci_ephemeral`, `sit_deployed`, or `evidence_only`.
- Follow the generated logical target dependency graph and stop downstream execution when a required upstream target validation fails.
- Check deployment and environment readiness before running `sit_deployed` tests.
- Reject or mark invalid runs when suite manifest, run plan, environment binding, target runner, execute operation, setup fixtures, expected_results, verify rules, required deployment evidence, or environment readiness are missing.
- Resolve logical setup fixtures and execute inputs to concrete fixture or generated data.
- Bind runtime values from setup fixtures, execute inputs, context, and previous execute outputs.
- Run setup actions needed by the package fixture lifecycle.
- Execute or validate through the configured release package adapter.
- Collect actual outputs, logs, and execution metadata.
- Run verification against resolved expected_results or deterministic verify rules.
- Run cleanup actions and record cleanup evidence.
- Emit run-level artifacts such as execution report, execution log, actual results, assertion results, observation results, postcondition results, cleanup evidence, and failure details.
- Emit a batch-level summary that lists suite ID or trace package ID, environment profile, batch status, executed run IDs, test case IDs, AC IDs, and run statuses.

## F008 — Coverage and Evidence Package

### Purpose

Package RP-level coverage, traceability, raw execution evidence, failures, and approved exclusions into review-ready evidence artifacts.

### Expected Behavior

The framework shall report coverage against automatable RP-level AC using batch-level evidence, trace generated tests to RP AC, retain raw execution evidence from F007, identify failures with expected-result or inline decision rule results and actual results, and include manual-only or waived AC records where approved.

F008 shall not execute tests, generate tests, generate expected results, approve waivers, change the coverage denominator, or decide release Go/No-Go. Product developers own evidence review for implementation correctness. QA or release owners approve waivers, manual-only exclusions, and release decisions through F010 or a human release process.

### Required Mechanism

- Consume RP AC inventory, AC classification, approved test cases, F007 batch evidence, run-level evidence, expected-result artifacts, waiver records, manual-only approvals, and traceability links.
- Treat `tests/approved/` location plus an allowed DSL lifecycle status such as `active` as execution eligibility; do not treat DSL `status` as expected-result approval, waiver approval, or release approval.
- Read coverage traceability from execution-focused DSL v1 `traceability.*` fields and normalized run evidence, not from legacy-only test case fields.
- Calculate coverage as distinct covered automatable RP-level AC divided by distinct total automatable RP-level AC.
- Count an AC as covered only when at least one run in the selected batch passed and traces to an approved test case for that AC.
- De-duplicate coverage by AC ID when multiple tests cover the same AC.
- Exclude manual-only or waived AC only when approval records exist.
- Keep partial AC in the denominator unless split and reclassified.
- Keep blocked AC visible in the evidence package instead of silently dropping them.
- Link each evidence item to RP ID, AC ID, test case ID, execution batch ID, and execution run ID.
- Include expected result, actual result, failure reason, and source artifact links for failed checks.
- Produce review-ready artifacts such as coverage report, traceability report, evidence index, failure summary, and release review summary.

## F009 — Advanced Spec Readiness

### Purpose

Detect deeper cross-artifact spec gaps and drift after the M1 vertical slice proves the basic readiness workflow.

### Expected Behavior

F009 is not part of M1. It extends readiness beyond basic folder, artifact, AC, and mapping completeness checks.

The advanced checker shall inspect product specs, RP feature specs, RP AC, architecture notes, RP/RU mapping, data assumptions, change records, existing tests, expected-result artifacts, and prior evidence to identify inconsistent or stale information before generation, execution, or release review.

F009 shall not author product specs, author RP feature specs, author RP AC, decide which RU repos belong to an RP, generate regression tests, generate expected results, execute tests, approve waivers, or decide release readiness.

### Required Mechanism

- Compare product intent, RP feature specs, RP AC, RP/RU mapping, architecture notes, change records, expected-result artifacts, generated tests, and prior evidence.
- Detect conflicting specs, duplicated or contradictory AC, missing inputs, missing outputs, missing owners, missing evidence duties, missing version references, and missing validation boundaries.
- Flag stale RP/RU mappings when RP scope, RU version references, or validation boundaries change without matching evidence updates.
- Flag product E2E scenarios that are not mapped to RP AC when they affect package-release confidence.
- Flag tests or expected-result artifacts that are not traceable to RP AC.
- Flag expected results or evidence that are older than the RP version, change record, or mapped RU version reference.
- Flag RP scope or product behavior changes without matching AC readiness, generated-test readiness, or evidence impact records.
- Produce `advanced_spec_readiness_report.md` and/or JSON with issue type, severity, affected product/RP/AC/RU artifact, reason, owner action, and required next step.

## F010 — Release Governance Integration

### Purpose

Connect regression evidence to release governance without fully automating release approval.

### Expected Behavior

The platform shall support release gate policy, waiver workflow, approval records, and Go/No-Go evidence summaries while keeping final release approval human-owned.

### Required Mechanism

- Consume coverage reports, evidence index, waiver records, and manual-only approvals.
- Apply configured release gate thresholds and blocking rules.
- Generate a Go/No-Go summary for release review.
- Require human approval for waiver acceptance and final release decision.
- Preserve approval records with the RP release evidence.

## F011 — Package Type Plugin SDK

### Purpose

Allow additional release package types to plug into the generic test execution process.

### Expected Behavior

The SDK shall define package adapter contracts, evidence contribution expectations, validation boundaries, and onboarding conventions for package types and provider families beyond the M1 heterogeneous pilot.

### Required Mechanism

- Define the minimum adapter contract for execute, validate, collect evidence, and report errors.
- Define required metadata for package type registration.
- Provide onboarding rules for mapping package type behavior into the generic execution process.
- Require package adapters to emit standard evidence and failure records.
- Validate new package type plugins before they can be used in release evidence.
