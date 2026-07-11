# DSL v0.3 Test Plan

**Status:** Release/0.3.0 P0 runtime sample matrix, symlink-negative hardening, and cleanup-failure preservation implemented
**Scope:** Verification plan for DSL v0.3 no-Provider-Instance implementation.

## 1. Objective

Verify that DSL v0.3 can validate, dry-run, execute, produce evidence, and report through the simplified public model:

```text
DSL target -> Suite Manifest target -> Provider Contract -> Env_Profile target -> Execution Plan
```

The test plan must also prove that v0.2 behavior remains isolated and unchanged.

## 2. Test Levels

| Level | Purpose | Command |
| --- | --- | --- |
| Unit tests | Schema parsing, ref parsing, target resolution, binding validation, secret guardrails. | `./mvnw test -Dtest=*V03*Test` |
| Component tests | CLI validate/dry-run/report behavior with temporary fixtures. | `./mvnw test -Dtest=*V03*CommandTest` |
| Integration tests | Checked-in v0.3 sample suites validate, dry-run, run, and report. | `./mvnw verify -Dit.test=*V03*IT` |
| Regression compatibility | Existing v0.2 samples still pass selected smoke tests. | `./mvnw test -Dtest=GoldenE2eCommandTest,*ProviderCapability*Test` |

Use bounded memory locally:

```bash
MAVEN_OPTS='-Xmx1024m -XX:MaxMetaspaceSize=384m' ./mvnw test
```

## 3. Sample Corpus

Release/0.3.0 includes checked-in executable samples under the inherited canonical `samples/` layout.
The current corpus is:

```text
samples/
  00-getting-started/golden_e2e/
  10-contract-baseline/mixed_wiremock_jdbc_nats/
  20-provider-capability-p0/
    http/rest_client_with_wiremock/
    data/jdbc/
    messaging/{nats,kafka,ibm_mq,kafka_ibm_mq_mixed}/
    rpc/{soap_mock,grpc_mock}/
    verification/{common_verify,artifact_compare,polling_observer,multi_test_shared_env}/
  30-cross-provider-groups/mock_server_cross_verify/
  40-evidence-reporting/evidence_hardening/
  80-negative/
```

Required sample groups:

| Group | Required Cases |
| --- | --- |
| `00-getting-started/golden_e2e/` | One happy-path suite using the sample provider plus one assertion. |
| `20-provider-capability-p0/verification/multi_test_shared_env/` | Multiple test cases share one Env_Profile and suite-scoped framework target. |
| `80-negative/target-resolution/` | Unknown target, missing Env_Profile target, missing Provider Contract. |
| `80-negative/bindings/` | Missing required binding, unknown binding key, invalid runtime mode, blank env var. |
| `80-negative/operations/` | Unknown operation, missing required input, unsupported input field, prohibited `provider_check.expect`. |
| `80-negative/refs/` | Unknown artifact root, path traversal, symlink escape, invalid JSON pointer, forward `step://` ref. |
| `80-negative/legacy-fields/` | Each prohibited v0.3 field fails validation. |
| `80-negative/secrets/` | Raw secret in DSL, Env_Profile, result, and evidence fails guardrail checks. |
| `80-negative/runtime/` | Original failure plus cleanup failure keeps both failures. |
| `40-evidence-reporting/evidence_hardening/` | Valid evidence index, missing evidence file, unknown evidence ref, raw secret leak. |

The checked-in negative corpus currently covers target resolution, missing Env_Profile
target, missing contract, binding keys, runtime mode, operation/input validation,
artifact traversal, symlink escape, forward `step://` refs, prohibited `data_binding`,
raw secret guardrails, and cleanup failure preservation.

## 3.1 Unified Output Contract

Run a leaf suite and a suite group with `--root <project-root>`. Verify that both write canonical artifacts only below `target/regression/`; the group parent has `children/` entries for each executed child, each with a unique child `run_id`. Verify that no new run artifacts are written below `target/provider-capability/` or `target/suite-groups/`.

