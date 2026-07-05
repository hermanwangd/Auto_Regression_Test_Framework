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
| Env_Profile | Supplies environment-specific provider binding values and defines what may run in `local`, `ci`, `sit`, or `preprod`. |
| CLI | Initializes, validates, executes, and reports. |
| Evidence Contract | Defines reviewable outputs for release readiness. |

Resolution flow:

```text
DSL target
  -> provider_id
  -> Provider Instance
  -> provider_type
  -> Framework built-in Provider Contract catalog
  -> selected Env_Profile
  -> Env_Profile.providers.<provider_id>.binding_keys
```

There is no additional user-facing runtime interface beyond Provider Instances, Env_Profiles, DSL, CLI, evidence, and framework-owned Provider Contracts. RP/suite repositories do not copy built-in Provider Contracts by default.

## 3. Role Responsibilities

| Role | Responsibility |
| --- | --- |
| Product Owner / Product Developer | Defines product release scope, product-level E2E AC, and product-to-RP mapping. |
| RP Owner / RP Developer | Defines RP feature spec, RP AC, RP/RU mapping, approved tests, expected results, Provider Instances, and Env_Profiles. |
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
  env-profiles/
  custom-provider-contracts/   # optional, only for approved custom providers or pinned contract snapshots
  traceability.md
  evidence_index.md
```

Approved tests and expected results are versioned assets. Do not regenerate or overwrite them on every run.

Provider instance and Env_Profile authoring folders are owner/Agent Skill working areas. Runtime execution consumes canonical generated artifacts under `generated-framework/provider_instances/` and `generated-framework/env_profiles/`. Built-in Provider Contracts are resolved from the framework catalog by `provider_type`. Suite-local Provider Contracts are optional and only valid for approved custom providers or explicit contract snapshot pinning.

## 5. End-to-End Workflow

Owner or Agent Skill workflows prepare suite artifacts outside the framework runtime CLI. The v0.2.5 runtime public interface starts only after a suite manifest, test cases, Provider Instances, Env_Profiles, expected data, and evidence policy exist.

```text
prepare suite artifacts
  -> validate
  -> run --dry-run
  -> run
  -> report
  -> validate-evidence
```

The v0.2.5 runtime public interface is suite-mode: `validate --suite`, `run --suite --dry-run`, `run --suite`, `report --result`, and `validate-evidence --result`.

Typical v0.2.5 runtime commands:

```bash
regress validate \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider

regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider \
  --dry-run

regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider

regress report --result <generated_result_json>
```

Product/RP tooling must translate owner-authored artifacts into suite-mode artifacts before invoking the framework runtime. Direct Product/RP runtime orchestration is not part of the v0.2.5 framework public interface.

During framework development, keep Maven memory bounded:

```bash
MAVEN_OPTS='-Xmx1024m' ./mvnw verify
```

## 6. DSL Test Case

The DSL defines:

- Optional traceability metadata: feature spec, AC, defect, ADR, or other source links.
- Runtime target: provider instance. The active profile is selected by CLI or suite manifest.
- Test data: optional `data` catalog plus operation `inputs`.
- Setup and cleanup operations.
- Execution operations.
- Verification/oracle.
- Required evidence.

`source_refs` is metadata only. Runtime execution must not resolve it, and execution artifacts such as expected results, fixtures, SQL, payloads, mock mappings, and test data belong in `data`, operation `inputs`, or verify expected refs.

Example:

```yaml
dsl_version: v0.2
test_case_id: RP-PAYMENT-001-TC-001
title: Submit valid payment request
status: active
revision: 1

source_refs:
  acceptance_criteria: acceptance_criteria.md#AC001

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

data:
  request_payload:
    ref: fixtures/payment/valid_request.json
  submit_payment_method:
    value: POST
  submit_payment_path:
    value: /payments
  seed_customer_sql:
    ref: fixtures/payment/seed_customer.sql
  cleanup_customer_sql:
    ref: fixtures/payment/cleanup_customer.sql

setup:
  operations:
    - id: seed_customer
      target: payment_db
      operation: db_seed
      inputs:
        sql_ref:
          ref: ${data.seed_customer_sql}
      cleanup:
        target: payment_db
        operation: db_cleanup
        inputs:
          sql_ref:
            ref: ${data.cleanup_customer_sql}

execute:
  operations:
    - id: submit_payment
      target: payment_api
      operation: http_request
      inputs:
        request.method:
          ref: ${data.submit_payment_method}
        request.path:
          ref: ${data.submit_payment_path}
        request.body:
          ref: ${data.request_payload}
      outputs:
        status: response.status
        headers: response.headers
        body: response.body
        duration_ms: response.duration_ms

verify:
  checks:
    - id: payment_status_is_accepted
      type: json_path_equals
      actual:
        ref: ${execute.submit_payment.outputs.body}
      selector: $.status
      expected: ACCEPTED

