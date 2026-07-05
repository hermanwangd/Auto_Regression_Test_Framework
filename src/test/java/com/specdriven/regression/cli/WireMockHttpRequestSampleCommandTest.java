package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireMockHttpRequestSampleCommandTest {

    private static final Path SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml");
    private static final Path FAILURE_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest_failure.yaml");
    private static final Path BOUNDARY_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest_boundary.yaml");

    @TempDir
    Path tempDir;

    @Test
    void sampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/test_case.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/provider_instances/wiremock_payment_api.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/provider_instances/payment_api_client.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/provider_instances/runtime_mode_sample__payment_api_client_mock.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/provider_instances/runtime_mode_sample__payment_api_client_stub.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/execution_profiles/local_wiremock_http.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/environment_bindings/local_wiremock_http.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/fixtures/wiremock/payment_success_stub.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/fixtures/wiremock/payment_empty_response_stub.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/fixtures/payment_request.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/fixtures/payment_empty_request.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/expected_results/payment_response.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest_failure.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/test_case_failure.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest_boundary.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/test_case_boundary.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void sampleValidatesWireMockAndHttpRequestProviderContracts() {
        CommandResult result = execute("validate", "--suite", SUITE.toString());

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: WIREMOCK-HTTP-REQUEST-SAMPLE-v0.2")
                .contains("wiremock-payment-api")
                .contains("payment-api-client")
                .contains("wiremock_http_mock")
                .contains("rest_client");
    }

    @Test
    void sampleDryRunResolvesMockAndHttpClientTargetsWithoutRuntimeDispatch() {
        CommandResult result = execute("run", "--suite", SUITE.toString(), "--dry-run");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("target: payment_mock")
                .contains("provider_id: wiremock-payment-api")
                .contains("provider_type: wiremock_http_mock")
                .contains("target: payment_api")
                .contains("provider_id: payment-api-client")
                .contains("provider_type: rest_client")
                .contains("runtime_mode: mock")
                .contains("runtime_mode: native");
    }

    @Test
    void happyPathRunsWireMockAndHttpRequestProvidersAndReportConsumesResult() {
        CommandResult run = execute("run", "--suite", SUITE.toString(), "--profile", "local_wiremock_http");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: wiremock_http_mock,rest_client")
                .contains("provider_ids: wiremock-payment-api,payment-api-client")
                .contains("evidence_classification: framework_provider_capability_only");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertThat(evidenceDir.resolve("provider-evidence/wiremock/request_journal.json")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/http/response.json")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/payment_response_status.yaml")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/payment_response_body.json")).isRegularFile();
        assertThat(read(resultJson))
                .contains("\"status\": \"passed\"")
                .contains("\"provider_type\": \"wiremock_http_mock\"")
                .contains("\"provider_type\": \"rest_client\"")
                .contains("\"response.status\": 201")
                .contains("\"response.body\"");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: WIREMOCK-HTTP-REQUEST-SAMPLE-v0.2")
                .contains("status: passed")
                .contains("provider_results_count: 2");
    }

    @Test
    void failurePathProducesFailedResultAndAssertionEvidence() {
        CommandResult run = execute("run", "--suite", FAILURE_SUITE.toString(), "--profile", "local_wiremock_http");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: wiremock_http_mock,rest_client");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"payment_response_status\"")
                .contains("\"payment_response_body\"");
        assertThat(evidenceDir.resolve("assertions/payment_response_status.yaml")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/payment_response_body.json")).isRegularFile();
    }

    @Test
    void boundaryPathAllowsEmptyRequestBodyAndNoContentResponse() {
        CommandResult run = execute("run", "--suite", BOUNDARY_SUITE.toString(), "--profile", "local_wiremock_http");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"passed\"")
                .contains("\"response.status\": 204")
                .contains("\"response.body\": \"\"");
        assertThat(read(evidenceDir.resolve("provider-evidence/http/response.json")))
                .contains("\"status\": 204")
                .contains("\"body\": \"\"");
    }

    @Test
    void failurePathBlocksBeforeRuntimeWhenHttpRequestBodyRefIsMissing() throws Exception {
        Path suite = mutableWireMockHttpRequest();
        Files.delete(suite.getParent().resolve("fixtures/payment_request.json"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock_http");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unresolved_artifact_ref")
                .contains("data.payment_request.ref");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
    }

    private Path mutableWireMockHttpRequest() throws IOException {
        Path target = tempDir.resolve("wiremock_http_request_" + System.nanoTime());
        copyDirectory(Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock"), target);
        return target.resolve("suite_manifest.yaml");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).trim())
                .map(Path::of)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path line for " + key + " in:\n" + stdout));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