## 4. Positive Test Matrix

| Test ID | AC | Scenario | Expected Result |
| --- | --- | --- | --- |
| V03-POS-001 | AC-V03-001 | Valid suite manifest with targets and Env_Profile refs. | `validate` passes. |
| V03-POS-002 | AC-V03-002 | DSL target resolves without Provider Instance. | Dry-run shows target, Provider Contract, runtime mode, masked bindings. |
| V03-POS-003 | AC-V03-003 | Env_Profile supplies required bindings. | Binding validation passes. |
| V03-POS-004 | AC-V03-004 | Setup/execute/cleanup operations match Provider Contract inputs. | Execution plan contains all steps. |
| V03-POS-005 | AC-V03-005 | Provider check produces contract-declared output for a following assertion. | Verification passes. |
| V03-POS-006 | AC-V03-006 | Assertion reads prior `step://` public output. | Assertion passes. |
| V03-POS-007 | AC-V03-007 | Artifact refs and JSON pointers resolve safely. | Materialized input values are available in plan/runtime. |
| V03-POS-008 | AC-V03-008 | Dry-run valid suite. | `provider_runtime_invoked: false`. |
| V03-POS-009 | AC-V03-009 | Golden suite executes. | Result JSON and evidence index are written. |
| V03-POS-010 | AC-V03-010 | Cleanup succeeds after failed verify. | Original verify failure remains primary. |
| V03-POS-011 | AC-V03-011 | Report valid result/evidence. | Report returns review-ready summary. |
| V03-POS-012 | AC-V03-014 | Existing v0.2 smoke suite. | v0.2 behavior remains green. |
| V03-POS-013 | AC-V03-012 | Clean v0.3 test uses direct suite targets and no legacy fields. | Validation passes without compatibility fallback. |
| V03-POS-014 | AC-V03-013 | Env_Profile uses `env://` and generated refs with masked output. | Validation and dry-run pass without exposing values. |

## 5. Failure Test Matrix

| Test ID | AC | Scenario | Expected Failure |
| --- | --- | --- | --- |
| V03-NEG-001 | AC-V03-001 | Suite manifest contains `base_url`. | `VALIDATION_ERROR` at suite field path. |
| V03-NEG-002 | AC-V03-002 | Unknown DSL target. | Blocked before runtime. |
| V03-NEG-003 | AC-V03-002 | v0.3 test contains `provider_id`. | Legacy field failure. |
| V03-NEG-004 | AC-V03-003 | Missing required binding key. | `CONFIGURATION_ERROR`. |
| V03-NEG-005 | AC-V03-003 | Blank `env://` value. | `CONFIGURATION_ERROR`, value masked. |
| V03-NEG-006 | AC-V03-004 | Unsupported operation. | `CONTRACT_ERROR`. |
| V03-NEG-007 | AC-V03-004 | Unsupported `with` field. | `CONTRACT_ERROR`. |
| V03-NEG-008 | AC-V03-005 | Generic `provider_check.expect`. | `prohibited_provider_check_expect` before dispatch. |
| V03-NEG-009 | AC-V03-006 | Forward `step://` ref. | `VALIDATION_ERROR`. |
| V03-NEG-010 | AC-V03-007 | `artifact://fixtures/../secret.txt`. | Path security validation failure. |
| V03-NEG-011 | AC-V03-007 | Invalid JSON pointer. | Owner-actionable pointer error. |
| V03-NEG-012 | AC-V03-008 | Dry-run invalid suite. | `dry_run_status: blocked`, no runtime. |
| V03-NEG-013 | AC-V03-010 | Cleanup failure after execute failure. | Primary execute failure plus secondary cleanup failure. |
| V03-NEG-014 | AC-V03-011 | Result evidence ref missing from index. | `EVIDENCE_ERROR`. |
| V03-NEG-015 | AC-V03-013 | Raw JDBC credential in Env_Profile. | `SECRET_GUARDRAIL_ERROR`. |
| V03-NEG-016 | AC-V03-014 | v0.2 sample regression. | Existing v0.2 tests must still pass. |
| V03-NEG-017 | AC-V03-009 | Provider runtime timeout or safe provider exception after execution starts. | Failed result JSON includes target, Provider Contract, operation, failure code, and evidence refs. |

