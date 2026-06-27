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

    public CoverageReportResult generateBatch(Path packageRoot, String batchId) {
        Path batchDir = packageRoot.resolve("evidence/batches").resolve(batchId);
        Path batchYaml = batchDir.resolve("batch.yaml");
        boolean batchMissing = !Files.exists(batchYaml);
        Map<String, Object> batch = batchMissing
                ? Map.of("rp_id", packageRoot.getFileName().toString(), "status", "missing_evidence")
                : readYamlMap(batchYaml);
        List<RunEvidence> runs = batchMissing ? List.of() : runsFromBatch(packageRoot, batch);
        List<Map<?, ?>> acceptanceCriteria = acceptanceCriteria(packageRoot.resolve("acceptance_criteria.md"));
        List<Map<String, Object>> approvedTests = approvedTests(packageRoot.resolve("tests/approved"));
        List<String> denominatorAcIds = coverageDenominatorAcIds(acceptanceCriteria);
        int totalAutomatable = denominatorAcIds.size();
        List<String> coveredAcIds = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        List<String> failureDetails = new ArrayList<>();
        gaps.addAll(unapprovedExclusionGaps(acceptanceCriteria));
        if (batchMissing) {
            String fieldPath = packageRoot.relativize(batchYaml).toString();
            gaps.add(reportGap(
                    "missing_evidence",
                    fieldPath,
                    "Restore or regenerate the missing batch evidence before release review.",
                    "missing_evidence: " + fieldPath));
        }
        for (RunEvidence evidence : runs) {
            Map<String, Object> run = evidence.run();
            String runStatus = stringValue(run.get("status"));
            String runAcId = stringValue(run.get("ac_id"));
            String runTestCaseId = stringValue(run.get("test_case_id"));
            boolean hasTraceableApprovedTest = approvedTests.stream()
                    .anyMatch(test -> runTestCaseId.equals(stringValue(test.get("test_case_id")))
                            && runAcId.equals(stringValue(test.get("ac_id"))));
            if (evidence.missing()) {
                String fieldPath = packageRoot.relativize(evidence.runDir().resolve("run.yaml")).toString();
                gaps.add(reportGap(
                        "missing_evidence",
                        fieldPath,
                        "Restore or regenerate the missing run evidence before release review.",
                        "run_id: " + evidence.runId(),
                        "test_case_id: " + runTestCaseId,
                        "ac_id: " + runAcId,
                        "missing_evidence: " + fieldPath));
            } else if (!"passed".equals(runStatus)) {
                String fieldPath = packageRoot.relativize(evidence.runDir().resolve("run.yaml")).toString();
                gaps.add(reportGap(
                        "run_status_not_passed",
                        fieldPath,
                        "Resolve the non-passing run before claiming release review readiness.",
                        "run_id: " + evidence.runId(),
                        "test_case_id: " + runTestCaseId,
                        "ac_id: " + runAcId,
                        "run_status: " + runStatus));
            }
            if (!evidence.missing() && !hasTraceableApprovedTest) {
                gaps.add(reportGap(
                        "missing_traceability",
                        "tests/approved",
                        "Check approved test traceability for the reported test case and AC.",
                        "test_case_id: " + runTestCaseId,
                        "ac_id: " + runAcId,
                        "missing_traceability: " + runTestCaseId + " -> " + runAcId));
            }
            if ("passed".equals(runStatus)
                    && isAutomatable(acceptanceCriteria, runAcId)
                    && hasTraceableApprovedTest
                    && !coveredAcIds.contains(runAcId)) {
                coveredAcIds.add(runAcId);
            }
            failureDetails.addAll(assertionFailureDetails(evidence.runDir(), run));
        }
        if (!batchMissing) {
            gaps.addAll(uncoveredAcGaps(denominatorAcIds, coveredAcIds));
        }
        int covered = coveredAcIds.size();
        double coveragePercent = totalAutomatable == 0 ? 0.0 : covered * 100.0 / totalAutomatable;
        boolean reviewReady = gaps.isEmpty() && totalAutomatable > 0 && covered == totalAutomatable;
        Path reviewDir = packageRoot.resolve("evidence/review").resolve(batchId);
        writeBatchReports(
                reviewDir,
                packageRoot,
                batchId,
                batch,
                runs,
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
            String fieldPath = packageRoot.relativize(runYaml).toString();
            gaps.add(reportGap(
                    "missing_evidence",
                    fieldPath,
                    "Restore or regenerate the missing run evidence before release review.",
                    "missing_evidence: " + fieldPath));
        } else if (!"passed".equals(runStatus)) {
            String fieldPath = packageRoot.relativize(runYaml).toString();
            gaps.add(reportGap(
                    "run_status_not_passed",
                    fieldPath,
                    "Resolve the non-passing run before claiming release review readiness.",
                    "test_case_id: " + runTestCaseId,
                    "ac_id: " + runAcId,
                    "run_status: " + runStatus));
        }
        if (!runMissing && !hasTraceableApprovedTest) {
            gaps.add(reportGap(
                    "missing_traceability",
                    "tests/approved",
                    "Check approved test traceability for the reported test case and AC.",
                    "test_case_id: " + runTestCaseId,
                    "ac_id: " + runAcId,
                    "missing_traceability: " + runTestCaseId + " -> " + runAcId));
        }
        if (!runMissing) {
            gaps.addAll(uncoveredAcGaps(denominatorAcIds, coveredAcIds));
        }
        if (!runMissing && gaps.isEmpty() && totalAutomatable > 0 && covered == totalAutomatable) {
            gaps.add(reportGap(
                    "batch_required_for_release_coverage",
                    "evidence/runs/" + runId + "/run.yaml",
                    "Generate release coverage with --batch-id so RP coverage uses batch-level evidence.",
                    "run_id: " + runId,
                    "batch_required_for_release_coverage: " + runId));
        }
        List<String> failureDetails = runMissing ? List.of() : assertionFailureDetails(runDir, run);
        boolean reviewReady = false;
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

    private void writeBatchReports(
            Path reviewDir,
            Path packageRoot,
            String batchId,
            Map<String, Object> batch,
            List<RunEvidence> runs,
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
                    batch_id: %s
                    covered: %s
                    total_automatable: %s
                    coverage_percent: %.1f
                    review_ready: %s
                    """.formatted(
                    stringValue(batch.get("rp_id")),
                    batchId,
                    covered,
                    totalAutomatable,
                    coveragePercent,
                    reviewReady));
            Files.writeString(
                    reviewDir.resolve("traceability_report.yaml"),
                    "traceability:\n" + traceabilityYaml(packageRoot, batchId, runs));
            Files.writeString(reviewDir.resolve("evidence_index.md"), """
                    # Evidence Index

                    - RP: `%s`
                    - Batch: `%s`
                    - Batch evidence: `%s`
                    %s
                    - Coverage: `coverage_report.yaml`
                    - Traceability: `traceability_report.yaml`
                    - Failure summary: `failure_summary.yaml`
                    - Release review summary: `release_review_summary.yaml`
                    """.formatted(
                    stringValue(batch.get("rp_id")),
                    batchId,
                    packageRoot.relativize(packageRoot.resolve("evidence/batches").resolve(batchId)),
                    evidenceIndexRuns(packageRoot, runs)));
            Files.writeString(reviewDir.resolve("failure_summary.yaml"), """
                    batch_id: %s
                    unresolved_failures: %s
                    gaps:
                    %s
                    failure_details:
                    %s
                    """.formatted(batchId, gaps.size(), gapYaml(gaps), gapYaml(failureDetails)));
            Files.writeString(reviewDir.resolve("release_review_summary.yaml"), """
                    rp_id: %s
                    batch_id: %s
                    review_ready: %s
                    coverage_percent: %.1f
                    unresolved_failures: %s
                    """.formatted(
                    stringValue(batch.get("rp_id")),
                    batchId,
                    reviewReady,
                    coveragePercent,
                    gaps.size()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write batch coverage report package.", e);
        }
    }

    private String traceabilityYaml(Path packageRoot, String batchId, List<RunEvidence> runs) {
        if (runs.isEmpty()) {
            return "  []\n";
        }
        StringBuilder builder = new StringBuilder();
        for (RunEvidence evidence : runs) {
            Map<String, Object> run = evidence.run();
            builder.append("  - rp_id: ").append(stringValue(run.get("rp_id"))).append("\n");
            builder.append("    ac_id: ").append(stringValue(run.get("ac_id"))).append("\n");
            builder.append("    test_case_id: ").append(stringValue(run.get("test_case_id"))).append("\n");
            builder.append("    batch_id: ").append(batchId).append("\n");
            builder.append("    run_id: ").append(evidence.runId()).append("\n");
            builder.append("    evidence_ref: ")
                    .append(packageRoot.relativize(packageRoot.resolve("evidence/runs").resolve(evidence.runId())))
                    .append("\n");
        }
        return builder.toString();
    }

    private String evidenceIndexRuns(Path packageRoot, List<RunEvidence> runs) {
        if (runs.isEmpty()) {
            return "- Runs: `[]`";
        }
        StringBuilder builder = new StringBuilder();
        for (RunEvidence evidence : runs) {
            Map<String, Object> run = evidence.run();
            builder.append("- AC: `").append(stringValue(run.get("ac_id"))).append("`\n");
            builder.append("                    - Test case: `")
                    .append(stringValue(run.get("test_case_id")))
                    .append("`\n");
            builder.append("                    - Run: `").append(evidence.runId()).append("`\n");
            builder.append("                    - Run evidence: `")
                    .append(packageRoot.relativize(packageRoot.resolve("evidence/runs").resolve(evidence.runId())))
                    .append("`\n");
        }
        return builder.toString().stripTrailing();
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
                gaps.add(reportGap(
                        "uncovered_ac",
                        "acceptance_criteria.md#" + acId,
                        "Add or fix a passed approved test run for this automatable AC.",
                        "ac_id: " + acId,
                        "uncovered_ac: " + acId));
            }
        }
        return gaps;
    }

    private List<String> unapprovedExclusionGaps(List<Map<?, ?>> acceptanceCriteria) {
        List<String> gaps = new ArrayList<>();
        for (Map<?, ?> item : acceptanceCriteria) {
            if (isUnapprovedExclusion(item)) {
                String acId = stringValue(item.get("ac_id"));
                gaps.add(reportGap(
                        "unapproved_exclusion",
                        "acceptance_criteria.md#" + acId,
                        "Add an approved manual-only or waiver exclusion record, or make the AC automatable.",
                        "ac_id: " + acId,
                        "unapproved_exclusion: " + acId));
            }
        }
        return gaps;
    }

    private String reportGap(String reason, String fieldPath, String ownerAction, String... extraLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("ap: Evidence and Reporting\n");
        builder.append("    field_path: ").append(fieldPath).append("\n");
        builder.append("    reason: ").append(reason).append("\n");
        for (String line : extraLines) {
            if (line != null && !line.isBlank()) {
                builder.append("    ").append(line).append("\n");
            }
        }
        builder.append("    owner_action: ").append(ownerAction);
        return builder.toString();
    }

    private List<RunEvidence> runsFromBatch(Path packageRoot, Map<String, Object> batch) {
        Object entries = batch.get("runs");
        if (!(entries instanceof List<?> list)) {
            return List.of();
        }
        List<RunEvidence> result = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> row)) {
                continue;
            }
            String runId = stringValue(row.get("run_id"));
            if (runId.isBlank()) {
                continue;
            }
            Path runDir = packageRoot.resolve("evidence/runs").resolve(runId);
            Path runYaml = runDir.resolve("run.yaml");
            boolean missing = !Files.exists(runYaml);
            Map<String, Object> run = missing ? missingRunFromBatchRow(batch, row, runId) : readYamlMap(runYaml);
            result.add(new RunEvidence(runId, runDir, run, missing));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> missingRunFromBatchRow(Map<String, Object> batch, Map<?, ?> row, String runId) {
        Map<String, Object> run = new java.util.LinkedHashMap<>();
        run.put("run_id", runId);
        run.put("batch_id", stringValue(batch.get("batch_id")));
        run.put("rp_id", stringValue(batch.get("rp_id")));
        run.put("test_case_id", stringValue(row.get("test_case_id")));
        run.put("ac_id", stringValue(row.get("ac_id")));
        run.put("status", "missing_evidence");
        return run;
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
                        && ("automatable".equals(item.get("classification"))
                                || "partial".equals(item.get("classification"))));
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

    private record RunEvidence(String runId, Path runDir, Map<String, Object> run, boolean missing) {
    }
}
