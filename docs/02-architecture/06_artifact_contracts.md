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
            orders_seed: ${test_case.package_inputs.inputs.orders_seed}
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
      oracles: {}
      assertions: {}
      observations: {}
    evidence_responsibility:
      - execution_log
      - output_dataset
    dependencies: []
    required_for:
      - RP-AR-M1-data-pipeline-AC-001
```

The framework consumes this mapping. It must not decide RP membership.

`provider_contracts` is the canonical contract container. Executable provider contracts must declare `provider_family` and `provider_type` so the provider capability registry can validate required fields, runtime support status, allowed execution modes, and evidence outputs before execution. Contracts may be supplied at reusable defaults, RP level, or RU level. Resolution order is: framework/provider default, then RP-level override, then RU-level override. The most specific declared contract wins, and unsupported or ambiguous overrides fail before execution. Dispatch uses DSL and mapping fields: `execution_target.adapter` and step `action` select adapter contracts; `package_inputs.inputs.<name>.bind_as` selects binding contracts; fixture actions select fixture contracts; `oracles.<name>.type` selects oracle contracts; assertion `type` selects assertion contracts; observation `type` selects observation contracts.

Current provider contract minimums enforced by the framework verification build:

| Provider Family / Type | Enforced Minimum Fields |
|---|---|
| `file_batch/shell` | `command`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `request_response/rest` | `endpoint_ref`, `base_url_ref`, or `service_ref`; `actions`; positive `timeout_seconds`; `outputs.actual_output_ref` |
| `messaging/local` or `messaging/mock` | `topic_ref`, `subject_ref`, `stream_ref`, or `endpoint_ref`; positive `timeout_seconds`; `outputs.actual_output_ref`; supported action; payload binding when publishing; cleanup strategy and positive max count when cleaning; correlation id when required |
| `messaging/kafka` or `messaging/nats` | `bootstrap_servers_ref`, `server_ref`, or `connection_ref`; `topic_ref` or `subject_ref`; positive `timeout_seconds`; `outputs.actual_output_ref`; supported action; payload binding when publishing; `cleanup_strategy: drain` and positive `max_count` when cleaning; correlation id when required |
| `db_fixture/jdbc` | `connection_ref`, `isolation_key`, `cleanup_strategy`, setup/cleanup SQL by `sql_ref`, verification SQL by `sql_ref` |
| `deployment_readiness/local` or `deployment_readiness/mock` | `readiness_probe`, deployment/service/target ref, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `deployment_readiness/k8s` | `readiness_probe`, `kube_context_ref` or `connection_ref`, `namespace_ref`, deployment/service/selector ref for readiness, `target_selector` or `pod_ref` plus positive `log_tail_lines` for `pod_logs`, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` |
| `deployment_readiness/vm` | `readiness_probe`, `host_ref` and positive `port` for TCP, or `health_url_ref`/`endpoint_ref` for HTTP, `deployed_version_ref`, positive `timeout_seconds`, `outputs.actual_output_ref` |
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

## 6.7 Package-Neutral Test Case DSL

The test case artifact is a package-neutral DSL. It describes the RP AC being validated, the execution target, test scenario, package inputs, fixture lifecycle, logical steps, assertions, and evidence expectations. It must not embed package-specific execution code; package-specific behavior is resolved through the configured adapter.

M1 keeps the required DSL surface small. A field is required only when the framework cannot trace, validate, execute, assert, or collect evidence without it.

DSL v1 follows a combined reference model: Robot Framework-style setup/teardown and reusable actions, Gherkin-style behavior traceability, Karate-style payload/assertion bindings, and Pact-style interaction boundaries. The DSL remains a regression execution contract, not a BDD-only document.

DSL v1 sections:

