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
- Next-stage Phase 2 Agent Skill-assisted Product Repo initialization, RU implementation repo scanning, reverse-engineered draft documentation, and new spec creation support when source docs are missing.
- Next-stage Phase 2 Agent Skill generation of regression tests, expected results, suite manifests, Env_Profiles, Provider Instances, and mapping explanations from RP AC and product context. Built-in Provider Contracts are framework-owned and resolved by `provider_type`.
- Auto Regression Test Framework v0.2 as the product-agnostic execution, validation, plugin, result, evidence, and CLI foundation.
- Coverage, traceability, evidence, and release review inputs.

### Out of Scope for Framework v0.2

- Product-level formal AC as a release denominator.
- RU repo-owned primary specs or primary acceptance criteria.
- Full dashboard experience.
- Product/RP/RU topology interpretation inside the framework runtime.
- Framework-side strategy selection from product docs.
- Framework-side test-case generation or expected-result generation.
- Support for every release package type through framework-specific code.
- Fully automated release approval.
- Unreviewed agent-generated expected results.

## 3.3 v0.2 Full Pre-release Framework Boundary

v0.2 validates the framework baseline through a feature-complete pre-release execution contract. It is not a minimum MVP. It should be complete enough for pilot project execution and Phase 2 Agent Skill integration, while still allowing breaking changes before v1.0.

Framework v0.2 includes:

- DSL v0.2 schema for identity, status, revision, tags, labels, source refs, compatible profiles, optional `data`, operation `inputs`, targets, setup operations, execute operations, expected refs, verify checks, evidence, and runtime policy.
- Env_Profiles for local, CI, SIT, and preprod execution permissions plus provider environment bindings.
- Framework-owned Provider Contracts plus Provider Instances and Env_Profiles for isolated local/CI targets and deployed SIT targets.
- Suite selection by test ID, suite, tag, and profile.
- Provider type catalog for REST, gRPC, Kafka, NATS, JDBC, Kubernetes, VM, file/batch, and approved external runner boundaries.
- Execute operation catalog defined by Provider Contracts, including command, API, SQL, message, readiness, runtime, and file operations.
- Fixture manager with scoped setup/cleanup policy.
- Verify/assertion catalog for basic, structure, collection, numeric/time, file, state, event, and custom verify types.
- DB and event polling with bounded timeout, poll interval, and final-observation evidence.
- Expected-result artifact support for literal, file, JSON, YAML, CSV, schema, DB snapshot, and event payload truth sources.
- Parameter expansion with per-parameter run result and evidence.
- Evidence collector with secret masking and evidence indexing.
- Standard result JSON and technical failure classification.
- Documented v0.2 public interface with controlled breaking changes allowed before v1.0 covering CLI invocation, DSL/test definition fields, Provider Contract fields, Provider Instance fields, Env_Profile fields, result schema, evidence paths, exit codes, stable output keys, and support-command boundaries.
- Provider and verify plugin contracts.

Framework v0.2 excludes:

- RP/RU product interpretation and topology parsing.
- Product strategy selection from product docs.
- Agent-generated test cases and expected results.
- Cross-package orchestration as a product-topology feature.
- Production data unless masked and approved.
- Dashboard-driven release governance.
- Fully automated waiver, manual-only, or release approval.
- Business-level failure triage.

### Staged Provider Boundary

Framework v0.2 delivery is staged. The current framework verification target is not the same as the selected heterogeneous pilot target.

| Provider Area | Framework Verification Target | Heterogeneous Pilot Target |
|---|---|---|
| Request/response | REST and native descriptor-driven gRPC unary Provider Contracts with payload binding, timeout, output refs, evidence, and explicit local/CI mock or stub endpoint bindings. | Pilot endpoint validation remains required for release acceptance. |
| WireMock-backed protocol mocks | WireMock HTTP mock remains the P0 HTTP mock proof. SOAP mock and gRPC unary mock are PR-008 provider capability slices: `soap_mock` uses WireMock HTTP/XML/SOAP conventions; `grpc_mock` uses the WireMock gRPC extension and descriptor refs. | Pilot SOAP/gRPC release evidence still requires owner-provided RP artifacts and real environment proof where release acceptance depends on real endpoints. |
| Messaging | Local/mock plus native Kafka/NATS/IBM MQ client-provider publish, put, browse, consume/observe, and payload-match behavior with topic/subject/queue binding keys, payload binding, timeout, correlation checks, and contract-defined output refs. | Pilot broker validation remains required for release acceptance; broker/server provisioning, Kafka request/reply, IBM MQ destructive get, and persistent broker/queue purge are future work only if selected RP acceptance requires them. |
| DB fixture | JDBC setup, verification query, cleanup SQL refs, cleanup strategy, isolation key, cleanup evidence, and explicit local/CI mock DB, ephemeral DB, or disposable schema bindings. | Same contract against the selected pilot DB fixture boundary. |
| Deployment readiness | Local/mock plus native K8s/VM bounded readiness evidence with deployed version ref, timeout, output ref, K8s `kubectl` or direct API probes, K8s pod log tail bound, VM command refs where configured, and bounded probe behavior. | Owner-provided pilot K8s/VM validation remains required for release acceptance. |
| External runner | Approved command-runner escape hatch with provider safety approval metadata, bounded timeout, inputs, outputs, evidence map, and mapped-artifact checks. External runner approval is provider safety approval, not release approval. | Optional only when no reusable built-in provider can represent the selected boundary. |

Local and CI runs are expected to replace most external service, database, messaging, K8s, and VM dependencies with explicit mock, stub, ephemeral, fake-topic, embedded-broker, disposable-schema, or generated-data bindings. This replacement must be declared through Env_Profile dependency policy, Provider Contract runtime modes, Provider Instance allowed runtime modes, and Env_Profile provider `runtime_mode`; the framework must not silently fall back to mocks.

The product shall not claim downstream RP release readiness merely because framework verification passes with local/mock providers or simulated broker tests. Native provider paths become release-accepted only after their reusable runtime, contract validation, evidence mapping, verification cases, and owner-provided pilot environment evidence are present.

Framework v0.2 may expose build-tool execution through governed provider types such as `external_runner` or a reusable future provider. It does not decide which product, RP, or RU should use them. The Phase 2 Agent Skill records the product-side strategy reason in generated mapping explanation artifacts.

### Current Maturity Priority

The current stage prioritizes framework maturity over Phase 2 Agent Skill maturity. The framework must become contract-complete for DSL validation, generated artifact validation, provider capability registry, fixture lifecycle, execution, verification, result JSON, evidence, report, and plugin contract validation. Phase 2 Agent Skills may remain next-stage as long as their future outputs target these stable framework contracts.

The immediate Track A target is contract completeness, not runtime completeness. Track A must document and sample-validate the public interfaces for DSL Test Case, Provider Contract, Provider Instance, Env_Profile, CLI, result, evidence, validation error taxonomy, secret guardrails, and P0 provider/verify catalog.

Track B proves one complete Golden E2E framework lifecycle with a deterministic framework-owned fake provider and checked-in `samples/golden_e2e/` artifacts. Track B validates, executes, verifies, writes evidence/result JSON, and reports using the public CLI, but it is not provider-expansion work and is not downstream RP release evidence.

Track C implements only the selected v0.2 P0 provider capability runtime after Track B proves the reusable framework lifecycle: WireMock, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and evidence. PR-008 extends the mock-service area with WireMock-backed `soap_mock` and unary `grpc_mock` capability. Kafka, IBM MQ, native REST/gRPC endpoint certification, K8s, VM, external runner, Oracle/DB2 Testcontainers, streaming gRPC, and broader heterogeneous provider behavior remain outside Track C unless moved into P0 by decision log.

## 3.4 Feature List

