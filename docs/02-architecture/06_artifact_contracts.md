# 06. Artifact Contracts

These contracts define the artifacts needed to design or implement Auto Regression Test Framework v0.2 as a feature-complete pre-release execution framework. They are specification contracts, not final stable v1.0 runtime API design.

Core boundary: Product/RP/RU mapping is product knowledge consumed by Phase 2 Agent Skills. The framework core consumes generated framework-readable artifacts: DSL tests, suite manifest, run plan, Env_Profiles, Provider Instances, Provider Contracts, expected results, parameter sets, and traceability map. Legacy `execution_profiles/` and `environment_bindings/` remain compatibility inputs for older generated artifacts only; new v0.2.6 samples author environment configuration through Env_Profile `bindings`.

## 6.1 Product Repo Layout

Release Package records shall be grouped by RP ID:

```text
docs/08-release/release-packages/<rp_id>/
  package.yaml
  rp_feature_spec.md
  rp_ru_mapping.yaml
  generated-framework/
    suite_manifest.yaml
    run_plan.yaml
    env_profiles/
    provider_instances/
    provider_contracts/
    mapping_explanation.md
    traceability_map.yaml
  acceptance_criteria.md
  parameter-sets/
  tests/
    draft/
    approved/
    retired/
  expected-results/
    draft/
    approved/
    retired/
  provider-contracts/
  provider-instances/
  environment-bindings/
  execution-profiles/
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

Examples in this document use a file/batch-style RP ID for readability. They illustrate artifact shape and do not define the selected heterogeneous pilot.

## 6.3 `package.yaml`

```yaml
product_id: PROD-auto-regression
rp_id: RP-AR-M1-data-pipeline
name: M1 File Batch Regression Example
owner: product_developer
target_release: M1
package_type: data_pipeline
lifecycle_status: draft
default_execution_mode: ci
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

## 6.4 Product Mapping Input and Generated Framework Artifacts

`rp_ru_mapping.yaml` remains a Product Repo artifact, but it is not a framework runtime contract. The Agent Skill reads it with product docs, release manifest, deployment manifest, and SIT topology, then produces framework-readable artifacts.

```yaml
rp_id: RP-AR-M1-data-pipeline
release_units:
  - ru_id: RU-transform-job
    repo: /path/to/release-unit-repo
    unit_type: data_pipeline
    owner: product_developer
    version_ref: main
    validation_boundary: execute_pipeline_with_fixture
    execution_mode: ci
    deployment_required: false
    environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
    provider_intent:
      provider_id: transform-job
      provider_type: shell_command
      operation: run_batch
    evidence_responsibility:
      - execution_log
      - output_dataset
    dependencies: []
    required_for:
      - RP-AR-M1-data-pipeline-AC-001
```

The framework must not consume this mapping to decide RP membership, RU technology, runner eligibility, or topology. Runtime uses generated artifacts:

```text
generated-framework/
  suite_manifest.yaml
  run_plan.yaml
  env_profiles/
    ci.yaml
    sit.yaml
  provider_instances/
    providers.yaml
  provider_contracts/
    providers.yaml
  mapping_explanation.md
  traceability_map.yaml
```

Minimal `suite_manifest.yaml`:

```yaml
suite_id: RP-AR-M1-data-pipeline-regression
tests:
  - tests/approved/RP-AR-M1-data-pipeline-TC-001.yaml
coverage_source_ref: acceptance_criteria.md
traceability_map_ref: traceability_map.yaml
```

Minimal `run_plan.yaml`:

```yaml
env_profile_ref: env_profiles/ci.yaml
execution_mode: ci
target_dependencies:
  transform_job: []
runtime:
  timeout: PT10M
```

Minimal `env_profiles/ci.yaml`:

```yaml
env_profile_id: ci
execution_mode: ci
providers:
  transform-job:
    runtime_mode: native
    bindings:
      command:
        value: command://run-transform-job
```

Optional Env_Profile policy sections such as `isolation_scope`, `max_duration`, dependency policy, provisioning policy, data policy, and evidence policy are defaults-backed. Sample authors add them only when the profile needs stricter behavior than the framework defaults.

Minimal `provider_instances/providers.yaml`:

```yaml
provider_instances:
  transform-job:
    provider_instance_version: v0.2
    provider_id: transform-job
    provider_type: shell_command
    runtime_modes: [native]
    safety:
      access_policy:
        allow_shell: false
        allowed_commands:
          - command://run-transform-job
        blocked_args:
          - rm
          - shutdown
          - reboot
    operations:
      run_batch:
        allowed_inputs:
          - runner.args.ordersSeed
        required_inputs:
          - runner.args.ordersSeed
        outputs:
          exit_code: exit_code
          actual_output: output_files
          execution_log: stdout
    evidence:
      capture: [stdout, stderr, actual_output]
```

Minimal `traceability_map.yaml`:

```yaml
package_id: RP-AR-M1-data-pipeline
source_labels:
  transform_job:
    rp_id: RP-AR-M1-data-pipeline
    ru_id: RU-transform-job
    version_ref: main
```

Provider Contracts, Provider Instances, and Env_Profiles are the canonical runtime public interfaces. Runtime resolution is:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Provider Contract
  -> selected Env_Profile
  -> Env_Profile.providers.<provider_id>.bindings
