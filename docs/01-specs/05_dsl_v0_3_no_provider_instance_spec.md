# DSL v0.3 No Provider Instance Specification

**Status:** Implemented for release/0.3.0 golden baseline; local release gates passed
**Version:** v0.3 public preview interface
**Scope:** Versioned DSL and runtime contract track. This document does not change the v0.2 or v0.2.7 public interface.

## 1. Objective

DSL v0.3 simplifies framework usage by removing user-authored Provider Instance artifacts. Suite targets become the stable logical provider names used by test cases, while Provider Contracts and Env_Profiles define the executable contract and environment-specific values.

Success means a user can author a suite with:

- one suite manifest,
- one or more Env_Profile files,
- one or more test case files,
- checked-in artifacts such as fixtures, SQL, payloads, and expected results,

without writing Provider Instance files or duplicating runtime binding metadata.

## 2. Public Interface Model

Resolution flow:

```text
test_case.target
  -> suite_manifest.targets.<target>.provider_contract
  -> framework Provider Contract
  -> selected env_profile.targets.<target>
  -> bindings / runtime policy / generated outputs
  -> resolved execution plan
```

Public artifacts:

| Artifact | Owner | Responsibility |
| --- | --- | --- |
| Suite Manifest | Suite owner | Declares suite ID, tests, artifact roots, targets, Provider Contract IDs, and Env_Profile refs. |
| Env_Profile | Suite owner / CI | Declares selected runtime mode, target bindings, provisioning policy, generated outputs, and evidence classification for one environment. |
| DSL Test Case | Test author / agent with owner review | Declares setup, execute, verify, and cleanup steps against suite targets. |
| Provider Contract | Framework | Declares provider type, required bindings, allowed operations, operation inputs, expectations, outputs, evidence, and failure codes. |
| Execution Plan | Framework | Normalized, validated plan generated before dry-run or runtime execution. |

Users do not author Provider Instance artifacts in v0.3.

## 3. Suite Manifest Contract

Example:

```yaml
manifest_version: v0.3
suite_id: payment-regression
default_profile: local_sit

artifact_roots:
  fixtures: fixtures/
  queries: queries/
  expected_results: expected_results/

targets:
  payment_api:
    provider_contract: rest_client.v0.3
  order_db:
    provider_contract: jdbc.v0.3
  payment_mock:
    provider_contract: http_mock.v0.3

env_profiles:
  local_sit:
    ref: env_profiles/local_sit.yaml
  ci:
    ref: env_profiles/ci.yaml

tests:
  - ref: test_cases/payment_success.yaml
```

Rules:

- `targets.<name>` is the only target namespace available to test cases.
- `targets.<name>.provider_contract` must resolve from the framework bundled registry or an explicit `--contract-root`.
- Suite manifests must not contain URLs, topics, queue names, JDBC strings, namespaces, credentials, or secret values.
- `artifact_roots` are the only file roots available to `artifact://` references.
- Artifact roots must be relative to the suite manifest directory.
- Mock-server targets must use protocol capability contracts such as `http_mock`, `soap_mock`, and `grpc_mock`. Runtime implementation such as WireMock belongs in Provider Contract metadata, not in test case DSL target names.

## 4. Env_Profile Contract

Example:

```yaml
profile_id: local_sit
execution_mode: sit
isolation: shared
evidence_classification: external_runtime_evidence

targets:
  payment_api:
    runtime_mode: native
    bindings:
      base_url: env://PAYMENT_API_BASE_URL

  order_db:
    runtime_mode: native
    bindings:
      jdbc_connection: env://JDBC_CONNECTION

  payment_mock:
    runtime_mode: mock
    bindings:
      port_strategy: dynamic

  payment_mock_client:
    runtime_mode: mock
    bindings:
      base_url: generated://payment_mock/base_url
```

Rules:

- `env_profile.targets` keys must match `suite_manifest.targets` keys.
- `runtime_mode` must be allowed by the target's Provider Contract.
- `bindings` keys must match required or optional Provider Contract binding keys.
- Missing required binding keys block before provider runtime dispatch.
- `generated://<target>/<output>` bindings may consume framework-created values only when the producing Provider Contract declares that output as bindable.
- Raw secrets are prohibited. Runtime values must use safe refs such as `env://NAME` or approved generated refs.

## 5. DSL Test Case Contract

Example:

```yaml
dsl_version: v0.3
test_case_id: PAYMENT-TC-001
title: Payment success should persist order

setup:
  - id: seed_order
    target: order_db
    op: db_execute
    with:
      sql: artifact://fixtures/sql/seed_order.sql

execute:
  - id: submit_payment
    target: payment_api
    op: http_request
    with:
      method: POST
      path: /payments
      body: artifact://fixtures/payment_request.json

verify:
  - id: response_status
    type: assertion
    assert:
      actual: step://submit_payment/response.status
      operator: equals
      expected: 201

  - id: load_order
    type: provider_check
    target: order_db
    op: db_query
    with:
      sql: artifact://queries/find_order.sql
      bind:
        order_id: artifact://expected_results/payment_expected.json#/order_id

  - id: order_persisted
    type: assertion
    assert:
      actual: step://load_order/row_count
      operator: gte
      expected: 1

cleanup:
  - id: cleanup_order
    target: order_db
    op: db_execute
    scope: test
    with:
      sql: artifact://fixtures/sql/cleanup_order.sql
```