| Feature ID | Feature | Purpose | v0.2 Scope |
|---|---|---|---:|
| F001 | Product Repo Bootstrap, RU Repo Discovery, and Readiness Agent Skill | Initialize the Product Repo structure, optionally scan RU implementation repos to draft missing docs/specs, produce deterministic readiness reports, and use an agent skill to explain owner-actionable next steps | Phase 2 Agent Skill next stage; CLI foundation optional |
| F002 | Release Package Creation Guide and Completeness Agent Skill | Tell owners how to create an RP, scaffold required RP artifact locations, and check whether required RP artifacts are complete | Phase 2 Agent Skill next stage; CLI foundation optional |
| F003 | RP Feature Spec and AC Intake Agent Skill | Consume owner-authored or draft RP feature specs, preserve approval/source state, and validate formal Product Owner / PM / SA-owned acceptance criteria | Phase 2 Agent Skill next stage |
| F004 | Agent Skill: Product Mapping Translation | Translate human-authored Product/RP/RU context into framework-readable suite, Env_Profile, Provider Instance, traceability, and mapping explanation artifacts | Phase 2 Agent Skill next stage |
| F005 | Agent Skill: AC and Execution Context Readiness with DSL Test Drafting | Use agent reasoning to classify AC/execution readiness and draft package-neutral DSL test skeletons or executable regression test cases | Phase 2 Agent Skill next stage |
| F006 | Agent Skill: Expected Result Drafting | Draft reviewable expected-result artifacts from explicit RP AC and source context | Phase 2 Agent Skill next stage |
| F007 | Generic DSL Test Execution | Execute checked-in package-neutral DSL v0.2 test cases using Env_Profiles, Provider Instances, framework-owned Provider Contracts, fixture lifecycle, verify rules, polling, result schema, and evidence collection | Framework v0.2 core |
| F008 | Coverage and Evidence Package | Report RP AC coverage, traceability, execution evidence, failures, waivers, and release review inputs from normalized framework results | Framework v0.2 core |
| F009 | Advanced Spec Readiness | Detect deeper cross-artifact spec gaps and drift across product, RP, architecture, data, and change history | Later Agent Skill |
| F010 | Release Governance Integration | Apply release gate policy, waiver workflow, and Go/No-Go records | Later Governance |
| F011 | Provider and Verify Plugin Contracts | Support additional provider types and verify types through catalogued Provider Contracts, Provider Instances, and plugin contracts | Framework v0.2 core |

### PR-008 WireMock-backed SOAP and gRPC Mock Feature Boundary

SOAP and gRPC mocks are mock-service provider capabilities, not RU deployment features. They follow the suite/batch lifecycle:

```text
provision mock service endpoint
-> expose predefined generated endpoint binding
-> RU starts or is already ready through an external/local/CI workflow
-> run one or more DSL test cases
-> verify mock interactions
-> reset or stop framework-owned mock services
```

`soap_mock` shall use WireMock HTTP/XML behavior for SOAPAction/header matching, XPath request matching, static XML response stubs, request journal evidence, server log evidence, HTTP response evidence, and assertion evidence. `grpc_mock` shall use the WireMock gRPC extension with descriptor/proto refs, unary request/response JSON fixtures, request journal evidence, and protobuf JSON diff evidence. The framework must not build a separate SOAP/gRPC server unless WireMock-backed implementation cannot satisfy a documented future requirement.

The test case DSL may load stubs and verify interactions, but it must not start or restart RU, provision mock servers directly, or embed mock endpoint URLs. Endpoint values must be supplied by Env_Profile provider bindings through predefined generated refs such as `generated://payment-soap-mock.endpoint_url` or `generated://customer-grpc-mock.target_uri`.

## 3.5 Next-Phase Readiness

The baseline is ready for architecture design when these conditions are true:

- Product, RP, and RU responsibilities remain separated as defined in the baseline.
- Release Package remains the release unit and primary regression evidence unit.
- Formal release AC remain RP-level; product E2E scenarios provide context only.
- F001 through F006 have purpose, expected behavior, required mechanism, and Phase 2 Agent Skill support evidence; F007, F008, and F011 have framework acceptance criteria in `docs/03-acceptance/04_acceptance_criteria.md`.
- Minimum RP artifacts, product mapping inputs, and generated framework artifact contracts are defined.
- v0.2 full pre-release scope, non-goals, success metrics, and human approval boundaries are explicit.

Implementation may start only for framework slices whose inputs are ready:

- Current-stage framework work starts with F007, F008, and F011 foundation: DSL v0.2 validation, generated artifact validation, Env_Profile resolution, suite selection, parameter expansion, fixture lifecycle, framework Provider Contract catalog validation, Provider Instance validation, provider capability registry, provider/verify plugin contracts, result schema, evidence schema, report, and secret guardrails.
- F007 requires checked-in execution-eligible DSL v0.2 test artifacts, Env_Profiles, Provider Instances, targets, optional data catalog, setup operations, operation inputs, execute operations and outputs, expected refs, verify rules, polling policy, evidence requirements, runtime policy, provider registry support, plugin contracts, and result schema validation.
- F008 requires source-ref based AC inventory, execution evidence format, traceability rules, batch/run evidence, result JSON, and approved waiver or manual-only records when those records are consumed as evidence metadata.
- F011 requires materialized Provider Contract, Provider Instance, Env_Profile, provider plugin, and verify plugin contract documents, provider capability registry rules, version compatibility behavior, required field validation, unsupported capability blocking, and dry-run evidence.

Phase 2 Agent Skill work for F001 through F006 may start in the next stage. It requires owner-provided Product/RP artifacts, explicit RP/RU mapping, reviewed AC, and generated framework artifact contracts. It must not block current-stage framework maturity unless a missing framework contract prevents generated artifacts from being validated or executed.

Before full v0.2 pilot validation, the responsible owner shall supply the pilot RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, desired provider types, Provider Instance identities, binding key ownership, and any approved external-runner need. The Agent Skill must translate those inputs into generated framework artifacts before downstream RP execution can start, but this is a pilot adoption gate rather than a current-stage framework maturity gate.

For implementation planning, a slice is ready only when its Provider Contract can name the provider type, allowed operations, allowed input keys, required inputs, output refs, evidence outputs, failure codes, valid Provider Instance shape, public `support_status`, AP owner, and verification case. If any of those cannot be stated, the slice remains a spec/design task rather than an implementation task.

## 3.6 CI/CD and Environment Execution Policy

RP regression can run in local, CI, SIT, or preprod depending on the RP validation boundary.

- `local`: run against local fixtures, files, mocks, stubs, generated data, ephemeral DBs, fake topics, embedded brokers, or deterministic sample inputs. External service, DB, messaging, K8s, and VM dependencies should be replaced unless a native local dependency is explicitly declared.
- `ci`: run in CI against mocks, stubs, temporary services, containers, disposable schemas, fake topics, embedded brokers, queues, or jobs provisioned by the pipeline. External dependencies should default to mock/stub/ephemeral replacement unless the Env_Profile explicitly allows a native CI dependency.
- `sit`: run against already deployed RU versions in SIT when real integration behavior cannot be validated locally or ephemerally.
- `preprod`: run against a production-like environment only with explicit approval, masking, and safety constraints.

Mock substitution rules:

- Provider Contracts must declare which runtime modes are supported, such as `native`, `mock`, `stub`, or `ephemeral`.
- Provider Instances may select only runtime modes allowed by the referenced Provider Contract.
- Env_Profiles choose the actual `runtime_mode` for each `provider_id` and supply `providers.<provider_id>.binding_keys` values for mock endpoints, stub servers, fake topics, embedded brokers, ephemeral DBs, disposable schemas, or native dependency references.
- Env_Profiles for `local` and `ci` must define the allowed dependency provisioning policy, including whether Testcontainers or an equivalent ephemeral provisioner may start DB, broker, mock-service, or other dependency containers before provider execution. Concrete generated values from provisioned dependencies may be referenced by Env_Profile binding keys through `generated_ref`; they do not belong in the DSL.
- `sit` and `preprod` must not use mock substitution for release evidence. Any exception must be explicitly marked as framework verification evidence and blocked from downstream RP release-readiness claims.

For RPs that include multiple RUs, the Agent Skill shall translate `rp_ru_mapping.yaml` into a framework-readable target dependency graph, selected Env_Profile, Provider Instances, deployment readiness refs, validation boundary, and evidence responsibility labels.

The framework may verify deployment readiness through generated Env_Profile provider bindings before SIT execution, but v0.2 does not own CD deployment orchestration. CD or the environment owner must deploy required product components before SIT regression starts.

## 3.7 Test Case Lifecycle

Regression test cases are managed as durable RP artifacts. The framework shall not regenerate test cases on every execution run.

The framework uses a package-neutral regression test case DSL so different Products and Release Packages can express repeatable auto regression tests through the same artifact model. The DSL describes what RP behavior must be validated, while package-specific execution remains isolated behind framework-owned Provider Contracts, Provider Instances, and Env_Profiles.

