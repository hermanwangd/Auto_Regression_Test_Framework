# Auto Regression Test Framework User Guide

## 1. Purpose

This guide explains how product owners, release package owners, release unit developers, CI/CD, and agents use the Auto Regression Test Framework.

The framework is product-agnostic. It does not decide product topology, RP/RU ownership, acceptance criteria, expected business behavior, or release readiness. Owners provide those inputs. The framework provides stable public interfaces for test definition, runtime configuration, execution, and evidence.

## 2. Public Interface Model

The user-facing public interfaces are:

| Interface | Purpose |
| --- | --- |
| DSL Test Case | Defines executable regression behavior. |
| Provider Contract | Framework-owned contract for a provider type, allowed operations, bindings, outputs, evidence, failure codes, and valid provider instance shape. |
| Provider Instance | Defines an RP logical runtime target using a built-in or explicitly declared custom provider contract. |
| Environment Binding | Supplies profile-specific values such as URLs, topics, DB strings, namespaces, and secret refs. |
| Execution Profile | Defines what may run in `local`, `ci`, `sit`, or `preprod`. |
| CLI | Initializes, validates, executes, and reports. |
| Evidence Contract | Defines reviewable outputs for release readiness. |

Resolution flow:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Framework built-in Provider Contract catalog
  -> selected profile
  -> Environment Binding
```

There is no additional user-facing runtime interface beyond provider instances, environment bindings, execution profiles, DSL, CLI, evidence, and framework-owned provider contracts. RP/suite repositories do not copy built-in Provider Contracts by default.

## 3. Role Responsibilities

| Role | Responsibility |
| --- | --- |
| Product Owner / Product Developer | Defines product release scope, product-level E2E AC, and product-to-RP mapping. |
| RP Owner / RP Developer | Defines RP feature spec, RP AC, RP/RU mapping, approved tests, expected results, provider instances, and environment bindings. |
| RU Developer | Provides APIs, events, jobs, readiness signals, fixtures, cleanup support, and implementation fixes. |
| Agent | Initializes structure, drafts tests, validates readiness, runs tests, and reports gaps/evidence. |

Agents must not invent AC, expected results, RP/RU mapping, or release decisions.

## 4. RP Package Structure

```text
docs/08-release/release-packages/<RP-ID>/
  package.yaml
  rp_feature_spec.md
  rp_ru_mapping.yaml
  architecture.md
  acceptance_criteria.md
  tests/
    draft/
    approved/
  expected-results/
    draft/
    approved/
  provider-instances/
  environment-bindings/
  execution-profiles/
  custom-provider-contracts/   # optional, only for approved custom providers or pinned contract snapshots
  traceability.md
  evidence_index.md
```

Approved tests and expected results are versioned assets. Do not regenerate or overwrite them on every run.

Provider instance and environment authoring folders are owner/Agent Skill working areas. Runtime execution consumes canonical generated artifacts under `generated-framework/provider_instances/`, `generated-framework/environment_bindings/`, and `generated-framework/execution_profiles/`. Built-in Provider Contracts are resolved from the framework catalog by `provider_type`. Suite-local Provider Contracts are optional and only valid for approved custom providers or explicit contract snapshot pinning.

## 5. End-to-End Workflow

```text
init-product-repo
  -> init-rp
  -> check-rp
  -> generate-tests
  -> draft-expected-results
  -> validate
  -> run --dry-run
  -> run
  -> report
```

`init-product-repo`, `init-rp`, `check-rp`, `generate-tests`, and `draft-expected-results` are Product Repo / Phase 2 Agent Skill support steps. The v0.2 runtime public interface is proven by `validate`, `run --dry-run`, `run`, and `report`.

Typical commands:

```bash
regress init-product-repo --root .

regress init-rp \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --package-type service

regress check-rp \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --strict-schema \
  --include-ac-readiness \
  --include-expected-results

regress validate \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --env ci \
  --suite smoke \
  --format yaml
```

During framework development, keep Maven memory bounded:

```bash
MAVEN_OPTS='-Xmx1024m' ./mvnw verify
```

## 6. DSL Test Case

The DSL defines:

- Source truth: feature spec, AC, expected result.
- Runtime target: provider instance. The active profile is selected by CLI or suite manifest.
- Test data: `ref` and `bind_as`.
- Setup and cleanup.
- Execution action.
- Verification/oracle.
- Required evidence.

Example:

```yaml
dsl_version: v0.2
test_case_id: RP-PAYMENT-001-TC-001
title: Submit valid payment request
status: active
revision: 1

source_refs:
  acceptance_criteria: acceptance_criteria.md#AC001
  expected_result: expected-results/approved/ER001.yaml

labels:
  rp_id: RP-PAYMENT-001
  feature_id: F001
  tags: [smoke, happy-path]

compatible_profiles: [ci, sit]

targets:
  payment_api:
    provider_id: payment-api
  payment_db:
    provider_id: payment-db

