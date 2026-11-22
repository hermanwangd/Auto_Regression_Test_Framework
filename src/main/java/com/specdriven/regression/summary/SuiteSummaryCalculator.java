package com.specdriven.regression.summary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class SuiteSummaryCalculator {

    public String statusOf(List<String> statuses, String completionStatus) {
        if ("partial".equals(completionStatus) || statuses.contains("blocked")) {
            return "blocked";
        }
        if (statuses.contains("failed")) {
            return "failed";
        }
        if (statuses.contains("passed")) {
            return "passed";
        }
        return "skipped";
    }

    public SuiteSummaryDocument.Counts counts(List<String> statuses, String countCompleteness) {
        int passed = frequency(statuses, "passed");
        int failed = frequency(statuses, "failed");
        int blocked = frequency(statuses, "blocked");
        int skipped = frequency(statuses, "skipped");
        return counts(countCompleteness, statuses.size(), passed + failed, passed, failed, blocked, skipped);
    }

    public SuiteSummaryDocument.ChildCounts childCounts(
            List<SuiteSummaryDocument.ChildEntry> children, int erroredChildSuiteCount) {
        int passed = 0;
        int failed = 0;
        int blocked = 0;
        int skipped = 0;
        int completedChildren = 0;
        int blockedChildren = 0;
        int skippedChildren = 0;
        int testCases = 0;
        for (SuiteSummaryDocument.ChildEntry child : children) {
            SuiteSummaryDocument.Counts counts = child.totalSummary();
            passed += counts.passCount();
            failed += counts.failCount();
            blocked += counts.blockedCount();
            skipped += counts.skippedCount();
            testCases += counts.testCaseCount();
            if ("passed".equals(child.status()) || "failed".equals(child.status())) {
                completedChildren++;
            } else if ("blocked".equals(child.status())) {
                blockedChildren++;
            } else if ("skipped".equals(child.status())) {
                skippedChildren++;
            }
        }
        boolean partial = erroredChildSuiteCount > 0
                || children.stream().anyMatch(child -> "partial".equals(child.totalSummary().countCompleteness()));
        String completeness = partial ? "partial" : "complete";
        SuiteSummaryDocument.Counts totals = counts(
                completeness, testCases, passed + failed, passed, failed, blocked, skipped);
        return new SuiteSummaryDocument.ChildCounts(
                completeness, partial, children.size() + erroredChildSuiteCount, completedChildren,
                blockedChildren, skippedChildren, erroredChildSuiteCount, totals.testCaseCount(),
                totals.executedCount(), totals.passCount(), totals.failCount(), totals.blockedCount(),
                totals.skippedCount(), totals.passRatePercent(), totals.completionRatePercent());
    }

    public SuiteSummaryDocument.Counts total(
            SuiteSummaryDocument.Counts self, SuiteSummaryDocument.ChildCounts children) {
        String completeness = "complete".equals(self.countCompleteness())
                        && "complete".equals(children.countCompleteness())
                ? "complete" : "partial";
        return counts(completeness,
                self.testCaseCount() + children.testCaseCount(),
                self.executedCount() + children.executedCount(),
                self.passCount() + children.passCount(),
                self.failCount() + children.failCount(),
                self.blockedCount() + children.blockedCount(),
                self.skippedCount() + children.skippedCount());
    }

    private SuiteSummaryDocument.Counts counts(
            String completeness, int tests, int executed, int passed, int failed, int blocked, int skipped) {
        boolean partial = "partial".equals(completeness);
        return new SuiteSummaryDocument.Counts(
                completeness, partial, tests, executed, passed, failed, blocked, skipped,
                partial ? null : rate(passed, executed), partial ? null : rate(executed, tests));
    }

    private BigDecimal rate(int numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    private int frequency(List<String> statuses, String status) {
        return (int) statuses.stream().filter(status::equals).count();
    }
}