```

Executable Provider Contracts must declare `provider_type`, allowed runtime modes, allowed operations, allowed input names, binding key schema, bindable outputs, output refs, evidence outputs, failure codes, and the valid Provider Instance shape. Provider Instances define logical provider targets and cannot redefine binding key schema. Env_Profiles supply actual environment-specific values for required binding keys under `providers.<provider_id>.bindings` and select `runtime_mode` for each `provider_id`. Unsupported, missing, prohibited, or ambiguous resolution fails before execution and before non-dry-run provider dispatch.

Local and CI Env_Profiles are expected to replace most external service, database, messaging, K8s, and VM dependencies with explicit mock, stub, ephemeral, fake-topic, embedded-broker, disposable-schema, generated-data, or externally pre-provisioned dependency bindings. Those replacements are still Provider Instances and Env_Profile dependency-provisioning policy; they are not a separate DSL feature and must not be inferred as a fallback. SIT and preprod default to `runtime_mode: native` and cannot use mock substitution for release evidence unless the run is explicitly classified as framework verification evidence.

Env_Profiles own the dependency provisioning policy and provider binding keys for local and CI. For externally provisioned or embedded dependencies, the Env_Profile declares allowed provisioners, dependency type, startup/readiness policy, cleanup scope, and provider bindings. Generated values such as JDBC URLs, broker bootstrap servers, mapped ports, service base URLs, or topic refs may be supplied as materialized `value`, `secret_ref`, approved `local_ref`, or as `generated_ref` only when the ref is declared in `dependency_provisioning_policy.generated_outputs` or produced by another Provider Contract in the same suite through a matching `bindable_outputs` entry.

`mapping_explanation.md` is generated by the Phase 2 Agent Skill and records source facts, selected provider_id/profile/contract refs, strategy rationale, unresolved assumptions, and owner actions. The framework may surface the file path in readiness evidence but must not parse it to choose runtime behavior.

## 6.4.1 Track A Framework Contract Baseline

Track A contract artifacts are framework-owned and live under `docs/02-architecture/contracts/`. They define the public interface before provider runtime expansion:

- `framework_usage_interface.v0.2.md`
- `test_case_dsl.v0.2.schema.yaml`
- `suite_manifest.v0.2.schema.yaml`
- `provider_contract.v0.2.schema.yaml`
- `provider_instance.v0.2.schema.yaml`
- `env_profile.v0.2.schema.yaml`
- `execution_profile.v0.2.schema.yaml` as compatibility input until runtime migration
- `environment_binding.v0.2.schema.yaml` as compatibility input until runtime migration
- `result.v0.2.schema.yaml`
- `evidence.v0.2.schema.yaml`
- `validation_error_taxonomy.v0.2.yaml`
- `secret_guardrails.v0.2.yaml`
- `evidence_folder_structure.v0.2.md`
- `p0_provider_verify_catalog.v0.2.md`

Track A samples live under `samples/` and are contract-shape examples only. They may be used for syntax checks, docs review, and later `regress validate` / `regress run --dry-run` tests, but they are not downstream Product/RP release evidence.

## 6.4.2 Track B Golden E2E Sample Artifacts

Track B sample artifacts live under `samples/golden_e2e/` and prove one complete framework lifecycle through a deterministic framework-owned fake provider. These artifacts are framework verification inputs only and must not be represented as Product/RP release evidence.

Required Track B artifacts:

```text
samples/golden_e2e/
  suite_manifest.yaml
  test_case.yaml
  provider_contracts/sample_fake_provider.yaml
  provider_instances/sample_fake_instance.yaml
  env_profiles/local_golden.yaml
  fixtures/input.json
  fixtures/setup_fixture.yaml
  fixtures/cleanup_fixture.yaml
  expected_results/expected_output.json
  evidence/expected_evidence_index.yaml
  result/expected_result_shape.json
```

The `sample_fake_provider` contract is the only provider type introduced by Track B. It may set up fixture evidence, produce deterministic actual output, clean up fixture evidence, and support generic `value_equals` / `json_match` verification. It must not open network connections, run shell commands, connect to databases or brokers, call Product/RP/RU code, or inspect Product/RP/RU labels.

## 6.4.3 Track C Provider Capability Sample Artifacts

Track C sample artifacts live under `samples/provider_capability/` and define the P0 provider capability verification suite. These artifacts are framework provider capability evidence inputs only and must not be represented as downstream Product/RP release evidence.

Required Track C sample roots:

```text
samples/provider_capability/
  suite_manifest.yaml
  wiremock/
  wiremock_http_request/
  jdbc/
  nats/
  compare/
  polling/
  result/
