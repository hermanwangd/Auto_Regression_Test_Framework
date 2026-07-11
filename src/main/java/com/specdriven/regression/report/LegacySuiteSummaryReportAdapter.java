package com.specdriven.regression.report;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Isolates the temporary v0.3.x reader for unversioned suite-group summaries. */
public final class LegacySuiteSummaryReportAdapter {

    public boolean supports(Map<String, Object> document) {
        return !document.containsKey("result_contract_version")
                && !document.containsKey("suite_summary_version")
                && document.containsKey("suite_id")
                && document.containsKey("children")
                && !document.containsKey("provider_results");
    }

    public Map<String, Object> adapt(Map<String, Object> legacy) {
        if (!supports(legacy)) {
            throw new IllegalArgumentException("Input is not an unversioned legacy suite summary");
        }
        String status = text(legacy.get("status"));
        if (!List.of("passed", "failed", "blocked").contains(status)) {
            throw new IllegalArgumentException("Legacy suite summary has unsupported status: " + status);
        }
        String now = Instant.now().toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework_version", "legacy-compatibility");
        result.put("dsl_version", "v0.2");
        result.put("suite_id", text(legacy.get("suite_id")));
        result.put("batch_id", text(legacy.get("batch_id")));
        result.put("run_id", text(legacy.get("run_id")));
        result.put("test_case_id", text(legacy.get("suite_id")) + "-LEGACY-SUMMARY");
        result.put("status", status);
        result.put("profile", text(legacy.get("profile")));
        result.put("environment", text(legacy.get("profile")));
        result.put("start_time", now);
        result.put("end_time", now);
        result.put("duration_ms", 0);
        result.put("timestamps", Map.of("started_at", now, "finished_at", now));
        result.put("provider_results", List.of());
        result.put("steps", List.of());
        result.put("verify_results", List.of());
        result.put("evidence_refs", List.of());
        result.put("failure", "passed".equals(status) ? null : Map.of(
                "code", "LEGACY_SUITE_FAILED", "category", "verification_failed",
                "reason", "Legacy suite summary reported a non-passing status.",
                "owner_action", "Inspect the legacy child summary and migrate to canonical v0.3 result output."));
        result.put("labels", Map.of("report_compatibility_source", "legacy_suite_summary"));
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
