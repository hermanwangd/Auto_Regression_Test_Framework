# 05. Architecture Design

Status: Implementation-Ready Draft for M1

This design turns the Product/RP/RU baseline into an implementable architecture for the M1 Release Package Regression Framework. It preserves the agreed ownership model: Product Repo is the source of truth, Release Package is the release and evidence unit, and RU repos remain implementation workspaces.

## 5.1 Design Scope

M1 implements a local/CI-first command-line framework. It reads Product Repo artifacts, checks RP readiness, assists agent-based test drafting, executes approved RP tests, and emits evidence packages.

In scope:

- Product Repo and RP skeleton readiness.
- RP artifact and RP/RU mapping validation.
- AC readiness classification and agent draft generation boundaries.
- Durable test case and expected-result lifecycle.
- Local fixture, CI ephemeral, SIT deployed, and evidence-only execution modes.
- Data pipeline pilot adapter as the first package-specific adapter.
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
| Adapter model | Core framework plus package adapters | Execution process is generic; package behavior stays adapter-specific. |
| Implementation stack | Spring Boot 3.x on Java 17+ | Provides a modern Java runtime, dependency injection, validation, configuration binding, and CLI packaging path. |

## 5.3 Component Architecture

The AP-level architecture follows the common test platform reference model: definition, discovery, planning, fixture/state, execution, oracle/assertion, and reporting. Fine-grained resolvers remain internal modules under these components; they are not separate AP-level components.

```text
Product Repo Artifact Store
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
| Product Repo Artifact Store | Own versioned docs, RP records, tests, expected results, and evidence | AC-001, AC-002 |
| Definition and Validation | Parse DSL and RP artifacts, validate schemas, statuses, required and conditional fields, and artifact lifecycle rules | AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-007 |
| Discovery and Context | Discover Product/RP/RU artifacts, load AC inventory, RP/RU mapping, environment context, and readiness reports | AC-001, AC-002, AC-003, AC-004, AC-005 |
| Planning and Binding | Expand parameters, resolve dependencies, bind package inputs, expected results, oracles, runtime references, and step placeholders into an execution plan | AC-005, AC-007 |
| Fixture and State Manager | Check preconditions, set up fixtures, seed or publish data, enforce cleanup, and validate postconditions | AC-007 |
| Execution Engine | Execute planned steps through package adapters, manage execution modes, step outputs, timeout, retry, and adapter result capture | AC-007 |
| Oracle and Assertion Engine | Resolve oracle truth sources and evaluate actual outputs through assertion decision rules | AC-007 |
| Evidence and Reporting | Persist run evidence, observations, cleanup results, failures, traceability, coverage, and release-review reports | AC-007, AC-008 |

Internal module mapping:

| AP-Level Component | Internal Modules |
|---|---|
| Definition and Validation | `schema`, `testcase`, `expectedresult` packages |
| Discovery and Context | `productrepo`, `discovery`, `mapping`, `environment`, `readiness` packages |
| Planning and Binding | `binding`, `provider`, parameter expansion and execution-plan construction in `testcase` |
| Fixture and State Manager | `fixture` package |
| Execution Engine | `execution`, `adapter` packages |
| Oracle and Assertion Engine | `oracle`, `assertion` packages |
| Evidence and Reporting | `evidence`, `report` packages |

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
- `mapping` consumes `rp_ru_mapping.yaml`; it never infers RP membership.
- The test case DSL describes validation intent, inputs, fixtures, assertions, and evidence requirements; it does not contain package-specific execution logic.
- `dsl_version` is required so the runner can apply the correct parser and compatibility behavior.
- `testcase` may create draft artifacts but must not overwrite approved tests.
- `expectedresult` enforces source references and approval status.
- `environment` blocks SIT runs unless deployment and readiness evidence exist.
- `binding` resolves parameters, package inputs, oracles, runtime references, and step placeholders; it does not execute package behavior.
- `provider` resolves provider contract precedence and dispatches provider contracts by adapter/action, `bind_as`, fixture action, oracle type, assertion type, and observation type.
- `fixture` owns setup, cleanup, precondition, and postcondition lifecycle coordination.
- `execution` executes a prepared plan and records step results; it does not own schema validation, binding resolution, provider contract resolution, or fixture policy.
- `oracle` loads truth sources and decision parameters; `assertion` applies comparison rules against actual outputs.
- `adapter` is the only package-type-specific execution boundary.
- `evidence` and `report` write durable evidence under the RP release record.

## 5.5 Extension Model for RP/RU Variants

The framework must scale across different RP and RU test needs by keeping the DSL semantic core stable and moving package-specific behavior into configurable adapters and providers. A new RP type should normally configure an existing adapter/provider through RP/RU contracts, not change runner orchestration or provider code.

```text
Stable DSL Core
        |
        +--> RP/RU Mapping Contract
        |
        +--> Extension Points
              +--> Execution Adapter
              +--> Binding Provider
              +--> Fixture Provider
              +--> Oracle Provider
              +--> Assertion Provider
              +--> Observation Provider
