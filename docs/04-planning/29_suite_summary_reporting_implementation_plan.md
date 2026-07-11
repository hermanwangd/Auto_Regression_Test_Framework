# Suite Summary Reporting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the v0.3 canonical leaf and aggregation suite summaries, complete standard aggregation result, evidence aggregation, compatibility reader, and deterministic QA report defined in `docs/04-planning/28_suite_summary_public_contract_proposal.md`.

**Architecture:** Summary arithmetic and validation live in `com.specdriven.regression.summary`; complete result mutation remains owned by `com.specdriven.regression.contract`; legacy summary input conversion lives in `com.specdriven.regression.report`. A common finalizer turns every recoverable v0.3 leaf result into `result.json + suite_summary.json` before aggregation can consume it. Parent-issued execution context is passed through explicit runtime overloads so children share one batch ID without breaking existing standalone callers.

**Tech Stack:** Java 17, Spring Boot 3.x, Jackson, SnakeYAML, JUnit 5, AssertJ, Maven Wrapper.

---

## Resolved Implementation Decisions

1. `result_contract_version: v0.3` identifies the result wire contract independently from `dsl_version`.
2. Every recoverable v0.3 leaf and aggregation result requires `suite_summary_ref`, `completion_status`, and `termination_reason`; v0.2 readers/writers retain their existing requirements.
3. `ProviderCapabilityResultWriter` remains package-private and unchanged at its constructor boundary. New public `StandardResultDocumentService` atomically augments existing result JSON and writes aggregation results, avoiding constructor changes across provider services.
4. `SuiteArtifactFinalizer` owns leaf summary creation at the common dispatcher boundary. `SuiteRuntimeResult` carries both `resultJson` and `summaryJson`.
5. Existing runtime `run(...)` signatures remain for standalone callers. Each child-capable runtime adds an overload accepting `SuiteExecutionContext`; the existing method delegates to a standalone context.
6. Aggregation parent result fields are explicit: `test_case_id=<suite_id>-AGGREGATE`; immediate-child `test_results`, `provider_results`, `steps`, and `verify_results` are copied once with suite-path identities; `provider_summary` is rebuilt; `failure` represents aggregate outcome.
7. Parent evidence uses a merged metadata index. Evidence files stay in child directories; evidence IDs and refs are suite-path-prefixed and paths are real-path-contained under the parent run directory.
8. `LegacySuiteSummaryReportAdapter` is the only compatibility boundary for unversioned legacy suite summaries. Canonical report input remains a standard result.
9. Stable `report_status` values remain `review_ready`, `review_ready_with_failures`, `failed`, and `invalid`.
10. `skipped` is propagated through runtime status calculation, result validation, evidence counts, summary calculation, and report rendering; it is never inferred from a missing test result.

## Change Map

**Create:**

- `schemas/suite_summary.v0.3.schema.yaml`
- `schemas/result.v0.3.schema.yaml`
- `docs/02-architecture/contracts/suite_summary.v0.3.schema.yaml`
- `docs/02-architecture/contracts/result.v0.3.schema.yaml`
- `src/main/java/com/specdriven/regression/summary/SuiteExecutionContext.java`
- `src/main/java/com/specdriven/regression/summary/SuiteSummaryDocument.java`
- `src/main/java/com/specdriven/regression/summary/SuiteSummaryCalculator.java`
- `src/main/java/com/specdriven/regression/summary/SuiteSummaryValidator.java`
- `src/main/java/com/specdriven/regression/summary/SuiteSummaryWriter.java`
- `src/main/java/com/specdriven/regression/summary/SuiteArtifactFinalizer.java`
- `src/main/java/com/specdriven/regression/summary/SuiteAggregationService.java`
- `src/main/java/com/specdriven/regression/contract/StandardResultDocumentService.java`
- `src/main/java/com/specdriven/regression/report/ReportInputDocument.java`
- `src/main/java/com/specdriven/regression/report/LegacySuiteSummaryReportAdapter.java`
- `src/test/java/com/specdriven/regression/summary/SuiteSummaryCalculatorTest.java`
- `src/test/java/com/specdriven/regression/summary/SuiteSummaryValidatorTest.java`
- `src/test/java/com/specdriven/regression/summary/SuiteArtifactFinalizerTest.java`
- `src/test/java/com/specdriven/regression/summary/SuiteAggregationServiceTest.java`
- `src/test/java/com/specdriven/regression/contract/StandardResultDocumentServiceTest.java`
- `src/test/java/com/specdriven/regression/report/LegacySuiteSummaryReportAdapterTest.java`
- `src/test/java/com/specdriven/regression/cli/SuiteSummaryAggregationCommandTest.java`

