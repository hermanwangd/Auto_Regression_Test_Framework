# Auto Regression Test Framework User Guide

## 1. Purpose

This guide explains how product owners, release package owners, release unit developers, CI/CD, and agents use the Auto Regression Test Framework.

The framework is product-agnostic. It does not decide product topology, RP/RU ownership, acceptance criteria, expected business behavior, or release readiness. Owners provide those inputs. The framework provides stable public interfaces for test definition, runtime configuration, execution, and evidence.

## 2. Public Interface Model

The user-facing public interfaces are:

| Interface | Purpose |
| --- | --- |
| DSL Test Case | Defines executable regression behavior with direct suite target references. |
| Provider Contract | Framework-owned contract for a provider type, allowed operations, bindings, outputs, evidence, failure codes, and valid target shape. |
| Suite Manifest | Defines suite metadata, artifact roots, targets, Env_Profile refs, and test-case refs. |
| Suite Target | Suite-level logical runtime target that selects a Provider Contract. |
| Env_Profile | Supplies profile-specific target runtime mode and binding values. |
| CLI | Initializes, validates, executes, and reports. |
| Evidence Contract | Defines reviewable outputs for release readiness. |

Resolution flow:

```text
DSL target
  -> suite_manifest.targets.<target>.provider_contract
  -> Framework built-in Provider Contract catalog
  -> selected Env_Profile
  -> Env_Profile.targets.<target>.bindings
```

There is no additional user-facing runtime interface beyond suite targets,
Env_Profiles, DSL, CLI, evidence, and framework-owned Provider Contracts.
RP/suite repositories do not copy built-in Provider Contracts by default.
Provider Instance files are v0.2 compatibility artifacts only.

## 3. Role Responsibilities

| Role | Responsibility |
| --- | --- |
| Product Owner / Product Developer | Defines product release scope, product-level E2E AC, and product-to-RP mapping. |
| RP Owner / RP Developer | Defines RP feature spec, RP AC, RP/RU mapping, approved tests, expected results, suite targets, and Env_Profiles. |
| RU Developer | Provides APIs, events, jobs, readiness signals, fixtures, cleanup support, and implementation fixes. |
| Agent | Initializes structure, drafts tests, validates readiness, runs tests, and reports gaps/evidence. |

Agents must not invent AC, expected results, RP/RU mapping, or release decisions.

## 4. Suite Package Structure

```text
samples/<suite>/
  suite_manifest.yaml
  test_cases/
  env_profiles/
  fixtures/
  expected_results/
  queries/
  custom_provider_contracts/   # optional, only for approved custom providers or pinned contract snapshots
```

Approved tests and expected results are versioned assets. Do not regenerate or overwrite them on every run.

Built-in Provider Contracts are resolved from the framework catalog by
`provider_contract`. Suite-local Provider Contracts are optional and only valid
for approved custom providers or explicit contract snapshot pinning.

## 5. End-to-End Workflow

Owner or Agent Skill workflows prepare suite artifacts outside the framework
runtime CLI. The stable runtime public interface starts after a suite manifest,
test cases, Env_Profiles, expected artifacts, and evidence policy exist.

```text
prepare suite artifacts
  -> validate
  -> run --dry-run
  -> run
  -> report
  -> validate-evidence
```

The runtime public interface is suite-mode: `validate --suite`,
`run --suite --dry-run`, `run --suite`, `report --result`, and
`validate-evidence --result`.

Typical v0.3 runtime commands:

```bash
regress validate \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03 \
  --dry-run

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>
```

These are executable, checked-in samples. `validate` and `run --dry-run` print
the same `plan_digest` for the selected leaf suite/profile. The runtime result
persists that digest in `result.json`, so a report can be traced to the exact
compiled execution plan.

Release maintainers run the bounded Maven release gate, which validates the
schemas and contracts, executes every supported local v0.3 sample through
validate/dry-run/run/report/evidence validation, and verifies the bundled
Provider Contract registry from outside the repository working directory:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw verify -Pv03-release-gate
```

Product/RP tooling must translate owner-authored artifacts into suite-mode
artifacts before invoking the framework runtime. Direct Product/RP runtime
orchestration is not part of the framework public interface.

Resolution flow:

```text
test_case.target
  -> suite_manifest.targets.<target>.provider_contract
  -> framework Provider Contract
  -> selected env_profile.targets.<target>
  -> resolved execution plan
```

v0.3 test cases reference suite target names directly. They use `op`, `with`, `verify`, and typed refs such as `artifact://...` and `step://...`. They must not contain v0.2-only fields such as `provider_id`, `provider_instance`, `parameters`, `bind_as`, `data_binding`, `datasets`, `fixtures`, or `expected_results`.

Typical v0.3 commands:

```bash
regress validate \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03 \
  --dry-run

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03

regress validate \
  --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml \
  --profile local_v03

regress run \
  --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml \
  --profile local_v03 \
  --dry-run

regress run \
  --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>
```

Use `docs/v0.3/` in the usage kit for the formal v0.3 spec, architecture, AC,
and test plan. Use `docs/09-operations/` for the stable v0.3 user guide and
shared operations topics. v0.2 materials are compatibility references only.

During framework development, keep Maven memory bounded:

```bash
MAVEN_OPTS='-Xmx1024m' ./mvnw verify
```

## 6. DSL Test Case

The DSL defines:

- Optional traceability metadata: feature spec, AC, defect, ADR, or other source links.
- Runtime target: suite target name. The target's Provider Contract is declared in the suite manifest.
- Test data: artifact refs and safe literals supplied directly through operation `with`.
- Setup and cleanup operations.
- Execution operations.
- Verification/oracle.
- Required evidence.

`source_refs` is metadata only. Runtime execution must not resolve it, and execution artifacts such as expected results, fixtures, SQL, payloads, mock mappings, and test data belong in `artifact://` refs, operation `with`, or verify expected refs.

