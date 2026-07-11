# Suite Summary Public Contract Proposal

**Status:** Proposed for v0.3 follow-up hardening.
**Scope:** Suite result aggregation and reporting contract.
**Non-goal:** This proposal does not define verifier syntax, add providers, or introduce release governance, RP/RU interpretation, or external reporting.

## 1. Problem

Current suite-group output writes `suite_summary.json`, but its public contract mixes child execution status, expected-failure compatibility fields, and aggregation counts. Parent suites and CI therefore cannot consume one stable, recursive result model.

## 2. Design Principles

Suite summary aggregates only the final status produced by each test case or child suite:

```text
runtime observation -> verify/assert -> test final_status -> suite_summary
```

How verify/assert determines correctness belongs to the DSL and verifier contracts. Suite summary must not expose or reinterpret `expected_failure`.

The v0.3 manifest modes remain unchanged:

- A leaf suite owns `tests[]`.
- An aggregation suite owns `child_suites[]`.
- A manifest must not mix `tests[]` and `child_suites[]`.

## 3. Canonical Contract

Every recoverable execution path that passes preflight and establishes a run output context writes canonical JSON `suite_summary.json`. An optional YAML copy may be generated from the same model. Unrecoverable process or storage loss is handled as the missing-run-artifact condition defined below.

```yaml
suite_summary_version: v0.3
suite_id: MOCK-SERVER-CROSS-VERIFY-v0.3
batch_id: BATCH-20260711-001
run_id: RUN-PARENT-001
profile: local_v03
status: passed
completion_status: complete
termination_reason: null
start_time: 2026-07-11T09:00:00Z
end_time: 2026-07-11T09:00:12Z
duration_ms: 12000
generated_at: 2026-07-11T09:00:12Z
framework_version: 0.3.0
dsl_version: v0.3
suite_manifest_ref: suite_manifest.yaml
suite_manifest_digest: sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef

self_summary:
  count_completeness: complete
  unknown_test_case_count: false
  test_case_count: 0
  executed_count: 0
  pass_count: 0
  fail_count: 0
  blocked_count: 0
  skipped_count: 0
  pass_rate_percent: null
  completion_rate_percent: null

child_aggregate_summary:
  count_completeness: complete
  unknown_test_case_count: false
  child_suite_count: 1
  completed_child_suite_count: 1
  blocked_child_suite_count: 0
  skipped_child_suite_count: 0
  errored_child_suite_count: 0
  test_case_count: 1
  executed_count: 1
  pass_count: 1
  fail_count: 0
  blocked_count: 0
  skipped_count: 0
  pass_rate_percent: 100.0
  completion_rate_percent: 100.0

total_summary:
  count_completeness: complete
  unknown_test_case_count: false
  test_case_count: 1
  executed_count: 1
  pass_count: 1
  fail_count: 0
  blocked_count: 0
  skipped_count: 0
  pass_rate_percent: 100.0
  completion_rate_percent: 100.0

failure_summary:
  test_failure_count: 0
  test_blocked_count: 0
  aggregation_error_count: 0
  total_issue_count: 0
  by_category: {}
  failed_test_refs: []
  failed_child_refs: []

evidence_summary:
  evidence_count: 12
  masking_applied: true
  evidence_index_ref: evidence/evidence_index.yaml

aggregation_errors: []

children:
  - child_suite_id: REST-001
    ref: rest_http/suite_manifest.yaml
    batch_id: BATCH-20260711-001
    run_id: RUN-REST-001
    status: passed
    start_time: 2026-07-11T09:00:01Z
    end_time: 2026-07-11T09:00:04Z
    duration_ms: 3000
    summary_ref: children/REST-001/RUN-REST-001/suite_summary.json
    total_summary:
      count_completeness: complete
      unknown_test_case_count: false
      test_case_count: 1
      executed_count: 1
      pass_count: 1
      fail_count: 0
      blocked_count: 0
      skipped_count: 0
      pass_rate_percent: 100.0
      completion_rate_percent: 100.0
```

## 4. Field Semantics