```

Track C may implement only the P0 capability runtime represented by those samples: WireMock HTTP mock, WireMock plus `rest_client` HTTP request, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and provider evidence. Non-P0 providers and release governance remain outside this artifact contract unless a decision log moves them into P0.

### 6.4A Framework-Owned Schemas and Catalogs

The framework owns generic schemas and catalogs. Product-specific strategy selection remains outside the framework.

Framework-owned schemas and contract files:

- `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`
- `docs/02-architecture/contracts/test_case_dsl.v0.2.schema.yaml`
- `docs/02-architecture/contracts/env_profile.v0.2.schema.yaml`
- `docs/02-architecture/contracts/execution_profile.v0.2.schema.yaml` as compatibility input until runtime migration
- `docs/02-architecture/contracts/environment_binding.v0.2.schema.yaml` as compatibility input until runtime migration
- `docs/02-architecture/contracts/suite_manifest.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_contract.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_instance.v0.2.schema.yaml`
- `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml`
- `docs/02-architecture/contracts/provider_plugin_contract.md`
- `docs/02-architecture/contracts/verify_plugin_contract.md`
- `docs/02-architecture/contracts/result.v0.2.schema.yaml`
- `docs/02-architecture/contracts/evidence.v0.2.schema.yaml`

Framework-owned catalogs:

- Provider type catalog: `shell_command`, `rest_client`, `grpc_client`, `wiremock_http_mock`, `jdbc`, `kafka`, `ibm_mq`, `nats`, `kubernetes_runtime`, `vm_runtime`, `external_runner`, `artifact_compare`, and `polling_observer`. `kafka_messaging` is a deprecated compatibility alias for older v0.2 artifacts.
- Provider capability registry: supported `provider_type` values, required binding keys, supported operations, public `support_status`, evidence outputs, and safety constraints.
- Operation catalog: Provider Contract-backed operations, including `run_batch`, `execute_command`, `http_request`, `unary_call`, `db_seed`, `db_cleanup`, `db_query`, `db_record_exists`, `kafka_publish`, `kafka_observe`, `kafka_payload_match`, `mq_put`, `mq_browse`, `mq_message_exists`, `mq_payload_match`, legacy `publish_message`, legacy `consume_message`, `nats_publish`, `nats_observe`, `event_published`, `event_payload_match`, `check_deployment_ready`, `check_pod_ready`, `get_logs`, `wait_rollout`, `exec_command`, `check_host_ready`, `run_command`, `collect_file`, `collect_logs`, `check_process`, `run`, `run_and_collect`, `check_status`, `start_mock`, `connect_mock`, `load_stubs`, `verify_requests`, `read_artifact`, and `observe_condition`.
- Verify catalog: `equals`, `not_equals`, `exists`, `not_exists`, `contains`, `regex_match`, `json_match`, `schema_match`, `list_size_equals`, `unordered_list_equals`, `subset_match`, `partial_match`, `numeric_tolerance`, `greater_than`, `less_than`, `between`, `timestamp_tolerance`, `file_exists`, `file_not_empty`, `file_diff`, `json_diff`, `yaml_diff`, `csv_row_count_equals`, `csv_diff`, `db_record_exists`, `db_field_equals`, `db_row_count_equals`, `event_published`, `event_payload_match`, `event_not_published`, `http_mock_called`, `http_mock_request_body_match`, `http_mock_request_count`, `http_mock_not_called`, and `custom_verify`.
- Fixture and setup catalog: DSL `data` catalog, operation `inputs`, `database_seed`, `database_cleanup`, `db_seed`, `db_cleanup`, `http_stub`, `event_seed`, `event_expectation`, `file_seed`, `file_cleanup`, `config_injection`, `env_injection`, `mock_config`, `message_seed`, `container_dependency`, `environment_variable`, and `test_data_namespace`.

The framework validates that a Provider Instance, Provider Contract, selected runtime mode, operation, fixture type, verify type, evidence ref, and required Env_Profile provider binding keys are declared and supported. The Agent Skill decides whether a product/RU should use a catalog entry. For example, the framework may support an external runner provider type, but the Agent Skill decides whether a release unit has an approved need for that escape hatch.

DSL `targets.<target_id>` must contain `provider_id` only. The active Env_Profile is selected by CLI or suite manifest. Concrete URLs, topics, database strings, namespaces, and secret refs belong in Env_Profile provider binding keys, not in the DSL. Kafka topic/consumer group and IBM MQ queue are destination binding keys; operation inputs must not override them.

Messaging request/reply is outside the P0 NATS runtime and the P1 Kafka/IBM MQ client-provider baseline. Kafka broker provisioning, IBM MQ queue-manager provisioning, broker purge, destructive queue get, and Kafka request/reply require a later explicit Provider Contract decision. A future reusable Provider Contract may introduce request/reply with payload binding, timeout, correlation when required, and an output ref for the reply.

Contract materialization is a framework maturity gate. The public interface contract must be fixed before runtime implementation and test-plan expansion. That interface includes invocation commands, DSL/test definition fields, Env_Profile fields, Provider Instance fields, Provider Contract fields, result/evidence schemas, and support-command boundaries. A contract is not implementation-ready until the corresponding file path exists, declares required fields, version compatibility behavior, unsupported capability behavior, evidence outputs, and failure classification, and is referenced by the AC and test-plan rows that verify it.

Legacy or future operation names such as `call_api`, `execute_sql`, `run_application`, `run_container`, `run_k8s_job`, `run_maven_failsafe`, `read_file`, and `write_file` are not public v0.2 core operations unless a Provider Contract explicitly materializes them. New DSL must use Provider Contract-backed operations.

### 6.4B v0.2 Execution Contract Summary

v0.2 is the full pre-release execution contract. It supersedes the earlier minimum DSL v1 subset while preserving compatibility rules for migrated artifacts.

Required framework-owned artifact families:

| Artifact | Purpose | Must Not Contain |
|---|---|---|
| Test case DSL | Generic validation intent, targets, setup, execute, expected results, verify, evidence, runtime | Raw secrets, topology decisions, approval or waiver state |
| Provider Instance | Define one RP logical runtime target using a Provider Contract shape | Endpoint/topic/DB credential values, unsupported fields, product topology decisions |
| Env_Profile | Where and when to run; resolve Provider Contract binding keys and `runtime_mode` for `local`, `ci`, `sit`, or `preprod`; define dependency substitution/provisioning policy and mock/stub/ephemeral replacement refs when selected | Product topology decisions, raw secrets, silent fallback rules, or binding keys not defined by Provider Contract |
| Suite manifest | Select tests by suite, tag, profile, or test ID | Hidden product/RU inference |
| Provider Contract | Define provider type, allowed runtime modes, allowed operations, allowed input names, binding keys, output refs, evidence outputs, failure codes, and valid Provider Instance shape | Product-specific strategy selection, raw secrets, or inline unsafe commands |
| Provider plugin metadata | Declare provider type, operations, required binding keys, evidence outputs, and safety constraints | Product topology decisions or RP-specific scripts |
| Verify plugin metadata | Declare verify type, target types, and required fields | Business truth approval |
| Result schema | Normalize step results, verify results, evidence refs, labels, timing, and failure classification | Raw secrets |
| Evidence schema | Index retained logs, artifacts, diffs, query results, events, runner reports, fixture logs, and cleanup logs | Raw secrets |

Standard step result:

```yaml
step_result:
  status: passed | failed | error
  outputs: {}
  logs: {}
  files: {}
  metrics:
    duration_ms:
  error:
    code:
    message:
    details:
```

Standard verify result:

```yaml
verify_result:
  status: passed | failed | error
  type:
  actual:
  expected:
  diff:
  evidence_ref:
  metrics:
    duration_ms:
    attempts:
  error:
    code:
    message:
```

Technical failure classifications are `schema_error`, `target_resolution_error`, `fixture_setup_error`, `execution_error`, `verification_failed`, `timeout`, `environment_error`, `secret_resolution_error`, `cleanup_error`, and `framework_error`. Product defect, test data issue, expected outdated, and spec ambiguity remain Agent Skill or later triage-layer classifications.

v0.2 validation must block before execution when:

- `dsl_version` is not `v0.2`.
- Test case ID, source refs, selected profile, targets, execute step IDs, verify IDs, fixture refs, expected-result refs, cleanup refs, parameter refs, evidence refs, or runtime policy are missing or invalid.
- A selected profile is incompatible with the test case.
- A target, Provider Instance, Provider Contract, operation, verify type, Env_Profile provider binding, required binding key, or plugin contract cannot be resolved.
- A DSL operation input key is not allowed by the referenced Provider Contract.
- A DSL or provider instance output ref is not declared by the referenced Provider Contract.
- `file_diff`, DB, event, or normal comparison verify rules are missing required actual, expected, target, query, event, topic/filter, or selector fields.
- Polling timeout or poll interval is not an ISO-8601 duration.
- Raw passwords, tokens, credentials, or connection strings appear in DSL, Env_Profile, result, or evidence.

Current provider contract minimums enforced by the framework verification build:

| Provider Type | Enforced Minimum Fields |
|---|---|
| `shell_command` | `command` binding key, positive timeout, declared output refs |
| `rest_client` | `base_url` binding key, allowed `http_request` operation, allowed request input names, positive timeout, declared output refs |
| `grpc_client` | service/descriptor refs, allowed request input names, positive timeout, declared output refs |
| `kafka` | bootstrap/topic/consumer-group binding keys, allowed publish/observe/payload-match operations, payload input names, non-mutating observation defaults, declared output refs |
| `ibm_mq` | queue-manager/channel/connection/queue/credential binding keys, allowed put/browse/message-exists/payload-match operations, browse-first safety, declared output refs |
| `kafka_messaging` | deprecated compatibility alias for legacy publish/consume artifacts; new provider work must use `kafka` |
| `nats` | connection/subject binding keys, allowed publish/observe/event verification operations, payload input names, bounded observation, declared output refs |
| `jdbc` | connection binding key, isolation key, cleanup strategy, setup/cleanup SQL refs, verification SQL refs, declared output refs |
| `kubernetes_runtime` | namespace/context/API binding keys, readiness operation, deployed version ref, bounded log tail, declared output refs |
| `vm_runtime` | host/health binding keys, command refs for SSH/WinRM when selected, deployed version ref, declared output refs |
| `external_runner` | approval metadata, reason, command/container ref, inputs, outputs, positive timeout, evidence map, safe evidence paths |

Examples in this document must use those fields when they describe current `supported` provider contracts. K8s, VM, external runner, broker provisioning, destructive message consumption, and unsupported messaging options remain `contract_only` or future work unless the provider support matrix marks them `supported` and a release-verifiable sample exists.

Allowed generated `execution_mode` values are `local`, `ci`, `sit`, and `preprod`.

The generated run plan must declare target dependency semantics. Use an empty list for independent targets. A dependency entry must include `target_id` and `required`, and may include `consumes` to identify the upstream output used by this target.

```yaml
target_dependencies:
  downstream_check:
    - target_id: transform_job
      required: true
      consumes:
        - normalized_orders
```

Execution order is derived from this generated target dependency graph. Product-side `dependency_order` may be used only as an Agent Skill input; it is not sufficient for framework execution planning.

When `execution_mode` is `sit` or `preprod`, the generated Env_Profile or run plan must include deployment readiness evidence refs:

```yaml
deployment:
  required: true
  environment: sit
  deployment_ref: cd://release/RP-AR-M1-data-pipeline/build-123
  readiness_ref: readiness/sit-transform-job.yaml
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

Drafting artifacts created after the generic DSL metadata migration must use the same identity, status, revision, `source_refs`, and optional `labels` vocabulary as executable DSL test cases. Skeletons and update proposals are review artifacts, not executable tests; they must not emit legacy-only fields such as `rp_id`, `ac_id`, `artifact_status`, or old `traceability.*` fields.

Skeleton draft shape:

```yaml
dsl_version: v0.2
test_case_id: RP-AR-M1-data-pipeline-TC-001
status: draft_skeleton
revision: 1
labels:
  package: RP-AR-M1-data-pipeline
  runtime_unit: RU-transform-job
source_refs:
  acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
source_fingerprint: <fingerprint>
readiness_gaps:
  - field_path: generated-framework/env_profiles/ci.yaml#providers.transform-job
    reason: execution_context_incomplete
    gap: logical target binding is missing
    owner_action: Run Product Mapping Translation after completing Product/RP/RU context.
```

Update proposal shape:

```yaml
proposal_type: test_case_update
dsl_version: v0.2
test_case_id: RP-AR-M1-data-pipeline-TC-001
status: needs_update
revision: 1
labels:
  package: RP-AR-M1-data-pipeline
source_refs:
  acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
replaces: tests/approved/RP-AR-M1-data-pipeline-TC-001.yaml
source_fingerprint: <fingerprint>
readiness_gaps:
  - field_path: tests/approved
    reason: approved_test_exists
    gap: approved test exists
    owner_action: Review the generated update proposal instead of overwriting the approved test.
```

An approved test case may be replaced only by an explicit update proposal that records the source change and replacement relationship.

## 6.7 Execution-Focused Test Case DSL v0.2

The test case artifact is a package-neutral execution DSL. It describes what reviewed behavior is validated, which targets are involved, what setup data is needed, what operation runs, what outputs are captured, which expected results are used, how verification is performed, what evidence is retained, and what runtime policy applies.

DSL v0.2 is intentionally not a governance workflow. It must not contain `approval_status`, `approved_by`, `approval_required`, `waiver`, `release_gate`, `risk_approval`, or release governance fields. Expected-result review and release approval remain separate artifacts and processes.

Use clear test-case language in new DSL artifacts:

| DSL Section | Purpose |
|---|---|
| `dsl_version` | Select parser and compatibility rules. |
| `test_case_id`, `status`, `revision` | Identify the durable test artifact and revision. `status` is execution lifecycle state, not approval state. |
| `labels` | Optional opaque metadata for reporting, such as product, package, runtime unit, team, or domain. |
| `source_refs` | Link the test to reviewed source artifacts such as acceptance criteria and feature specs. |
| `targets` | Name each application, database, event bus, file store, batch runner, or external boundary used by the test. |
| `data` | Optionally declare reusable reviewed data refs or safe literals. |
| `setup` | Declare provider-backed setup operations for seed data, mock setup, or initial state needed before execution. |
| `execute` | Declare provider-backed operations, target, inputs, and output capture. |
| `verify` | Declare provider-backed checks or framework assertions over captured outputs, state, events, or files. |
| `evidence` | Declare evidence refs that must be retained from execution or verification. |
| `runtime` | Declare bounded timeout and retry policy. |

### 6.7.0 Requirement Rules

The v0.2 contract separates stable top-level structure from execution-required content:

- Always required: `dsl_version`, `test_case_id`, `status`, `revision`, `targets`, `execute`, `verify`, `evidence`, and `runtime`. `source_refs` is optional traceability metadata and is not part of runtime target resolution.
- Optional: `labels` and `compatible_profiles`. `labels` are report metadata only; `compatible_profiles` restricts the test to named Env_Profiles.
- Optional: `data`. Use it only when reusable reviewed data refs or safe literals make operation inputs clearer.
- Conditionally required: `setup.operations` when the test needs precondition data, state mutation, mock setup, seed data, or cleanup.
- Conditionally required: operation-level `inputs` when setup, execute, verify, or cleanup passes data to a provider. Input map keys identify Provider Contract input names and must be allowed by the referenced Provider Contract. Input values provide either `ref` or safe literal `value`.
- Required when referenced: `execute.operations[].outputs`, `verify.checks[].selector` for structured output checks, `verify.checks[].target` plus `operation` for provider-backed state or event checks, `verify.checks[].options` for polling/tolerance/normalization, and cleanup operations for mutating setup.
- Prohibited in DSL: `scenario`, provider implementation settings, secrets, endpoint URLs, topics, namespaces, connection strings, SQL bodies, shell scripts, release gates, waivers, risk approvals, and approval workflow state.