Example:

```yaml
dsl_version: v0.3
test_case_id: HTTP-MOCK-REST-CLIENT-V03-TC-001
title: HTTP mock target is consumed by REST client target without Provider Instance

setup:
  - id: load_payment_stub
    target: payment_mock
    op: load_stubs
    with:
      mock.mappings_ref: artifact://fixtures/wiremock/payment_success_stub.json
      mock.reset_before_load: true

execute:
  - id: call_payment_api
    target: payment_api
    op: http_request
    with:
      request.method: POST
      request.path: /payments
      request.body_ref: artifact://fixtures/payment_request.json

verify:
  - id: payment_response_status
    type: assertion
    assert:
      actual: step://call_payment_api/response.status
      operator: equals
      expected: 200
  - id: payment_response_body
    type: assertion
    assert:
      actual: step://call_payment_api/response.body
      operator: json_match
      expected_ref: artifact://expected_results/payment_response.json

cleanup:
  - id: reset_payment_mock
    target: payment_mock
    op: reset_mock
    with: {}
```

The matching suite manifest declares the Provider Contracts:

```yaml
manifest_version: v0.3
targets:
  payment_mock:
    provider_contract: http_mock.v0.3
  payment_api:
    provider_contract: rest_client.v0.3
```

The matching Env_Profile supplies runtime mode and bindings:

```yaml
profile_id: local_v03
targets:
  payment_mock:
    runtime_mode: mock
    bindings:
      port_strategy: dynamic
  payment_api:
    runtime_mode: mock
    bindings:
      base_url: generated://payment_mock/base_url
```

`payment_mock` must have an explicit preceding provider operation in the same test case, normally a `setup` operation such as `load_stubs`, which produces the bindable `base_url`. The framework materializes `payment_api.base_url` immediately before the client step. It does not start targets at suite initialization, and the generated value cannot cross into another test case.

## 7. Artifact Refs and Operation Inputs

Use `artifact://...` when a test needs reviewed fixtures, SQL, mock mappings, schemas, or expected results. Artifact refs resolve under the suite manifest `artifact_roots` and must not escape the suite directory. Use safe inline literals only for small technical values such as `POST`, `/payments`, or `true`.

v0.3 test cases do not require a predeclared data catalog. Legacy `data_binding`, `data`, `input_data`, `setup_data`, `cleanup_data`, `expect_data`, `datasets`, `fixtures`, `expected_results`, `db_seed`, `db_cleanup`, `mock_stubs`, `parameters`, and `bind_as` are prohibited in v0.3 DSL artifacts.

Artifact root example:

```yaml
artifact_roots:
  fixtures: fixtures/
  expected_results: expected_results/
  queries: queries/
```

Common `artifact://` values:

```yaml
artifact://fixtures/payment_request.json
artifact://fixtures/wiremock/payment_success_stub.json
artifact://queries/find_order_by_id.sql
artifact://expected_results/payment_response.json
```

Use operation `with` for where the provider receives data. Each `with` key must be allowed by the Provider Contract operation resolved from the suite target.

```yaml
with:
  request.body_ref: artifact://fixtures/payment_request.json
  query_ref:
    ref: artifact://queries/find_order_by_id.sql
  bind_variables:
    order_id: ORD-1001
```

A DSL test case is invalid if it uses an `op`, `with` key, output ref, or verify `actual` ref that is not allowed by the Provider Contract resolved from the suite target.

`step://<step_id>/<output>` is test-case scoped: it may reference only an earlier
provider operation in the same test case. It cannot read a same-named step from
another test case. A dotted subpath is valid when its root output is declared,
for example `step://call_payment_api/response.body.order_id` requires the
operation to declare `response.body`. If the full dotted name is not itself a
declared output id, its declared root output must be an object; scalar outputs
cannot be used as nested references.

Runtime connection and authentication values belong to Env_Profile target bindings, not test-case data. DSL test cases must not bind `secret.*` directly. Runtime endpoints, tokens, DB credentials, broker credentials, kubeconfig, SSH keys, and runner credentials must be supplied through `env_profiles/` or generated `generated-framework/env_profiles/`.

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
expected_ref: artifact://expected_results/payment_response.json
```

Rules:

- Use `expected` for small technical constants such as status code, count, or boolean readiness.
- Use `expected_ref` for owner-approved business output, event payloads, DB state, files, or complex JSON.
- `expected_ref` must point to an approved artifact for release-readiness tests.
- `actual` must resolve to a `step://...` output ref allowed by the resolved Provider Contract or to a framework-generated evidence ref.

### 8.3 Verify Contract Catalog

For v0.3, `verify` accepts only `type: assertion` and `type: provider_check`. The first four rows below are the framework-owned v0.3 assertion catalog. The remaining named verifier rows document v0.2 compatibility syntax only; a v0.3 leaf test must express the same observation through a Provider Contract `provider_check`, then a following assertion. `provider_check.expect` is prohibited.

| Verify Type | Required Fields | Optional Fields | Expected Source | Common Failure Codes |
| --- | --- | --- | --- | --- |
| `assertion` with `operator: equals` | `assert.actual`, `assert.expected` | `selector`, `severity`, `evidence` | Inline value or approved expected result | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND`, `SELECTOR_NOT_FOUND` |
| `assertion` with `operator: json_match` | `assert.actual`, `assert.expected_ref` | `ignore_paths`, `normalize`, `ignore_order`, `severity`, `evidence` | Approved JSON expected artifact | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
| `assertion` with `operator: schema_match` | `assert.actual`, `assert.schema_ref` | `ignore_paths`, `severity`, `evidence` | Committed schema artifact | `SCHEMA_MISMATCH`, `ASSERTION_INPUT_NOT_FOUND` |
| `assertion` with `operator: file_diff` | `assert.actual`, `assert.expected_ref` | `normalize`, `ignore_order`, `ignore_paths`, `severity`, `evidence` | Approved expected file | `ASSERTION_FAILED`, `ASSERTION_INPUT_NOT_FOUND` |
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
  - id: http_status_is_200
    type: assertion
    assert:
      actual: step://call_payment_api/response.status
      operator: equals
      expected: 200
```

