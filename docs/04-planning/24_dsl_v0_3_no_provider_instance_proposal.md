# DSL v0.3 No Provider Instance Proposal

**Status:** Proposal adopted into the release/0.3.0 golden baseline and superseded for implementation tracking by:

- `docs/01-specs/05_dsl_v0_3_no_provider_instance_spec.md`
- `docs/02-architecture/08_dsl_v0_3_no_provider_instance_architecture.md`
- `docs/03-acceptance/05_dsl_v0_3_acceptance_criteria.md`
- `docs/07-validation-evidence/11_dsl_v0_3_test_plan.md`
- `docs/04-planning/25_dsl_v0_3_no_provider_instance_implementation_plan.md`

**Goal:** Define a simpler DSL v0.3 public interface that removes Provider Instance as a user-authored artifact while preserving strict Provider Contract validation, Env_Profile runtime policy, evidence governance, and dry-run safety.

This proposal records the design rationale. Current release status is tracked in the formal spec, architecture, AC, test plan, and implementation plan. DSL v0.3 does not change the v0.2 suite-mode public interface.

---

## 1. Design Decision

DSL v0.3 removes Provider Instance from the user-facing runtime model.

The suite target becomes the suite-local logical provider identity:

```text
test_case.target
  -> suite_manifest.targets[target].provider_contract
  -> Provider Contract
  -> selected env_profile.targets[target]
  -> bindings / runtime policy / generated outputs
  -> resolved execution plan
```

Responsibilities:

| Artifact | Responsibility |
| --- | --- |
| Suite Manifest | Target names, provider contracts, artifact roots, env profile refs, test list |
| Test Case DSL | Ordered setup / execute / verify / cleanup flow |
| Provider Contract | Provider standard operation interface, runtime bindings, outputs, evidence, failures |
| Env_Profile | Environment runtime policy, provisioning, bindings, generated outputs, evidence classification |
| Execution Plan | Framework-generated normalized plan executed by runtime |

Provider Contracts are framework-owned and resolved from the bundled registry or an explicit `--contract-root`.

---

## 2. Public Artifacts

Users author and maintain:

```text
suite_manifest.yaml
env_profiles/<profile>.yaml
test_cases/<test_case>.yaml
fixtures/
queries/
expected_results/
```

Users do not author Provider Instance artifacts in v0.3.

---

## 3. Suite Manifest

```yaml
manifest_version: v0.3
suite_id: payment-regression
default_profile: local_sit

artifact_roots:
  fixtures: fixtures/
  queries: queries/
  expected: expected_results/

targets:
  payment_api:
    provider_contract: rest_client.v0.3
  order_db:
    provider_contract: jdbc.v0.3
  payment_mock:
    provider_contract: http_mock.v0.3
  event_bus:
    provider_contract: nats.v0.3

env_profiles:
  local_sit:
    ref: env_profiles/local_sit.yaml
  ci:
    ref: env_profiles/ci.yaml

tests:
  - ref: test_cases/payment_success.yaml
```

Rules:

- `targets.<name>` is the only target namespace for test cases.
- `targets.<name>.provider_contract` must resolve to exactly one Provider Contract.
- Test cases may reference only target names declared in the suite manifest.
- Artifact roots are the only file root definitions used by test cases.
- Suite manifests must not define provider runtime values, URLs, credentials, topics, DB strings, or environment-specific namespaces.
- Mock-server Provider Contracts must use protocol capability names such as `http_mock`, `soap_mock`, and `grpc_mock`; runtime implementation such as WireMock belongs in Provider Contract metadata, not in the target name.

---

## 4. Env_Profile

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

Env_Profile owns runtime policy:

- `execution_mode`: `local`, `ci`, `sit`, or `preprod`.
- `isolation`: isolation expectation for the run.
- `runtime_mode`: target-level mode declared by the Provider Contract, such as `mock`, `native`, `stub`, `ephemeral`, or `framework`.
- `evidence_classification`: how evidence may be interpreted.
- `bindings`: actual profile-specific values, secret refs, or approved `generated://<target>/<output>` refs.

Raw secret values are prohibited. Use `env://<ENV_NAME>` for environment-sourced secrets and configuration.

---

## 5. Test Case DSL