cleanup:
  operations:
    - id: cleanup_customer
      target: payment_db
      operation: db_cleanup
      inputs:
        sql_ref:
          ref: ${data.cleanup_customer_sql}

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

## 7. Data Catalog and Inputs

Use `data` only when a test needs reusable reviewed artifacts or small safe literals. `data` is lifecycle-neutral; it does not split values into setup, execute, cleanup, or expected-result categories.

The framework resolves `${data.<name>}` to the matching entry's `ref` or `value`. Legacy `data_binding` and lifecycle category keys such as `input_data`, `setup_data`, `cleanup_data`, `expect_data`, `datasets`, `fixtures`, `expected_results`, `db_seed`, `db_cleanup`, and `mock_stubs` are prohibited in new v0.2 DSL artifacts.

```yaml
data:
  request_payload:
    ref: fixtures/payment/valid_request.json
  seed_customer_sql:
    ref: fixtures/payment/seed_customer.sql
  cleanup_customer_sql:
    ref: fixtures/payment/cleanup_customer.sql
  expected_payment_response:
    ref: expected-results/approved/ER001.yaml
  customer_id:
    value: CUST-001
```

`data` is not an Env_Profile substitute. It must not contain endpoint URLs, JDBC URLs, broker URLs, namespaces, credentials, or raw secrets.

Use operation `inputs` for where the provider receives data. Each input key must be allowed by the resolved Provider Contract operation.

Common `ref` values:

```yaml
ref: fixtures/payment/valid_request.json
ref: expected-results/approved/ER001.yaml
ref: ${data.request_payload}
ref: ${data.seed_customer_sql}
ref: ${execute.submit_payment.outputs.body}
```

Common `inputs` values:

```yaml
inputs:
  request.body:
    ref: ${data.request_payload}
  request.headers.X-Correlation-ID:
    value: RP-PAYMENT-001-TC-001
  query_ref:
    ref: queries/payment/find_by_id.sql
  bind_variables:
    payment_id:
      ref: ${execute.submit_payment.outputs.body}
```

A DSL test case is invalid if it uses an `inputs` key that is not allowed by the Provider Contract resolved from the target Provider Instance `provider_type`.

Runtime connection and authentication values belong to Env_Profile provider bindings, not test-case data. DSL test cases must not bind `secret.*` directly. Runtime endpoints, tokens, DB credentials, broker credentials, kubeconfig, SSH keys, and runner credentials must be supplied through `env-profiles/` or generated `generated-framework/env_profiles/`.

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
  checks:
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
  ref: ${setup.seed_customer.outputs.affected_rows}
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
| `soap_request_received` | `target`, `soap.operation` | `soap.path`, `soap.action`, `soap.request_xpath`, `soap.expected_count`, `options.timeout`, `severity`, `evidence` | Inline SOAP request expectation or approved XML request ref | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `XML_MISMATCH`, `TIMEOUT` |
| `grpc_request_received` | `target`, `grpc.service`, `grpc.method` | `grpc.request_json`, `expected.count`, `options.timeout`, `severity`, `evidence` | Inline gRPC request expectation or checked-in JSON fixture | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `PROTO_DESCRIPTOR_INVALID`, `TIMEOUT` |

Each verify implementation must produce the verify result contract described below.

#### HTTP Status

```yaml
verify:
  checks:
    - id: http_status_is_200
      type: response_status_equals
      expected: 200
```

#### JSON Path Equals

```yaml
verify:
  checks:
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
  checks:
    - id: response_matches_schema
      type: schema_match
      actual:
        ref: ${execute.submit_payment.outputs.body}
      schema_ref: schemas/payment_response.schema.json
```

#### Event Payload Match

```yaml
verify:
  checks:
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
  checks:
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
  checks:
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
  checks:
    - id: exactly_one_payment_record
      type: list_size_equals
      actual:
        ref: ${execute.query_payment.outputs.query_result}
      expected: 1
```

#### Numeric Tolerance

```yaml
verify:
  checks:
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
  checks:
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
  checks:
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
  checks:
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
  - assertions/payment_status_is_accepted.yaml
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

A provider instance is invalid if it contains a field, operation input key, output ref, evidence output, or failure code not allowed by its provider contract.

Contract and instance shapes are aligned by domain, not byte-for-byte identical. For example, a contract declares allowed readiness operations under `readiness.operations`; an instance selects one operation under `readiness.operation`.

| Domain | Contract Defines | Instance Selects |
| --- | --- | --- |
| `binding_keys` | Required and optional binding keys, value types, allowed value kinds, defaults, and generated-ref rules. | Not selected by Provider Instance. Env_Profile supplies values for these keys. |
| `bindable_outputs` | Runtime outputs that may be referenced by Env_Profile `generated_ref`, such as `generated://wiremock-payment-api.base_url`. | Not selected by Provider Instance. Provider runtime produces the output at execution time. |
| `defaults` | Allowed default fields and default values. | RP-level timeout, retry, or provider defaults. |
| `readiness` | Allowed readiness operations and fields. | Selected readiness operation and concrete inputs. |
| `operations` | Allowed executable operations, input keys, and output refs. | Operations are referenced by DSL `setup`, `execute`, or `cleanup`. |
| `evidence` | Allowed evidence capture and redaction options. | RP-level evidence capture and redaction selections. |
| `safety` | Required safety rules and approval shape for command-capable providers, such as `safety.rules.required_fields`. | Explicit RP-level `safety.access_policy` and `safety.approval` selections. |
| `failure_mapping` | Allowed failure codes. | Mapping from provider-specific failure cases to allowed failure codes. |