data_binding:
  input_data:
    request_payload:
      ref: fixtures/payment/valid_request.json
    submit_payment_method:
      ref: config/http/post.yaml
    submit_payment_path:
      ref: config/http/payments_path.yaml
  setup_data:
    customer:
      ref: fixtures/payment/seed_customer.sql
  cleanup_data:
    customer:
      ref: fixtures/payment/cleanup_customer.sql

parameters:
  - name: request_payload
    ref: ${data.input_data.request_payload}
    bind_as: request.body

setup:
  fixtures:
    seed_customer:
      type: db_seed
      target: payment_db
      operation: db_seed
      parameters:
        - name: script
          ref: ${data.setup_data.customer}
          bind_as: sql_ref
      cleanup_ref: ${data.cleanup_data.customer}

execute:
  - id: submit_payment
    target: payment_api
    operation: http_request
    parameters:
      - name: method
        ref: ${data.input_data.submit_payment_method}
        bind_as: request.method
      - name: path
        ref: ${data.input_data.submit_payment_path}
        bind_as: request.path
      - name: body
        ref: parameters.request_payload
        bind_as: request.body
    outputs:
      status: response.status
      headers: response.headers
      body: response.body
      duration_ms: response.duration_ms

verify:
  - id: payment_status_is_accepted
    type: json_path_equals
    actual:
      ref: ${execute.submit_payment.outputs.body}
    selector: $.status
    expected: ACCEPTED

cleanup:
  fixtures:
    cleanup_customer:
      target: payment_db
      operation: db_cleanup
      parameters:
        - name: script
          ref: ${data.cleanup_data.customer}
          bind_as: sql_ref

evidence:
  required:
    - ${execute.submit_payment.outputs.status}
    - ${execute.submit_payment.outputs.body}
    - ${verify.payment_status_is_accepted.result}

runtime:
  timeout: PT5M
  retry:
    max_attempts: 0
```

## 7. Data Binding

Use `data_binding` when a test needs reviewed input, setup, cleanup, or expected-result artifacts that are reused by setup, execute, cleanup, or verify.

The framework resolves `${data.<category>.<name>}` to the matching entry's `ref`. Allowed v0.2 categories are `input_data`, `setup_data`, `cleanup_data`, and `expect_data`. Legacy categories such as `datasets`, `fixtures`, `expected_results`, `db_seed`, `db_cleanup`, and `mock_stubs` are prohibited as `data_binding` category keys. This prohibition does not remove lifecycle sections such as `setup.fixtures` or Provider Contract operations such as `db_seed` and `db_cleanup`.

```yaml
data_binding:
  input_data:
    request_payload:
      ref: fixtures/payment/valid_request.json
  setup_data:
    customer:
      ref: fixtures/payment/seed_customer.sql
    payment_gateway:
      ref: stubs/payment-gateway/mappings/
  cleanup_data:
    customer:
      ref: fixtures/payment/cleanup_customer.sql
  expect_data:
    payment_response:
      ref: expected-results/approved/ER001.yaml
```

`data_binding` is not an Environment Binding substitute. It must not contain endpoint URLs, JDBC URLs, broker URLs, namespaces, credentials, or raw secrets.

Use `ref` for where operation data comes from and `bind_as` for where the provider receives it.

Common `ref` values:

```yaml
ref: fixtures/payment/valid_request.json
ref: expected-results/approved/ER001.yaml
ref: ${data.input_data.request_payload}
ref: ${data.setup_data.customer}
ref: parameters.customer_id
ref: ${execute.submit_payment.outputs.body}
```

Common `bind_as` values:

```yaml
bind_as: request.body
bind_as: request.headers.X-Correlation-ID
bind_as: request.path
bind_as: request.query.customerId
bind_as: grpc.message
bind_as: message.topic
bind_as: message.subject
bind_as: message.payload
bind_as: sql_ref
bind_as: query_ref
bind_as: k8s.deployment
bind_as: vm.command
bind_as: runner.args.businessDate
```

A DSL test case is invalid if it uses a `bind_as` that is not allowed by the Provider Contract resolved from the target Provider Instance `provider_type`.

Runtime connection and authentication values belong to provider instances and environment bindings, not test-case data binding. DSL test cases must not bind `secret.*` directly. Runtime endpoints, tokens, DB credentials, broker credentials, kubeconfig, SSH keys, and runner credentials must be supplied through `provider-instances/` and `environment-bindings/`.

Authentication headers such as `Authorization` should be injected by provider configuration. Test cases may bind ordinary request headers, such as correlation IDs, only when the resolved Provider Contract allows `request.headers.*`.

## 8. DSL Verify / Oracle Model

The `verify` section defines how the framework decides whether an executed test passes, fails, is blocked, or has an execution error.

`execute` produces observable outputs. `verify` compares those outputs against literals, approved expected-result artifacts, schemas, database state, messages, files, logs, or runner results.

The framework must not invent expected business behavior. Expected values must come from:

- Literal values in the test case for simple technical assertions.
- `expected-results/approved/` for owner-approved business results.
- AC-linked expected behavior.
- Provider outputs from previous `setup` or `execute` steps.

### 8.1 Verify Item Shape

Each verify item should use a stable `id`, an assertion `type`, an `actual` source, and either an inline expected value or an approved expected-result reference.

```yaml
verify:
  - id: payment_status_is_accepted
    type: json_path_equals
    actual:
      ref: ${execute.submit_payment.outputs.body}
    selector: $.status
    expected: ACCEPTED
    severity: blocking
    evidence:
      capture:
        - actual
        - expected
        - assertion_result