**Modify runtime and contract paths:**

- `src/main/java/com/specdriven/regression/contract/ResultContractValidator.java`
- `src/main/java/com/specdriven/regression/contract/ContractBaselineService.java`
- `src/main/java/com/specdriven/regression/contract/ContractBaselineRuntimeService.java`
- `src/main/java/com/specdriven/regression/contract/v03/V03RuntimeExecutionService.java`
- `src/main/java/com/specdriven/regression/contract/WireMockHttpRequestCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/SoapMockCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/GrpcMockCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/RestClientCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/WireMockProviderCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/JdbcProviderCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/MessagingClientProviderCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/NatsProviderCapabilityService.java`
- `src/main/java/com/specdriven/regression/contract/CommonVerifyService.java`
- `src/main/java/com/specdriven/regression/contract/GoldenE2eService.java`
- `src/main/java/com/specdriven/regression/evidence/EvidenceHardeningService.java`
- `src/main/java/com/specdriven/regression/cli/RegressionCommand.java`

**Modify tests/docs:**

- `src/test/java/com/specdriven/regression/contract/ResultContractValidatorTest.java`
- `src/test/java/com/specdriven/regression/contract/v03/V03ResultEvidenceContractTest.java`
- `src/test/java/com/specdriven/regression/cli/MockServerCrossVerifySampleCommandTest.java`
- `src/test/java/com/specdriven/regression/cli/ReportJsonFormatCommandTest.java`
- `src/test/java/com/specdriven/regression/contracts/FrameworkPublicInterfaceContractTest.java`
- `docs/02-architecture/contracts/framework_usage_interface.v0.2.md`
- `docs/03-acceptance/04_acceptance_criteria.md`
- `docs/07-validation-evidence/07_regression_test_plan.md`
- `docs/09-operations/test_framework_user_guide.md`

## Task 1: Freeze Result and Summary Wire Contracts

**Files:** four schema files and `V03ResultEvidenceContractTest`.

- [ ] Add failing schema presence/drift tests for packaged and documentation copies.
- [ ] Define `result_contract_version: v0.3`, complete existing standard-result fields, mandatory summary fields, aggregation identity fields, and allowed test statuses.
- [ ] Define summary required/nullability rules, count/failure/evidence invariants, child snapshots, aggregation errors, ref shapes, and SHA-256 pattern.
- [ ] Verify RED then GREEN:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=V03ResultEvidenceContractTest test
scripts/ci/check-schema-drift.sh
```

- [ ] Commit:

```bash
git add schemas docs/02-architecture/contracts \
  src/test/java/com/specdriven/regression/contract/v03/V03ResultEvidenceContractTest.java
git commit -m "feat: define v0.3 suite reporting contracts"
```

## Task 2: Implement Summary Model, Arithmetic, and Validation

**Files:** summary document/calculator/validator/writer and focused tests.

- [ ] Write failing tests for status precedence, pass/completion rates, zero denominator, complete/partial coverage, errored children, failure totals, timestamp/digest validation, shared batch ID, snapshot mismatch, traversal, and symlink escape.
- [ ] Implement immutable records and pure calculations. Required equations include:

```java
testCaseCount == passCount + failCount + blockedCount + skippedCount
childSuiteCount == completedChildSuiteCount + blockedChildSuiteCount
        + skippedChildSuiteCount + erroredChildSuiteCount
totalIssueCount == testFailureCount + testBlockedCount + aggregationErrorCount
```

- [ ] Implement validator findings as owner-actionable `ContractFinding` values. Resolve artifact refs with `toRealPath()` containment.
- [ ] Verify:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=SuiteSummaryCalculatorTest,SuiteSummaryValidatorTest test
```

- [ ] Commit `feat: add canonical suite summary model`.

## Task 3: Add Contract-Owned Standard Result Service

**Files:** `StandardResultDocumentService`, result validator, contract service, and focused tests.

- [ ] Write failing tests proving v0.3 requires `result_contract_version`, `suite_summary_ref`, `completion_status`, and `termination_reason` while v0.2 remains readable.
- [ ] Write failing tests proving complete standard fields remain mandatory and aggregation arrays retain test/provider/step/verify/evidence/failure data.
- [ ] Implement `StandardResultDocumentService` with atomic temp-file replace:

```java
public Path augmentLeafResult(Path resultJson, Path summaryJson, String completionStatus, String terminationReason);
public Path writeAggregationResult(Path runDir, Map<String, Object> completeResult);
```