Rules:

- `setup`, `execute`, `verify`, and `cleanup` are ordered lists.
- Step IDs must be unique in one test case and match `[a-z][a-z0-9_]*`.
- Test cases directly reference suite target names; they must not define aliases with `uses`.
- `op`, `with`, and output refs are validated by the target Provider Contract.
- `provider_check.expect` is prohibited. Provider-specific expectation input belongs in contract-declared `with`; use a following `type: assertion` step for framework-owned comparison.
- `verify` supports `type: assertion` and `type: provider_check`.
- Provider configuration, endpoints, topics, queues, DB credentials, and generated runtime values do not belong in test cases.

Prohibited v0.3 test case fields:

```text
uses, targets, provider_id, provider_instance, data_binding, datasets,
fixtures, expected_results, db_seed, db_cleanup, mock_stubs, parameters, bind_as
```

## 6. Provider Contract Requirements

Provider Contracts are framework-owned. A v0.3 Provider Contract must define:

- `provider_contract` and `provider_type`.
- allowed `runtime_modes`.
- required and optional binding keys per runtime mode.
- operations, with required and optional `with` inputs.
- expectation paths and operators for provider checks.
- output refs, output type, and output sensitivity.
- bindable generated outputs when mock or framework targets expose values.
- evidence outputs and failure codes.

Operation phase restrictions are optional. If a Provider Contract does not restrict an operation to specific phases, the operation is valid in any DSL phase after all input, safety, and runtime-mode rules pass.

## 7. Typed Reference Model

Allowed refs:

| Ref | Used By | Meaning |
| --- | --- | --- |
| `artifact://<root>/<path>[#<json_pointer>]` | DSL | Checked-in suite artifact under a declared root. |
| `step://<step_id>/<output_path>` | DSL | Prior step output from the same test case. |
| `generated://<target>/<output>` | Env_Profile | Framework-generated output from a mock or framework target. |
| `env://<ENV_NAME>` | Env_Profile | Environment variable resolved at runtime and masked in outputs. |

Validation rules:

- `artifact://` roots must exist in the suite manifest.
- Paths must canonicalize under the suite directory and must reject absolute paths, `../`, `~`, drive-letter paths, encoded traversal, symlink escape, and root overlap.
- JSON pointers must be valid RFC 6901 pointers and bounded by configured depth and extracted value size.
- `step://` may reference only prior steps in the same test case.
- `generated://` may reference only declared generated outputs for the selected Env_Profile.
- Resolved `env://` values must never appear in validate, dry-run, result JSON, evidence, report, or logs.

## 8. CLI Contract

Required public commands:

```bash
regress validate --suite <suite_manifest> [--profile <profile>] [--contract-root <dir>]
regress run --suite <suite_manifest> --profile <profile> --dry-run [--contract-root <dir>]
regress run --suite <suite_manifest> --profile <profile> [--contract-root <dir>]
regress report --result <result_json>
```

`validate` must not invoke provider runtime. `run --dry-run` must produce a resolved execution plan with `provider_runtime_invoked: false`.

## 9. Result and Evidence Contract

Result JSON must include:

- `suite_id`, `profile`, `batch_id`, `run_id`, `test_case_id`, and status.
- ordered step results with phase, step ID, target, Provider Contract, operation, status, outputs, evidence refs, and failure code.
- cleanup status and cleanup evidence refs.
- evidence index ref.
- primary failure and secondary cleanup failures.

Evidence validation must prove:

- every result evidence ref exists in the evidence index,
- every indexed evidence file exists,
- provider evidence names target and Provider Contract,
- failed assertions include failure evidence,
- cleanup failures are visible and do not hide the original failure,
- result, evidence, report, and logs contain no raw secrets.

## 10. Compatibility Boundary

v0.2 artifacts remain supported by the v0.2 runtime path. v0.3 artifacts are versioned separately and must fail fast if they contain v0.2-only fields. A future migration command may translate v0.2 artifacts into v0.3 shape, but v0.3 runtime must not silently infer Provider Instances from legacy files.

## 11. Success Criteria

The v0.3 spec is implementation-ready when:

- schema changes can be derived directly from this document,
- validators have explicit positive and negative cases,
- dry-run resolution is deterministic,
- provider runtime dispatch can consume the generated execution plan without reading Provider Instance files,
- v0.2 compatibility remains isolated,
- every acceptance criterion in the v0.3 AC document maps to at least one planned test.

This document satisfies those readiness conditions when read together with the v0.3 architecture, AC, test plan, and implementation plan documents.
