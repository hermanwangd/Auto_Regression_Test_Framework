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
    batches/
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

Examples in this document use a file/batch-style RP ID for readability. They illustrate artifact shape and do not define the selected heterogeneous M1 pilot.

## 6.3 `package.yaml`

```yaml
product_id: PROD-auto-regression
rp_id: RP-AR-M1-data-pipeline
name: M1 File Batch Regression Example
owner: product_developer
target_release: M1
package_type: data_pipeline
lifecycle_status: draft
default_execution_mode: ci_ephemeral
scope:
  includes:
    - bounded file/batch release package example
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
    adapter: spring_boot_cli
    provider_contracts:
      adapters:
        spring_boot_cli:
          provider_family: file_batch
          provider_type: shell
          command: java -jar ${repo}/target/release-unit.jar --spring.profiles.active=regression
          working_directory: ${repo}
          timeout_seconds: 900
          inputs:
            orders_seed: ${test_case.setup.fixtures.orders_seed}
          outputs:
            actual_output_ref: actual/orders_normalized.csv
          logs:
            stdout: logs/stdout.log
            stderr: logs/stderr.log
          success_exit_codes:
            - 0
      bindings:
        db_seed:
          provider_family: file_batch
          provider_type: file_fixture
          materialize_as: input_file
      fixtures: {}
      expected_results: {}
      verify: {}
      evidence: {}
    evidence_responsibility:
      - execution_log
      - output_dataset
    dependencies: []
    required_for:
      - RP-AR-M1-data-pipeline-AC-001
```

The framework consumes this mapping. It must not decide RP membership.

`provider_contracts` is the canonical contract container. Executable provider contracts must declare `provider_family` and `provider_type` so the provider capability registry can validate required fields, runtime support status, allowed execution modes, and evidence outputs before execution. Contracts may be supplied at reusable defaults, RP level, or RU level. Resolution order is: framework/provider default, then RP-level override, then RU-level override. The most specific declared contract wins, and unsupported or ambiguous overrides fail before execution. Dispatch uses DSL and mapping fields: `targets.<target_id>.runner` and `execute[].operation` select adapter/provider contracts; `setup.fixtures.<name>.type` selects fixture contracts; `expected_results.<name>.type` selects expected-result readers; `verify[].type` selects verify providers; `evidence.required[]` selects evidence collection requirements.

Current provider contract minimums enforced by the framework verification build:

