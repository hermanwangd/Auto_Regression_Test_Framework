# 05. Architecture Design

Status: v0.2 Full Pre-release Architecture Draft

This design turns the Product/RP/RU baseline into an implementable architecture while keeping the framework core product-topology agnostic. Product Repo remains the source of truth, Release Package remains the release and evidence unit, and RU repos remain implementation workspaces; Agent Skills translate that context into framework-readable artifacts.

## 5.1 Design Scope

v0.2 implements a product-agnostic command-line execution framework plus Agent Skill translation boundary. Product-aware commands and skills read Product Repo artifacts, but the generic execution core consumes generated DSL tests, suite manifests, Env_Profiles, Provider Instances, expected results, traceability maps, and framework-owned Provider Contracts resolved by `provider_type`.

In scope:

- Product Repo and RP skeleton readiness.
- Product/RP/RU mapping readiness through Agent Skill translation.
- AC readiness classification and agent draft generation boundaries.
- Durable test case and expected-result lifecycle.
- `local`, `ci`, `sit`, and `preprod` Env_Profiles.
- Heterogeneous RP pilot support through framework-owned Provider Contracts, Provider Instances, Env_Profiles, a provider type capability registry, verify plugin contracts, reusable built-in provider runtimes, and a governed external runner escape hatch.
- v0.2 result schema, evidence schema, secret guardrail, suite selection, and profile-based execution.
- Coverage, traceability, and release review evidence.

Out of scope:

- Framework-owned CD deployment orchestration.
- Runtime ownership of RU repos.
- Unreviewed agent-generated expected results as regression truth.
- Persistent dashboard or service runtime.

## 5.2 Architecture Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Runtime shape | CLI-first framework | Fits v0.2 pre-release, avoids operating a service, works in local, CI, and SIT contexts. |
| Durable store | Product Repo filesystem | Specs, tests, expected results, and evidence remain reviewable and versioned. |
| Release unit | Release Package | Package release is the real release boundary; Product is virtual. |
| Primary AC level | RP-level AC | Coverage denominator must match release evidence ownership. |
| Test lifecycle | Generate separately from execute | Approved tests are checked in and not regenerated on every run. |
| Test case DSL | Full pre-release package-neutral DSL v0.2 with explicit version | Different Products and RPs express regression tests through one artifact model without overfitting to one package type. |
| Provider public interface | DSL targets resolve to Provider Instances, Provider Contracts, and Env_Profiles | Execution process is generic; product topology stays outside the framework core. |
| Heterogeneous RP support | Agent-generated Provider Instances and Env_Profiles plus framework-owned provider contract registry validation | Different RU languages, deployments, and messaging styles are translated into logical provider targets and reusable built-in provider contracts instead of DSL changes, framework-core changes, or RP-specific scripts. External runner is a governed escape hatch only. |
| Implementation stack | Spring Boot 3.x on Java 17+ | Provides a modern Java runtime, dependency injection, validation, configuration binding, and CLI packaging path. |
| Verification boundary | Separate framework verification from RP regression execution | Maven validates this framework; CLI `run` validates downstream Product/RP packages and writes RP release evidence. |

### Framework Verification vs RP Regression Execution

| Concern | Framework Verification | RP Regression Execution |
|---|---|---|
| Subject under test | This framework codebase and its contracts | A downstream Product Release Package |
| Primary command | `./mvnw test` or `./mvnw verify` | Product-aware wrapper or pipeline generates framework artifacts, then calls the generic runner. |
| Test runner | Surefire for unit/component tests; Failsafe for integration tests | Framework CLI execution engine |
| Fixture source | Sample generated artifacts and local/mock providers | Real Product Repo context translated into generated suite, run, Provider Instance, and Env_Profile artifacts; Provider Contracts resolve from the framework catalog unless a custom provider is explicitly declared |
| SIT/preprod dependency | Not required | Required only for `sit` or `preprod` RP validation boundary |
| Evidence meaning | Framework build/CI evidence | Downstream RP release review evidence |

Framework integration tests may exercise the same CLI flows used by RP Regression Execution, but the fixture scope remains framework verification. The architecture must not treat sample fixture evidence as product release evidence.

## 5.3 Component Architecture

The AP-level architecture follows the common test platform reference model: definition, discovery, planning, fixture/state, execution, oracle/assertion, and reporting. Fine-grained resolvers remain internal modules under these components; they are not separate AP-level components.

```text
Product Repo Artifact Store
        |
        v
Phase 2 Agent Skill Translation and Drafting
        |
        v
Generated Framework Artifacts
        |
        v
CLI Orchestrator
        |
        +--> Definition and Validation
        +--> Discovery and Context
        +--> Planning and Binding
        +--> Fixture and State Manager
        +--> Execution Engine
        +--> Oracle and Assertion Engine
        +--> Evidence and Reporting
```

| Component | Responsibility | Verification / Evidence |
|---|---|---|
| Product Repo Artifact Store | Own versioned docs, RP records, mapping inputs, draft/proposed docs, tests, expected results, and evidence | EVD-001 / EVD-002 / EVD-003 |
| Phase 2 Agent Skill Translation and Drafting | Initialize Product Repo guidance, scan RU implementation repos when requested, draft missing docs/specs with source refs, interpret Product/RP/RU topology, and produce framework-readable suite, run, Provider Instance, Env_Profile, and traceability artifacts | EVD-001 through EVD-006 |
| Definition and Validation | Parse DSL and generated framework artifacts, validate schemas, statuses, required and conditional fields, and artifact lifecycle rules | AC-001, AC-014, AC-017 |
| Discovery and Context | Load suite manifest, run plan, Env_Profile, Provider Instances, framework Provider Contract catalog, traceability map, and readiness reports | AC-002, AC-015, AC-018 |
| Planning and Binding | Resolve DSL targets through `provider_id` and the selected Env_Profile, validate Provider Instances against the framework Provider Contract catalog, bind Env_Profile provider binding values, and resolve parameters, fixtures, execute inputs/outputs, expected results, verify references, and runtime references into an execution plan | AC-002, AC-003, AC-004, AC-016 |
| Fixture and State Manager | Check preconditions, set up fixtures, seed or publish data, enforce cleanup, and validate postconditions | AC-004 |
| Execution Engine | Execute planned operations through provider registry dispatch, manage execution profiles, captured outputs, timeout, retry, and provider result capture | AC-005, AC-006, AC-007, AC-008 |
| Oracle and Assertion Engine | Resolve expected-result truth sources and evaluate actual outputs or provider metadata through verify rules, including selector-based structured output checks | AC-009, AC-010, AC-011 |
| Evidence and Reporting | Persist batch summaries, run evidence, observations, cleanup results, failures, traceability, coverage, and release-review reports | AC-012, AC-013, AC-015, AC-017 |