```

Core runner owns:

- DSL parsing, schema validation, and compatibility rules.
- Test discovery, parameter expansion, dependency planning, and lifecycle orchestration.
- Dispatch to adapters/providers by declared `adapter`, `action`, `bind_as`, oracle type, assertion type, observation type, and fixture action.
- Stable evidence structure and traceability to RP, AC, test case, run, parameter case, RU, and environment.

Adapters and providers own:

- How to call an RU through CLI, HTTP, batch, DB, queue, or package-specific tooling.
- How to materialize package input bindings such as `db_seed`, `message_event`, `api_payload`, `config_file`, and `existing_state`.
- How to set up and clean up package-specific state safely.
- How to load package-specific oracle truth sources and produce actual values compatible with assertions.
- How to collect package-specific logs, metrics, traces, events, and final-state probes.

Provider implementation must be reusable by default. RP-specific behavior belongs in validated `provider_contracts` configuration, grouped by adapters, bindings, fixtures, oracles, assertions, and observations. Provider configuration is not part of the test case DSL; DSL tests may reference provider capabilities only by logical fields such as `action`, `bind_as`, oracle type, assertion type, observation type, and fixture action. Provider code should know generic actions and resource types, not product business concepts.

Example configurable HTTP adapter contract:

```yaml
provider_contracts:
  adapters:
    http_api:
      actions:
        submit_order:
          method: POST
          path: /orders
          request_binding: order_payload
          response_mapping:
            order_id: $.id
```

Example configurable relational DB fixture contract:

```yaml
provider_contracts:
  fixtures:
    relational_db:
      connection_ref: secret://sit/order-db
      seed_actions:
        seed_orders:
          input_binding: orders_seed
          table: orders
          key_fields: [test_run_id, order_id]
      cleanup_actions:
        cleanup_orders:
          table: orders
          where:
            test_run_id: ${run.id}