| Provider Family / Type | Enforced Minimum Fields |
|---|---|
| `file_batch/shell` | `command`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `request_response/rest` | `endpoint_ref`, `base_url_ref`, or `service_ref`; `actions`; positive `timeout_seconds`; `outputs.actual_output_ref` |
| `messaging/local` or `messaging/mock` | `topic_ref`, `subject_ref`, `stream_ref`, or `endpoint_ref`; positive `timeout_seconds`; `outputs.actual_output_ref`; supported action; payload binding when publishing or requesting a reply; cleanup strategy and positive max count when cleaning; correlation id when required |
| `messaging/kafka` or `messaging/nats` | `bootstrap_servers_ref`, `server_ref`, or `connection_ref`; `topic_ref` or `subject_ref`; positive `timeout_seconds`; `outputs.actual_output_ref`; supported action; payload binding when publishing; payload binding for NATS request/reply; `cleanup_strategy: drain` and positive `max_count` when cleaning; correlation id when required |
| `db_fixture/jdbc` | `connection_ref`, `isolation_key`, `cleanup_strategy`, setup/cleanup SQL by `sql_ref`, verification SQL by `sql_ref` |
| `deployment_readiness/local` or `deployment_readiness/mock` | `readiness_probe`, deployment/service/target ref, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `deployment_readiness/k8s` | `readiness_probe`, `namespace_ref`, `kube_context_ref` or `connection_ref` for `kubectl` probes, `api_server_ref` or `endpoint_ref` for `api_deployment_available`, deployment/service/selector ref for readiness, `target_selector` or `pod_ref` plus positive `log_tail_lines` for `pod_logs`, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `deployment_readiness/vm` | `readiness_probe`, `host_ref` and positive `port` for TCP, `health_url_ref`/`endpoint_ref` for HTTP, or `host_ref` plus `command_ref` for `ssh_command`/`winrm_command`; optional `ssh_ref`, `winrm_ref`, `user_ref`, and positive `port`; `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `external_runner/command_runner` | approval metadata, reason, command/container ref, inputs, outputs, positive timeout, evidence map, safe evidence paths |

Examples in this document must use those fields when they describe current runtime-supported provider contracts. Native gRPC, Kafka, NATS, bounded K8s readiness, and bounded VM readiness examples are runtime-supported only within the verification boundaries stated in the architecture and validation plan.

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

## 6.7 Execution-Focused Test Case DSL v1

The test case artifact is a package-neutral execution DSL. It describes what RP behavior is validated, which targets are involved, what setup data is needed, what operation runs, what outputs are captured, which expected results are used, how verification is performed, what evidence is retained, and what runtime policy applies.

DSL v1 is intentionally not a governance workflow. It must not contain `approval_status`, `approved_by`, `approval_required`, `waiver`, `release_gate`, `risk_approval`, or release governance fields. Expected-result review and release approval remain separate artifacts and processes.

Use clear test-case language in new DSL artifacts:

| DSL Section | Purpose |
|---|---|
| `dsl_version` | Select parser and compatibility rules. |
| `test_case_id`, `status`, `revision` | Identify the durable test artifact and revision. `status` is execution lifecycle state, not approval state. |
| `traceability` | Link the test to package ID, AC ID, and source spec reference. |
| `targets` | Name each application, database, event bus, file store, batch runner, or external boundary used by the test. |
| `scenario` | Describe test type, scope, behavior, and required capabilities. |
| `setup` | Declare fixtures, seed data, mock setup, or initial state needed before execution. |
| `execute` | Declare readable operations, target, runtime inputs, and output capture. |
| `expected_results` | Reference expected files, JSON/YAML payloads, DB state snapshots, response payloads, schemas, or contracts. |
| `verify` | Declare assertions over captured outputs, state, events, or files. |
| `evidence` | Declare evidence refs that must be retained from execution or verification. |
| `runtime` | Declare bounded timeout and retry policy. |

### 6.7.0 Requirement Rules

The v1 contract separates stable top-level structure from execution-required content:

- Always required: `dsl_version`, `test_case_id`, `status`, `revision`, `traceability`, `targets`, `scenario`, `execute`, `verify`, `evidence`, and `runtime`.
- Conditionally required: `setup.fixtures` when the scenario needs precondition data, state mutation, mock setup, seed data, or cleanup.
- Conditionally required: `expected_results` when a verify item references an approved artifact, schema, contract, payload, file, DB state snapshot, or other reusable truth source. Simple deterministic expected values may be declared directly in `verify[].expected`.
- Required when referenced: `execute[].outputs`, `verify[].selector`, `verify[].target/query/event`, `verify[].options`, and fixture `cleanup_ref`.
- Prohibited in DSL: provider implementation settings, secrets, endpoint URLs, connection strings, SQL bodies, shell scripts, release gates, waivers, risk approvals, and approval workflow state.

The v1 semantic model is:

```yaml
dsl_version: v1
test_case_id: RP-FWK-SAMPLE-TC-001
status: draft_executable
revision: 1

traceability:
  package_id: RP-FWK-SAMPLE
  acceptance_criteria_id: RP-FWK-SAMPLE-AC-001
  source: acceptance_criteria.md#RP-FWK-SAMPLE-AC-001

targets:
  framework_sample_pipeline:
    type: spring_boot_application
    runner: spring_boot_cli
    environment: ci://framework-verification/RP-FWK-SAMPLE

scenario:
  type: integration
  scope: release_package
  description: Run the sample pipeline and verify normalized output.
  capabilities: [db_seed, batch_execution, file_assertion]

setup:
  fixtures:
    orders_seed:
      type: database_seed
      ref: fixtures/db/orders_seed.yaml
      cleanup_ref: fixtures/db/orders_cleanup.yaml

execute:
  - id: run_pipeline
    operation: run_batch
    target: framework_sample_pipeline
    with:
      db_seed: ${setup.fixtures.orders_seed}
    outputs:
      exit_code: ${result.exit_code}
      normalized_orders_file: ${result.files.normalized_orders}
      execution_log: ${result.logs.execution_log}

expected_results:
  normalized_orders:
    type: file
    ref: expected-results/RP-FWK-SAMPLE-ER-001.yaml

