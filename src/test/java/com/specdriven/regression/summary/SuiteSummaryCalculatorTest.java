package com.specdriven.regression.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuiteSummaryCalculatorTest {

    private final SuiteSummaryCalculator calculator = new SuiteSummaryCalculator();

    @Test
    void appliesStatusPrecedenceAndAllSkippedRule() {
        assertThat(calculator.statusOf(List.of("passed", "skipped"), "complete")).isEqualTo("passed");
        assertThat(calculator.statusOf(List.of("failed", "passed"), "complete")).isEqualTo("failed");
        assertThat(calculator.statusOf(List.of("blocked", "failed"), "complete")).isEqualTo("blocked");
        assertThat(calculator.statusOf(List.of("skipped", "skipped"), "complete")).isEqualTo("skipped");
        assertThat(calculator.statusOf(List.of("passed"), "partial")).isEqualTo("blocked");
    }

    @Test
    void calculatesExplicitCountsAndHalfUpRates() {
        SuiteSummaryDocument.Counts counts = calculator.counts(
                List.of("passed", "passed", "failed", "blocked", "skipped", "skipped"), "complete");

        assertThat(counts.testCaseCount()).isEqualTo(6);
        assertThat(counts.executedCount()).isEqualTo(3);
        assertThat(counts.passCount()).isEqualTo(2);
        assertThat(counts.failCount()).isEqualTo(1);
        assertThat(counts.blockedCount()).isEqualTo(1);
        assertThat(counts.skippedCount()).isEqualTo(2);
        assertThat(counts.passRatePercent()).isEqualByComparingTo("66.7");
        assertThat(counts.completionRatePercent()).isEqualByComparingTo("50.0");
    }

    @Test
    void returnsNullRatesForZeroDenominatorsAndPartialCoverage() {
        SuiteSummaryDocument.Counts empty = calculator.counts(List.of(), "complete");
        SuiteSummaryDocument.Counts partial = calculator.counts(List.of("passed"), "partial");

        assertThat(empty.passRatePercent()).isNull();
        assertThat(empty.completionRatePercent()).isNull();
        assertThat(partial.unknownTestCaseCount()).isTrue();
        assertThat(partial.passRatePercent()).isNull();
        assertThat(partial.completionRatePercent()).isNull();
    }

    @Test
    void aggregatesImmediateChildSnapshotsAndMarksErroredCoveragePartial() {
        SuiteSummaryDocument.Counts childOne = calculator.counts(List.of("passed", "skipped"), "complete");
        SuiteSummaryDocument.Counts childTwo = calculator.counts(List.of("failed"), "complete");

        SuiteSummaryDocument.ChildCounts aggregate = calculator.childCounts(List.of(
                child("ONE", "passed", childOne), child("TWO", "failed", childTwo)), 1);

        assertThat(aggregate.childSuiteCount()).isEqualTo(3);
        assertThat(aggregate.completedChildSuiteCount()).isEqualTo(2);
        assertThat(aggregate.erroredChildSuiteCount()).isEqualTo(1);
        assertThat(aggregate.countCompleteness()).isEqualTo("partial");
        assertThat(aggregate.testCaseCount()).isEqualTo(3);
        assertThat(aggregate.passRatePercent()).isNull();
    }

    @Test
    void totalsSelfAndChildCountsFieldByField() {
        SuiteSummaryDocument.Counts self = calculator.counts(List.of("passed"), "complete");
        SuiteSummaryDocument.ChildCounts children = calculator.childCounts(
                List.of(child("ONE", "skipped", calculator.counts(List.of("skipped"), "complete"))), 0);

        SuiteSummaryDocument.Counts total = calculator.total(self, children);

        assertThat(total.testCaseCount()).isEqualTo(2);
        assertThat(total.passCount()).isEqualTo(1);
        assertThat(total.skippedCount()).isEqualTo(1);
        assertThat(total.passRatePercent()).isEqualByComparingTo(new BigDecimal("100.0"));
        assertThat(total.completionRatePercent()).isEqualByComparingTo(new BigDecimal("50.0"));
    }

    private SuiteSummaryDocument.ChildEntry child(
            String id, String status, SuiteSummaryDocument.Counts total) {
        return new SuiteSummaryDocument.ChildEntry(
                id, id.toLowerCase() + "/suite_manifest.yaml", "BATCH", "RUN-" + id, status,
                "2026-07-11T09:00:00Z", "2026-07-11T09:00:01Z", 1000,
                "children/" + id + "/suite_summary.json", total);
    }
}
