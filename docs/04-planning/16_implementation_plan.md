# 16. Implementation Plan

Status: Implementation-Ready Draft for M1 staged delivery

This plan implements the Product/RP/RU baseline without changing product scope or authoring RP acceptance criteria. It starts with the framework foundation, then enables generation, execution, and evidence once pilot RP artifacts exist.

## Entry Criteria

- Product, RP, and RU responsibilities are accepted.
- RP-level AC are the release coverage denominator.
- Minimum RP artifacts are defined: `package.yaml`, `rp_feature_spec.md`, `rp_ru_mapping.yaml`, `acceptance_criteria.md`, `tests/`, `expected-results/`, `traceability.md`, and `evidence_index.md`.
- Architecture design defines module boundaries, CLI commands, storage paths, execution modes, failure handling, and AC coverage.

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

Pilot RP owner must supply RP ID, package type, target release, RU repos, version references, validation boundaries, execution modes, deployment requirements, environment references, fixture source, expected-result approval owner, adapter mode, dependency graph, and adapter command contract.

## Task Backlog

### T001 - Product Repo Bootstrap CLI and Readiness Agent Skill

Related feature: F001
Acceptance: AC-001
Modules: `src/regress/cli.py`, `src/regress/product_repo.py`, readiness agent skill

Implement `regress init-product-repo --root <path>` to create the agreed lifecycle folders and starter locations. Implement `regress check-readiness --root <path> --format yaml|json` to emit a machine-readable readiness report. Provide a readiness agent skill that reads the report and explains status, missing items, owner actions, and next steps. The CLI must be idempotent and must not overwrite existing content. The agent skill must not mutate repo artifacts or invent RP scope, RP AC, or RP/RU membership.

Verification:

```bash
regress init-product-repo --root <tmp-product-repo>
regress check-readiness --root <tmp-product-repo> --format yaml
agent product-repo-readiness --report <tmp-product-repo>/docs/08-release/release-packages/<rp-id>/evidence/readiness/readiness.yaml
```

Done when missing folders are created or reported, readiness output includes pass/fail status, missing items, owner action, and next required step, and the agent skill translates the readiness report into owner-actionable guidance.

### T002 - RP Skeleton and Completeness Check

Related feature: F002
Acceptance: AC-002
Modules: `src/regress/cli.py`, `src/regress/rp_discovery.py`, `src/regress/schemas.py`

Implement `regress init-rp` and `regress check-rp` for the RP folder contract under `docs/08-release/release-packages/<rp_id>/`.

Verification:

```bash
regress init-rp --root <product-repo> --rp-id RP-AR-M1-data-pipeline --package-type data_pipeline
regress check-rp --root <product-repo> --rp-id RP-AR-M1-data-pipeline
```

Done when required RP files and folders are present or reported as completeness gaps with owner action.

### T003 - Artifact Schema Parser

Related features: F002, F003, F004
Acceptance: AC-002, AC-003, AC-004
Modules: `src/regress/schemas.py`

Implement typed parsers for `package.yaml`, `rp_ru_mapping.yaml`, AC entries, test cases, expected results, and evidence records. Start with YAML/Markdown front matter or embedded YAML blocks supported by the artifact contracts.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id> --strict-schema
```

Done when schema errors identify file path, field path, severity, and owner action.

### T004 - RP/RU Mapping Validator

Related feature: F004
Acceptance: AC-004
Modules: `src/regress/mapping.py`, `src/regress/environment.py`

Validate that each owner-authored RU entry declares repo, owner, unit type, version reference, validation boundary, execution mode, deployment requirement, environment reference, adapter or adapter mode, evidence responsibility, dependencies, and adapter command contract when execution is required.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id>
```

Done when missing mapping fields block execution, dependency graph errors are reported, and the report does not infer RP membership.

### T005 - AC Intake and Readiness Classifier

Related features: F003, F005
Acceptance: AC-003, AC-005
Modules: `src/regress/readiness.py`, `src/regress/schemas.py`

Read owner-authored RP AC, preserve stable AC IDs, classify each AC as `automatable`, `manual_only`, `partial`, `waived`, or `not_ready_for_generation`, and identify whether inputs, actions, expected outputs, side effects, and pass/fail rules are explicit.

Verification:

```bash
regress check-rp --root <product-repo> --rp-id <rp-id> --include-ac-readiness
```

Done when ambiguous AC are blocked from executable test drafting and never rewritten by the framework.

### T006 - Test Case Lifecycle Manager

Related feature: F005
Acceptance: AC-005
Modules: `src/regress/test_cases.py`

Implement draft package-neutral DSL test skeleton and draft executable DSL test artifact writing under `tests/draft/`. Detect existing `tests/approved/` artifacts for the same RP AC and create update proposals instead of overwriting.

Verification:

```bash
regress generate-tests --root <product-repo> --rp-id <rp-id> --mode draft
```

Done when checked-in approved DSL tests are protected and generated drafts include source refs, source fingerprint, revision, and status.

### T007 - Expected Result Manager

Related feature: F006
Acceptance: AC-006
Modules: `src/regress/expected_results.py`

Draft expected-result artifacts from explicit RP AC, RP feature spec, package inputs, and source context. Enforce statuses `draft`, `blocked`, and `approved_for_regression`.

Verification:

```bash
regress draft-expected-results --root <product-repo> --rp-id <rp-id>
regress check-rp --root <product-repo> --rp-id <rp-id> --include-expected-results
```

Done when only approved expected results are eligible as regression truth.

### T008 - Execution Environment Resolver

Related features: F004, F007
Acceptance: AC-004, AC-007
Modules: `src/regress/environment.py`

Resolve `local_fixture`, `ci_ephemeral`, `sit_deployed`, and `evidence_only` execution modes from `rp_ru_mapping.yaml`. Block SIT execution unless deployment and environment readiness evidence exist.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env sit_deployed
```

Done when the command blocks before adapter execution if SIT readiness evidence is missing.

### T009 - Package Input and Binding Resolver

Related feature: F007
Acceptance: AC-007
Modules: `src/regress/bindings.py`

Resolve input refs, expected-result refs, runtime paths, environment refs, and step output placeholders from approved test cases.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --dry-run
```

Done when unresolved bindings fail fast with file path, test case ID, AC ID, and owner action.

### T010 - Fixture Lifecycle Manager

Related feature: F007
Acceptance: AC-007
Modules: `src/regress/fixtures.py`

Implement fixture setup and cleanup lifecycle for local and CI runs. Record cleanup evidence even when execution fails.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --env local_fixture
```

Done when setup, cleanup, and cleanup failure state are written to run evidence.

### T011 - Adapter Runtime and Data Pipeline Pilot Adapter

Related feature: F007
Acceptance: AC-007
Modules: `src/regress/execution.py`, `src/regress/adapters/base.py`, `src/regress/adapters/data_pipeline.py`

Implement the adapter interface and the first data pipeline adapter. The core executor and test case DSL stay package-type-neutral; the adapter owns package-specific command execution and actual-result capture using the declared adapter command contract.

Verification:

```bash
regress run --root <product-repo> --rp-id RP-AR-M1-data-pipeline --env ci_ephemeral
```

Done when a pilot adapter can execute or validate one approved test, preserve stdout/stderr/exit code/timeout state, and emit actual outputs under the run evidence directory.

### T012 - Assertion Engine

Related feature: F007
Acceptance: AC-007
Modules: `src/regress/assertions.py`

Implement M1 assertion types required by the pilot, starting with file equality/diff, structured value equality, status checks, and evidence-reference checks.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id> --test-case <tc-id>
```

Done when failures include expected ref, actual ref, diff summary, and test case trace.

### T013 - Evidence Writer

Related features: F007, F008
Acceptance: AC-007, AC-008
Modules: `src/regress/evidence.py`

Write `evidence/runs/<run_id>/run.yaml`, logs, actual outputs, assertion results, cleanup evidence, and failure details. Evidence must include RP ID, AC ID, test case ID, run ID, RU refs, execution mode, and environment ref.

Verification:

```bash
regress run --root <product-repo> --rp-id <rp-id>
```

Done when every execution path produces durable evidence, including failed and blocked runs.

### T014 - Coverage and Traceability Reporter

Related feature: F008
Acceptance: AC-008
Modules: `src/regress/reports.py`, `src/regress/evidence.py`

Calculate coverage as covered automatable RP-level AC divided by total automatable RP-level AC. Link generated tests and evidence to RP ID, AC ID, test case ID, and run ID. Exclude manual-only or waived AC only with approval records.

Verification:

```bash
regress report --root <product-repo> --rp-id <rp-id> --run-id <run-id>
```

Done when coverage, traceability, evidence index, failure summary, and release review summary are review-ready.

### T015 - Pilot RP Validation Harness

Related features: F001-F008
Acceptance: AC-001 through AC-008
Modules: all M1 modules

Run the full workflow against the data pipeline pilot RP after owner-provided RP artifacts exist.

Verification:

```bash
regress check-readiness --root <product-repo>
regress check-rp --root <product-repo> --rp-id RP-AR-M1-data-pipeline
regress generate-tests --root <product-repo> --rp-id RP-AR-M1-data-pipeline --mode draft
regress run --root <product-repo> --rp-id RP-AR-M1-data-pipeline --env ci_ephemeral
regress report --root <product-repo> --rp-id RP-AR-M1-data-pipeline --run-id <run-id>
```

Done when the pilot RP evidence package shows greater than 80% coverage for automatable RP AC or reports explicit approved exclusions.

## Dependency Order

```text
T001 -> T002 -> T003 -> T004
                  |
                  +-> T005 -> T006 -> T007
                  |
                  +-> T008 -> T009 -> T010 -> T011 -> T012 -> T013 -> T014
                                                                  |
                                                                  v
                                                                 T015
```

Parallelizable after T003:

- T004 mapping validation.
- T005 AC readiness.
- T007 expected-result manager.
- T008 environment resolver.
- T014 report formatting skeleton.

## Implementation Gates

Gate 1 - Foundation ready:

- T001, T002, T003, and T004 complete.
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

- T014 and T015 complete.
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

## Completion Criteria for M1

- Product developer can initialize/check the Product Repo and RP.
- Pilot RP artifacts are complete and human-authored where required.
- Agent drafts tests only from ready AC and execution context.
- Approved tests execute without regeneration by default.
- Evidence records inputs, outputs, logs, assertions, cleanup, and failures.
- Coverage is greater than 80% of automatable RP-level AC or exclusions are approved.