## 6. Traceability

| AC | Positive Tests | Failure Tests |
| --- | --- | --- |
| AC-V03-001 | V03-POS-001 | V03-NEG-001 |
| AC-V03-002 | V03-POS-002 | V03-NEG-002, V03-NEG-003 |
| AC-V03-003 | V03-POS-003 | V03-NEG-004, V03-NEG-005 |
| AC-V03-004 | V03-POS-004 | V03-NEG-006, V03-NEG-007 |
| AC-V03-005 | V03-POS-005 | V03-NEG-008 |
| AC-V03-006 | V03-POS-006 | V03-NEG-009 |
| AC-V03-007 | V03-POS-007 | V03-NEG-010, V03-NEG-011 |
| AC-V03-008 | V03-POS-008 | V03-NEG-012 |
| AC-V03-009 | V03-POS-009 | V03-NEG-017 |
| AC-V03-010 | V03-POS-010 | V03-NEG-013 |
| AC-V03-011 | V03-POS-011 | V03-NEG-014 |
| AC-V03-012 | V03-POS-013 | V03-NEG-003 and one case per prohibited field. |
| AC-V03-013 | V03-POS-014 | V03-NEG-015 |
| AC-V03-014 | V03-POS-012 | V03-NEG-016 |

## 7. Release Gates

### 7.1 Release/0.3.0 P0 Runtime Sample Gate

The release/0.3.0 gate covers checked-in v0.3 P0 runtime samples and selected
contract/security regressions:

- `validate` resolves suite targets through explicit v0.3 Provider Contracts.
- `run --dry-run` proves `provider_runtime_invoked: false`.
- `run`, `report`, and `validate-evidence` pass for the v0.3 P0 sample matrix.
- v0.3 validation rejects Provider Instance fields, synthetic `.v0.3` provider-type aliases, unsupported operations/inputs, invalid step refs, duplicate step IDs, artifact traversal, symlink escape, and invalid JSON pointers.
- selected v0.2 smoke tests remain green.
- usage-kit verification includes v0.3 schemas, docs, Provider Contracts, executable samples, and the explicit `verify-v0-3-runtime-samples.sh` release gate.

### 7.2 Full v0.3 Hardening Gate

The full v0.3 matrix is not complete until:

- all v0.3 positive and negative tests pass,
- dry-run proves `provider_runtime_invoked: false`,
- v0.3 report validates evidence before review-ready output,
- secret guardrail tests cover DSL, Env_Profile, result, evidence, and report,
- selected v0.2 smoke tests still pass,
- checked-in v0.3 samples are included in usage documentation only after the versioned feature is accepted.

## 8. v0.3.0 Semantic Contract Completion Matrix

| Area | Happy path | Failure/boundary path |
| --- | --- | --- |
| Canonical plan | validate, dry-run, and run share plan digest and ordered steps | invalid input emits no plan; runtime YAML reload is detected by characterization test |
| Typed contracts | input/output types, sensitivity, phase, runtime mode, and evidence fields validate | type mismatch, prohibited phase, unknown output, unsafe output consumption |
| Generated DAG | producer runs before consumer deterministically | missing producer, undeclared output, self-edge, two-node and longer cycle |
| Version routing | v0.2 and v0.3 leaf suites route independently in a group | missing/unsupported manifest version and mixed leaf versions block pre-schema |
| Executable docs/release | clean-checkout jar/usage-kit samples validate, run, report, and validate evidence | missing sample, contract registry, schema, user guide, or outside-cwd execution fails gate |