The v0.2 semantic model is:

```yaml
dsl_version: v0.2
test_case_id: RP-FWK-SAMPLE-TC-001
status: draft_executable
revision: 1

labels:
  product: FRAMEWORK-SAMPLE
  package: RP-FWK-SAMPLE
  runtime_unit: RU-framework-sample-target

source_refs:
  acceptance_criteria: acceptance_criteria.md#RP-FWK-SAMPLE-AC-001
  feature_spec: rp_feature_spec.md#F001

compatible_profiles:
  - ci

targets:
  framework_sample_pipeline:
    provider_id: transform-job
  order_database:
    provider_id: order-db

data:
  orders_seed_ref:
    ref: parameter-sets/orders_regression_cases.yaml#orders_case.orders_seed_ref
  orders_cleanup_ref:
    ref: fixtures/db/orders_cleanup.yaml
  normalized_orders:
    ref: expected-results/RP-FWK-SAMPLE-ER-001.yaml

setup:
  operations:
    - id: orders_seed
      target: order_database
      operation: db_seed
      inputs:
        sql_ref:
          ref: ${data.orders_seed_ref}
      cleanup:
        operation: db_cleanup
        inputs:
          sql_ref:
            ref: ${data.orders_cleanup_ref}

execute:
  operations:
    - id: run_pipeline
      operation: run_batch
      target: framework_sample_pipeline
      inputs:
        runner.args.ordersSeed:
          ref: ${setup.orders_seed.outputs.affected_rows}
      outputs:
        exit_code: exit_code
        normalized_orders_file: output_files
        execution_log: stdout

verify:
  checks:
    - id: verify_exit_code
      type: equals
      actual:
        ref: ${execute.run_pipeline.outputs.exit_code}
      expected:
        value: 0
    - id: verify_normalized_orders_file
      type: file_diff
      actual:
        ref: ${execute.run_pipeline.outputs.normalized_orders_file}
      expected:
        ref: ${data.normalized_orders}
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

| Replace Old Term | Use v0.2 Term | Reason |
|---|---|---|
| `execution_target` | `targets` plus `execute.operations[].target` | One test may involve multiple targets. |
| `target_ru_id` | `target` | Tests should refer to logical target IDs, not framework-internal RU fields. |
| `action: call_ru` | `operation: run_batch`, `execute_command`, `http_request`, `unary_call`, `db_seed`, `db_query`, `kafka_publish`, `kafka_observe`, `mq_put`, `mq_browse`, `nats_publish`, `nats_observe`, or another operation declared by the referenced Provider Contract | Operation names should explain what the test does and must be contract-backed. |
| `package_inputs` | `data` plus operation `inputs` | Data sources and binding points are separated. |
| `oracles` | `data` plus `verify.checks` expected refs | v0.2 uses expected data refs and explicit checks instead of a heavy oracle framework. |
| `steps` | `execute.operations` | The section is executable behavior, not an abstract workflow step. |
| `assertions` | `verify.checks` | The section defines pass/fail checks. |
| `evidence_required` | `evidence.required` | Evidence should reference real execution or verification outputs. |
| `policy` | `runtime` | Runtime policy is timeout, retry, and bounded execution behavior in v0.2. |

Legacy v1 artifacts may still be read through an explicit compatibility path during migration, but new generated drafts and new documentation examples must use the v0.2 execution-focused shape above.

### 6.7.2 Targets and Operations

`targets` is a name-keyed map. Each target must declare `provider_id`. `provider_id` resolves to a Provider Instance. The Provider Instance declares `provider_type`, which selects a Provider Contract. The active Env_Profile selected by CLI or suite manifest supplies `providers.<provider_id>.bindings` values used at runtime. A DSL target must not contain URLs, topics, namespaces, DB connection strings, or credentials.

`setup.operations`, `execute.operations`, provider-backed `verify.checks`, and `cleanup.operations` must be allowed by the referenced Provider Contract. Core v0.2 operations include:

- `run_batch`
- `execute_command`
- `http_request`
- `unary_call`
- `db_seed`
- `db_cleanup`
- `db_query`
- `db_record_exists`
- `kafka_publish`
- `kafka_observe`
- `kafka_payload_match`
- `mq_put`
- `mq_browse`
- `mq_message_exists`
- `mq_payload_match`
- `nats_publish`
- `nats_observe`
- `check_deployment_ready`
- `check_pod_ready`
- `get_logs`
- `wait_rollout`
- `exec_command`
- `check_host_ready`
- `run_command`
- `collect_file`
- `collect_logs`
- `check_process`
- `run`
- `run_and_collect`
- `check_status`
- `start_mock`
- `connect_mock`
- `load_stubs`
- `verify_requests`

Every execute operation must declare an `outputs` map when later verification or evidence depends on the result. Normal actions such as API calls, SQL queries, batch runs, or command execution belong in `execute`, not framework assertion checks.

Every output ref in DSL must be declared by the referenced Provider Contract. Every operation `inputs` key must be allowed by the referenced Provider Contract. v0.2 supports one or more executable `execute.operations[]` items when each operation has a unique ID, declared operation, target, inputs, outputs, ordering semantics, and evidence refs. Ambiguous multi-step execution must block before provider dispatch.

### 6.7.3 Setup, Inputs, and Cleanup

Use `setup.operations` for precondition data or state that must exist before the main execution runs, such as database seeds, file seeds, mock setup, queue seed messages, or initial state. A setup operation that changes system state must declare bounded cleanup through an inline `cleanup` operation or a matching `cleanup.operations[]` item.

Cleanup authority and precedence:

- DSL `setup.operations[].scope` owns lifecycle scope. Supported v0.2 values are `test_case`, `parameter_case`, and `batch`.
- DSL `setup.operations[].cleanup_policy` owns when cleanup is required. Supported v0.2 values are `always`, `on_success`, `on_failure`, and `manual_blocked`.
- Provider Contract `cleanup_strategy` owns how cleanup is performed for the selected technology, such as `by_test_run_id`, `by_parameter_case_id`, `drain`, or `delete_files_under_workspace`.
- The framework resolves cleanup by first reading DSL scope/policy, then validating that the selected Provider Contract has a compatible cleanup strategy and bounded evidence output.
- If a mutating fixture omits required cleanup, declares an unsafe scope, or selects an incompatible provider cleanup strategy, execution blocks before dispatch with `fixture_setup_error` or `cleanup_error`.

Use operation `inputs` for runtime inputs passed to setup, execute, provider-backed verify, or cleanup operations, such as request payloads, file refs, fixture refs, event payloads, SQL bind variables, or command args. Provider-specific connection strings, credentials, endpoints, SQL bodies, queue client settings, and shell scripts do not belong in the DSL body; they belong in validated Provider Contracts, Provider Instances, Env_Profiles, or referenced files.

### 6.7.3A Parameterization

v0.2 parameterization and operation binding use reviewed source references plus provider input names:

```yaml
execute:
  operations:
    - id: submit_order
      target: order_api
      operation: http_request
      inputs:
        request.body:
          ref: parameter-sets/orders_regression_cases.yaml#happy_path.request
