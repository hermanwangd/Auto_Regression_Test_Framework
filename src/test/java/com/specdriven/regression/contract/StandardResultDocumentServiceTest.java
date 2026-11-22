package com.specdriven.regression.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardResultDocumentServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StandardResultDocumentService service = new StandardResultDocumentService();

    @TempDir
    Path tempDir;

    @Test
    void atomicallyAugmentsLeafResultWithoutLosingExistingFields() throws Exception {
        Path resultJson = tempDir.resolve("run/result.json");
        Path summaryJson = tempDir.resolve("run/summaries/suite_summary.json");
        Files.createDirectories(resultJson.getParent());
        Files.createDirectories(summaryJson.getParent());
        Files.writeString(summaryJson, "{}\n");
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("suite_id", "SUITE-1");
        original.put("provider_results", List.of(Map.of("provider_id", "payments")));
        original.put("custom_extension", Map.of("preserved", true));
        mapper.writeValue(resultJson.toFile(), original);

        Path written = service.augmentLeafResult(resultJson, summaryJson, "complete", null);

        assertThat(written).isEqualTo(resultJson);
        Map<String, Object> result = read(resultJson);
        assertThat(result)
                .containsEntry("result_contract_version", "v0.3")
                .containsEntry("completion_status", "complete")
                .containsEntry("termination_reason", null)
                .containsEntry("suite_summary_ref", "summaries/suite_summary.json")
                .containsEntry("custom_extension", Map.of("preserved", true));
        assertThat(result.get("provider_results")).isEqualTo(original.get("provider_results"));
        try (var files = Files.list(resultJson.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("result.json", "summaries");
        }
    }

    @Test
    void rejectsSummarySymlinkThatEscapesResultDirectory() throws Exception {
        Path runDir = tempDir.resolve("run");
        Path resultJson = runDir.resolve("result.json");
        Path outsideSummary = tempDir.resolve("outside/suite_summary.json");
        Path summaryLink = runDir.resolve("suite_summary.json");
        Files.createDirectories(runDir);
        Files.createDirectories(outsideSummary.getParent());
        mapper.writeValue(resultJson.toFile(), Map.of("suite_id", "SUITE-1"));
        Files.writeString(outsideSummary, "{}\n");
        Files.createSymbolicLink(summaryLink, outsideSummary);

        assertThatThrownBy(() -> service.augmentLeafResult(resultJson, summaryLink, "complete", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("real path")
                .hasMessageContaining("result directory");
    }

    @Test
    void rejectsMissingResultOrSummaryBeforeAugmenting() throws Exception {
        Path runDir = tempDir.resolve("run");
        Path resultJson = runDir.resolve("result.json");
        Path summaryJson = runDir.resolve("suite_summary.json");
        Files.createDirectories(runDir);

        assertThatThrownBy(() -> service.augmentLeafResult(resultJson, summaryJson, "complete", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing result");

        mapper.writeValue(resultJson.toFile(), Map.of("suite_id", "SUITE-1"));
        assertThatThrownBy(() -> service.augmentLeafResult(resultJson, summaryJson, "complete", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing summary");
    }

    @Test
    void atomicallyWritesCompleteAggregationResultMap() throws Exception {
        Map<String, Object> completeResult = new LinkedHashMap<>();
        completeResult.put("result_contract_version", "v0.3");
        completeResult.put("suite_id", "PARENT");
        completeResult.put("test_results", List.of(Map.of(
                "suite_path", "child-a",
                "test_result_id", "child-a/TC-1",
                "test_case_id", "TC-1")));
        completeResult.put("provider_results", List.of(Map.of("suite_path", "child-a")));
        completeResult.put("steps", List.of(Map.of("suite_path", "child-a")));
        completeResult.put("verify_results", List.of(Map.of("suite_path", "child-a")));
        completeResult.put("evidence_refs", List.of("child-a/evidence.json"));
        completeResult.put("failure", Map.of("code", ""));

        Path resultJson = service.writeAggregationResult(tempDir.resolve("aggregate-run"), completeResult);

        assertThat(resultJson).isEqualTo(tempDir.resolve("aggregate-run/result.json"));
        assertThat(read(resultJson)).isEqualTo(completeResult);
        try (var files = Files.list(resultJson.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("result.json");
        }
    }

    @Test
    void fallsBackToReplaceMoveWhenAtomicMoveIsUnsupported() throws Exception {
        StandardResultDocumentService fallbackService = new StandardResultDocumentService(mapper,
                (source, target, options) -> {
                    if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported");
                    }
                    Files.move(source, target, options);
                });
        Map<String, Object> result = Map.of("suite_id", "PARENT");

        Path resultJson = fallbackService.writeAggregationResult(tempDir.resolve("fallback-run"), result);

        assertThat(read(resultJson)).isEqualTo(result);
        try (var files = Files.list(resultJson.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("result.json");
        }
    }

    @Test
    void cleansTemporaryFileWhenFallbackMoveFails() throws Exception {
        Path runDir = tempDir.resolve("failed-fallback-run");
        Path resultJson = runDir.resolve("result.json");
        Files.createDirectories(runDir);
        Files.writeString(resultJson, "original\n");
        StandardResultDocumentService failingService = new StandardResultDocumentService(mapper,
                (source, target, options) -> {
                    if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                        throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported");
                    }
                    throw new IOException("fallback failed");
                });

        assertThatThrownBy(() -> failingService.writeAggregationResult(runDir, Map.of("suite_id", "PARENT")))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("fallback failed");
        assertThat(Files.readString(resultJson)).isEqualTo("original\n");
        try (var files = Files.list(runDir)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("result.json");
        }
    }

    private Map<String, Object> read(Path path) throws Exception {
        return mapper.readValue(path.toFile(), new TypeReference<>() {});
    }
}