### 9.1 Command-Capable Provider Access Policy

Providers that can execute commands or collect host/runtime data must define contract-owned safety requirements under `safety.rules` when required by the Provider Contract or Env_Profile execution mode. Provider instances must explicitly select the matching `safety.access_policy` and, where required, `safety.approval`.

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

Framework `0.2.5` public provider support is defined in `docs/09-operations/provider_support_matrix.md`. That matrix is keyed by provider type and `support_status`; runtime lifecycle details such as native, mock, ephemeral, framework-managed, or external are Env_Profile details, not public support statuses.

RP/suite repositories do not need a `provider_contracts/` folder for built-in provider types such as `wiremock_http_mock`, `rest_client`, `jdbc`, `nats`, `kafka`, `ibm_mq`, `artifact_compare`, or `polling_observer`. Suite-local contracts are an explicit opt-in for custom provider plugins or contract snapshot pinning:

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
- `executable_runtime_modes` when only a subset is runnable by the current framework build
- `contract_only_runtime_modes` when remaining modes are vocabulary for future implementation
- `binding_keys`
- `bindable_outputs`
- `defaults`
- `valid_provider_instance_shape`
- `safety`
- `operations`
- `evidence`
- `failure_mapping`

A Provider Instance defines one logical runtime target for an RP. It must use the top-level shape allowed by its Provider Contract and must not contain physical endpoint, topic, DB credential, namespace, host, or secret values. It declares `runtime_modes` as the subset it may use; the selected `runtime_mode` and all Provider Contract `binding_keys` are supplied by Env_Profile for the active environment. For messaging client providers, destination keys such as Kafka `topic`, Kafka `consumer_group`, and IBM MQ `queue` are Env_Profile bindings. Model different destinations as different Provider Instances instead of overriding them from operation inputs.

### 10.1 Built-In Provider Contract Catalog

| Provider Type | Canonical Contract File | Required Binding Keys | Operations |
| --- | --- | --- | --- |
| `shell_command` | `provider-contracts/shell_command.yaml` | `command` | `run_batch`, `execute_command` |
| `rest_client` | `provider-contracts/rest_client.yaml` | `base_url` | `http_request` |
| `grpc_client` | `provider-contracts/grpc_client.yaml` | `target` | `unary_call` |
| `wiremock_http_mock` | `provider-contracts/wiremock_http_mock.yaml` | none required; local/CI usually uses `mappings_ref` and `port_strategy` | `start_mock`, `connect_mock`, `load_stubs`, `verify_requests` |
| `soap_mock` | `provider-contracts/soap_mock.yaml` | none required; local/CI usually uses `port_strategy` and generated `endpoint_url` output | `start_soap_mock`, `connect_soap_mock`, `load_soap_stub`, `soap_request_received`, `reset_mock` |
| `grpc_mock` | `provider-contracts/grpc_mock.yaml` | `descriptor_ref`, `service_name`; `target_uri` is a generated bindable output for clients | `start_grpc_mock`, `connect_grpc_mock`, `load_grpc_stub`, `grpc_request_received`, `reset_mock` |
| `kafka` | `provider-contracts/kafka.yaml` | `bootstrap_servers`, `topic`, `consumer_group` | `kafka_publish`, `kafka_observe`, `kafka_payload_match` |
| `ibm_mq` | `provider-contracts/ibm_mq.yaml` | `queue_manager`, `channel`, `conn_name`, `queue`, `credential.secret_ref` | `mq_put`, `mq_browse`, `mq_message_exists`, `mq_payload_match` |
| `kafka_messaging` | `provider-contracts/kafka_messaging.yaml` | `bootstrap_servers` | deprecated alias for legacy `publish_message`, `consume_message` artifacts |
| `nats` | `provider-contracts/nats.yaml` | `connection`, `subject` | `nats_publish`, `nats_observe`, `event_published`, `event_payload_match` |
| `jdbc` | `provider-contracts/jdbc.yaml` | `connection.secret_ref` or approved `connection.local_ref`, `dialect` | `db_seed`, `db_cleanup`, `db_query`, `db_record_exists` |
| `kubernetes_runtime` | `provider-contracts/kubernetes_runtime.yaml` | `context`, `namespace` | `check_deployment_ready`, `check_pod_ready`, `get_logs`, `wait_rollout`, `exec_command` |
| `vm_runtime` | `provider-contracts/vm_runtime.yaml` | `host`, `user` | `check_host_ready`, `run_command`, `collect_file`, `collect_logs`, `check_process` |
| `external_runner` | `provider-contracts/external_runner.yaml` | `command` | `run`, `run_and_collect`, `check_status` |
| `artifact_compare` | `provider-contracts/artifact_compare.yaml` | none | `read_artifact` |
| `polling_observer` | `provider-contracts/polling_observer.yaml` | none | `observe_condition` |