```

The RP/RU mapping is the primary extension entrypoint. It declares adapter mode, provider contracts, validation boundary, execution mode, environment reference, dependencies, and evidence responsibility. The DSL references those logical capabilities; it must not embed package-specific shell scripts, URLs, credentials, SQL bodies, queue implementation details, or database commands.

Extension governance rules:

- Prefer configuring an existing adapter/provider before adding provider code or a new DSL field.
- Add provider code only when the required behavior cannot be expressed safely by an existing provider contract.
- Validate provider contract schemas before execution, including required fields, secret references, cleanup strategy, and unsupported actions.
- Add a new DSL enum only when a recurring cross-RP concept cannot be represented by existing `bind_as`, fixture action, oracle type, assertion type, observation type, or adapter action.
- Add or change DSL required fields only through a `dsl_version` compatibility decision.
- A package adapter must not silently skip unsupported DSL sections; it must fail fast with test case ID, AC ID, section name, and owner action.

## 5.6 CLI Boundary

The CLI is the M1 public interface. Commands return non-zero exit codes when readiness, validation, or execution gates fail.

```bash
regress init-product-repo --root .
regress check-readiness --root .
agent product-repo-readiness --report docs/08-release/release-packages/<rp_id>/evidence/readiness/readiness.yaml
regress init-rp --root . --rp-id RP-AR-M1-data-pipeline --package-type data_pipeline
regress check-rp --root . --rp-id RP-AR-M1-data-pipeline
regress generate-tests --root . --rp-id RP-AR-M1-data-pipeline --mode draft
regress draft-expected-results --root . --rp-id RP-AR-M1-data-pipeline
regress run --root . --rp-id RP-AR-M1-data-pipeline --env ci_ephemeral
regress report --root . --rp-id RP-AR-M1-data-pipeline --run-id RUN-20260626-001
```

CLI contracts:

- `init-product-repo` creates missing lifecycle folders and starter locations only.
- `check-readiness` reports product and RP readiness in machine-readable form without generating tests.
- `product-repo-readiness` agent skill explains the readiness report, missing items, owner actions, and next steps.
- `init-rp` creates RP skeleton artifacts and lifecycle folders.
- `check-rp` validates artifact completeness, RP/RU mapping, and generation readiness.
- `generate-tests` writes package-neutral DSL drafts to `tests/draft/` and proposes updates when approved tests already exist.
- `run` reads checked-in package-neutral DSL tests and does not regenerate tests by default.
- `report` produces coverage, traceability, evidence index, and failure summary.

## 5.7 Data Ownership and Storage

M1 does not require a database. The Product Repo filesystem is the durable store.

```text
docs/08-release/release-packages/<rp_id>/
  package.yaml
  rp_feature_spec.md
  rp_ru_mapping.yaml
  acceptance_criteria.md
  tests/
  expected-results/
  traceability.md
  evidence_index.md
  evidence/
    readiness/
    generation/
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
- Evidence records the RP ID, AC ID, test case ID, run ID, RU version references, execution mode, and environment reference.

## 5.8 Execution Flow

```text
Select RP
-> validate package.yaml and RP artifacts
-> validate rp_ru_mapping.yaml
-> load approved or execution-eligible DSL test cases
-> validate expected-result approval status
-> resolve execution mode and RU dependency graph
-> verify environment readiness
-> resolve inputs and runtime bindings
-> set up fixtures
-> execute adapter or collect evidence-only input
-> run assertions
-> clean up fixtures
-> write raw evidence
-> produce coverage and traceability report
```

Execution mode behavior:

| Mode | Behavior | Block Condition |
|---|---|---|
| `local_fixture` | Run against local fixtures, files, mocks, or generated data | Missing fixture or local adapter config |
| `ci_ephemeral` | Run against temporary CI services, containers, schemas, or jobs | Missing CI environment reference |
| `sit_deployed` | Run against already deployed RU versions in SIT | Missing deployment or readiness evidence |
| `evidence_only` | Validate mapped RU evidence without direct execution | Missing approved evidence reference |

## 5.9 Multi-RU Execution

The framework executes RUs only from the human-authored `rp_ru_mapping.yaml`.

Rules:

- Build the RU execution plan from each RU entry's `dependencies` list.
- Treat an empty `dependencies` list as independent.
- Stop downstream execution when a required upstream RU validation fails.
- Continue independent RUs when they do not depend on the failed RU.
- Record each RU version reference, adapter mode, environment reference, and evidence responsibility.
- Never add, remove, or reorder RUs based on agent inference.
- Treat `dependency_order` as a display hint only; it is not sufficient to express execution dependencies.

## 5.10 Adapter and Provider Contract

Executable adapters and providers must define validated contracts before F007 execution. A contract may be declared per RU, per RP, or supplied by a reusable adapter/provider default.

Minimum executable adapter fields:

- Command and working directory.
- Timeout seconds.
- Resolved input references.
- Actual output references under `evidence/runs/<run_id>/actual/`.
- Stdout and stderr log paths under `evidence/runs/<run_id>/logs/`.
- Success exit codes.
- Environment variables required by the adapter.

Minimum provider contract rules:

- A provider contract must declare provider type and supported action names.
- A provider contract must reference inputs, queries, payloads, secrets, or environment resources by reference, not inline sensitive values.
- Fixture providers that mutate state must declare cleanup strategy.
- Oracle providers must declare oracle type, truth source, and comparison or decision rule.
- Observation providers must declare source and collection rule.

Runtime rules:

- Non-success exit codes fail the test case and preserve exit code, stdout, stderr, and resolved inputs.
- Timeout fails the test case and still triggers fixture cleanup.
- Adapter execution must not deploy RU code in M1.
- Package-type behavior belongs in configurable adapters/providers; DSL, orchestration, evidence format, and assertion lifecycle stay in framework core.

## 5.11 Failure Handling

| Failure | Handling |
|---|---|
| Missing Product Repo folder | Report readiness gap and owner action; do not continue to generation. |
| Missing RP artifact | Block generation and execution for that RP. |
| Missing RP/RU mapping field | Block execution and report the exact field. |
| Unsupported DSL version | Block execution and report supported versions and migration action. |
| Missing required DSL field | Block generation approval or execution and report the field path. |
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
- Destructive operations require explicit approval in the RP test case or adapter policy.
- Local commands should be bounded and avoid memory-heavy execution.

## 5.13 Observability and Evidence

Each run writes:

- `run.yaml`: run status, timestamps, RP ID, AC IDs, test case IDs, RU refs, execution mode.
- `logs/`: adapter logs and framework validation logs.
- `actual/`: captured actual outputs or evidence references.
- `assertions.yaml`: assertion results, oracle refs, actual refs, decision rule, diff summary.
- `observations.yaml`: observation collection results such as log, metric, event, or trace checks.
- `postconditions.yaml`: final-state validation results after execution and cleanup.
- `cleanup.yaml`: fixture cleanup status and cleanup evidence.
- Coverage report, traceability report, evidence index, and failure summary.

M1 alerting is CI/report based. A failed command exits non-zero and writes evidence for review.

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
| Framework decides RP membership | Rejected | RP/RU membership is owner-authored business/release scope. |

The architecture should be revisited when multiple RP types require shared service workflows, concurrent teams need a central index, or evidence volume becomes too large for repo-based storage.

## 5.15 AC Coverage Matrix

| AC | Architecture Support | Implementation Module |
|---|---|---|
| AC-001 | Product Repo bootstrap CLI, machine-readable readiness report, and readiness agent explanation | `productrepo`, `cli`, readiness agent skill |
| AC-002 | RP skeleton and artifact completeness check | `discovery`, `schema` |
| AC-003 | AC intake preserving owner-authored truth | `readiness`, `schema` |
| AC-004 | RP/RU mapping validation and unsafe execution block | `mapping`, `environment` |
| AC-005 | Agent DSL test drafting only from ready inputs and no silent overwrite | `readiness`, `testcase` |
| AC-006 | Expected result source references and approval gate | `expectedresult` |
| AC-007 | RP DSL test execution with inputs, fixtures, adapters, assertions, evidence | `execution`, `binding`, `provider`, `fixture`, `adapter`, `assertion`, `evidence` |
| AC-008 | Coverage, traceability, failures, and approved exclusions | `report`, `evidence` |

## 5.16 Implementation Readiness Gate

Architecture gate result: PASS for design readiness and staged implementation readiness.

Ready to implement now:

- F001 Product Repo Bootstrap CLI and Readiness Agent Skill.
- F002 Release Package Creation Guide and Completeness Check.
- F004 structural RP/RU mapping validator, using placeholder pilot data or fixtures.

Ready after pilot RP artifacts exist:

- F003 RP AC intake against real owner-authored AC.
- F005/F006 agent draft generation and expected-result drafting.
- F007 execution path for the data pipeline pilot adapter, using the adapter/provider contract in the artifact contract.
- F008 final coverage and evidence package using real run evidence.

Implementation must start with F001/F002/F004 foundation tasks, then validate the pilot RP before enabling generation and execution.

## 5.17 Open Decisions

Blocking for full F003-F008 implementation:

- Pilot RP ID and target release.
- Owner-authored `rp_feature_spec.md`.
- Owner-authored `acceptance_criteria.md`.
- Owner-authored `rp_ru_mapping.yaml`.
- RU repo paths or version references.
- Fixture source and expected-result approval owner.

Non-blocking for F001/F002/F004:

- Final package-type plugin SDK shape.
- Dashboard or central service design.
- Cross-package orchestration.