| Section | Purpose |
|---|---|
| `dsl_version` | Select parser and compatibility rules. |
| `test_case_id`, `artifact_status`, `revision` | Govern identity, lifecycle, and review state. |
| `rp_id`, `ac_id`, `source_refs`, `source_fingerprint` | Trace the test to RP AC and source artifacts. |
| `scenario` | Declare test type, scope, and composed capabilities. |
| `preconditions` | Declare required state that must already exist before setup starts. |
| `parameters` | Declare data variants or example rows used to repeat the same logical test. |
| `dependencies` | Declare required RUs, services, or step ordering constraints. |
| `execution_target` | Select RU boundary, adapter, execution mode, and environment. |
| `expected` | Reference reviewed regression truth. |
| `oracles` | Declare named truth sources and decision rules used by assertions. |
| `package_inputs` | Declare name-keyed test data, payload, config, state, or seed bindings. |
| `fixture` | Prepare and clean resources required by the test lifecycle. |
| `steps` | Declare logical executable actions. |
| `assertions` | Declare pass/fail evaluation rules. |
| `observations` | Declare logs, metrics, traces, or events that must be collected or checked. |
| `postconditions` | Declare state that must hold after execution and cleanup. |
| `evidence_required` | Declare minimum evidence produced by a run. |
| `policy` | Declare execution safety rules such as timeout, retry, cleanup, and destructive-action limits. |

The DSL must support multiple regression scenario shapes, not only file-based execution. Supported M1 scenario capabilities are:

| Capability | Example Binding |
|---|---|
| `file_input`, `dataset_input` | Bind CSV, JSON, Parquet, or generated dataset into a step. |
| `api_payload`, `message_event` | Bind a request payload, event, or queue message into an adapter action. |
| `db_seed` | Insert test rows before execution and clean them after execution. |
| `config_input`, `env_var` | Bind config files, feature flags, or environment variables for the run. |
| `existing_state` | Verify pre-existing SIT state before running assertions. |
| `batch_execution` | Execute a batch, job, pipeline, or scheduled process through an adapter. |
| `multi_ru_integration` | Bind inputs and expected outputs across more than one RU boundary. |
| `file_assertion`, `response_assertion`, `db_assertion` | Validate actual outputs, API responses, or database state. |

Each input binding must declare what it is and how it enters the execution lifecycle. Scenario capabilities are composable because real RP regression often combines setup, API calls, queue events, DB checks, and file assertions in one test. The DSL owns the logical binding contract; adapters own the concrete command, API, database, or queue operation.

Oracle and assertion reference model:

| Oracle Type | Purpose |
|---|---|
| `golden_file` | Compare actual output with an approved file or snapshot. |
| `expected_result_artifact` | Resolve truth from the reviewed expected-result artifact. |
| `schema` | Validate structure, required fields, and allowed values. |
| `contract` | Validate provider/consumer interaction expectations. |
| `invariant` | Validate business rules that must always hold. |
| `query_result` | Validate database state through a reviewed query reference. |
| `tolerance` | Validate numeric, timing, or aggregate results within a threshold. |
| `absence` | Validate that an error, event, row, or side effect did not occur. |

Assertions execute comparisons against an oracle. The assertion owns the comparison method and actual reference; the oracle owns the truth source and decision rule. This keeps expected data, business rules, and executable comparison logic separate.

Data selection and parameterization reference model:

| Strategy | Purpose |
|---|---|
| `explicit_cases` | Run the same logical test against owner-declared case rows with explicit binding and oracle overrides. |
| `catalog_query` | Select cases from a test data catalog using tags, attributes, limits, and eligibility rules. |
| `matrix` | Generate cases from controlled dimension combinations such as region, currency, channel, or feature flag state. |

Parameterized execution must preserve case identity in evidence. A failed case must report the parameter case name, resolved bindings, oracle overrides, and assertion result without hiding other case results.

DSL v1 schema rules:

M1 implementation core is intentionally smaller than the full DSL vocabulary. The initial executable core supports `scenario.type` values `component` and `integration`, `scenario.scope` values `release_unit` and `release_package`, and the currently implemented file/batch capabilities: `file_input`, `dataset_input`, `db_seed`, `batch_execution`, and `file_assertion`.

The selected heterogeneous RP pilot promotes additional DSL v1 capabilities such as `api_payload`, `message_event`, `existing_state`, `response_assertion`, and `db_assertion` when their provider families are implemented and verified. DSL enum values outside the selected or implemented provider families remain reserved and must fail as unsupported before execution.