| Field | Contract |
| --- | --- |
| `batch_id` | One ID created per `regress run`; every executed child must use the same value. |
| `run_id` | Unique execution ID for this suite. A rerun creates a new ID and must not overwrite earlier output. |
| `status` | Authoritative execution outcome: `passed`, `failed`, `blocked`, or `skipped`. |
| `completion_status` | Whether the run produced a complete result set: `complete` or `partial`. |
| `termination_reason` | `null` for complete runs; required reason for partial runs. |
| `start_time`, `end_time`, `duration_ms` | UTC execution interval and non-negative elapsed milliseconds. |
| `framework_version`, `dsl_version` | Runtime and DSL versions used to produce the summary. |
| `suite_manifest_ref`, `suite_manifest_digest` | Normalized manifest reference and SHA-256 digest used for reproducibility. |
| `count_completeness`, `unknown_test_case_count` | Whether summary counts cover the full selected scope or only valid materialized results. |
| `self_summary` | Final statuses of tests directly owned by this suite. It is zero-valued for a v0.3 aggregation suite. |
| `child_aggregate_summary` | Sum of each immediate child's authoritative `total_summary`. |
| `total_summary` | Field-by-field sum of `self_summary` and `child_aggregate_summary`. |
| `failure_summary` | Compact failure categories plus references to failed or blocked test results and evidence. |
| `evidence_summary` | Generated evidence count, masking-applied flag, and canonical evidence-index reference. |
| `aggregation_errors` | Post-execution child artifact errors; empty for normal and leaf runs. |
| `children[].summary_ref` | Required reference to the executed child's canonical summary. |
| `children[].total_summary` | Report snapshot copied from the referenced child summary. It is not an independent source. |

For entries accepted into `children`, `summary_ref` is authoritative. A missing, invalid, identity-mismatched, or snapshot-mismatched child summary fails child aggregation and is recorded in the parent's `aggregation_errors`; it cannot appear as a valid child entry.

All fields shown in the canonical contract are required. Empty values are represented consistently rather than omitted:

| Condition | Required representation |
| --- | --- |
| Complete run | `completion_status: complete`, `termination_reason: null`. |
| Complete count coverage | `count_completeness: complete`, `unknown_test_case_count: false`. |
| Partial count coverage | `count_completeness: partial`, `unknown_test_case_count: true`, and both rates are `null`. |
| Leaf suite | `children: []`; child aggregate counters are zero and rates are `null`. |
| Aggregation suite | `self_summary` counters are zero and rates are `null`. |
| No failures | Empty `by_category`, `failed_test_refs`, and `failed_child_refs`. |
| No evidence | `evidence_count: 0`, `masking_applied: true`, and the required evidence-index reference still identify the empty index. |
| No aggregation error | `aggregation_errors: []`. |

The schema-owned enums are:

- Final status: `passed`, `failed`, `blocked`, `skipped`.
- Count completeness: `complete`, `partial`.
- Completion status: `complete`, `partial`.
- Partial termination reason: `timeout`, `cancelled`, `framework_error`, `aggregation_error`.
- Report/evidence validation status is owned by the report and evidence contracts, not persisted as suite execution status.
- Failure categories are owned by the framework failure taxonomy referenced by `schemas/result.v0.3.schema.yaml`; failure codes remain provider/verifier-contract values.

## 5. Aggregation and Status Rules

- A leaf suite sets `self_summary` from final test case statuses and sets `total_summary` equal to it.
- An aggregation suite sets `self_summary` to zero and aggregates only immediate child `total_summary` values.
- A nested aggregation suite is consumed exactly like any other child; parents must not inspect grandchildren.
- Parents must not recalculate child counts from result JSON, evidence, Allure, or provider logs.
- `test_case_count = pass_count + fail_count + blocked_count + skipped_count`.
- `executed_count = pass_count + fail_count`; a test counts as executed only when it reaches a final verification outcome.
- `pass_rate_percent = pass_count / executed_count * 100`.
- `completion_rate_percent = executed_count / test_case_count * 100`.
- Rates are rounded to one decimal using half-up rounding and are `null` when their denominator is zero.
- Pass rate is informational and must not be used as the release gate.
- Every parent count must equal the corresponding immediate-child count sum; mismatched arithmetic invalidates the summary.
- When `count_completeness: complete`, `unknown_test_case_count` is `false` and all counts and rates cover the selected scope.
- When `count_completeness: partial`, `unknown_test_case_count` is `true`, counts cover only valid materialized results, and both rates must be `null` to prevent misleading percentages.
- `total_summary.count_completeness` is `complete` only when both self and child aggregate coverage are complete; otherwise it is `partial`.

