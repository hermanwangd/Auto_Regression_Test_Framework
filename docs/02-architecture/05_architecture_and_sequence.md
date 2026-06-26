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
| Test case DSL | Package-neutral DSL | Different Products and RPs express regression tests through one artifact model. |
| Adapter model | Core framework plus package adapters | Execution process is generic; package behavior stays adapter-specific. |

## 5.3 Component Architecture

```text
Product Repo Artifact Store
        |
        v
CLI Orchestrator
        |
        +--> Product Repo Bootstrap
        +--> Readiness Agent Skill
        +--> RP Discovery and Artifact Validator
        +--> RP/RU Mapping Validator
        +--> AC Intake and Readiness Classifier
        +--> Package-Neutral Test Case DSL
        +--> Test Case Lifecycle Manager
        +--> Expected Result Manager
        +--> Execution Environment Resolver
        +--> Package Input and Binding Resolver
        +--> Fixture Lifecycle Manager
        +--> Adapter Runtime
        +--> Assertion Engine
        +--> Evidence Writer
        +--> Coverage and Traceability Reporter
```

| Component | Responsibility | Primary AC |
|---|---|---|
| Product Repo Artifact Store | Own versioned docs, RP records, tests, expected results, and evidence | AC-001, AC-002 |
| Product Repo Bootstrap | Create/check lifecycle folders and starter RP locations through deterministic CLI behavior | AC-001 |
| Readiness Agent Skill | Explain readiness report, summarize gaps, and produce owner-actionable next steps without inventing RP truth | AC-001 |
| RP Discovery and Artifact Validator | Find RP records and validate required files and identifiers | AC-002 |
| RP/RU Mapping Validator | Validate owner-authored RU repo mapping and block unsafe execution | AC-004 |
| AC Intake and Readiness Classifier | Preserve AC truth, classify readiness, and report gaps | AC-003, AC-005 |
| Package-Neutral Test Case DSL | Describe RP behavior validation independent of package type | AC-005, AC-007 |
| Test Case Lifecycle Manager | Detect existing tests, write drafts, protect approved tests | AC-005, AC-007 |
| Expected Result Manager | Draft, validate, and enforce approval status for expected results | AC-006 |
| Execution Environment Resolver | Select local, CI, SIT, or evidence-only path from mapping | AC-004, AC-007 |
| Package Input and Binding Resolver | Resolve fixture, input, expected-result, and runtime references | AC-007 |
| Fixture Lifecycle Manager | Set up and clean up deterministic test fixtures | AC-007 |
| Adapter Runtime | Execute package-specific behavior through declared adapter mode | AC-007 |
| Assertion Engine | Compare actual results with approved expected results | AC-007 |
| Evidence Writer | Persist run metadata, logs, actuals, assertions, and cleanup evidence | AC-007, AC-008 |
| Coverage and Traceability Reporter | Calculate RP AC coverage and produce review-ready evidence | AC-008 |

## 5.4 Module Boundaries

The implementation should use a small internal module layout. Names may be adjusted to match the final language/runtime, but these boundaries are required.

```text
src/regress/
  cli.py
  product_repo.py
  rp_discovery.py
  schemas.py
  readiness.py
  mapping.py
  test_cases.py
  expected_results.py
  environment.py
  bindings.py
  fixtures.py
  execution.py
  adapters/
    base.py
    data_pipeline.py
  assertions.py
  evidence.py
  reports.py
```

Boundary rules:

- `cli.py` orchestrates use cases only; it does not embed validation logic.
- `schemas.py` owns typed parsing and schema validation for RP artifacts.
- `readiness.py` reports gaps and owner actions; it does not create business truth.
- F001 readiness agent skill consumes readiness reports; it does not mutate repo artifacts directly.
- `mapping.py` consumes `rp_ru_mapping.yaml`; it never infers RP membership.
- The test case DSL describes validation intent, inputs, fixtures, assertions, and evidence requirements; it does not contain package-specific execution logic.
- `test_cases.py` may create draft artifacts but must not overwrite approved tests.
- `expected_results.py` enforces source references and approval status.
- `environment.py` blocks SIT runs unless deployment and readiness evidence exist.
- `adapters/` is the only package-type-specific execution boundary.
- `evidence.py` and `reports.py` write durable evidence under the RP release record.

## 5.5 CLI Boundary

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

## 5.6 Data Ownership and Storage

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

## 5.7 Execution Flow

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

## 5.8 Multi-RU Execution

The framework executes RUs only from the human-authored `rp_ru_mapping.yaml`.

Rules:

- Build the RU execution plan from each RU entry's `dependencies` list.
- Treat an empty `dependencies` list as independent.
- Stop downstream execution when a required upstream RU validation fails.
- Continue independent RUs when they do not depend on the failed RU.
- Record each RU version reference, adapter mode, environment reference, and evidence responsibility.
- Never add, remove, or reorder RUs based on agent inference.
- Treat `dependency_order` as a display hint only; it is not sufficient to express execution dependencies.

## 5.9 Adapter Command Contract

Executable adapters must define a command contract before F007 execution. The contract may be declared per RU or supplied by the adapter default.

Minimum fields:

- Command and working directory.
- Timeout seconds.
- Resolved input references.
- Actual output references under `evidence/runs/<run_id>/actual/`.
- Stdout and stderr log paths under `evidence/runs/<run_id>/logs/`.
- Success exit codes.
- Environment variables required by the adapter.

Runtime rules:

- Non-success exit codes fail the test case and preserve exit code, stdout, stderr, and resolved inputs.
- Timeout fails the test case and still triggers fixture cleanup.
- Adapter execution must not deploy RU code in M1.
- Package-type behavior belongs in adapters; DSL, orchestration, evidence, and assertion behavior stay in framework core.

## 5.10 Failure Handling

| Failure | Handling |
|---|---|
| Missing Product Repo folder | Report readiness gap and owner action; do not continue to generation. |
| Missing RP artifact | Block generation and execution for that RP. |
| Missing RP/RU mapping field | Block execution and report the exact field. |
| Ambiguous AC | Mark `not_ready_for_generation`; do not draft executable tests. |
| Existing approved test | Create update proposal or new draft revision; do not overwrite. |
| Expected result not approved | Block normal regression execution. |
| Expected result blocked | Report blocked reason and required owner action. |
| Manual-only or waived AC lacks approval record | Keep AC in denominator or block review-ready coverage report. |
| SIT readiness missing | Block `sit_deployed` run before adapter execution. |
| Adapter failure | Capture command, inputs, outputs, logs, and failure reason. |
| Fixture cleanup failure | Record cleanup failure and mark run evidence incomplete. |

## 5.11 Security and Data Safety

- Production data is disallowed unless masked and approved.
- Secrets must be referenced through environment variables or CI secret stores, never committed.
- Evidence must not include raw secret values.
- Test fixtures should be small and reviewable, or generated by a documented command.
- Destructive operations require explicit approval in the RP test case or adapter policy.
- Local commands should be bounded and avoid memory-heavy execution.

## 5.12 Observability and Evidence

Each run writes:

- `run.yaml`: run status, timestamps, RP ID, AC IDs, test case IDs, RU refs, execution mode.
- `logs/`: adapter logs and framework validation logs.
- `actual/`: captured actual outputs or evidence references.
- `assertions.yaml`: assertion results, expected refs, actual refs, diff summary.
- `cleanup.yaml`: fixture cleanup status and cleanup evidence.
- Coverage report, traceability report, evidence index, and failure summary.

M1 alerting is CI/report based. A failed command exits non-zero and writes evidence for review.

Canonical run evidence path:

```text
docs/08-release/release-packages/<rp_id>/evidence/runs/<run_id>/
```

## 5.13 Architecture Alternatives

| Alternative | Decision | Reason |
|---|---|---|
| Persistent web service | Rejected for M1 | Adds deployment and operations burden before pilot value is proven. |
| Database-backed artifact store | Rejected for M1 | Git-versioned artifacts are enough and easier to review. |
| Always require SIT | Rejected | Some RPs can be validated with local or CI fixtures; SIT is required only when the boundary needs deployed behavior. |
| Generate tests on every run | Rejected | Breaks reviewability and durable test case management. |
| Framework decides RP membership | Rejected | RP/RU membership is owner-authored business/release scope. |

The architecture should be revisited when multiple RP types require shared service workflows, concurrent teams need a central index, or evidence volume becomes too large for repo-based storage.

## 5.14 AC Coverage Matrix

| AC | Architecture Support | Implementation Module |
|---|---|---|
| AC-001 | Product Repo bootstrap CLI, machine-readable readiness report, and readiness agent explanation | `product_repo.py`, `cli.py`, readiness agent skill |
| AC-002 | RP skeleton and artifact completeness check | `rp_discovery.py`, `schemas.py` |
| AC-003 | AC intake preserving owner-authored truth | `readiness.py`, `schemas.py` |
| AC-004 | RP/RU mapping validation and unsafe execution block | `mapping.py`, `environment.py` |
| AC-005 | Agent DSL test drafting only from ready inputs and no silent overwrite | `readiness.py`, `test_cases.py` |
| AC-006 | Expected result source references and approval gate | `expected_results.py` |
| AC-007 | RP DSL test execution with inputs, fixtures, adapters, assertions, evidence | `execution.py`, `bindings.py`, `fixtures.py`, `adapters/`, `assertions.py`, `evidence.py` |
| AC-008 | Coverage, traceability, failures, and approved exclusions | `reports.py`, `evidence.py` |

## 5.15 Implementation Readiness Gate

Architecture gate result: PASS for design readiness and staged implementation readiness.

Ready to implement now:

- F001 Product Repo Bootstrap CLI and Readiness Agent Skill.
- F002 Release Package Creation Guide and Completeness Check.
- F004 structural RP/RU mapping validator, using placeholder pilot data or fixtures.

Ready after pilot RP artifacts exist:

- F003 RP AC intake against real owner-authored AC.
- F005/F006 agent draft generation and expected-result drafting.
- F007 execution path for the data pipeline pilot adapter, using the adapter command contract in the artifact contract.
- F008 final coverage and evidence package using real run evidence.

Implementation must start with F001/F002/F004 foundation tasks, then validate the pilot RP before enabling generation and execution.

## 5.16 Open Decisions

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