Internal module mapping:

| AP-Level Component | Internal Modules |
|---|---|
| Definition and Validation | `schema`, `testcase`, `expectedresult` packages |
| Discovery and Context | `productrepo`, `discovery`, `environment`, `readiness` packages plus generated artifact readers |
| Planning and Binding | `binding`, `provider`, parameter expansion and execution-plan construction in `testcase` |
| Fixture and State Manager | `fixture` package |
| Execution Engine | `execution` package plus the legacy internal `adapter` package for shell/file execution only; `adapter` is not a public runtime interface |
| Oracle and Assertion Engine | `oracle`, `assertion` packages |
| Evidence and Reporting | `evidence`, `report` packages |

DSL and artifact flow through the 7 AP:

| AP | Main Input | Main Output |
|---|---|---|
| Definition and Validation | DSL tests, suite manifest, run plan, Env_Profiles, Provider Instances, framework Provider Contract catalog, expected-result artifacts | Validated artifact graph, schema errors, lifecycle/status errors |
| Discovery and Context | Validated artifact graph plus requested suite, Env_Profile, Provider Instances, and framework Provider Contracts resolved by `provider_type` | Execution context, logical target list, traceability labels, available tests, provider instance refs, and provider contract refs |
| Planning and Binding | Execution context, DSL tests, targets, setup operations, expected-result references, verify rules, Provider Instances, framework Provider Contracts, Env_Profile binding values | Concrete execution plan with bound provider targets, inputs, setup/cleanup intents, expected refs, verify refs, and execute output placeholders |
| Fixture and State Manager | Execution plan fixture sections, environment reference, cleanup policy | Prepared state, fixture evidence, cleanup plan, precondition/postcondition results |
| Execution Engine | Execution plan operations and Provider Instance / framework Provider Contract / Env_Profile resolution | Operation results, provider outputs, runtime metadata, timeout/retry outcomes |
| Oracle and Assertion Engine | Actual outputs, expected results, verify rules | Pass/fail decisions with expected value, actual value, comparison rule, and failure reason |
| Evidence and Reporting | All AP outputs, traceability, coverage policy | Durable batch evidence, run evidence package, AC coverage report, release-review summary |

This flow keeps the DSL stable. New Product/RP/RU behavior should be translated by Agent Skills into generated framework artifacts and configurable built-in provider contracts first. New provider code is allowed only for reusable cross-RP behavior; DSL enum extensions require a versioned compatibility decision.

The heterogeneous RP support model and current capability matrix are recorded in `docs/02-architecture/07_heterogeneous_rp_support_capability_matrix.md`.

AP boundary contract:

| AP | Must Fail Before | Required Failure Detail |
|---|---|---|
| Definition and Validation | Discovery, planning, generation approval, or run start | Artifact path, field path, lifecycle status, supported `dsl_version`, owner action |
| Discovery and Context | Planning | Requested suite or Env_Profile, missing generated artifact, missing logical target, requested environment, owner action |
| Planning and Binding | Setup planning or provider dispatch | Test case ID, acceptance-criteria source ref when available, unresolved target, setup operation, execute input, expected-result ref, verify ref, evidence ref, runtime policy, `provider_id`, `provider_type`, `env_profile_id`, and owner action |
| Fixture and State Manager | Provider dispatch when setup is unsafe or incomplete | Test case ID, setup action, cleanup requirement, state scope, provider_id, owner action |
| Execution Engine | Verify evaluation when an execute operation fails | Execute step ID, provider_id, provider_type, env_profile_id, operation, exit code or timeout, log refs, owner action |
| Oracle and Assertion Engine | Pass/fail reporting when truth or comparison rule is missing | Verify ID, expected-result ref, expected-result approval status when applicable, actual ref or provider metadata source, owner action |
| Evidence and Reporting | Release-review-ready claim | Missing evidence type, affected AC/test/run, coverage impact, owner action |

APs communicate through structured reports and execution-plan records, not hidden side effects. Each AP output must be durable enough to explain why the next AP did or did not run.

For implementation clarity, each AP owns one primary question and one durable handoff artifact:

| AP | Primary Question | Durable Handoff |
|---|---|---|
| Definition and Validation | Are declared artifacts syntactically valid, lifecycle-ready, approved when needed, and compatible with supported versions? | `validation_report` with field paths, lifecycle status, and compatibility result. |
| Discovery and Context | Which suite, DSL tests, trace labels, logical targets, Env_Profile, Provider Instances, and framework Provider Contracts are in scope? | `execution_context` with resolved artifact and contract references. |
| Planning and Binding | Can every DSL target resolve from `provider_id` and selected Env_Profile into a valid Provider Instance, framework Provider Contract, and Env_Profile provider binding without embedding provider-specific values in the DSL? | `execution_plan` with bound data refs, operation inputs, setup/cleanup intents, expected-result refs, verify refs, provider refs, and execute output placeholders. |
| Fixture and State Manager | Is test state safe to prepare and clean up for this Env_Profile execution mode? | `fixture_plan` and `cleanup_plan` with setup, mutation scope, cleanup, and postcondition evidence refs. |
| Execution Engine | Can the planned logical steps run through declared providers in the selected Env_Profile? | `execution_result` with step status, provider outputs, logs, actual result refs, timeout/retry metadata. |
| Oracle and Assertion Engine | Do approved truth sources or deterministic decision rules prove pass/fail for the actual outputs? | `assertion_result` with expected-result refs, expected, actual, comparator, and failure reason. |
| Evidence and Reporting | Is there enough durable evidence to support coverage and release review without manually reconstructing the RP execution? | `batch_summary`, `evidence_package`, `coverage_report`, `failure_summary`, and `release_review_summary`. |