In v0.2 provider capability mode, `rest_client` is executable for checked-in WireMock + HTTP request samples. It resolves `base_url` from Env_Profile, including generated WireMock outputs such as `generated://wiremock-payment-api.base_url`, executes `http_request`, exposes `response.status`, `response.headers`, `response.body`, and `response.duration_ms`, and writes `http_request_response` evidence. Downstream SIT/preprod endpoint validation still requires owner-provided RP artifacts and real Env_Profiles.

`soap_mock` and `grpc_mock` are PR-008 WireMock-backed mock capabilities. `soap_mock` is executable in PR-008A through WireMock HTTP/XML/SOAP behavior for SOAPAction/header and XPath matching. `grpc_mock` is executable in PR-008B through the WireMock gRPC extension and descriptor refs for unary calls. They are mock providers for local/CI framework evidence; they do not prove downstream SIT/preprod release readiness and do not imply custom SOAP/gRPC server ownership by the framework.

`kafka` and `ibm_mq` are P1 client provider contracts with framework-owned mock capability runtimes and native client runtimes for externally provisioned broker or queue-manager endpoints. Their Provider Contracts list `runtime_modes: [native, mock, ephemeral]` as vocabulary and declare `executable_runtime_modes: [mock, native]`; `ephemeral` remains `contract_only_runtime_modes`. They describe how the test runner consumes Env_Profile binding keys and writes framework evidence without starting brokers, queue managers, Testcontainers, or RUs. Public CI release gates validate external profiles and run native external messaging samples only when broker or queue-manager bindings are configured. A single messaging suite may include Kafka and IBM MQ test cases together when every test case uses the same selected Env_Profile and each test case has exactly one messaging runtime target. External broker or queue-manager values must be materialized into `value`, `secret_ref`, or approved `local_ref` before framework execution; client providers only consume resolved bindings.

### 10.2 Provider Instance Examples

REST Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: payment-api
provider_type: rest_client
runtime_modes: [native, stub]
readiness:
  operation: http_request
  inputs:
    request.method:
      value: GET
    request.path:
      value: /health
  expected_status: 200
defaults:
  timeout_seconds: 30
```

JDBC Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: payment-db
provider_type: jdbc
runtime_modes: [native, ephemeral]
readiness:
  operation: db_query
  inputs:
    query_ref:
      ref: fixtures/payment/db/readiness.sql
defaults:
  query_timeout: PT10S
```

Kafka client Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: payment-events
provider_type: kafka
runtime_modes: [mock]
defaults:
  consume_from: test_start_time
  timeout: PT10S
```

IBM MQ client Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: payment-mq
provider_type: ibm_mq
runtime_modes: [mock]
defaults:
  browse_only: true
  timeout: PT10S
```

Command-capable Provider Instance:

```yaml
provider_instance_version: v0.2
provider_id: settlement-runner
provider_type: external_runner
runtime_modes: [native]
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
- When Provider Contract `executable_runtime_modes` is present, selected Env_Profile `runtime_mode` must be listed there.
- Env_Profile exists for the selected `env_profile_id`.
- Env_Profile has `providers.<provider_id>` for every DSL target.
- Env_Profile `providers.<provider_id>.binding_keys` supplies all required Provider Contract binding keys.
- Env_Profile binding value kinds are allowed by the Provider Contract `binding_keys`.
- Env_Profile `generated_ref` values resolve to Provider Contract `bindable_outputs` or selected Env_Profile `dependency_provisioning_policy.generated_outputs`.
- Selected `runtime_mode` is allowed by Env_Profile, Provider Contract, and Provider Instance.
- Operation exists in the Provider Contract.
- Every operation `inputs` key is allowed by the Provider Contract operation.
- Every output ref used by DSL, evidence, or verify exists in the Provider Contract operation.
- Command-capable providers that execute shell, VM, K8s, or external commands include `safety.access_policy` when required by their Provider Contract or Env_Profile execution mode.


## 11. Env_Profile

Env_Profile supplies actual environment values for Provider Contract `binding_keys` and defines the execution mode. The `providers` map is keyed by Provider Instance `provider_id`. Provider Instance files do not define binding key schema or physical endpoints.

Secrets must be referenced, not committed.

```yaml
env_profile_id: sit_payment
execution_mode: sit
providers:
  payment-api:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-api-sit.yaml
    binding_keys:
      base_url:
        value: https://payment-api.sit.example.com

  payment-db:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-db-sit.yaml
    binding_keys:
      connection.secret_ref:
        secret_ref: vault://sit/payment/db-connection
      dialect:
        value: oracle

  payment-events:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-events-sit.yaml
    binding_keys:
      bootstrap_servers:
        secret_ref: vault://sit/payment/kafka-bootstrap
      topic:
        value: payment.events
      consumer_group:
        value: artf-payment-sit

  payment-mq:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-mq-sit.yaml
    binding_keys:
      queue_manager:
        value: QM.PAYMENT.SIT
      channel:
        value: APP.SVRCONN
      conn_name:
        secret_ref: vault://sit/payment/mq-conn-name
      queue:
        value: PAYMENT.REQUEST.SIT
      credential.secret_ref:
        secret_ref: vault://sit/payment/mq-credential