Suite status has this precedence:

1. `blocked` when any selected test or child has final status `blocked`, aggregation cannot validate an executed child summary, or `completion_status` is `partial`.
2. `failed` when nothing is blocked and at least one final status is `failed`.
3. `passed` when nothing failed or blocked, at least one final status is `passed`, and any remaining statuses are `skipped`.
4. `skipped` when all selected tests or children were skipped.

An empty execution selection is a preflight validation failure and produces no suite summary.

`completion_status: partial` records interruption after execution starts, such as framework timeout, cancellation, an unrecoverable runner error, or invalid child output. It requires `status: blocked` and a `termination_reason` of `timeout`, `cancelled`, `framework_error`, or `aggregation_error`. A partial summary must retain every completed test result and available evidence.

`skipped` is an additive v0.3 result-status value. The result schema, runtime status mapper, aggregation logic, report renderer, and compatibility reader must be upgraded together. Legacy v0.2 results continue to use `passed`, `failed`, and `blocked`; readers must not infer skipped results from absent test entries.

For child suites:

- `child_suite_count` is the number selected after successful preflight.
- `completed_child_suite_count` counts children ending as `passed` or `failed`.
- `blocked_child_suite_count` and `skipped_child_suite_count` count their corresponding final statuses.
- `errored_child_suite_count` counts children whose post-execution result or summary cannot be validated.
- These counts must satisfy `child_suite_count = completed_child_suite_count + blocked_child_suite_count + skipped_child_suite_count + errored_child_suite_count`.
- Any non-zero `errored_child_suite_count` requires `count_completeness: partial`, `unknown_test_case_count: true`, `completion_status: partial`, and `status: blocked`.

## 6. Failure and Diagnostic Summary

`failure_summary` provides navigation, not duplicated result or evidence payloads. Its invariants are:

- `test_failure_count = total_summary.fail_count`.
- `test_blocked_count = total_summary.blocked_count`.
- `aggregation_error_count = aggregation_errors.length`.
- `total_issue_count = test_failure_count + test_blocked_count + aggregation_error_count`.
- The sum of `by_category` values equals `total_issue_count`.

A leaf summary lists its failed or blocked tests in `failed_test_refs`:

```yaml
- test_case_id: TC-002
  final_status: failed
  failure_code: ASSERTION_JSON_MISMATCH
  category: VERIFICATION_FAILED
  result_ref: test-results/TC-002/result.json
  evidence_refs:
    - evidence://assertion-diff-TC-002
```

An aggregation summary keeps `failed_test_refs: []` and lists only immediate failed or blocked children:

```yaml
- child_suite_id: REST-001
  final_status: failed
  summary_ref: children/REST-001/RUN-REST-001/suite_summary.json
```

The parent sums each immediate child's failure counters and `by_category`, then adds its own aggregation errors. It navigates through `failed_child_refs` and must not flatten descendant failure references or inspect grandchildren.

`result_ref` and every evidence reference must resolve through the standard result/evidence contracts. Messages and samples remain in masked evidence; suite summary must not copy raw payloads, credentials, connection values, or stack traces.

Execution status, evidence validity, and report publication are separate contracts:

- `status` records the observed test execution outcome and is never rewritten by later reporting.
- `evidence_summary` records what the run generated and whether masking was applied; it does not claim that later evidence validation passed.
- `report_status` remains the report command's stable review/publication decision using `review_ready`, `review_ready_with_failures`, `failed`, or `invalid`.

A release gate passes only when `report_status: review_ready`. `evidence_index_ref` follows the artifact-reference rules below.

`evidence_summary.evidence_count` must equal the number of entries in the referenced evidence index. `masking_applied: true` means every indexed evidence entry declares masking applied; any mismatch makes report/evidence validation fail. The summary never embeds evidence payloads.

## 7. Preflight and Runtime Blocking

The following are aggregation-manifest structural preflight blockers:

- Missing or malformed child manifest.
- Duplicate child ID.
- Child reference escaping the aggregation directory.
- Invalid child schema or contract artifact.
- Empty child-suite selection.

An aggregation structural preflight blocker returns `run_status: blocked` and produces no `batch_id`, `run_id`, `result_json`, `suite_summary_json`, or Allure directory. The framework must not synthesize a blocked child summary for these errors.

