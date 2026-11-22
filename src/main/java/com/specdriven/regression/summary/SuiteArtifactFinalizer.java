package com.specdriven.regression.summary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.contract.StandardResultDocumentService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Produces the canonical summary/result pair for a completed v0.3 leaf run. */
public final class SuiteArtifactFinalizer {
    private static final String MANIFEST_SNAPSHOT = "suite_manifest.yaml";
    private static final String RESULT_REF = "result.json";

    private final ObjectMapper mapper;
    private final SuiteSummaryCalculator calculator;
    private final SuiteSummaryWriter summaryWriter;
    private final StandardResultDocumentService resultService;

    public SuiteArtifactFinalizer() {
        this(new ObjectMapper().findAndRegisterModules(), new SuiteSummaryCalculator(),
                new SuiteSummaryWriter(), new StandardResultDocumentService());
    }

    SuiteArtifactFinalizer(
            ObjectMapper mapper,
            SuiteSummaryCalculator calculator,
            SuiteSummaryWriter summaryWriter,
            StandardResultDocumentService resultService) {
        this.mapper = mapper;
        this.calculator = calculator;
        this.summaryWriter = summaryWriter;
        this.resultService = resultService;
    }

    public FinalizedArtifacts finalizeLeaf(Path suiteManifest, Path resultJson) {
        Map<String, Object> document = null;
        Path manifest = null;
        Path runDirectory = null;
        try {
            Path result = resultJson.toAbsolutePath().normalize().toRealPath();
            runDirectory = result.getParent();
            if (runDirectory == null) {
                throw new IllegalArgumentException("result.json must have a containing run directory");
            }
            document = mapper.readValue(result.toFile(), new TypeReference<>() {});
            if (!"v0.3".equals(text(document.get("dsl_version")))) {
                return new FinalizedArtifacts(resultJson, null, false);
            }

            manifest = suiteManifest.toAbsolutePath().normalize().toRealPath();
            Path manifestSnapshot = runDirectory.resolve(MANIFEST_SNAPSHOT);
            Files.copy(manifest, manifestSnapshot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            SuiteSummaryDocument summary = buildSummary(
                    document, manifest, runDirectory, MANIFEST_SNAPSHOT, "complete", null, null);
            Path summaryJson = summaryWriter.write(runDirectory, summary, false);
            resultService.augmentLeafResult(result, summaryJson, "complete", null);
            return new FinalizedArtifacts(resultJson, summaryJson, true);
        } catch (Exception error) {
            if (document != null && manifest != null && runDirectory != null) {
                return recoverPartial(document, manifest, runDirectory, resultJson, error);
            }
            if (error instanceof IOException io) {
                throw new UncheckedIOException("Cannot finalize v0.3 leaf artifacts", io);
            }
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Cannot finalize v0.3 leaf artifacts", error);
        }
    }

    private FinalizedArtifacts recoverPartial(
            Map<String, Object> original,
            Path manifest,
            Path runDirectory,
            Path resultJson,
            Exception cause) {
        try {
            String evidenceId = "suite-finalization-error";
            Path evidenceFile = runDirectory.resolve("suite_finalization_error.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(evidenceFile.toFile(), Map.of(
                    "failure_code", "SUITE_ARTIFACT_FINALIZATION_FAILED",
                    "message", "Canonical leaf finalization failed; inspect and correct the run artifacts."));
            String now = Instant.now().toString();
            String index = """
                    evidence_index_version: v0.3
                    suite_id: %s
                    batch_id: %s
                    run_id: %s
                    test_case_id: %s
                    profile: %s
                    entries:
                      - evidence_id: %s
                        evidence_type: execution_log
                        produced_by: framework
                        test_case_id: %s
                        run_id: %s
                        batch_id: %s
                        file_path: suite_finalization_error.json
                        content_type: application/json
                        status: failed
                        created_at: "%s"
                        masking_applied: true
                        linked_result_field: evidence_refs
                        failure_code: SUITE_ARTIFACT_FINALIZATION_FAILED
                    """.formatted(
                    text(original.get("suite_id")), text(original.get("batch_id")), text(original.get("run_id")),
                    text(original.get("test_case_id")), text(original.get("profile")), evidenceId,
                    text(original.get("test_case_id")), text(original.get("run_id")),
                    text(original.get("batch_id")), now);
            Files.writeString(runDirectory.resolve("finalization_evidence_index.yaml"), index);
            Files.copy(manifest, runDirectory.resolve(MANIFEST_SNAPSHOT),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> partial = new LinkedHashMap<>(original);
            partial.put("status", "blocked");
            partial.put("evidence_index_ref", "finalization_evidence_index.yaml");
            partial.put("evidence_refs", List.of("evidence://" + evidenceId));
            partial.put("failure", Map.of(
                    "code", "SUITE_ARTIFACT_FINALIZATION_FAILED",
                    "category", "framework_error",
                    "reason", "Canonical leaf finalization failed",
                    "owner_action", "Inspect suite_finalization_error.json and correct the malformed run artifact."));
            resultService.writeAggregationResult(runDirectory, partial);
            SuiteSummaryDocument summary = buildSummary(
                    partial, manifest, runDirectory, MANIFEST_SNAPSHOT,
                    "partial", "framework_error", "finalization_evidence_index.yaml");
            Path summaryJson = summaryWriter.write(runDirectory, summary, false);
            resultService.augmentLeafResult(
                    runDirectory.resolve("result.json"), summaryJson, "partial", "framework_error");
            return new FinalizedArtifacts(resultJson, summaryJson, true);
        } catch (IOException recoveryFailure) {
            throw new UncheckedIOException("Cannot persist recoverable v0.3 finalization failure", recoveryFailure);
        }
    }

    private SuiteSummaryDocument buildSummary(
            Map<String, Object> result,
            Path originalManifest,
            Path runDirectory,
            String manifestRef,
            String completionStatus,
            String terminationReason,
            String evidenceIndexOverride) throws IOException {
        List<Map<String, Object>> tests = maps(result.get("test_results"));
        List<String> statuses = tests.stream().map(test -> text(test.get("status"))).toList();
        String completeness = "partial".equals(completionStatus) ? "partial" : "complete";
        SuiteSummaryDocument.Counts self = calculator.counts(statuses, completeness);
        SuiteSummaryDocument.ChildCounts child = new SuiteSummaryDocument.ChildCounts(
                completeness, "partial".equals(completeness), 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, null, null);
        SuiteSummaryDocument.Counts total = calculator.total(self, child);
        String status = calculator.statusOf(statuses, completionStatus);
        Instant started = instant(result.get("start_time"), "start_time");
        Instant ended = instant(result.get("end_time"), "end_time");
        Instant generated = Instant.now();
        if (generated.isBefore(ended)) {
            generated = ended;
        }
        List<String> evidenceRefs = strings(result.get("evidence_refs"));
        Map<String, Object> failure = map(result.get("failure"));
        String failureCode = text(failure.get("code"));
        String category = text(failure.get("category"));
        if (category.isBlank()) {
            category = text(failure.get("failure_classification"));
        }
        if (category.isBlank() && (!failureCode.isBlank())) {
            category = "framework_error";
        }
        List<SuiteSummaryDocument.FailedTestRef> failedRefs = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> test : tests) {
            String testStatus = text(test.get("status"));
            if (!("failed".equals(testStatus) || "blocked".equals(testStatus))) {
                continue;
            }
            String refCategory = category.isBlank()
                    ? ("blocked".equals(testStatus) ? "contract_error" : "verification_failed") : category;
            String refCode = failureCode.isBlank()
                    ? ("blocked".equals(testStatus) ? "SUITE_TEST_BLOCKED" : "SUITE_TEST_FAILED") : failureCode;
            byCategory.merge(refCategory, 1, Integer::sum);
            failedRefs.add(new SuiteSummaryDocument.FailedTestRef(
                    text(test.get("test_case_id")), testStatus, refCode, refCategory, RESULT_REF, evidenceRefs));
        }
        int issues = self.failCount() + self.blockedCount();
        SuiteSummaryDocument.FailureSummary failureSummary = new SuiteSummaryDocument.FailureSummary(
                self.failCount(), self.blockedCount(), 0, issues, byCategory, failedRefs, List.of());
        EvidenceMetadata evidence = evidenceMetadata(result, runDirectory, evidenceIndexOverride);

        return new SuiteSummaryDocument(
                "v0.3",
                text(result.get("suite_id")),
                text(result.get("batch_id")),
                text(result.get("run_id")),
                text(result.get("profile")),
                status,
                completionStatus,
                terminationReason,
                started.toString(),
                ended.toString(),
                Math.max(0, ended.toEpochMilli() - started.toEpochMilli()),
                generated.toString(),
                text(result.get("framework_version")),
                text(result.get("dsl_version")),
                manifestRef,
                "sha256:" + sha256(originalManifest),
                self,
                child,
                total,
                failureSummary,
                new SuiteSummaryDocument.EvidenceSummary(
                        evidence.count(), evidence.maskingApplied(), evidence.indexRef()),
                List.of(),
                List.of());
    }

