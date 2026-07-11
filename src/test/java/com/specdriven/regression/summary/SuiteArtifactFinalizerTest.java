package com.specdriven.regression.summary;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteArtifactFinalizerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void finalizesCompleteV03LeafWithMatchingIdentityAndCounts() throws Exception {
        Path manifest = tempDir.resolve("source/suite_manifest.yaml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "manifest_version: v0.3\nsuite_id: LEAF-001\n");
        Path runDir = tempDir.resolve("runs/RUN-001");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("evidence_index.yaml"), """
                entries:
                  - evidence_id: log-1
                    masking_applied: true
                """);
        Path result = runDir.resolve("result.json");
        mapper.writeValue(result.toFile(), Map.ofEntries(
                Map.entry("framework_version", "0.3.0"),
                Map.entry("dsl_version", "v0.3"),
                Map.entry("suite_id", "LEAF-001"),
                Map.entry("batch_id", "BATCH-001"),
                Map.entry("run_id", "RUN-001"),
                Map.entry("test_case_id", "LEAF-001-MULTI"),
                Map.entry("test_count", 2),
                Map.entry("status", "passed"),
                Map.entry("profile", "local"),
                Map.entry("start_time", "2026-07-11T00:00:00Z"),
                Map.entry("end_time", "2026-07-11T00:00:01Z"),
                Map.entry("test_results", List.of(
                        Map.of("test_case_id", "TC-1", "status", "passed", "profile", "local"),
                        Map.of("test_case_id", "TC-2", "status", "skipped", "profile", "local"))),
                Map.entry("evidence_index_ref", "evidence_index.yaml"),
                Map.entry("evidence_refs", List.of("evidence://log-1")),
                Map.entry("failure", Map.of())));

        SuiteArtifactFinalizer.FinalizedArtifacts finalized =
                new SuiteArtifactFinalizer().finalizeLeaf(manifest, result);

        assertThat(finalized.v03()).isTrue();
        assertThat(finalized.summaryJson()).isRegularFile();
        Map<String, Object> resultDocument = mapper.readValue(result.toFile(), new TypeReference<>() {});
        assertThat(resultDocument)
                .containsEntry("result_contract_version", "v0.3")
                .containsEntry("completion_status", "complete")
                .containsEntry("termination_reason", null)
                .containsEntry("suite_summary_ref", "suite_summary.json");
        Map<String, Object> summary = mapper.readValue(finalized.summaryJson().toFile(), new TypeReference<>() {});
        assertThat(summary)
                .containsEntry("suite_id", "LEAF-001")
                .containsEntry("batch_id", "BATCH-001")
                .containsEntry("run_id", "RUN-001")
                .containsEntry("status", "passed");
        assertThat((Map<String, Object>) summary.get("self_summary"))
                .containsEntry("test_case_count", 2)
                .containsEntry("pass_count", 1)
                .containsEntry("skipped_count", 1);
        assertThat((Map<String, Object>) summary.get("total_summary"))
                .containsEntry("test_case_count", 2)
                .containsEntry("pass_count", 1)
                .containsEntry("skipped_count", 1);
        assertThat(runDir.resolve("suite_manifest.yaml")).hasSameTextualContentAs(manifest);
        ObjectMapper summaryMapper = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        SuiteSummaryDocument canonical = summaryMapper.readValue(
                finalized.summaryJson().toFile(), SuiteSummaryDocument.class);
        assertThat(new SuiteSummaryValidator().validate(canonical, runDir)).isEmpty();
    }

    @Test
    void leavesLegacyResultUnversionedAndDoesNotWriteSummary() throws Exception {
        Path manifest = tempDir.resolve("suite.yaml");
        Files.writeString(manifest, "manifest_version: v0.2\n");
        Path runDir = tempDir.resolve("legacy");
        Files.createDirectories(runDir);
        Path result = runDir.resolve("result.json");
        mapper.writeValue(result.toFile(), Map.of("dsl_version", "v0.2"));

        SuiteArtifactFinalizer.FinalizedArtifacts finalized =
                new SuiteArtifactFinalizer().finalizeLeaf(manifest, result);

        assertThat(finalized.v03()).isFalse();
        assertThat(finalized.summaryJson()).isNull();
        assertThat(Files.readString(result)).doesNotContain("result_contract_version");
    }

    @Test
    void recoverableFinalizationFailureProducesBlockedPartialArtifacts() throws Exception {
        Path manifest = tempDir.resolve("recovery-source/suite_manifest.yaml");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "manifest_version: v0.3\nsuite_id: RECOVERY-LEAF\n");
        Path runDir = tempDir.resolve("recovery-run");
        Files.createDirectories(runDir);
        Path result = runDir.resolve("result.json");
        mapper.writeValue(result.toFile(), Map.ofEntries(
                Map.entry("framework_version", "0.3.0"), Map.entry("dsl_version", "v0.3"),
                Map.entry("suite_id", "RECOVERY-LEAF"), Map.entry("batch_id", "BATCH-R"),
                Map.entry("run_id", "RUN-R"), Map.entry("test_case_id", "TC-R"),
                Map.entry("test_count", 1), Map.entry("status", "passed"),
                Map.entry("profile", "local"), Map.entry("environment", "local"),
                Map.entry("start_time", "2026-07-11T00:00:00Z"),
                Map.entry("end_time", "2026-07-11T00:00:01Z"), Map.entry("duration_ms", 1000),
                Map.entry("timestamps", Map.of("started_at", "2026-07-11T00:00:00Z",
                        "finished_at", "2026-07-11T00:00:01Z")),
                Map.entry("test_results", List.of(Map.of(
                        "test_case_id", "TC-R", "status", "passed", "profile", "local"))),
                Map.entry("provider_results", List.of()), Map.entry("steps", List.of()),
                Map.entry("verify_results", List.of()), Map.entry("evidence_refs", List.of()),
                Map.entry("evidence_index_ref", "missing-index.yaml"), Map.entry("failure", Map.of())));

        SuiteArtifactFinalizer.FinalizedArtifacts finalized =
                new SuiteArtifactFinalizer().finalizeLeaf(manifest, result);

        Map<String, Object> recovered = mapper.readValue(result.toFile(), new TypeReference<>() {});
        assertThat(recovered)
                .containsEntry("status", "blocked")
                .containsEntry("result_contract_version", "v0.3")
                .containsEntry("completion_status", "partial")
                .containsEntry("termination_reason", "framework_error")
                .containsEntry("suite_summary_ref", "suite_summary.json");
        Map<String, Object> summary = mapper.readValue(finalized.summaryJson().toFile(), new TypeReference<>() {});
        assertThat(summary)
                .containsEntry("suite_id", "RECOVERY-LEAF")
                .containsEntry("batch_id", "BATCH-R")
                .containsEntry("run_id", "RUN-R")
                .containsEntry("status", "blocked")
                .containsEntry("completion_status", "partial")
                .containsEntry("termination_reason", "framework_error");
        assertThat((Map<String, Object>) summary.get("evidence_summary"))
                .containsEntry("evidence_count", 1)
                .containsEntry("masking_applied", true);
        ObjectMapper summaryMapper = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        SuiteSummaryDocument canonical = summaryMapper.readValue(
                finalized.summaryJson().toFile(), SuiteSummaryDocument.class);
        assertThat(new SuiteSummaryValidator().validate(canonical, runDir)).isEmpty();
        assertThat(runDir.resolve("finalization_evidence_index.yaml")).isRegularFile();
        assertThat(runDir.resolve("suite_finalization_error.json")).isRegularFile();
    }
}
