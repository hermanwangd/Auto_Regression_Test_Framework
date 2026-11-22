package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegacySuiteSummaryReportAdapterTest {
    private final LegacySuiteSummaryReportAdapter adapter = new LegacySuiteSummaryReportAdapter();

    @Test
    void adaptsOnlyUnversionedLegacySuiteSummary() {
        Map<String, Object> legacy = Map.of(
                "suite_id", "LEGACY", "batch_id", "BATCH-1", "run_id", "RUN-1",
                "profile", "local", "status", "passed", "test_count", 1,
                "children", List.of(Map.of("id", "child")));

        assertThat(adapter.supports(legacy)).isTrue();
        assertThat(adapter.adapt(legacy))
                .containsEntry("suite_id", "LEGACY")
                .containsEntry("status", "passed")
                .containsEntry("dsl_version", "v0.2");
    }

    @Test
    void doesNotMisclassifyStandardLegacyResultAndRejectsMalformedSummary() {
        assertThat(adapter.supports(Map.of(
                "suite_id", "RESULT", "test_count", 1, "provider_results", List.of())))
                .isFalse();
        assertThatThrownBy(() -> adapter.adapt(Map.of(
                "suite_id", "BAD", "status", "unknown", "children", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported status");
    }
}
