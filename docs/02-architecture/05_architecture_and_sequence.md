# 05. Architecture Design

Status: Implementation-Ready Draft for M1

This design turns the Product/RP/RU baseline into an implementable architecture while keeping the framework core product-topology agnostic. Product Repo remains the source of truth, Release Package remains the release and evidence unit, and RU repos remain implementation workspaces; Agent Skills translate that context into framework-readable artifacts.

## 5.1 Design Scope

M1 implements a local/CI-first command-line framework plus Agent Skill translation flow. Product-aware commands and skills read Product Repo artifacts, but the generic execution core consumes generated DSL tests, suite manifests, run plans, environment bindings, provider contracts, expected results, and traceability maps.

In scope:

- Product Repo and RP skeleton readiness.
- Product/RP/RU mapping readiness through Agent Skill translation.
- AC readiness classification and agent draft generation boundaries.
- Durable test case and expected-result lifecycle.
- Local fixture, CI ephemeral, SIT deployed, and evidence-only execution modes.
- Heterogeneous RP pilot support through a provider capability registry, config-driven provider contracts, reusable built-in provider families, and a governed external runner escape hatch.
- Coverage, traceability, and release review evidence.

Out of scope:

- Framework-owned CD deployment orchestration.
- Runtime ownership of RU repos.
- Unreviewed agent-generated expected results as regression truth.
- Persistent dashboard or service runtime.

## 5.2 Architecture Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Runtime shape | CLI-first framework | Fits M1, avoids operating a service, works in local and CI. |
| Durable store | Product Repo filesystem | Specs, tests, expected results, and evidence remain reviewable and versioned. |
| Release unit | Release Package | Package release is the real release boundary; Product is virtual. |
| Primary AC level | RP-level AC | Coverage denominator must match release evidence ownership. |
| Test lifecycle | Generate separately from execute | Approved tests are checked in and not regenerated on every run. |
| Test case DSL | Minimal package-neutral DSL with explicit version | Different Products and RPs express regression tests through one artifact model without overfitting to one package type. |
| Adapter model | Generic runners/providers plus generated environment bindings | Execution process is generic; product topology stays outside the framework core. |
| Heterogeneous RP support | Agent-generated run/environment artifacts plus provider capability registry | Different RU languages, deployments, and messaging styles are translated into logical targets and reusable provider contracts instead of DSL, framework-core changes, or RP-specific scripts. External runner is a governed escape hatch only. |
| Implementation stack | Spring Boot 3.x on Java 17+ | Provides a modern Java runtime, dependency injection, validation, configuration binding, and CLI packaging path. |
| Verification boundary | Separate framework verification from RP regression execution | Maven validates this framework; CLI `run` validates downstream Product/RP packages and writes RP release evidence. |

### Framework Verification vs RP Regression Execution

| Concern | Framework Verification | RP Regression Execution |
|---|---|---|
| Subject under test | This framework codebase and its contracts | A downstream Product Release Package |
| Primary command | `./mvnw test` or `./mvnw verify` | Product-aware wrapper or pipeline generates framework artifacts, then calls the generic runner. |
| Test runner | Surefire for unit/component tests; Failsafe for integration tests | Framework CLI execution engine |
| Fixture source | Sample generated artifacts and local/mock providers | Real Product Repo context translated into generated suite/run/environment/provider artifacts |
| SIT/UAT dependency | Not required | Required only for `sit_deployed` RP validation boundary |
| Evidence meaning | Framework build/CI evidence | Downstream RP release review evidence |

Framework integration tests may exercise the same CLI flows used by RP Regression Execution, but the fixture scope remains framework verification. The architecture must not treat sample fixture evidence as product release evidence.

## 5.3 Component Architecture

The AP-level architecture follows the common test platform reference model: definition, discovery, planning, fixture/state, execution, oracle/assertion, and reporting. Fine-grained resolvers remain internal modules under these components; they are not separate AP-level components.