#### JSON Match

```yaml
verify:
  - id: payment_response_body
    type: assertion
    assert:
      actual: step://call_payment_api/response.body
      operator: json_match
      expected_ref: artifact://expected_results/payment_response.json
```

#### JSON Schema Matches

```yaml
verify:
  - id: response_matches_schema
    type: assertion
    assert:
      actual: step://call_payment_api/response.body
      operator: schema_match
      schema_ref: artifact://expected_results/payment_response.schema.json
```

#### Event Payload Match

```yaml
verify:
  - id: payment_event_received
    type: assertion
    assert:
      actual: step://publish_order_event/message
      operator: json_match
      expected_ref: artifact://expected_results/order_event.json
```

#### Event Not Published

```yaml
verify:
  - id: no_rejection_event
    type: event_not_published
    target: order_events
    event:
      subject: orders.rejected
    expected:
      match:
        eventType: ORDER_REJECTED
    options:
      timeout: PT10S
```

#### DB Record Exists

```yaml
verify:
  - id: order_db_state_exists
    type: db_record_exists
    target: order_db
    query:
      ref: artifact://queries/find_order_by_id.sql
      params:
        order_id: ORD-1001
    expected:
      min_rows: 1
```

### 8.4 Eventual Verification

Use explicit `options.timeout` and `options.poll_interval` when behavior is asynchronous.

```yaml
verify:
  - id: settlement_event_eventually_received
    type: event_published
    target: settlement_events
    event:
      subject: settlement.completed
    expected_ref: artifact://expected_results/settlement_event.json
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
type: assertion
operator: equals
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
- suite target, Provider Contract, runtime mode, and profile
- related AC or expected-result reference

The report must be able to trace:

```text
AC -> test case -> execute step -> verify item -> evidence -> result
```

### 8.7 Verify Design Rules

- `verify` must check observable output only.
- `verify` must not call hidden product logic.
- `verify` must not infer expected business behavior from actual runtime behavior.
- `assert.actual` must resolve to a valid provider output ref or framework evidence ref.
- Assertions may evaluate `public`, `masked`, or `secret` outputs, but assertion
  evidence serializes non-public values as `[MASKED]`; the raw value is never
  written to result JSON, report output, or evidence files.
- `expected_ref` must point to approved expected-result artifacts for business assertions.
- Async verification must use explicit timeout and polling policy.
- Failed cleanup is not a passed test; it should be reported separately as cleanup evidence.
- Diagnostic assertions may be non-blocking, but blocking assertions determine test result.

## 9. Provider Contract Rule

Built-in Provider Contracts are owned by the framework. RP/suite repositories should not copy them into each suite. A suite-local Provider Contract is allowed only when the suite explicitly declares a custom provider or contract snapshot pinning mode.

A Provider Contract defines the public executable surface for one provider capability. In v0.3, suite authors do not copy or author Provider Instance files for built-in providers.

| Domain | Provider Contract Defines | Suite / Env_Profile Supplies |
| --- | --- | --- |
| `provider_contract` | Stable contract id, such as `jdbc.v0.3` or `rest_client.v0.3`. | Suite manifest `targets.<target>.provider_contract`. |
| `runtime_modes` | Allowed runtime modes and which modes are executable by this framework build. | Env_Profile `targets.<target>.runtime_mode`. |
| `binding_keys` | Required and optional binding keys, value types, allowed value kinds, defaults, and generated-ref rules. | Env_Profile `targets.<target>.bindings`. |
| `bindable_outputs` | Provider Contract outputs that another Env_Profile target may reference, such as `generated://payment_mock/base_url`. | Provider runtime produces the declared value. |
| `operations` | Allowed `op` names, allowed `with` keys, output refs, and supported phases. | DSL `setup`, `execute`, `verify`, and `cleanup` reference the operation. |
| `evidence` | Allowed evidence outputs and masking requirements. | Runtime writes evidence and result refs. |
| `safety` | Required safety rules for command-capable providers. | Env_Profile and approved owner policy supply the allowed runtime access values. |
| `failure_mapping` | Allowed failure codes and categories. | Runtime maps failures to those codes. |

A v0.3 suite is invalid if a target references an unknown Provider Contract, an Env_Profile omits a required binding key, a binding value kind is not allowed, a DSL `op` is unsupported, a `with` key is unsupported, or a `step://...` output ref is not declared by the Provider Contract operation. Provider runtimes must return only declared output refs; an undeclared output is blocked before it can enter the execution context or persisted result.

### 9.1 Command-Capable Provider Access Policy

Providers that can execute commands or collect host/runtime data must define contract-owned safety requirements under `safety.rules` when required by the Provider Contract or Env_Profile execution mode. The selected Env_Profile must explicitly supply the matching `safety.access_policy` and, where required, `safety.approval`.

External runner approval is provider safety approval, not release approval.

This applies at minimum to:

- `kubernetes_runtime` with `exec_command`.
- `vm_runtime` with `run_command`, `collect_file`, or `collect_logs`.
- `external_runner` with `run` or `run_and_collect`.

Command-capable Env_Profile target bindings should declare:

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

If a command-capable Env_Profile target does not define required `safety.access_policy`, readiness validation must fail.

## 10. Provider Contracts and Suite Targets

The canonical built-in Provider Contracts are materialized under `docs/02-architecture/contracts/provider-contracts/` and indexed by `docs/02-architecture/contracts/provider_capability_registry.v0.2.yaml`. Start with the [Provider Contract Catalog](../02-architecture/contracts/provider-contracts/README.md) to map `provider_contract` ids such as `jdbc.v0.3` to contract YAML files and sample suites. The user guide must not redefine a second provider contract catalog. Runtime suite manifests use this built-in catalog by default.