```

Common fields:

| Field | Required | Purpose |
| --- | --- | --- |
| `id` | Yes | Stable assertion identity. |
| `type` | Yes | Assertion/oracle type. |
| `actual` | Yes | Runtime output to verify. |
| `expected` | Usually | Inline expected value for simple checks. |
| `expected_ref` | Optional | Approved expected-result artifact. |
| `selector` | Optional | JSONPath, XPath, SQL column, message field, or output field selector. |
| `match` | Optional | Matching rules for messages, files, or structured outputs. |
| `tolerance` | Optional | Numeric or time tolerance. |
| `options` | Optional | Polling, timeout, tolerance, or eventual assertion settings. |
| `severity` | Optional | `blocking`, `warning`, or `diagnostic`. |
| `evidence` | Optional | Assertion-specific evidence capture. |

### 8.2 Actual and Expected Rules

`actual.ref` must reference observable output from setup, execute, provider evidence, or runtime evidence.

Examples:

```yaml
actual:
  ref: ${execute.submit_payment.outputs.status}

actual:
  ref: ${execute.submit_payment.outputs.body}

actual:
  ref: ${execute.publish_event.outputs.offset}

actual:
  ref: ${setup.fixtures.seed_customer.outputs.affected_rows}
```

Expected values may be inline for simple technical checks:

```yaml
expected: 200
```

Business expected behavior should use approved expected-result artifacts:

```yaml
expected_ref: expected-results/approved/payment_accepted_event.yaml
```

Rules:

- Use `expected` for small technical constants such as status code, count, or boolean readiness.
- Use `expected_ref` for owner-approved business output, event payloads, DB state, files, or complex JSON.
- `expected_ref` must point to an approved artifact for release-readiness tests.
- `actual.ref` must resolve to an output ref allowed by the resolved Provider Contract or to a framework-generated evidence ref.

### 8.3 Verify Contract Catalog

The following verify types define the minimum v0.2 oracle surface. Additional verify types may be added as additive public-interface changes. Canonical v0.2 feature/spec names such as `response_status_equals`, `json_match`, `schema_match`, `event_published`, and `db_record_exists` should be preferred in new DSL artifacts; older names remain compatibility aliases only when explicitly implemented.

| Verify Type | Required Fields | Optional Fields | Expected Source | Common Failure Codes |
| --- | --- | --- | --- | --- |
| `response_status_equals` | `expected` | `actual.ref`, `selector`, `severity`, `evidence` | Provider HTTP status metadata or captured status field | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
| `equals` | `actual.ref`, `expected` | `selector`, `severity`, `evidence` | Inline value or approved expected result | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `SELECTOR_NOT_FOUND` |
| `json_path_equals` | `actual.ref`, `selector`, `expected` or `expected_ref` | `severity`, `evidence` | Inline value or approved expected result | `ASSERTION_FAILED`, `SELECTOR_NOT_FOUND` |
| `json_match` | `actual.ref`, `expected` or `expected_ref` | `selector`, `ignore_paths`, `partial_match`, `severity`, `evidence` | Inline value or approved expected result | `ASSERTION_FAILED`, `SELECTOR_NOT_FOUND` |
| `schema_match` | `actual.ref`, `schema_ref` | `ignore_paths`, `severity`, `evidence` | Committed schema file | `SCHEMA_MISMATCH`, `ASSERTION_INPUT_NOT_FOUND` |
| `file_diff` | `actual.ref`, `expected_ref` | `format`, `normalize`, `ignore_order`, `ignore_paths`, `severity`, `evidence` | Approved expected file | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
| `list_size_equals` | `actual.ref`, `expected` | `selector`, `severity`, `evidence` | Inline count | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
| `numeric_tolerance` | `actual.ref`, `expected`, `tolerance` | `selector`, `severity`, `evidence` | Inline value or approved expected result | `ASSERTION_FAILED`, `SELECTOR_NOT_FOUND` |
| `db_record_exists` | `target`, `query.ref`, `expected.min_rows` or `expected_ref` | `query.dialect`, `query.params`, `options.timeout`, `options.poll_interval`, `severity`, `evidence` | Inline row condition or approved expected DB state | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `TIMEOUT` |
| `event_published` | `target`, `event.topic` / `event.subject` / `event.subject_ref`, `expected.match` or `expected_ref` | `event.key`, `options.timeout`, `options.poll_interval`, `options.consume_from`, `severity`, `evidence` | Inline match rule or approved event payload | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `TIMEOUT` |
| `event_payload_match` | `actual.ref` or `target/event`, `expected.match` or `expected_ref` | `selector`, `ignore_paths`, `severity`, `evidence` | Inline match rule or approved event payload | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
| `event_not_published` | `target`, `event.topic` or `event.subject`, `expected.match` | `event.key`, `options.timeout`, `options.poll_interval`, `severity`, `evidence` | Inline negative expectation | `ASSERTION_FAILED`, `TIMEOUT` |
| `http_mock_called` | `target`, `expected` | `options.timeout`, `severity`, `evidence` | Inline request expectation | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `TIMEOUT` |
| `http_mock_request_body_match` | `target`, `expected.match` | `selector`, `ignore_paths`, `severity`, `evidence` | Inline request expectation or approved request body | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
| `http_mock_request_count` | `target`, `expected.count` | `options.timeout`, `severity`, `evidence` | Inline count | `ASSERTION_FAILED`, `TIMEOUT` |
| `http_mock_not_called` | `target`, `expected` | `options.timeout`, `severity`, `evidence` | Inline negative expectation | `ASSERTION_FAILED`, `TIMEOUT` |

Each verify implementation must produce the verify result contract described below.

#### HTTP Status

```yaml
verify:
  - id: http_status_is_200
    type: response_status_equals
    expected: 200
