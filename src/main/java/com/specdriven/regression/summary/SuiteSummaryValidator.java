package com.specdriven.regression.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public final class SuiteSummaryValidator {
    private static final Set<String> STATUSES = Set.of("passed", "failed", "blocked", "skipped");
    private static final Set<String> TERMINATION_REASONS =
            Set.of("timeout", "cancelled", "framework_error", "aggregation_error");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public List<ContractFinding> validate(SuiteSummaryDocument document, Path summaryDirectory) {
        Set<Path> activeSummaries = new HashSet<>();
        try {
            Path current = summaryDirectory.resolve("suite_summary.json");
            if (Files.exists(current)) activeSummaries.add(current.toRealPath());
        } catch (Exception ignored) {
            // Reference validation below reports inaccessible paths as contract findings.
        }
        return validate(document, summaryDirectory, activeSummaries);
    }

    private List<ContractFinding> validate(
            SuiteSummaryDocument document, Path summaryDirectory, Set<Path> activeSummaries) {
        List<ContractFinding> findings = new ArrayList<>();
        if (document == null) {
            findings.add(finding(summaryDirectory, "$", "Suite summary is missing",
                    "Produce a canonical v0.3 suite summary before reporting."));
            return List.copyOf(findings);
        }
        require(findings, summaryDirectory, "suite_summary_version", document.suiteSummaryVersion());
        require(findings, summaryDirectory, "suite_id", document.suiteId());
        require(findings, summaryDirectory, "batch_id", document.batchId());
        require(findings, summaryDirectory, "run_id", document.runId());
        require(findings, summaryDirectory, "profile", document.profile());
        require(findings, summaryDirectory, "framework_version", document.frameworkVersion());
        require(findings, summaryDirectory, "dsl_version", document.dslVersion());
        if (!"v0.3".equals(document.suiteSummaryVersion())) {
            add(findings, summaryDirectory, "suite_summary_version", "Expected suite_summary_version v0.3");
        }
        if (!isStatus(document.status())) {
            add(findings, summaryDirectory, "status", "Status must be passed, failed, blocked, or skipped");
        }
        validateCompletion(document, summaryDirectory, findings);
        validateTiming(document.startTime(), document.endTime(), document.generatedAt(), document.durationMs(),
                "", summaryDirectory, findings);
        if (document.suiteManifestDigest() == null || !DIGEST.matcher(document.suiteManifestDigest()).matches()) {
            add(findings, summaryDirectory, "suite_manifest_digest",
                    "Manifest digest must be sha256 followed by 64 lowercase hexadecimal characters");
        }

        validateCounts(document.selfSummary(), "self_summary", summaryDirectory, findings);
        validateChildCounts(document.childAggregateSummary(), summaryDirectory, findings);
        validateCounts(document.totalSummary(), "total_summary", summaryDirectory, findings);
        validateTotals(document, summaryDirectory, findings);
        validateFailures(document, summaryDirectory, findings);
        if (document.failureSummary() != null) {
            validateAggregationErrors(document, summaryDirectory, findings);
        }
        validateReferences(document, summaryDirectory, findings);
        validateChildren(document, summaryDirectory, findings, activeSummaries);
        validateSuiteKind(document, summaryDirectory, findings);
        validateStatus(document, summaryDirectory, findings);
        return List.copyOf(findings);
    }

    private void validateCompletion(SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        if (!("complete".equals(document.completionStatus()) || "partial".equals(document.completionStatus()))) {
            add(findings, path, "completion_status", "Completion status must be complete or partial");
        } else if ("complete".equals(document.completionStatus()) && document.terminationReason() != null) {
            add(findings, path, "termination_reason", "Complete runs must have a null termination reason");
        } else if ("partial".equals(document.completionStatus())) {
            if (document.terminationReason() == null || !TERMINATION_REASONS.contains(document.terminationReason())) {
                add(findings, path, "termination_reason", "Partial runs require an allowed termination reason");
            }
            if (!"blocked".equals(document.status())) {
                add(findings, path, "status", "Partial runs must have blocked status");
            }
        }
    }

    private void validateCounts(
            SuiteSummaryDocument.Counts counts, String field, Path path, List<ContractFinding> findings) {
        if (counts == null) {
            add(findings, path, field, "Required count summary is missing");
            return;
        }
        validateCompleteness(counts.countCompleteness(), counts.unknownTestCaseCount(),
                counts.passRatePercent(), counts.completionRatePercent(), field, path, findings);
        validateNonNegative(List.of(counts.testCaseCount(), counts.executedCount(), counts.passCount(),
                counts.failCount(), counts.blockedCount(), counts.skippedCount()), field, path, findings);
        if (counts.testCaseCount()
                != counts.passCount() + counts.failCount() + counts.blockedCount() + counts.skippedCount()) {
            add(findings, path, field + ".test_case_count",
                    "test_case_count must equal pass + fail + blocked + skipped counts");
        }
        if (counts.executedCount() != counts.passCount() + counts.failCount()) {
            add(findings, path, field + ".executed_count", "executed_count must equal pass_count + fail_count");
        }
        validateRates(counts.countCompleteness(), counts.passCount(), counts.executedCount(),
                counts.testCaseCount(), counts.passRatePercent(), counts.completionRatePercent(), field, path, findings);
    }

    private void validateChildCounts(
            SuiteSummaryDocument.ChildCounts counts, Path path, List<ContractFinding> findings) {
        String field = "child_aggregate_summary";
        if (counts == null) {
            add(findings, path, field, "Required child aggregate summary is missing");
            return;
        }
        validateCompleteness(counts.countCompleteness(), counts.unknownTestCaseCount(),
                counts.passRatePercent(), counts.completionRatePercent(), field, path, findings);
        validateNonNegative(List.of(counts.childSuiteCount(), counts.completedChildSuiteCount(),
                counts.blockedChildSuiteCount(), counts.skippedChildSuiteCount(), counts.erroredChildSuiteCount(),
                counts.testCaseCount(), counts.executedCount(), counts.passCount(), counts.failCount(),
                counts.blockedCount(), counts.skippedCount()), field, path, findings);
        if (counts.childSuiteCount() != counts.completedChildSuiteCount() + counts.blockedChildSuiteCount()
                + counts.skippedChildSuiteCount() + counts.erroredChildSuiteCount()) {
            add(findings, path, field + ".child_suite_count",
                    "child_suite_count must equal completed + blocked + skipped + errored child counts");
        }
        if (counts.testCaseCount()
                != counts.passCount() + counts.failCount() + counts.blockedCount() + counts.skippedCount()) {
            add(findings, path, field + ".test_case_count",
                    "test_case_count must equal pass + fail + blocked + skipped counts");
        }
        if (counts.executedCount() != counts.passCount() + counts.failCount()) {
            add(findings, path, field + ".executed_count", "executed_count must equal pass_count + fail_count");
        }
        validateRates(counts.countCompleteness(), counts.passCount(), counts.executedCount(),
                counts.testCaseCount(), counts.passRatePercent(), counts.completionRatePercent(), field, path, findings);
    }

    private void validateTotals(SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        if (document.selfSummary() == null || document.childAggregateSummary() == null || document.totalSummary() == null) {
            return;
        }
        SuiteSummaryDocument.Counts self = document.selfSummary();
        SuiteSummaryDocument.ChildCounts child = document.childAggregateSummary();
        SuiteSummaryDocument.Counts total = document.totalSummary();
        checkSum(total.testCaseCount(), self.testCaseCount(), child.testCaseCount(), "test_case_count", path, findings);
        checkSum(total.executedCount(), self.executedCount(), child.executedCount(), "executed_count", path, findings);
        checkSum(total.passCount(), self.passCount(), child.passCount(), "pass_count", path, findings);
        checkSum(total.failCount(), self.failCount(), child.failCount(), "fail_count", path, findings);
        checkSum(total.blockedCount(), self.blockedCount(), child.blockedCount(), "blocked_count", path, findings);
        checkSum(total.skippedCount(), self.skippedCount(), child.skippedCount(), "skipped_count", path, findings);
        String expected = "complete".equals(self.countCompleteness()) && "complete".equals(child.countCompleteness())
                ? "complete" : "partial";
        if (!expected.equals(total.countCompleteness())) {
            add(findings, path, "total_summary.count_completeness",
                    "Total count completeness must reflect self and child coverage");
        }
        if (child.erroredChildSuiteCount() > 0
                && (!("partial".equals(child.countCompleteness()) && child.unknownTestCaseCount()
                && "partial".equals(document.completionStatus()) && "blocked".equals(document.status())))) {
            add(findings, path, "child_aggregate_summary.errored_child_suite_count",
                    "Errored children require partial unknown coverage and a partial blocked run");
        }
    }

    private void validateFailures(SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        SuiteSummaryDocument.FailureSummary failure = document.failureSummary();
        if (failure == null || document.totalSummary() == null) {
            add(findings, path, "failure_summary", "Required failure summary is missing");
            return;
        }
        if (failure.testFailureCount() != document.totalSummary().failCount()) {
            add(findings, path, "failure_summary.test_failure_count", "Must equal total_summary.fail_count");
        }
        if (failure.testBlockedCount() != document.totalSummary().blockedCount()) {
            add(findings, path, "failure_summary.test_blocked_count", "Must equal total_summary.blocked_count");
        }
        if (failure.aggregationErrorCount() != document.aggregationErrors().size()) {
            add(findings, path, "failure_summary.aggregation_error_count", "Must equal aggregation_errors length");
        }
        int expected = failure.testFailureCount() + failure.testBlockedCount() + failure.aggregationErrorCount();
        if (failure.totalIssueCount() != expected) {
            add(findings, path, "failure_summary.total_issue_count", "Must equal all failure and aggregation counts");
        }
        int categories = failure.byCategory().values().stream().mapToInt(Integer::intValue).sum();
        if (failure.byCategory().values().stream().anyMatch(value -> value == null || value < 0)) {
            add(findings, path, "failure_summary.by_category", "Category counts must be non-negative integers");
        }
        if (categories != failure.totalIssueCount()) {
            add(findings, path, "failure_summary.by_category", "Category counts must sum to total_issue_count");
        }
    }

    private void validateAggregationErrors(
            SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        for (int i = 0; i < document.aggregationErrors().size(); i++) {
            SuiteSummaryDocument.AggregationError error = document.aggregationErrors().get(i);
            String prefix = "aggregation_errors[" + i + "]";
            require(findings, path, prefix + ".child_suite_id", error.childSuiteId());
            require(findings, path, prefix + ".artifact_ref", error.artifactRef());
            require(findings, path, prefix + ".failure_code", error.failureCode());
            require(findings, path, prefix + ".owner_action", error.ownerAction());
        }
        SuiteSummaryDocument.FailureSummary failure = document.failureSummary();
        for (int i = 0; i < failure.failedTestRefs().size(); i++) {
            SuiteSummaryDocument.FailedTestRef ref = failure.failedTestRefs().get(i);
            String prefix = "failure_summary.failed_test_refs[" + i + "]";
            require(findings, path, prefix + ".test_case_id", ref.testCaseId());
            require(findings, path, prefix + ".failure_code", ref.failureCode());
            require(findings, path, prefix + ".category", ref.category());
            if (!("failed".equals(ref.finalStatus()) || "blocked".equals(ref.finalStatus()))) {
                add(findings, path, prefix + ".final_status", "Failure navigation status must be failed or blocked");
            }
        }
        for (int i = 0; i < failure.failedChildRefs().size(); i++) {
            SuiteSummaryDocument.FailedChildRef ref = failure.failedChildRefs().get(i);
            String prefix = "failure_summary.failed_child_refs[" + i + "]";
            require(findings, path, prefix + ".child_suite_id", ref.childSuiteId());
            if (!("failed".equals(ref.finalStatus()) || "blocked".equals(ref.finalStatus()))) {
                add(findings, path, prefix + ".final_status", "Failure navigation status must be failed or blocked");
            }
        }
    }

    private void validateChildren(SuiteSummaryDocument document, Path path, List<ContractFinding> findings,
            Set<Path> activeSummaries) {
        Set<String> runIds = new HashSet<>();
        int tests = 0, executed = 0, passed = 0, failed = 0, blocked = 0, skipped = 0;
        int completedChildren = 0, blockedChildren = 0, skippedChildren = 0;
        for (int i = 0; i < document.children().size(); i++) {
            SuiteSummaryDocument.ChildEntry child = document.children().get(i);
            String prefix = "children[" + i + "]";
            require(findings, path, prefix + ".child_suite_id", child.childSuiteId());
            require(findings, path, prefix + ".run_id", child.runId());
            if (!isStatus(child.status())) {
                add(findings, path, prefix + ".status", "Child status must be passed, failed, blocked, or skipped");
            }
            resolveReference(child.ref(), prefix + ".ref", path, findings);
            if (!Objects.equals(document.batchId(), child.batchId())) {
                add(findings, path, prefix + ".batch_id", "Child batch_id must equal parent batch_id");
            }
            if (!runIds.add(child.runId())) {
                add(findings, path, prefix + ".run_id", "Child run_id must be unique within the batch");
            }
            validateChildTiming(child.startTime(), child.endTime(), child.durationMs(), prefix, path, findings);
            if (child.totalSummary() != null) {
                tests += child.totalSummary().testCaseCount();
                executed += child.totalSummary().executedCount();
                passed += child.totalSummary().passCount();
                failed += child.totalSummary().failCount();
                blocked += child.totalSummary().blockedCount();
                skipped += child.totalSummary().skippedCount();
            }
            if ("passed".equals(child.status()) || "failed".equals(child.status())) completedChildren++;
            if ("blocked".equals(child.status())) blockedChildren++;
            if ("skipped".equals(child.status())) skippedChildren++;
            Path resolved = resolveReference(child.summaryRef(), prefix + ".summary_ref", path, findings);
            if (resolved != null) {
                validateChildSnapshot(document, child, prefix, resolved, path, findings, activeSummaries);
            }
        }
        SuiteSummaryDocument.ChildCounts aggregate = document.childAggregateSummary();
        if (aggregate != null) {
            compare(aggregate.childSuiteCount(), document.children().size() + aggregate.erroredChildSuiteCount(),
                    "child_aggregate_summary.child_suite_count", path, findings);
            compare(aggregate.completedChildSuiteCount(), completedChildren,
                    "child_aggregate_summary.completed_child_suite_count", path, findings);
            compare(aggregate.blockedChildSuiteCount(), blockedChildren,
                    "child_aggregate_summary.blocked_child_suite_count", path, findings);
            compare(aggregate.skippedChildSuiteCount(), skippedChildren,
                    "child_aggregate_summary.skipped_child_suite_count", path, findings);
            compare(aggregate.testCaseCount(), tests, "child_aggregate_summary.test_case_count", path, findings);
            compare(aggregate.executedCount(), executed, "child_aggregate_summary.executed_count", path, findings);
            compare(aggregate.passCount(), passed, "child_aggregate_summary.pass_count", path, findings);
            compare(aggregate.failCount(), failed, "child_aggregate_summary.fail_count", path, findings);
            compare(aggregate.blockedCount(), blocked, "child_aggregate_summary.blocked_count", path, findings);
            compare(aggregate.skippedCount(), skipped, "child_aggregate_summary.skipped_count", path, findings);
        }
    }

    private void validateChildSnapshot(SuiteSummaryDocument parent, SuiteSummaryDocument.ChildEntry child,
            String prefix, Path childPath, Path path, List<ContractFinding> findings, Set<Path> activeSummaries) {
        if (!activeSummaries.add(childPath)) {
            add(findings, path, prefix + ".summary_ref", "Referenced child summary creates a validation cycle");
            return;
        }
        try {
            SuiteSummaryDocument authoritative = mapper.readValue(childPath.toFile(), SuiteSummaryDocument.class);
            List<ContractFinding> childFindings = validate(authoritative, childPath.getParent(), activeSummaries);
            for (ContractFinding childFinding : childFindings) {
                findings.add(new ContractFinding(path.toString(), prefix + ".summary." + childFinding.fieldPath(),
                        childFinding.reason(), "", "", "", "", childFinding.ownerAction()));
            }
            if (!Objects.equals(child.childSuiteId(), authoritative.suiteId())) {
                add(findings, path, prefix + ".child_suite_id", "Must match referenced child suite_id");
            }
            if (!Objects.equals(child.batchId(), authoritative.batchId())
                    || !Objects.equals(parent.batchId(), authoritative.batchId())) {
                add(findings, path, prefix + ".batch_id", "Must match referenced child and parent batch_id");
            }
            if (!Objects.equals(child.runId(), authoritative.runId())) {
                add(findings, path, prefix + ".run_id", "Must match referenced child run_id");
            }
            if (!Objects.equals(child.status(), authoritative.status())) {
                add(findings, path, prefix + ".status", "Must match referenced child status");
            }
            if (!Objects.equals(child.startTime(), authoritative.startTime())) {
                add(findings, path, prefix + ".start_time", "Must match referenced child start_time");
            }
            if (!Objects.equals(child.endTime(), authoritative.endTime())) {
                add(findings, path, prefix + ".end_time", "Must match referenced child end_time");
            }
            if (child.durationMs() != authoritative.durationMs()) {
                add(findings, path, prefix + ".duration_ms", "Must match referenced child duration_ms");
            }
            if (!Objects.equals(child.totalSummary(), authoritative.totalSummary())) {
                add(findings, path, prefix + ".total_summary", "Must equal referenced authoritative child snapshot");
            }
        } catch (Exception exception) {
            add(findings, path, prefix + ".summary_ref",
                    "Referenced child summary is malformed or cannot be read: " + exception.getMessage());
        } finally {
            activeSummaries.remove(childPath);
        }
    }

    private void validateReferences(SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        resolveReference(document.suiteManifestRef(), "suite_manifest_ref", path, findings);
        Set<String> evidenceIds = Set.of();
        if (document.evidenceSummary() == null) {
            add(findings, path, "evidence_summary", "Required evidence summary is missing");
        } else {
            Path evidence = resolveReference(document.evidenceSummary().evidenceIndexRef(),
                    "evidence_summary.evidence_index_ref", path, findings);
            if (evidence != null) {
                evidenceIds = validateEvidenceIndex(document, evidence, path, findings);
            }
        }
        if (document.failureSummary() == null) return;
        for (int i = 0; i < document.failureSummary().failedTestRefs().size(); i++) {
            SuiteSummaryDocument.FailedTestRef failed = document.failureSummary().failedTestRefs().get(i);
            resolveReference(failed.resultRef(),
                    "failure_summary.failed_test_refs[" + i + "].result_ref", path, findings);
            for (int j = 0; j < failed.evidenceRefs().size(); j++) {
                validateEvidenceReference(failed.evidenceRefs().get(j), evidenceIds,
                        "failure_summary.failed_test_refs[" + i + "].evidence_refs[" + j + "]", path, findings);
            }
        }
        for (int i = 0; i < document.failureSummary().failedChildRefs().size(); i++) {
            resolveReference(document.failureSummary().failedChildRefs().get(i).summaryRef(),
                    "failure_summary.failed_child_refs[" + i + "].summary_ref", path, findings);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> validateEvidenceIndex(SuiteSummaryDocument document, Path index, Path path,
            List<ContractFinding> findings) {
        Set<String> evidenceIds = new HashSet<>();
        try {
            Object loaded = new Yaml().load(Files.readString(index));
            if (!(loaded instanceof Map<?, ?> root)) {
                add(findings, path, "evidence_summary.evidence_index_ref",
                        "Evidence index root must be a map containing an entries list");
                return Set.of();
            }
            Object entriesValue = root.get("entries");
            if (!(entriesValue instanceof List<?> rawEntries)) {
                add(findings, path, "evidence_summary.evidence_index_ref",
                        "Evidence index entries must be a list");
                return Set.of();
            }
            List<Object> entries = (List<Object>) rawEntries;
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("evidence_id") instanceof String id) {
                    evidenceIds.add(id);
                }
            }
            if (document.evidenceSummary().evidenceCount() != entries.size()) {
                add(findings, path, "evidence_summary.evidence_count", "Must equal evidence index entries length");
            }
            if (document.evidenceSummary().maskingApplied()) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("masking_applied"))) {
                        add(findings, path, "evidence_summary.masking_applied",
                                "Every indexed evidence entry must declare masking_applied true");
                        break;
                    }
                }
            }
        } catch (Exception exception) {
            add(findings, path, "evidence_summary.evidence_index_ref",
                    "Evidence index is malformed: " + exception.getMessage());
        }
        return Set.copyOf(evidenceIds);
    }

    private void validateEvidenceReference(String reference, Set<String> evidenceIds, String field, Path path,
            List<ContractFinding> findings) {
        if (reference != null && reference.startsWith("evidence://")) {
            String id = reference.substring("evidence://".length());
            if (id.isBlank() || !evidenceIds.contains(id)) {
                add(findings, path, field, "Evidence URI must identify an entry in the canonical evidence index");
            }
            return;
        }
        resolveReference(reference, field, path, findings);
    }

    private void validateSuiteKind(SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        if (document.failureSummary() == null || document.childAggregateSummary() == null
                || document.selfSummary() == null) return;
        boolean aggregation = !document.children().isEmpty()
                || document.childAggregateSummary().childSuiteCount() > 0;
        if (!aggregation) {
            if (document.childAggregateSummary().childSuiteCount() != 0) {
                add(findings, path, "child_aggregate_summary.child_suite_count",
                        "Leaf suites must have zero child counts");
            }
            if (!document.failureSummary().failedChildRefs().isEmpty()) {
                add(findings, path, "failure_summary.failed_child_refs",
                        "Leaf suites navigate failures only through failed_test_refs");
            }
            validateLeafFailureRefs(document, path, findings);
            return;
        }
        if (!isZero(document.selfSummary())) {
            add(findings, path, "self_summary", "Aggregation suites must have a zero-valued self_summary");
        }
        if (!document.failureSummary().failedTestRefs().isEmpty()) {
            add(findings, path, "failure_summary.failed_test_refs",
                    "Aggregation suites navigate failures only through immediate failed_child_refs");
        }
        for (SuiteSummaryDocument.FailedChildRef failed : document.failureSummary().failedChildRefs()) {
            boolean immediate = document.children().stream().anyMatch(child ->
                    Objects.equals(child.childSuiteId(), failed.childSuiteId())
                            && Objects.equals(child.status(), failed.finalStatus())
                            && Objects.equals(child.summaryRef(), failed.summaryRef())
                            && ("failed".equals(child.status()) || "blocked".equals(child.status())));
            if (!immediate) add(findings, path, "failure_summary.failed_child_refs",
                    "Aggregation failure refs must match immediate failed or blocked children");
        }
        for (SuiteSummaryDocument.ChildEntry child : document.children()) {
            if (("failed".equals(child.status()) || "blocked".equals(child.status()))
                    && document.failureSummary().failedChildRefs().stream().noneMatch(failed ->
                    Objects.equals(child.childSuiteId(), failed.childSuiteId())
                            && Objects.equals(child.status(), failed.finalStatus())
                            && Objects.equals(child.summaryRef(), failed.summaryRef()))) {
                add(findings, path, "failure_summary.failed_child_refs",
                        "Every immediate failed or blocked child requires a matching failed_child_ref");
            }
        }
    }

    private void validateLeafFailureRefs(
            SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        List<SuiteSummaryDocument.FailedTestRef> refs = document.failureSummary().failedTestRefs();
        int expected = document.totalSummary() == null
                ? 0 : document.totalSummary().failCount() + document.totalSummary().blockedCount();
        if (refs.size() != expected) {
            add(findings, path, "failure_summary.failed_test_refs",
                    "Leaf suites require exactly one failure ref per failed or blocked test");
        }
        long failedRefs = refs.stream().filter(ref -> "failed".equals(ref.finalStatus())).count();
        long blockedRefs = refs.stream().filter(ref -> "blocked".equals(ref.finalStatus())).count();
        if (document.totalSummary() != null
                && (failedRefs != document.totalSummary().failCount()
                || blockedRefs != document.totalSummary().blockedCount())) {
            add(findings, path, "failure_summary.failed_test_refs",
                    "Failure ref statuses must exactly match leaf failed and blocked counts");
        }
        Set<String> testCaseIds = new HashSet<>();
        for (int i = 0; i < refs.size(); i++) {
            String testCaseId = refs.get(i).testCaseId();
            if (testCaseId != null && !testCaseIds.add(testCaseId)) {
                add(findings, path, "failure_summary.failed_test_refs[" + i + "].test_case_id",
                        "Leaf failure refs must use unique test_case_id values");
            }
        }
    }

    private boolean isZero(SuiteSummaryDocument.Counts counts) {
        return counts.testCaseCount() == 0 && counts.executedCount() == 0 && counts.passCount() == 0
                && counts.failCount() == 0 && counts.blockedCount() == 0 && counts.skippedCount() == 0
                && counts.passRatePercent() == null && counts.completionRatePercent() == null;
    }

    private Path resolveReference(String reference, String field, Path base, List<ContractFinding> findings) {
        if (reference == null || reference.isBlank()) {
            add(findings, base, field, "Artifact reference is required");
            return null;
        }
        try {
            Path ref = Path.of(reference);
            if (ref.isAbsolute() || reference.startsWith("~") || ref.normalize().startsWith("..")
                    || !ref.equals(ref.normalize())) {
                add(findings, base, field, "Artifact reference must be a normalized relative path without .. or ~");
                return null;
            }
            Path realBase = base.toRealPath();
            Path resolved = realBase.resolve(ref).toRealPath();
            if (!resolved.startsWith(realBase)) {
                add(findings, base, field, "Artifact reference resolves outside the assigned summary directory");
                return null;
            }
            return resolved;
        } catch (Exception exception) {
            add(findings, base, field, "Artifact reference does not resolve to an existing contained file");
            return null;
        }
    }

    private void validateStatus(SuiteSummaryDocument document, Path path, List<ContractFinding> findings) {
        if (document.totalSummary() == null) return;
        String expected;
        if ("partial".equals(document.completionStatus()) || document.totalSummary().blockedCount() > 0
                || document.children().stream().anyMatch(child -> "blocked".equals(child.status()))
                || !document.aggregationErrors().isEmpty()) expected = "blocked";
        else if (document.totalSummary().failCount() > 0) expected = "failed";
        else if (document.totalSummary().passCount() > 0) expected = "passed";
        else expected = "skipped";
        if (!expected.equals(document.status())) {
            add(findings, path, "status", "Status does not follow blocked, failed, passed, skipped precedence");
        }
    }

    private void validateTiming(String start, String end, String generated, long duration, String prefix,
            Path path, List<ContractFinding> findings) {
        Instant startInstant = parse(start, prefix + "start_time", path, findings);
        Instant endInstant = parse(end, prefix + "end_time", path, findings);
        Instant generatedInstant = parse(generated, prefix + "generated_at", path, findings);
        if (startInstant != null && endInstant != null && endInstant.isBefore(startInstant)) {
            add(findings, path, prefix + "end_time", "end_time must not precede start_time");
        }
        if (endInstant != null && generatedInstant != null && generatedInstant.isBefore(endInstant)) {
            add(findings, path, prefix + "generated_at", "generated_at must not precede end_time");
        }
        if (duration < 0) add(findings, path, prefix + "duration_ms", "duration_ms must be non-negative");
    }

    private void validateChildTiming(
            String start, String end, long duration, String prefix, Path path, List<ContractFinding> findings) {
        Instant startInstant = parse(start, prefix + ".start_time", path, findings);
        Instant endInstant = parse(end, prefix + ".end_time", path, findings);
        if (startInstant != null && endInstant != null && endInstant.isBefore(startInstant)) {
            add(findings, path, prefix + ".end_time", "end_time must not precede start_time");
        }
        if (duration < 0) {
            add(findings, path, prefix + ".duration_ms", "duration_ms must be non-negative");
        }
    }

    private Instant parse(String value, String field, Path path, List<ContractFinding> findings) {
        try {
            Instant parsed = Instant.parse(value);
            if (!value.endsWith("Z")) throw new DateTimeParseException("not UTC", value, 0);
            return parsed;
        } catch (Exception exception) {
            add(findings, path, field, "Timestamp must be UTC RFC 3339");
            return null;
        }
    }

    private void validateCompleteness(String completeness, boolean unknown, BigDecimal passRate,
            BigDecimal completionRate, String field, Path path, List<ContractFinding> findings) {
        if (!("complete".equals(completeness) || "partial".equals(completeness))) {
            add(findings, path, field + ".count_completeness", "Count completeness must be complete or partial");
        }
        if ("complete".equals(completeness) && unknown) {
            add(findings, path, field + ".unknown_test_case_count", "Complete coverage requires false");
        }
        if ("partial".equals(completeness) && !unknown) {
            add(findings, path, field + ".unknown_test_case_count", "Partial coverage requires true");
        }
        if ("partial".equals(completeness) && passRate != null) {
            add(findings, path, field + ".pass_rate_percent", "Partial coverage requires null rates");
        }
        if ("partial".equals(completeness) && completionRate != null) {
            add(findings, path, field + ".completion_rate_percent", "Partial coverage requires null rates");
        }
    }

    private void validateRates(String completeness, int pass, int executed, int tests, BigDecimal passRate,
            BigDecimal completionRate, String field, Path path, List<ContractFinding> findings) {
        if (!"complete".equals(completeness)) return;
        compareRate(passRate, rate(pass, executed), field + ".pass_rate_percent", path, findings);
        compareRate(completionRate, rate(executed, tests), field + ".completion_rate_percent", path, findings);
    }

    private BigDecimal rate(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    private void compareRate(BigDecimal actual, BigDecimal expected, String field, Path path,
            List<ContractFinding> findings) {
        if (actual == null ? expected != null : expected == null || actual.compareTo(expected) != 0) {
            add(findings, path, field, "Rate must use the contract equation rounded half-up to one decimal");
        }
    }

    private void validateNonNegative(List<Integer> values, String field, Path path, List<ContractFinding> findings) {
        if (values.stream().anyMatch(value -> value < 0)) add(findings, path, field, "Counts must be non-negative");
    }

    private void checkSum(int total, int self, int child, String name, Path path, List<ContractFinding> findings) {
        if (total != self + child) add(findings, path, "total_summary." + name,
                "Total must equal self_summary plus child_aggregate_summary");
    }

    private void compare(int actual, int expected, String field, Path path, List<ContractFinding> findings) {
        if (actual != expected) add(findings, path, field, "Must equal the immediate-child authoritative sum");
    }

    private void require(List<ContractFinding> findings, Path path, String field, String value) {
        if (value == null || value.isBlank()) add(findings, path, field, "Required field is missing");
    }

    private boolean isStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    private void add(List<ContractFinding> findings, Path path, String field, String reason) {
        findings.add(finding(path, field, reason, "Correct the field and regenerate the suite summary."));
    }

    private ContractFinding finding(Path path, String field, String reason, String ownerAction) {
        return new ContractFinding(path == null ? "" : path.toString(), field, reason,
                "", "", "", "", ownerAction);
    }
}