```

Each provider binding must declare the selected runtime mode. Local and CI Env_Profiles usually replace external services, databases, and messaging with mocks, stubs, fake topics, embedded brokers, ephemeral DBs, disposable schemas, or generated data.

The `binding_keys` under each provider must match the keys defined by that provider's Provider Contract. Allowed value kinds are `value`, `ref`, `secret_ref`, `generated_ref`, and approved `local_ref`, but the Provider Contract decides which kinds are valid for each binding key. Use `local_ref` only for framework-controlled local/CI fixtures; it must not be used as SIT/preprod release evidence.

```yaml
env_profile_id: local_wiremock_http
execution_mode: local
providers:
  wiremock-payment-api:
    runtime_mode: mock
    binding_keys:
      mappings_ref:
        ref: fixtures/wiremock/payment_mappings
      port_strategy:
        value: dynamic

  payment-api-client:
    runtime_mode: native
    binding_keys:
      base_url:
        generated_ref: generated://wiremock-payment-api.base_url

  payment-soap-mock:
    runtime_mode: mock
    binding_keys:
      endpoint_url:
        generated_ref: generated://payment-soap-mock.endpoint_url
      wsdl_ref:
        ref: contracts/payment.wsdl

  customer-grpc-mock:
    runtime_mode: mock
    binding_keys:
      descriptor_ref:
        ref: proto/customer.desc
      service_name:
        value: CustomerService

  customer-grpc-client:
    runtime_mode: native
    binding_keys:
      target:
        generated_ref: generated://customer-grpc-mock.target_uri

  payment-db:
    runtime_mode: ephemeral
    binding_keys:
      connection:
        local_ref: approved_local_h2_oracle
      dialect:
        value: oracle

  payment-events:
    runtime_mode: mock
    binding_keys:
      bootstrap_servers:
        value: localhost:19092
      topic:
        value: payment.events.test
      consumer_group:
        value: artf-payment-ci

  payment-mq:
    runtime_mode: mock
    binding_keys:
      queue_manager:
        value: QM.PAYMENT.CI
      channel:
        value: APP.SVRCONN
      conn_name:
        value: localhost(1414)
      queue:
        value: PAYMENT.REQUEST.CI
      credential.secret_ref:
        secret_ref: env://PAYMENT_MQ_CREDENTIAL_REF
```

`generated_ref` can reference outputs declared in a producing Provider Contract `bindable_outputs`, such as `generated://wiremock-payment-api.base_url` from a framework-owned mock provider in the same suite. It may also reference externally provisioned dependency outputs explicitly listed in the selected Env_Profile `dependency_provisioning_policy.generated_outputs`. Undeclared project-specific generated refs are blocked before provider dispatch. Use a literal `value`, `secret_ref`, or approved local/CI-only `local_ref` when the dependency value has already been materialized.

`sit` and `preprod` Env_Profiles default to `runtime_mode: native`. Mock substitution in those execution modes must not be used as downstream RP release evidence.

## 12. Execution Mode and Dependency Policy

Env_Profile defines what is allowed to run in an environment.

```yaml
env_profile_id: sit
execution_mode: sit
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

Local and CI Env_Profiles may reference ephemeral dependencies only after those dependencies have been provisioned outside the framework runtime and declared through standard artifacts. This is separate from client providers: Kafka and IBM MQ client providers consume resolved connection values but do not create brokers or queue managers. v0.2.5 accepts `generated://` refs only when they target Provider Contract `bindable_outputs` or selected Env_Profile `dependency_provisioning_policy.generated_outputs`; unresolved generated refs block validation before provider dispatch.