```

#### JSON Path Equals

```yaml
verify:
  - id: payment_status_is_accepted
    type: json_path_equals
    actual:
      ref: ${execute.submit_payment.outputs.body}
    selector: $.paymentStatus
    expected: ACCEPTED
```

#### JSON Schema Matches

```yaml
verify:
  - id: response_matches_schema
    type: schema_match
    actual:
      ref: ${execute.submit_payment.outputs.body}
    schema_ref: schemas/payment_response.schema.json
```

#### Event Payload Match

```yaml
verify:
  - id: payment_event_received
    type: event_payload_match
    actual:
      ref: ${execute.consume_payment_event.outputs.consumed_message}
    expected_ref: expected-results/approved/payment_accepted_event.yaml
    expected:
      match:
        correlation_id:
          ref: ${execute.submit_payment.outputs.body}
```

#### Event Not Published

```yaml
verify:
  - id: no_rejection_event
    type: event_not_published
    target: payment_events
    event:
      topic: payment.events
    expected:
      match:
        eventType: PAYMENT_REJECTED
    options:
      timeout: PT10S
```

#### DB Record Exists

```yaml
verify:
  - id: payment_db_state_is_accepted
    type: db_record_exists
    target: payment_db
    query:
      ref: queries/payment/find_by_id.sql
      params:
        payment_id: ${execute.submit_payment.outputs.body}
    expected:
      min_rows: 1
```

#### List Size Equals

```yaml
verify:
  - id: exactly_one_payment_record
    type: list_size_equals
    actual:
      ref: ${execute.query_payment.outputs.query_result}
    expected: 1
```

#### Numeric Tolerance

```yaml
verify:
  - id: amount_matches
    type: numeric_tolerance
    actual:
      ref: ${execute.query_payment.outputs.query_result}
    selector: $[0].amount
    expected: 100.00
    tolerance:
      absolute: 0.01
```

#### Runtime Ready Output

```yaml
verify:
  - id: payment_deployment_ready
    type: equals
    actual:
      ref: ${execute.check_payment_deployment.outputs.deployment_status}
    selector: $.ready
    expected: true
```

#### External Runner Result

```yaml
verify:
  - id: legacy_runner_passed
    type: equals
    actual:
      ref: ${execute.run_legacy_regression.outputs.result_json}
    selector: $.status
    expected: PASSED
```

### 8.4 Eventual Verification

Use explicit `options.timeout` and `options.poll_interval` when behavior is asynchronous.

```yaml
verify:
  - id: settlement_event_eventually_received
    type: event_payload_match
    actual:
      ref: ${execute.consume_settlement_event.outputs.consumed_message}
    expected_ref: expected-results/approved/settlement_event.yaml
    options:
      timeout: PT60S
      poll_interval: PT5S
```

Rules:

- Use eventual verification for Kafka, NATS, async jobs, batch completion, and delayed DB state.
- Do not use arbitrary sleep.
- Timeout must be explicit.
- Timeout failure must produce assertion evidence.

### 8.5 Verify Result Contract

Each verify item should produce a structured assertion result.

```yaml
verify_id: payment_status_is_accepted
type: json_path_equals
status: failed
actual: PENDING
expected: ACCEPTED
failure_code: ASSERTION_FAILED
message: Expected $.status to equal ACCEPTED but was PENDING
evidence_refs:
  - provider-evidence/payment-api-response.json
  - assertion-results/payment_status_is_accepted.yaml