```text
Product Repo Artifact Store
        |
        v
Phase 2 Agent Skill Translation
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

| Component | Responsibility | Primary AC |
|---|---|---|
| Product Repo Artifact Store | Own versioned docs, RP records, mapping inputs, tests, expected results, and evidence | AC-001, AC-002 |
| Phase 2 Agent Skill Translation | Interpret Product/RP/RU topology and produce framework-readable suite, run, environment, provider, and traceability artifacts | AC-004, AC-005 |
| Definition and Validation | Parse DSL and generated framework artifacts, validate schemas, statuses, required and conditional fields, and artifact lifecycle rules | AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007 |
| Discovery and Context | Load suite manifest, run plan, environment binding, provider contracts, traceability map, and readiness reports | AC-001, AC-002, AC-003, AC-004, AC-005 |
| Planning and Binding | Resolve targets, setup fixtures, execute inputs/outputs, expected results, verify references, runtime references, and provider contracts into an execution plan | AC-005, AC-007 |
| Fixture and State Manager | Check preconditions, set up fixtures, seed or publish data, enforce cleanup, and validate postconditions | AC-007 |
| Execution Engine | Execute planned operations through provider registry dispatch, manage execution modes, captured outputs, timeout, retry, and adapter/provider result capture | AC-007 |
| Oracle and Assertion Engine | Resolve expected-result truth sources and evaluate actual outputs or provider metadata through verify rules, including selector-based structured output checks | AC-006, AC-007 |
| Evidence and Reporting | Persist batch summaries, run evidence, observations, cleanup results, failures, traceability, coverage, and release-review reports | AC-007, AC-009 |

Internal module mapping:

| AP-Level Component | Internal Modules |
|---|---|
| Definition and Validation | `schema`, `testcase`, `expectedresult` packages |
| Discovery and Context | `productrepo`, `discovery`, `environment`, `readiness` packages plus generated artifact readers |
| Planning and Binding | `binding`, `provider`, parameter expansion and execution-plan construction in `testcase` |
| Fixture and State Manager | `fixture` package |
| Execution Engine | `execution`, `adapter` packages |
| Oracle and Assertion Engine | `oracle`, `assertion` packages |
| Evidence and Reporting | `evidence`, `report` packages |

DSL and artifact flow through the 7 AP:

| AP | Main Input | Main Output |
|---|---|---|
| Definition and Validation | DSL tests, suite manifest, run plan, environment binding, provider contracts, expected-result artifacts | Validated artifact graph, schema errors, lifecycle/status errors |
| Discovery and Context | Validated artifact graph plus requested suite, run profile, and environment binding | Execution context, logical target list, traceability labels, available tests, provider contract references |
| Planning and Binding | Execution context, DSL tests, targets, setup fixtures, expected-result references, verify rules, provider contracts | Concrete execution plan with bound targets, inputs, fixtures, expected results, verify refs, and execute output placeholders |
| Fixture and State Manager | Execution plan fixture sections, environment reference, cleanup policy | Prepared state, fixture evidence, cleanup plan, precondition/postcondition results |
| Execution Engine | Execution plan operations and adapter/provider contracts | Operation results, adapter outputs, runtime metadata, timeout/retry outcomes |
| Oracle and Assertion Engine | Actual outputs, expected results, verify rules | Pass/fail decisions with expected value, actual value, comparison rule, and failure reason |
| Evidence and Reporting | All AP outputs, traceability, coverage policy | Durable batch evidence, run evidence package, AC coverage report, release-review summary |

This flow keeps the DSL stable. New Product/RP/RU behavior should be translated by Agent Skills into generated framework artifacts and configurable built-in provider contracts first. New provider code is allowed only for reusable cross-RP behavior; DSL enum extensions require a versioned compatibility decision.

The heterogeneous RP support model and current capability matrix are recorded in `docs/02-architecture/07_heterogeneous_rp_support_capability_matrix.md`.

AP boundary contract:

| AP | Must Fail Before | Required Failure Detail |
|---|---|---|
| Definition and Validation | Discovery, planning, generation approval, or run start | Artifact path, field path, lifecycle status, supported `dsl_version`, owner action |
| Discovery and Context | Planning | RP ID, missing artifact, missing RU, requested environment, owner action |
| Planning and Binding | Fixture setup or adapter/provider execution | Test case ID, AC ID, unresolved target, setup fixture, execute input, expected-result ref, verify ref, evidence ref, runtime policy, provider contract key, owner action |
| Fixture and State Manager | Adapter execution when setup is unsafe or incomplete | Test case ID, setup action, cleanup requirement, state scope, owner action |
| Execution Engine | Verify evaluation when an execute operation fails | Execute step ID, provider or adapter, operation, exit code or timeout, log refs, owner action |
| Oracle and Assertion Engine | Pass/fail reporting when truth or comparison rule is missing | Verify ID, expected-result ref, expected-result approval status when applicable, actual ref or provider metadata source, owner action |
| Evidence and Reporting | Release-review-ready claim | Missing evidence type, affected AC/test/run, coverage impact, owner action |

APs communicate through structured reports and execution-plan records, not hidden side effects. Each AP output must be durable enough to explain why the next AP did or did not run.

For implementation clarity, each AP owns one primary question and one durable handoff artifact:

| AP | Primary Question | Durable Handoff |
|---|---|---|
| Definition and Validation | Are declared artifacts syntactically valid, lifecycle-ready, approved when needed, and compatible with supported versions? | `validation_report` with field paths, lifecycle status, and compatibility result. |
| Discovery and Context | Which suite, DSL tests, trace labels, logical targets, run profile, environment binding, and provider contract references are in scope? | `execution_context` with resolved artifact and contract references. |
| Planning and Binding | Can every logical DSL reference be resolved into an executable plan without embedding provider-specific code in the DSL? | `execution_plan` with bound inputs, parameters, fixture intents, expected-result refs, verify refs, and execute output placeholders. |
| Fixture and State Manager | Is test state safe to prepare and clean up for this execution mode? | `fixture_plan` and `cleanup_plan` with setup, mutation scope, cleanup, and postcondition evidence refs. |
| Execution Engine | Can the planned logical steps run through declared providers and adapters in the selected environment? | `execution_result` with step status, adapter outputs, logs, actual result refs, timeout/retry metadata. |
| Oracle and Assertion Engine | Do approved truth sources or deterministic decision rules prove pass/fail for the actual outputs? | `assertion_result` with expected-result refs, expected, actual, comparator, and failure reason. |
| Evidence and Reporting | Is there enough durable evidence to support coverage and release review without manually reconstructing the RP execution? | `batch_summary`, `evidence_package`, `coverage_report`, `failure_summary`, and `release_review_summary`. |

Provider contracts are configuration artifacts consumed by APs; they are not DSL sections. A provider contract declares a named capability such as a target runner, fixture provider, execute operation provider, expected-result reader, verify provider, or evidence collector. The DSL references those capabilities by logical name, and the AP validates that the referenced capability exists before execution.

Execution-focused DSL v1 references provider behavior through `targets.<target_id>.runner`, `execute[].operation`, `setup.fixtures`, `expected_results`, and `verify` rules. It must not embed provider configuration, endpoint URLs, connection strings, shell scripts, SQL bodies, release gates, waivers, or approval workflow. Legacy fields such as `execution_target`, `package_inputs`, `oracles`, `steps`, `assertions`, `evidence_required`, and `policy` are compatibility inputs only until parser/generator migration is complete.

DSL v1 validation is the first F007 architecture gate. Definition and Validation must confirm syntax, required fields, supported execution lifecycle status, the M1 single-execute-step runtime boundary, forbidden governance fields, selector-based verify requirements, and legacy-field migration rules before Discovery, Planning, provider contract binding, fixture setup, or provider dispatch can run. New DSL artifacts that contain multiple executable `execute[]` operations, `call_ru`, `target_ru_id`, `package_inputs`, `oracles`, release gates, waivers, or approval workflow state are invalid even when equivalent legacy artifacts remain readable during migration.

The gate sequence for execution-focused tests is:

```text
DSL v1 parse and validation
-> traceability and lifecycle status check
-> target and scenario resolution
-> setup, single execute step, execute output, expected_result, verify selector/query/event, evidence, and runtime validation
-> provider contract lookup through environment binding and run plan
-> execution plan creation
-> fixture setup and provider dispatch
```

Provider capability registry rules:

- The registry is the canonical source for supported `provider_family` and `provider_type` combinations.
- Each registry entry declares required fields, supported actions, allowed execution modes, runtime support status, evidence outputs, and safety policy.
- Supported runtime status values are `supported`, `partial`, `escape_hatch`, and `unsupported`.
- Provider contracts must explicitly declare `provider_family` and `provider_type`. Heuristic family inference may be retained only for backward-compatible diagnostics and must not silently choose a runtime.
- Contract resolution follows framework defaults, then generated suite/run-profile/environment-binding overrides. Ambiguous logical target or provider matches block dry-run and execution.
- Execution dispatch must go through the registry. Adding a provider runtime should not require adding product-specific conditionals to the execution engine.
- External runner entries are `escape_hatch` providers. They require explicit owner approval metadata, bounded timeout, declared inputs/outputs, and an evidence map.

Current provider runtime status:

| Provider Family / Type | Runtime Status | Primary Implementation | Minimum Required Contract Fields | Verification Boundary |
|---|---|---|---|---|
| `file_batch/shell` | Supported | `ProviderRuntimeRegistry` dispatch to `DataPipelineAdapter` | `command`, positive `timeout_seconds`, `outputs.actual_output_ref`, logs, success exit codes when non-default | Framework unit/component and sample integration tests. |
| `request_response/rest` | Supported | `RequestResponseProvider` | endpoint/base/service ref, action map, request binding where action needs input, positive `timeout_seconds`, `outputs.actual_output_ref` | Framework provider-family tests. |
| `request_response/grpc` | Supported for unary descriptor-driven calls | `RequestResponseProvider` plus `DefaultGrpcClientInvoker` | service ref, descriptor ref, action service and method, request binding, positive `timeout_seconds`, `outputs.actual_output_ref` | Framework registry, CLI preflight, provider evidence, and loopback gRPC tests; not yet pilot endpoint evidence. |
| `messaging/local` and `messaging/mock` | Supported for local/mock evidence | `MessagingProvider` | topic/subject/stream/endpoint ref, supported action mode, payload binding for publish/request, cleanup strategy and positive max count for cleanup, positive `timeout_seconds`, `outputs.actual_output_ref`, correlation id when required | Framework provider-family tests. |
| `messaging/kafka` and `messaging/nats` | Supported for native publish, consume/observe, and bounded cleanup drain; NATS also supports native request/reply | `MessagingProvider` plus `DefaultMessagingTransport` | bootstrap/server/connection ref, topic or subject ref, supported action mode, payload binding for publish and request/reply, cleanup strategy and positive max count for cleanup, positive `timeout_seconds`, `outputs.actual_output_ref`, correlation id when required | Framework provider-family and injectable transport tests; pilot broker evidence remains pending. |
| `db_fixture/jdbc` | Supported for JDBC fixture lifecycle and DB row count assertions | `DatabaseFixtureProvider` plus `AssertionEngine` | `connection_ref`, `isolation_key`, `cleanup_strategy`, setup/cleanup `sql_ref`, verification query `sql_ref` or query-result expected-result when declared | Framework provider-family tests with H2/local JDBC fixtures. |
| `deployment_readiness/local` and `deployment_readiness/mock` | Supported for local/mock readiness | `DeploymentReadinessProvider` | readiness probe, deployment/service/target ref, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` | Framework provider-family tests. |
| `deployment_readiness/k8s` | Supported for bounded `kubectl` probes, direct API deployment availability, and pod log capture | `DeploymentReadinessProvider` plus `DefaultDeploymentReadinessProbe` | readiness probe, namespace ref, kube context or API server ref, deployment/service/selector refs, `deployed_version_ref`, positive `timeout_seconds`, bounded log tail when used, `outputs.actual_output_ref` | Framework provider-family and stubbed API tests; pilot cluster evidence remains pending. |
| `deployment_readiness/vm` | Supported for bounded TCP, HTTP health, SSH command, and WinRM command probes | `DeploymentReadinessProvider` plus `DefaultDeploymentReadinessProbe` | host or health URL ref, command refs for SSH/WinRM, optional executable wrapper refs, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` | Framework provider-family tests; pilot VM evidence remains pending. |
| `external_runner/command_runner` | Supported escape hatch | `DataPipelineAdapter` plus `ExecutionEngine` external-runner evidence mapping | approval ref, approver, reason, command/container ref, inputs, outputs, positive timeout, evidence map, no built-in-provider alternative | Framework contract and evidence tests; not the standard extension path. |

The architecture is implementation-ready for supported rows only. Pilot acceptance still requires owner-provided RP artifacts and real environment evidence for the selected provider contracts.

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
  readiness/
    ReadinessService.java
  mapping/
    RpRuMappingService.java
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
  fixture/
    FixtureLifecycleService.java
  execution/
    ExecutionEngine.java
  adapter/
    ExecutionAdapter.java
    DataPipelineAdapter.java
  oracle/
    OracleResolver.java
  assertion/
    AssertionEngine.java
  evidence/
    EvidenceWriter.java
  report/
    CoverageReportService.java
```