Framework `0.3.0` has two public surfaces:

- v0.2 compatibility suites use Provider Instances, Env_Profiles, and the v0.2 provider capability registry.
- v0.3 suites do not use Provider Instance files. They declare suite targets with `provider_contract`, and Env_Profile `targets.<target>` supplies `runtime_mode` and `bindings`.

Framework `0.3.0` public provider support is defined in `docs/09-operations/provider_support_matrix.md`. That matrix is keyed by provider type and `support_status`; runtime lifecycle details such as native, mock, stub, ephemeral, or framework are Env_Profile details, not public support statuses.

RP/suite repositories do not need a `provider_contracts/` folder for built-in v0.3 Provider Contracts such as `http_mock.v0.3`, `rest_client.v0.3`, `jdbc.v0.3`, `nats.v0.3`, `kafka.v0.3`, `ibm_mq.v0.3`, `artifact_compare.v0.3`, `common_verify.v0.3`, or `polling_observer.v0.3`. Suite-local contracts are an explicit opt-in for custom provider plugins or contract snapshot pinning:

```yaml
provider_contract_resolution:
  mode: suite_override
  custom_provider_contracts: custom_provider_contracts/
  allowed_provider_types:
    - custom_runner
```

When this section is absent, the resolution mode is `framework_builtin`.

For v0.3 suites, the target contract catalog is keyed by `provider_contract` ids such as `http_mock.v0.3`, `rest_client.v0.3`, `jdbc.v0.3`, `nats.v0.3`, `kafka.v0.3`, `ibm_mq.v0.3`, `artifact_compare.v0.3`, `common_verify.v0.3`, and `polling_observer.v0.3`. A v0.3 suite target example:

```yaml
targets:
  payment_mock:
    provider_contract: http_mock.v0.3
  payment_api:
    provider_contract: rest_client.v0.3
```

The matching Env_Profile uses the same target names:

```yaml
targets:
  payment_mock:
    runtime_mode: mock
    bindings:
      port_strategy: dynamic
  payment_api:
    runtime_mode: mock
    bindings:
      base_url: generated://payment_mock/base_url
```

Do not add Provider Instance files for v0.3 suites.

A Provider Contract defines reusable rules for one `provider_type`:

- `provider_contract_version`
- `provider_type`
- `runtime_modes`
- `executable_runtime_modes` when only a subset is runnable by the current framework build
- `contract_only_runtime_modes` when remaining modes are vocabulary for future implementation
- `binding_keys`
- `bindable_outputs`
- `defaults`
- `valid_env_profile_target_shape`
- `safety`
- `operations`
- `evidence`
- `failure_mapping`

A v0.2 compatibility Provider Instance defines one logical runtime target for an RP. It must use the top-level shape allowed by its Provider Contract and must not contain physical endpoint, topic, DB credential, namespace, host, or secret values. It declares `runtime_modes` as the subset it may use; the selected `runtime_mode` and all Provider Contract `binding_keys` are supplied by Env_Profile for the active environment. For messaging client providers, destination keys such as Kafka `topic`, Kafka `consumer_group`, and IBM MQ `queue` are Env_Profile bindings. v0.3 suites model those choices as suite targets plus Env_Profile target bindings instead of Provider Instance files.

### 10.1 Built-In Provider Contract Catalog

The built-in catalog is documented in
[`docs/02-architecture/contracts/provider-contracts/README.md`](../02-architecture/contracts/provider-contracts/README.md).
Use it to map v0.3 contract ids to YAML files and samples, for example:

- `jdbc.v0.3` -> `docs/02-architecture/contracts/provider-contracts/jdbc_v0_3.yaml`
- `rest_client.v0.3` -> `docs/02-architecture/contracts/provider-contracts/rest_client_v0_3.yaml`
- `http_mock.v0.3` -> `docs/02-architecture/contracts/provider-contracts/http_mock_v0_3.yaml`

The contract YAML is the source of truth for required `binding_keys`, allowed
`runtime_mode` values, operation `op` names, allowed `with` keys, output refs,
evidence rules, and failure codes.

In v0.3 provider capability mode, `rest_client.v0.3` is executable for checked-in HTTP mock + HTTP request samples. It resolves `base_url` from Env_Profile, including generated mock outputs such as `generated://payment_mock/base_url`, executes `http_request`, exposes `response.status`, `response.headers`, `response.body`, and `response.duration_ms`, and writes HTTP request/response evidence. Downstream SIT/preprod endpoint validation still requires owner-provided RP artifacts and real Env_Profiles.

#### `wiremock_http_mock` External `base_url`

`wiremock_http_mock.base_url` has one narrow meaning: connect to an owner-provisioned WireMock-compatible mock server that exposes the WireMock Admin API. It is not a generic external REST endpoint binding.

| Case | Provider | Binding | Framework Responsibility |
| --- | --- | --- | --- |
| Framework starts local WireMock | `wiremock_http_mock` | `port_strategy`, optional `mappings_ref` | Start/stop mock process, load stubs, verify request journal, emit generated `base_url`. |
| Owner already started WireMock-compatible mock | `wiremock_http_mock` | `base_url` | Connect to Admin API, load/reset stubs, verify request journal, record `framework_started_wiremock: false`. |
| Owner provides SUT or external HTTP API | `rest_client` | `base_url` | Send HTTP request and capture request/response evidence. |

External `wiremock_http_mock.base_url` must be an HTTP(S) root URL for a WireMock-compatible server, must not include userinfo or secret-like query parameters, and must support Admin API calls such as `/__admin/mappings`, `/__admin/reset`, and request journal access. In this mode the framework must not allocate ports, start a process, or claim ownership of server lifecycle. `connect_mock` is the preferred operation name for external mode; existing v0.2 flows that call `load_stubs` may connect first when `base_url` is supplied. Generic project-provisioned HTTP endpoints, even if implemented by WireMock behind the scenes, must be modeled as `rest_client.base_url` unless the framework needs WireMock Admin API behavior.