```

Allowed statuses:

```yaml
passed
failed
blocked
error
skipped
```

Common failure codes:

```yaml
ASSERTION_FAILED
ASSERTION_INPUT_NOT_FOUND
EXPECTED_RESULT_MISSING
EXPECTED_RESULT_INVALID
SELECTOR_NOT_FOUND
SCHEMA_MISMATCH
TIMEOUT
EVIDENCE_INVALID
```

### 8.6 Verify Evidence Rules

Every blocking verify item must produce evidence.

Evidence should include:

- `verify_id`
- assertion `type`
- actual source ref
- expected value or expected ref
- selected actual value
- comparison result
- failure code, if failed
- provider instance and profile
- related AC or expected-result reference

The report must be able to trace:

```text
AC -> test case -> execute step -> verify item -> evidence -> result
```

### 8.7 Verify Design Rules

- `verify` must check observable output only.
- `verify` must not call hidden product logic.
- `verify` must not infer expected business behavior from actual runtime behavior.
- `actual.ref` must resolve to a valid provider output ref or framework evidence ref.
- `expected_ref` must point to approved expected-result artifacts for business assertions.
- Async verification must use explicit timeout and polling policy.
- Failed cleanup is not a passed test; it should be reported separately as cleanup evidence.
- Diagnostic assertions may be non-blocking, but blocking assertions determine test result.

## 9. Provider Contract Rule

Built-in Provider Contracts are owned by the framework. RP/suite repositories should not copy them into each suite. A suite-local Provider Contract is allowed only when the suite explicitly declares a custom provider or contract snapshot pinning mode.

A provider contract and provider instance should use the same top-level domains.

The contract defines allowed fields, allowed operations, allowed values, required fields, defaults, output refs, evidence outputs, access policy shape, and failure codes. The instance fills concrete RP-level selections using those contract-defined domains.

A provider instance is invalid if it contains a field, operation, `bind_as`, output ref, evidence output, or failure code not allowed by its provider contract.

Contract and instance shapes are aligned by domain, not byte-for-byte identical. For example, a contract declares allowed readiness operations under `readiness.operations`; an instance selects one operation under `readiness.operation`.

| Domain | Contract Defines | Instance Selects |
| --- | --- | --- |
| `binding_keys` | Required and optional binding keys, types, and value source. | Binding key names supplied by environment binding. |
| `defaults` | Allowed default fields and default values. | RP-level timeout, retry, or provider defaults. |
| `readiness` | Allowed readiness operations and fields. | Selected readiness operation and concrete parameters. |
| `operations` | Allowed executable operations, `bind_as`, and output refs. | Operations are referenced by DSL `setup`, `execute`, or `cleanup`. |
| `evidence` | Allowed evidence capture and redaction options. | RP-level evidence capture and redaction selections. |
| `safety` | Required safety rules and approval shape for command-capable providers, such as `safety.rules.required_fields`. | Explicit RP-level `safety.access_policy` and `safety.approval` selections. |
| `failure_mapping` | Allowed failure codes. | Mapping from provider-specific failure cases to allowed failure codes. |

### 9.1 Command-Capable Provider Access Policy

Providers that can execute commands or collect host/runtime data must define contract-owned safety requirements under `safety.rules` when required by the Provider Contract or Execution Profile. Provider instances must explicitly select the matching `safety.access_policy` and, where required, `safety.approval`.

External runner approval is provider safety approval, not release approval.

This applies at minimum to:

- `kubernetes_runtime` with `exec_command`.
- `vm_runtime` with `run_command`, `collect_file`, or `collect_logs`.
- `external_runner` with `run` or `run_and_collect`.

Command-capable provider instances should declare:

- Whether command execution is allowed.
- Allowed commands or runner IDs.
- Blocked commands or arguments.
- Allowed namespaces, pod selectors, hosts, or working directories.
- Allowed file paths for collection.
- Whether shell execution is allowed.
- Redaction rules for command, environment, stdout, stderr, and output files.

Example:

```yaml
safety:
  access_policy:
    allow_run_command: true
    allow_shell: false
    allowed_commands:
      - /opt/settlement/bin/regression-runner
    allowed_paths:
      - /var/log/settlement/
      - /opt/settlement/output/
    blocked_args:
      - rm
      - shutdown
      - reboot
  approval:
    required: true
    ref: approvals/provider-safety/settlement-runner.yaml
```

If a command-capable provider instance does not define required `safety.access_policy`, readiness validation must fail.

## 10. Provider Contracts and Provider Instances

The canonical built-in Provider Contracts are materialized under `docs/02-architecture/contracts/provider-contracts/` and indexed by `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml`. The user guide must not redefine a second provider contract catalog. Runtime suite manifests use this built-in catalog by default.

RP/suite repositories do not need a `provider_contracts/` folder for built-in provider types such as `wiremock_http_mock`, `jdbc`, `nats`, `artifact_compare`, or `polling_observer`. Suite-local contracts are an explicit opt-in for custom provider plugins or contract snapshot pinning:

```yaml
provider_contract_resolution:
  mode: suite_override
  custom_provider_contracts: custom-provider-contracts/
  allowed_provider_types:
    - custom_runner
