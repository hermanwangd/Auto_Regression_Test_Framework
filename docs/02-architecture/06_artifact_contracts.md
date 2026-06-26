# 06. Artifact Contracts

These contracts define the minimum artifacts needed to design or implement the M1 Release Package Regression Framework. They are specification contracts, not final runtime API design.

## 6.1 Product Repo Layout

Release Package records shall be grouped by RP ID:

```text
docs/08-release/release-packages/<rp_id>/
  package.yaml
  rp_feature_spec.md
  rp_ru_mapping.yaml
  acceptance_criteria.md
  tests/
    draft/
    approved/
    retired/
  expected-results/
    draft/
    approved/
    retired/
  traceability.md
  evidence_index.md
  evidence/
    readiness/
    generation/
    runs/
    review/
```

Other lifecycle folders may reference these RP artifacts, but the RP folder is the package-release source of truth.

## 6.2 Identifier Rules

| ID | Pattern | Example |
|---|---|---|
| Product ID | `PROD-<slug>` | `PROD-auto-regression` |
| RP ID | `RP-<product>-<release>-<slug>` | `RP-AR-M1-data-pipeline` |
| RU ID | `RU-<slug>` | `RU-transform-job` |
| AC ID | `<rp_id>-AC-###` | `RP-AR-M1-data-pipeline-AC-001` |
| Test Case ID | `<rp_id>-TC-###` | `RP-AR-M1-data-pipeline-TC-001` |
| Evidence ID | `<rp_id>-EVD-###` | `RP-AR-M1-data-pipeline-EVD-001` |

Stable IDs are required for generation, execution, coverage, and evidence traceability.

## 6.3 `package.yaml`

```yaml
product_id: PROD-auto-regression
rp_id: RP-AR-M1-data-pipeline
name: M1 Data Pipeline Regression Pilot
owner: product_developer
target_release: M1
package_type: data_pipeline
lifecycle_status: draft
default_execution_mode: ci_ephemeral
scope:
  includes:
    - bounded data pipeline release package pilot
  excludes:
    - cross-package orchestration
linked_product_context:
  - docs/00-intake-scope/01_project_scope_capability_baseline.md
artifact_paths:
  feature_spec: rp_feature_spec.md
  acceptance_criteria: acceptance_criteria.md
  ru_mapping: rp_ru_mapping.yaml
  tests: tests/
  expected_results: expected-results/
  traceability: traceability.md
  evidence_index: evidence_index.md
```

## 6.4 `rp_ru_mapping.yaml`

```yaml
rp_id: RP-AR-M1-data-pipeline
release_units:
  - ru_id: RU-transform-job
    repo: /path/to/release-unit-repo
    unit_type: data_pipeline
    owner: product_developer
    version_ref: main
    validation_boundary: execute_pipeline_with_fixture
    execution_mode: ci_ephemeral
    deployment_required: false
    environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
    adapter: data_pipeline_cli
    adapter_contract:
      command: python -m pipeline.run
      working_directory: ${repo}
      timeout_seconds: 900
      inputs:
        input_ref: ${test_case.package_inputs.input_ref}
      outputs:
        actual_output_ref: actual/orders_normalized.csv
      logs:
        stdout: logs/stdout.log
        stderr: logs/stderr.log
      success_exit_codes:
        - 0
    evidence_responsibility:
      - execution_log
      - output_dataset
    dependencies: []
    required_for:
      - RP-AR-M1-data-pipeline-AC-001
```

The framework consumes this mapping. It must not decide RP membership.

Allowed `execution_mode` values are `local_fixture`, `ci_ephemeral`, `sit_deployed`, and `evidence_only`.

Each RU entry must declare dependency semantics with `dependencies`. Use an empty list for independent RUs. A dependency entry must include `ru_id` and `required`, and may include `consumes` to identify the upstream output used by this RU.

