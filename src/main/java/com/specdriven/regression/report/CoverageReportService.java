package com.specdriven.regression.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class CoverageReportService {

    public CoverageReportResult generate(Path packageRoot, String runId) {
        Path runDir = packageRoot.resolve("evidence/runs").resolve(runId);
        Path runYaml = runDir.resolve("run.yaml");
        boolean runMissing = !Files.exists(runYaml);
        Map<String, Object> run = runMissing
                ? Map.of("rp_id", packageRoot.getFileName().toString(), "status", "missing_evidence")
                : readYamlMap(runYaml);
        List<Map<?, ?>> acceptanceCriteria = acceptanceCriteria(packageRoot.resolve("acceptance_criteria.md"));
        List<Map<String, Object>> approvedTests = approvedTests(packageRoot.resolve("tests/approved"));
        List<String> denominatorAcIds = coverageDenominatorAcIds(acceptanceCriteria);
        int totalAutomatable = denominatorAcIds.size();
        String runStatus = stringValue(run.get("status"));
        String runAcId = stringValue(run.get("ac_id"));
        String runTestCaseId = stringValue(run.get("test_case_id"));
        boolean hasTraceableApprovedTest = approvedTests.stream()
                .anyMatch(test -> runTestCaseId.equals(stringValue(test.get("test_case_id")))
                        && runAcId.equals(stringValue(test.get("ac_id"))));
        boolean runCoversAc =
                "passed".equals(runStatus) && isAutomatable(acceptanceCriteria, runAcId) && hasTraceableApprovedTest;
        List<String> coveredAcIds = runCoversAc ? List.of(runAcId) : List.of();
        int covered = coveredAcIds.size();
        double coveragePercent = totalAutomatable == 0 ? 0.0 : covered * 100.0 / totalAutomatable;
        List<String> gaps = new ArrayList<>();
        gaps.addAll(unapprovedExclusionGaps(acceptanceCriteria));
        if (runMissing) {
            gaps.add("missing_evidence: " + packageRoot.relativize(runYaml));
        } else if (!"passed".equals(runStatus)) {
            gaps.add("run_status: " + runStatus);
        }
        if (!runMissing && !hasTraceableApprovedTest) {
            gaps.add("missing_traceability: " + runTestCaseId + " -> " + runAcId);
        }
        if (!runMissing) {
            gaps.addAll(uncoveredAcGaps(denominatorAcIds, coveredAcIds));
        }
        List<String> failureDetails = runMissing ? List.of() : assertionFailureDetails(runDir, run);
        boolean reviewReady = gaps.isEmpty() && totalAutomatable > 0 && covered == totalAutomatable;
        Path reviewDir = packageRoot.resolve("evidence/review").resolve(runId);
        writeReports(
                reviewDir,
                packageRoot,
                runId,
                run,
                covered,
                totalAutomatable,
                coveragePercent,
                reviewReady,
                gaps,
                failureDetails);
        return new CoverageReportResult(
                reviewReady,
                covered,
                totalAutomatable,
                coveragePercent,
                reviewDir,
                List.copyOf(gaps));
    }

    private void writeReports(
            Path reviewDir,
            Path packageRoot,
            String runId,
            Map<String, Object> run,
            int covered,
            int totalAutomatable,
            double coveragePercent,
            boolean reviewReady,
            List<String> gaps,
            List<String> failureDetails) {
        try {
            Files.createDirectories(reviewDir);
            Files.writeString(reviewDir.resolve("coverage_report.yaml"), """
                    rp_id: %s
                    run_id: %s
                    covered: %s
                    total_automatable: %s
                    coverage_percent: %.1f
                    review_ready: %s
                    """.formatted(
                    stringValue(run.get("rp_id")),
                    runId,
                    covered,
                    totalAutomatable,
                    coveragePercent,
                    reviewReady));
            Files.writeString(reviewDir.resolve("traceability_report.yaml"), """
                    traceability:
                      - rp_id: %s
                        ac_id: %s
                        test_case_id: %s
                        run_id: %s
                        evidence_ref: %s
                    """.formatted(
                    stringValue(run.get("rp_id")),
                    stringValue(run.get("ac_id")),
                    stringValue(run.get("test_case_id")),
                    runId,
                    packageRoot.relativize(packageRoot.resolve("evidence/runs").resolve(runId))));
            Files.writeString(reviewDir.resolve("evidence_index.md"), """
                    # Evidence Index

                    - RP: `%s`
                    - AC: `%s`
                    - Test case: `%s`
                    - Run: `%s`
                    - Run evidence: `%s`
                    - Coverage: `coverage_report.yaml`
                    - Traceability: `traceability_report.yaml`
                    - Failure summary: `failure_summary.yaml`
                    - Release review summary: `release_review_summary.yaml`
                    """.formatted(
                    stringValue(run.get("rp_id")),
                    stringValue(run.get("ac_id")),
                    stringValue(run.get("test_case_id")),
                    runId,
                    packageRoot.relativize(packageRoot.resolve("evidence/runs").resolve(runId))));
            Files.writeString(reviewDir.resolve("failure_summary.yaml"), """
                    run_id: %s
                    unresolved_failures: %s
                    gaps:
                    %s
                    failure_details:
                    %s
                    """.formatted(runId, gaps.size(), gapYaml(gaps), gapYaml(failureDetails)));
            Files.writeString(reviewDir.resolve("release_review_summary.yaml"), """
                    rp_id: %s
                    run_id: %s
                    review_ready: %s
                    coverage_percent: %.1f
                    unresolved_failures: %s
                    """.formatted(
                    stringValue(run.get("rp_id")),
                    runId,
                    reviewReady,
                    coveragePercent,
                    gaps.size()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write coverage report package.", e);
        }
    }

    private String gapYaml(List<String> gaps) {
        if (gaps.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (String gap : gaps) {
            builder.append("  - ").append(gap).append("\n");
        }
        return builder.toString();
    }

    private List<String> coverageDenominatorAcIds(List<Map<?, ?>> acceptanceCriteria) {
        List<String> acIds = new ArrayList<>();
        for (Map<?, ?> item : acceptanceCriteria) {
            String classification = stringValue(item.get("classification"));
            if ("automatable".equals(classification)
                    || "partial".equals(classification)
                    || isUnapprovedExclusion(item)) {
                acIds.add(stringValue(item.get("ac_id")));
            }
        }
        return List.copyOf(acIds);
    }

    private List<String> uncoveredAcGaps(List<String> denominatorAcIds, List<String> coveredAcIds) {
        List<String> gaps = new ArrayList<>();
        for (String acId : denominatorAcIds) {
            if (!coveredAcIds.contains(acId)) {
                gaps.add("uncovered_ac: " + acId);
            }
        }
        return gaps;
    }

    private List<String> unapprovedExclusionGaps(List<Map<?, ?>> acceptanceCriteria) {
        List<String> gaps = new ArrayList<>();
        for (Map<?, ?> item : acceptanceCriteria) {
            if (isUnapprovedExclusion(item)) {
                gaps.add("unapproved_exclusion: " + stringValue(item.get("ac_id")));
            }
        }
        return gaps;
    }

    private List<String> assertionFailureDetails(Path runDir, Map<String, Object> run) {
        if (!"failed".equals(stringValue(run.get("assertion_status")))) {
            return List.of();
        }
        String assertionRef = stringValue(run.get("assertions"));
        if (assertionRef.isBlank()) {
            return List.of("assertion_status: failed", "assertion_evidence: missing");
        }
        Path assertionPath = runDir.resolve(assertionRef).normalize();
        if (!Files.exists(assertionPath)) {
            return List.of("assertion_status: failed", "assertion_evidence: " + assertionRef + " missing");
        }
        Object entries = readYamlMap(assertionPath).get("assertions");
        if (!(entries instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> assertion)) {
            return List.of("assertion_status: failed", "assertion_evidence: " + assertionRef + " unreadable");
        }
        List<String> details = new ArrayList<>();
        details.add("assertion_status: " + stringValue(assertion.get("status")));
        details.add("assertion_evidence: " + assertionRef);
        details.add("expected_ref: " + stringValue(assertion.get("expected_ref")));
        details.add("actual_ref: " + stringValue(assertion.get("actual_ref")));
        details.add("decision_rule: " + stringValue(assertion.get("decision_rule")));
        details.add("diff_summary: " + stringValue(assertion.get("diff_summary")));
        return details;
    }

    private boolean isUnapprovedExclusion(Map<?, ?> item) {
        String classification = stringValue(item.get("classification"));
        if (!"manual_only".equals(classification) && !"waived".equals(classification)) {
            return false;
        }
        Object exclusion = item.get("exclusion_record");
        if (!(exclusion instanceof Map<?, ?> record)) {
            return true;
        }
        return stringValue(record.get("approved_by")).isBlank()
                || stringValue(record.get("approved_at")).isBlank();
    }

    private boolean isAutomatable(List<Map<?, ?>> acceptanceCriteria, String acId) {
        return acceptanceCriteria.stream()
                .anyMatch(item -> acId.equals(stringValue(item.get("ac_id")))
                        && "automatable".equals(item.get("classification")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> acceptanceCriteria(Path path) {
        Map<String, Object> document = readYamlMap(path);
        Object entries = document.get("acceptance_criteria");
        if (entries instanceof List<?> list) {
            List<Map<?, ?>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(map);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> approvedTests(Path approvedDir) {
        if (!Files.isDirectory(approvedDir)) {
            return List.of();
        }
        try (var paths = Files.list(approvedDir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .map(this::readYamlMap)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read approved tests for report.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read report source artifact: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
