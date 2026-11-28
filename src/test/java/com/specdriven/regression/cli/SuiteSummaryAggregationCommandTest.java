package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.application.SuiteReporting;
import com.specdriven.regression.contract.ContractBaselineService.ReportResult;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.evidence.EvidenceHardeningService.EvidenceValidationResult;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.specdriven.regression.report.CoverageReportService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteSummaryAggregationCommandTest {
    private static final Path SUITE = Path.of(
            "samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void v03SuiteGroupWritesCanonicalResultSummaryAndMergedEvidence() throws Exception {
        CommandResult run = execute("run", "--suite", SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout()).contains("result_json:").contains("suite_summary_json:");
        Path resultPath = path(run.stdout(), "result_json");
        Path summaryPath = path(run.stdout(), "suite_summary_json");
        Map<String, Object> result = mapper.readValue(resultPath.toFile(), new TypeReference<>() {});
        Map<String, Object> summary = mapper.readValue(summaryPath.toFile(), new TypeReference<>() {});
        assertThat(result).containsEntry("result_contract_version", "v0.3")
                .containsEntry("suite_id", "MOCK-SERVER-CROSS-VERIFY-v0.3")
                .containsEntry("suite_summary_ref", "suite_summary.json");
        assertThat((List<?>) result.get("test_results")).isNotEmpty().allSatisfy(item ->
                assertThat((Map<String, Object>) item).containsKeys("suite_path", "test_result_id"));
        assertThat(summary).containsEntry("suite_summary_version", "v0.3")
                .containsEntry("batch_id", result.get("batch_id"))
                .containsEntry("run_id", result.get("run_id"));
        List<Map<String, Object>> children = (List<Map<String, Object>>) summary.get("children");
        assertThat(children).hasSize(3).allSatisfy(child ->
                assertThat(child.get("batch_id")).isEqualTo(result.get("batch_id")));
        Path evidenceIndex = resultPath.getParent().resolve(result.get("evidence_index_ref").toString());
        assertThat(evidenceIndex).isRegularFile();
        assertThat(Files.readString(evidenceIndex)).contains("aggregation::execution-log");

        CommandResult report = execute("report", "--result", resultPath.toString(), "--format", "json");
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(mapper.readTree(report.stdout()).path("report_status").asText()).isEqualTo("review_ready");

        Path coverage = Path.of(mapper.readTree(report.stdout()).path("coverage_json").asText());
        var childTraces = mapper.readTree(coverage.toFile()).at("/run_traceability/child_runs");
        assertThat(childTraces.size()).isEqualTo(3);
        for (var child : childTraces) {
            Path childResult = coverage.getParent().resolve(child.at("/canonical_result/path").asText()).normalize();
            assertThat(childResult).isRegularFile();
            assertThat(child.path("run_id").asText()).isEqualTo(mapper.readTree(childResult.toFile()).path("run_id").asText());
            assertThat(child.at("/cases/0/source_sha256").asText()).matches("[0-9a-f]{64}");
        }

        CommandResult yamlReport = execute("report", "--result", resultPath.toString());
        assertThat(yamlReport.exit()).as(yamlReport.stderr() + yamlReport.stdout()).isZero();
        assertThat(yamlReport.stdout())
                .contains("children:")
                .contains("child_suite_id:")
                .contains("status:")
                .contains("run_id:");
    }

    @Test
    void rendersAggregationErrorsAndSuppressesUnavailableSummary() throws Exception {
        Path result = tempDir.resolve("result.json");
        Path summary = tempDir.resolve("suite_summary.json");
        Files.writeString(result, mapper.writeValueAsString(Map.of(
                "result_contract_version", "v0.3",
                "suite_summary_ref", summary.getFileName().toString())));
        Files.writeString(summary, mapper.writeValueAsString(Map.of(
                "completion_status", "complete",
                "termination_reason", "",
                "duration_ms", 1,
                "self_summary", Map.of(),
                "child_aggregate_summary", Map.of(),
                "total_summary", Map.of(),
                "children", List.of(),
                "aggregation_errors", List.of(Map.of(
                        "child_suite_id", "invalid-child",
                        "failure_code", "INVALID_CHILD_SUITE",
                        "owner_action", "Fix the child suite manifest.")))));

        assertThat(renderSuiteSummarySections(result))
                .contains("aggregation_errors:")
                .contains("child_suite_id: invalid-child")
                .contains("failure_code: INVALID_CHILD_SUITE")
                .contains("owner_action: Fix the child suite manifest.");

        Files.delete(summary);
        assertThat(renderSuiteSummarySections(result)).isEmpty();

        Files.writeString(result, mapper.writeValueAsString(Map.of(
                "result_contract_version", "v0.3",
                "suite_summary_ref", "")));
        assertThat(renderSuiteSummarySections(result)).isEmpty();
        assertThat(renderJsonReportWithoutEvidence()).containsEntry("report_status", "passed");
        assertThat(firstFailureCode()).isEqualTo("VALIDATION_INVALID_TEST_CASE");
        assertThat(coveragePercentForEmptyEvidence()).isEqualTo("100.0");
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private String renderSuiteSummarySections(Path result) {
        var reporting = new SuiteReporting(new CoverageReportService());
        var stdout = new ByteArrayOutputStream();
        new ReportOutput().printSuiteSummaryReportSections(new PrintStream(stdout), reporting.suiteSummaryForReport(result));
        return stdout.toString();
    }

    private Map<String, Object> renderJsonReportWithoutEvidence() {
        return new ReportOutput().jsonReport(new ReportResult(
                true, false, "", "", "", "", "passed", "local", 0, 0, false, List.of(), List.of()), null);
    }

    private String firstFailureCode() {
        ContractFinding finding = new ContractFinding(
                "test_case.yaml", "verify[0]", "invalid_test_case", "", "", "", "", "Fix the test case.");
        return new ReportOutput().firstFailureCode(List.of(finding), "FALLBACK");
    }

    private String coveragePercentForEmptyEvidence() {
        var evidence = new EvidenceValidationResult(
                true, "suite", "batch", "run", 0, 0, 0, 0, 0, tempDir, 0, 0, true, List.of(), List.of(), List.of());
        return new ReportOutput().coveragePercent(evidence);
    }

    private Path path(String stdout, String key) {
        return stdout.lines().filter(line -> line.startsWith(key + ": "))
                .map(line -> Path.of(line.substring(key.length() + 2).trim()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + key + " in:\n" + stdout));
    }

    private record CommandResult(int exit, String stdout, String stderr) {}
}