Provider Contracts, Provider Instances, and Env_Profiles are configuration artifacts consumed by APs; they are not DSL implementation sections. A Provider Contract declares one reusable `provider_type`, allowed operations, allowed input keys, required inputs, binding key schema, bindable outputs, output refs, evidence outputs, failure codes, and the valid Provider Instance shape. A Provider Instance declares one RP logical runtime target with `provider_id` and `provider_type` using the same top-level shape as the contract. An Env_Profile supplies environment-specific actual values for Provider Contract `binding_keys` under `providers.<provider_id>.binding_keys`.

Execution-focused DSL v0.2 references runtime behavior through `targets.<target_id>.provider_id`, optional `data`, `setup.operations`, `execute.operations`, `verify.checks`, `cleanup.operations`, operation `inputs`, expected refs, and evidence rules. The active Env_Profile is selected by CLI or suite manifest, while `compatible_profiles` only restricts where the test may run. The DSL must not embed provider configuration, endpoint URLs, topics, namespaces, connection strings, credentials, shell scripts, SQL bodies, release gates, waivers, or approval workflow. New DSL artifacts must not use legacy fields such as `data_binding`, `execution_target`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, or `policy`.

DSL v0.2 validation is the first F007 architecture gate. Definition and Validation must confirm syntax, required fields, supported execution lifecycle status, explicit operation IDs, forbidden governance fields, selector-based verify requirements, secret guardrails, Provider Contract references, and legacy-field migration rules before Discovery, Planning, provider binding, fixture setup, or provider dispatch can run. New DSL artifacts that contain old executable array forms, `data_binding`, `call_ru`, `target_ru_id`, `package_inputs`, `oracles`, release gates, waivers, raw secrets, or approval workflow state are invalid even when equivalent legacy artifacts remain readable during migration.

The gate sequence for execution-focused tests is:

```text
DSL v0.2 parse and validation
-> traceability and lifecycle status check
-> target and operation resolution
-> data catalog, setup operation, execute operation, output ref, expected ref, verify selector/query/event, evidence, result, and runtime validation
-> resolve DSL target provider_id and selected Env_Profile
-> Provider Instance lookup
-> framework Provider Contract catalog lookup by provider_type
-> Provider Instance shape validation against Provider Contract
-> Env_Profile.providers.<provider_id>.binding_keys validation against Provider Contract binding_keys
-> execution plan creation
-> fixture setup and provider dispatch
```

Provider capability registry rules:

- The registry is the canonical source for supported `provider_type` values.
- Each registry entry declares required binding keys, supported operations, allowed execution modes, runtime support status, evidence outputs, and safety policy.
- Supported runtime status values are `supported`, `partial`, `escape_hatch`, and `unsupported`.
- Provider Contracts must explicitly declare `provider_type`; heuristic inference must not choose a runtime.
- Resolution follows one explicit chain: DSL target `provider_id` + selected Env_Profile -> Provider Instance -> `provider_type` -> framework Provider Contract catalog -> Env_Profile `providers.<provider_id>.binding_keys`. Suite manifests select tests and may select the active Env_Profile, but must not override provider fields. Ambiguous logical target or provider matches block dry-run and execution.
- Execution dispatch must go through the registry. Adding a provider runtime should not require adding product-specific conditionals to the execution engine.
- External runner entries are `escape_hatch` providers. They require explicit owner approval metadata, bounded timeout, declared inputs/outputs, and an evidence map.

Provider contract and runtime baseline status:

These statuses describe the current contract baseline and the implemented framework-owned provider capability slices. Track A rows are contract-complete only; Track C rows are accepted only inside the stated framework verification boundary. None of these rows by themselves prove downstream RP release readiness or real external environment certification.

| Provider Type | Current Status | Runtime Implementation Boundary | Minimum Required Contract Fields | Verification Boundary |
|---|---|---|---|---|
| `shell_command` | Contract baseline, existing local fixture path may remain partial | Runtime dispatch acceptance is not Track A unless explicitly selected by a later implementation slice | `command` binding key, positive timeout, output refs, logs, success exit codes when non-default, safety policy when command-capable | Contract validation, dry-run planning, and sample artifact syntax. |
| `sample_fake_provider` | Track B Golden E2E target | Framework-owned fake provider only; proves lifecycle, not provider expansion | workspace binding, setup/execute/cleanup operations, actual output refs, assertion/diff evidence | Public CLI Golden E2E validation, execution, result, evidence, and report. |
| `rest_client` | Track C P0 for WireMock + HTTP sample runtime | Framework executes checked-in local/CI `http_request` samples against generated WireMock `base_url`; generic downstream SIT/preprod endpoint evidence remains pilot work | base URL binding key, operation path/method, request binding where action needs input, positive timeout, output refs | Contract validation, dry-run planning, provider capability execution, happy/failure/boundary sample coverage, and `http_request_response` evidence. |
| `grpc_client` | Contract baseline | Full gRPC runtime is beyond Track C P0 unless selected by decision log | service/target binding key, operation service and method, request binding, positive timeout, output refs | Contract validation and dry-run planning; pilot endpoint evidence remains future. |
| `wiremock_http_mock` | Track C P0 | Track C implements WireMock server lifecycle for framework provider capability evidence and can feed generated `base_url` to `rest_client` samples | mappings ref, mock runtime mode, stub loading, request journal outputs, server log outputs | Contract validation, dry-run planning, local provider capability execution, request journal/server log evidence, and paired WireMock + HTTP request sample coverage. |
| `kafka_messaging` | Contract baseline | Full Kafka runtime is beyond Track C P0 unless selected by decision log | broker binding key, topic binding key, publish/consume operation mode, payload binding, timeout, output refs | Contract validation and dry-run planning; pilot broker evidence remains future. |
| `nats` | Track C P0 | Track C implements NATS publish/observe/payload-match verification for framework provider capability evidence | connection and subject binding keys, `nats_publish`, `nats_observe`, payload binding, `consume_from: test_start_time`, timeout, poll interval, output refs | Contract validation, local provider capability execution, and framework evidence only; pilot broker evidence remains future. |
| `jdbc_database` | Track C P0 | Track C implements JDBC Oracle/DB2-style seed/query/cleanup verification for framework provider capability evidence | connection binding keys or secret refs, dialect metadata, SQL refs, parameter binding, cleanup strategy, query/result output refs | Contract validation and dry-run planning; real DB evidence remains future. |
| `artifact_compare` | Track C P0 | Track C implements artifact loading for JSON/schema/file diff verification | file refs, output refs, diff evidence | Contract validation, dry-run planning, and sample artifact syntax. |
| `polling_observer` | Track C P0 | Track C implements observation polling for framework provider capability evidence | timeout, poll interval, expected state, last observed output ref | Contract validation, dry-run planning, and sample artifact syntax. |
| `kubernetes_runtime` | Contract baseline | Full K8s readiness/runtime provider is beyond Track C P0 unless selected by decision log | namespace/context refs, deployment/service/selector refs, deployed version ref, positive timeout, bounded log tail refs, output refs | Contract validation and dry-run planning; pilot cluster evidence remains future. |
| `vm_runtime` | Contract baseline | Full VM readiness/runtime provider is beyond Track C P0 unless selected by decision log | host/user refs, health or command refs, deployed version ref, positive timeout, output refs, safety policy | Contract validation and dry-run planning; pilot VM evidence remains future. |
| `external_runner` | Contract baseline escape hatch | Invocation bridge acceptance is not Track A or Track B | provider safety approval ref, owner, reason, command/container ref, inputs, outputs, positive timeout, evidence map, no built-in-provider alternative | Contract validation, safety gating, dry-run planning, and sample artifact syntax. |

