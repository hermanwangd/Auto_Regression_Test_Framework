package com.specdriven.regression.summary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.specdriven.regression.contract.StandardResultDocumentService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Builds the canonical result, summary, and merged evidence index for an aggregation suite. */
public final class SuiteAggregationService {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final SuiteSummaryCalculator calculator = new SuiteSummaryCalculator();
    private final SuiteSummaryValidator validator = new SuiteSummaryValidator();
    private final SuiteSummaryWriter summaryWriter = new SuiteSummaryWriter();
    private final StandardResultDocumentService resultService = new StandardResultDocumentService();

    public AggregatedArtifacts aggregate(
            Path suiteManifest,
            Path runDirectory,
            String suiteId,
            String batchId,
            String runId,
            String profile,
            Instant startedAt,
            Instant endedAt,
            List<ChildArtifact> childArtifacts) {
        try {
            Files.createDirectories(runDirectory);
            Path realRun = runDirectory.toRealPath();
            Path manifest = suiteManifest.toRealPath();
            Files.copy(manifest, realRun.resolve("suite_manifest.yaml"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            List<ResolvedChild> children = new ArrayList<>();
            List<SuiteSummaryDocument.AggregationError> errors = new ArrayList<>();
            Set<String> runIds = new HashSet<>();
            for (ChildArtifact child : childArtifacts) {
                try {
                    ResolvedChild resolved = resolveChild(realRun, batchId, child);
                    if (!runIds.add(resolved.summary().runId())) {
                        throw new IllegalArgumentException("duplicate child run_id: " + resolved.summary().runId());
                    }
                    children.add(resolved);
                } catch (RuntimeException | IOException error) {
                    errors.add(new SuiteSummaryDocument.AggregationError(
                            child.id(), artifactRef(realRun, child.summaryJson()),
                            "AGGREGATION_CHILD_ARTIFACT_INVALID",
                            "Repair or rerun child suite `" + child.id() + "`: " + safeMessage(error)));
                }
            }

            EvidenceMerge evidence = mergeEvidence(realRun, suiteId, batchId, runId, profile, children);
            List<Map<String, Object>> tests = copyEntries(children, "test_results", "test_result_id", evidence.refMap());
            List<Map<String, Object>> providers = copyEntries(children, "provider_results", null, evidence.refMap());
            List<Map<String, Object>> steps = copyEntries(children, "steps", null, evidence.refMap());
            List<Map<String, Object>> verifies = copyEntries(children, "verify_results", null, evidence.refMap());

            String completion = errors.isEmpty() ? "complete" : "partial";
            List<SuiteSummaryDocument.ChildEntry> childEntries = children.stream()
                    .map(child -> childEntry(realRun, child)).toList();
            SuiteSummaryDocument.Counts self = calculator.counts(List.of(), "complete");
            SuiteSummaryDocument.ChildCounts childCounts = calculator.childCounts(childEntries, errors.size());
            SuiteSummaryDocument.Counts total = calculator.total(self, childCounts);
            String status = calculator.statusOf(childEntries.stream().map(SuiteSummaryDocument.ChildEntry::status).toList(), completion);
            SuiteSummaryDocument.FailureSummary failureSummary = failureSummary(realRun, children, errors);
            Instant generatedAt = Instant.now().isBefore(endedAt) ? endedAt : Instant.now();
            String frameworkVersion = children.isEmpty()
                    ? "0.3.1" : text(children.get(0).result().get("framework_version"));
            SuiteSummaryDocument summary = new SuiteSummaryDocument(
                    "v0.3", suiteId, batchId, runId, profile, status, completion,
                    errors.isEmpty() ? null : "aggregation_error",
                    startedAt.toString(), endedAt.toString(),
                    Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli()),
                    generatedAt.toString(), frameworkVersion, "v0.3", "suite_manifest.yaml",
                    "sha256:" + sha256(manifest), self, childCounts, total, failureSummary,
                    new SuiteSummaryDocument.EvidenceSummary(
                            evidence.entryCount(), evidence.maskingApplied(), "evidence_index.yaml"),
                    errors, childEntries);
            List<?> summaryFindings = validator.validate(summary, realRun);
            if (!summaryFindings.isEmpty()) {
                throw new IllegalStateException("Generated aggregation summary is invalid: " + summaryFindings);
            }
            Path summaryJson = summaryWriter.write(realRun, summary, true);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result_contract_version", "v0.3");
            result.put("framework_version", frameworkVersion);
            result.put("dsl_version", "v0.3");
            result.put("suite_id", suiteId);
            result.put("batch_id", batchId);
            result.put("run_id", runId);
            result.put("test_case_id", suiteId + "-AGGREGATE");
            result.put("test_count", tests.size());
            result.put("status", status);
            result.put("profile", profile);
            result.put("environment", profile);
            result.put("start_time", startedAt.toString());
            result.put("end_time", endedAt.toString());
            result.put("duration_ms", Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli()));
            result.put("timestamps", Map.of("started_at", startedAt.toString(), "finished_at", endedAt.toString()));
            result.put("test_results", tests);
            result.put("provider_results", providers);
            result.put("provider_summary", providerSummary(providers));
            result.put("steps", steps);
            result.put("verify_results", verifies);
            result.put("evidence_refs", evidence.refs());
            result.put("evidence_index_ref", "evidence_index.yaml");
            result.put("failure", "passed".equals(status) ? null : aggregateFailure(errors));
            result.put("completion_status", completion);
            result.put("termination_reason", errors.isEmpty() ? null : "aggregation_error");
            result.put("suite_summary_ref", "suite_summary.json");
            Path resultJson = resultService.writeAggregationResult(realRun, result);
            return new AggregatedArtifacts(resultJson, summaryJson, realRun.resolve("suite_summary.yaml"),
                    realRun.resolve("evidence_index.yaml"), status, completion);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot aggregate suite artifacts", error);
        }
    }