- `scenario.type` must be one of `component`, `integration`, `contract`, `migration`, or `e2e`.
- `scenario.scope` must be one of `release_unit`, `release_package`, or `product`.
- `scenario.capabilities` must list one or more supported capabilities.
- `preconditions` declare state that must already exist and must not be created by this test.
- `parameters.strategy` must be one of `explicit_cases`, `catalog_query`, or `matrix` when `parameters` is declared.
- `parameters.cases` declares named data variants for `explicit_cases`.
- `parameters.cases[].bindings` overrides one or more `package_inputs.inputs.<name>.ref` values for that case.
- `parameters.cases[].oracle_overrides` may patch one or more named oracle fields for that case, such as `ref` or `value`.
- `parameters.selector` defines catalog filters for `catalog_query`, including tags and maximum case count.
- `parameters.matrix.dimensions` defines combinatorial data variants for `matrix`.
- `dependencies.requires` lists RUs, services, fixtures, or external state that must be available before execution.
- `dependencies.step_order` may constrain step order when dependency cannot be inferred from interpolation references.
- `package_inputs.inputs` is a name-keyed map. References use `${package_inputs.inputs.<name>}`.
- `package_inputs.inputs.<name>.bind_as` must be one of `input_file`, `dataset`, `api_payload`, `message_event`, `db_seed`, `config_file`, `env_var`, or `existing_state`.
- `package_inputs.inputs.<name>.lifecycle` is required when a binding creates, mutates, publishes, seeds, or configures shared or persistent resources.
- `fixture.cleanup` is required whenever setup mutates local, CI, SIT, or shared state.
- `expected.ref` is required when the test uses an approved expected-result artifact as the reviewed regression truth source.
- `oracles` is a name-keyed map when assertions need reusable truth sources or decision rules. References use `${oracles.<name>}`.
- `oracles.<name>.type` must be one of `golden_file`, `expected_result_artifact`, `schema`, `contract`, `invariant`, `query_result`, `tolerance`, or `absence`.
- Every assertion must define either `oracle` or an inline decision rule. Assertions should not compare directly to `expected.ref`; use an oracle of type `expected_result_artifact` when the expected-result artifact owns the truth source.
- `observations` declare non-primary signals such as logs, metrics, traces, and emitted events used for evidence or secondary validation.
- `postconditions` declare required final state after execution and cleanup.
- `policy.cleanup_required` must be true when any binding lifecycle requires cleanup.

```yaml
dsl_version: 1
test_case_id: RP-AR-M1-data-pipeline-TC-001
rp_id: RP-AR-M1-data-pipeline
ac_id: RP-AR-M1-data-pipeline-AC-001
artifact_status: approved_for_regression
revision: 1
source_refs:
  acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
source_fingerprint: "sha256:<hash-of-source-contract>"
execution_target:
  ru_id: RU-transform-job
  adapter: spring_boot_cli
  execution_mode: ci_ephemeral
  environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
scenario:
  type: integration
  scope: release_package
  capabilities:
    - db_seed
    - batch_execution
    - file_assertion
preconditions:
  - type: service_available
    ref: ci://pipeline/rp-ar-m1-data-pipeline
parameters:
  strategy: explicit_cases
  cases:
    - name: valid_orders
      bindings:
        orders_seed: fixtures/db/orders_valid.yaml
      oracle_overrides:
        normalized_orders:
          ref: expected/output/orders_valid_normalized.csv
    - name: cancelled_orders
      bindings:
        orders_seed: fixtures/db/orders_cancelled.yaml
      oracle_overrides:
        normalized_orders:
          ref: expected/output/orders_cancelled_normalized.csv
dependencies:
  requires:
    - RU-transform-job
  step_order:
    - run_pipeline
expected:
  ref: expected-results/approved/RP-AR-M1-data-pipeline-AC-001.yaml
oracles:
  normalized_orders:
    type: golden_file
    ref: expected/output/orders_normalized.csv
    comparison: file_diff
package_inputs:
  inputs:
    orders_seed:
      ref: fixtures/db/orders_seed.yaml
      bind_as: db_seed
      required: true
      checksum: "sha256:<hash-of-seed-data>"
      lifecycle:
        isolation_key: test_run_id
        cleanup_required: true
        cleanup_on_failure: true
fixture:
  setup:
    - action: seed_database
      input: ${package_inputs.inputs.orders_seed}
      strategy: tagged_test_run
  cleanup:
    - action: cleanup_database
      strategy: by_test_run_id
steps:
  - id: run_pipeline
    action: execute
    input: ${package_inputs.inputs.orders_seed}
assertions:
  - type: file_diff
    actual: ${steps.run_pipeline.outputs.normalized_orders}
    oracle: ${oracles.normalized_orders}
observations:
  - type: log_contains
    source: ${steps.run_pipeline.logs.stdout}
    pattern: "pipeline completed"
postconditions:
  - type: no_seeded_rows_remaining
    strategy: by_test_run_id
evidence_required:
  - execution_log
  - assertion_results
  - actual_output
  - resolved_bindings
  - cleanup_result
policy:
  timeout_seconds: 600
  retry: 0
  cleanup_required: true
  destructive_action_allowed: false
```