```

The referenced parameter set is a checked-in, reviewed RP-local artifact under `parameter-sets/` or a generated artifact that resolves to that folder before execution. It may contain one or more named cases, but the cases are not embedded in the DSL body. Each input key must match `allowed_inputs` from the Provider Contract selected by the target Provider Instance. Each resolved parameter case produces a separate run ID and run evidence record with `parameter_case_id`. Coverage still counts the traced AC once per selected batch even when multiple parameter cases pass.

Legacy parameter strategy fields, inline case lists, parameter expressions, old parameter binding arrays, dynamic data selection, combinatorial case generation, runtime-created cases, secrets, and provider connection details do not belong in the next v0.2 DSL shape. Existing `strategy: explicit_cases` artifacts may be read only through a legacy compatibility path until migrated to operation-level `inputs`.

### 6.7.4 Expected Results and Verification

Expected refs point to simple truth artifacts: expected files, expected JSON/YAML, expected DB state snapshots, expected response payloads, schemas, or contracts. Do not duplicate the same expected artifact under both new refs and legacy `oracles`.

Core `verify.checks[].type` values for v0.2 are:

| Group | Verify Types |
|---|---|
| Basic | `equals`, `not_equals`, `exists`, `not_exists`, `contains`, `regex_match` |
| Response and structured output | `json_path_equals`, `json_path_absent`, `response_status_equals` |
| Structure | `schema_match` |
| Compatibility / optional | `schema_matches`, `json_schema_matches`, `contract_match`, `contract_matches` |
| Collection | `list_size_equals`, `unordered_list_equals`, `subset_match` |
| Numeric / Time | `numeric_tolerance`, `greater_than`, `less_than`, `between`, `timestamp_tolerance` |
| File | `file_exists`, `file_not_empty`, `file_diff`, `csv_row_count_equals`, `csv_diff` |
| State | `db_record_exists`, `db_field_equals`, `db_row_count_equals` |
| Event | `event_published`, `event_payload_match`, `event_not_published` |
| Plugin | `custom_verify` |

For captured-output comparison checks, each `verify` item must define explicit `actual` and `expected`. `actual` identifies the captured output or evidence source. Use `selector` when comparing part of a structured actual result. New DSL artifacts must not overload `actual` with a JSONPath expression when a captured output ref is required.

Some verify types consume provider metadata instead of a captured output body. `response_status_equals` may declare only `expected` when the selected request/response provider writes HTTP status metadata into provider evidence. It shall declare `actual` plus `selector` only when the status is read from a structured captured output.

`selector` is the canonical v0.2 field for JSON/YAML path selection. `path` and `json_path` are accepted only as compatibility aliases while older artifacts migrate. New generator output and new checked-in RP tests must use `selector`.

Required verify source rules:

| Verify Type | Required Fields |
|---|---|
| `json_path_equals` | `actual`, `selector`, `expected` |
| `json_path_absent` | `actual`, `selector` |
| `numeric_tolerance` over structured output | `actual`, `selector`, `expected`, and tolerance in `options.tolerance`, `tolerance`, or `epsilon` |
| `response_status_equals` | `expected`; `actual` plus `selector` when status is read from a structured captured output, or provider HTTP status metadata when supplied by the request/response provider |

```yaml
verify:
  checks:
    - id: verify_http_status
      type: response_status_equals
      expected: 202
    - id: verify_response_status
      type: equals
      actual:
        ref: ${execute.create_order.outputs.response_body}
      selector: $.status
      expected: CREATED
    - id: verify_response_body_status
      type: json_path_equals
      actual:
        ref: ${execute.create_order.outputs.response_body}
      selector: $.status
      expected: CREATED
```

Use `db_record_exists` when verifying persisted database state:

```yaml
verify:
  checks:
    - id: verify_order_record_created
      type: db_record_exists
      target: order_database
      query:
        ref: queries/order_exists.sql
        params:
          order_id: ${setup.orders_seed.outputs.order_id}
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
  checks:
    - id: verify_order_normalized_event_published
      type: event_published
      target: order_event_bus
      event:
        topic: order.normalized
        key: ${setup.orders_seed.outputs.order_id}
      expected:
        match:
          $.order_id: ${setup.orders_seed.outputs.order_id}
          $.event_type: ORDER_NORMALIZED
          $.status: NORMALIZED
      options:
        timeout: PT30S
        poll_interval: PT5S
        consume_from: test_start_time
```

### 6.7.5 Runtime, Evidence, and Status

`runtime` stays bounded in v0.2:

```yaml
runtime:
  timeout: PT10M
  retry:
    max_attempts: 0