`soap_mock` and `grpc_mock` are PR-008 WireMock-backed mock capabilities. `soap_mock` is executable in PR-008A through WireMock HTTP/XML/SOAP behavior for SOAPAction/header and XPath matching. `grpc_mock` is executable in PR-008B through the WireMock gRPC extension and descriptor refs for unary calls. They are mock providers for local/CI framework evidence; they do not prove downstream SIT/preprod release readiness and do not imply custom SOAP/gRPC server ownership by the framework.

`kafka`, `ibm_mq`, and `nats` are client provider contracts with framework-owned mock/local capability runtimes and native client runtimes for externally provisioned broker or queue-manager endpoints. Their Provider Contracts describe how the test runner consumes Env_Profile binding keys and writes framework evidence without starting brokers, queue managers, Testcontainers, or RUs. Public CI release gates validate external profiles and run native external messaging samples only when broker or queue-manager bindings are configured. A single messaging suite may include Kafka and IBM MQ test cases together when every test case uses the same selected Env_Profile and each test case has exactly one messaging runtime target. External broker or queue-manager values must be materialized into `value`, `secret_ref`, or approved `local_ref` before framework execution; client providers only consume resolved bindings.

`jdbc` supports deterministic local verification plus optional native external verification. `local_v03` uses approved framework-owned local database bindings for deterministic local/CI evidence. Native external JDBC uses the same v0.3 suite with `external_oracle` or `external_db2` Env_Profile selection. Each external Env_Profile uses `connection.secret_ref: env://JDBC_CONNECTION`. `env://JDBC_CONNECTION` is the canonical public secret-ref name and must be written in uppercase exactly; mixed-case variants are invalid. The runner process supplies the actual JDBC URL through the environment variable `JDBC_CONNECTION`, not through a literal `env://...` value. The framework resolves `JDBC_CONNECTION` only at runtime, masks the resolved value from result/evidence/report output, and fails owner-actionably with `SECRET_RESOLUTION_ERROR` when the env var is missing. External Oracle/DB2 suites assume the owner has pre-provisioned the target schema and `ORDERS` table. Release CI validates both external profiles by default and runs exactly one native external profile only when `REQUIRE_EXTERNAL_JDBC=true`; `JDBC_EXTERNAL_DIALECT=oracle|db2` selects which profile to execute.

### 10.2 v0.2 Provider Instance Examples

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

Before dispatch, the framework validates the selected authoring model.

For v0.3 suites:

- `suite_manifest.targets.<target>.provider_contract` exists in the framework catalog or an approved contract root.
- Env_Profile exists for the selected profile.
- Env_Profile has `targets.<target>` for every suite target referenced by selected test cases.
- Env_Profile `targets.<target>.bindings` supplies every required Provider Contract `binding_key`.
- Env_Profile binding value kinds are allowed by the Provider Contract `binding_keys`.
- Env_Profile `generated://<target>/<output>` refs resolve only to a compiler-selected, preceding `PROVIDER_OPERATION` in the same test case whose Provider Contract declares that output bindable. The framework materializes the binding only for its consumer step; `generated://` is not valid in DSL `with` values or assertion operands.
- Selected `runtime_mode` is allowed by the Provider Contract and the active Env_Profile.
- Each `op` exists in the Provider Contract.
- Every `with` key is allowed by that Provider Contract operation.
- Every output ref used by DSL, evidence, or verify exists in the Provider Contract operation.

For v0.2 compatibility suites, the framework also validates Provider Instance existence, Provider Instance shape, and `providers.<provider_id>.bindings` against the Provider Contract.


## 11. Env_Profile

Env_Profile supplies actual environment values for Provider Contract `binding_keys` and defines the execution mode. In v0.3, the `targets` map is keyed by suite target name. In v0.2 compatibility mode, the `providers` map is keyed by Provider Instance `provider_id`.

Secrets must be referenced, not committed.

```yaml
env_profile_id: sit_payment
execution_mode: sit
targets:
  payment-api:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-api-sit.yaml
    bindings:
      base_url: https://payment-api.sit.example.com

  payment-db:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-db-sit.yaml
    bindings:
      connection:
        secret_ref: vault://sit/payment/db-connection
      dialect: oracle

  payment-events:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-events-sit.yaml
    bindings:
      bootstrap_servers:
        secret_ref: vault://sit/payment/kafka-bootstrap
      topic: payment.events
      consumer_group: artf-payment-sit

  payment-mq:
    runtime_mode: native
    readiness_ref: evidence/readiness/payment-mq-sit.yaml
    bindings:
      queue_manager: QM.PAYMENT.SIT
      channel: APP.SVRCONN
      conn_name:
        secret_ref: vault://sit/payment/mq-conn-name
      queue: PAYMENT.REQUEST.SIT
      credential:
        secret_ref: vault://sit/payment/mq-credential
```

Each target binding must declare the selected runtime mode. Local and CI Env_Profiles usually replace external services, databases, and messaging with mocks, stubs, fake topics, embedded brokers, ephemeral DBs, disposable schemas, or generated data.

The `bindings` under each target must match the keys defined by that target's Provider Contract `binding_keys`. Direct scalar values are treated as `value`; structured values may use `ref`, `secret_ref`, `generated_ref`, or approved `local_ref` only when the Provider Contract allows that value kind. Use `local_ref` only for framework-controlled local/CI fixtures; it must not be used as SIT/preprod release evidence.