Boundary rules:

- `cli` orchestrates use cases only; it does not embed validation logic.
- `schema` owns typed parsing and schema validation for RP artifacts.
- `readiness` reports gaps and owner actions; it does not create business truth.
- F001 readiness agent skill consumes readiness reports; it does not mutate repo artifacts directly.
- Product mapping interpretation belongs to the Agent Skill. Framework modules consume generated suite/run/environment artifacts and never infer RP membership.
- The test case DSL describes validation intent, targets, setup fixtures, execute operations, expected results, verify rules, evidence, and runtime policy; it does not contain package-specific execution logic.
- `dsl_version` is required so the runner can apply the correct parser and compatibility behavior.
- `testcase` may create draft artifacts but must not overwrite approved tests.
- `expectedresult` enforces source references and approval status.
- `environment` blocks SIT runs unless deployment and readiness evidence exist.
- `binding` resolves targets, setup fixtures, execute inputs, expected results, verify references, runtime references, and output placeholders; it does not execute package behavior.
- `provider` owns the capability registry, validates provider contract precedence, and dispatches provider contracts by target runner, execute operation, fixture type, expected-result type, verify type, and evidence need.
- `fixture` owns setup, cleanup, precondition, and postcondition lifecycle coordination.
- `execution` executes a prepared plan and records operation results; it does not own schema validation, binding resolution, provider contract resolution, or fixture policy.
- `oracle` loads expected-result sources and decision parameters; `assertion` applies verify rules against actual outputs.
- `assertion` evaluates structured output selectors declared by DSL verify rules; it must not infer missing JSONPath selectors for `json_path_equals` or `json_path_absent` in new execution-focused DSL artifacts. Provider metadata checks, such as `response_status_equals`, may use request/response provider evidence without a DSL `actual` field when the provider contract supplies HTTP status metadata.
- `adapter` is the legacy shell/file execution boundary. New heterogeneous behavior should enter through provider registry entries before considering adapter-specific or external-runner behavior.
- `evidence` and `report` write durable evidence under the RP release record.