    private ResolvedChild resolveChild(Path runRoot, String batchId, ChildArtifact child) throws IOException {
        Path resultPath = contained(runRoot, child.resultJson(), "child result");
        Path summaryPath = contained(runRoot, child.summaryJson(), "child summary");
        Map<String, Object> result = mapper.readValue(resultPath.toFile(), new TypeReference<>() {});
        SuiteSummaryDocument summary = mapper.readValue(summaryPath.toFile(), SuiteSummaryDocument.class);
        if (!validator.validate(summary, summaryPath.getParent()).isEmpty()) {
            throw new IllegalArgumentException("child summary contract validation failed");
        }
        if (!batchId.equals(summary.batchId()) || !batchId.equals(text(result.get("batch_id")))) {
            throw new IllegalArgumentException("child batch_id does not match parent batch_id");
        }
        if (!summary.runId().equals(text(result.get("run_id")))
                || !summary.suiteId().equals(text(result.get("suite_id")))) {
            throw new IllegalArgumentException("child result/summary identity mismatch");
        }
        String resultSummaryRef = text(result.get("suite_summary_ref"));
        if (!"suite_summary.json".equals(resultSummaryRef)
                || !summaryPath.equals(resultPath.getParent().resolve(resultSummaryRef).normalize())) {
            throw new IllegalArgumentException("child suite_summary_ref does not resolve to supplied summary");
        }
        return new ResolvedChild(child, resultPath, summaryPath, result, summary);
    }

