package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunTraceabilityIndexTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    private static ObjectNode run() {
        ObjectNode run = JSON.createObjectNode().put("status", "passed").put("plan_digest", SHA)
                .put("completion_status", "complete");
        var sources = run.putArray("case_sources");
        for (String id : List.of("actual", "other")) {
            var source = sources.addObject().put("test_case_id", id).put("source_path", id + ".yaml")
                    .put("source_sha256", SHA).put("selection", id.equals("actual") ? "SELECTED" : "UNSELECTED");
            var refs = source.putObject("source_refs").put("acceptance_criteria", "AC-" + id);
            refs.putArray("acceptance_refs").add("AC-extra").add("AC-" + id);
            refs.putArray("requirement_refs").add("REQ-1").add("REQ-2");
        }
        run.putArray("test_results").addObject().put("test_case_id", "actual").put("status", "passed");
        run.putArray("verify_results").addObject().put("test_case_id", "actual").put("evidence_ref", "actual.json");
        return run;
    }

    private static JsonNode project(ObjectNode run, String state, String evidence) {
        Map<String, List<JsonNode>> paths = Map.of("actual",
                List.of(JSON.createObjectNode().put("path", evidence).put("sha256", SHA)));
        return RunTraceabilityIndex.project(JSON, run, SHA, "../run/result.json", state, paths,
                JSON.createObjectNode(), "/run/result.json");
    }

    @Test void distinguishesCanonicalOutcomeFromCaseSelectionAndEvidence() {
        ObjectNode run = run();
        JsonNode index = project(run, "VALID", "actual.json");
        assertThat(index.path("review_ready").asBoolean()).isTrue();
        assertThat(index.at("/cases/0/outcome").asText()).isEqualTo("PASS");
        assertThat(index.at("/cases/0/acceptance_criteria_refs/0").asText()).isEqualTo("AC-actual");
        assertThat(index.at("/cases/0/acceptance_criteria_refs").size()).isEqualTo(2);
        assertThat(index.at("/cases/0/requirement_refs/1").asText()).isEqualTo("REQ-2");
        assertThat(index.at("/cases/1/selection").asText()).isEqualTo("UNSELECTED");
        assertThat(index.at("/cases/1/outcome").asText()).isEqualTo("NOT_RUN");
        assertThat(index.at("/cases/1/review_ready").asBoolean()).isFalse();
        assertThat(index.at("/cases/1/evidence").size()).isZero();
        for (var outcome : Map.of("failed", "FAIL", "blocked", "BLOCKED", "skipped", "SKIPPED").entrySet()) {
            ((ObjectNode) run.path("test_results").get(0)).put("status", outcome.getKey());
            assertThat(project(run, "VALID", "actual.json").at("/cases/0/outcome").asText()).isEqualTo(outcome.getValue());
        }
    }

    @Test void missingRequiredEvidenceSourceIdentityOrExecutionNeverYieldsReviewReady() {
        ObjectNode run = run();
        assertThat(project(run, "MISSING", "actual.json").path("review_ready").asBoolean()).isFalse();
        assertThat(project(run, "VALID", "unrelated.json").path("review_ready").asBoolean()).isFalse();
        run.remove("verify_results");
        JsonNode noExecution = project(run, "VALID", "actual.json");
        assertThat(noExecution.at("/cases/0/outcome").asText()).isEqualTo("NOT_RUN");
        assertThat(noExecution.path("review_ready").asBoolean()).isFalse();
        ObjectNode historical = run();
        historical.remove("case_sources");
        JsonNode oldIndex = project(historical, "VALID", "actual.json");
        assertThat(oldIndex.path("review_ready").asBoolean()).isFalse();
        assertThat(oldIndex.at("/cases/0/source_sha256").isNull() || oldIndex.at("/cases/0/source_sha256").isMissingNode()).isTrue();
    }
}