Representative DSL v1 scenario examples:

```yaml
# Catalog-selected data regression
parameters:
  strategy: catalog_query
  source: testdata://orders
  selector:
    tags: [regression, approved]
    attributes:
      order_type: standard
    max_cases: 10
package_inputs:
  inputs:
    orders_seed:
      ref: ${parameters.selected.ref}
      bind_as: db_seed
```

```yaml
# Matrix parameterization
parameters:
  strategy: matrix
  matrix:
    dimensions:
      region: [TW, US]
      currency: [TWD, USD]
    exclude:
      - region: US
        currency: TWD
package_inputs:
  inputs:
    order_payload:
      ref: payloads/order_${parameters.region}_${parameters.currency}.json
      bind_as: api_payload
```

```yaml
# File or dataset regression
scenario:
  type: component
  scope: release_unit
  capabilities: [file_input, file_assertion]
package_inputs:
  inputs:
    source_file:
      ref: fixtures/input/orders.csv
      bind_as: input_file
      checksum: "sha256:<hash>"
oracles:
  transformed_file:
    type: golden_file
    ref: expected/output/orders_transformed.csv
    comparison: file_diff
steps:
  - id: transform
    action: execute
    input: ${package_inputs.inputs.source_file}
assertions:
  - type: file_diff
    actual: ${steps.transform.outputs.result_file}
    oracle: ${oracles.transformed_file}
```

```yaml
# API payload regression
scenario:
  type: integration
  scope: release_package
  capabilities: [api_payload, response_assertion]
package_inputs:
  inputs:
    submit_order:
      ref: payloads/submit_order.json
      bind_as: api_payload
steps:
  - id: submit
    action: call_api
    input: ${package_inputs.inputs.submit_order}
assertions:
  - type: json_path_equals
    actual: ${steps.submit.response.status}
    oracle:
      type: invariant
      rule: equals
      value: APPROVED
```

```yaml
# Message event regression
scenario:
  type: integration
  scope: release_package
  capabilities: [message_event, db_assertion]
package_inputs:
  inputs:
    order_event:
      ref: events/order_created.json
      bind_as: message_event
      lifecycle:
        isolation_key: test_run_id
        cleanup_required: true
        cleanup_on_failure: true
oracles:
  order_projection:
    type: query_result
    ref: queries/order_projection_by_test_run_id.sql
    comparison: db_row_matches
fixture:
  setup:
    - action: publish_message
      input: ${package_inputs.inputs.order_event}
  cleanup:
    - action: cleanup_messages
      strategy: by_test_run_id
steps:
  - id: consume_order_event
    action: execute_consumer
assertions:
  - type: db_row_matches
    actual: ${steps.consume_order_event.outputs.order_projection}
    oracle: ${oracles.order_projection}
```

```yaml
# Existing SIT state validation
scenario:
  type: e2e
  scope: release_package
  capabilities: [existing_state, api_payload]
package_inputs:
  inputs:
    sit_order_state:
      ref: sit://orders/known-approved-order
      bind_as: existing_state
oracles:
  state_ready:
    type: invariant
    rule: state_exists
    value: true
steps:
  - id: verify_state
    action: validate_state
    input: ${package_inputs.inputs.sit_order_state}
assertions:
  - type: state_exists
    actual: ${steps.verify_state.result.exists}
    oracle: ${oracles.state_ready}
```

```yaml
# Multi-RU integration
scenario:
  type: integration
  scope: release_package
  capabilities: [api_payload, multi_ru_integration, file_assertion]
execution_target:
  ru_id: RP-ORDER-PACKAGE
  adapter: rp_orchestrator
  execution_mode: ci_ephemeral
oracles:
  settlement_file:
    type: golden_file
    ref: expected/output/settlement_file.csv
    comparison: file_diff
steps:
  - id: create_order
    action: call_ru
    target_ru_id: RU-order-api
  - id: settle_order
    action: call_ru
    target_ru_id: RU-settlement-job
    input: ${steps.create_order.outputs.order_id}
assertions:
  - type: file_diff
    actual: ${steps.settle_order.outputs.settlement_file}
    oracle: ${oracles.settlement_file}
```