```

When this section is absent, the resolution mode is `framework_builtin`.

A Provider Contract defines reusable rules for one `provider_type`:

- `provider_contract_version`
- `provider_type`
- `runtime_modes`
- `binding_keys`
- `defaults`
- `valid_provider_instance_shape`
- `safety`
- `operations`
- `evidence`
- `failure_mapping`

A Provider Instance defines one logical runtime target for an RP. It must use the top-level shape allowed by its Provider Contract and must not contain physical endpoint, topic, DB credential, namespace, host, or secret values. It declares `runtime_modes` as the subset it may use; the selected `runtime_mode` is supplied by Environment Binding for the active profile.

### 10.1 Built-In Provider Contract Catalog

| Provider Type | Canonical Contract File | Required Binding Keys | Operations |
| --- | --- | --- | --- |
| `shell_command` | `provider-contracts/shell_command.yaml` | `command` | `run_batch`, `execute_command` |
| `rest_client` | `provider-contracts/rest_client.yaml` | `base_url` | `http_request` |
| `grpc_client` | `provider-contracts/grpc_client.yaml` | `target` | `unary_call`, `server_stream_call` |
| `wiremock_http_mock` | `provider-contracts/wiremock_http_mock.yaml` | `mappings_ref` | `start_mock`, `connect_mock`, `load_stubs`, `verify_requests` |
| `kafka_messaging` | `provider-contracts/kafka_messaging.yaml` | `bootstrap_servers` | `publish_message`, `consume_message` |
| `nats` | `provider-contracts/nats.yaml` | `connection`, `subject` | `nats_publish`, `nats_observe`, `event_published`, `event_payload_match` |
| `jdbc` | `provider-contracts/jdbc.yaml` | `connection.secret_ref`, `dialect` | `db_seed`, `db_cleanup`, `db_query`, `db_record_exists` |
| `kubernetes_runtime` | `provider-contracts/kubernetes_runtime.yaml` | `context`, `namespace` | `check_deployment_ready`, `check_pod_ready`, `get_logs`, `wait_rollout`, `exec_command` |
| `vm_runtime` | `provider-contracts/vm_runtime.yaml` | `host`, `user` | `check_host_ready`, `run_command`, `collect_file`, `collect_logs`, `check_process` |
| `external_runner` | `provider-contracts/external_runner.yaml` | `command` | `run`, `run_and_collect`, `check_status` |
| `artifact_compare` | `provider-contracts/artifact_compare.yaml` | none | `read_artifact` |
| `polling_observer` | `provider-contracts/polling_observer.yaml` | none | `observe_condition` |

### 10.2 Provider Instance Examples

REST Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: payment-api
provider_type: rest_client
runtime_modes: [native, stub]
binding_keys:
  base_url:
    required: true
readiness:
  operation: http_request
  parameters:
    - name: method
      ref: config/http/get.yaml
      bind_as: request.method
    - name: path
      ref: config/http/health_path.yaml
      bind_as: request.path
  expected_status: 200
defaults:
  timeout_seconds: 30
```

JDBC Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: payment-db
dialect: oracle
provider_type: jdbc
runtime_modes: [native, ephemeral]
connection:
  secret_ref: ${environment.connection.secret_ref}
binding_keys:
  connection.secret_ref:
    required: true
  dialect:
    required: true
readiness:
  operation: db_query
  parameters:
    - name: readiness_query
      ref: fixtures/payment/db/readiness.sql
      bind_as: query_ref
defaults:
  query_timeout: PT10S
```

Command-capable Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: settlement-runner
provider_type: external_runner
runtime_modes: [native]
binding_keys:
  command:
    required: true
safety:
  access_policy:
    allow_shell: false
    allowed_commands:
      - /opt/settlement/bin/regression-runner
    blocked_args:
      - rm
      - shutdown
      - reboot
    allowed_paths:
      - /var/log/settlement/
      - /opt/settlement/output/
  approval:
    required: true
    ref: approvals/provider-safety/settlement-runner.yaml
```

### 10.3 Contract Validation Rules

Before dispatch, the framework validates:

- Provider Instance exists for the DSL `provider_id`.
- Provider Contract exists in the framework built-in catalog for the Provider Instance `provider_type`, unless an explicit custom/snapshot resolution mode is declared.
- Provider Instance fields are allowed by `valid_provider_instance_shape`.
- Provider Instance `runtime_modes` are a subset of Provider Contract `runtime_modes`.
- Environment Binding exists for the selected profile and provider_id.
- Environment Binding supplies all required Provider Contract binding keys.
- Selected `runtime_mode` is allowed by Execution Profile, Provider Contract, and Provider Instance.
- Operation exists in the Provider Contract.
- Every `parameters[].bind_as` value is allowed by the Provider Contract operation.
- Every output ref used by DSL, evidence, or verify exists in the Provider Contract operation.
- Command-capable providers that execute shell, VM, K8s, or external commands include `safety.access_policy` when required by their Provider Contract or execution profile.