A standard leaf-suite preflight failure retains the existing standard-result behavior: after a run context has been allocated, it may write a blocked result and evidence with batch/run IDs. This proposal does not remove that diagnostic lifecycle. Malformed top-level YAML that cannot establish a run context remains validation-only and produces no run artifacts.

If a child passes preflight, enters execution, and finishes as `blocked`, it must still produce a valid result and summary. The parent records that child with its shared `batch_id`, unique `run_id`, `status: blocked`, and required `summary_ref`.

If a child enters execution but its result or summary is missing, malformed, or identity-mismatched, the parent writes a valid partial blocked result and summary with `termination_reason: aggregation_error`. Valid child entries remain under `children`; unresolved children are recorded under `aggregation_errors` with child ID, artifact reference, failure code, and owner-actionable message. An invalid child artifact must never be copied into `children` as though it were authoritative.

One `regress run` creates a parent execution context containing `batch_id`, output root, profile, and start time. The dispatcher must pass that context to every child runtime. Each child reuses the parent `batch_id`, allocates its own unique `run_id`, and writes only beneath its assigned child output directory.

## 8. Result and Report Interface

Every executed leaf or aggregation suite writes the complete standard result contract. `suite_summary_ref`, `completion_status`, and `termination_reason` are additive fields; they do not replace existing test, provider, verification, failure, or evidence fields. The following is an excerpt, not a complete result:

```yaml
result_contract_version: v0.3
suite_id: MOCK-SERVER-CROSS-VERIFY-v0.3
batch_id: BATCH-20260711-001
run_id: RUN-PARENT-001
test_count: 1
status: passed
completion_status: complete
termination_reason: null
test_results: [...]
provider_results: [...]
verify_results: [...]
evidence_refs: [...]
evidence_index_ref: evidence/evidence_index.yaml
failure: null
suite_summary_ref: suite_summary.json
```

For aggregation results:

- `test_case_id` is `<suite_id>-AGGREGATE`; it identifies the aggregate result and is not counted as a test case.
- The parent copies the already-materialized `test_results` from each immediate child standard result; it does not rerun tests or derive outcomes from evidence.
- Every copied entry adds `suite_path` and a globally unique `test_result_id` formed from the full suite path plus `test_case_id`.
- A nested aggregation child already contains its descendant test results. The parent consumes that immediate child list once and must not recursively read grandchild results.
- The parent similarly copies immediate-child `provider_results`, `steps`, and `verify_results` exactly once, prefixes their identities/evidence refs with `suite_path`, and rebuilds `provider_summary` from the copied provider results.
- The parent writes its own execution log, batch summary, result, summary, and merged evidence index. The merged index copies child evidence metadata with suite-path-prefixed `evidence_id` values and file paths pointing into isolated child directories; evidence files are referenced in place and are not duplicated.
- Parent `evidence_refs`, provider evidence refs, and refs inside copied provider/verify results use the rewritten merged-index IDs. Evidence validation applies the normal required-evidence rules to this merged index.
- Parent `failure` is `null` only when execution status is `passed`; otherwise it contains the aggregate failure code/category, original test failures, cleanup failures, or aggregation errors without hiding child evidence.
- `result.test_count` equals the copied `test_results.length`. When summary count completeness is `complete`, it must also equal `total_summary.test_case_count`, and status counts must match. A mismatch blocks report publication.
- When count completeness is `partial`, result and summary counts cover only copied valid results; rates are `null`, and `aggregation_errors` identifies unreported scope.
- Summary aggregation remains authoritative for suite and child counts. The copied test list exists for standard-result compatibility, test-level reporting, and evidence navigation.

The canonical v0.3 report input remains:

```bash
regress report --result <result_json>
```

The report validates `result.json`, resolves `suite_summary_ref`, validates the referenced summary, and prints these deterministic sections in order:

1. Suite identity, versions, manifest digest, profile, batch ID, and run ID.
2. Overall status, completion status, start/end time, and duration.
3. Self, child aggregate, and total counts, completion rates, and pass rates.
4. Child suite status and duration breakdown in manifest order.
5. Failed and blocked tests and child suites, grouped by category and failure code.
6. Aggregation errors and whether test-count coverage is complete or partial.
7. Result/evidence references and evidence/masking validation status.

The stable `report_status` values remain:

- `review_ready`: execution passed and result, summary, evidence, and masking validation passed.
- `review_ready_with_failures`: execution failed or blocked, but result, summary, evidence, and masking validation are structurally valid.
- `failed`: evidence or secret-guardrail validation failed.
- `invalid`: result or suite-summary contract validation failed before a reviewable report could be produced.

A release gate requires `report_status: review_ready`. Pass and completion rates are displayed only as metrics. Report validation must reject arithmetic inconsistencies before printing a successful report.

During v0.3.x migration, aggregation runs dual-write and print both `result_json` and `suite_summary_json`. `regress report --result <standard_result_json>` is canonical. The existing `regress report --result <legacy_suite_summary_json>` dual-reader behavior remains available only for compatibility until usage-kit and downstream-consumer evidence proves removal is safe.

Existing text and `--format json` report keys, including `report_status`, `test_count`, failed-evidence summary, and masking status, remain stable. New suite summary sections and keys are additive. Removal or renaming requires a later versioned contract decision.

Reference rules:

- `suite_summary_ref` is relative to the directory containing `result.json`.
- `children[].summary_ref` is relative to the parent summary directory.
- References must be normalized relative paths and must not contain absolute paths, `..`, or home expansion.
- Validators must resolve existing artifacts with real-path containment and reject symbolic-link escapes outside the assigned run directory.
- Every reference must resolve to an existing schema-valid file of the expected artifact type.

Timing invariants:

- `start_time`, `end_time`, and `generated_at` are UTC RFC 3339 timestamps; `end_time >= start_time` and `generated_at >= end_time`.
- `duration_ms` is measured with the framework monotonic clock and is non-negative. It is not recomputed from wall-clock timestamps.
- Child durations are diagnostic values and must not be summed to derive parent duration because child execution may become parallel.
- `suite_manifest_digest` is `sha256:` followed by exactly 64 lowercase hexadecimal characters.

## 9. Compatibility Migration

| Existing field | v0.3 replacement | Migration rule |
| --- | --- | --- |
| `expected_status` input | DSL verify/assert final status | Accept only as deprecated compatibility input during v0.3.x; do not expose it in the new summary. |
| `expected_failure_count` | `pass_count` after verification | Dual-write only in legacy summary output during v0.3.x. |
| `expected_failed_observed_count` | Final `passed` test/child plus verifier evidence | Dual-write only for compatibility consumers. |
| `expected_failed_missing_count` | Final `failed` plus verifier failure evidence | Dual-write only for compatibility consumers. |
| `status_taxonomy` | `status` | Keep only in legacy child output during v0.3.x. |
| `unsupported_count` | Final `blocked` or `failed`, according to verifier/runtime contract | Do not add to the new summary contract. |

New consumers must use `status`, `self_summary`, `child_aggregate_summary`, and `total_summary`. Removal of compatibility fields requires usage-kit and downstream-consumer evidence and a separately documented version decision.

Wire-contract migration rules:

- `result_contract_version` identifies the result wire contract independently of `dsl_version`. `schemas/result.v0.3.schema.yaml` requires `result_contract_version: v0.3`, extends the complete standard result with summary references and the `skipped` status, and does not weaken existing runtime/evidence fields.
- `schemas/suite_summary.v0.3.schema.yaml` is the canonical new summary schema and declares every required field, enum, nullability rule, invariant, and artifact-ref shape.
- Readers identify summaries by `suite_summary_version`. They dual-read legacy unversioned suite-group summaries and v0.3 summaries during v0.3.x, but writers produce only the v0.3 canonical summary plus explicitly named compatibility output.
- `suite_summary_json` and legacy machine-readable report keys remain available during v0.3.x. Their removal requires a later version decision backed by downstream-consumer verification.
- User guide and framework interface contract must stop describing `expected_status`, `status_taxonomy`, or expected-failure counters as canonical v0.3 output.

## 10. Implementation Scope

- Add `schemas/suite_summary.v0.3.schema.yaml` and align packaged schema copies.
- Add `schemas/result.v0.3.schema.yaml` as an additive extension of the complete standard result.
- Make leaf and aggregation suites write complete `result.json` plus canonical `suite_summary.json`.
- Introduce a parent-issued execution context so child suites share one batch ID while retaining unique run IDs and isolated output roots.
- Aggregate child summaries recursively without reading child implementation artifacts.
- Add summary-reference validation to result/evidence validation and report.
- Add timing, reproducibility metadata, count invariants, failure navigation, and partial-completion validation.
- Update CLI output, user guide, framework interface contract, AC, and test plan.
- Dual-write legacy fields only at the compatibility boundary defined above.
- Preserve current machine-readable report keys and add new summary keys without replacement.