```yaml
env_profile_id: local_v03
execution_mode: local
targets:
  wiremock-payment-api:
    runtime_mode: mock
    bindings:
      mappings_ref:
        ref: fixtures/wiremock/payment_mappings
      port_strategy: dynamic

  payment-api-client:
    runtime_mode: native
    bindings:
      base_url:
        generated_ref: generated://wiremock-payment-api/base_url

  payment-soap-mock:
    runtime_mode: mock
    bindings:
      endpoint_url:
        generated_ref: generated://payment-soap-mock/endpoint_url
      wsdl_ref:
        ref: contracts/payment.wsdl

  customer-grpc-mock:
    runtime_mode: mock
    bindings:
      descriptor_ref:
        ref: proto/customer.desc
      service_name: CustomerService

  customer-grpc-client:
    runtime_mode: native
    bindings:
      target:
        generated_ref: generated://customer-grpc-mock/target_uri

  payment-db:
    runtime_mode: ephemeral
    bindings:
      connection:
        local_ref: approved_local_h2_oracle
      dialect: oracle

  payment-events:
    runtime_mode: mock
    bindings:
      bootstrap_servers: localhost:19092
      topic: payment.events.test
      consumer_group: artf-payment-ci

  payment-mq:
    runtime_mode: mock
    bindings:
      queue_manager: QM.PAYMENT.CI
      channel: APP.SVRCONN
      conn_name: localhost(1414)
      queue: PAYMENT.REQUEST.CI
      credential:
        secret_ref: env://PAYMENT_MQ_CREDENTIAL_REF
```

`generated_ref` can reference outputs declared in the producer Provider Contract `bindable_outputs`, such as `generated://payment_mock/base_url` from a framework-owned mock target in the same suite. Undeclared, cyclic, or missing-producer generated refs are blocked before provider dispatch. Use a literal `value`, `secret_ref`, or approved local/CI-only `local_ref` when the dependency value has already been materialized.

`sit` and `preprod` Env_Profiles default to `runtime_mode: native`. Mock substitution in those execution modes must not be used as downstream RP release evidence.

## 12. Execution Mode and Dependency Policy

Env_Profile defines what is allowed to run in an environment. For normal local/CI samples, the minimal public shape is enough:

```yaml
env_profile_id: local_v03
execution_mode: local
targets:
  wiremock-payment-api:
    runtime_mode: mock
    bindings:
      port_strategy: dynamic
      mappings_ref:
        ref: fixtures/wiremock/
  payment-api-client:
    runtime_mode: native
    bindings:
      base_url:
        generated_ref: generated://wiremock-payment-api/base_url
```

When omitted, framework defaults apply for isolation scope, max duration, dependency policy, dependency substitution policy, dependency provisioning policy, data policy, and evidence policy. Add policy sections only when the profile needs stricter behavior than defaults, such as SIT/native-only execution or externally provisioned CI services.

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

Local and CI Env_Profiles may reference ephemeral dependencies only after those dependencies have been provisioned outside the framework runtime and declared through standard artifacts. This is separate from client providers: Kafka and IBM MQ client providers consume resolved connection values but do not create brokers or queue managers. v0.3 accepts `generated://<target>/<output>` refs only when the producer Provider Contract lists `<output>` in `bindable_outputs`; unresolved generated refs block plan compilation before provider dispatch.

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

targets:
  payment-db:
    runtime_mode: ephemeral
    bindings:
      connection:
        local_ref: approved_local_h2_oracle
      dialect: oracle

  payment-events:
    runtime_mode: mock
    bindings:
      bootstrap_servers: localhost:9092

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

Validate checks the DSL, suite manifest, suite targets, Env_Profile, framework built-in Provider Contract catalog, secret guardrails, result schema, and evidence contract without provider execution. v0.2 compatibility suites additionally validate Provider Instance artifacts.

Malformed suite YAML is a validation failure. The command returns `validation_status: failed` with `reason: invalid_yaml`, does not enter provider runtime, and requires the owner to fix the YAML or referenced contract artifact before retrying.

```bash
regress validate \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03
```

Framework-owned samples may use suite-path mode. This mode is for framework verification only and must not be treated as downstream RP release evidence.

Golden E2E proves the framework lifecycle with a deterministic fake provider:

```bash
regress validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>
```

Golden E2E suite-path mode may execute only deterministic framework-owned fake providers. It must not start WireMock, JDBC, NATS, Kafka, REST/gRPC, K8s, VM, external runner, SIT, or downstream product deployment.

Provider Capability suite-path mode proves selected v0.3 provider capabilities as framework evidence. A standard suite run creates one `batch_id`, one `run_id`, one result JSON, and per-test outcomes in `test_results[]`; all selected `tests[]` share the selected Env_Profile. Provider identity for suite-level reporting comes from `provider_summary[]` and `provider_results[]`. Multi-provider standard results, inferred from either `test_results[]` or `provider_results[]`, must include `provider_summary[]`. Top-level `provider_id`, `provider_type`, or destination fields are single-provider compatibility fields only and must not be used to summarize a multi-provider suite.

The v0.3 golden sample proves the simplified no-Provider-Instance lifecycle with the deterministic fake provider:

```bash
regress validate --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml --profile local_v03

regress run \
  --suite samples/00-getting-started/golden_e2e/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>
```

This sample is framework verification evidence only. It proves v0.3 suite target resolution, Env_Profile target binding validation, runtime execution, evidence writing, and report consumption without external providers.

The v0.3 HTTP mock plus REST client sample proves protocol-level authoring without Provider Instance files:

```bash
regress validate --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml --profile local_v03

regress run \
  --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml \
  --profile local_v03 \
  --dry-run
```

This sample resolves `http_mock.v0.3` and `rest_client.v0.3` targets and executes provider runtime without Provider Instance files.

The v0.3 local runtime sample matrix includes:

| Path | Provider Contracts |
| --- | --- |
| `samples/20-provider-capability-p0/http/rest_client_with_wiremock/` | `http_mock.v0.3`, `rest_client.v0.3` |
| `samples/20-provider-capability-p0/data/jdbc/` | `jdbc.v0.3` |
| `samples/20-provider-capability-p0/verification/artifact_compare/` | `artifact_compare.v0.3` assertion-only |
| `samples/20-provider-capability-p0/verification/common_verify/` | `common_verify.v0.3` assertion-only |
| `samples/20-provider-capability-p0/verification/polling_observer/` | `polling_observer.v0.3` provider-check observation |
| `samples/20-provider-capability-p0/messaging/nats/` | `nats.v0.3` |
| `samples/20-provider-capability-p0/messaging/kafka/` | `kafka.v0.3` |
| `samples/20-provider-capability-p0/messaging/ibm_mq/` | `ibm_mq.v0.3` |
| `samples/20-provider-capability-p0/messaging/kafka_ibm_mq_mixed/` | `kafka.v0.3`, `ibm_mq.v0.3` |
| `samples/20-provider-capability-p0/rpc/soap_mock/` | `soap_mock.v0.3`, `rest_client.v0.3` |
| `samples/20-provider-capability-p0/rpc/grpc_mock/` | `grpc_mock.v0.3`, `grpc_client.v0.3` |
| `samples/20-provider-capability-p0/verification/multi_test_shared_env/` | shared Env_Profile multi-test assertion suite |
| `samples/30-cross-provider-groups/mixed_provider_e2e/` | one test case across `http_mock.v0.3`, `rest_client.v0.3`, `jdbc.v0.3`, and `nats.v0.3` |

Release verification runs each listed v0.3 suite through validate, dry-run, run, report, and validate-evidence.

The v0.3 contract baseline sample is an executable mixed-provider framework verification suite:

```bash
regress validate --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml --profile local_v03

regress run \
  --suite samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>
```

This sample dispatches only the checked-in `http_mock.v0.3` + `rest_client.v0.3` + `jdbc.v0.3` + `nats.v0.3` combination. It uses local/CI deterministic bindings and always reports `release_evidence_eligible: false`.

`regress validate-evidence --result <generated_result_json>` and `regress report --result <generated_result_json>` validate the standard result JSON before publishing or reporting. A v0.3 result must include `result_contract_version`, suite/run identity, `test_count`, `test_results`, timestamps, `completion_status`, nullable `termination_reason`, and `suite_summary_ref`. `test_count` is a JSON integer equal to `test_results.length`; allowed v0.3 test statuses are `passed`, `failed`, `blocked`, and `skipped`. Quoted numeric strings such as `"1"` are invalid. Invalid suite summaries return a non-zero exit and must be fixed before publication.

```bash
regress validate --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>

regress validate --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>

regress validate \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile external_oracle

regress validate \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile external_db2

JDBC_CONNECTION='<jdbc-url>' regress run \
  --suite samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml \
  --profile external_oracle

regress validate-evidence --result <generated_result_json>
```

The HTTP mock + REST client sample keeps the canonical happy path in `suite_manifest.yaml` under one shared Env_Profile. Deterministic failure fixtures belong under `samples/80-negative/`; v0.2 WireMock failure/boundary fixtures are compatibility material under `samples/90-compatibility/legacy-v0.2/`.

The JDBC provider capability sample keeps fixture/query/cleanup behavior in the canonical local `suite_manifest.yaml` `tests[]` list. Running it with `--profile local_v03` executes the checked-in sample under one shared Env_Profile.

For native external JDBC evidence, the checked-in external Env_Profiles keep `connection.secret_ref: env://JDBC_CONNECTION` but are split by dialect so one run targets exactly one external database. Owners must provide `JDBC_CONNECTION` in the runner environment, for example `JDBC_CONNECTION='<jdbc-url>' regress run ...`, and must select the matching external profile. Do not write raw JDBC URLs into DSL or Env_Profile files.

Provider Capability suite-path mode may execute only checked-in framework provider capability samples for WireMock HTTP mock, `rest_client` HTTP request, SOAP mock, gRPC unary mock, JDBC Oracle/DB2-style verification, NATS event verification, JSON/schema/file diff, polling, and evidence/report behavior. It must not execute non-P0 providers, Product/RP/RU topology interpretation, release governance, SIT/preprod release evidence, or downstream product deployment.

Usage-kit runtime-mode coverage is represented by v0.3 suite targets and Env_Profile examples. Provider Instance files are v0.2 compatibility artifacts only and are not part of the v0.3 public sample surface.

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
| `samples/80-negative/` | v0.3 expected-failure validation and runtime samples. |

A leaf suite owns `tests[]` in its `suite_manifest.yaml`. A suite group owns `child_suites[]` and no `tests[]`; every child `ref` must remain inside the suite group directory after path normalization. Runtime-mode contract samples labeled `sample_scope: usage_kit_runtime_mode_sample` are coverage artifacts and are not executable targets.

### 13.2 Mock Server Usage

Mock server providers replace external REST, SOAP, or gRPC dependencies with deterministic checked-in stubs for local/CI framework verification. They are provider capability evidence only and must not be treated as downstream SIT/preprod RP release evidence.

| Provider Type | Purpose | Typical Client | Generated Binding Output |
| --- | --- | --- | --- |
| `http_mock` | REST/HTTP stubs and request journal verification | `rest_client` | `base_url` |
| `soap_mock` | SOAP-over-HTTP XML/SOAPAction stubs and request verification | HTTP/SOAP request flow | `endpoint_url` |
| `grpc_mock` | Unary gRPC mock behavior through WireMock gRPC extension | `grpc_client` | `target_uri` |

Mock server lifecycle:

1. `validate` checks suite targets, test case DSL, Env_Profile target bindings, stubs, descriptors, and expected refs.
2. `run --dry-run` resolves mock server and client targets without starting runtime.
3. `run` executes every selected test in the suite with one selected Env_Profile.
4. Test case `setup` loads checked-in stubs or mappings.
5. Test case `execute` calls the client provider, such as `rest_client` or `grpc_client`.
6. Test case `verify` checks response, request journal, SOAP request, gRPC request, or assertion output.
7. Cleanup resets or stops mock runtime and records evidence.