The DSL is not a BDD story, shell script, provider configuration, or generated run log. It is a versioned test contract with enough structured data for the framework to validate, plan, bind, execute, assert, and report a regression test repeatedly.

The DSL must answer execution questions in a package-neutral, execution-focused way:

| Question | DSL Responsibility | Not DSL Responsibility |
|---|---|---|
| What is being validated? | Optional `source_refs`, optional opaque `labels`, setup/execute intent, and `verify.checks[]` | Writing or changing RP feature truth or making runtime decisions from product labels or source refs |
| What targets are involved? | `targets.<name>.provider_id` and `execute.operations[].target`; the active profile is selected by CLI or suite manifest | Inferring RP/RU membership, endpoint, topic, credential, or deployment topology |
| What data or state is needed? | Optional `data` catalog entries and operation-level `inputs` | Inline secrets, physical DB connection strings, or package-specific loaders |
| What operation is performed? | `setup.operations[].operation`, `execute.operations[].operation`, `verify.checks[].operation` when provider-backed, `cleanup.operations[].operation`, target, inputs, and captured outputs | Shell scripts, endpoint URLs, queue drivers, DB credentials, or deployment commands embedded in the test |
| How is pass/fail decided? | `verify.checks[]` with explicit captured-output actual/expected, provider-backed operation inputs, or deterministic framework assertion semantics | Approving expected-result truth or inventing business rules |
| What must be retained? | `evidence.required` traceable to execution or verification outputs | Release approval or waiver approval |
| What runtime policy applies? | `runtime.timeout` and `runtime.retry` | Release gate, waiver, or risk approval workflow |

| DSL Area | Purpose | Primary AP Consumer |
|---|---|---|
| Identity and source refs | Identify DSL version, test ID, execution status, revision, source references, and opaque report labels. | Definition and Validation |
| Targets and provider references | Describe named targets by `provider_id`; provider capability comes from the referenced Provider Contract. | Discovery and Context / Planning and Binding |
| Data and operation inputs | Optionally reference reusable data sources, then bind them through operation `inputs`. | Planning and Binding / Fixture and State Manager |
| Parameterization | Declare a reviewed parameter set reference when one logical test must run with multiple input variants. | Planning and Binding / Evidence and Reporting |
| Execution | Declare readable operations, target IDs, runtime inputs, and captured outputs without embedding provider-specific scripts. | Execution Engine |
| Verification | Define explicit `verify.checks` over captured outputs, DB state, events, files, or provider-backed observations. | Oracle and Assertion Engine |
| Evidence and runtime | Declare concrete evidence refs, timeout, and retry. | Evidence and Reporting |

The 7 AP are the framework capability areas behind the DSL lifecycle: Definition and Validation, Discovery and Context, Planning and Binding, Fixture and State Manager, Execution Engine, Oracle and Assertion Engine, and Evidence and Reporting. Provider configuration belongs in framework-owned Provider Contracts, Provider Instances, and Env_Profiles; DSL tests may reference Provider Instances only by `provider_id`.

Each AP must be independently explainable in readiness and execution reports. A report that says only "DSL invalid" or "execution failed" is not sufficient; it must name the AP, field path or Provider Contract, affected test case, affected AC, reason, and owner action.

The DSL field set should stay execution-focused but complete enough for v0.2 pre-release execution. v0.2 supersedes the earlier minimum v1 subset and defines the framework-owned generic execution contract. Top-level sections may be present as empty maps for schema stability, but content is required only when setup, execute, cleanup, verify, or evidence references need them.

| Field or Section | Requirement Rule | Why It Is Required | Consuming AP |
|---|---|---|---|
| `dsl_version` | Always required | Selects supported schema and compatibility rules. | Definition and Validation |
| `test_case_id`, `status`, `revision` | Always required | Gives every run stable artifact identity and execution lifecycle state. | Definition and Validation / Evidence and Reporting |
| `source_refs` | Optional | Gives reports a stable traceability link to reviewed AC, feature specs, defects, ADRs, or other source references without making runtime execution depend on product topology. | Definition and Validation / Evidence and Reporting |
| `labels` | Optional; required only when generated artifacts need product/RP/RU labels in reports | Carries opaque report metadata such as product, package, or runtime unit. Framework runtime must not branch on labels. | Evidence and Reporting |
| `compatible_profiles` | Optional; required only when a test is limited to named Env_Profiles | Lets the runtime block a test when the selected Env_Profile is not compatible. | Definition and Validation / Planning and Binding |
| `targets.<name>.provider_id` | Always required | Names the Provider Instance used by the test without exposing physical environment values. The active Env_Profile is selected by CLI or suite manifest. | Discovery and Context / Planning and Binding |
| `data` | Optional; used when one or more operations reuse reviewed artifact refs or safe literals | Declares reusable test data sources only. Binding happens in operation `inputs`. | Planning and Binding |
| `setup.operations` | Required when the test needs precondition data, mutated state, mock setup, seed data, or cleanup | Declares provider-backed setup operations before execution. | Fixture and State Manager |
| `execute.operations` | Always required; each operation ID must be unique | Declares operations allowed by the selected Provider Contract, target IDs, operation inputs, and contract-defined observable outputs. v0.2 may run one or more ordered execute operations when each operation is explicit and evidence-addressable. | Execution Engine |
| `verify.checks` | Always required | Declares how pass/fail is evaluated. Provider-backed checks use `target`, `operation`, and `inputs`; framework assertions may use `type`. | Oracle and Assertion Engine |
| `evidence.required[]` | Always required | Declares what concrete execution or verification outputs must be retained. | Evidence and Reporting |
| `runtime.timeout`, `runtime.retry` | Always required | Bounds execution duration and retry behavior. | Execution Engine |

Conditional fields are required only when setup, execute, cleanup, verify, or evidence references need them:

| Conditional Field | Required When | Not Allowed To Contain |
|---|---|---|
| `setup.operations[].cleanup` or `cleanup.operations[]` | The test creates, mutates, seeds, publishes, or deletes state. | Hidden destructive actions without cleanup references. |
| `setup.operations[].inputs`, `execute.operations[].inputs`, `verify.checks[].inputs`, `cleanup.operations[].inputs` | Runtime inputs are passed to the selected Provider Contract operation. Input map keys are provider input names. Values use `ref` or safe literal `value`. | Secrets, endpoint URLs, connection strings, topics, namespaces, or provider code. |
| `execute.operations[].outputs` | Later verification or evidence references execution output. Output refs must be declared by the referenced Provider Contract. | Uncaptured implicit result paths. |
| `verify.checks[].actual` | A framework assertion reads a captured output file or captured execution value. It is not required for provider-backed checks that use `target`, `operation`, and `inputs`. | Hidden provider lookup rules or JSONPath expressions overloaded into the actual ref. |
| `verify.checks[].selector` | A framework assertion compares or checks a field inside a structured actual result, including `json_path_equals`, `json_path_absent`, and structured `numeric_tolerance` checks. `actual` names the captured output; `selector` names the JSON/YAML path inside it. | Provider-specific parser code, captured-output refs overloaded as JSONPath expressions, or runtime-inferred selectors. |
| `verify.checks[].options` | The check needs timeout, polling, tolerance, normalization, or ignore paths. | Release gate or risk approval policy. |
| `data.<name>.ref` or `data.<name>.value` | A reviewed value or artifact is reused by more than one operation, or a literal is clearer as a named test datum. | Endpoint URLs, connection strings, secrets, environment values, dynamic data discovery, or unreviewed runtime-created cases. |

Optional fields such as tags, notes, or replacement links may improve maintenance, but governance-heavy fields must not be required for first execution.

The DSL v0.2 contract is a pre-implementation gate for F007 provider runtime work. Before provider dispatch, sample fixture migration, or native runtime expansion can be claimed complete, the framework must validate the execution-focused DSL shape, generator output, compatibility behavior, and CLI run/report consumption described in `docs/02-architecture/06_artifact_contracts.md`.

Validation alone is not enough. At least one checked-in `tests/approved/` execution-focused DSL v0.2 artifact with `status: active` must be accepted by `run`, produce run and batch evidence, emit standard result JSON, and be accepted by `report` as review-ready coverage before provider runtime expansion can claim execution-focused DSL support.

New execution-focused DSL artifacts must:

