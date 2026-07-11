package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteSummaryAggregationCommandTest {
    private static final Path SUITE = Path.of(
            "samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

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
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path path(String stdout, String key) {
        return stdout.lines().filter(line -> line.startsWith(key + ": "))
                .map(line -> Path.of(line.substring(key.length() + 2).trim()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + key + " in:\n" + stdout));
    }

    private record CommandResult(int exit, String stdout, String stderr) {}
}
