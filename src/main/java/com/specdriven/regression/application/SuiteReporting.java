package com.specdriven.regression.application;

import static com.specdriven.regression.application.SuiteDocuments.mapValue;
import static com.specdriven.regression.application.SuiteDocuments.objectList;
import static com.specdriven.regression.application.SuiteDocuments.readYamlMap;
import static com.specdriven.regression.application.SuiteDocuments.stringValue;

import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.ContractBaselineService.ReportResult;
import com.specdriven.regression.contract.ResultDocumentReader;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.evidence.EvidenceHardeningService.EvidenceValidationResult;
import com.specdriven.regression.report.CanonicalCoverageReportResult;
import com.specdriven.regression.report.CoverageReportService;
import com.specdriven.regression.report.LegacySuiteSummaryReportAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Validates result evidence and projects coverage from canonical runs. */
public final class SuiteReporting {

    private final ContractBaselineService baseline = new ContractBaselineService();
    private final EvidenceHardeningService evidence = new EvidenceHardeningService();
    private final CoverageReportService coverageReportService;

    public SuiteReporting(CoverageReportService coverageReportService) {
        this.coverageReportService = coverageReportService;
    }

    public EvidenceValidationResult validateEvidence(Path result) {
        return evidence.validateResult(result);
    }

    public record CoverageInputs(String directory, String catalog, String selection, String authority,
            String expectedAuthoritySha256) {
        boolean requested() {
            return directory != null || catalog != null || selection != null || authority != null
                    || expectedAuthoritySha256 != null;
        }
    }

    public record Report(ReportResult result, EvidenceValidationResult evidence, CanonicalCoverageReportResult coverage,
            List<Map<String, Object>> timings, Map<String, Object> summary, RuntimeException coverageFailure) {
        public boolean invalidEvidence() {
            return (evidence != null && !evidence.valid()) || (coverage != null && !coverage.valid());
        }
    }

    public Report report(Path root, Path resultPath, CoverageInputs inputs) {
        ReportResult result = baseline.report(resultPath);
        if (!result.valid()) return new Report(result, null, null, List.of(), Map.of(), null);

        EvidenceValidationResult evidenceResult = evidence.hasEvidenceIndexReference(resultPath)
                ? evidence.validateResult(resultPath) : null;
        Map<String, Object> document = readResultMap(resultPath);
        CanonicalCoverageReportResult coverage = null;
        if (!result.suiteId().isBlank() && (inputs.requested() || !legacyCoverage(document))) {
            try {
                Path directory = inputs.directory() == null
                        ? root.resolve("target/coverage")
                                .resolve(result.suiteId().replaceAll("[^A-Za-z0-9._-]", "_"))
                                .resolve(result.batchId().replaceAll("[^A-Za-z0-9._-]", "_"))
                                .resolve(result.runId().replaceAll("[^A-Za-z0-9._-]", "_"))
                                .resolve("report-" + UUID.randomUUID())
                        : root.resolve(inputs.directory()).normalize();
                Path catalog = inputs.catalog() == null ? null : root.resolve(inputs.catalog());
                Path selection = inputs.selection() == null ? null : root.resolve(inputs.selection());
                Path authority;
                try {
                    authority = inputs.authority() == null ? null : root.resolve(inputs.authority());
                } catch (java.nio.file.InvalidPathException invalid) {
                    throw new IllegalArgumentException("COVERAGE_AUTHORITY_INVALID_REFERENCE");
                }
                coverage = coverageReportService.projectCanonical(resultPath, directory, catalog, selection,
                        authority, inputs.expectedAuthoritySha256());
            } catch (RuntimeException failure) {
                return new Report(result, evidenceResult, null, List.of(), Map.of(), failure);
            }
        }
        boolean invalid = (evidenceResult != null && !evidenceResult.valid()) || (coverage != null && !coverage.valid());
        return new Report(result, evidenceResult, coverage, invalid ? List.of() : stepTimings(document), suiteSummaryForReport(resultPath), null);
    }

    private boolean legacyCoverage(Map<String, Object> document) {
        return !document.containsKey("result_contract_version") && !document.containsKey("product_test")
                && ("v0.2".equals(stringValue(document.get("dsl_version")))
                        || (!document.containsKey("dsl_version") && new LegacySuiteSummaryReportAdapter().supports(document)));
    }

    private List<Map<String, Object>> stepTimings(Map<String, Object> resultDocument) {
        List<Map<String, Object>> timings = new ArrayList<>();
        for (Object row : objectList(resultDocument.get("steps"))) {
            Map<String, Object> step = mapValue(row);
            Object duration = step.get("duration_ms");
            if (!(duration instanceof Integer || duration instanceof Long)
                    || ((Number) duration).longValue() < 0
                    || !(step.get("test_case_id") instanceof String testCaseId)
                    || !(step.get("id") instanceof String stepId)
                    || !(step.get("phase") instanceof String phase)
                    || !(step.get("operation") instanceof String operation)) {
                continue;
            }
            Map<String, Object> timing = new LinkedHashMap<>();
            if (step.get("suite_path") instanceof String suitePath) timing.put("suite_path", suitePath);
            timing.put("test_case_id", testCaseId);
            timing.put("step_id", stepId);
            timing.put("phase", phase);
            timing.put("operation", operation);
            timing.put("duration_ms", ((Number) duration).longValue());
            timings.add(timing);
        }
        timings.sort(Comparator.comparingLong((Map<String, Object> timing) -> (long) timing.get("duration_ms")).reversed());
        return timings;
    }

    public Map<String, Object> suiteSummaryForReport(Path resultJson) {
        try {
            Map<String, Object> result = readResultMap(resultJson);
            if (!"v0.3".equals(stringValue(result.get("result_contract_version")))) return Map.of();
            String ref = stringValue(result.get("suite_summary_ref"));
            if (ref.isBlank()) return Map.of();
            return readYamlMap(resultJson.toRealPath().getParent().resolve(ref).normalize().toRealPath());
        } catch (IOException | RuntimeException error) {
            return Map.of();
        }
    }

    private Map<String, Object> readResultMap(Path path) {
        try { return ResultDocumentReader.read(path).fields(); }
        catch (IOException error) { throw new UncheckedIOException("Failed to read bounded result JSON: " + path, error); }
    }
}