- Reference runtime targets through `targets.<name>.provider_id`. Use `compatible_profiles` only to restrict allowed profiles; do not select runtime behavior from labels.
- Use operations allowed by the referenced Provider Contract, such as `http_request`, `unary_call`, `kafka_publish`, `kafka_observe`, `mq_put`, `mq_browse`, `nats_publish`, `nats_observe`, `db_seed`, `db_cleanup`, `db_query`, `db_record_exists`, `check_deployment_ready`, `run_command`, or `run_and_collect`.
- Use `source_refs` for source truth and `labels` for opaque product/RP/RU reporting metadata. New generated DSL must not require `traceability.package_id`, `traceability.acceptance_criteria_id`, or `traceability.source`; those fields are compatibility inputs until migrated.
- Use `targets`, optional `data`, `setup` when needed, `execute`, `verify`, `evidence`, and `runtime` instead of framework-internal legacy fields.
- Allow multiple `execute.operations[]` items only when each operation has a unique ID, declared target, Provider Contract operation, inputs, outputs, ordering semantics, and evidence refs. The framework must block ambiguous or unsupported multi-step execution.
- Capture `execute.operations[].outputs` whenever verification or evidence references execution results.
- For structured output checks, keep `actual` as the captured output reference and declare `selector` as the canonical field path. `path` and `json_path` are compatibility aliases only; new generated DSL must use `selector`.
- For provider metadata checks such as `response_status_equals`, declare the deterministic `expected` value and let request/response provider evidence supply the HTTP status. Declare `actual` plus `selector` only when the status is read from a captured structured output.
- Use operation `inputs` to bind reviewed refs or safe literals to Provider Contract input names. Input keys must be allowed by the referenced Provider Contract.
- Use optional `data.<name>.ref` or `data.<name>.value` only as a reusable data source catalog. Do not categorize data by setup/execute/cleanup/expected lifecycle.
- Reference reviewed artifacts or safe literals from operation input values using `ref` or `value`. New DSL artifacts must not use `parameters.strategy`, inline `parameters.cases`, or `${parameters.<name>}` references.
- Keep provider implementation details in framework-owned Provider Contracts, Provider Instances, or Env_Profiles, not inside the test case body.
- Do not define provider `binding_keys` in DSL. Provider binding keys can be supplied only by Env_Profile `providers.<provider_id>.binding_keys`.
- Fail validation when any operation input key is not allowed by the referenced Provider Contract.
- Fail validation when any setup/execute/cleanup operation is not allowed by the referenced Provider Contract.
- Fail validation when a referenced output ref is not declared by the referenced Provider Contract.
- Fail validation before execution when required fields, conditional fields, or supported enum values are missing.

The DSL shall reject or block these concerns from the test case body:

- Secrets, credentials, tokens, or production data.
- Physical provider implementation details such as endpoint URLs, shell commands, queue client settings, DB connection strings, or loader code.
- Dynamic data-selection queries, combinatorial parameter generation, or unreviewed runtime-created cases in v0.2.
- CD deployment instructions or environment provisioning scripts.
- New RP feature behavior, AC wording, expected-result approval, waiver approval, release approval, release gate, or risk approval.
- Governance-heavy fields such as `approval_status`, `approved_by`, `approval_required`, `waiver`, `release_gate`, `risk_approval`, or governance workflow state.

v0.2 defines the full pre-release DSL contract. A provider or verify enum may be listed only when the framework can validate its contract and either execute it or block it with a precise unsupported-capability finding.

The lifecycle is:

```text
RP AC / execution context ready
-> draft test skeleton or draft executable test case using execution-focused DSL v0.2
-> product developer review
-> checked-in RP test artifact
-> repeated execution
-> update or retire when RP AC, mapping, input, fixture, Provider Contract, Provider Instance, Env_Profile, or expected result changes
```

Execution-eligible test cases should be stored in the RP `tests/` folder. In the current Product Repo model this means `docs/08-release/release-packages/<rp_id>/tests/`. If a dedicated RP repository is introduced later, the same `tests/` lifecycle applies inside that repo.

The agent may propose new or updated test cases when source artifacts change, but it shall not silently overwrite checked-in approved tests.

## 3.8 Pre-Implementation Documentation Gate

Any implementation slice that changes DSL validation, generation, execution, evidence, reporting, provider runtime, or AP boundaries must update the documents first. The slice is not implementation-ready until the feature/spec, architecture design, artifact contract, acceptance criteria, implementation plan, and framework verification test plan agree on:

- The user-visible behavior and non-goals.
- Required, conditional, optional, legacy-compatible, and prohibited DSL fields.
- Data binding, fixture setup, fixture cleanup, expected-result, verify/assert, evidence, and runtime semantics.
- Which AP consumes each DSL section and which Provider Contract resolves package-specific behavior.
- Happy path, failure path, and boundary path acceptance.
- Framework verification tests and downstream RP validation evidence needed to prove the change.

If any of those items cannot be stated without inventing RP behavior, the work remains a spec/design task rather than an implementation task.

## 3.9 Framework Verification and RP Regression Execution Boundary

This document uses two different execution terms:

| Execution Line | Subject Under Test | Primary Command | Evidence Meaning |
|---|---|---|---|
| Framework Verification | This regression framework product | `./mvnw test` and `./mvnw verify` | Proves framework code, contracts, and sample fixture flows behave correctly. |
| RP Regression Execution | A downstream Product Release Package | Product-aware wrapper or pipeline generates framework artifacts, then invokes the generic runtime with `suite_manifest`, `run_plan`, Env_Profiles, Provider Instances, and framework-owned Provider Contract catalog lookup | Produces Product Repo RP evidence for release review. |

`./mvnw test` is the fast framework unit/component verification layer. `./mvnw verify` is the framework integration verification layer and should use a sample Product Repo fixture with local or mock Provider Instances. Neither Maven command requires SIT/UAT deployment, and neither command by itself produces downstream product release evidence.

RP Regression Execution is the runtime capability delivered by F007. Product-aware tooling first turns Product/RP/RU context into framework-readable artifacts. The framework core then reads checked-in DSL tests, `suite_manifest`, `run_plan`, Env_Profiles, Provider Instances, approved truth sources, and the framework Provider Contract catalog, executes or validates the selected suite, and writes batch/run evidence.

SIT/UAT regression is a release package pipeline concern. The framework may verify SIT/UAT readiness and run against an already deployed environment, but v0.2 does not deploy release units as part of Maven framework verification.

## 3.10 v0.2 Capability Selection Matrix

P0 is required for v0.2 core and pilot execution. P1 is strongly recommended for v0.2 but may follow the P0 vertical slice. P2 is later or optional.

| Area | P0 - v0.2 Core | P1 - Strongly Recommended | P2 - Later / Optional |
|---|---|---|---|
| HTTP / AP Mock | WireMock, `rest_client` `http_request` against framework-owned WireMock samples, `http_stub`, `http_mock_called`, `http_mock_request_body_match` | `http_mock_request_count`, `http_mock_not_called` | MockServer |
| DB / Oracle / DB2 | JDBC Provider, `secret_ref` connection, SQL params binding, Oracle / DB2 dialect, `db_record_exists`, query evidence | `db_field_equals`, `db_row_count_equals`, cleanup by marker | Oracle / DB2 Testcontainers |
| Messaging / Event | NATS Provider, `event_published`, `event_payload_match`, `consume_from: test_start_time`, subject handling, event evidence | Kafka client Provider with `kafka_publish`, `kafka_observe`, `kafka_payload_match`; IBM MQ client Provider with `mq_put`, `mq_browse`, `mq_message_exists`, `mq_payload_match`; NATS `event_not_published`, stream handling, consumer handling | Broker/server provisioning, Kafka request/reply, IBM MQ destructive get, advanced broker purge / persistent stream cleanup |
| Polling | `polling_observer`, poll until condition, timeout, `poll_interval`, last observed evidence | Fail-fast on connection error | Advanced retry policy |
| JSON / Schema | `artifact_compare`, `json_match`, `schema_match`, `ignore_paths` | `numeric_tolerance`, `unordered_array`, `partial_match` | `contract_match` |
| File Diff | `artifact_compare`, `file_diff`, normalize, `ignore_order`, `ignore_paths` | `csv_diff`, `yaml_diff`, `json_diff`, `file_exists`, `file_not_empty` | Approval-test workflow |
| Test Data Injection | optional `data` catalog plus Provider Contract operations such as `db_seed`, `db_cleanup`, and `http_stub` | `event_seed`, `event_expectation`, `file_seed`, `config_injection`, `env_injection` | Generated synthetic data |
| Reporting | Standard result JSON, evidence folder | Basic HTML report, Allure format output | ReportPortal integration |