DSL-to-module responsibility:

| DSL Section | Primary Module | Secondary Module |
|---|---|---|
| `dsl_version`, `test_case_id`, `status`, `revision`, `traceability` | `schema`, `testcase` | `evidence` |
| `targets`, `scenario` | `testcase`, `binding` | `provider`, generated artifact readers |
| `setup.fixtures` | `fixture`, `binding` | `provider`, `environment`, `evidence` |
| `execute` | `execution` | `adapter`, `provider`, `binding` |
| `expected_results`, `verify` | `expectedresult`, `oracle`, `assertion` | `binding` |
| `evidence`, `runtime` | `evidence`, `report`, `execution` | `fixture` |

If a DSL section cannot be assigned to one of these modules, it is not ready for M1 execution and must be treated as an unsupported DSL extension.

## 5.5 Extension Model for Product Variants

The framework must scale across different Product/RP/RU test needs by keeping the DSL semantic core stable and moving product-specific interpretation into Agent Skill translation. A new RP type should normally generate framework-readable targets, run plans, environment bindings, and provider contracts, not change runner orchestration or provider code.

```text
Product / RP / RU Docs
        |
        +--> Phase 2 Agent Skill Translation
              +--> Suite Manifest
              +--> Run Plan
              +--> Environment Binding
              +--> Traceability Map
        |
Stable Framework Core
        |
        +--> Extension Points
              +--> Execution Adapter
              +--> Target and Binding Provider
              +--> Fixture Provider
              +--> Expected-Result Reader
              +--> Verify Provider
              +--> Evidence Collector
```