verify:
  - id: verify_exit_code
    type: equals
    actual: ${execute.run_pipeline.outputs.exit_code}
    expected: 0
  - id: verify_normalized_orders_file
    type: file_diff
    actual: ${execute.run_pipeline.outputs.normalized_orders_file}
    expected: ${expected_results.normalized_orders.ref}
    options:
      format: yaml
      normalize: true
      ignore_order: true

evidence:
  required:
    - ${execute.run_pipeline.outputs.execution_log}
    - ${execute.run_pipeline.outputs.normalized_orders_file}
    - ${verify.verify_exit_code.result}
    - ${verify.verify_normalized_orders_file.result}

runtime:
  timeout: PT10M
  retry:
    max_attempts: 0
```

### 6.7.1 Naming Rules

New DSL artifacts must use readable execution terms:

| Replace Old Term | Use v1 Term | Reason |
|---|---|---|
| `execution_target` | `targets` plus `execute[].target` | One test may involve multiple targets. |
| `target_ru_id` | `target` | Tests should refer to logical target IDs, not framework-internal RU fields. |
| `action: call_ru` | `operation: run_batch`, `call_api`, `execute_sql`, `publish_message`, `consume_message`, `run_application`, or `execute_command` | Operation names should explain what the test does. |
| `package_inputs` | `setup.fixtures` or `execute[].with` | Setup owns preconditions; execute owns runtime inputs. |
| `oracles` | `expected_results` | v1 uses expected result references and explicit `verify` rules instead of a heavy oracle framework. |
| `steps` | `execute` | The section is executable behavior, not an abstract workflow step. |
| `assertions` | `verify` | The section defines pass/fail checks. |
| `evidence_required` | `evidence.required` | Evidence should reference real execution or verification outputs. |
| `policy` | `runtime` | Runtime policy is timeout and retry in v1. |

Legacy v1 artifacts may still be read through a compatibility adapter during migration, but new generated drafts and new documentation examples must use the execution-focused shape above.

### 6.7.2 Targets and Operations

`targets` is a name-keyed map. Each target must declare `type`, `runner`, and `environment`. Common target types include `application`, `spring_boot_application`, `database`, `event_bus`, `file_storage`, `batch_runner`, `k8s_deployment`, and `vm_service`.

`execute[].operation` must be one of the supported reusable operations for the selected provider family. Core v1 operations are:

- `run_batch`
- `execute_command`
- `call_api`
- `execute_sql`
- `publish_message`
- `consume_message`
- `request_reply_message`
- `run_application`

Every execute step must declare an `outputs` map when later verification or evidence depends on the result. Normal actions such as API calls, SQL queries, batch runs, or command execution belong in `execute`, not `verify`.

### 6.7.3 Setup, Inputs, and Cleanup

Use `setup.fixtures` for precondition data or state that must exist before the operation runs, such as database seeds, file seeds, mock setup, queue seed messages, or initial state. A fixture that changes system state should include `cleanup_ref` when possible.

Use `execute[].with` for runtime inputs passed to the target operation, such as request payloads, file refs, fixture refs, event payloads, query params, or command args. Provider-specific connection strings, credentials, endpoints, SQL bodies, queue client settings, and shell scripts do not belong in the DSL body; they belong in validated provider contracts or referenced files.

### 6.7.4 Expected Results and Verification

`expected_results` contains simple truth references: expected files, expected JSON/YAML, expected DB state snapshots, expected response payloads, schemas, or contracts. Do not duplicate the same expected artifact under both `expected_results` and legacy `oracles`.

Core `verify[].type` values for v1 are:

| Group | Verify Types |
|---|---|
| Basic | `equals`, `not_equals`, `exists`, `not_exists`, `contains`, `regex_match` |
| Structure | `schema_match` |
| Collection | `list_size_equals`, `unordered_list_equals` |
| Numeric | `numeric_tolerance` |
| File | `file_exists`, `file_not_empty`, `file_diff` |
| State | `db_record_exists` |
| Event | `event_published` |

For normal comparison checks, each `verify` item must define explicit `actual` and `expected`. Use `selector` when comparing part of a structured actual result.

```yaml
verify:
  - id: verify_response_status
    type: equals
    actual: ${execute.create_order.outputs.response_body}
    selector: $.status
    expected: CREATED