v0.2 may list a provider or verify enum only when the framework can validate its contract and either execute it or block it with a precise unsupported-capability finding.

## 3.11 WireMock HTTP Mock Provider

WireMock is the default HTTP mock provider for local and CI RU-isolated tests.

The framework shall support a `wiremock_http_mock` Provider Contract that can:

- Start or connect to a WireMock instance.
- Load checked-in stub mappings.
- Expose a runtime `base_url`.
- Expose that `base_url` through Provider Contract `bindable_outputs` so another provider can reference it with Env_Profile `generated_ref`.
- Retain request journal and server logs as evidence.
- Support verification such as `http_mock_called`, `http_mock_request_body_match`, `http_mock_request_count`, and `http_mock_not_called`.

WireMock is for local and CI isolated dependency replacement. WireMock shall not replace internal RP/RU dependencies in SIT release evidence. SIT shall use deployed runtime targets unless an approved external simulator is explicitly declared and blocked from downstream release-readiness claims.

```yaml
provider_type: wiremock_http_mock
runtime_modes:
  - mock
required_bindings:
  - mappings_ref
outputs:
  - base_url
  - request_journal
  - server_log
evidence_outputs:
  - request_journal
  - server_log
verify_types:
  - http_mock_called
  - http_mock_request_body_match
  - http_mock_request_count
  - http_mock_not_called
```

The v0.2 framework shall also support a `rest_client` Provider Contract for the checked-in WireMock plus HTTP request provider capability sample. In that sample, the framework resolves `base_url` from Env_Profile, including generated WireMock output via `generated_ref`, executes `http_request`, captures `response.status`, `response.headers`, `response.body`, and `response.duration_ms`, writes `http_request_response` evidence, and runs happy, failure, and boundary checks through `regress run` and `regress report`.

This `rest_client` runtime is framework provider capability evidence for local/CI samples. It does not by itself prove downstream SIT/preprod endpoint readiness; pilot endpoint evidence still requires owner-provided RP artifacts, selected Env_Profiles, and real environment refs.

## 3.12 JDBC / Oracle / DB2 Verification Model

Framework v0.2 shall provide JDBC-based verification for Oracle and DB2.

P0 DB capabilities are JDBC Provider, `secret_ref` connection, SQL params binding, Oracle / DB2 dialect selection, `db_record_exists`, and query evidence. P1 DB capabilities are `db_field_equals`, `db_row_count_equals`, and cleanup by `run_id` / `case_id` marker.

DB verification shall never embed raw credentials in DSL, Env_Profile, result, or evidence. Env_Profile may contain only `secret_ref` or approved runtime-generated connection references. SQL queries shall support parameter binding. Query artifacts should support dialect selection for Oracle and DB2. Query evidence shall include query ref, dialect, masked params, row count, execution duration, and masked sample result. `db_record_exists` shall support timeout and `poll_interval`. DB cleanup should use `run_id` / `case_id` marker where possible.

```yaml
verify:
  checks:
    - id: verify_order_record
      type: db_record_exists
      target: database
      query:
        ref: queries/order_exists.sql
        dialect: oracle
        params:
          order_id: ${data.order_id}
      expected:
        min_rows: 1
        fields:
          status: NORMALIZED
      options:
        timeout: PT60S
        poll_interval: PT5S
```

## 3.13 NATS Event Verification Model

Framework v0.2 shall provide NATS event observation and verification.

P0 NATS capabilities are NATS Provider, `event_published`, `event_payload_match`, `consume_from: test_start_time`, subject handling, and event evidence. P1 NATS capabilities are `event_not_published`, stream handling, and consumer handling.

`event_published` shall support bounded polling through timeout and `poll_interval`. `consume_from: test_start_time` shall be the default behavior when possible to avoid matching old messages. Event evidence shall include subject, key/correlation value when available, observed payload, matched fields, observation window, attempts, and timeout status. When timeout occurs, the framework shall retain last observed evidence. NATS subject handling is P0. Stream and consumer handling are P1 unless the selected pilot requires JetStream.

```yaml
verify:
  checks:
    - id: verify_event_published
      type: event_published
      target: event_bus
      event:
        subject: order.normalized
        key: ${data.order_id}
      expected:
        match:
          $.order_id: ${data.order_id}
          $.status: NORMALIZED
      options:
        timeout: PT60S
        poll_interval: PT5S
        consume_from: test_start_time
```

## 3.14 Kafka and IBM MQ Client Provider Model

Framework v0.2 P1 adds client-side messaging providers for Kafka and IBM MQ. These providers are test-runner clients only: they use connection values supplied by Env_Profile and do not start Kafka brokers, queue managers, Testcontainers, or RUs in this slice. Their Provider Contracts define runtime vocabulary `[native, mock, ephemeral]`; this framework build exposes `executable_runtime_modes: [mock, native]` for local framework fixtures and externally provisioned broker or queue-manager endpoints. `ephemeral` remains vocabulary for future release infrastructure and must block before provider dispatch until implemented.

`kafka` is the canonical Kafka provider type for new artifacts. Existing `kafka_messaging` is a deprecated compatibility alias during the v0.2 transition. The `kafka` Provider Contract shall support `kafka_publish`, `kafka_observe`, and `kafka_payload_match`. Required Env_Profile binding keys are `bootstrap_servers`, `topic`, and `consumer_group`. Optional binding keys are limited to `timeout` and `poll_interval` in this runtime baseline. Kafka observation shall default to `consume_from: test_start_time`, use a test-owned consumer group, and must not commit or mutate shared consumer offsets.

`ibm_mq` is the canonical IBM MQ provider type. It shall support `mq_put`, `mq_browse`, `mq_message_exists`, and `mq_payload_match`. Required Env_Profile binding keys are `queue_manager`, `channel`, `conn_name`, `queue`, and `credential.secret_ref`. Optional binding keys are limited to `timeout` and `poll_interval` in this runtime baseline. IBM MQ verification shall be browse-first for shared queues and may filter by operation-level `correlation_id`. Destructive `mq_get` and queue purge are unsupported in this slice unless a future contract adds explicit destructive opt-in and cleanup policy.

Kafka `topic`, Kafka `consumer_group`, and IBM MQ `queue` are Env_Profile binding keys, not operation-level destination overrides. If one test suite must exercise multiple topics or queues, it should declare separate Provider Instances and Env_Profile provider bindings for those logical destinations.

Kafka and IBM MQ evidence shall include provider identity, target topic or queue, correlation/key metadata when available, payload ref, masked payload sample, observation start/end, attempts, timeout status, matched status, and failure code. Raw broker credentials, authorization headers, connection strings, and secret values must be blocked or masked.

```yaml
execute:
  operations:
    publish_order:
      target: order-events
      operation: kafka_publish
      inputs:
        key:
          ref: data/order_created.json#/order_id
        payload_ref:
          ref: data/order_created.json

verify:
  checks:
    order_message_exists:
      type: mq_payload_match
      target: order-queue
      expected:
        ref: expected/order_message.json
      options:
        timeout: PT10S
        poll_interval: PT1S
```

## 3.15 Polling / Eventually Consistent Verification

Framework v0.2 shall provide a polling engine for eventually consistent verification.

P0 polling capabilities use the framework-owned `polling_observer` Provider Contract for observation targets and include poll until condition, timeout, `poll_interval`, and last observed evidence. P1 polling capability is fail-fast on connection error.

Execute steps shall not be retried automatically as product actions. Verify checks may poll when the verify type supports observation, such as `db_record_exists`, `event_published`, `file_exists`, and deployment readiness checks. Product assertion failures shall not be retried unless expressed as observation polling. Infrastructure connection errors may support fail-fast behavior when configured. Timeout failures shall include last observed evidence.

## 3.16 JSON / Schema / File Diff Verification

Framework v0.2 shall provide structured payload and artifact comparison.