Core runner owns:

- DSL parsing, schema validation, and compatibility rules.
- Test discovery from suite manifest, parameter expansion, target dependency planning, and lifecycle orchestration.
- Dispatch to adapters/providers by declared target runner, execute operation, fixture type, expected-result type, verify type, and evidence need.
- Stable evidence structure and traceability to source labels, AC, test case, run, parameter case, logical target, and environment.

Adapters and providers own:

- How to call a logical target through CLI, HTTP, batch, DB, queue, or package-specific tooling.
- How to materialize execution inputs and setup fixtures such as `db_seed`, `message_event`, `api_payload`, `config_file`, and `existing_state`.
- How to set up and clean up package-specific state safely.
- How to load package-specific expected-result truth sources and produce actual values compatible with verify rules.
- How to collect package-specific logs, metrics, traces, events, and final-state probes.

Provider implementation must be reusable by default. RP-specific behavior belongs in validated `provider_contracts` configuration, grouped by adapters, bindings, fixtures, expected-result readers, verify providers, and evidence collectors. Provider configuration is not part of the test case DSL; DSL tests may reference provider capabilities only through logical fields such as target runner, execute operation, fixture type, expected-result type, verify type, and evidence need. Provider code should know generic actions and resource types, not product business concepts.

Example configurable HTTP adapter contract:

```yaml
provider_contracts:
  adapters:
    http_api:
      provider_family: request_response
      provider_type: rest
      endpoint_ref: env://ORDER_API
      timeout_seconds: 30
      actions:
        submit_order:
          method: POST
          path: /orders
          request_binding: order_payload
          response_mapping:
            order_id: $.id
      outputs:
        actual_output_ref: actual/order-response.json
```

Example configurable relational DB fixture contract:

```yaml
provider_contracts:
  fixtures:
    relational_db:
      provider_family: db_fixture
      provider_type: jdbc
      connection_ref: secret://sit/order-db
      isolation_key: test_run_id
      cleanup_strategy: by_test_run_id
      setup_actions:
        seed_orders:
          sql_ref: fixtures/db/seed_orders.sql
      cleanup_actions:
        cleanup_orders:
          sql_ref: fixtures/db/cleanup_orders.sql
      verification_queries:
        seeded_orders:
          sql_ref: fixtures/db/count_orders.sql
```