```

`evidence.required` must reference concrete execution or verification outputs, for example `${execute.run_pipeline.outputs.execution_log}` or `${verify.verify_order_record_created.result}`.

Allowed DSL execution statuses are `draft_skeleton`, `draft_executable`, `active`, `needs_update`, and `retired`. These are not approval states and must not be used as release gates.

An execution-focused DSL v0.2 artifact is execution-eligible only when it is stored under the configured `tests/approved/` lifecycle location and has an allowed executable status such as `active`. Expected-result approval remains on expected-result artifacts, not on the DSL status field.

### 6.7.6 Run and Report Consumption

The same execution-focused DSL v0.2 artifact must be consumed consistently by validation, binding, execution evidence, result generation, and coverage reporting:

- `run` may copy reviewed source metadata from `source_refs` and opaque report labels from `labels` or `traceability_map.yaml`, but it must not use them for runtime target resolution or provider dispatch.
- `run` must derive provider_id, env_profile_id, fixture type, operation, expected-result reader, verify type, evidence refs, timeout, retry, and selected Env_Profile from v0.2 sections.
- `run` must write normalized evidence fields needed by reporting, including source refs, labels when provided, test case ID, batch ID, run ID, parameter case ID when applicable, provider_id, provider_type, env_profile_id, Provider Contract path, Provider Instance path, final status, failure classification, resolved operation result, and actual-output refs.
- `run` must emit a standard result JSON for each test/parameter case.
- `report --batch-id` must calculate coverage from batch/run evidence and approved v0.2 test artifacts without requiring legacy-only fields.
- A v0.2 test that passes execution but cannot be included in a review-ready batch report is not complete F007/F008 support.

### 6.7.7 Compatibility and Migration

The current implementation still contains legacy field readers in some framework modules. The migration target is v0.2:

| Legacy Field | v0.2 Execution-Focused Field |
|---|---|
| `rp_id` | `labels.package` or `traceability_map.yaml` |
| `ru_id` / `runtime_unit_id` | `labels.runtime_unit` or `traceability_map.yaml` |
| `ac_id` | `source_refs.acceptance_criteria` or `traceability_map.yaml` |
| `traceability.package_id` | `labels.package` |
| `traceability.acceptance_criteria_id` | `source_refs.acceptance_criteria` |
| `traceability.source` | `source_refs.acceptance_criteria` or a specific source ref |
| `execution_target.runner` | `targets.<target_id>.provider_id` plus generated Provider Instance |
| `execution_target.environment_ref` | selected Env_Profile plus `targets.<target_id>.provider_id` |
| `fixture.setup` / `fixture.cleanup` | `setup.operations[]` and `cleanup.operations[]`, with inline cleanup allowed on mutating setup operations |
| `package_inputs.inputs.<name>` | `data.<name>` plus operation `inputs.<input_key>.ref` |
| `steps[]` | `execute.operations[]` |
| `oracles` / `expected.ref` | `data.<name>` or check-level `expected_ref` |
| `assertions[]` | `verify.checks[]` |
| `evidence_required` | `evidence.required` |
| `policy.timeout_seconds` / `policy.retry` | `runtime.timeout` / `runtime.retry.max_attempts` |

Implementation work after this documentation baseline must first add validation and execution support for this execution-focused DSL shape, while keeping legacy samples readable until the sample fixture is migrated.

Implementation sequencing rule:

- First implement DSL v0.2 parsing and validation for the execution-focused field set.
- Then update generation so executable drafts emit execution-focused fields, and skeleton/update proposal artifacts emit only v0.2 identity/status/revision, `source_refs`, optional `labels`, source fingerprint, replacement link when relevant, and readiness gaps.
- Then keep legacy artifacts readable through compatibility behavior until migrated.
- Then prove one active execution-focused DSL v0.2 artifact can pass `run`, emit standard result JSON, and pass `report --batch-id` with review-ready coverage.
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

v0.2 expected-result types are:

- `literal`
- `file`
- `json`
- `yaml`
- `csv`
- `schema`
- `db_snapshot`
- `event_payload`

Rules:

- `draft` artifacts must include source references and unresolved gaps, if any.
- `blocked` artifacts must include `blocked_reason`.
- `approved_for_regression` artifacts must include `approved_by`, `approved_at`, and `approval_ref`.
- Only `approved_for_regression` expected results may be used as regression truth unless an explicit execution policy allows otherwise.

## 6.9 Provider Contract, Provider Instance, and Env_Profile

Each executable provider must resolve through a validated Provider Contract, Provider Instance, and Env_Profile. The Provider Contract defines reusable rules, binding key schema, and bindable outputs. The Provider Instance defines a logical provider target. The Env_Profile supplies environment-specific runtime modes and binding key values. The DSL only references `provider_id`, operations, `inputs`, output refs, verify refs, and evidence refs; active Env_Profile selection comes from CLI or suite manifest.

```yaml
provider_contract_version: v0.2
provider_type: shell_command
runtime_modes: [native, mock, stub]
binding_keys:
  command:
    required: true
    value_type: string
    allowed_value_kinds: [value, secret_ref]
safety:
  rules:
    access_policy_required_when_operations_used: [run_batch]
    required_fields:
      - safety.access_policy.allowed_commands
      - safety.access_policy.allow_shell
valid_provider_instance_shape:
  required_fields: [provider_id, provider_type, runtime_modes]
  allowed_fields: [provider_id, provider_type, runtime_modes, defaults, readiness, operations, evidence, failure_mapping, safety, labels]
operations:
  run_batch:
    allowed_inputs:
      - runner.args.*
      - runner.env.*
      - runner.timeout
      - runner.input_file
    required_inputs: []
    output_refs:
      - exit_code
      - stdout
      - stderr
      - output_files
      - duration_ms
evidence:
  outputs: [command, stdout, stderr, output_files, exit_code, timing, error]
failure_mapping:
  allowed_codes: [PROVIDER_UNAVAILABLE, PROVIDER_TIMEOUT, OPERATION_FAILED, EVIDENCE_INVALID]
---
provider_instance_version: v0.2
provider_id: transform-job
provider_type: shell_command
runtime_modes: [native]
safety:
  access_policy:
    allow_shell: false
    allowed_commands:
      - command://run-transform-job
    blocked_args:
      - rm
      - shutdown
      - reboot
operations:
  run_batch:
    inputs:
      business_date:
        input: runner.args.businessDate
    outputs:
      exit_code: exit_code
      actual_output: output_files
      execution_log: stdout
---
env_profile_id: ci
execution_mode: ci
providers:
  transform-job:
    runtime_mode: native
    bindings:
      command: command://run-transform-job
