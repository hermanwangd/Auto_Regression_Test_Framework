package com.specdriven.regression.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Portable per-invocation projection of compiled sources, canonical outcomes and indexed evidence. */
final class RunTraceabilityIndex {
    private RunTraceabilityIndex() {}

    static ObjectNode project(ObjectMapper json, JsonNode result, String digest, String path,
            String evidenceState, Map<String, List<JsonNode>> caseEvidence, JsonNode coverage, String sourcePath) {
        ObjectNode index = header(json, result, digest, path, evidenceState, coverage);
        Map<String, JsonNode> sources = byCase(result.path("case_sources"));
        Map<String, JsonNode> tests = byCase(result.path("test_results"));
        Set<String> ids = new LinkedHashSet<>(sources.keySet());
        ids.addAll(tests.keySet());
        Set<String> attempted = new LinkedHashSet<>();
        Map<String, Set<String>> expectedAssertions = new LinkedHashMap<>();
        for (String section : List.of("steps", "verify_results")) {
            for (JsonNode step : result.path(section)) {
                String id = step.path("test_case_id").asText();
                attempted.add(id);
                if ("verify_results".equals(section)) add(expectedAssertions, id, step.path("evidence_ref"));
            }
        }
        Map<String, Set<String>> requirements = new LinkedHashMap<>(), acceptance = new LinkedHashMap<>();
        for (JsonNode trace : coverage.path("oracle_trace")) {
            if (!sourcePath.equals(trace.path("source_result").asText())) continue;
            String id = trace.path("test_case_id").asText();
            for (JsonNode ref : trace.path("requirement_refs")) add(requirements, id, ref);
            for (JsonNode ref : trace.path("acceptance_criteria_refs")) add(acceptance, id, ref);
        }
        // Catalog mappings use qualified case refs for reviewed multi-repository runs.
        Map<String, String> matchedCases = new LinkedHashMap<>();
        for (JsonNode row : coverage.path("cases")) {
            if (!sourcePath.equals(row.path("source_result").asText())) continue;
            String id = row.path("test_case_id").asText();
            matchedCases.put(row.path("qualified_case_ref").asText(id), id);
        }
        for (JsonNode entity : coverage.path("entities")) {
            if (!"acceptance_criterion".equals(entity.path("kind").asText())) continue;
            for (JsonNode mapped : entity.path("test_case_ids")) {
                String id = matchedCases.get(mapped.asText());
                if (id != null) {
                    add(requirements, id, entity.path("parent_requirement_id"));
                    add(acceptance, id, entity.path("id"));
                }
            }
        }
        boolean completeRun = "VALID".equals(evidenceState) && readyForCase(result);
        boolean ready = !tests.isEmpty() && completeRun;
        var rows = index.putArray("cases");
        for (String id : ids) {
            JsonNode source = sources.getOrDefault(id, json.createObjectNode());
            JsonNode test = tests.get(id);
            JsonNode refs = source.path("source_refs");
            for (String name : List.of("requirement", "requirements", "requirement_ref", "requirement_refs", "framework_requirement")) add(requirements, id, refs.path(name));
            for (String name : List.of("acceptance_criteria", "acceptance_criterion", "acceptance_ref", "acceptance_refs",
                    "acceptance_criteria_refs", "ac_id", "ac_ids")) add(acceptance, id, refs.path(name));
            String selection = source.path("selection").asText(test == null ? "UNKNOWN" : "SELECTED");
            String outcome = test == null ? "NOT_RUN" : switch (test.path("status").asText()) {
                case "passed" -> attempted.contains(id) ? "PASS" : "NOT_RUN";
                case "failed" -> "FAIL";
                case "blocked" -> "BLOCKED";
                case "skipped" -> "SKIPPED";
                default -> "NOT_RUN";
            };
            ObjectNode row = rows.addObject().put("test_case_id", id).put("selection", selection)
                    .put("lifecycle", source.path("lifecycle").asText("UNKNOWN")).put("outcome", outcome);
            row.set("canonical_case_status", test == null ? json.nullNode() : test.path("status"));
            row.set("source_path", source.path("source_path"));
            row.set("source_sha256", source.path("source_sha256"));
            row.set("source_refs", refs);
            row.set("requirement_refs", json.valueToTree(requirements.getOrDefault(id, Set.of())));
            row.set("acceptance_criteria_refs", json.valueToTree(acceptance.getOrDefault(id, Set.of())));
            List<JsonNode> evidence = caseEvidence.getOrDefault(id, List.of());
            row.set("evidence", json.valueToTree(evidence));
            List<String> gaps = new ArrayList<>();
            if (source.path("source_path").asText().isBlank() || !source.path("source_sha256").asText().matches("[0-9a-f]{64}"))
                gaps.add("compiled_source_identity_missing");
            if ("SELECTED".equals(selection)) {
                if (!"VALID".equals(evidenceState)) gaps.add("canonical_evidence_not_valid");
                if ("NOT_RUN".equals(outcome)) gaps.add("execution_not_observed");
                Set<String> observed = new LinkedHashSet<>();
                for (JsonNode entry : evidence) observed.add(entry.path("path").asText());
                if (observed.isEmpty() || !observed.containsAll(expectedAssertions.getOrDefault(id, Set.of())))
                    gaps.add("case_evidence_missing");
            } else if (!"UNSELECTED".equals(selection) || test != null) {
                gaps.add("selection_inconsistent");
            }
            row.set("gaps", json.valueToTree(gaps));
            row.put("review_ready", "SELECTED".equals(selection) && gaps.isEmpty() && completeRun);
            if (!gaps.isEmpty()) ready = false;
        }
        index.put("review_ready", ready);
        return index;
    }

    static ObjectNode header(ObjectMapper json, JsonNode result, String digest, String path,
            String evidenceState, JsonNode coverage) {
        ObjectNode index = json.createObjectNode();
        index.put("schema_version", "run-traceability.v1");
        index.put("review_scope", "case_execution_and_evidence");
        for (String field : List.of("suite_id", "run_id", "batch_id", "profile", "plan_digest", "evidence_index_ref")) {
            index.set(field, result.path(field));
        }
        index.put("canonical_status", result.path("status").asText());
        index.putObject("canonical_result").put("path", path).put("sha256", digest);
        index.put("evidence_state", evidenceState);
        index.set("evidence_classification", result.path("labels").path("evidence_classification"));
        index.set("requirement_inventory_state", coverage.path("inventory").path("state"));
        return index;
    }

    private static boolean readyForCase(JsonNode result) {
        return "complete".equals(result.path("completion_status").asText())
                && result.path("plan_digest").asText().matches("[0-9a-f]{64}");
    }

    private static Map<String, JsonNode> byCase(JsonNode rows) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String id = row.path("test_case_id").asText();
            if (id.isBlank() || byId.putIfAbsent(id, row) != null)
                throw new IllegalArgumentException("Invalid or duplicate traceability case ID");
        }
        return byId;
    }

    private static void add(Map<String, Set<String>> values, String id, JsonNode value) {
        if (value.isArray()) {
            for (JsonNode item : value) if (item.isTextual()) add(values, id, item);
        } else if (value.isTextual() && !value.asText().isBlank()) {
            values.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(value.asText());
        }
    }
}