The architecture defines the intended verification scope for supported rows only. It does not mean v0.2 has already been implemented or accepted. Pilot acceptance still requires implemented slices, owner-provided RP artifacts, and real environment evidence for the selected Provider Contracts.

## 5.4 Module Boundaries

The implementation uses Spring Boot 3.x on Java 17+. Package names may be adjusted to match the final Java group ID, but these boundaries are required.

```text
src/main/java/com/specdriven/regression/
  RegressionApplication.java
  cli/
    RegressionCommand.java
  productrepo/
    ProductRepoService.java
  discovery/
    RpDiscoveryService.java
  schema/
    ArtifactSchemaValidator.java
  artifact/
    GeneratedArtifactReader.java
  validation/
    ValidationIssue.java
    ValidationReport.java
  readiness/
    ReadinessService.java
  mapping/
    RpRuMappingService.java
  suite/
    SuiteSelectionService.java
  parameter/
    ParameterSetResolver.java
  testcase/
    TestCaseLifecycleService.java
  expectedresult/
    ExpectedResultService.java
  environment/
    ExecutionEnvironmentResolver.java
  binding/
    BindingResolver.java
  provider/
    ProviderRegistry.java
    ProviderContractResolver.java
  plugin/
    PluginContractCatalog.java
  fixture/
    FixtureLifecycleService.java
  execution/
    ExecutionEngine.java
  provider_runtime/
    ProviderRuntimeDispatcher.java
    ProviderExecutionBridge.java
  oracle/
    OracleResolver.java
  assertion/
    AssertionEngine.java
  evidence/
    EvidenceWriter.java
  result/
    ResultWriter.java
  report/
    CoverageReportService.java
```

Boundary rules:

- `cli` orchestrates use cases only; it does not embed validation logic.
- `schema` owns typed parsing and schema validation for RP artifacts.
- `artifact` owns reading generated framework artifacts such as suite manifest, run plan, Env_Profiles, Provider Instances, and traceability map; Provider Contracts are read from the framework catalog unless explicit custom contract resolution is configured.
- `validation` owns reusable validation issue shape, severity, blocking scope, field path, AP, and owner action reporting.
- `readiness` reports gaps and owner actions; it does not create business truth.
- F001 readiness/init agent skill consumes readiness reports and optional RU repo scan evidence. It may create draft/proposed docs or spec artifacts only when explicitly invoked for that action, and those artifacts must keep source refs, assumptions, and review status.
- Reverse-engineered RU repo findings are evidence for owner review; they are not formal Product/RP truth or RP AC until reviewed by the responsible owner.
- Product mapping interpretation belongs to the Agent Skill. Framework modules consume generated suite/run/Env_Profile artifacts and never infer RP membership.
- The test case DSL describes validation intent, targets, optional data sources, setup operations, execute operations, expected refs, verify rules, evidence, and runtime policy; it does not contain package-specific execution logic.
- `dsl_version` is required so the framework can apply the correct parser and compatibility behavior.
- `testcase` may create draft artifacts but must not overwrite approved tests.
- `expectedresult` enforces source references and approval status.
- `environment` blocks `sit` and `preprod` runs unless deployment and readiness evidence exist.
- `binding` resolves DSL `provider_id` / `env_profile_id` targets, optional data refs, setup inputs, execute inputs, expected refs, verify references, runtime references, and output placeholders; it does not execute package behavior.
- `suite` owns test ID, suite, tag, and profile selection over framework-readable manifests.
- `parameter` owns reusable data refs, operation input expansion, reviewed case loading, safe substitution, per-case run context, and coverage de-duplication inputs.
- `provider` owns the capability registry, validates Provider Instances against Provider Contracts, resolves Env_Profile provider binding values by `provider_id`, and dispatches provider operations by DSL target and operation.
- `provider` owns the generic provider catalog and provider metadata contract, including governed `external_runner` Provider Contracts.
- `plugin` owns provider and verify plugin contract discovery, version compatibility checks, duplicate ID blocking, and startup/preflight catalog evidence.
- `fixture` owns setup, cleanup, precondition, and postcondition lifecycle coordination.
- `execution` executes a prepared plan and records operation results; it does not own schema validation, binding resolution, Provider Contract / Provider Instance resolution, or fixture policy.
- `oracle` loads expected-result sources and decision parameters; `assertion` applies verify rules against actual outputs.
- `assertion` evaluates structured output selectors declared by DSL verify rules; it must not infer missing JSONPath selectors for `json_path_equals` or `json_path_absent` in new execution-focused DSL artifacts. Provider metadata checks, such as `response_status_equals`, may use request/response provider evidence without a DSL `actual` field when the provider contract supplies HTTP status metadata.
- `adapter` is an internal legacy package name for shell/file execution. New documentation, generated artifacts, and runtime configuration must use Provider naming.
- `evidence` and `report` write durable evidence under the configured evidence output path.
- `result` owns standard result JSON emission and schema validation before a run is considered complete.

DSL-to-module responsibility:

| DSL Section | Primary Module | Secondary Module |
|---|---|---|
| `dsl_version`, `test_case_id`, `status`, `revision`, `source_refs`, optional `labels` | `schema`, `testcase` | `evidence` |
| `targets` | `testcase`, `binding` | `provider`, generated artifact readers |
| `data` | `parameter`, `binding` | `execution`, `evidence`, `report` |
| `setup.operations` | `fixture`, `binding` | `provider`, `environment`, `evidence` |
| `execute.operations` | `execution` | `provider`, `binding` |
| expected refs, `verify.checks` | `expectedresult`, `oracle`, `assertion` | `binding` |
| `evidence`, `runtime` | `evidence`, `report`, `execution`, `result` | `fixture` |

If a DSL section cannot be assigned to one of these modules, it is not ready for v0.2 execution and must be treated as an unsupported DSL extension.

## 5.5 Extension Model for Product Variants

The framework must scale across different Product/RP/RU test needs by keeping the DSL semantic core stable and moving product-specific interpretation into Agent Skill translation. A new RP type should normally generate framework-readable targets, run plans, Provider Instances, and Env_Profiles that bind to existing framework-owned Provider Contracts, not change runner orchestration or provider code.

```text
Product / RP / RU Docs
        |
        +--> Phase 2 Agent Skill Translation and Drafting
              +--> Suite Manifest
              +--> Run Plan
              +--> Provider Instance
              +--> Env_Profile
              +--> Traceability Map
        |
Stable Framework Core
        |
        +--> Built-in Provider Contract Catalog
        +--> Extension Points
              +--> Provider Runtime
              +--> Target and Binding Resolver
              +--> Fixture Provider
              +--> Expected-Result Reader
              +--> Verify Provider
              +--> Evidence Collector
```

Core execution framework owns:

- DSL parsing, schema validation, and compatibility rules.
- Test discovery from suite manifest, parameter expansion, target dependency planning, and lifecycle orchestration.
- Dispatch to providers by resolved `provider_id`, `provider_type`, selected Env_Profile, and declared operation.
- Stable evidence structure and traceability to source labels, AC, test case, run, parameter case, logical target, and environment.

Providers own:

- How to call a logical target through CLI, HTTP, batch, DB, queue, or package-specific tooling.
- How to materialize execution inputs and setup operations such as `db_seed`, `message_event`, `api_payload`, `config_file`, and `existing_state`.
- How to set up and clean up package-specific state safely.
- How to load package-specific expected-result truth sources and produce actual values compatible with verify rules.
- How to collect package-specific logs, metrics, traces, events, and final-state probes.

Provider implementation must be reusable by default. RP-level logical behavior belongs in validated Provider Instances, environment-specific values belong in Env_Profile provider binding keys, and reusable rules belong in framework-owned Provider Contracts. Provider configuration is not part of the test case DSL; DSL tests may reference provider capabilities only through `provider_id`, operation, operation `inputs`, output refs, verify refs, and evidence refs. Provider code should know generic actions and resource types, not product business concepts.

Example configurable REST Provider Instance and Env_Profile using the built-in `rest_client` Provider Contract:

```yaml
provider_instance_version: v0.2
provider_id: order-api
provider_type: rest_client
runtime_modes: [native]
---
env_profile_id: sit
execution_mode: sit
providers:
  order-api:
    runtime_mode: native
    binding_keys:
      base_url:
        secret_ref: vault://sit/order-api/base-url
```

Example configurable JDBC Provider Instance using the built-in `jdbc_database` Provider Contract:

```yaml
provider_instance_version: v0.2
provider_id: order-db
provider_type: jdbc_database
runtime_modes: [native]
operations:
  execute_script:
    cleanup_strategy: by_test_run_id
    outputs:
      affected_rows: affected_rows
```

The Agent-generated framework artifacts are the primary extension entrypoint for runtime. Product-owned RP/RU mapping may declare provider intent, validation boundary, execution mode, environment reference, dependencies, and evidence responsibility, but the framework core consumes only the generated suite manifest, run plan, Provider Instances, Env_Profiles, traceability map, and framework-owned Provider Contract catalog. The DSL references logical capabilities; it must not embed package-specific shell scripts, URLs, credentials, SQL bodies, queue implementation details, or database commands.

Extension governance rules:

- Prefer configuring an existing built-in provider before adding provider code or a new DSL field.
- Add provider code only when the required behavior is reusable across RPs and cannot be expressed safely by an existing Provider Contract.
- Use an external runner only as an approved escape hatch for legacy or specialized boundaries, not as the normal way to onboard each RP.
- Validate Provider Contract, Provider Instance, and Env_Profile schemas before execution, including required binding keys, allowed value kinds, generated refs, secret references, cleanup strategy, and unsupported operations.
- Add a new DSL enum only when a recurring cross-RP concept cannot be represented by an existing provider type, fixture type, execute operation, expected-result type, verify type, or evidence output.
- Add or change DSL required fields only through a `dsl_version` compatibility decision.
- A provider runtime must not silently skip unsupported DSL sections; it must fail fast with test case ID, acceptance-criteria source ref when available, section name, and owner action.

## 5.6 Invocation Boundary

The CLI is the invocation portion of the v0.2 framework public interface. The complete framework public interface also includes DSL/test definitions, Env_Profiles, Provider Instances, Provider Contracts, result schema, and evidence schema. Commands return non-zero exit codes when readiness, validation, or execution gates fail.

Maven commands are not the RP public execution interface. `./mvnw test` and `./mvnw verify` validate this framework. Product-aware tooling may expose an RP-oriented wrapper, but the framework execution core validates a generated suite/run/Env_Profile set and writes evidence through the configured output paths.

```bash
regress init-product-repo --root .
regress check-readiness --root .
regress check-readiness --root . --rp-id <pilot-rp-id> --write-report
agent product-repo-readiness --report docs/08-release/release-packages/<rp_id>/evidence/readiness/readiness.yaml
regress init-rp --root . --rp-id <pilot-rp-id> --package-type <package-type>
regress check-rp --root . --rp-id <pilot-rp-id>
regress generate-tests --root . --rp-id <pilot-rp-id> --mode draft
regress draft-expected-results --root . --rp-id <pilot-rp-id>
regress validate --root . --rp-id <pilot-rp-id> --env ci
regress run --root . --rp-id <pilot-rp-id> --env ci --dry-run
regress run --root . --rp-id <pilot-rp-id> --env ci
regress report --root . --rp-id <pilot-rp-id> --batch-id <batch-id>
```

CLI contracts:

