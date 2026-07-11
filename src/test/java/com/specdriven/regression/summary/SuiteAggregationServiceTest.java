package com.specdriven.regression.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteAggregationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @TempDir Path tempDir;

    @Test
    void aggregatesImmediateChildrenIntoCanonicalResultSummaryAndEvidence() throws Exception {
        Path runDir = tempDir.resolve("parent");
        Files.createDirectories(runDir);
        Path manifest = tempDir.resolve("suite_manifest.yaml");
        Files.writeString(manifest, "manifest_version: v0.3\nsuite_id: PARENT\n");
        SuiteAggregationService.ChildArtifact one = child(runDir, "ONE", "RUN-ONE", "passed");
        SuiteAggregationService.ChildArtifact two = child(runDir, "TWO", "RUN-TWO", "skipped");

        SuiteAggregationService.AggregatedArtifacts artifacts = new SuiteAggregationService().aggregate(
                manifest, runDir, "PARENT", "BATCH-SHARED", "RUN-PARENT", "local",
                Instant.parse("2026-07-11T00:00:00Z"), Instant.parse("2026-07-11T00:00:02Z"),
                List.of(one, two));

        assertThat(artifacts.status()).isEqualTo("passed");
        assertThat(artifacts.completionStatus()).isEqualTo("complete");
        Map<String, Object> result = mapper.readValue(artifacts.resultJson().toFile(), new TypeReference<>() {});
        assertThat(result).containsEntry("result_contract_version", "v0.3")
                .containsEntry("test_case_id", "PARENT-AGGREGATE")
                .containsEntry("test_count", 2)
                .containsEntry("suite_summary_ref", "suite_summary.json");
        List<Map<String, Object>> tests = (List<Map<String, Object>>) result.get("test_results");
        assertThat(tests).extracting(test -> test.get("test_result_id"))
                .containsExactly("ONE::TC-ONE", "TWO::TC-TWO");
        Map<String, Object> summary = mapper.readValue(artifacts.summaryJson().toFile(), new TypeReference<>() {});
        assertThat((Map<String, Object>) summary.get("self_summary")).containsEntry("test_case_count", 0);
        assertThat((Map<String, Object>) summary.get("child_aggregate_summary"))
                .containsEntry("child_suite_count", 2)
                .containsEntry("test_case_count", 2);
        assertThat(Files.readString(artifacts.evidenceIndex()))
                .contains("ONE::log-ONE")
                .contains("TWO::log-TWO")
                .contains("aggregation::execution-log");
    }

    @Test
    void corruptExecutedChildProducesPartialBlockedAggregationWithoutDiscardingValidChild() throws Exception {
        Path runDir = tempDir.resolve("partial-parent");
        Files.createDirectories(runDir);
        Path manifest = tempDir.resolve("partial-suite.yaml");
        Files.writeString(manifest, "manifest_version: v0.3\nsuite_id: PARTIAL\n");
        SuiteAggregationService.ChildArtifact valid = child(runDir, "GOOD", "RUN-GOOD", "passed");
        Path corrupt = runDir.resolve("children/BAD/suite_summary.json");
        Files.createDirectories(corrupt.getParent());
        Files.writeString(corrupt, "{not-json");
        SuiteAggregationService.ChildArtifact bad =
                new SuiteAggregationService.ChildArtifact("BAD", "bad/suite.yaml", corrupt, corrupt);

        SuiteAggregationService.AggregatedArtifacts artifacts = new SuiteAggregationService().aggregate(
                manifest, runDir, "PARTIAL", "BATCH-SHARED", "RUN-PARENT", "local",
                Instant.parse("2026-07-11T00:00:00Z"), Instant.parse("2026-07-11T00:00:02Z"),
                List.of(valid, bad));

        assertThat(artifacts.status()).isEqualTo("blocked");
        assertThat(artifacts.completionStatus()).isEqualTo("partial");
        Map<String, Object> result = mapper.readValue(artifacts.resultJson().toFile(), new TypeReference<>() {});
        assertThat(result).containsEntry("test_count", 1)
                .containsEntry("termination_reason", "aggregation_error");
        Map<String, Object> summary = mapper.readValue(artifacts.summaryJson().toFile(), new TypeReference<>() {});
        assertThat((List<?>) summary.get("aggregation_errors")).hasSize(1);
        assertThat((Map<String, Object>) summary.get("total_summary"))
                .containsEntry("count_completeness", "partial")
                .containsEntry("pass_rate_percent", null);
    }

    private SuiteAggregationService.ChildArtifact child(
            Path parentRun, String id, String runId, String status) throws Exception {
        Path childDir = parentRun.resolve("children/" + id);
        Files.createDirectories(childDir);
        Path manifest = childDir.resolve("source.yaml");
        Files.writeString(manifest, "manifest_version: v0.3\nsuite_id: " + id + "\n");
        Files.writeString(childDir.resolve("log.txt"), "masked\n");
        Files.writeString(childDir.resolve("evidence_index.yaml"), """
                entries:
                  - evidence_id: log-%s
                    evidence_type: execution_log
                    produced_by: test
                    test_case_id: TC-%s
                    run_id: %s
                    batch_id: BATCH-SHARED
                    file_path: log.txt
                    content_type: text/plain
                    status: passed
                    created_at: "2026-07-11T00:00:01Z"
                    masking_applied: true
                    linked_result_field: evidence_refs
                """.formatted(id, id, runId));
        Path result = childDir.resolve("result.json");
        mapper.writeValue(result.toFile(), Map.ofEntries(
                Map.entry("framework_version", "0.3.0"), Map.entry("dsl_version", "v0.3"),
                Map.entry("suite_id", id), Map.entry("batch_id", "BATCH-SHARED"),
                Map.entry("run_id", runId), Map.entry("test_case_id", "TC-" + id),
                Map.entry("test_count", 1), Map.entry("status", status), Map.entry("profile", "local"),
                Map.entry("environment", "local"), Map.entry("start_time", "2026-07-11T00:00:00Z"),
                Map.entry("end_time", "2026-07-11T00:00:01Z"), Map.entry("duration_ms", 1000),
                Map.entry("timestamps", Map.of("started_at", "2026-07-11T00:00:00Z", "finished_at", "2026-07-11T00:00:01Z")),
                Map.entry("test_results", List.of(Map.of("test_case_id", "TC-" + id, "status", status, "profile", "local"))),
                Map.entry("provider_results", List.of()), Map.entry("steps", List.of()),
                Map.entry("verify_results", List.of()), Map.entry("evidence_refs", List.of("evidence://log-" + id)),
                Map.entry("evidence_index_ref", "evidence_index.yaml"), Map.entry("failure", Map.of())));
        SuiteArtifactFinalizer.FinalizedArtifacts finalized = new SuiteArtifactFinalizer().finalizeLeaf(manifest, result);
        return new SuiteAggregationService.ChildArtifact(id, id.toLowerCase() + "/suite.yaml", result, finalized.summaryJson());
    }
}