## 11. Environment Binding

Environment Binding supplies actual values for a profile. Provider Instances declare required binding keys; Environment Bindings provide values for those keys by `provider_id`.

Secrets must be referenced, not committed.

```yaml
environment_id: sit-payment
profile: sit
provider_bindings:
  - provider_id: payment-api
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-api-sit.yaml
    binding_values:
      base_url: https://payment-api.sit.example.com

  - provider_id: payment-db
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-db-sit.yaml
    binding_values:
      jdbc_url: jdbc:postgresql://payment-db.sit:5432/payment
      username:
        secret_ref: vault://sit/payment/db-username
      password:
        secret_ref: vault://sit/payment/db-password

  - provider_id: payment-events
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-events-sit.yaml
    binding_values:
      bootstrap_servers: kafka-sit-01:9092,kafka-sit-02:9092
```

Each provider binding must declare the selected runtime mode. Local and CI bindings usually replace external services, databases, and messaging with mocks, stubs, fake topics, embedded brokers, ephemeral DBs, disposable schemas, or generated data.

```yaml
environment_id: ci-payment
profile: ci

provider_bindings:
  - provider_id: payment-api
    runtime_mode: stub
    binding_values:
      base_url: stub://payment-api-ci

  - provider_id: payment-db
    runtime_mode: ephemeral
    binding_values:
      jdbc_url: generated://payment-postgres.jdbc_url
      username: generated://payment-postgres.username
      password:
        secret_ref: generated://payment-postgres.password

  - provider_id: payment-events
    runtime_mode: mock
    binding_values:
      bootstrap_servers: embedded-broker://payment-kafka.bootstrap_servers
```

`sit` and `preprod` bindings default to `runtime_mode: native`. Mock substitution in those profiles must not be used as downstream RP release evidence.

## 12. Execution Profile

Execution profiles define what is allowed to run in a profile.

```yaml
profile_id: sit
execution_mode: sit
environment_binding_ref: generated-framework/environment_bindings/sit-payment.yaml
isolation_scope: shared_sit_environment

dependency_policy:
  require_readiness_evidence: true
  allow_framework_managed_dependencies: false

dependency_substitution_policy:
  allowed_runtime_modes: [native]
  mock_evidence_release_claim: prohibited

dependency_provisioning_policy:
  allowed_provisioners: [none]

max_duration: PT30M

data_policy:
  approved_expected_results_required: true
  production_data_allowed: false
  secrets_must_use_refs: true
```

Local and CI profiles may provision ephemeral dependencies before execution. The profile defines the allowed provisioner and lifecycle policy; generated connection values are written into Environment Binding output keys.

```yaml
profile_id: ci
execution_mode: ci
environment_binding_ref: generated-framework/environment_bindings/ci-payment.yaml
isolation_scope: per_run

dependency_policy:
  require_readiness_evidence: true
  allow_framework_managed_dependencies: true

dependency_substitution_policy:
  allowed_runtime_modes: [mock, stub, ephemeral]
  mock_evidence_release_claim: prohibited

dependency_provisioning_policy:
  allowed_provisioners: [testcontainers]
  dependencies:
    - dependency_id: payment-postgres
      provider_id: payment-db
      dependency_type: postgres
      image_ref_or_catalog_ref: testcontainers.postgres.default
      startup_timeout: PT60S
      readiness_check: jdbc_connect
      cleanup_scope: per_run
      output_binding_keys:
        jdbc_url: payment-postgres.jdbc_url
        username: payment-postgres.username
        password: payment-postgres.password

    - dependency_id: payment-kafka
      provider_id: payment-events
      dependency_type: kafka
      image_ref_or_catalog_ref: testcontainers.kafka.default
      startup_timeout: PT90S
      readiness_check: broker_connect
      cleanup_scope: per_run
      output_binding_keys:
        bootstrap_servers: payment-kafka.bootstrap_servers

max_duration: PT15M

data_policy:
  approved_expected_results_required: true
  production_data_allowed: false
  generated_data_allowed: true
  secrets_must_use_refs: true
```

Typical profiles:

| Profile | Purpose |
| --- | --- |
| `local` | Developer debugging with mocks, stubs, files, generated data, ephemeral DBs, fake topics, or embedded brokers. |
| `ci` | Fast validation with mock/stub/ephemeral replacements for most external dependencies. |
| `sit` | Integrated RP regression across real services; mock substitution is not release evidence. |
| `preprod` | Release candidate validation with native dependencies and stricter approval. |

## 13. Running Tests

Validate checks the DSL, suite manifest, Execution Profile, Provider Instance, Environment Binding, framework built-in Provider Contract catalog, secret guardrails, result schema, and evidence contract without provider execution.

```bash
regress validate \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --env ci \
  --suite smoke \
  --format yaml
```