Allowed test case statuses are `draft_test_skeleton`, `draft_executable_test_case`, `approved_for_regression`, `needs_update`, and `retired`.

Required DSL fields:

| Field | Why Required |
|---|---|
| `dsl_version` | Selects the parser and compatibility rules. |
| `test_case_id` | Stable identity for review, execution, evidence, and replacement. |
| `rp_id` | Binds the test to a Release Package. |
| `ac_id` | Binds the test to the RP AC coverage denominator. |
| `artifact_status` | Controls whether the test is draft, approved, update-needed, or retired. |
| `revision` | Preserves durable test history. |
| `source_refs.acceptance_criteria` | Proves the test is derived from owner-authored AC. |
| `source_fingerprint` | Detects drift between the DSL test and source artifacts. |
| `execution_target.ru_id` | Identifies which RU boundary is validated. |
| `execution_target.adapter` | Selects package-specific execution behavior. |
| `execution_target.execution_mode` | Selects local, CI, SIT, or evidence-only execution policy. |
| `scenario.type` | Declares the test shape, such as component, integration, contract, migration, or e2e. |
| `scenario.scope` | Declares whether the test validates an RU, RP, or product-level behavior. |
| `scenario.capabilities` | Declares the composed scenario capabilities so binding, fixture, and evidence rules are selected deliberately. |
| `steps` | Defines at least one logical validation action. |
| `assertions` | Defines pass/fail evaluation. |
| `evidence_required` | Defines minimum evidence expected from the run. |

Conditionally required fields:

| Field | Required When |
|---|---|
| `execution_target.environment_ref` | Required for `ci_ephemeral`, `sit_deployed`, and `evidence_only`. |
| `preconditions[].type` / `preconditions[].ref` | Required for each declared precondition. |
| `parameters.strategy` | Required when `parameters` is declared. |
| `parameters.cases[].name` | Required for each `explicit_cases` case. |
| `parameters.cases[].bindings` | Required when an `explicit_cases` case overrides package input bindings. |
| `parameters.source` / `parameters.selector` | Required when `parameters.strategy` is `catalog_query`. |
| `parameters.matrix.dimensions` | Required when `parameters.strategy` is `matrix`. |
| `dependencies.requires` | Required when `scenario.capabilities` includes `multi_ru_integration` or an adapter depends on external services. |
| `dependencies.step_order` | Required when step order cannot be inferred from explicit interpolation references. |
| `package_inputs.inputs.<name>` | Required for each named input binding used by steps, fixtures, assertions, or adapters. |
| `package_inputs.inputs.<name>.ref` | Required when the binding resolves from a file, dataset, payload, catalog entry, script, or environment resource. |
| `package_inputs.inputs.<name>.bind_as` | Required when the binding is not self-evident from the adapter contract; allowed values include `input_file`, `dataset`, `api_payload`, `message_event`, `db_seed`, `config_file`, `env_var`, and `existing_state`. |
| `package_inputs.inputs.<name>.checksum` | Required for checked-in or approved immutable test data. |
| `package_inputs.inputs.<name>.lifecycle` | Required when the binding creates, mutates, seeds, publishes, or configures shared or persistent resources. |
| `expected.ref` | Required when an oracle of type `expected_result_artifact` or approved external expected-result truth is used. |
| `oracles.<name>` | Required when an assertion references `${oracles.<name>}`. |
| `oracles.<name>.type` | Required for each declared oracle. |
| `observations[].type` / `observations[].source` | Required for each declared observation. |
| `postconditions[].type` | Required for each declared postcondition. |
| `fixture.setup` / `fixture.cleanup` | Required when the test creates, mutates, seeds, publishes, or configures local, CI, SIT, or shared resources. |
| `policy.cleanup_required` | Required when the test creates, mutates, seeds, publishes, or configures local, CI, SIT, or shared resources. |

Optional DSL fields:

| Field | Purpose |
|---|---|
| `source_refs.rp_feature_spec` | Improves traceability to feature behavior, but AC remains the formal source. |
| `source_refs.rp_ru_mapping` | Improves traceability to execution mapping. |
| `replaces` | Links a new revision to a retired or superseded test. |
| `bdd` | Preserves Given/When/Then human-readable context. |
| `package_inputs.inputs.<name>.owner_action` | Explains what the owner must fix when the binding cannot be resolved. |
| `tags` | Supports future run selection such as smoke, release-gate, or impacted-RU. |
| `priority` | Supports future suite policy. |
| `notes` | Reviewer-facing explanation that is not used for execution. |