```yaml
env_profile_id: ci
execution_mode: ci
isolation_scope: per_run

dependency_policy:
  require_readiness_evidence: true
  allow_framework_managed_dependencies: true

dependency_substitution_policy:
  allowed_runtime_modes: [mock, stub, ephemeral]
  mock_evidence_release_claim: prohibited

dependency_provisioning_policy:
  allowed_provisioners: [external_ci_pre_step]

providers:
  payment-db:
    runtime_mode: ephemeral
    binding_keys:
      connection:
        local_ref: approved_local_h2_oracle
      dialect:
        value: oracle

  payment-events:
    runtime_mode: mock
    binding_keys:
      bootstrap_servers:
        value: localhost:9092

max_duration: PT15M

data_policy:
  approved_expected_results_required: true
  production_data_allowed: false
  generated_data_allowed: true
  secrets_must_use_refs: true
```

Typical execution modes:

| Mode | Purpose |
| --- | --- |
| `local` | Developer debugging with mocks, stubs, files, generated data, ephemeral DBs, fake topics, or embedded brokers. |
| `ci` | Fast validation with mock/stub/ephemeral replacements for most external dependencies. |
| `sit` | Integrated RP regression across real services; mock substitution is not release evidence. |
| `preprod` | Release candidate validation with native dependencies and stricter approval. |

## 13. Running Tests

Validate checks the DSL, suite manifest, Env_Profile, Provider Instance, framework built-in Provider Contract catalog, secret guardrails, result schema, and evidence contract without provider execution.

Malformed suite YAML is a validation failure. The command returns `validation_status: failed` with `reason: invalid_yaml`, does not enter provider runtime, and requires the owner to fix the YAML or referenced contract artifact before retrying.

```bash
regress validate \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider
```

Framework-owned samples may use suite-path mode. This mode is for framework verification only and must not be treated as downstream RP release evidence.

Golden E2E proves the framework lifecycle with a deterministic fake provider:

```bash
regress validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_golden

regress report --result <generated_result_json>
```

Golden E2E suite-path mode may execute only deterministic framework-owned fake providers. It must not start WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, external runner, SIT, or downstream product deployment.

Provider Capability suite-path mode proves selected v0.2 P0 provider capabilities as framework evidence. A standard suite run creates one `batch_id`, one `run_id`, one result JSON, and per-test outcomes in `test_results[]`; all selected `tests[]` share the selected Env_Profile. Provider identity for suite-level reporting comes from `provider_summary[]` and `provider_results[]`. Multi-provider standard results, inferred from either `test_results[]` or `provider_results[]`, must include `provider_summary[]`. Top-level `provider_id`, `provider_type`, or destination fields are single-provider compatibility fields only and must not be used to summarize a multi-provider suite.

The v0.2.5 contract baseline sample is an executable mixed-provider framework verification suite:

```bash
regress validate --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml --profile ci

regress run \
  --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml \
  --profile ci

regress report --result <generated_result_json>
```

This sample dispatches only the checked-in `wiremock_http_mock` + `jdbc` + `nats` combination. It uses local/CI deterministic bindings and always reports `release_evidence_eligible: false`.

`regress validate-evidence --result <generated_result_json>` and `regress report --result <generated_result_json>` validate the standard result JSON before publishing or reporting. Any result with non-empty `provider_results`, `batch_id`, `run_id`, `test_count`, or `test_results` is treated as a standard suite run and must include `suite_id`, `batch_id`, `run_id`, `test_count`, `test_results`, `start_time`, `end_time`, and `duration_ms`. `test_count` must be a positive JSON integer value that equals `test_results.length`, and every `test_results[]` entry must be an object containing `test_case_id`, `status`, and `profile`. Allowed per-test status values are `passed`, `failed`, and `blocked`. Quoted numeric strings such as `"1"` are invalid. Invalid suite summaries return a non-zero exit and must be fixed before the result can be published.

```bash
regress validate --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml \
  --profile local_wiremock_http

regress report --result <generated_result_json>

regress validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile local_jdbc

regress report --result <generated_result_json>
```

The WireMock + HTTP request sample keeps happy and boundary cases in the canonical `suite_manifest.yaml` `tests[]` list so both run under one shared Env_Profile. It also includes `suite_manifest_failure.yaml` for deterministic assertion-failure evidence.

Provider Capability suite-path mode may execute only checked-in framework provider capability samples for WireMock HTTP mock, `rest_client` HTTP request, SOAP mock, gRPC unary mock, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and evidence/report behavior. It must not execute non-P0 providers, Product/RP/RU topology interpretation, release governance, SIT/preprod release evidence, or downstream product deployment.

Usage-kit provider instance files labeled `sample_scope: usage_kit_runtime_mode_sample` are runtime-mode coverage artifacts. They prove the public Provider Instance shape is represented in the usage kit for that provider/runtime mode, but they are not executed unless a test case target references the `provider_id` and the selected Env_Profile supplies a matching `runtime_mode`.

### 13.1 Sample Layout

Checked-in samples use a canonical usage-kit layout:

| Path | Purpose |
| --- | --- |
| `samples/00-getting-started/golden_e2e/` | Minimal deterministic framework lifecycle sample. |
| `samples/10-contract-baseline/mixed_wiremock_jdbc_nats/` | Mixed WireMock/JDBC/NATS contract baseline. |
| `samples/20-provider-capability-p0/` | Executable P0 provider capability suites by capability family. |
| `samples/30-cross-provider-groups/mock_server_cross_verify/` | Cross-provider mock server suite group. |
| `samples/40-evidence-reporting/evidence_hardening/` | Result/evidence validation fixtures. |
| `samples/90-compatibility/dummy_rest/` | Compatibility-only fixture, not a supported provider gate. |

A leaf suite owns `tests[]` in its `suite_manifest.yaml`. A suite group owns `child_suites[]` and no `tests[]`; every child `ref` must remain inside the suite group directory after path normalization. Runtime-mode contract samples labeled `sample_scope: usage_kit_runtime_mode_sample` are coverage artifacts and are not executable targets.

### 13.2 Mock Server Usage

Mock server providers replace external REST, SOAP, or gRPC dependencies with deterministic checked-in stubs for local/CI framework verification. They are provider capability evidence only and must not be treated as downstream SIT/preprod RP release evidence.

| Provider Type | Purpose | Typical Client | Generated Binding Output |
| --- | --- | --- | --- |
| `wiremock_http_mock` | REST/HTTP stubs and request journal verification | `rest_client` | `base_url` |
| `soap_mock` | SOAP-over-HTTP XML/SOAPAction stubs and request verification | HTTP/SOAP request flow | `endpoint_url` |
| `grpc_mock` | Unary gRPC mock behavior through WireMock gRPC extension | `grpc_client` | `target_uri` |

Mock server lifecycle:

1. `validate` checks suite, test case DSL, Provider Instances, Env_Profile bindings, stubs, descriptors, and expected refs.
2. `run --dry-run` resolves mock server and client targets without starting runtime.
3. `run` executes every selected test in the suite with one selected Env_Profile.
4. Test case `setup` loads checked-in stubs or mappings.
5. Test case `execute` calls the client provider, such as `rest_client` or `grpc_client`.
6. Test case `verify` checks response, request journal, SOAP request, gRPC request, or assertion output.
7. Cleanup resets or stops mock runtime and records evidence.

DSL rules:

- Test cases reference `provider_id` only. The active profile is selected once for the suite by CLI `--profile` or suite manifest `profile`; test cases must not embed generated endpoint URLs or select a different runtime profile.
- Mock stubs, mappings, descriptors, and expected results must be checked-in artifacts referenced by `ref`.
- Env_Profile supplies or resolves binding keys.
- Generated mock outputs, such as `generated://wiremock-payment-api.base_url`, are bound through Env_Profile and consumed by client providers.
- `grpc_mock` v0.2 supports unary calls only.

Evidence generated by mock server samples includes request journals, server logs, client request/response evidence, assertion diffs, suite summaries, and raw Allure result files when running a child-suite aggregation manifest.

### 13.3 Provider Capability Multi-Test Suites

The primary multi-test runner model is a standard suite manifest with `tests[]`, where every selected test case shares the same suite profile and Env_Profile. Single-suite provider capability runs may mix supported executable provider types, such as Kafka and IBM MQ client-provider test cases, when the selected Env_Profile contains provider bindings for each referenced `provider_id`.

`child_suites[]` aggregation manifests are a compatibility model for checked-in child suite manifests and summarize provider-pair suites plus expected-failure suites. Child refs must stay under the aggregation manifest directory and child ids must be unique. Boundary paths should live inside the canonical child suite `tests[]` when they share the same Env_Profile.

Aggregation lifecycle:

- `regress validate --suite <suite_manifest>` validates child refs, duplicate child ids, child suite YAML, and child suite contract artifacts.
- `regress run --suite <suite_manifest> --dry-run` validates child suites and returns `provider_runtime_invoked: false`.
- `regress run --suite <suite_manifest> --profile <profile>` validates child suites before execution. Runtime starts only after all preflight checks pass.

```bash
regress validate --suite samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml

regress run \
  --suite samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml \
  --dry-run

regress run \
  --suite samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml \
  --profile local_mock_server_cross_verify
```

The following child-suite aggregation problems are preflight blockers:

- missing child suite manifest
- child ref escaping the aggregation manifest directory
- duplicate child id
- malformed child suite YAML
- invalid child contract artifacts

Blocked aggregation runs return `run_status: blocked` and must not produce `batch_id`, `run_id`, `suite_summary_json`, or `allure_results_dir`.

`expected_status: failed` means the child suite is expected to execute and finish with observed status `failed`. `blocked` is not an expected failure pass. Configuration, schema, missing file, malformed YAML, or invalid contract problems are preflight blockers and must be fixed before execution.