Framework-owned samples may use suite-path mode. This mode is for framework verification only and must not be treated as downstream RP release evidence.

Golden E2E proves the framework lifecycle with a deterministic fake provider:

```bash
regress validate --suite samples/golden_e2e/suite_manifest.yaml

regress run \
  --suite samples/golden_e2e/suite_manifest.yaml \
  --profile local_golden

regress report --result <generated_result_json>
```

Golden E2E suite-path mode may execute only deterministic framework-owned fake providers. It must not start WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, external runner, SIT, or downstream product deployment.

Provider Capability suite-path mode proves selected v0.2 P0 provider capabilities as framework evidence:

```bash
regress validate --suite samples/provider_capability/jdbc/suite_manifest.yaml

regress run \
  --suite samples/provider_capability/jdbc/suite_manifest.yaml \
  --profile local_jdbc

regress report --result <generated_result_json>
```

Provider Capability suite-path mode may execute only checked-in framework provider capability samples for WireMock HTTP mock, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and evidence/report behavior. It must not execute non-P0 providers, Product/RP/RU topology interpretation, release governance, SIT/preprod release evidence, or downstream product deployment.

Dry run validates the same contract graph and produces a resolved execution plan. It should not perform real test execution.

```bash
regress run \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --env sit \
  --dry-run
```

Run approved tests:

```bash
regress run \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --env sit \
  --suite smoke
```

Run by tag:

```bash
regress run \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --env sit \
  --tag happy-path
```

## 14. Evidence Contract

Standard result JSON is the canonical runtime output. The evidence folder stores the durable run artifacts used by reports and optional exporters. HTML and Allure outputs are optional export formats; ReportPortal integration is future optional integration.

Evidence should be written under:

```text
evidence/
  batches/<BATCH-ID>/
    batch.yaml
    coverage_summary.yaml
    readiness_summary.yaml
    evidence_index.yaml
  runs/<RUN-ID>/
    run.yaml
    execution_plan.yaml
    actual/
    logs/
    provider-evidence/
    query-evidence/
    event-evidence/
    assertion-results/
    cleanup-results/
    masking_report.yaml
  review/
    report.yaml
    report.txt
```

Generate a release-level report:

```bash
regress report \
  --root . \
  --rp-id RP-PAYMENT-001 \
  --batch-id BATCH-001 \
  --format text
```

Use `--batch-id` for release readiness. Use `--run-id` for single-run diagnostics.

Evidence must answer:

- Which AC was tested?
- Which test case ran?
- Which provider instance and profile were used?
- Which Provider Contract, provider_type, runtime_mode, and resolved operation result were used?
- Which execution log, actual artifact, expected artifact reference, assertion diff, query evidence, event evidence, mock request journal, and cleanup evidence were retained?
- What input data was used?
- What assertion passed or failed?
- What evidence supports the result?
- Is the failure caused by product behavior, test data, provider config, environment readiness, or framework error?

## 15. Agent Rules

Agents must:

1. Read RP feature spec, RP/RU mapping, architecture, AC, expected results, and approved tests first.
2. Generate drafts only under `tests/draft/` and `expected-results/draft/`.
3. Never overwrite approved tests or expected results without explicit instruction.
4. Run `check-rp` before generation work.
5. Run `validate` and `run --dry-run` before real execution.
6. Report missing AC, expected result, unknown provider type or custom provider contract, provider instance, environment binding, fixture, or evidence as gaps.
7. Preserve owner-authored truth.
8. Produce evidence and report paths after execution.
9. Never place runtime secrets or credentials in DSL test cases.
10. Do not execute command-capable providers unless their provider instance defines required `safety.access_policy`.

## 16. Compatibility Rules

Additive changes:

- Add optional DSL field.
- Add provider contract optional field.
- Add provider operation.
- Add supported `bind_as`.
- Add output ref.
- Add evidence output.
- Add optional `safety.access_policy` field.
- Add CLI flag with unchanged default behavior.

Breaking changes:

- Rename or remove DSL fields.
- Rename `provider_id` or `provider_type`.
- Change provider operation semantics.
- Change existing `bind_as` meaning.
- Change output ref names.
- Change pass/fail semantics.
- Change evidence output shape.
- Change CLI flag behavior.
- Remove or weaken command access policy requirements.
- Allow DSL test cases to bind runtime secrets directly.

Breaking changes require a version bump, migration guide, compatibility tests, and release notes.

## 17. RP Readiness Checklist

An RP regression package is ready when:

- RP feature spec exists.
- RP/RU mapping is complete.
- RP regression architecture view exists.
- AC includes happy path, failure path, and boundary path where relevant.
- Approved tests trace to AC.
- Approved expected results are available.
- Framework Provider Contracts exist for all used provider types, or explicit custom contracts are declared for approved custom mode.
- Provider instances exist for all DSL targets.
- Environment bindings exist for the selected profiles.
- Execution profile exists.
- Dry run passes.
- Full run produces required evidence.
- Batch report is reviewable by RP/product owner.