```yaml
dependencies:
  - ru_id: RU-transform-job
    required: true
    consumes:
      - normalized_orders
```

Execution order is derived from this dependency graph. `dependency_order` may be used only as a display hint or migration aid; it is not sufficient for M1 execution planning.

When `execution_mode` is `sit_deployed`, the mapping must include deployment readiness evidence:

```yaml
deployment:
  required: true
  environment: SIT
  deployment_ref: cd://release/RP-AR-M1-data-pipeline/build-123
  readiness_check: https://sit.example.internal/health
  deployed_version_ref: build-123
```

## 6.5 `acceptance_criteria.md`

Each RP AC shall include:

```yaml
ac_id: RP-AR-M1-data-pipeline-AC-001
rp_id: RP-AR-M1-data-pipeline
title: Valid input produces approved output
owner: product_owner
classification: automatable
input: fixture/input/orders.csv
behavior: transform valid orders using the RP feature rules
expected_output: expected/output/orders_normalized.csv
allowed_side_effects:
  - execution_log
pass_fail_rule: actual output matches approved expected output
status: ready_for_generation
exclusion_record: null
```

Allowed classifications are `automatable`, `manual_only`, `partial`, `waived`, and `not_ready_for_generation`.

Manual-only and waived AC must include an approved exclusion record before they are removed from the coverage denominator:

```yaml
exclusion_record:
  exclusion_id: RP-AR-M1-data-pipeline-EXC-001
  ac_id: RP-AR-M1-data-pipeline-AC-009
  exclusion_type: manual_only
  reason: requires exploratory business review
  approved_by: release_owner
  approved_at: 2026-06-26T10:00:00+08:00
  approval_ref: docs/10-change-control/13_issue_template.md#EXC-001
```

## 6.6 Test Case Storage Lifecycle

Test case generation and test case execution are separate actions. Execution shall read checked-in test cases and shall not regenerate test cases by default.

| Folder | Meaning |
|---|---|
| `tests/draft/` | Agent-generated skeletons or executable drafts awaiting review |
| `tests/approved/` | Reviewed test cases eligible for normal regression execution |
| `tests/retired/` | Superseded test cases retained for traceability |

An approved test case may be replaced only by an explicit update proposal that records the source change and replacement relationship.

## 6.7 Package-Neutral Test Case DSL

The test case artifact is a package-neutral DSL. It describes the RP AC being validated, the execution target, package inputs, fixture lifecycle, logical steps, assertions, and evidence expectations. It must not embed package-specific execution code; package-specific behavior is resolved through the configured adapter.

```yaml
test_case_id: RP-AR-M1-data-pipeline-TC-001
rp_id: RP-AR-M1-data-pipeline
ac_id: RP-AR-M1-data-pipeline-AC-001
artifact_status: approved_for_regression
revision: 1
source_refs:
  rp_feature_spec: rp_feature_spec.md
  acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
  rp_ru_mapping: rp_ru_mapping.yaml#RU-transform-job
source_fingerprint: "sha256:<hash-of-source-contract>"
replaces: null
execution_target:
  ru_id: RU-transform-job
  adapter: data_pipeline_cli
  execution_mode: ci_ephemeral
  environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
package_inputs:
  input_ref: fixture/input/orders.csv
  expected_result_ref: expected-results/approved/RP-AR-M1-data-pipeline-AC-001.yaml
fixture:
  setup:
    - create_temp_workspace
  cleanup:
    - remove_temp_workspace
steps:
  - id: run_pipeline
    action: execute
    input: ${package_inputs.input_ref}
assertions:
  - type: file_diff
    actual: ${steps.run_pipeline.outputs.normalized_orders}
    expected: ${expected.output_ref}
evidence_required:
  - execution_log
  - assertion_results
  - actual_output
```

Allowed test case statuses are `draft_test_skeleton`, `draft_executable_test_case`, `approved_for_regression`, `needs_update`, and `retired`.

