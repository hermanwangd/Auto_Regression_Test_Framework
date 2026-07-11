package com.specdriven.regression.summary;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SuiteSummaryDocument(
        String suiteSummaryVersion,
        String suiteId,
        String batchId,
        String runId,
        String profile,
        String status,
        String completionStatus,
        String terminationReason,
        String startTime,
        String endTime,
        long durationMs,
        String generatedAt,
        String frameworkVersion,
        String dslVersion,
        String suiteManifestRef,
        String suiteManifestDigest,
        Counts selfSummary,
        ChildCounts childAggregateSummary,
        Counts totalSummary,
        FailureSummary failureSummary,
        EvidenceSummary evidenceSummary,
        List<AggregationError> aggregationErrors,
        List<ChildEntry> children) {

    public SuiteSummaryDocument {
        aggregationErrors = List.copyOf(aggregationErrors);
        children = List.copyOf(children);
    }

    public record Counts(
            String countCompleteness,
            boolean unknownTestCaseCount,
            int testCaseCount,
            int executedCount,
            int passCount,
            int failCount,
            int blockedCount,
            int skippedCount,
            BigDecimal passRatePercent,
            BigDecimal completionRatePercent) {
    }

    public record ChildCounts(
            String countCompleteness,
            boolean unknownTestCaseCount,
            int childSuiteCount,
            int completedChildSuiteCount,
            int blockedChildSuiteCount,
            int skippedChildSuiteCount,
            int erroredChildSuiteCount,
            int testCaseCount,
            int executedCount,
            int passCount,
            int failCount,
            int blockedCount,
            int skippedCount,
            BigDecimal passRatePercent,
            BigDecimal completionRatePercent) {
    }

    public record ChildEntry(
            String childSuiteId,
            String ref,
            String batchId,
            String runId,
            String status,
            String startTime,
            String endTime,
            long durationMs,
            String summaryRef,
            Counts totalSummary) {
    }

    public record FailureSummary(
            int testFailureCount,
            int testBlockedCount,
            int aggregationErrorCount,
            int totalIssueCount,
            Map<String, Integer> byCategory,
            List<FailedTestRef> failedTestRefs,
            List<FailedChildRef> failedChildRefs) {
        public FailureSummary {
            byCategory = Map.copyOf(byCategory);
            failedTestRefs = List.copyOf(failedTestRefs);
            failedChildRefs = List.copyOf(failedChildRefs);
        }
    }

    public record FailedTestRef(
            String testCaseId,
            String finalStatus,
            String failureCode,
            String category,
            String resultRef,
            List<String> evidenceRefs) {
        public FailedTestRef {
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    public record FailedChildRef(String childSuiteId, String finalStatus, String summaryRef) {
    }

    public record EvidenceSummary(int evidenceCount, boolean maskingApplied, String evidenceIndexRef) {
    }

    public record AggregationError(String childSuiteId, String artifactRef, String failureCode, String ownerAction) {
    }
}