    private EvidenceMetadata evidenceMetadata(
            Map<String, Object> result, Path runDirectory, String override) throws IOException {
        String ref = override == null ? text(result.get("evidence_index_ref")) : override;
        if (ref.isBlank()) {
            ref = "evidence_index.yaml";
        }
        Path relative = Path.of(ref);
        if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith("..")) {
            throw new IllegalArgumentException("evidence_index_ref must be a normalized contained relative path");
        }
        Path index = runDirectory.resolve(relative).normalize();
        if (!index.toRealPath().startsWith(runDirectory.toRealPath())) {
            throw new IllegalArgumentException("evidence_index_ref resolves outside the run directory");
        }
        Object loaded = new Yaml().load(Files.readString(index));
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("entries") instanceof List<?> entries)) {
            throw new IllegalArgumentException("Evidence index must contain an entries array");
        }
        boolean masked = entries.stream().allMatch(entry -> entry instanceof Map<?, ?> map
                && Boolean.TRUE.equals(map.get("masking_applied")));
        return new EvidenceMetadata(ref.replace('\\', '/'), entries.size(), masked);
    }

    private String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Instant instant(Object value, String field) {
        try {
            return Instant.parse(text(value));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Result field " + field + " must be an RFC3339 instant", error);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Result field test_results must be an array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Each test_results entry must be an object");
            }
            result.add((Map<String, Object>) map);
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(this::text).filter(text -> !text.isBlank()).toList();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public record FinalizedArtifacts(Path resultJson, Path summaryJson, boolean v03) {
    }

    private record EvidenceMetadata(String indexRef, int count, boolean maskingApplied) {
    }
}