```yaml
dsl_version: v0.3
test_case_id: PAYMENT-TC-001
title: Payment success should persist order

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

  - id: order_persisted
    type: provider_check
    target: order_db
    op: db_query
    with:
      sql: artifact://queries/find_order.sql
      bind:
        order_id: artifact://expected/payment_expected.json#/order_id
    expect:
      - actual: row_count
        operator: gte
        expected: 1
      - actual: rows[0].status
        operator: equals
        expected: PAID

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
- Step IDs must be unique within one test case.
- Step IDs must match `[a-z][a-z0-9_]*`.
- A step target must exist in `suite_manifest.targets`.
- `op`, `with`, `expect`, and output refs must be validated by the resolved Provider Contract.
- `verify` is a discriminated union:
  - `type: assertion` uses structured assertions over prior step outputs.
  - `type: provider_check` invokes a provider operation and validates `expect`.
- Test cases do not declare target aliases or provider configuration.

---

## 6. Provider Contract

```yaml
contract_version: v0.3
provider_contract: jdbc.v0.3
provider_type: jdbc
runtime_modes: [native, ephemeral]

binding_keys:
  connection:
    required: true
  dialect:
    required: true

operations:
  db_query:
    allowed_phases: [setup, execute, verify, cleanup]
    inputs:
      required: [sql]
      fields:
        sql:
          type: artifact_ref
          materialize_as: text
        bind:
          type: object
          values: literal_or_ref
    expectations:
      paths:
        row_count:
          operators: [equals, not_equals, gt, gte, lt, lte]
        rows[*].status:
          operators: [equals, not_equals, matches, exists, not_exists]
    outputs:
      row_count:
        type: integer
        sensitivity: public
      rows:
        type: array
        sensitivity: masked
    evidence:
      types: [jdbc_query]
    failures:
      codes: [JDBC_QUERY_FAILED, JDBC_EXPECTATION_FAILED]
```

Provider Contract owns:

- allowed runtime modes
- required runtime binding keys per runtime mode
- allowed operations and phases
- operation inputs
- expectation paths and operators
- output refs and output sensitivity
- evidence types
- failure codes

Provider Contract does not own file paths, target names, environment values, or secrets.

Only outputs marked `public` or `masked` may be referenced by `step://`. Outputs marked `secret` or `internal` must not be serialized into public result, evidence, or report output.

---

## 7. Typed Reference Model

Allowed ref forms:

```text
artifact://<root>/<path>[#<json_pointer>]
step://<step_id>/<output_path>
generated://<target>/<output_name>
env://<ENV_NAME>
```

Usage:

- Test cases may use `artifact://` and `step://`.
- Env_Profiles may use `env://` and `generated://`.
- `step://` may reference only prior steps in the same test case.
- Forward refs are invalid.
- Cross-test step refs are invalid.
- `generated://` may reference only generated outputs declared by the selected Env_Profile.
- `env://` names must match `[A-Z_][A-Z0-9_]*`.
- Blank or unset env vars fail before provider dispatch.
- Resolved env values must never appear in validate, dry-run, result, evidence, report, or logs.

Path selector rules:

- Provider output paths must match declared Provider Contract output refs or declared expectation path patterns.
- JSON pointer follows RFC 6901.
- Invalid JSON pointer fails with an owner-actionable validation error.
- JSON pointer depth and extracted value size must be bounded by schema defaults.

---

## 8. Artifact Root Security

Artifact roots must canonicalize under the suite manifest directory.

Reject:

- absolute paths
- `../` escape
- `~` paths
- drive-letter paths
- symlink escape
- root overlap
- encoded traversal
- roots that escape after canonicalization

Provider Contracts validate ref type, not filesystem root.

---

## 9. Dry-Run Contract

Command:

```bash
regress run --suite suite_manifest.yaml --profile local_sit --dry-run
```

Dry-run must not invoke provider runtime.

Minimum output:

```yaml
dry_run_status: ready
provider_runtime_invoked: false
selected_profile: local_sit
resolved_targets:
  - target: order_db
    provider_contract: jdbc.v0.3
    runtime_mode: native
    bindings_status: resolved_masked
execution_plan:
  - phase: execute
    step_id: submit_payment
    target: payment_api
    provider_contract: rest_client.v0.3
    op: http_request
  - phase: verify
    step_id: order_persisted
    target: order_db
    provider_contract: jdbc.v0.3
    op: db_query
evidence_plan: []
validation_findings: []
```

Dry-run validates:

- suite manifest shape
- selected profile
- target existence
- Provider Contract existence
- runtime mode compatibility
- required binding keys
- secret ref shape
- typed refs
- artifact root containment
- operation support
- `with` input fields
- `expect` paths and operators
- step output refs
- cleanup plan
- evidence plan