The Agent-generated framework artifacts are the primary extension entrypoint for runtime. Product-owned RP/RU mapping may declare adapter intent, validation boundary, execution mode, environment reference, dependencies, and evidence responsibility, but the framework core consumes only the generated suite manifest, run plan, environment binding, provider contracts, and traceability map. The DSL references logical capabilities; it must not embed package-specific shell scripts, URLs, credentials, SQL bodies, queue implementation details, or database commands.

Extension governance rules:

- Prefer configuring an existing built-in provider before adding provider code or a new DSL field.
- Add provider code only when the required behavior is reusable across RPs and cannot be expressed safely by an existing provider contract.
- Use an external runner only as an approved escape hatch for legacy or specialized boundaries, not as the normal way to onboard each RP.
- Validate provider contract schemas before execution, including required fields, secret references, cleanup strategy, and unsupported actions.
- Add a new DSL enum only when a recurring cross-RP concept cannot be represented by existing target runner, fixture type, execute operation, expected-result type, verify type, or evidence output.
- Add or change DSL required fields only through a `dsl_version` compatibility decision.
- A package adapter must not silently skip unsupported DSL sections; it must fail fast with test case ID, AC ID, section name, and owner action.

## 5.6 CLI Boundary

The CLI is the M1 public interface. Commands return non-zero exit codes when readiness, validation, or execution gates fail.

Maven commands are not the RP public execution interface. `./mvnw test` and `./mvnw verify` validate this framework. Product-aware tooling may expose an RP-oriented wrapper, but the framework execution core validates a generated suite/run/environment set and writes evidence through the configured output paths.

```bash
regress init-product-repo --root .
regress check-readiness --root .
regress check-readiness --root . --rp-id <pilot-rp-id> --write-report
agent product-repo-readiness --report docs/08-release/release-packages/<rp_id>/evidence/readiness/readiness.yaml
regress init-rp --root . --rp-id <pilot-rp-id> --package-type <package-type>
regress check-rp --root . --rp-id <pilot-rp-id>
regress generate-tests --root . --rp-id <pilot-rp-id> --mode draft
regress draft-expected-results --root . --rp-id <pilot-rp-id>
regress run --suite-manifest generated-framework/suite_manifest.yaml --run-plan generated-framework/run_plan.yaml --environment-binding generated-framework/environment_bindings/ci_ephemeral.yaml
regress report --root . --rp-id <pilot-rp-id> --batch-id <batch-id>
```

CLI contracts:

- `init-product-repo` creates missing lifecycle folders and starter locations only.
- `check-readiness` reports product and RP readiness in machine-readable form without generating tests. It writes a repo-local readiness report only when `--write-report` and `--rp-id` are explicitly provided.
- `product-repo-readiness` agent skill explains the readiness report, missing items, owner actions, and next steps.
- `init-rp` creates RP skeleton artifacts and lifecycle folders.
- `check-rp` validates product artifact completeness and whether Agent Skill translation inputs are present.
- `generate-tests` writes package-neutral DSL drafts to `tests/draft/` and proposes updates when approved tests already exist.
- `run` reads generated suite/run/environment artifacts and checked-in package-neutral DSL tests, creates one batch ID for the suite execution, creates one run ID per approved test case, and does not regenerate tests by default.
- `report` produces coverage, traceability, evidence index, and failure summary from batch-level evidence. Single-run reports may support debugging, but they are not RP release coverage.

## 5.7 Data Ownership and Storage

M1 does not require a database. The Product Repo filesystem is the durable store.

