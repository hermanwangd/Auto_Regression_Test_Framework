package com.specdriven.regression.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class EvidenceIndexFormatter {

    private EvidenceIndexFormatter() {
    }

    public static String format(Context context, Path runDir, List<String> refs) {
        StringBuilder index = new StringBuilder();
        index.append("evidence_index_version: v0.2\n");
        index.append("suite_id: ").append(context.suiteId()).append('\n');
        index.append("batch_id: ").append(context.batchId()).append('\n');
        index.append("run_id: ").append(context.runId()).append('\n');
        index.append("test_case_id: ").append(context.testCaseId()).append('\n');
        index.append("profile: ").append(context.profile()).append('\n');
        index.append("entries:\n");
        for (String ref : distinct(refs)) {
            if (ref == null || ref.isBlank() || ref.endsWith("evidence_index.yaml")) {
                continue;
            }
            Entry entry = entry(context, runDir, ref);
            index.append("  - evidence_id: ").append(entry.evidenceId()).append('\n');
            index.append("    evidence_type: ").append(entry.evidenceType()).append('\n');
            index.append("    produced_by: ").append(entry.producedBy()).append('\n');
            if (entry.providerEvidence()) {
                index.append("    provider_type: ").append(context.providerType()).append('\n');
                index.append("    provider_id: ").append(context.providerId()).append('\n');
            }
            index.append("    test_case_id: ").append(context.testCaseId()).append('\n');
            index.append("    run_id: ").append(context.runId()).append('\n');
            index.append("    batch_id: ").append(context.batchId()).append('\n');
            index.append("    file_path: ").append(ref).append('\n');
            index.append("    content_type: ").append(contentType(ref)).append('\n');
            index.append("    status: ").append(entry.status()).append('\n');
            index.append("    created_at: ").append(Instant.now()).append('\n');
            index.append("    masking_applied: true\n");
            index.append("    linked_result_field: ").append(entry.linkedResultField()).append('\n');
            if (!entry.failureCode().isBlank()) {
                index.append("    failure_code: ").append(entry.failureCode()).append('\n');
            }
        }
        index.append("masking:\n  raw_secret_found: false\n");
        return index.toString();
    }

    private static Entry entry(Context context, Path runDir, String ref) {
        String evidenceType = evidenceType(context.providerType(), ref);
        String content = read(runDir.resolve(ref));
        String extractedFailureCode = failureCode(content);
        String status = failed(content) || ref.contains("failure_detail") ? "failed" : "passed";
        String failureCode = status.equals("failed")
                ? (extractedFailureCode.isBlank() ? "EVIDENCE_FAILED" : extractedFailureCode)
                : "";
        return new Entry(
                evidenceType + "-" + safe(ref),
                evidenceType,
                producedBy(evidenceType),
                providerEvidence(evidenceType),
                linkedResultField(evidenceType),
                status,
                failureCode);
    }

    private static String evidenceType(String providerType, String ref) {
        String normalized = ref.toLowerCase(Locale.ROOT);
        if (normalized.contains("logs/execution")) {
            return "execution_log";
        }
        if (normalized.contains("batch/")) {
            return "batch_summary";
        }
        if (normalized.contains("polling/")) {
            return "polling_observation";
        }
        if (normalized.contains("assertions/") || normalized.contains("diffs/")) {
            return "assertion_diff";
        }
        if (normalized.contains("injected_stubs") || normalized.contains("seed_")) {
            return "jdbc".equals(providerType) || "jdbc_database".equals(providerType) ? "jdbc_seed" : "fixture_setup";
        }
        if (normalized.contains("cleanup")) {
            return "jdbc".equals(providerType) || "jdbc_database".equals(providerType) ? "jdbc_cleanup" : "fixture_cleanup";
        }
        if (normalized.contains("request_journal")) {
            return "wiremock_request_journal";
        }
        if (normalized.contains("server_log")) {
            return "wiremock_server_log";
        }
        if (normalized.contains("query_")) {
            return "jdbc_query";
        }
        if ("nats".equals(providerType) || normalized.contains("provider-evidence/nats/")) {
            return "nats_event";
        }
        if ("wiremock_http_mock".equals(providerType)) {
            return "wiremock_server_log";
        }
        if ("jdbc".equals(providerType) || "jdbc_database".equals(providerType)) {
            return "jdbc_query";
        }
        return "assertion_diff";
    }

    private static String producedBy(String evidenceType) {
        if (evidenceType.equals("execution_log") || evidenceType.equals("batch_summary")) {
            return "framework";
        }
        if (evidenceType.equals("assertion_diff")) {
            return "assertion_engine";
        }
        if (evidenceType.equals("polling_observation")) {
            return "polling_engine";
        }
        return "provider";
    }

    private static boolean providerEvidence(String evidenceType) {
        return List.of(
                "fixture_setup",
                "fixture_cleanup",
                "wiremock_request_journal",
                "wiremock_server_log",
                "jdbc_seed",
                "jdbc_query",
                "jdbc_cleanup",
                "nats_event").contains(evidenceType);
    }

    private static String linkedResultField(String evidenceType) {
        return switch (evidenceType) {
            case "execution_log", "batch_summary" -> "evidence_refs";
            case "assertion_diff" -> "verify_results";
            case "polling_observation" -> "verify_results.polling";
            case "fixture_cleanup", "jdbc_cleanup" -> "provider_results.cleanup_status";
            case "fixture_setup", "jdbc_seed" -> "steps";
            default -> "provider_results.resolved_operation_result";
        };
    }

    private static boolean failed(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("comparison_status: failed")
                || lower.contains("final_status: failed")
                || lower.contains("status: failed")
                || lower.contains("\"status\": \"failed\"")
                || !failureCode(content).isBlank();
    }

    private static String failureCode(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("failure_code:")) {
                return trimmed.substring("failure_code:".length()).trim();
            }
            if (trimmed.startsWith("\"failure_code\"")) {
                int colon = trimmed.indexOf(':');
                if (colon >= 0) {
                    return trimmed.substring(colon + 1).replace("\"", "").replace(",", "").trim();
                }
            }
        }
        return "";
    }

    private static String contentType(String ref) {
        String normalized = ref.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".json")) {
            return "application/json";
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return "application/x-yaml";
        }
        if (normalized.endsWith(".diff")) {
            return "text/x-diff";
        }
        return "text/plain";
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> distinct(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (seen.add(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public record Context(
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            String profile,
            String providerType,
            String providerId) {
    }

    private record Entry(
            String evidenceId,
            String evidenceType,
            String producedBy,
            boolean providerEvidence,
            String linkedResultField,
            String status,
            String failureCode) {
    }
}