P0 JSON / Schema capabilities use the framework-owned `artifact_compare` Provider Contract for artifact loading and include `json_match`, `schema_match`, and `ignore_paths`. P1 JSON / Schema capabilities are `numeric_tolerance`, `unordered_array`, and `partial_match`.

P0 File Diff capabilities use `artifact_compare` and include `file_diff`, normalize, `ignore_order`, and `ignore_paths`. P1 File Diff capabilities are `csv_diff`, `yaml_diff`, `json_diff`, `file_exists`, and `file_not_empty`.

`ignore_paths` shall support dynamic fields such as `generated_at`, `trace_id`, and `run_id`. `normalize` shall normalize formatting before diff. `ignore_order` shall allow order-insensitive comparison when result order is not business-significant. `schema_match` shall be usable for API response and event payload verification. `selector` remains the canonical way to reference fields inside structured captured outputs.

```yaml
verify:
  checks:
    - id: verify_output_file
      type: file_diff
      actual:
        ref: ${execute.run_subject.outputs.output_file}
      expected:
        ref: ${data.normalized_orders}
      options:
        format: yaml
        normalize: true
        ignore_order: true
        ignore_paths:
        - $.generated_at
        - $.run_id
```

## 3.17 Test Data Injection Model

Framework v0.2 shall provide a test data injection model.

The framework does not invent business data. The framework reads reviewed dataset, fixture, stub, expected-result, and parameter artifacts and injects them into selected provider runtimes.

P0 injection uses optional `data` catalog entries plus Provider Contract operations such as `db_seed`, `db_cleanup`, and `http_stub`. P1 injection types are `event_seed`, `event_expectation`, `file_seed`, `config_injection`, and `env_injection`.

`data` links a test case to reviewed artifact references or small safe literals. It is optional and lifecycle-neutral. DSL references use `${data.<name>}` and resolve to that entry's `ref` or `value`. Legacy `data_binding` and lifecycle category keys are prohibited in new v0.2 DSL artifacts. `data` must not contain raw endpoint URLs, JDBC strings, broker URLs, credentials, or secret values; environment-specific runtime values remain in Env_Profile.

`db_seed` injects Oracle / DB2 or relational DB test data through the selected JDBC provider. `db_cleanup` cleans injected DB data and records cleanup evidence. `http_stub` injects WireMock stub mappings. `event_expectation` may prepare event filters or expected observation configuration. `config_injection` and `env_injection` may inject runtime endpoint references, mock `base_url`, DB connection refs, or broker refs into the subject target through Env_Profile provider binding keys and Provider Contract `bindable_outputs`. Injection evidence shall include source artifact ref, target provider, execution result, cleanup result, and masked runtime values.

```yaml
data:
  order_id:
    value: ORD-001
  seed_sql:
    ref: fixtures/db/seed_orders.sql
  cleanup_sql:
    ref: fixtures/db/cleanup_orders.sql
  payment_api_mappings:
    ref: stubs/payment_api/mappings/

setup:
  operations:
    - id: orders_seed
      target: database
      operation: db_seed
      inputs:
        sql_ref:
          ref: ${data.seed_sql}
        bind_variables:
          order_id:
            ref: ${data.order_id}
    - id: payment_stub
      target: payment_api_mock
      operation: load_stubs
      inputs:
        mock.mappings_ref:
          ref: ${data.payment_api_mappings}
```

## 3.18 Reporting / Evidence Integration

P0 reporting outputs are standard result JSON and evidence folder. P1 outputs are basic HTML report and Allure format output. P2 output is ReportPortal integration.

Standard result JSON is the canonical output. The evidence folder shall contain execution logs, actual artifacts, expected artifact references, assertion diff, query evidence, event evidence, mock request journal, and cleanup evidence. Allure output is an optional export format, not the canonical result model. ReportPortal integration is a future optional integration.

## F001 — Product Repo Bootstrap, RU Repo Discovery, and Readiness Agent Skill

### Purpose

Initialize the Product Repo folder structure, run deterministic readiness checks, optionally scan RU implementation repos to draft missing docs/specs, and use an agent skill to explain whether the repo is ready for RP-level regression work.

### Expected Behavior

The platform shall provide a standard Product Repo bootstrap command and a machine-readable readiness check. The bootstrap command shall create the agreed docs lifecycle folders and starter artifact locations. The deterministic readiness check shall report missing folders, missing RP artifacts, missing stable IDs, missing product mapping input, missing generated framework artifacts when required, and missing RP AC prerequisites.

The readiness agent skill shall read the readiness report, explain the current repo state, classify whether the repo is ready for RP work, and produce owner-actionable next steps. When requested and given RU implementation repo locations, the skill may scan code, build files, API definitions, config, deployment manifests, tests, and README files to draft missing product baseline, RP context, RU inventory, interface summary, and spec placeholders. Reverse-engineered outputs are draft/proposed artifacts with source file and commit refs; they are not formal Product/RP truth until reviewed by the responsible owner. The agent skill may explain and prioritize gaps, but it shall not invent RP scope, formal RP AC, or RP/RU membership.

### Required Mechanism

- Initialize the agreed docs lifecycle folders when they do not exist.
- Create starter placeholders or templates for product baseline, RP specs, RP AC, traceability, evidence, release, operations, and change-control records.
- Accept explicit RU implementation repo inputs such as repo path, branch/commit ref, language/runtime hint, and scan scope.
- Scan RU implementation repos for code structure, public interfaces, build/deployment descriptors, config, tests, README files, and existing API/schema artifacts when owner docs are missing or stale.
- Generate draft or proposed docs/spec artifacts only with source refs, confidence notes, assumptions, and review status.
- Support adding a new spec draft to the Product Repo when the owner provides a title, scope, source refs, and intended Product/RP context.
- Run a deterministic readiness check that required folders exist.
- Check that at least one RP folder or record can be discovered, or report that RP creation is the next owner action.
- Check that required RP artifacts exist or are reported missing.
- Check that RP artifacts reference stable product, RP, and AC identifiers when those artifacts exist.
- Check that product mapping input exists before Agent Skill translation or regression generation.
- Emit a machine-readable readiness report with pass/fail status, missing items, owner action, and next required step.
- Provide an agent skill that reads the readiness report and optional repo-scan evidence, then returns a human-readable readiness explanation, missing-item summary, draft-doc/spec proposals, owner actions, and next command or document to update.

## F002 — Release Package Creation Guide and Completeness Agent Skill

### Purpose

Provide clear instructions and Agent Skill assistance for Product Owner, PM, SA, or the responsible owner to create a Release Package in the Product Repo, then check whether the RP is complete enough for downstream regression work.

### Expected Behavior

The Product Repo shall document how to create an RP. The responsible owner follows the instructions to create the RP folder or record and required artifacts.

The readiness check and agent skill shall verify that each RP has identity, owner, target release, lifecycle status, package type, scope, linked product spec context, source refs, and evidence package location. The skill may scaffold RP artifact drafts and explain gaps, but owner review remains required before the RP is treated as ready.

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
- Provide an agent-assisted RP creation flow that can use owner input plus optional RU scan findings to propose initial RP artifact drafts.
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
- Preserve draft/proposed status for any RP artifact created from RU repo scan findings until the responsible owner reviews it.

## F003 — RP Feature Spec and AC Intake Agent Skill

### Purpose

Consume and govern RP feature specs and formal acceptance criteria, including owner-authored specs and draft specs proposed by the Agent Skill from RU repo scan evidence.

### Expected Behavior

Formal AC shall be authored and owned by Product Owner, PM, or SA at RP level. Product-level docs may define intent and E2E scenarios, and Agent Skill output may propose draft behavior/spec text from RU evidence, but RP AC shall be the source for generation, coverage, and release readiness only after owner review.

The framework shall not invent or author primary RP feature behavior or formal RP AC. The Agent Skill may draft candidate specs from source refs, but it shall keep them marked as draft/proposed until owner review. The framework shall consume, validate, classify, and trace those specs for downstream generation and evidence.

### Required Mechanism

- Read RP feature behavior and AC from Product Owner / PM / SA-maintained spec artifacts.
- Read Agent Skill draft specs when present, including RU repo scan evidence, source refs, assumptions, and review status.
- Support adding a new RP feature spec draft when owner input or RU repo scan evidence identifies missing behavior documentation.
- Require stable AC IDs inside each RP.
- Require reviewed/approved status before draft specs or reverse-engineered findings can be used as formal generation truth.
- Validate that AC include observable inputs, actions, outputs, and allowed side effects when they are marked ready for generation.
- Classify each AC as automatable, manual-only, partial, waived, or not ready.
- Link product E2E scenarios to one or more RP AC when cross-RP context matters.
- Use RP AC IDs as the denominator for coverage and release evidence.

