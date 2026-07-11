package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DslV03ProviderRuntimeExecutionCommandTest {

    private static final Path HTTP_MOCK_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml");
    private static final Path MOCK_GROUP =
            Path.of("samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml");

    @Test
    void runV03HttpMockRestClientExecutesProviderRuntime() throws Exception {
        CommandResult run = execute("run", "--suite", HTTP_MOCK_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: HTTP-MOCK-REST-CLIENT-v0.3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: http_mock")
                .contains("provider_type: rest_client")
                .contains("suite_summary_json:");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path summaryJson = extractPath(run.stdout(), "suite_summary_json");
        assertThat(resultJson).isRegularFile();
        assertThat(summaryJson).isRegularFile();
        java.util.Map<String, Object> resultDocument = new ObjectMapper().readValue(
                resultJson.toFile(), new TypeReference<>() {});
        assertThat(resultDocument)
                .containsEntry("result_contract_version", "v0.3")
                .containsEntry("completion_status", "complete")
                .containsEntry("termination_reason", null)
                .containsEntry("suite_summary_ref", "suite_summary.json");
        String canonicalResult = new ObjectMapper().writeValueAsString(resultDocument);
        assertThat(canonicalResult)
                .contains("\"dsl_version\":\"v0.3\"")
                .contains("\"provider_contract\":\"http_mock.v0.3\"")
                .contains("\"provider_contract\":\"rest_client.v0.3\"")
                .contains("\"target\":\"payment_mock\"")
                .contains("\"target\":\"payment_api\"")
                .contains("\"operation\":\"load_stubs,reset_mock\"")
                .contains("\"operation\":\"http_request\"")
                .contains("\"evidence_index_ref\":\"evidence_index.yaml\"")
                .doesNotContain("provider_instance");
        String summary = Files.readString(summaryJson);
        assertThat(summary)
                .contains("\"suite_id\" : \"HTTP-MOCK-REST-CLIENT-v0.3\"")
                .contains("\"test_case_count\" : 1")
                .contains("\"pass_count\" : 1");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: HTTP-MOCK-REST-CLIENT-v0.3")
                .contains("status: passed")
                .contains("provider_results_count: 2")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("provider_type: http_mock")
                .contains("provider_type: rest_client");
    }

    @Test
    void suiteGroupFinalizesEveryV03ChildLeafWithSharedBatchIdentity() throws Exception {
        CommandResult run = execute("run", "--suite", MOCK_GROUP.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        String parentBatchId = run.stdout().lines()
                .filter(line -> line.startsWith("batch_id: "))
                .map(line -> line.substring("batch_id: ".length()))
                .findFirst().orElseThrow();
        Path parentSummary = extractPath(run.stdout(), "suite_summary_json");
        try (var paths = Files.walk(parentSummary.getParent().resolve("children"))) {
            java.util.List<Path> childSummaries = paths
                    .filter(path -> path.getFileName().toString().equals("suite_summary.json"))
                    .toList();
            assertThat(childSummaries).hasSize(3);
            for (Path childSummary : childSummaries) {
                java.util.Map<String, Object> summary = new ObjectMapper().readValue(
                        childSummary.toFile(), new TypeReference<>() {});
                assertThat(summary).containsEntry("batch_id", parentBatchId);
                Path childResult = childSummary.resolveSibling("result.json");
                java.util.Map<String, Object> result = new ObjectMapper().readValue(
                        childResult.toFile(), new TypeReference<>() {});
                assertThat(result)
                        .containsEntry("suite_id", summary.get("suite_id"))
                        .containsEntry("batch_id", summary.get("batch_id"))
                        .containsEntry("run_id", summary.get("run_id"))
                        .containsEntry("suite_summary_ref", "suite_summary.json");
            }
        }
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> Path.of(line.substring((key + ": ").length()).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path for " + key + " in:\n" + stdout));
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