```

Use `db_record_exists` when verifying persisted database state:

```yaml
verify:
  - id: verify_order_record_created
    type: db_record_exists
    target: order_database
    query:
      ref: queries/order_exists.sql
      params:
        order_id: ${setup.fixtures.orders_seed.order_id}
    expected:
      min_rows: 1
      fields:
        status: NORMALIZED
    options:
      timeout: PT30S
      poll_interval: PT5S
```

Use `event_published` when verifying a produced event:

```yaml
verify:
  - id: verify_order_normalized_event_published
    type: event_published
    target: order_event_bus
    event:
      topic: order.normalized
      key: ${setup.fixtures.orders_seed.order_id}
    expected:
      match:
        $.order_id: ${setup.fixtures.orders_seed.order_id}
        $.event_type: ORDER_NORMALIZED
        $.status: NORMALIZED
    options:
      timeout: PT30S
      poll_interval: PT5S
      consume_from: test_start_time
```

### 6.7.5 Runtime, Evidence, and Status

`runtime` stays small in v1:

```yaml
runtime:
  timeout: PT10M
  retry:
    max_attempts: 0
```

`evidence.required` must reference concrete execution or verification outputs, for example `${execute.run_pipeline.outputs.execution_log}` or `${verify.verify_order_record_created.result}`.

Allowed DSL execution statuses are `draft_skeleton`, `draft_executable`, `active`, `needs_update`, and `retired`. These are not approval states and must not be used as release gates.

An execution-focused DSL v1 artifact is execution-eligible only when it is stored under the RP `tests/approved/` lifecycle location and has an allowed executable status such as `active`. Expected-result approval remains on expected-result artifacts, not on the DSL status field.

### 6.7.6 Run and Report Consumption

The same execution-focused DSL v1 artifact must be consumed consistently by validation, binding, execution evidence, and coverage reporting:

- `run` must derive RP ID and AC ID from `traceability.package_id` and `traceability.acceptance_criteria_id`.
- `run` must derive target runner, fixture type, operation, expected-result reader, verify type, evidence refs, timeout, and retry from v1 sections.
- `run` must write normalized evidence fields needed by existing reporting, including RP ID, AC ID, test case ID, batch ID, run ID, provider family/type, provider contract path, final status, and actual-output refs.
- `report --batch-id` must calculate coverage from batch/run evidence and approved v1 test artifacts without requiring legacy-only fields.
- A v1 test that passes execution but cannot be included in a review-ready batch report is not complete F007/F008 support.

### 6.7.7 Compatibility and Migration

The current implementation still contains legacy v1 field readers in some framework modules. The migration target is:

| Legacy Field | v1 Execution-Focused Field |
|---|---|
| `rp_id` | `traceability.package_id` |
| `ac_id` | `traceability.acceptance_criteria_id` |
| `source_refs.acceptance_criteria` | `traceability.source` |
| `execution_target.adapter` | `targets.<target_id>.runner` |
| `execution_target.environment_ref` | `targets.<target_id>.environment` |
| `fixture.setup` / `fixture.cleanup` | `setup.fixtures.<name>` with `cleanup_ref` |
| `package_inputs.inputs.<name>` | `setup.fixtures.<name>` or `execute[].with.<name>` |
| `steps[]` | `execute[]` |
| `oracles` / `expected.ref` | `expected_results` |
| `assertions[]` | `verify[]` |
| `evidence_required` | `evidence.required` |
| `policy.timeout_seconds` / `policy.retry` | `runtime.timeout` / `runtime.retry.max_attempts` |

Implementation work after this documentation baseline must first add validation and execution support for this execution-focused DSL shape, while keeping legacy samples readable until the sample fixture is migrated.

Implementation sequencing rule:

- First implement DSL v1 parsing and validation for the execution-focused field set.
- Then update generation so new drafts emit only execution-focused fields.
- Then keep legacy artifacts readable through compatibility behavior until migrated.
- Then prove one active execution-focused DSL v1 artifact can pass `run` and `report --batch-id` with review-ready coverage.
- Only after those checks pass may provider runtime dispatch or sample fixture migration claim execution-focused DSL support.
- A new artifact that mixes execution-focused DSL with legacy-only or governance-heavy fields must be blocked before execution.

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

## 6.9 Adapter and Provider Contract

Each executable adapter or provider must expose a validated contract through `rp_ru_mapping.yaml`, RP-level provider configuration, or reusable defaults. Command execution is one adapter contract shape; HTTP, DB, queue, expected-result reader, verify, fixture, and evidence/observation providers should also be configured through contracts rather than one-off provider code.

```yaml
provider_contracts:
  adapters:
    spring_boot_cli:
      provider_family: file_batch
      provider_type: shell
      command: java -jar ${repo}/target/release-unit.jar --spring.profiles.active=regression
      working_directory: ${repo}
      timeout_seconds: 900
      env:
        PIPELINE_MODE: regression
      inputs:
        orders_seed: ${test_case.setup.fixtures.orders_seed}
      outputs:
        actual_output_ref: actual/orders_normalized.csv
      logs:
        stdout: logs/stdout.log
        stderr: logs/stderr.log
      success_exit_codes:
        - 0
  bindings:
    db_seed:
      provider_family: file_batch
      provider_type: file_fixture
      materialize_as: input_file
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
  expected_results: {}
  verify: {}
  evidence: {}
