package com.specdriven.regression.contract;

import com.specdriven.regression.provider.runtime.ProviderFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProviderCapabilityResultWriter {

    static final String FRAMEWORK_VERSION = "0.2.1";

    private ProviderCapabilityResultWriter() {
    }

    static Path write(Path runDir, ResultDocument document) {
        Path resultJson = runDir.resolve("result.json");
        try {
            Files.createDirectories(resultJson.getParent());
            Files.writeString(resultJson, toJson(document.toMap()) + "\n");
            return resultJson;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String topLevelTestCaseId(Object suiteId, Object testCaseId, int testCount) {
        if (testCount == 1) {
            return stringValue(testCaseId);
        }
        return stringValue(suiteId) + "-MULTI";
    }

    static boolean singleProvider(List<Map<String, Object>> providerResults) {
        return providerResults.stream()
                .map(provider -> stringValue(provider.get("provider_type")) + "\n" + stringValue(provider.get("provider_id")))
                .distinct()
                .count() <= 1;
    }

    static Map<String, Object> singleProviderRootFields(List<Map<String, Object>> providerResults) {
        if (providerResults.isEmpty() || !singleProvider(providerResults)) {
            return Map.of();
        }
        Map<String, Object> provider = providerResults.get(0);
        Map<String, Object> rootFields = new LinkedHashMap<>();
        putIfPresent(rootFields, "provider_type", provider.get("provider_type"));
        putIfPresent(rootFields, "provider_id", provider.get("provider_id"));
        putIfPresent(rootFields, "runtime_mode", provider.get("runtime_mode"));
        putIfPresent(rootFields, "base_url", provider.get("base_url"));
        putIfPresent(rootFields, "dialect", provider.get("dialect"));
        putIfPresent(rootFields, "subject", provider.get("subject"));
        putIfPresent(rootFields, "topic", provider.get("topic"));
        putIfPresent(rootFields, "queue", provider.get("queue"));
        return rootFields;
    }

    static List<Map<String, Object>> providerSummary(List<Map<String, Object>> providerResults) {
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (Map<String, Object> provider : providerResults) {
            String key = stringValue(provider.get("provider_type")) + "\n" + stringValue(provider.get("provider_id"));
            Map<String, Object> existing = summaries.get(key);
            if (existing == null) {
                summaries.put(key, providerSummaryEntry(provider));
            } else {
                mergeProviderSummaryStatus(existing, provider);
            }
        }
        return List.copyOf(summaries.values());
    }

    static List<String> providerEvidenceRefs(List<String> evidenceRefs) {
        return evidenceRefs.stream()
                .filter(ProviderCapabilityResultWriter::isProviderEvidenceRef)
                .distinct()
                .toList();
    }

    private static Map<String, Object> providerSummaryEntry(Map<String, Object> provider) {
        Map<String, Object> summary = new LinkedHashMap<>();
        putIfPresent(summary, "provider_id", provider.get("provider_id"));
        putIfPresent(summary, "provider_type", provider.get("provider_type"));
        putIfPresent(summary, "runtime_mode", provider.get("runtime_mode"));
        putIfPresent(summary, "base_url", provider.get("base_url"));
        putIfPresent(summary, "dialect", provider.get("dialect"));
        putIfPresent(summary, "subject", provider.get("subject"));
        putIfPresent(summary, "topic", provider.get("topic"));
        putIfPresent(summary, "queue", provider.get("queue"));
        putIfPresent(summary, "status", provider.get("status"));
        putIfPresent(summary, "cleanup_status", provider.get("cleanup_status"));
        return summary;
    }

    private static void mergeProviderSummaryStatus(Map<String, Object> summary, Map<String, Object> provider) {
        putIfPresent(summary, "status", mergedStatus(summary.get("status"), provider.get("status")));
        Object cleanupStatus = provider.get("cleanup_status");
        if (cleanupStatus != null && !"passed".equals(stringValue(cleanupStatus))) {
            putIfPresent(summary, "cleanup_status", cleanupStatus);
        }
    }

    private static String mergedStatus(Object current, Object incoming) {
        List<String> statuses = List.of(stringValue(current), stringValue(incoming));
        if (statuses.contains("failed")) {
            return "failed";
        }
        if (statuses.contains("blocked")) {
            return "blocked";
        }
        if (statuses.contains("passed")) {
            return "passed";
        }
        return statuses.stream().filter(value -> !value.isBlank()).findFirst().orElse("");
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            target.put(key, value);
        }
    }

    private static boolean isProviderEvidenceRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        String normalized = stripFragment(ref).replace('\\', '/');
        return containsEvidenceSegment(normalized, "provider-evidence/")
                || containsEvidenceSegment(normalized, "query-evidence/")
                || containsEvidenceSegment(normalized, "event-evidence/")
                || containsEvidenceSegment(normalized, "fixture/")
                || containsEvidenceSegment(normalized, "actual/")
                || hasProviderEvidenceIdPrefix(normalized);
    }

    private static boolean containsEvidenceSegment(String ref, String segment) {
        return ref.startsWith(segment) || ref.contains("/" + segment);
    }

    private static boolean hasProviderEvidenceIdPrefix(String ref) {
        return List.of(
                        "fixture-setup",
                        "fixture-cleanup",
                        "wiremock-request-journal",
                        "wiremock-server-log",
                        "jdbc-seed",
                        "jdbc-query",
                        "jdbc-cleanup",
                        "nats-event",
                        "kafka-event",
                        "ibm-mq-event",
                        "http-request-response",
                        "grpc-request-response")
                .stream()
                .anyMatch(ref::startsWith);
    }

    private static String stripFragment(String ref) {
        int fragmentIndex = ref.indexOf('#');
        return fragmentIndex < 0 ? ref : ref.substring(0, fragmentIndex);
    }

    private static Map<String, Object> failureObject(ProviderFailure failure, ProviderFailure cleanupFailure) {
        Map<String, Object> failureObject = new LinkedHashMap<>();
        failureObject.put("code", failure == null ? null : failure.code());
        failureObject.put("classification", failure == null ? null : failure.classification());
        failureObject.put("reason", failure == null ? null : failure.reason());
        failureObject.put("owner_action", failure == null ? null : failure.ownerAction());
        if (cleanupFailure != null) {
            failureObject.put("cleanup_failure", Map.of(
                    "code", cleanupFailure.code(),
                    "classification", cleanupFailure.classification(),
                    "reason", cleanupFailure.reason(),
                    "owner_action", cleanupFailure.ownerAction()));
        }
        return failureObject;
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    record ResultDocument(
            Object dslVersion,
            Object suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            String environment,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Object labels,
            Object sourceRefs,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<Map<String, Object>> testResults,
            List<Map<String, Object>> providerResults,
            List<String> providerEvidenceRefs,
            List<String> evidenceRefs,
            Map<String, Object> rootProviderFields,
            ProviderFailure failure,
            ProviderFailure cleanupFailure,
            boolean includeEvidenceIndexRef) {

        Map<String, Object> toMap() {
            String started = startedAt.toString();
            String finished = finishedAt.toString();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("framework_version", FRAMEWORK_VERSION);
            result.put("dsl_version", dslVersion);
            result.put("suite_id", suiteId);
            result.put("batch_id", batchId);
            result.put("run_id", runId);
            result.put("test_case_id", testCaseId);
            result.put("test_count", testCount);
            result.put("profile", profile);
            result.put("environment", environment == null || environment.isBlank() ? profile : environment);
            result.put("provider_summary", providerSummary(providerResults));
            result.putAll(rootProviderFields);
            result.put("status", status);
            result.put("start_time", started);
            result.put("end_time", finished);
            result.put("duration_ms", Duration.between(startedAt, finishedAt).toMillis());
            result.put("timestamps", Map.of("started_at", started, "finished_at", finished));
            result.put("labels", labels);
            result.put("source_refs", sourceRefs);
            result.put("step_results", stepResults);
            result.put("steps", stepResults);
            result.put("verify_results", verifyResults);
            result.put("test_results", testResults);
            result.put("provider_results", providerResults);
            if (includeEvidenceIndexRef) {
                result.put("evidence_index_ref", "evidence_index.yaml");
            }
            result.put("provider_evidence_refs", ProviderCapabilityResultWriter.providerEvidenceRefs(providerEvidenceRefs));
            result.put("evidence_refs", evidenceRefs);
            result.put("failure", failureObject(failure, cleanupFailure));
            return result;
        }
    }
}