    private EvidenceMerge mergeEvidence(
            Path runRoot, String suiteId, String batchId, String runId, String profile,
            List<ResolvedChild> children) throws IOException {
        Path evidenceDir = runRoot.resolve("evidence");
        Files.createDirectories(evidenceDir);
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, String> refs = new LinkedHashMap<>();
        for (ResolvedChild child : children) {
            String indexRef = text(child.result().get("evidence_index_ref"));
            if (indexRef.isBlank()) continue;
            Path index = contained(child.resultPath().getParent(), child.resultPath().getParent().resolve(indexRef), "evidence index");
            Object loaded = new Yaml().load(Files.readString(index));
            if (!(loaded instanceof Map<?, ?> root) || !(root.get("entries") instanceof List<?> childEntries)) {
                throw new IllegalArgumentException("child evidence index has no entries array: " + index);
            }
            for (Object value : childEntries) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                Map<String, Object> entry = stringMap(raw);
                String oldId = text(entry.get("evidence_id"));
                String newId = child.artifact().id() + "::" + oldId;
                String oldFileRef = text(entry.get("file_path"));
                Path childFile = contained(index.getParent(), index.getParent().resolve(oldFileRef), "evidence file");
                String newFileRef = artifactRef(runRoot, childFile);
                entry.put("evidence_id", newId);
                entry.put("file_path", newFileRef);
                entry.put("linked_result_field", "evidence_refs");
                entries.add(entry);
                refs.put("evidence://" + oldId, "evidence://" + newId);
                refs.put(oldId, newId);
                refs.put(oldFileRef, newFileRef);
            }
        }
        Path log = evidenceDir.resolve("aggregation_execution_log.txt");
        Files.writeString(log, "suite_id=" + suiteId + "\nrun_id=" + runId + "\n");
        Map<String, Object> own = new LinkedHashMap<>();
        own.put("evidence_id", "aggregation::execution-log");
        own.put("evidence_type", "execution_log");
        own.put("produced_by", "suite_aggregation");
        own.put("test_case_id", suiteId + "-AGGREGATE");
        own.put("run_id", runId);
        own.put("batch_id", batchId);
        own.put("file_path", "evidence/aggregation_execution_log.txt");
        own.put("content_type", "text/plain");
        own.put("status", "passed");
        own.put("created_at", Instant.now().toString());
        own.put("masking_applied", true);
        own.put("linked_result_field", "evidence_refs");
        entries.add(own);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("evidence_index_version", "v0.3");
        index.put("suite_id", suiteId);
        index.put("batch_id", batchId);
        index.put("run_id", runId);
        index.put("test_case_id", suiteId + "-AGGREGATE");
        index.put("profile", profile);
        index.put("entries", entries);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Files.writeString(runRoot.resolve("evidence_index.yaml"), new Yaml(options).dump(index));
        List<String> resultRefs = entries.stream()
                .map(entry -> "evidence://" + text(entry.get("evidence_id"))).toList();
        boolean masked = entries.stream().allMatch(entry -> Boolean.TRUE.equals(entry.get("masking_applied")));
        return new EvidenceMerge(refs, resultRefs, entries.size(), masked);
    }

    private List<Map<String, Object>> copyEntries(
            List<ResolvedChild> children, String field, String identityField, Map<String, String> refs) {
        List<Map<String, Object>> copied = new ArrayList<>();
        for (ResolvedChild child : children) {
            String suitePath = child.artifact().id();
            Object value = child.result().get(field);
            if (!(value instanceof List<?> list)) continue;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Map<String, Object> entry = rewriteRefs(stringMap(raw), refs);
                entry.put("suite_path", suitePath);
                if (identityField != null) {
                    entry.put(identityField, suitePath + "::" + text(entry.get("test_case_id")));
                }
                copied.add(entry);
            }
        }
        return List.copyOf(copied);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rewriteRefs(Map<String, Object> source, Map<String, String> refs) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof String text) result.put(key, refs.getOrDefault(text, text));
            else if (value instanceof List<?> list) result.put(key, list.stream()
                    .map(item -> item instanceof String text ? refs.getOrDefault(text, text) : item).toList());
            else if (value instanceof Map<?, ?> map) result.put(key, rewriteRefs(stringMap(map), refs));
            else result.put(key, value);
        });
        return result;
    }

    private SuiteSummaryDocument.ChildEntry childEntry(Path runRoot, ResolvedChild child) {
        SuiteSummaryDocument summary = child.summary();
        Path childManifest = child.summaryPath().getParent().resolve(summary.suiteManifestRef()).normalize();
        return new SuiteSummaryDocument.ChildEntry(
                summary.suiteId(), artifactRef(runRoot, childManifest), summary.batchId(), summary.runId(), summary.status(),
                summary.startTime(), summary.endTime(), summary.durationMs(), artifactRef(runRoot, child.summaryPath()),
                summary.totalSummary());
    }

    private SuiteSummaryDocument.FailureSummary failureSummary(
            Path runRoot, List<ResolvedChild> children, List<SuiteSummaryDocument.AggregationError> errors) {
        int failed = 0;
        int blocked = 0;
        Map<String, Integer> categories = new LinkedHashMap<>();
        List<SuiteSummaryDocument.FailedChildRef> refs = new ArrayList<>();
        for (ResolvedChild child : children) {
            SuiteSummaryDocument.FailureSummary failure = child.summary().failureSummary();
            failed += failure.testFailureCount();
            blocked += failure.testBlockedCount();
            failure.byCategory().forEach((key, value) -> categories.merge(key, value, Integer::sum));
            if ("failed".equals(child.summary().status()) || "blocked".equals(child.summary().status())) {
                refs.add(new SuiteSummaryDocument.FailedChildRef(
                        child.summary().suiteId(), child.summary().status(),
                        artifactRef(runRoot, child.summaryPath())));
            }
        }
        if (!errors.isEmpty()) categories.merge("aggregation_error", errors.size(), Integer::sum);
        return new SuiteSummaryDocument.FailureSummary(
                failed, blocked, errors.size(), failed + blocked + errors.size(), categories, List.of(), refs);
    }

    private List<Map<String, Object>> providerSummary(List<Map<String, Object>> providers) {
        Map<String, Long> byType = new LinkedHashMap<>();
        providers.forEach(provider -> byType.merge(text(provider.get("provider_type")), 1L, Long::sum));
        return byType.entrySet().stream().map(entry -> Map.<String, Object>of(
                "provider_type", entry.getKey(), "provider_count", entry.getValue())).toList();
    }

    private Map<String, Object> aggregateFailure(List<SuiteSummaryDocument.AggregationError> errors) {
        if (!errors.isEmpty()) return Map.of(
                "code", "SUITE_AGGREGATION_FAILED", "category", "aggregation_error",
                "reason", "One or more executed child artifacts could not be aggregated.",
                "owner_action", "Inspect aggregation_errors in suite_summary.json and rerun invalid children.");
        return Map.of(
                "code", "CHILD_SUITE_FAILED", "category", "verification_failed",
                "reason", "One or more child suites did not pass.",
                "owner_action", "Inspect failed_child_refs and child evidence.");
    }

    private Path contained(Path root, Path candidate, String label) throws IOException {
        Path realRoot = root.toRealPath();
        Path real = candidate.toAbsolutePath().normalize().toRealPath();
        if (!real.startsWith(realRoot)) throw new IllegalArgumentException(label + " escapes assigned run directory");
        return real;
    }

    private String artifactRef(Path root, Path path) {
        if (path == null) return "";
        try {
            return root.toRealPath().relativize(path.toRealPath()).toString().replace('\\', '/');
        } catch (IOException error) {
            return path.toString().replace('\\', '/');
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private String text(Object value) { return value == null ? "" : value.toString(); }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public record ChildArtifact(String id, String ref, Path resultJson, Path summaryJson) {}
    public record AggregatedArtifacts(
            Path resultJson, Path summaryJson, Path summaryYaml, Path evidenceIndex,
            String status, String completionStatus) {}
    private record ResolvedChild(
            ChildArtifact artifact, Path resultPath, Path summaryPath,
            Map<String, Object> result, SuiteSummaryDocument summary) {}
    private record EvidenceMerge(
            Map<String, String> refMap, List<String> refs, int entryCount, boolean maskingApplied) {}
}