## 11. Required Tests

- Leaf suite with one and multiple test cases.
- Aggregation suite and nested aggregation suite.
- Mixed passed/failed/blocked/skipped final statuses, including passed plus skipped, and status precedence.
- All-skipped and empty-selection behavior.
- Aggregation structural preflight blockers produce no run or summary artifacts; standard leaf blocked-run diagnostics remain intact.
- Executed blocked child has valid IDs, result, and summary.
- Child `batch_id` mismatch, duplicate `run_id`, missing summary, invalid schema, snapshot mismatch, and path traversal fail aggregation.
- Count invariants, executed/completion counts, zero-denominator behavior, and one-decimal half-up rounding.
- Complete and partial count coverage, unknown test scope, and errored-child count invariants.
- Suite/child timestamps, duration arithmetic, and manifest digest validation.
- Failure category totals, result/evidence navigation, masking, and unresolved reference failures.
- Evidence count/index mismatch and per-entry masking mismatch fail validation.
- Nested aggregation failure navigation stops at immediate child summary refs while category totals remain recursive.
- Post-execution missing or corrupt child output produces a valid partial parent result with `aggregation_error`.
- Normalized-path traversal and real-path symbolic-link escape are rejected.
- Complete, timed-out, cancelled, and framework-error partial runs preserve completed results and evidence.
- Complete standard result required fields remain required after adding `suite_summary_ref`.
- Aggregation result copies immediate-child materialized test results exactly once, uses unique suite-path identities, and cross-validates complete counts.
- `regress report --result` and `--format json` preserve existing keys and add leaf/aggregation summary output deterministically.
- Aggregation dual-output prints both `result_json` and compatibility `suite_summary_json`.
- Parent-issued execution context enforces one shared batch ID, unique child run IDs, and isolated child output paths.
- v0.2 result status compatibility and v0.3 skipped-status normalization are deterministic.
- Legacy compatibility fields are accepted only at the documented boundary and are absent from the new canonical summary.

Failure-path verifier behavior must be tested in the DSL/verifier suite; suite-summary tests consume only its final test status.

Test retries are outside the required v0.3 summary scope. If introduced later, they must be modeled separately from polling attempts; polling must never increase test retry or test case counts.

The framework guarantees partial result/summary output only for failures it catches after establishing the run output context. Process termination, JVM crash, host loss, or storage failure may leave no valid summary; report treats that condition as runner-level incomplete execution and fails with an owner-actionable missing-run-artifact error.

## 12. Acceptance Criteria

- Every suite that enters execution writes a complete valid standard `result.json` and canonical `suite_summary.json` with matching suite, batch, and run IDs.
- Every summary contains `self_summary`, `child_aggregate_summary`, and `total_summary`.
- Counts and rates satisfy all arithmetic invariants and define unambiguous zero-denominator behavior.
- Partial aggregation explicitly reports unknown test scope and never publishes a pass or completion rate.
- Aggregation summaries use only authoritative immediate-child summaries.
- Executed child entries contain a shared `batch_id`, unique `run_id`, final `status`, required `summary_ref`, and matching summary snapshot.
- Aggregation structural preflight blockers create no run artifacts; standard leaf blocked runs preserve existing diagnostic result/evidence behavior.
- `status` follows the defined precedence and remains the execution outcome; release publication additionally requires valid evidence, masking, and report status.
- Mixed passed/skipped suites resolve to `passed`; partial execution resolves to `blocked` without losing completed results.
- Summary includes execution timing, framework/DSL versions, manifest identity, and masked failure/evidence navigation.
- Aggregation standard results preserve unique test-level outcomes without using those copies to recalculate authoritative child summary counts.
- Expected-failure semantics remain owned by verify/assert and do not appear in the canonical summary contract.
- Report and validation reject unresolved, escaped, mismatched, or malformed summary references with owner-actionable errors.
- Readers support the documented v0.3.x dual-read period, and writers expose both canonical result and compatibility summary paths without changing existing JSON report keys.