```text
docs/08-release/release-packages/<rp_id>/
  package.yaml
  rp_feature_spec.md
  rp_ru_mapping.yaml
  generated-framework/
    suite_manifest.yaml
    run_plan.yaml
    environment_bindings/
    provider_contracts/
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
- Batch evidence records the RP ID, batch ID, execution mode, environment reference, and included run IDs.
- Run evidence records trace package labels when provided, AC ID, test case ID, run ID, batch ID, logical target refs, execution mode, and environment reference.

## 5.8 Execution Flow

```text
Select generated suite
-> validate suite_manifest, run_plan, environment_binding, provider contracts, and traceability_map
-> load approved or execution-eligible DSL test cases
-> validate execution-focused DSL v1 contract and block invalid legacy/governance fields
-> normalize v1 traceability and execution sections for run/report metadata
-> validate expected-result approval status
-> resolve execution mode and logical target dependency graph
-> resolve provider contracts from DSL logical references
-> verify environment readiness
-> resolve inputs and runtime bindings
-> create suite execution batch
-> set up fixtures
-> execute adapter or collect evidence-only input
-> run verify rules
-> clean up fixtures
-> write run evidence
-> write batch summary
-> produce batch-level coverage and traceability report
```

Execution mode behavior:

| Mode | Behavior | Block Condition |
|---|---|---|
| `local_fixture` | Run against local fixtures, files, mocks, or generated data | Missing fixture or local adapter config |
| `ci_ephemeral` | Run against temporary CI services, containers, schemas, or jobs | Missing CI environment reference |
| `sit_deployed` | Run against already deployed logical targets in SIT | Missing deployment or readiness evidence |
| `evidence_only` | Validate mapped evidence without direct execution | Missing approved evidence reference |

## 5.9 Target Dependency Execution

The framework executes logical targets only from the generated `run_plan.yaml` and `environment_binding.yaml`. It does not understand whether a target originated from one RU, multiple RUs, an external dependency, or a product topology node.

Rules:

- Build the target execution plan from declared target dependencies.
- Treat an empty `dependencies` list as independent.
- Stop downstream execution when a required upstream target validation fails.
- Continue independent targets when they do not depend on the failed target.
- Record each logical target ref, provider contract ref, environment reference, and evidence responsibility.
- Never add, remove, or reorder targets based on naming or inferred product topology.
- Treat product-side `dependency_order` as an Agent Skill input only; it is not a framework runtime contract.

## 5.10 Adapter and Provider Contract

Executable adapters and providers must define validated contracts before F007 execution. A contract may be supplied by a reusable adapter/provider default or generated suite/run/environment binding.

Minimum executable adapter fields:

- Command and working directory.
- Timeout seconds.
- Resolved input references.
- Actual output references under `evidence/runs/<run_id>/actual/`.
- Stdout and stderr log paths under `evidence/runs/<run_id>/logs/`.
- Success exit codes.
- Environment variables required by the adapter.

Minimum provider contract rules:

- A provider contract must declare provider family, provider type, supported operation or capability names, runtime support status, and required evidence outputs.
- A provider contract must reference inputs, queries, payloads, secrets, or environment resources by reference, not inline sensitive values.
- Fixture providers that mutate state must declare cleanup strategy.
- Expected-result readers must declare truth source type, source reference, and allowed usage.
- Verify providers must declare verify type, actual/expected requirements, provider metadata requirements when applicable, selector/query/event requirements, and comparison or decision rule.
- Evidence collectors must declare source and collection rule.

Runtime rules:

- Non-success exit codes fail the test case and preserve exit code, stdout, stderr, and resolved inputs.
- Timeout fails the test case and still triggers fixture cleanup.
- Adapter execution must not deploy product code in M1.
- Package-type behavior belongs in configurable adapters/providers; DSL, orchestration, evidence format, and verify lifecycle stay in framework core.

## 5.11 Failure Handling

| Failure | Handling |
|---|---|
| Missing Product Repo folder | Report readiness gap and owner action; do not continue to generation. |
| Missing RP artifact | Block generation and execution for that RP. |
| Missing generated suite, run plan, environment binding, or provider contract field | Block execution and report the exact field. |
| Unsupported DSL version | Block execution and report supported versions and migration action. |
| Missing required DSL field | Block generation approval or execution and report the field path. |
| V1 DSL traceability cannot be normalized for run/report | Block execution or review-ready reporting and report the affected test case, AC, and field path. |
| Ambiguous AC | Mark `not_ready_for_generation`; do not draft executable tests. |
| Existing approved test | Create update proposal or new draft revision; do not overwrite. |
| Expected result not approved | Block normal regression execution. |
| Expected result blocked | Report blocked reason and required owner action. |
| Manual-only or waived AC lacks approval record | Keep AC in denominator or block review-ready coverage report. |
| SIT readiness missing | Block `sit_deployed` run before adapter execution. |
| Adapter failure | Capture command, inputs, outputs, logs, and failure reason. |
| Fixture cleanup failure | Record cleanup failure and mark run evidence incomplete. |

## 5.12 Security and Data Safety

- Production data is disallowed unless masked and approved.
- Secrets must be referenced through environment variables or CI secret stores, never committed.
- Evidence must not include raw secret values.
- Test fixtures should be small and reviewable, or generated by a documented command.
- Destructive operations are blocked unless a validated provider contract declares safe scope, cleanup behavior, and an allowed non-production execution environment.
- Local commands should be bounded and avoid memory-heavy execution.

## 5.13 Observability and Evidence

Each suite execution writes a batch summary:

- `batch.yaml`: batch status, timestamps, trace package labels when provided, execution mode, environment ref, and included run IDs with test case ID, AC ID, and status.
- Batch reporting consumes normalized traceability from execution-focused DSL v1 and run evidence. It must not require new tests to carry legacy-only fields such as `rp_id` or `ac_id`.

Each test run writes:

- `run.yaml`: run status, timestamps, trace package labels when provided, batch ID, AC ID, test case ID, logical target refs, execution mode.
- `logs/`: adapter logs and framework validation logs.
- `actual/`: captured actual outputs or evidence references.
- `assertions.yaml`: verify results, expected-result refs, actual refs, decision rule, diff summary.
- `observations.yaml`: observation collection results such as log, metric, event, or trace checks.
- `postconditions.yaml`: final-state validation results after execution and cleanup.
- `cleanup.yaml`: fixture cleanup status and cleanup evidence.
- Coverage report, traceability report, evidence index, and failure summary.

M1 alerting is CI/report based. A failed command exits non-zero and writes evidence for review.

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
| Persistent web service | Rejected for M1 | Adds deployment and operations burden before pilot value is proven. |
| Database-backed artifact store | Rejected for M1 | Git-versioned artifacts are enough and easier to review. |
| Always require SIT | Rejected | Some RPs can be validated with local or CI fixtures; SIT is required only when the boundary needs deployed behavior. |
| Generate tests on every run | Rejected | Breaks reviewability and durable test case management. |
| Framework decides RP membership or product topology | Rejected | Product/RP/RU membership and topology are owner-authored business/release scope translated by Agent Skills. |
| External runner as primary integration model | Rejected | Would push each RP to maintain its own test tools or scripts and weaken reusable provider governance. |

The architecture should be revisited when multiple RP types require shared service workflows, concurrent teams need a central index, or evidence volume becomes too large for repo-based storage.

## 5.15 AC Coverage Matrix

| AC | Architecture Support | Implementation Module |
|---|---|---|
| AC-001 | Product Repo bootstrap CLI, machine-readable readiness report, and readiness agent explanation | `productrepo`, `cli`, readiness agent skill |
| AC-002 | RP skeleton and artifact completeness check | `discovery`, `schema` |
| AC-003 | AC intake preserving owner-authored truth | `readiness`, `schema` |
| AC-004 | Generated framework artifact binding and unsafe execution block | `environment`, `provider`, generated artifact readers |
| AC-005 | Agent DSL test drafting only from ready inputs and no silent overwrite | `readiness`, `testcase` |
| AC-006 | Expected result source references and approval gate | `expectedresult` |
| AC-007 | RP DSL test execution with inputs, fixtures, adapters/providers, verify rules, evidence | `execution`, `binding`, `provider`, `fixture`, `adapter`, `assertion`, `evidence` |
| AC-008 | Unsafe or incomplete regression execution is blocked | `environment`, `mapping`, `provider`, `execution` |
| AC-009 | Coverage, traceability, failures, and approved exclusions | `report`, `evidence` |
| AC-010 | Framework verification and RP regression execution remain separate | Maven Surefire/Failsafe configuration, sample Product Repo fixture, `cli`, `evidence` |

## 5.16 Implementation Readiness Gate

Architecture gate result: PASS for design readiness and staged implementation readiness.

Ready to implement now:

- F001 Product Repo Bootstrap CLI and Readiness Agent Skill.
- F002 Release Package Creation Guide and Completeness Check.
- F004 Agent Skill translation contract and generated artifact validation, using placeholder pilot data or fixtures.
- Execution-focused DSL v1 validation and generator guard, before expanding provider runtime dispatch.

Ready after pilot RP artifacts exist:

- F003 RP AC intake against real owner-authored AC.
- F005/F006 agent draft generation and expected-result drafting.
- F007 execution path for the selected heterogeneous pilot provider set, after the DSL v1 validation gate is green and using the adapter/provider contract model in the artifact contracts and capability matrix.
- F008 final coverage and evidence package using real run evidence.

Implementation must start with F001/F002/F004 foundation tasks, then validate generated framework artifacts before enabling generation and execution.

## 5.17 Open Decisions

Blocking for full F003-F008 implementation:

- Pilot RP ID and target release.
- Pilot RP provider family priority across REST/gRPC, Kafka/NATS, DB fixture, K8s and VM readiness, and any approved external runner escape hatch.
- Owner-authored `rp_feature_spec.md`.
- Owner-authored `acceptance_criteria.md`.
- Owner-authored `rp_ru_mapping.yaml`.
- Generated `suite_manifest.yaml`, `run_plan.yaml`, `environment_binding.yaml`, provider contracts, and `traceability_map.yaml`.
- Fixture source and expected-result approval owner.

Non-blocking for F001/F002/F004:

- Final package-type plugin SDK shape.
- Dashboard or central service design.
- Cross-package orchestration.