| Command | Success Output | Blocked Output |
| --- | --- | --- |
| `validate` | `validation_status: passed` | `validation_status: failed` |
| `run --dry-run` | `run_status: dry_run_ready`, `provider_runtime_invoked: false` | `run_status: blocked`, no runtime |
| `run` | `batch_id`, `run_id`, `suite_summary_json`, `allure_results_dir` | `run_status: blocked`, no run artifacts |

A successful aggregation run writes `suite_summary.json`, `suite_summary.yaml`, and raw Allure result files under `target/suite-groups/<suite_id>/<batch_id>/<run_id>/`.

Suite-mode output paths are deterministic:

| Run Type | Output Path | Canonical Artifact |
| --- | --- | --- |
| Direct provider capability suite | `target/provider-capability/<provider-family>/<suite_id>/<batch_id>/<run_id>/` | `result.json` |
| Suite group aggregation | `target/suite-groups/<suite_id>/<batch_id>/<run_id>/` | `suite_summary.json` |
| Report | Reads the printed `result_json` or `suite_summary_json` path | Deterministic text or YAML summary |

Every direct suite result must reference an `evidence_index.yaml`; every suite group summary must include child suite status, `passed_count`, `failed_count`, `blocked_count`, and `status_taxonomy`. Expected-failure children are reported as `expected_failed_observed` only after execution. Preflight blockers remain `blocked` and do not produce run artifacts.

PR-008A SOAP provider capability samples use separate suite manifests:

```bash
regress validate --suite samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml \
  --profile local_soap_mock
```

PR-008B gRPC provider capability samples use the same pattern:

```bash
regress validate --suite samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml \
  --profile local_grpc_mock

regress report --result <generated_result_json>
```

SOAP/gRPC mock samples may start WireMock-backed mock services at suite/batch scope. Test case `setup` loads stubs; it must not start RU, restart RU, or embed generated endpoint values in the DSL. gRPC v0.2 mock scope is unary only.

Dry run validates the same contract graph and produces a resolved execution plan. It should not perform real test execution.

```bash
regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider \
  --dry-run
```

Run approved tests in the suite:

```bash
regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider
```

Run by tag:

```bash
regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_provider \
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
    assertions/
    cleanup-results/
    masking_report.yaml
  review/
    report.yaml
    report.txt
```

`evidence_index.yaml` is a structured index, not a compact ref list. Each entry must include the stable evidence identity, file location, producer, status, and masking state:

```yaml
evidence_index_version: v0.2
suite_id: RP-PAYMENT-001-regression
batch_id: BATCH-001
run_id: RUN-001
test_case_id: RP-PAYMENT-001-TC-001
profile: ci
entries:
  - evidence_id: wiremock-request-journal-001
    evidence_type: wiremock_request_journal
    produced_by: provider
    provider_type: wiremock_http_mock
    provider_id: payment-api-mock
    test_case_id: RP-PAYMENT-001-TC-001
    run_id: RUN-001
    batch_id: BATCH-001
    file_path: provider-evidence/payment-api/request_journal.json
    content_type: application/json
    status: passed
    created_at: "2026-06-29T00:00:01Z"
    masking_applied: true
    linked_result_field: provider_results.resolved_operation_result
masking:
  raw_secret_found: false
```

Do not publish legacy compact entries such as `ref:` plus `masked:`. Result JSON `evidence_refs[]` is the complete evidence list; `provider_evidence_refs[]` must contain only provider-produced refs such as provider, query, event, fixture, actual, HTTP, gRPC, Kafka, IBM MQ, JDBC, NATS, or WireMock evidence. Framework logs, batch summaries, assertion diffs, and expected artifacts belong in `evidence_refs[]` only.

Generate a release-level report:

```bash
regress validate-evidence \
  --result <generated_result_json>

regress report \
  --result <generated_result_json> \
  --format text
```

Use `--result` for v0.2.5 suite-mode release readiness. Product/RP-specific report forms are outside the v0.2.5 framework runtime public interface.

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
4. Run the Product/RP readiness checks supplied by the owner or Agent Skill before generation work.
5. Run `validate` and `run --dry-run` before real execution.
6. Report missing AC, expected result, unknown provider type or custom provider contract, Provider Instance, Env_Profile, fixture, or evidence as gaps.
7. Preserve owner-authored truth.
8. Produce evidence and report paths after execution.
9. Never place runtime secrets or credentials in DSL test cases.
10. Do not execute command-capable providers unless their provider instance defines required `safety.access_policy`.

## 16. Compatibility Rules

Additive changes:

- Add optional DSL field.
- Add provider contract optional field.
- Add provider operation.
- Add supported input key.
- Add output ref.
- Add evidence output.
- Add optional `safety.access_policy` field.
- Add CLI flag with unchanged default behavior.

Breaking changes:

- Rename or remove DSL fields.
- Rename `provider_id` or `provider_type`.
- Change provider operation semantics.
- Change existing input key meaning.
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