DSL rules:

- Test cases reference suite target names only. The active profile is selected once for the suite by CLI `--profile` or suite manifest `profile`; test cases must not embed generated endpoint URLs or select a different runtime profile.
- Mock stubs, mappings, descriptors, and expected results must be checked-in artifacts referenced by `ref`.
- Env_Profile supplies or resolves binding keys.
- Generated mock outputs, such as `generated://payment_mock/base_url`, are bound through Env_Profile and consumed by client providers.
- WireMock-backed mock server providers are protocol-specific: `http_mock` is the v0.3 HTTP mock surface, `soap_mock` is the SOAP/XML mock surface, and `grpc_mock` is the unary gRPC mock surface. All three may use WireMock runtime internals, but their Provider Contracts stay protocol-specific.
- `http_mock` uses `port_strategy: dynamic` plus checked-in HTTP mappings and produces `generated://payment_mock/base_url` for HTTP client providers. Keep `wiremock_http_mock` only as a v0.2 compatibility provider type.
- External HTTP endpoints, including a project-provisioned WireMock server used only as a generic REST endpoint, must be modeled as `rest_client` with an Env_Profile `base_url`. The test framework does not need to know that the external endpoint is WireMock unless it is managing mock lifecycle, stubs, or request journal evidence.
- `grpc_mock` supports unary calls only in the current v0.3 sample set.

Evidence generated by mock server samples includes request journals, server logs, client request/response evidence, assertion diffs, suite summaries, and raw Allure result files when running a child-suite aggregation manifest.

### 13.3 Provider Capability Multi-Test Suites

The primary multi-test runner model is a standard suite manifest with `tests[]`, where every selected test case shares the same suite profile and Env_Profile. Single-suite provider capability runs may mix supported executable provider types, such as Kafka and IBM MQ client-provider test cases, when the selected Env_Profile contains target bindings for each referenced suite target.

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
  --profile local_v03
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

A successful v0.3 run writes canonical `result.json`, `suite_summary.json`, `suite_summary.yaml`, `evidence_index.yaml`, and optional raw Allure result files under `target/regression/<suite_id>/<batch_id>/<run_id>/`. A suite group stores each child below `children/<child_suite_id>/<child_run_id>/`; every child uses the parent `batch_id` and a unique child `run_id`.

The canonical summary exposes `self_summary`, `child_aggregate_summary`, `total_summary`, `failure_summary`, `evidence_summary`, ordered `children`, and `aggregation_errors`. Parent counts come from each immediate child's validated `total_summary`; parents do not inspect grandchildren. A post-execution invalid child produces a partial blocked parent with `termination_reason: aggregation_error`. Structural preflight blockers still produce no run artifacts.

Use `regress report --result <result_json>` for canonical reporting. Text and JSON reports add completion, timing, self/child/total counts, child status, and aggregation errors while preserving existing report keys. Reporting an unversioned legacy summary is compatibility-only and does not claim canonical evidence validation.

Suite-mode output paths are deterministic:

| Run Type | Output Path | Canonical Artifact |
| --- | --- | --- |
| Direct suite | `target/regression/<suite_id>/<batch_id>/<run_id>/` | `result.json`, `suite_summary.json` |
| Suite group | `target/regression/<suite_id>/<batch_id>/<run_id>/` plus `children/<child_suite_id>/<child_run_id>/` | Parent and child `result.json`, `suite_summary.json` |
| Report | Reads the printed `result_json` in the same run directory | Deterministic text, YAML, or JSON summary |

Every direct suite result must reference an `evidence_index.yaml`; every suite group summary must include child suite status, `passed_count`, `failed_count`, `blocked_count`, and `status_taxonomy`. Expected-failure children are reported as `expected_failed_observed` only after execution. Preflight blockers remain `blocked` and do not produce run artifacts.

PR-008A SOAP provider capability samples use separate suite manifests:

```bash
regress validate --suite samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml \
  --profile local_v03
```

PR-008B gRPC provider capability samples use the same pattern:

```bash
regress validate --suite samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml

regress run \
  --suite samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml \
  --profile local_v03

regress report --result <generated_result_json>
```

SOAP/gRPC mock samples may start WireMock-backed mock services at suite/batch scope. Test case `setup` loads stubs; it must not start RU, restart RU, or embed generated endpoint values in the DSL. gRPC mock scope is unary only in the current v0.3 sample set.

Dry run validates the same contract graph and produces a resolved execution plan. It should not perform real test execution.

```bash
regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_v03 \
  --dry-run
```

Run approved tests in the suite:

```bash
regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_v03
```

Run by tag:

```bash
regress run \
  --suite samples/20-provider-capability-p0/suite_manifest.yaml \
  --profile local_v03 \
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
evidence_index_version: v0.3
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

Every indexed evidence ref must be a regular file inside the current run
directory. Refs that escape the run directory, are missing, or exceed the
framework evidence sanitization limit are rejected rather than being marked as
masked.

Generate a release-level report:

```bash
regress validate-evidence \
  --result <generated_result_json>

regress report \
  --result <generated_result_json> \
  --format text
```

Use `--result` for v0.3 suite-mode release readiness. Product/RP-specific report forms are outside the v0.3 framework runtime public interface.

Evidence must answer:

- Which AC was tested?
- Which test case ran?
- Which suite target and profile were used?
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
6. Report missing AC, expected result, unknown provider contract, suite target, Env_Profile, fixture, or evidence as gaps.
7. Preserve owner-authored truth.
8. Produce evidence and report paths after execution.
9. Never place runtime secrets or credentials in DSL test cases.
10. Do not execute command-capable providers unless the selected Env_Profile target defines required `safety.access_policy`.

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
