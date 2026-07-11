package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireMockProviderCapabilityCommandTest {

    private static final Path WIREMOCK_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void wireMockSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/suite_manifest.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/provider_instances/wiremock_payment_api.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/env_profiles/local_wiremock.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/fixtures/payment_success_stub.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/fixtures/payment_failure_stub.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/fixtures/request_input.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/expected_results/expected_request.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/result/expected_result_shape.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/evidence/expected_evidence_index.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void wireMockSuiteValidatesThroughPublicCli() {
        CommandResult result = execute("validate", "--suite", WIREMOCK_SUITE.toString());

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: WIREMOCK-CAPABILITY-v0.2")
                .contains("wiremock-payment-api")
                .contains("wiremock_http_mock");
    }

    @Test
    void wireMockSuiteRunsProviderRuntimeAndReportConsumesGeneratedResult() {
        CommandResult run = execute("run", "--suite", WIREMOCK_SUITE.toString(), "--profile", "local_wiremock");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: wiremock_http_mock")
                .contains("provider_id: wiremock-payment-api")
                .contains("base_url: http://localhost:")
                .contains("evidence_classification: framework_provider_capability_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertEvidenceFilesExist(evidenceDir);

        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"suite_id\": \"WIREMOCK-CAPABILITY-v0.2\"")
                .contains("\"provider_type\": \"wiremock_http_mock\"")
                .contains("\"provider_id\": \"wiremock-payment-api\"")
                .contains("\"status\": \"passed\"")
                .contains("\"base_url\"")
                .contains("\"request_journal\"")
                .contains("\"server_log\"")
                .contains("\"release_evidence_eligible\": false");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: WIREMOCK-CAPABILITY-v0.2")
                .contains("batch_id: BATCH-WIREMOCK-")
                .contains("run_id: RUN-WIREMOCK-")
                .contains("test_case_id: WIREMOCK-CAPABILITY-TC-001")
                .contains("status: passed");
    }

    @Test
    void wireMockSuiteConsumesExternalBaseUrlBinding() throws Exception {
        Path suite = mutableWireMock();
        Path envProfile = suite.getParent().resolve("env_profiles/local_wiremock.yaml");
        WireMockServer externalWireMock = new WireMockServer(options().dynamicPort());
        externalWireMock.start();
        try {
            String originalEnvProfile = read(envProfile);
            String updatedEnvProfile = originalEnvProfile.replace(
                    """
                        runtime_mode: mock
                        bindings:
                          port_strategy: dynamic
                          mappings_ref:
                            ref: fixtures/
                    """,
                    """
                        runtime_mode: external
                        bindings:
                          base_url:
                            value: %s
                          mappings_ref:
                            ref: fixtures/
                    """.formatted(externalWireMock.baseUrl()));
            assertThat(updatedEnvProfile).isNotEqualTo(originalEnvProfile);
            Files.writeString(envProfile, updatedEnvProfile);

            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            assertThat(run.stdout())
                    .contains("run_status: passed")
                    .contains("provider_runtime_executed: true")
                    .contains("provider_id: wiremock-payment-api")
                    .contains("provider_type: wiremock_http_mock");
            Path resultJson = extractPath(run.stdout(), "result_json");
            Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
            assertThat(read(resultJson))
                    .contains("\"runtime_mode\": \"external\"")
                    .contains("\"base_url\": \"" + externalWireMock.baseUrl() + "\"")
                    .contains("\"framework_started_wiremock\": false");
            assertThat(read(evidenceDir.resolve("provider-evidence/wiremock/server_log.txt")))
                    .contains("framework_started_wiremock: false")
                    .contains("base_url: " + externalWireMock.baseUrl());
        } finally {
            externalWireMock.stop();
        }
    }

    @Test
    void wireMockSuiteRunsAllTestsWithSharedProfile() throws Exception {
        Path suite = mutableWireMock();
        Path secondTestCase = suite.getParent().resolve("second_test_case.yaml");
        Files.copy(suite.getParent().resolve("test_case.yaml"), secondTestCase);
        Files.writeString(secondTestCase, read(secondTestCase)
                .replace("WIREMOCK-CAPABILITY-TC-001", "WIREMOCK-CAPABILITY-TC-002"));
        Files.writeString(suite, read(suite)
                .replace("  - test_case.yaml", "  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("test_count: 2")
                .contains("profile: local_wiremock")
                .doesNotContain("\nbase_url:");
        Path resultJson = extractPath(run.stdout(), "result_json");
        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"test_case_id\": \"WIREMOCK-CAPABILITY-v0.2-MULTI\"")
                .contains("\"test_count\": 2")
                .contains("\"test_case_id\": \"WIREMOCK-CAPABILITY-TC-001\"")
                .contains("\"test_case_id\": \"WIREMOCK-CAPABILITY-TC-002\"");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("test_case_id: WIREMOCK-CAPABILITY-TC-001")
                .contains("test_case_id: WIREMOCK-CAPABILITY-TC-002");
    }

    @Test
    void wireMockRunRejectsProfileNotSelectedBySuiteBeforeRuntimeExecution() {
        CommandResult result = execute("run", "--suite", WIREMOCK_SUITE.toString(), "--profile", "wrong_profile");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: profile_mismatch")
                .contains("profile: wrong_profile");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
        assertThat(result.stdout()).doesNotContain("result_json:");
    }

    @Test
    void wireMockRunRejectsMissingRequiredOperationParameterBeforeExecution() throws Exception {
        Path suite = mutableWireMock();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase)
                .replace("        mock.mappings_ref:\n"
                        + "          ref: ${data.payment_success_stub}\n", ""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: missing_required_input")
                .contains("operation: load_stubs");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
        assertThat(result.stdout()).doesNotContain("result_json:");
    }

    @Test
    void wireMockRuntimeUsesDslMethodAndPathBindingsOverRequestInputDefaults() throws Exception {
        Path suite = mutableWireMock();
        Path requestInput = suite.getParent().resolve("fixtures/request_input.json");
        Files.writeString(requestInput, read(requestInput)
                .replace("\"method\": \"POST\"", "\"method\": \"GET\"")
                .replace("\"path\": \"/payments\"", "\"path\": \"/wrong-from-input\""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("provider-evidence/wiremock/request_journal.json")))
                .contains("\"method\" : \"POST\"")
                .contains("\"url\" : \"/payments\"");
    }

    @Test
    void wireMockRunResolvesProviderIdAndStepIdsFromDslArtifacts() throws Exception {
        Path suite = mutableWireMock();
        replaceInTree(suite.getParent(), "wiremock-payment-api", "renamed-payment-mock");
        replaceInTree(suite.getParent(), "load_payment_stub", "load_success_stub");
        replaceInTree(suite.getParent(), "submit_payment_to_mock", "post_payment_to_mock");

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_id: renamed-payment-mock")
                .contains("provider_runtime_executed: true");
        Path resultJson = extractPath(result.stdout(), "result_json");
        assertThat(read(resultJson))
                .contains("\"provider_id\": \"renamed-payment-mock\"")
                .contains("\"id\": \"load_success_stub\"")
                .contains("\"id\": \"post_payment_to_mock\"");
    }

    @Test
    void wireMockRunGeneratesDistinctRunIdsAndEvidenceDirectories() {
        CommandResult first = execute("run", "--suite", WIREMOCK_SUITE.toString(), "--profile", "local_wiremock");
        CommandResult second = execute("run", "--suite", WIREMOCK_SUITE.toString(), "--profile", "local_wiremock");

        assertThat(first.exit()).as(first.stderr() + first.stdout()).isZero();
        assertThat(second.exit()).as(second.stderr() + second.stdout()).isZero();
        Path firstResult = extractPath(first.stdout(), "result_json");
        Path secondResult = extractPath(second.stdout(), "result_json");
        assertThat(firstResult).isNotEqualTo(secondResult);
        assertThat(read(firstResult)).contains("\"run_id\": \"RUN-WIREMOCK-");
        assertThat(read(secondResult)).contains("\"run_id\": \"RUN-WIREMOCK-");
    }

    @Test
    void wireMockRunRejectsUnknownProviderTypeBeforeExecution() throws Exception {
        Path suite = mutableWireMock();
        Path instance = suite.getParent().resolve("provider_instances/wiremock_payment_api.yaml");
        Files.writeString(instance, read(instance)
                .replace("provider_type: wiremock_http_mock", "provider_type: unknown_wiremock"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unknown_provider_type")
                .contains("provider_type: unknown_wiremock");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
    }

    @Test
    void wireMockRunRejectsInvalidProviderInstanceBeforeExecution() throws Exception {
        Path suite = mutableWireMock();
        Path instance = suite.getParent().resolve("provider_instances/wiremock_payment_api.yaml");
        Files.writeString(instance, read(instance).replace("provider_type: wiremock_http_mock", "provider_type: missing_runtime"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unknown_provider_type")
                .contains("provider_type: missing_runtime");
        assertThat(result.stdout()).doesNotContain("result_json:");
    }

    @Test
    void wireMockRunRejectsMissingEnvProfileBeforeExecution() throws Exception {
        Path suite = mutableWireMock();
        Files.delete(suite.getParent().resolve("env_profiles/local_wiremock.yaml"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: missing_env_profile")
                .contains("profile: local_wiremock");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
    }

    @Test
    void wireMockRunRejectsUnsupportedOperationBeforeExecution() throws Exception {
        Path suite = mutableWireMock();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("operation: send_http_request", "operation: query_database"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unsupported_operation")
                .contains("operation: query_database");
    }

    @Test
    void wireMockRunRejectsUnsupportedBindAsBeforeExecution() throws Exception {
        Path suite = mutableWireMock();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("http.request_body:", "db.sql_ref:"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unsupported_input")
                .contains("db.sql_ref");
    }

    @Test
    void wireMockRunFailsOwnerActionablyWhenStubMappingIsMissing() throws Exception {
        Path suite = mutableWireMock();
        Files.delete(suite.getParent().resolve("fixtures/payment_success_stub.json"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unresolved_artifact_ref")
                .contains("data.payment_success_stub.ref");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
        assertThat(result.stdout()).doesNotContain("result_json:");
    }

    @Test
    void wireMockRunWritesFailedResultWhenExpectedMockCallIsMissing() throws Exception {
        Path suite = mutableWireMock();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("value: /payments", "value: /not-found"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"http_mock_called\"")
                .contains("\"matched_count\": 0");
        assertThat(evidenceDir.resolve("provider-evidence/wiremock/request_journal.json")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/wiremock/server_log.txt")).isRegularFile();
    }

    @Test
    void wireMockRunWritesFailedResultWhenRequestBodyMismatches() throws Exception {
        Path suite = mutableWireMock();
        Path requestInput = suite.getParent().resolve("fixtures/request_input.json");
        Files.writeString(requestInput, read(requestInput).replace("\"amount\": 100", "\"amount\": 999"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"http_mock_request_body_match\"");
        assertThat(evidenceDir.resolve("provider-evidence/wiremock/failure_detail.yaml")).isRegularFile();
    }

    @Test
    void wireMockUnavailableMapsToTechnicalFailureClassification() throws Exception {
        Path suite = mutableWireMock();
        try (ServerSocket occupied = new ServerSocket(0)) {
            Path binding = suite.getParent().resolve("env_profiles/local_wiremock.yaml");
            Files.writeString(binding, read(binding)
                    .replace("""
                              port_strategy: dynamic
                        """, """
                              port_strategy: fixed
                              fixed_port: %d
                        """.formatted(occupied.getLocalPort())));

            CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

            assertThat(result.exit()).isEqualTo(1);
            Path resultJson = extractPath(result.stdout(), "result_json");
            assertThat(read(resultJson))
                    .contains("\"status\": \"failed\"")
                    .contains("\"classification\": \"PROVIDER_UNAVAILABLE\"");
        }
    }

    @Test
    void wireMockRunBlocksRawSecretInConfig() throws Exception {
        Path suite = mutableWireMock();
        Path binding = suite.getParent().resolve("env_profiles/local_wiremock.yaml");
        Files.writeString(binding, read(binding) + "\npassword: plain-text-secret\n");

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_wiremock");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: raw_secret");
    }

    @Test
    void wireMockReportRejectsMissingRequiredEvidenceRef() {
        CommandResult run = execute("run", "--suite", WIREMOCK_SUITE.toString(), "--profile", "local_wiremock");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        delete(evidenceDir.resolve("provider-evidence/wiremock/request_journal.json"));

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_ref")
                .contains("provider-evidence/wiremock/request_journal.json");
    }

    private void assertEvidenceFilesExist(Path evidenceDir) {
        List<String> evidenceFiles = List.of(
                "evidence_index.yaml",
                "provider-evidence/wiremock/request_journal.json",
                "provider-evidence/wiremock/server_log.txt",
                "provider-evidence/wiremock/injected_stubs.yaml",
                "logs/execution.log",
                "assertions/http_mock_called.yaml",
                "assertions/http_mock_request_body_match.yaml",
                "batch/batch.yaml");

        assertThat(evidenceFiles).allSatisfy(path -> assertThat(evidenceDir.resolve(path))
                .as(path)
                .isRegularFile());
    }

    private Path mutableWireMock() throws IOException {
        Path target = tempDir.resolve("wiremock_" + System.nanoTime());
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock"), target);
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

    private void replaceInTree(Path root, String target, String replacement) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    Files.writeString(path, read(path).replace(target, replacement));
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

    private void delete(Path path) {
        try {
            Files.delete(path);
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