- `init-product-repo` creates missing lifecycle folders and starter locations only.
- `check-readiness` reports product and RP readiness in machine-readable form without generating tests. It writes a repo-local readiness report only when `--write-report` and `--rp-id` are explicitly provided.
- `product-repo-readiness` agent skill explains the readiness report, missing items, owner actions, and next steps.
- `init-rp` creates RP skeleton artifacts and lifecycle folders.
- `check-rp` validates product artifact completeness and whether Agent Skill translation inputs are present.
- `generate-tests` writes package-neutral DSL drafts to `tests/draft/` and proposes updates when approved tests already exist.
- `validate` reads generated suite/run/Env_Profile artifacts and checked-in package-neutral DSL tests, validates the contract graph, and does not execute providers.
- `run --dry-run` resolves a provider execution plan without executing providers, mutating fixtures, publishing messages, running SQL, or writing provider execution evidence.
- `run` reads generated suite/run/Env_Profile artifacts and checked-in package-neutral DSL tests, creates one batch ID for the suite execution, creates one run ID per approved test case, and does not regenerate tests by default.
- `report` produces coverage, traceability, evidence index, and failure summary from batch-level evidence. Single-run reports may support debugging, but they are not RP release coverage.

## 5.7 Data Ownership and Storage

v0.2 does not require a database. The Product Repo filesystem is the durable store.

```text
docs/08-release/release-packages/<rp_id>/
  package.yaml
  rp_feature_spec.md
  rp_ru_mapping.yaml
  generated-framework/
    suite_manifest.yaml
    run_plan.yaml
    provider_instances/
    env_profiles/
    traceability_map.yaml
  acceptance_criteria.md
  tests/
  expected-results/
  traceability.md
  evidence_index.md
  evidence/
    readiness/
    generation/
    batches/<batch_id>/
      batch.yaml
    runs/<run_id>/
      run.yaml
      actual/
      logs/
      assertions.yaml
      cleanup.yaml
    review/
```

Consistency model:

- Source artifacts are read at command start.
- Generated drafts record source references and source fingerprints.
- Approved tests and expected results are immutable within a run.
- Batch evidence records the batch ID, execution mode, environment reference, included run IDs, acceptance-criteria source refs, and optional report labels when provided.
- Run evidence records source refs, optional report labels when provided, test case ID, run ID, batch ID, logical target refs, execution mode, and environment reference.

## 5.8 Execution Flow

```text
Select generated suite
-> validate suite_manifest, run_plan, Env_Profile, Provider Instances, framework Provider Contract catalog, and traceability_map
-> load approved or execution-eligible DSL test cases
-> validate execution-focused DSL v0.2 contract and block invalid legacy/governance fields
-> normalize v0.2 source refs, optional report labels, and execution sections for run/report metadata
-> validate expected-result approval status
-> resolve selected Env_Profile, execution mode, and logical target dependency graph
-> resolve DSL target provider_id + selected Env_Profile to Provider Instance, framework Provider Contract, and Env_Profile provider binding values
-> verify environment readiness
-> resolve inputs and runtime bindings
-> create suite execution batch
-> set up fixtures
-> execute provider operation
-> run verify rules
-> clean up fixtures
-> write run evidence
-> write batch summary
-> produce batch-level coverage and traceability report
```

Execution mode behavior:

| Mode | Behavior | Block Condition |
|---|---|---|
| `local` | Run against local fixtures, files, mocks, stubs, generated data, ephemeral DBs, fake topics, or embedded brokers | Missing local Provider Instance, Env_Profile binding value, or allowed runtime mode |
| `ci` | Run against mock/stub/ephemeral replacements, CI-provisioned services, containers, disposable schemas, fake topics, embedded brokers, or jobs | Missing CI Env_Profile provider binding, unsupported runtime mode, or blocked cleanup policy |
| `sit` | Run against already deployed logical targets in SIT with `runtime_mode: native` by default | Missing deployment/readiness evidence or prohibited mock substitution for release evidence |
| `preprod` | Run against pre-production targets with native dependencies, stricter approval, and masking | Missing approval, masking policy, deployment evidence, readiness evidence, or prohibited mock substitution |

## 5.9 Target Dependency Execution

The framework executes logical targets only from the generated `run_plan.yaml`, selected Env_Profile, and Provider Instances. It does not understand whether a target originated from one RU, multiple RUs, an external dependency, or a product topology node.

Rules:

- Build the target execution plan from declared target dependencies.
- Treat an empty `dependencies` list as independent.
- Stop downstream execution when a required upstream target validation fails.
- Continue independent targets when they do not depend on the failed target.
- Record each logical target ref, provider_id, provider_type, env_profile_id, Provider Contract ref, Env_Profile ref, and evidence responsibility.
- Never add, remove, or reorder targets based on naming or inferred product topology.
- Treat product-side `dependency_order` as an Agent Skill input only; it is not a framework runtime contract.

## 5.10 Provider Runtime Public Interface

Provider runtime configuration must use the public model from the user guide:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Provider Contract
  -> Env_Profile
  -> Env_Profile.providers.<provider_id>.binding_keys