DSL responsibility split:

| DSL Section | Owns | Adapter Owns |
|---|---|---|
| `execution_target` | RU ID, adapter name, execution mode, environment ref | How the adapter invokes the RU |
| `scenario` | Regression type, scope, composed capabilities, and lifecycle expectations | Package-specific feasibility checks |
| `preconditions` | Required pre-existing state and readiness checks | Package-specific readiness probing |
| `parameters` | Named case variants and binding overrides | Adapter-specific expansion limits or unsupported variants |
| `dependencies` | Required RUs, services, external state, and explicit step ordering | Package-specific dependency health details |
| `expected` | Reviewed expected-result reference when the test uses an expected-result artifact as truth | Loading package-specific expected output artifacts |
| `oracles` | Named truth sources, oracle type, comparison rule, and decision parameters | Producing actual values compatible with the oracle |
| `package_inputs` | Named logical bindings, refs, binding type, checksum, and lifecycle policy | Resolving adapter-specific argument names and concrete resource handles |
| `fixture` | Setup and cleanup intent, including seed and cleanup strategy | Concrete fixture command implementation when adapter-specific |
| `steps` | Logical validation actions | Package-specific command execution |
| `assertions` | Assertion type, actual refs, oracle refs, and inline decision rules | Producing actual outputs |
| `observations` | Required secondary logs, metrics, traces, or events | Producing package-specific observation streams |
| `postconditions` | Required final state after execution and cleanup | Package-specific final-state probes |
| `evidence_required` | Required evidence categories | Concrete log/output file production |

DSL extension rules:

- Keep DSL sections and required fields stable across Products, RPs, and RUs.
- Provider interfaces must be stable and reusable across RPs by default.
- RP-specific provider behavior belongs in validated provider contract configuration, not test case DSL, not one-off provider code, and not RP-specific custom scripts as the standard path.
- Provider configuration is not part of the test case DSL. Test cases may reference provider capabilities only by logical type, action, binding, oracle, assertion, observation, or fixture names.
- Prefer configuring an existing built-in provider before adding provider code or a new DSL field.
- Add provider code only when the required behavior is reusable across RPs and cannot be expressed safely by an existing provider contract.
- Use external runner contracts only as approved escape hatches with bounded execution and evidence mapping.
- Provider contract configuration must be schema-validated before execution.
- Add new `bind_as`, fixture action, oracle type, assertion type, observation type, or adapter action only for recurring cross-RP concepts.
- Do not add package-specific commands, URLs, credentials, SQL bodies, queue implementation details, or shell scripts directly to test case DSL.
- Any incompatible DSL shape change requires a new `dsl_version` and compatibility behavior.
- Unsupported adapter/provider capabilities must fail fast with test case ID, AC ID, section name, and owner action.

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

Each executable adapter or provider must expose a validated contract through `rp_ru_mapping.yaml`, RP-level provider configuration, or reusable defaults. Command execution is one adapter contract shape; HTTP, DB, queue, oracle, assertion, fixture, and observation providers should also be configured through contracts rather than one-off provider code.

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
        orders_seed: ${test_case.package_inputs.inputs.orders_seed}
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
  oracles: {}
  assertions: {}
  observations: {}
```

Adapter/provider runtime rules:

- Contract resolution order is provider default, RP-level override, then RU-level override.
- Executable contracts must declare `provider_family` and `provider_type`; heuristic family inference is diagnostic only and must not silently choose a runtime.
- Provider capability registry status must be checked before dispatch. Unsupported, ambiguous, unsafe, or unapproved escape-hatch contracts fail before execution.
- Dispatch uses DSL fields and mapping fields: adapter/action, `bind_as`, fixture action, oracle type, assertion type, and observation type.
- The framework supplies resolved input paths and run workspace paths.
- Adapters and providers write actual outputs, observation results, and cleanup results under the run evidence directory.
- Messaging actions that declare `requires_correlation: true` must also declare `correlation_id`, `correlation_id_ref`, or `correlation_key` before publish, consume, observe, or cleanup dispatch.
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