## F004 — Agent Skill: Product Mapping Translation

### Purpose

Translate human-authored Product/RP/RU context into framework-readable artifacts without moving product topology interpretation into the framework core.

### Expected Behavior

The framework shall not decide which RU repos are included in an RP, which RU is Java/Maven, which provider type a RU should use, or how SIT topology maps to deployed components. SA, tech lead, product developer, or the responsible owner defines RP membership in `rp_ru_mapping.yaml` and related product docs.

The Agent Skill shall check that product mapping is complete enough to generate framework artifacts. It shall emit explicit `suite_manifest.yaml`, `run_plan.yaml`, Env_Profiles, Provider Instances, and `traceability_map.yaml`. The framework validates those generated artifacts, resolves Provider Contracts from the framework catalog, and treats RP/RU labels as opaque report metadata.

### Required Mechanism

- Read owner-authored `rp_ru_mapping.yaml`, release manifest, deployment manifest, SIT topology, and related product docs.
- Check that product mapping entries have enough information to choose logical targets, provider types, Provider Instance IDs, Env_Profiles, evidence responsibility, and target dependencies.
- Generate `suite_manifest.yaml` with selected DSL tests, source refs, trace labels, and coverage source refs.
- Generate `run_plan.yaml` with logical target dependency graph, selected Env_Profile ref, execution mode, and suite-level runtime constraints.
- Generate Env_Profile artifacts with trigger, isolation/dependency model, constraints, data policy, max duration, runtime modes, and `providers.<provider_id>.binding_keys`.
- Generate Provider Instances that declare `provider_id`, `provider_type`, operation selections, defaults, evidence choices, and failure mappings without redefining binding key schema.
- Generate Env_Profile provider bindings that supply environment-specific actual values for Provider Contract binding keys.
- Reference existing framework-owned Provider Contracts through each Provider Instance `provider_type`. Generate suite-local Provider Contracts only when an approved custom provider or explicit contract snapshot pinning mode is selected.
- Generate `traceability_map.yaml` with product/RP/RU/source labels used for reporting only.
- Generate a mapping explanation report that records selected provider type, Provider Instance ID, selected Env_Profile, strategy selection reason, source facts used, unresolved assumptions, and validation warnings.
- For strategy-sensitive provider types such as an external runner or build-tool driven provider, record the product-side reason, for example implementation stack, build tool, and supported test strategy. The framework only validates the Provider Contract and Provider Instance; the Agent Skill owns eligibility reasoning.
- Report missing or incomplete product mapping as Agent Skill readiness gaps before framework execution is requested.
- Never require the framework core to inspect RP/RU membership, implementation language, release manifest semantics, or SIT topology.

## F005 — Agent Skill: AC and Execution Context Readiness with DSL Test Drafting

### Purpose

Use an agent skill to reason over RP specs, RP AC, RP/RU mapping, release context, deployment context, and generated framework artifacts before drafting package-neutral DSL regression tests.

### Expected Behavior

The agent skill shall classify both AC readiness and execution context readiness. It shall not draft tests from ambiguous AC. It may generate a draft package-neutral DSL test skeleton when AC is ready but execution context is incomplete. It may generate a draft executable DSL regression test case only when both AC and execution context are ready.

Product developers own AC clarification and manual expected-result input. QA or release owners approve manual-only and waiver classifications when those classifications affect release evidence or coverage.

### Required Mechanism

- Read `rp_feature_spec.md`, `acceptance_criteria.md`, product mapping inputs, generated `suite_manifest`, `run_plan`, Env_Profile, target references, setup operation references, execute operation references, expected-result references, verify rules, and validation boundaries.
- Check AC readiness: AC ID, linked RP feature, observable input, behavior, expected output, and pass/fail condition.
- Check execution context readiness: named targets, provider_id, provider_type, env_profile_id, Env_Profile provider binding, setup operation or data source, cleanup reference when state is mutated, execute operation, execute input refs, execute output refs, deployment readiness ref, validation boundary, expected refs, verify type, captured-output actual/expected, provider-metadata expected, or target/query/event semantics, evidence refs, and runtime policy.
- Map each generated DSL section to the AP that will consume it: identity, `source_refs`, and optional `labels` to Definition and Validation, targets/setup/execute inputs to Planning and Binding, setup operation fields to Fixture and State Manager, execute operations to Execution Engine, expected refs/verify to Oracle and Assertion Engine, and evidence/runtime fields to Evidence and Reporting.
- Refuse executable drafting when an AP cannot be selected or when a required AP input would have to be invented by the agent.
- Mark ambiguous AC as `not_ready_for_generation`.
- Generate `draft_test_skeleton` only when AC is ready but execution context is incomplete. The skeleton shall use v0.2 identity/status/revision, `source_refs`, optional `labels`, plus `source_fingerprint` and `readiness_gaps`; it shall not include executable sections that require invented context.
- Generate `draft_executable_test_case` using the package-neutral test case DSL only when AC and execution context are both ready.
- Include `dsl_version` and all required DSL identity/status/revision, optional `source_refs`, optional `labels`, targets, setup, execute, expected refs, verify, evidence, and runtime fields in every generated executable draft.
- Do not emit legacy-only fields such as `rp_id`, `ac_id`, `artifact_status`, or old `traceability.*` fields in new generated test-case drafts.
- Store generated test drafts as reviewable artifacts instead of transient execution state.
- Detect existing checked-in test cases for the same RP AC before generating replacements.
- Emit update proposals when RP AC, product mapping inputs, generated framework artifacts, targets, setup operations, execute operations, expected refs, verify rules, evidence refs, or runtime policy change. Update proposals shall use v0.2 identity/status/revision, `source_refs`, optional `labels`, include `replaces`, `source_fingerprint`, and `readiness_gaps`, and avoid legacy-only fields.
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
- Attach source references from expected results back to RP AC source refs and input references.
- Mark expected-result artifacts as `draft`, `blocked`, or `approved_for_regression`.
- Record assumptions and unresolved gaps inside the expected-result artifact.
- Mark generation as `blocked` when required business rules, input definitions, or output rules are missing.
- Require product developer approval before using generated expected results as regression truth.
- Preserve diffs when expected results change.

## F007 — Generic DSL Test Execution

### Purpose

Execute checked-in execution-eligible package-neutral DSL regression test cases using generated framework-readable suite, run, Env_Profile, provider, and traceability artifacts. v0.2 validates this process with one selected heterogeneous release package pilot, but the framework core remains product-topology agnostic.

### Expected Behavior

The framework shall resolve targets, setup inputs, execute inputs, cleanup inputs, expected refs, verify rules, evidence refs, runtime policy, Provider Instances, framework-owned Provider Contracts, and Env_Profiles; confirm environment readiness; set up state; execute operations through selected provider runtimes; collect actual results; run verification; clean up state; and emit raw execution evidence.

One suite execution is a batch/run context. A suite may contain one or more execution-eligible DSL test cases, all selected under one suite-level Env_Profile. The standard result JSON records suite-level `batch_id`, `run_id`, `test_count`, `test_results[]`, provider summaries, and evidence refs. Product/RP labels are carried as report metadata so F008 can package RP-level coverage, but runtime decisions come from framework artifacts only.

The execution process and DSL shall remain package-type-neutral. v0.2 provider implementations shall prioritize reusable built-in providers for request/response, messaging, DB fixture, deployment readiness, and file/batch execution. External runner support is not the default extension path; it is an approved escape hatch when a legacy or specialized boundary cannot yet be represented by a reusable Provider Contract.

F007 defines the generic execution core used by RP Regression Execution. Framework Verification tests may exercise F007 through sample generated artifacts, but that fixture evidence is not downstream Product/RP release evidence.

F007 shall not author AC, classify AC readiness, generate tests, regenerate checked-in tests, generate expected results, approve expected results, approve waivers, or decide release readiness.

F007 implementation must not proceed directly to provider runtime expansion until the DSL v0.2 contract gate is green. The gate requires parser validation, dry-run blocking, compatibility behavior for legacy inputs, prohibited governance-field blocking, secret guardrails, plugin catalog validation, result schema validation, and CLI run/report proof using the same execution-focused DSL v0.2 test artifact.