```

Provider Contract rules:

- A Provider Contract defines one `provider_type`, allowed operations, allowed input keys, required inputs, binding key schema, allowed value kinds, bindable outputs, defaults, valid output refs, evidence outputs, valid failure codes, and the valid Provider Instance shape.
- Built-in Provider Contracts are owned by the framework catalog and are not generated into every suite by default. Suite-local contracts are allowed only for explicit custom provider or snapshot pinning mode.
- Provider Contract and Provider Instance share the same top-level shape so an instance cannot introduce undeclared fields.
- Contract values that point to inputs, queries, payloads, secrets, or environment resources must be references, not inline sensitive values.
- Fixture operations that mutate state must declare compatible cleanup strategy and cleanup evidence outputs.
- Unsupported operations, unsupported input keys, missing required inputs, missing output refs, missing required binding keys, and unsafe values block before provider dispatch.

Provider Instance rules:

- A Provider Instance defines one RP logical runtime target with `provider_id` and `provider_type`.
- A Provider Instance fills RP-level logical selections only, such as operation names, selected output refs, cleanup strategy choices, and evidence capture choices.
- A Provider Instance cannot add fields, operations, input keys, output refs, evidence outputs, or failure codes that are not allowed by its Provider Contract.

Env_Profile rules:

- An Env_Profile supplies environment-specific actual values for Provider Contract `binding_keys`, such as URLs, topics, DB connection refs, namespaces, host refs, and secret refs.
- Env_Profile `providers` map keys are Provider Instance `provider_id` values.
- Env_Profile `binding_keys` must match the referenced Provider Contract `binding_keys`.
- Env_Profile value kinds must be allowed by the Provider Contract for that binding key.
- Env_Profile `generated_ref` values must resolve to Provider Contract `bindable_outputs`.
- DSL Test Cases must not contain endpoint/topic/DB credential values.
- Missing Env_Profile, missing provider binding, missing required binding key, invalid value kind, or invalid `generated_ref` blocks readiness and dry-run before provider dispatch.

Fixture cleanup precedence:

- DSL `setup.operations[].scope` owns lifecycle scope. Supported v0.2 values are `test_case`, `parameter_case`, and `batch`.
- DSL `setup.operations[].cleanup_policy` owns when cleanup is required. Supported v0.2 values are `always`, `on_success`, `on_failure`, and `manual_blocked`.
- Provider Contract `cleanup_strategy` owns how cleanup is performed for the selected technology, such as `by_test_run_id`, `by_parameter_case_id`, bounded message `drain`, or `delete_files_under_workspace`.
- Provider Instance may select only a cleanup strategy allowed by its Provider Contract.
- Env_Profile may further restrict cleanup scope, destructive behavior, and dependency cleanup. The most restrictive compatible rule wins.
- If a mutating fixture omits required cleanup, declares an unsafe scope, or selects an incompatible provider cleanup strategy, execution blocks before setup/provider dispatch with `fixture_setup_error` or `cleanup_error`.

Runtime rules:

- Non-success exit codes fail the test case and preserve exit code, stdout, stderr, and resolved inputs.
- Timeout fails the test case and still triggers fixture cleanup.
- Provider execution must not deploy product code in v0.2.
- Package-type behavior belongs in Provider Instances and Provider Contracts; DSL, orchestration, evidence format, and verify lifecycle stay in framework core.

## 5.11 Failure Handling

| Failure | Handling |
|---|---|
| Missing Product Repo folder | Report readiness gap and owner action; do not continue to generation. |
| Missing RP artifact | Block generation and execution for that RP. |
| Missing generated suite, run plan, Env_Profile, Provider Instance, unknown provider type, or custom Provider Contract field | Block execution and report the exact field. |
| Unsupported DSL version | Block execution and report supported versions and migration action. |
| Missing required DSL field | Block generation approval or execution and report the field path. |
| V1 DSL source refs or report labels cannot be normalized for run/report | Block execution or review-ready reporting and report the affected test case, source ref, and field path. |
| Ambiguous AC | Mark `not_ready_for_generation`; do not draft executable tests. |
| Existing approved test | Create update proposal or new draft revision; do not overwrite. |
| Expected result not approved | Block normal regression execution. |
| Expected result blocked | Report blocked reason and required owner action. |
| Manual-only or waived AC lacks approval record | Keep AC in denominator or block review-ready coverage report. |
| SIT/preprod readiness missing | Block `sit` or `preprod` run before provider execution. |
| Provider failure | Capture provider_id, provider_type, profile, operation, inputs, outputs, logs, and failure reason. |
| Fixture cleanup failure | Record cleanup failure and mark run evidence incomplete. |

## 5.12 Security and Data Safety

- Production data is disallowed unless masked and approved.
- Secrets must be referenced through environment variables or CI secret stores, never committed.
- Evidence must not include raw secret values.
- Test fixtures should be small and reviewable, or generated by a documented command.
- Destructive operations are blocked unless a validated Provider Contract, Provider Instance, and Env_Profile declare safe scope, cleanup behavior, and an allowed non-production execution mode.
- Local commands should be bounded and avoid memory-heavy execution.

## 5.13 Observability and Evidence

Each suite execution writes a batch summary:

- `batch.yaml`: batch status, timestamps, report labels when provided, execution mode, environment ref, and included run IDs with test case ID, acceptance-criteria source ref, and status.
- Batch reporting consumes normalized source refs, optional labels, generated `traceability_map.yaml`, and run evidence. It must not require new tests to carry legacy-only fields such as `rp_id` or `ac_id`.

Each test run writes:

- `run.yaml`: run status, timestamps, source refs, report labels when provided, batch ID, test case ID, logical target refs, execution mode, provider_id, provider_type, and profile.
- `logs/`: provider logs and framework validation logs.
- `actual/`: captured actual outputs or evidence references.
- `assertions.yaml`: verify results, expected-result refs, actual refs, decision rule, diff summary.
- `observations.yaml`: observation collection results such as log, metric, event, or trace checks.
- `postconditions.yaml`: final-state validation results after execution and cleanup.
- `cleanup.yaml`: fixture cleanup status and cleanup evidence.
- Coverage report, traceability report, evidence index, and failure summary.

v0.2 alerting is CI/report based. A failed command exits non-zero and writes evidence for review.

Canonical batch evidence path:

```text
docs/08-release/release-packages/<rp_id>/evidence/batches/<batch_id>/
```

Canonical run evidence path:

```text
docs/08-release/release-packages/<rp_id>/evidence/runs/<run_id>/
```

## 5.14 Architecture Alternatives

| Alternative | Decision | Reason |
|---|---|---|
| Persistent web service | Rejected for v0.2 | Adds deployment and operations burden before pilot value is proven. |
| Database-backed artifact store | Rejected for v0.2 | Git-versioned artifacts are enough and easier to review. |
| Always require SIT | Rejected | Some RPs can be validated with local or CI fixtures; SIT is required only when the boundary needs deployed behavior. |
| Generate tests on every run | Rejected | Breaks reviewability and durable test case management. |
| Framework decides RP membership or product topology | Rejected | Product/RP/RU membership and topology are owner-authored business/release scope translated by Agent Skills. |
| External runner as primary integration model | Rejected | Would push each RP to maintain its own test tools or scripts and weaken reusable provider governance. |

The architecture should be revisited when multiple RP types require shared service workflows, concurrent teams need a central index, or evidence volume becomes too large for repo-based storage.

## 5.15 AC Coverage Matrix

Framework acceptance criteria validate the reusable execution framework only:

| AC | Architecture Support | Implementation Module |
|---|---|---|
| AC-001 | DSL v0.2 schema, lifecycle status, prohibited fields, secret guardrail, and compatibility validation | `schema`, `validation`, `cli` |
| AC-002 | Selected Env_Profile, logical targets, Provider Instances, Provider Contracts, dependency model, and binding keys resolve before dispatch | `environment`, `binding`, `provider`, generated artifact readers |
| AC-003 | Parameter set expansion creates per-case run context, result, and evidence without inflating AC coverage | `parameter`, `binding`, `result`, `evidence` |
| AC-004 | Fixture setup and cleanup are explicit, scoped, evidence-backed, and blocked when unsafe | `fixture`, `provider`, `evidence` |
| AC-005 | Shell/file-batch provider execution captures bounded command outputs, duration, status, and logs | `execution`, `provider`, `evidence` |
| AC-006 | HTTP/request-response execution captures request/response evidence and provider metadata | `execution`, `provider`, `evidence` |
| AC-007 | JDBC execution and DB verification use referenced connections/queries and normalized evidence | `execution`, `provider`, `assertion`, `evidence` |
| AC-008 | Kafka/NATS publish, consume, observe, cleanup, and NATS request/reply provider mode are validated through messaging contracts | `provider`, `execution`, `assertion`, `evidence` |
| AC-009 | File existence and diff verification use explicit actual/expected refs and normalization rules | `assertion`, `evidence` |
| AC-010 | DB polling verifies bounded final state without retrying product actions | `assertion`, `provider`, `evidence` |
| AC-011 | Event polling verifies bounded observations from an explicit observation position | `assertion`, `provider`, `evidence` |
| AC-012 | Required logs, artifacts, diffs, query results, events, fixture logs, and cleanup evidence are collected and masked | `evidence`, `provider` |
| AC-013 | Standard result JSON normalizes test, parameter, step, verify, evidence, status, and failure fields | `result`, `evidence`, `report` |
| AC-014 | Raw secrets are rejected in DSL, Env_Profile, result, and evidence | `schema`, `environment`, `evidence`, `report` |
| AC-015 | Test, suite, tag, and profile selection uses framework-readable manifests and does not infer RP/RU membership | `cli`, `discovery`, `suite` |
| AC-016 | Provider Contract, Provider Instance, Env_Profile, and verify/plugin contracts expose capability metadata before execution | `provider`, `environment`, `plugin`, `assertion` |
| AC-017 | Product/RP/RU labels are copied as report metadata only and never drive runtime behavior | `binding`, `result`, `report` |
| AC-018 | The same approved generic test can run against compatible local, CI, or SIT profiles through different bindings | `environment`, `binding`, `execution` |

Phase 2 Agent Skill support acceptance is separate from framework runtime AC:

| Support AC | Architecture Support | Implementation Module |
|---|---|---|
| SUP-AC-001 | Product Repo bootstrap, readiness report, optional RU repo scan, and draft/proposed docs/spec creation | `productrepo`, `cli`, readiness agent skill |
| SUP-AC-002 | RP artifact scaffold and completeness checking without inventing formal scope or AC | `productrepo`, `discovery`, RP creation agent skill |
| SUP-AC-003 | RP feature spec and AC intake preserves source refs, review state, and owner-authored truth boundary | `readiness`, `schema`, intake agent skill |
| SUP-AC-004 | Product/RP/RU mapping is translated into suite, run, Env_Profile, Provider Instance, and traceability artifacts | mapping agent skill, generated artifact readers |
| SUP-AC-005 | AC readiness classification and DSL draft generation avoid overwriting approved tests | `readiness`, `testcase`, drafting agent skill |
| SUP-AC-006 | Expected-result drafting preserves assumptions, source refs, and approval boundary | `expectedresult`, drafting agent skill |

## 5.16 Implementation Readiness Gate

Architecture gate result: design target defined for staged framework implementation readiness. This is not a claim that v0.2 implementation is complete. Phase 2 Agent Skill readiness is next-stage and must not block current-stage framework maturity.

Ready to implement now for framework maturity:

- F007/F008/F011 foundation: execution-focused DSL v0.2 validation, generated artifact validation, Env_Profile / Provider Instance resolution plus framework Provider Contract catalog lookup, suite selection, parameter binding, fixture lifecycle, provider capability registry, provider/verify plugin contracts, result JSON, evidence, and report.
- Provider public-interface verification for request/response, messaging, DB fixture, deployment readiness, file/batch, and external runner escape-hatch behavior using local/mock or injectable fixtures.
- Framework verification harness hardening through `./mvnw test`, `./mvnw verify`, and sample generated-artifact integration evidence.

Current-stage framework gates to verify before claiming full framework maturity:

- Materialized schema and contract artifacts exist under `docs/02-architecture/contracts/` or an equivalent framework-owned contract path, including Provider Contract, Provider Instance, Env_Profile, DSL, result, and evidence contracts.
- Align package/module ownership with the AC coverage matrix for `parameter`, `result`, `suite`, `provider`, `provider_runtime`, `plugin`, and generated artifact readers.
- Verify fixture cleanup precedence across DSL `scope` / `cleanup_policy`, Provider Contract `cleanup_strategy`, Provider Instance selection, and Env_Profile restrictions.
- Prove every framework AC has named happy, failure, and boundary verification coverage in the test plan.

Ready after pilot RP artifacts and Phase 2 Agent Skill outputs exist:

- F001 through F006 support flows for Product Repo/RP preparation, product mapping translation, test drafting, and expected-result drafting.
- F007/F008 downstream RP execution against real owner-authored artifacts and selected environment evidence.

## 5.17 Open Decisions

Blocking for full formal F003-F008 pilot validation:

- Pilot RP ID and target release.
- Pilot RP provider type priority across REST/gRPC, Kafka/NATS, DB fixture, K8s and VM readiness, and any approved external runner escape hatch.
- Owner-authored `rp_feature_spec.md`.
- Owner-authored `acceptance_criteria.md`.
- Owner-authored `rp_ru_mapping.yaml`.
- Generated `suite_manifest.yaml`, `run_plan.yaml`, Env_Profiles, Provider Instances, and `traceability_map.yaml`.
- Fixture source and expected-result approval owner.

Non-blocking for F001/F002/F003/F004:

- Final package-type plugin SDK shape.
- Dashboard or central service design.
- Cross-package orchestration.