DSL responsibility split:

| DSL Section | Owns | Adapter Owns |
|---|---|---|
| `execution_target` | RU ID, adapter name, execution mode, environment ref | How the adapter invokes the RU |
| `package_inputs` | Logical input and expected-result refs | Resolving adapter-specific argument names |
| `fixture` | Setup and cleanup intent | Concrete fixture command implementation when adapter-specific |
| `steps` | Logical validation actions | Package-specific command execution |
| `assertions` | Assertion type and expected/actual refs | Producing actual outputs |
| `evidence_required` | Required evidence categories | Concrete log/output file production |

## 6.8 Expected Result Artifact

```yaml
expected_result_id: RP-AR-M1-data-pipeline-ER-001
rp_id: RP-AR-M1-data-pipeline
ac_id: RP-AR-M1-data-pipeline-AC-001
status: draft
source_refs:
  - rp_feature_spec.md
  - acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
input_refs:
  - fixture/input/orders.csv
expected_outputs:
  output_ref: expected/output/orders_normalized.csv
assumptions: []
unresolved_gaps: []
approved_by: null
approved_at: null
approval_ref: null
blocked_reason: null
```

Allowed expected-result statuses are `draft`, `blocked`, and `approved_for_regression`.

Rules:

- `draft` artifacts must include source references and unresolved gaps, if any.
- `blocked` artifacts must include `blocked_reason`.
- `approved_for_regression` artifacts must include `approved_by`, `approved_at`, and `approval_ref`.
- Only `approved_for_regression` expected results may be used as regression truth unless an explicit execution policy allows otherwise.

## 6.9 Adapter Command Contract

Each executable adapter must expose this minimum contract through `rp_ru_mapping.yaml` or adapter defaults:

```yaml
adapter_contract:
  command: python -m pipeline.run
  working_directory: ${repo}
  timeout_seconds: 900
  env:
    PIPELINE_MODE: regression
  inputs:
    input_ref: ${test_case.package_inputs.input_ref}
  outputs:
    actual_output_ref: actual/orders_normalized.csv
  logs:
    stdout: logs/stdout.log
    stderr: logs/stderr.log
  success_exit_codes:
    - 0
```

Adapter runtime rules:

- The framework supplies resolved input paths and run workspace paths.
- The adapter writes actual outputs under the run evidence directory.
- Non-success exit codes fail the test case and must preserve stdout, stderr, exit code, and timeout state.
- Timeouts fail the test case and must trigger fixture cleanup.
- Adapters must not perform deployment in M1.

## 6.10 Execution Evidence

```yaml
run_id: RUN-20260626-001
rp_id: RP-AR-M1-data-pipeline
test_case_id: RP-AR-M1-data-pipeline-TC-001
ac_id: RP-AR-M1-data-pipeline-AC-001
status: passed
execution_mode: ci_ephemeral
environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
deployment_refs: []
started_at: 2026-06-26T10:00:00+08:00
finished_at: 2026-06-26T10:01:00+08:00
actual_results:
  output_ref: evidence/runs/RUN-20260626-001/actual/orders_normalized.csv
assertion_results:
  - assertion: file_diff
    status: passed
evidence_refs:
  - evidence/runs/RUN-20260626-001/logs/stdout.log
  - evidence/runs/RUN-20260626-001/assertions.yaml
```

Execution evidence is stored under:

```text
docs/08-release/release-packages/<rp_id>/evidence/runs/<run_id>/
```

## 6.11 Readiness Report

Readiness reports shall include:

```yaml
rp_id: RP-AR-M1-data-pipeline
status: blocked
checked_at: 2026-06-26T10:00:00+08:00
checks:
  - check_id: RP_ARTIFACTS_PRESENT
    status: failed
    missing:
      - rp_ru_mapping.yaml
    owner_action: add human-authored RP/RU mapping
next_required_step: complete required RP artifacts before generation
```