- [ ] Make all v0.3 results require summary fields unconditionally by `result_contract_version`; never gate requiredness on field presence.
- [ ] Verify:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=StandardResultDocumentServiceTest,ResultContractValidatorTest,V03ResultEvidenceContractTest test
```

- [ ] Commit `feat: add versioned standard result service`.

## Task 4: Propagate v0.3 Status and Counter Semantics

**Files:** `V03RuntimeExecutionService`, `ResultContractValidator`, `EvidenceHardeningService`, report tests.

- [ ] Write failing tests for passed+skipped, all-skipped, blocked precedence, partial completion, and evidence counts where skipped is not failed.
- [ ] Replace binary suite status logic with the canonical precedence from the proposal.
- [ ] Replace `failCount = testCount - passCount` with explicit passed/failed/blocked/skipped counting.
- [ ] Preserve v0.2 accepted statuses; allow `skipped` only under `result_contract_version: v0.3`.
- [ ] Verify:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=ResultContractValidatorTest,V03ResultEvidenceContractTest,EvidenceHardeningCommandTest test
```

- [ ] Commit `feat: propagate v0.3 suite statuses`.

## Task 5: Inject Shared Execution Context Without Breaking Standalone Runtimes

**Files:** `SuiteExecutionContext`, all runtime service files listed in the change map, and runtime-specific tests.

- [ ] Define context with parent batch ID, profile, start time, output root, and child-root containment.
- [ ] For every listed runtime service, add a context-aware overload; retain the existing signature and delegate it to a standalone context.
- [ ] Context-aware runs reuse supplied batch ID, allocate a unique run ID, and write under the supplied root. No runtime may mint a replacement batch ID.
- [ ] Add contract tests that invoke representative v0.3, WireMock/REST, JDBC, messaging, common-verify, and golden runtimes through both standalone and supplied-context overloads.
- [ ] Verify focused runtime tests with bounded Maven memory.
- [ ] Commit `feat: pass suite execution context to runtimes`.

## Task 6: Finalize Every Leaf Result and Summary

**Files:** `SuiteArtifactFinalizer`, `SuiteSummaryWriter`, `RegressionCommand.SuiteRuntimeResult`, dispatcher tests.

- [ ] Write failing tests proving direct and child leaf runs both produce complete `result.json`, canonical `suite_summary.json`, and matching IDs/counts.
- [ ] Implement finalizer at the common `dispatchSuiteRuntimeInternal` return boundary. It reads the existing standard result, calculates the leaf summary, writes it, then atomically augments the result.
- [ ] Extend `SuiteRuntimeResult` with `summaryJson`; print `suite_summary_json` for direct v0.3 runs.
- [ ] Recoverable finalizer failure produces blocked/partial artifacts when the output context exists; process/storage loss remains missing-run-artifact.
- [ ] Verify:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=SuiteArtifactFinalizerTest,GoldenE2eCommandTest,DslV03ProviderRuntimeExecutionCommandTest test
```

- [ ] Commit `feat: finalize leaf suite artifacts`.

## Task 7: Build Canonical Aggregation Result, Summary, and Evidence Index

**Files:** `SuiteAggregationService`, summary writer, evidence service, `RegressionCommand` suite-group path, new canonical CLI test.

- [ ] Write failing service tests for shared batch ID, unique child run IDs, nested no-duplication, suite-path test identities, authoritative child snapshots, partial coverage, and corrupt child artifacts.
- [ ] Copy immediate-child `test_results`, `provider_results`, `steps`, and `verify_results` once; prefix identities/refs with suite path; rebuild provider summary.
- [ ] Build parent merged evidence index by copying child metadata with prefixed IDs and contained child file paths. Rewrite all parent refs to merged IDs; do not duplicate evidence files.
- [ ] Populate all complete standard-result fields, including aggregate `test_case_id`, profile/environment/timestamps, evidence refs/index, and aggregate failure.
- [ ] Write parent `result.json`, canonical summary, compatibility summary/YAML, and Allure output. Print `result_json` and `suite_summary_json`.
- [ ] Keep `MockServerCrossVerifySampleCommandTest` for legacy compatibility. Add `SuiteSummaryAggregationCommandTest` for canonical result/summary/evidence behavior.
- [ ] Verify:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=SuiteAggregationServiceTest,SuiteSummaryAggregationCommandTest,MockServerCrossVerifySampleCommandTest test
```

- [ ] Commit `feat: aggregate canonical suite artifacts`.

## Task 8: Add Explicit Legacy Report Adapter and Rich Report Model

**Files:** `ReportInputDocument`, `LegacySuiteSummaryReportAdapter`, `ContractBaselineService.ReportResult`, `RegressionCommand`, report tests.

