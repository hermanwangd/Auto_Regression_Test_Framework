package com.specdriven.regression.cli;

import static com.specdriven.regression.cli.RegressionCommand.printContractFindings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.application.SuiteReporting;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ReportResult;
import com.specdriven.regression.evidence.EvidenceHardeningService.EvidenceValidationResult;
import com.specdriven.regression.evidence.EvidenceHardeningService.ProviderEvidenceSummary;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class ReportOutput {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    int render(SuiteReporting.Report observation, Path resolvedResult, String format, PrintStream out, PrintStream err) {
        ReportResult result = observation.result();
        if (!result.valid() && result.findings().stream().anyMatch(finding -> "missing_result_json".equals(finding.reason()))) {
            if ("json".equals(format)) {
                printReportJson(out, err, jsonFailure(
                        "RESULT_JSON_MISSING",
                        "CONFIGURATION_ERROR",
                        "Missing result JSON: " + resolvedResult,
                        result.findings()));
                return 1;
            }
            err.println("Missing result JSON: " + resolvedResult);
            return 1;
        }
        if (!result.valid()) {
            if ("json".equals(format)) {
                printReportJson(out, err, jsonFailure(
                        firstFailureCode(result.findings(), "RESULT_VALIDATION_FAILED"),
                        firstCategory(result.findings(), "VALIDATION_ERROR"),
                        firstOwnerAction(result.findings(), "Fix result JSON before running report."),
                        result.findings()));
                return 1;
            }
            out.println("report_status: invalid");
            printContractFindings(out, result.findings());
            return 1;
        }
        if (observation.coverageFailure() != null) {
            if ("json".equals(format)) printReportJson(out, err, jsonFailure(
                    "COVERAGE_GENERATION_FAILED", "EVIDENCE_ERROR", observation.coverageFailure().getMessage(), List.of()));
            else err.println(observation.coverageFailure().getMessage());
            return 1;
        }
        var evidenceResult = observation.evidence();
        var coverage = observation.coverage();
        boolean evidenceInvalid = observation.invalidEvidence();
        var timings = observation.timings();
        if ("json".equals(format)) {
            Map<String, Object> report = jsonReport(result, evidenceResult);
            addSuiteSummaryReportFields(report, observation.summary());
            if (!timings.isEmpty()) report.put("step_timings", timings);
            if (coverage != null) {
                report.put("coverage_json", coverage.json().toString());
                report.put("coverage_md", coverage.markdown().toString());
                report.put("coverage_report_status", coverage.status());
                report.put("coverage_json_sha256", coverage.jsonSha256());
                report.put("coverage_md_sha256", coverage.markdownSha256());
                report.put("requirement_coverage_percent", coverage.document().at("/metrics/requirements/satisfied_percent"));
                report.put("ac_coverage_percent", coverage.document().at("/metrics/acceptance_criteria/satisfied_percent"));
            }
            report.put("coverage_percent_metric", "test_pass_rate_percent");
            report.put("coverage_percent_deprecation", "Use typed requirement/AC metrics; the legacy field is a test pass rate.");
            return printReportJson(out, err, report) && !evidenceInvalid ? 0 : 1;
        }
        if (coverage != null) {
            out.println("coverage_json: " + coverage.json());
            out.println("coverage_md: " + coverage.markdown());
            out.println("coverage_report_status: " + coverage.status());
            out.println("requirement_coverage_percent: " + coverage.document().at("/metrics/requirements/satisfied_percent"));
            out.println("ac_coverage_percent: " + coverage.document().at("/metrics/acceptance_criteria/satisfied_percent"));
        }
        out.println("coverage_percent_metric: test_pass_rate_percent");
        out.println("coverage_percent_deprecation: Use typed requirement/AC metrics; the legacy field is a test pass rate.");
        if (result.suiteId().isBlank()) {
            out.println("report_status: " + (evidenceInvalid ? "invalid" : result.status()));
        } else {
            String reportStatus = evidenceInvalid
                    ? "invalid"
                    : ("passed".equals(result.status()) ? "review_ready" : "review_ready_with_failures");
            out.println("report_status: " + reportStatus);
            out.println("suite_id: " + result.suiteId());
            out.println("batch_id: " + result.batchId());
            out.println("run_id: " + result.runId());
            out.println("status: " + result.status());
        }
        out.println("test_case_id: " + result.testCaseId());
        out.println("profile: " + result.profile());
        printSuiteSummaryReportSections(out, observation.summary());
        if (!timings.isEmpty()) out.print(new Yaml().dumpAsMap(Map.of("step_timings", timings)));
        out.println("provider_results_count: " + result.providerResultsCount());
        out.println("verify_results_count: " + result.verifyResultsCount());
        if (!result.failedVerifySummary().isEmpty()) {
            out.println("failed_verify_summary:");
            for (String failedVerify : result.failedVerifySummary()) {
                out.println("  - " + failedVerify);
            }
        }
        out.println("release_evidence_eligible: " + result.releaseEvidenceEligible());
        if (evidenceResult != null) {
            out.println("coverage_percent: " + coveragePercent(evidenceResult));
            printEvidenceReportSummary(out, evidenceResult);
            printContractFindings(out, evidenceResult.findings());
            return !evidenceInvalid ? 0 : 1;
        }
        out.println("coverage_percent: " + ("passed".equals(result.status()) ? "100.0" : "0.0"));
        return evidenceInvalid ? 1 : 0;
    }

    private void addSuiteSummaryReportFields(Map<String, Object> report, Map<String, Object> summary) {
        if (summary.isEmpty()) return;
        report.put("suite_summary_version", summary.get("suite_summary_version"));
        report.put("completion_status", summary.get("completion_status"));
        report.put("termination_reason", summary.get("termination_reason"));
        report.put("start_time", summary.get("start_time"));
        report.put("end_time", summary.get("end_time"));
        report.put("duration_ms", summary.get("duration_ms"));
        report.put("self_summary", summary.get("self_summary"));
        report.put("child_aggregate_summary", summary.get("child_aggregate_summary"));
        report.put("total_summary", summary.get("total_summary"));
        report.put("failure_summary", summary.get("failure_summary"));
        report.put("children", summary.get("children"));
        report.put("aggregation_errors", summary.get("aggregation_errors"));
        report.put("report_compatibility_source", "canonical_result");
    }

    void printSuiteSummaryReportSections(PrintStream out, Map<String, Object> summary) {
        if (summary.isEmpty()) return;
        out.println("completion_status: " + stringValue(summary.get("completion_status")));
        out.println("termination_reason: " + stringValue(summary.get("termination_reason")));
        out.println("duration_ms: " + stringValue(summary.get("duration_ms")));
        for (String section : List.of("self_summary", "child_aggregate_summary", "total_summary")) {
            out.println(section + ":");
            mapValue(summary.get(section)).forEach((key, value) -> out.println("  " + key + ": " + stringValue(value)));
        }
        out.println("children:");
        for (Object value : objectList(summary.get("children"))) {
            Map<String, Object> child = mapValue(value);
            out.println("  - child_suite_id: " + stringValue(child.get("child_suite_id")));
            out.println("    status: " + stringValue(child.get("status")));
            out.println("    run_id: " + stringValue(child.get("run_id")));
        }
        out.println("aggregation_errors:");
        for (Object value : objectList(summary.get("aggregation_errors"))) {
            Map<String, Object> error = mapValue(value);
            out.println("  - child_suite_id: " + stringValue(error.get("child_suite_id")));
            out.println("    failure_code: " + stringValue(error.get("failure_code")));
            out.println("    owner_action: " + stringValue(error.get("owner_action")));
        }
    }

    Map<String, Object> jsonReport(ReportResult result, EvidenceValidationResult evidenceResult) {
        if (evidenceResult != null && !evidenceResult.valid()) {
            return jsonEvidenceFailure(result, evidenceResult);
        }
        Map<String, Object> report = jsonReportBase(result, evidenceResult);
        report.put("report_status", result.suiteId().isBlank()
                ? result.status()
                : ("passed".equals(result.status()) ? "review_ready" : "review_ready_with_failures"));
        report.put("failure_codes", List.of());
        return report;
    }

    private Map<String, Object> jsonEvidenceFailure(ReportResult result, EvidenceValidationResult evidenceResult) {
        String category = evidenceResult.findings().stream()
                .map(ContractFinding::category)
                .filter(value -> value != null && !value.isBlank())
                .filter("SECRET_GUARDRAIL_ERROR"::equals)
                .findFirst()
                .orElse("EVIDENCE_ERROR");
        String failureCode = "SECRET_GUARDRAIL_ERROR".equals(category)
                ? "SECRET_GUARDRAIL_ERROR"
                : "EVIDENCE_VALIDATION_FAILED";
        Map<String, Object> report = jsonReportBase(result, evidenceResult);
        report.put("report_status", "failed");
        report.put("failure_code", failureCode);
        report.put("category", category);
        report.put("message", "SECRET_GUARDRAIL_ERROR".equals(category)
                ? "Remove or mask raw secret values before using this report."
                : "Fix result evidence references before using this report.");
        report.put("failure_codes", List.of(failureCode));
        report.put("findings", jsonFindings(evidenceResult.findings()));
        return report;
    }

    private Map<String, Object> jsonReportBase(ReportResult result, EvidenceValidationResult evidenceResult) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite_id", result.suiteId());
        report.put("batch_id", result.batchId());
        report.put("run_id", result.runId());
        report.put("test_case_id", result.testCaseId());
        report.put("status", result.status());
        report.put("profile", result.profile());
        report.put("test_count", evidenceResult == null ? (result.status().isBlank() ? 0 : 1) : evidenceResult.testCount());
        report.put("passed_count", evidenceResult == null ? ("passed".equals(result.status()) ? 1 : 0) : evidenceResult.passCount());
        report.put("failed_count", evidenceResult == null ? ("passed".equals(result.status()) ? 0 : 1) : evidenceResult.failCount());
        report.put("provider_results_count", result.providerResultsCount());
        report.put("verify_results_count", result.verifyResultsCount());
        report.put("release_evidence_eligible", result.releaseEvidenceEligible());
        report.put("evidence_dir", evidenceResult == null ? "" : stringValue(evidenceResult.evidenceDir()));
        report.put("missing_evidence_count", evidenceResult == null ? 0 : evidenceResult.missingEvidenceCount());
        report.put("failed_evidence_count", evidenceResult == null ? 0 : evidenceResult.failedEvidenceCount());
        report.put("failed_evidence_summary", evidenceResult == null ? List.of() : evidenceResult.failedEvidenceSummary());
        report.put("provider_evidence_summary", evidenceResult == null
                ? List.of()
                : jsonProviderEvidenceSummary(evidenceResult.providerEvidenceSummary()));
        report.put("masking_status", evidenceResult == null || evidenceResult.maskingPassed() ? "passed" : "failed");
        report.put("failed_verify_summary", result.failedVerifySummary());
        return report;
    }

    private Map<String, Object> jsonFailure(
            String failureCode,
            String category,
            String message,
            List<ContractFinding> findings) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_status", "failed");
        report.put("failure_code", failureCode);
        report.put("category", category);
        report.put("message", message);
        report.put("findings", jsonFindings(findings));
        return report;
    }

    private List<Map<String, Object>> jsonProviderEvidenceSummary(List<ProviderEvidenceSummary> summaries) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (ProviderEvidenceSummary summary : summaries) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("provider_type", summary.providerType());
            value.put("provider_id", summary.providerId());
            value.put("evidence_count", summary.evidenceCount());
            values.add(value);
        }
        return values;
    }

    private List<Map<String, Object>> jsonFindings(List<ContractFinding> findings) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (ContractFinding finding : findings) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("file_path", finding.filePath());
            value.put("field_path", finding.fieldPath());
            value.put("reason", finding.reason());
            value.put("provider_id", finding.providerId());
            value.put("provider_type", finding.providerType());
            value.put("profile", finding.profile());
            value.put("operation", finding.operation());
            value.put("owner_action", finding.ownerAction());
            value.put("failure_code", finding.failureCode());
            value.put("category", finding.category());
            value.put("original_cause", finding.originalCause());
            values.add(value);
        }
        return values;
    }

    String firstFailureCode(List<ContractFinding> findings, String fallback) {
        return findings.stream()
                .map(ContractFinding::failureCode)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private String firstCategory(List<ContractFinding> findings, String fallback) {
        return findings.stream()
                .map(ContractFinding::category)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private String firstOwnerAction(List<ContractFinding> findings, String fallback) {
        return findings.stream()
                .map(ContractFinding::ownerAction)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private boolean printReportJson(PrintStream out, PrintStream err, Map<String, Object> report) {
        try {
            out.println(OBJECT_MAPPER.writeValueAsString(report));
            return true;
        } catch (JsonProcessingException e) {
            err.println("Failed to render JSON report: " + e.getMessage());
            return false;
        }
    }

    String coveragePercent(EvidenceValidationResult result) {
        if (result.testCount() <= 0) {
            return result.valid() ? "100.0" : "0.0";
        }
        double coverage = (100.0 * result.passCount()) / result.testCount();
        return String.format(java.util.Locale.ROOT, "%.1f", coverage);
    }

    void printEvidenceValidationSummary(
            PrintStream out,
            EvidenceValidationResult result,
            String statusLabel) {
        out.println(statusLabel + ": " + (result.valid() ? "passed" : "failed"));
        out.println("suite_id: " + result.suiteId());
        out.println("batch_id: " + result.batchId());
        out.println("run_id: " + result.runId());
        out.println("test_count: " + result.testCount());
        out.println("pass_count: " + result.passCount());
        out.println("fail_count: " + result.failCount());
        out.println("evidence_folder_path: " + result.evidenceDir());
        out.println("missing_evidence_count: " + result.missingEvidenceCount());
        out.println("failed_evidence_count: " + result.failedEvidenceCount());
        printFailedEvidenceSummary(out, result);
        printProviderEvidenceSummary(out, result.providerEvidenceSummary());
        out.println("masking_status: " + (result.maskingPassed() ? "passed" : "failed"));
    }

    private void printEvidenceReportSummary(PrintStream out, EvidenceValidationResult result) {
        out.println("test_count: " + result.testCount());
        out.println("pass_count: " + result.passCount());
        out.println("fail_count: " + result.failCount());
        out.println("evidence_folder_path: " + result.evidenceDir());
        out.println("missing_evidence_count: " + result.missingEvidenceCount());
        out.println("failed_evidence_count: " + result.failedEvidenceCount());
        printFailedEvidenceSummary(out, result);
        printProviderEvidenceSummary(out, result.providerEvidenceSummary());
        out.println("masking_status: " + (result.maskingPassed() ? "passed" : "failed"));
    }

    private void printFailedEvidenceSummary(PrintStream out, EvidenceValidationResult result) {
        out.println("failed_evidence_summary:");
        if (result.failedEvidenceSummary().isEmpty()) {
            out.println("  []");
            return;
        }
        for (String failedEvidence : result.failedEvidenceSummary()) {
            out.println("  - " + failedEvidence);
        }
    }

    private void printProviderEvidenceSummary(PrintStream out, List<ProviderEvidenceSummary> summaries) {
        out.println("provider_evidence_summary:");
        if (summaries.isEmpty()) {
            out.println("  []");
            return;
        }
        for (ProviderEvidenceSummary summary : summaries) {
            out.println("  - provider_type: " + summary.providerType());
            out.println("    provider_id: " + summary.providerId());
            out.println("    evidence_count: " + summary.evidenceCount());
        }
    }

    private List<Object> objectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