```

Provider runtime rules:

- Resolution order is DSL target `provider_id` + selected Env_Profile, Provider Instance, Provider Contract by `provider_type`, then Env_Profile `providers.<provider_id>.bindings`. Suite manifests select tests and may select the active Env_Profile, but must not override provider fields.
- Executable Provider Contracts must declare `provider_type`; heuristic inference is diagnostic only and must not silently choose a runtime.
- Provider capability registry status must be checked before dispatch. Unsupported, ambiguous, unsafe, or provider-safety-unapproved command-capable providers fail before execution.
- Dispatch uses the next v0.2 DSL fields and generated artifact fields: `targets.<target_id>.provider_id`, selected Env_Profile, `setup.operations[].operation`, `execute.operations[].operation`, `verify.checks[].operation` when provider-backed, `cleanup.operations[].operation`, operation `inputs`, `data`, `verify.checks`, and `evidence.required[]`.
- The framework supplies resolved input paths and run workspace paths.
- Providers write actual outputs, observation results, and cleanup results under the run evidence directory.
- Provider Instances cannot introduce fields, operations, input names, output refs, evidence outputs, or failure codes that are not allowed by the Provider Contract.
- Env_Profiles must supply all required binding keys for the selected provider_id.
- Env_Profile `providers` map keys must be Provider Instance `provider_id` values.
- Env_Profile `bindings` must match Provider Contract `binding_keys`; invalid binding keys, value kinds, enum values, and generated refs block before provider dispatch.
- `generated_ref` values must target a producing Provider Contract `bindable_outputs` entry or a selected Env_Profile `dependency_provisioning_policy.generated_outputs` entry; undeclared project-side generated refs must be materialized before invoking the framework.
- Messaging actions that declare `requires_correlation: true` must also declare `correlation_id`, `correlation_id_ref`, or `correlation_key` before publish, request/reply, consume, observe, or cleanup dispatch.
- Messaging request/reply actions are not part of the P0 NATS runtime; they remain future reusable Provider Contract scope.
- Messaging cleanup actions must declare `mode: cleanup`, `cleanup_strategy: drain`, and a positive bounded `max_count`. Current cleanup is bounded drain behavior for test-owned topics, subjects, or consumer groups, not broker administrator purge.
- Non-success exit codes fail the test case and must preserve stdout, stderr, exit code, and timeout state.
- Timeouts fail the test case and must trigger fixture cleanup.
- Providers must not perform product deployment in v0.2.
- Provider Contracts, Provider Instances, and Env_Profiles must reference secrets, SQL, payloads, and environment resources by reference, not inline sensitive values or package-specific implementation bodies.
- `external_runner` Provider Contracts require approval metadata, reason, bounded timeout, declared inputs/outputs, and evidence artifact map before invocation.
- Unsupported provider actions or contract fields must fail before execution with owner action.

## 6.10 Execution Evidence

One suite execution produces one batch summary, one run evidence context, and per-test evidence entries for every approved test case executed in that run. Product/RP/RU labels may be copied from `traceability_map.yaml` for reporting, but runtime decisions use generated target IDs, Provider Instances, Provider Contracts, and Env_Profiles.

v0.2 evidence types are `execution_log`, `actual_artifact`, `expected_artifact`, `assertion_diff`, `db_query_result`, `event_payload`, `http_request_response`, `screenshot`, `runner_report`, `fixture_log`, and `cleanup_log`. Evidence collection must copy or reference required artifacts, mask secrets, index evidence, and attach evidence refs to the standard result JSON.

Batch evidence:

```yaml
batch_id: BATCH-20260626-001
package_id: RP-AR-M1-data-pipeline
status: passed
execution_mode: ci
env_profile_id: ci
env_profile_ref: generated-framework/env_profiles/ci.yaml
started_at: 2026-06-26T10:00:00+08:00
finished_at: 2026-06-26T10:03:00+08:00
runs:
  - run_id: RUN-20260626-001
    test_case_id: RP-AR-M1-data-pipeline-TC-001
    source_refs:
      acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
    status: passed
  - run_id: RUN-20260626-002
    test_case_id: RP-AR-M1-data-pipeline-TC-002
    source_refs:
      acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-002
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
package_id: RP-AR-M1-data-pipeline
test_case_id: RP-AR-M1-data-pipeline-TC-001
parameter_case_id: valid_order_001
source_refs:
  acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001
status: passed
execution_mode: ci
env_profile_id: ci
env_profile_ref: generated-framework/env_profiles/ci.yaml
deployment_refs: []
providers:
  - provider_id: transform-job
    provider_type: shell_command
    env_profile_id: ci
    provider_contract_ref: docs/02-architecture/contracts/provider-contracts/shell_command.yaml
    provider_instance_ref: generated-framework/provider_instances/transform-job.yaml
started_at: 2026-06-26T10:00:00+08:00
finished_at: 2026-06-26T10:01:00+08:00
operation_results:
  - step_id: run_pipeline
    provider_id: transform-job
    provider_type: shell_command
    env_profile_id: ci
    operation: run_batch
    status: passed
    resolved_operation_result:
      exit_code: 0
      actual_output_ref: evidence/runs/RUN-20260626-001/actual/orders_normalized.csv
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
failure:
  classification: null
  message: null
  details: null
```

Standard result JSON shape:

```yaml
test_result:
  framework_version: 0.2.6
  dsl_version: v0.2
  test_case_id: RP-AR-M1-data-pipeline-TC-001
  parameter_case_id: valid_order_001
  status: passed
  profile: ci
  environment_id: ci
  labels:
    package: RP-AR-M1-data-pipeline
  steps:
    - id: run_subject
      operation: run_batch
      target: subject
      provider_id: transform-job
      provider_type: shell_command
      profile: ci
      status: passed
      resolved_operation_result:
        exit_code: 0
      outputs:
        execution_log: evidence/logs/run_subject.log
  verify_results:
    - id: verify_db_record
      type: db_record_exists
      status: passed
      attempts: 3
      evidence_ref: evidence/verify/db_record.json
  evidence:
    - type: execution_log
      ref: evidence/logs/run_subject.log
  failure:
    classification: null
    message: null
    details: null
```

Run evidence is stored under:

```text
docs/08-release/release-packages/<rp_id>/evidence/runs/<run_id>/
```

## 6.11 Readiness Report

Readiness reports for Product Repo readiness may include product mapping gaps. Runtime readiness reports shall include generated artifact gaps.

```yaml
rp_id: RP-AR-M1-data-pipeline
status: blocked
checked_at: 2026-06-26T10:00:00+08:00
checks:
  - check_id: RP_ARTIFACTS_PRESENT
    status: failed
    missing:
      - generated-framework/run_plan.yaml
      - generated-framework/env_profiles/ci.yaml
      - generated-framework/provider_instances/providers.yaml
    owner_action: run the product mapping Agent Skill after completing Product/RP/RU mapping inputs
next_required_step: generate framework-readable artifacts before execution
```