```

Adapter/provider runtime rules:

- Contract resolution order is provider default, RP-level override, then RU-level override.
- Executable contracts must declare `provider_family` and `provider_type`; heuristic family inference is diagnostic only and must not silently choose a runtime.
- Provider capability registry status must be checked before dispatch. Unsupported, ambiguous, unsafe, or unapproved escape-hatch contracts fail before execution.
- Dispatch uses v1 DSL fields and mapping fields: `targets.<target_id>.runner`, `execute[].operation`, `setup.fixtures.<name>.type`, `expected_results.<name>.type`, `verify[].type`, and `evidence.required[]`.
- The framework supplies resolved input paths and run workspace paths.
- Adapters and providers write actual outputs, observation results, and cleanup results under the run evidence directory.
- Messaging actions that declare `requires_correlation: true` must also declare `correlation_id`, `correlation_id_ref`, or `correlation_key` before publish, request/reply, consume, observe, or cleanup dispatch.
- Messaging request/reply actions must declare `mode: request_reply` or `mode: request` and a `payload_binding`, `message_binding`, or `event_binding`. Current native request/reply support is implemented for NATS; Kafka request/reply remains unsupported until a selected RP requires a reusable contract.
- Messaging cleanup actions must declare `mode: cleanup`, `cleanup_strategy: drain`, and a positive bounded `max_count`. Current cleanup is bounded drain behavior for test-owned topics, subjects, or consumer groups, not broker administrator purge.
- Non-success exit codes fail the test case and must preserve stdout, stderr, exit code, and timeout state.
- Timeouts fail the test case and must trigger fixture cleanup.
- Adapters must not perform deployment in M1.
- Provider contracts must reference secrets, SQL, payloads, and environment resources by reference, not inline sensitive values or package-specific implementation bodies.
- External runner contracts require approval metadata, reason, bounded timeout, declared inputs/outputs, and evidence artifact map before invocation.
- Unsupported provider actions or contract fields must fail before execution with owner action.

## 6.10 Execution Evidence

One RP regression execution produces one batch summary and one run evidence directory per approved test case executed in that batch.

Batch evidence:

```yaml
batch_id: BATCH-20260626-001
rp_id: RP-AR-M1-data-pipeline
status: passed
execution_mode: ci_ephemeral
environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
started_at: 2026-06-26T10:00:00+08:00
finished_at: 2026-06-26T10:03:00+08:00
runs:
  - run_id: RUN-20260626-001
    test_case_id: RP-AR-M1-data-pipeline-TC-001
    ac_id: RP-AR-M1-data-pipeline-AC-001
    status: passed
  - run_id: RUN-20260626-002
    test_case_id: RP-AR-M1-data-pipeline-TC-002
    ac_id: RP-AR-M1-data-pipeline-AC-002
    status: passed
```

Batch evidence is stored under:

```text
docs/08-release/release-packages/<rp_id>/evidence/batches/<batch_id>/
```

Run evidence:

```yaml
run_id: RUN-20260626-001
batch_id: BATCH-20260626-001
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
observation_results:
  - observation: log_contains
    status: passed
postcondition_results:
  - postcondition: no_seeded_rows_remaining
    status: passed
evidence_refs:
  - evidence/runs/RUN-20260626-001/logs/stdout.log
  - evidence/runs/RUN-20260626-001/assertions.yaml
  - evidence/runs/RUN-20260626-001/observations.yaml
  - evidence/runs/RUN-20260626-001/postconditions.yaml
```

Run evidence is stored under:

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