### Required Mechanism

- Read `suite_manifest.yaml`, Env_Profiles, Provider Instances, checked-in DSL test cases, target definitions, setup parameter refs, execute operation refs, cleanup refs, expected-result artifacts, verify rules, evidence refs, runtime policy, plugin catalogs, `traceability_map.yaml`, and the framework Provider Contract catalog.
- Check that test cases declare `dsl_version: v0.2` and include the v0.2 core contract: identity/status/revision, optional tags/source refs/labels, compatible profiles, parameters when used, targets, execute steps, verify, evidence, and runtime fields, with setup and expected-results content required when referenced by setup, execute, cleanup, verify, or evidence.
- Reject new execution-focused DSL artifacts that still use legacy-only fields such as `rp_id`, `ac_id`, `execution_target`, `target_ru_id`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, or `policy`.
- Reject new execution-focused DSL artifacts that contain governance-heavy fields such as approval, waiver, release gate, or risk approval state.
- Check that test cases have execution lifecycle status allowed by the selected run policy.
- Check that required expected-result artifacts are eligible under the expected-result process before they are used by `verify`.
- Validate that every DSL section can be consumed by exactly one AP responsibility path before provider execution starts.
- Validate that every DSL target resolves `provider_id + selected env_profile_id` to one Provider Instance, one framework Provider Contract for its `provider_type`, and one Env_Profile provider binding.
- Validate each Provider Instance against its Provider Contract and Env_Profile, including allowed operations, allowed input keys, required inputs, required binding keys, binding key value kinds, bindable output refs, output refs, evidence outputs, failure codes, public `support_status`, and Env_Profile policy before provider execution.
- Normalize execution-focused DSL v0.2 `source_refs`, optional `labels`, and generated `traceability_map.yaml` data into internal run/report metadata without requiring legacy `rp_id`, `ac_id`, `execution_target`, `package_inputs`, `oracles`, `assertions`, or `policy` fields in new test artifacts.
- Reject ambiguous Provider Instance, custom Provider Contract, or Env_Profile provider binding resolution rather than falling back silently to the first match.
- Block raw secrets in DSL, Env_Profile, result, and evidence; allow only `secret_ref`, runtime-generated secrets, CI secret references, or vault references.
- Require explicit approval metadata before using an external runner escape hatch, including reason, owner, bounded command or container ref, timeout, inputs, outputs, and evidence map.
- Create one unique batch ID for the suite execution request.
- Create one unique run ID for the suite execution request and record per-test outcomes in `test_results[]`.
- Resolve the execution mode from the selected Env_Profile, including local, CI, SIT, and preprod constraints.
- Follow the generated logical target dependency graph and stop downstream execution when a required upstream target validation fails.
- Check deployment and environment readiness before running `sit` or `preprod` tests.
- Reject or mark invalid runs when suite manifest, run plan, Env_Profile, Provider Instance, framework Provider Contract for the selected `provider_type`, Env_Profile provider binding, execute operation, setup inputs, expected refs, verify rules, required deployment evidence, or environment readiness are missing.
- Resolve logical setup operations and execute inputs to concrete provider-backed setup state or generated data.
- Bind runtime values from setup operation outputs, execute inputs, context, and previous execute outputs.
- Run setup actions needed by the configured fixture lifecycle.
- Execute or validate through the selected Provider Instance and Provider Contract.
- Collect actual outputs, logs, and execution metadata.
- Run verification against resolved expected refs or deterministic verify rules.
- Run cleanup actions and record cleanup evidence.
- Emit run-level artifacts such as standard result JSON, execution report, execution log, actual results, assertion results, observation results, postcondition results, cleanup evidence, failure classification, and failure details.
- Emit a batch-level summary that lists suite ID, optional report labels, environment profile, batch status, executed run IDs, test case IDs, acceptance-criteria source refs, and run statuses.

## F008 — Coverage and Evidence Package

### Purpose

Package RP-level coverage, traceability, raw execution evidence, failures, and approved exclusions into review-ready evidence artifacts.

### Expected Behavior

The framework shall report coverage against automatable RP-level AC using batch-level evidence, trace generated tests to RP AC, retain raw execution evidence from F007, identify failures with expected-result or inline decision rule results and actual results, and include manual-only or waived AC records where approved.

F008 may consume approved waiver or manual-only records as evidence metadata. F008 does not create, approve, or govern waivers. Waiver workflow belongs to F010 or human release governance.

F008 shall not execute tests, generate tests, generate expected results, approve waivers, change the coverage denominator, or decide release Go/No-Go. Product developers own evidence review for implementation correctness. QA or release owners handle waiver, manual-only exclusion, and release decisions through F010 or a human release process.

### Required Mechanism

- Consume RP AC inventory, AC classification, approved test cases, F007 batch evidence, run-level evidence, expected-result artifacts, approved waiver or manual-only evidence metadata records, and traceability links.
- Treat `tests/approved/` location plus an allowed DSL lifecycle status such as `active` as execution eligibility; do not treat DSL `status` as expected-result approval, waiver approval, or release approval.
- Read coverage traceability from execution-focused DSL v0.2 optional `source_refs`, optional `labels`, generated `traceability_map.yaml`, standard result JSON, and normalized run evidence, not from legacy-only test case fields.
- Calculate coverage as distinct covered automatable RP-level AC divided by distinct total automatable RP-level AC.
- Count an AC as covered only when at least one run in the selected batch passed and traces to an approved test case for that AC.
- De-duplicate coverage by acceptance-criteria source ref when multiple tests cover the same AC.
- Exclude manual-only or waived AC only when approved waiver or manual-only evidence metadata records exist.
- Keep partial AC in the denominator unless split and reclassified.
- Keep blocked AC visible in the evidence package instead of silently dropping them.
- Link each evidence item to the acceptance-criteria source ref, optional Product/RP labels when generated, test case ID, execution batch ID, and execution run ID.
- Include expected result, actual result, failure reason, and source artifact links for failed checks.
- Produce review-ready artifacts such as coverage report, traceability report, evidence index, failure summary, and release review summary.

## F009 — Advanced Spec Readiness

### Purpose

Detect deeper cross-artifact spec gaps and drift after the v0.2 vertical slice proves the basic readiness workflow.

### Expected Behavior

F009 is not part of v0.2. It extends readiness beyond basic folder, artifact, AC, and mapping completeness checks.

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

## F011 — Provider and Verify Plugin Contracts

### Purpose

Allow reusable execution capabilities to be extended through catalogued Provider Contracts, Provider Instances, provider plugins, and verify plugin contracts without making the framework understand product, RP, or RU topology.

### Expected Behavior

The framework shall expose contract metadata for provider IDs, provider types, valid Provider Instance shapes, operations, verify types, required binding keys, binding key value kinds, bindable outputs, allowed input keys, required inputs, supported Env_Profiles, public `support_status`, output refs, evidence outputs, timeout/safety constraints, and external-runner provider safety approval requirements. Validation shall check the selected Provider Contract, Provider Instance, and Env_Profile before execution or dry-run dispatch. External runner approval is provider safety approval, not release approval.

### Required Mechanism

- Define framework-owned schemas/catalogs for Provider Contracts, Provider Instances, Env_Profiles, provider plugin contracts, verify plugin contracts, and `provider_capability_registry.v0.2.yaml`.
- Require Provider Contracts to declare `provider_type`, valid Provider Instance shape, required binding keys, binding key value kinds, bindable outputs, allowed operations, allowed input keys, required inputs, output refs, evidence outputs, public `support_status`, failure codes, and safety policy.
- Resolve contracts through one explicit chain: DSL target `provider_id` plus selected Env_Profile, Provider Instance, framework Provider Contract by `provider_type`, and Env_Profile `providers.<provider_id>.binding_keys`; suite manifests select tests and may select the active Env_Profile but must not override provider fields.
- Block unsupported, ambiguous, unsafe, missing, or provider-safety-unapproved `external_runner` Provider Contracts before provider dispatch, with logical target, Provider Instance ID, provider type, profile, contract path, AP gate, and owner action.
- Keep product strategy selection outside the framework. The Phase 2 Agent Skill may decide which generated contract to reference; the framework only validates and executes declared generic contracts.