---

## 10. Result, Evidence, and Report Contract

v0.3 result must preserve:

```text
suite_id
profile
batch_id
run_id
test_case_id
test status
steps:
  phase
  step_id
  target
  provider_contract
  op
  status
  outputs
  evidence_refs
  failure_code
cleanup status
cleanup evidence refs
evidence_index_ref
primary_failure
secondary_cleanup_failures
```

Rules:

- Every result evidence ref must resolve to evidence index entry.
- Every evidence index entry path must exist.
- Step ordering in result must match execution plan order.
- Report must validate result JSON and evidence refs before returning success.
- Report must mask secrets and fail if raw secrets are detected.
- Cleanup failures must be visible and must not hide the original failure.

`regress report --result <result_json>` remains part of the public interface.

---

## 11. Cleanup Semantics

P0 cleanup rules:

- If setup starts, test cleanup is attempted.
- Test cleanup runs after setup, execute, or verify failure.
- Test cleanup runs in declared order.
- Original failure remains primary.
- Cleanup failure is recorded as secondary.
- Provider-managed cleanup runs after suite execution.
- Suite-scoped generated resources are controlled by Env_Profile provisioning.
- If a setup step fails before acquiring a resource, dependent cleanup may be marked `skipped` with an owner-actionable reason.

Failure precedence:

| Scenario | Primary Status | Secondary Status |
| --- | --- | --- |
| execute fails, cleanup passes | execute failure | none |
| verify fails, cleanup passes | verification failure | none |
| execute fails, cleanup fails | execute failure | cleanup failure |
| verify fails, cleanup fails | verification failure | cleanup failure |
| run passes, cleanup fails | cleanup failure | none |
| provider-managed cleanup fails | suite cleanup failure | affected test failures preserved |

---

## 12. Prohibited v0.3 Test Case Fields

Reject these fields in v0.3 test cases:

```yaml
uses:
targets:
provider_id:
provider_instance:
data_binding:
datasets:
fixtures:
expected_results:
db_seed:
db_cleanup:
mock_stubs:
parameters:
bind_as:
```

v0.2 remains readable as legacy input. New v0.3 samples, generated test cases, and QA agent output should use this shape only for the versioned v0.3 path after the corresponding release gate accepts it.

---

## 13. P0 Acceptance Criteria

- Valid v0.3 suite validates.
- Valid v0.3 suite dry-runs with `provider_runtime_invoked: false`.
- Valid v0.3 suite runs and emits result JSON plus evidence index.
- Unknown target fails validation.
- Missing Provider Contract fails validation.
- Missing Env_Profile target binding fails validation.
- Missing required binding key fails validation.
- Unsupported runtime mode fails validation.
- Unknown operation fails validation.
- Unsupported `with` field fails validation.
- Unsupported `expect` path or operator fails validation.
- Invalid assertion variant fails validation.
- Invalid step output ref fails validation.
- Forward step ref fails validation.
- Cross-test step ref fails validation.
- Invalid artifact root fails validation.
- Artifact path escape fails validation.
- Invalid JSON pointer fails validation.
- Raw secret fails validation or report evidence scan.
- `poll` on unsupported operation fails validation.
- Cleanup failure is reported without hiding original failure.
- Report validates evidence refs and masks secrets.

---

## 14. Test Corpus Proposal

Create v0.3 samples under:

```text
samples/v0_3_dsl/
  golden/
  negative/
  evidence_report/
```

Required sample groups:

- golden success suite
- multi-test suite with suite-scoped mock target
- unknown target
- missing Provider Contract
- missing Env_Profile binding
- unsupported operation
- unsupported input field
- unsupported expectation path/operator
- invalid `artifact://` root
- path traversal and symlink escape
- invalid JSON pointer
- invalid `step://` ref
- secret leakage fixture
- cleanup failure plus original failure
- report/evidence validation fixture

---

## 15. Open Questions

- Should v0.3 allow `suite` cleanup authored outside test cases, or keep only provider-managed cleanup in P0?
- Should `generated://` values be visible in dry-run as names only, or include source target lifecycle metadata?
- Should v0.3 provide an official migration command from v0.2, or only reject legacy fields in v0.3 files?
- Should `artifact://expected/...` be renamed to `oracle://...` later, or keep `expected` for user familiarity?

---

## 16. Verdict

This proposal has been converted into formal v0.3 spec, architecture, acceptance criteria, test plan, and implementation plan documents.

Use the implementation plan for release tracking and the user guide for release-facing commands.