- [ ] Write failing adapter tests for current unversioned suite summaries, malformed legacy input, and owner-actionable incompatibility errors.
- [ ] Detect input type once at the report boundary: standard result goes through result+summary validation; legacy summary goes only through the compatibility adapter.
- [ ] Widen `ReportResult` to carry parsed summary sections, failure/child/aggregation details, evidence state, and compatibility-source marker.
- [ ] Preserve `report_status` values exactly: `review_ready`, `review_ready_with_failures`, `failed`, `invalid`.
- [ ] Preserve existing text/JSON keys and add deterministic summary sections. Legacy adapter output must never claim canonical evidence validation when no standard result/index exists.
- [ ] Verify:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=LegacySuiteSummaryReportAdapterTest,ReportJsonFormatCommandTest,ReportAndEvidenceCommandTest test
```

- [ ] Commit `feat: report canonical and legacy suite summaries`.

## Task 9: Align Public Docs, AC, Test Plan, and Samples

**Files:** four public docs, samples, `FrameworkPublicInterfaceContractTest`, and release/sample contract tests.

- [ ] Update framework interface first, then AC, regression test plan, user guide, and canonical samples.
- [ ] Remove legacy expected-failure fields from canonical v0.3 output descriptions; keep them only in the compatibility section.
- [ ] Update `FrameworkPublicInterfaceContractTest` to freeze result contract version, dual output, status enums, summary fields, compatibility adapter, and evidence semantics.
- [ ] Add AC-to-test mapping for schema, leaf finalization, status propagation, aggregation, evidence merge, report, compatibility, and security paths.
- [ ] Verify:

```bash
scripts/ci/check-public-support-contract.sh
scripts/ci/check-schema-drift.sh
scripts/release/verify-v0-3-runtime-samples.sh
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest=FrameworkPublicInterfaceContractTest,SampleLayoutContractTest test
```

- [ ] Commit `docs: align suite summary reporting contracts`.

## Task 10: Full Verification and Migration Evidence

- [ ] Run targeted tests:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q \
  -Dtest='SuiteSummary*Test,SuiteArtifactFinalizerTest,StandardResultDocumentServiceTest,ResultContractValidatorTest,SuiteSummaryAggregationCommandTest,MockServerCrossVerifySampleCommandTest,LegacySuiteSummaryReportAdapterTest,ReportJsonFormatCommandTest,ReportAndEvidenceCommandTest' test
```

- [ ] Run bounded full verification and release guards:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q verify
scripts/ci/check-schema-drift.sh
scripts/ci/check-public-support-contract.sh
scripts/ci/verify-contracts.sh
scripts/release/verify-v0-3-runtime-samples.sh
scripts/release/verify-supported-provider-samples.sh
```

- [ ] Package and run canonical CLI acceptance:

```bash
MAVEN_OPTS="-Xmx1024m -XX:MaxMetaspaceSize=384m" ./mvnw -q -DskipTests package
java -jar target/spec-driven-auto-regression-0.3.0.jar run \
  --suite samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml \
  --profile local_v03 | tee target/suite-summary-run.out
RESULT_JSON=$(awk -F': ' '$1 == "result_json" {print $2}' target/suite-summary-run.out)
test -n "$RESULT_JSON"
java -jar target/spec-driven-auto-regression-0.3.0.jar report --result "$RESULT_JSON" --format json
java -jar target/spec-driven-auto-regression-0.3.0.jar validate-evidence --result "$RESULT_JSON"
```

- [ ] Run legacy adapter acceptance using a checked-in legacy suite summary fixture and verify the report marks `compatibility_source: legacy_suite_summary`.
- [ ] Record usage-kit/downstream verification showing consumers use `result_json`; do not remove `suite_summary_json` or the adapter in this release.
- [ ] Confirm no raw secret, absolute path, traversal, symlink escape, duplicate test/evidence ID, count mismatch, or legacy canonical field appears.
- [ ] Commit verification-only adjustments as `test: verify suite summary reporting lifecycle`; do not create an empty commit.

## Completion Gate

Implementation is complete only when:

- Every recoverable v0.3 leaf and aggregation run writes a complete standard result and canonical summary.
- Parent/children share one batch ID, use unique run IDs, and keep isolated output roots.
- Aggregation preserves all required result arrays and validates a merged, masked evidence index.
- Status, count, completion, failure, and evidence invariants agree across runtime, result, summary, report, AC, and test plan.
- Canonical and legacy report paths are isolated and return the frozen report-status values.
- Targeted tests, full Maven verification, release guards, canonical CLI acceptance, legacy adapter acceptance, and downstream migration evidence pass.
