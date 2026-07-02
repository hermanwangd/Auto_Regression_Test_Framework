package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MockServerCrossVerifySampleCommandTest {

    private static final Path CROSS_VERIFY_SUITE =
            Path.of("samples/provider_capability/mock_server_cross_verify/suite_manifest.yaml");
    private static final Path REST_SUITE =
            Path.of("samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/suite_manifest.yaml");
    private static final Path SOAP_SUITE =
            Path.of("samples/provider_capability/mock_server_cross_verify/soap_mock_http_client/suite_manifest.yaml");
    private static final Path GRPC_SUITE =
            Path.of("samples/provider_capability/mock_server_cross_verify/grpc_mock_grpc_client/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void mockServerCrossVerifySampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/provider_capability/mock_server_cross_verify/README.md",
                REST_SUITE.toString(),
                "samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/suite_manifest_failure.yaml",
                "samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/suite_manifest_boundary.yaml",
                SOAP_SUITE.toString(),
                "samples/provider_capability/mock_server_cross_verify/soap_mock_http_client/suite_manifest_failure.yaml",
                "samples/provider_capability/mock_server_cross_verify/soap_mock_http_client/suite_manifest_boundary.yaml",
                GRPC_SUITE.toString(),
                "samples/provider_capability/mock_server_cross_verify/grpc_mock_grpc_client/suite_manifest_failure.yaml",
                "samples/provider_capability/mock_server_cross_verify/grpc_mock_grpc_client/suite_manifest_boundary.yaml",
                "samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/test_case.yaml",
                "samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/test_case_failure.yaml",
                "samples/provider_capability/mock_server_cross_verify/rest_wiremock_http/test_case_boundary.yaml",
                "samples/provider_capability/mock_server_cross_verify/soap_mock_http_client/test_case.yaml",
                "samples/provider_capability/mock_server_cross_verify/soap_mock_http_client/test_case_failure.yaml",
                "samples/provider_capability/mock_server_cross_verify/soap_mock_http_client/test_case_boundary.yaml",
                "samples/provider_capability/mock_server_cross_verify/grpc_mock_grpc_client/test_case.yaml",
                "samples/provider_capability/mock_server_cross_verify/grpc_mock_grpc_client/test_case_failure.yaml",
                "samples/provider_capability/mock_server_cross_verify/grpc_mock_grpc_client/test_case_boundary.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void suiteGroupRunsMultipleMockServerCrossVerifyTestCasesAndWritesAllureResults() throws Exception {
        CommandResult validation = execute("validate", "--suite", CROSS_VERIFY_SUITE.toString());
        assertThat(validation.exit()).as(validation.stderr() + validation.stdout()).isZero();
        assertThat(validation.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: MOCK-SERVER-CROSS-VERIFY-v0.2")
                .contains("test_count: 9");

        CommandResult run = execute(
                "run",
                "--suite",
                CROSS_VERIFY_SUITE.toString(),
                "--profile",
                "local_mock_server_cross_verify");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: MOCK-SERVER-CROSS-VERIFY-v0.2")
                .contains("test_count: 9")
                .contains("passed_count: 9")
                .contains("failed_count: 0")
                .contains("expected_failure_count: 3")
                .contains("suite_summary_json:")
                .contains("allure_results_dir:");

        Path suiteSummary = extractPath(run.stdout(), "suite_summary_json");
        Path allureResults = extractPath(run.stdout(), "allure_results_dir");
        assertThat(suiteSummary.toString())
                .contains("target/suite-groups")
                .doesNotContain("target/provider-capability/mock_server_cross_verify");
        assertThat(Files.readString(suiteSummary))
                .contains("\"suite_id\": \"MOCK-SERVER-CROSS-VERIFY-v0.2\"")
                .contains("\"test_count\": 9")
                .contains("\"passed_count\": 9")
                .contains("\"expected_failure_count\": 3")
                .contains("\"expected_status\": \"failed\"")
                .contains("\"observed_status\": \"failed\"")
                .contains("\"status\": \"passed\"")
                .contains("\"child_suite_id\": \"MOCK-SERVER-CROSS-VERIFY-REST-v0.2\"")
                .contains("\"child_suite_id\": \"MOCK-SERVER-CROSS-VERIFY-SOAP-v0.2\"")
                .contains("\"child_suite_id\": \"MOCK-SERVER-CROSS-VERIFY-GRPC-v0.2\"");
        assertThat(countFiles(allureResults, "-result.json")).isEqualTo(9);
        assertThat(countFiles(allureResults, "-container.json")).isEqualTo(1);
        assertThat(Files.readString(firstFile(allureResults, "-result.json")))
                .contains("\"status\": \"passed\"")
                .contains("\"labels\"")
                .contains("\"suite\"");
    }

    @Test
    void suiteGroupDryRunValidatesChildrenWithoutExecutingProviderRuntime() {
        CommandResult dryRun = execute("run", "--suite", CROSS_VERIFY_SUITE.toString(), "--dry-run");

        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("suite_id: MOCK-SERVER-CROSS-VERIFY-v0.2")
                .contains("test_count: 9")
                .contains("child_suites:")
                .contains("expected_status: failed")
                .doesNotContain("provider_runtime_executed: true");
    }

    @Test
    void suiteGroupValidationRejectsChildRefsOutsideSuiteRoot() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "escape_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_type: suite_group
                suite_id: ESCAPE-SUITE
                profile: local_escape
                test_cases:
                  - id: ESCAPE-001
                    ref: ../outside/suite_manifest.yaml
                    profile: local_escape
                    expected_status: passed
                """);

        CommandResult validation = execute("validate", "--suite", manifest.toString());
        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("invalid_child_suite_ref")
                .contains("owner_action:");
    }

    @Test
    void suiteGroupValidationRejectsDuplicateChildIds() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "duplicate_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_type: suite_group
                suite_id: DUPLICATE-SUITE
                profile: local_duplicate
                test_cases:
                  - id: DUP-001
                    ref: child_a/suite_manifest.yaml
                    profile: local_duplicate
                    expected_status: passed
                  - id: DUP-001
                    ref: child_b/suite_manifest.yaml
                    profile: local_duplicate
                    expected_status: passed
                """);

        CommandResult validation = execute("validate", "--suite", manifest.toString());
        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("duplicate_child_id")
                .contains("test_cases[1].id");
    }

    @Test
    void suiteGroupValidationHandlesRootLevelManifestWithMissingChildSuite() throws Exception {
        Path manifest = tempDir.resolve("suite_manifest.yaml");
        Files.writeString(
                manifest,
                """
                contract_version: v0.2
                suite_type: suite_group
                suite_id: ROOT-LEVEL-SUITE
                profile: local_root
                test_cases:
                  - id: ROOT-001
                    ref: child/suite_manifest.yaml
                    profile: local_root
                    expected_status: passed
                """);

        CommandResult validation = execute(
                "validate",
                "--root",
                tempDir.toString(),
                "--suite",
                "suite_manifest.yaml");

        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("suite_id: ROOT-LEVEL-SUITE")
                .contains("missing_required_file")
                .contains("owner_action:");
    }

    @Test
    void suiteGroupRunBlocksBeforeOutputWhenChildSuiteIsMissing() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "missing_child_run_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_type: suite_group
                suite_id: MISSING-CHILD-RUN-SUITE
                profile: local_missing_child
                test_cases:
                  - id: MISSING-001
                    ref: child/suite_manifest.yaml
                    profile: local_missing_child
                    expected_status: passed
                """);

        CommandResult run = execute(
                "run",
                "--suite",
                manifest.toString(),
                "--profile",
                "local_missing_child");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: blocked")
                .contains("suite_id: MISSING-CHILD-RUN-SUITE")
                .contains("missing_required_file")
                .doesNotContain("batch_id:")
                .doesNotContain("suite_summary_json:");
    }

    @Test
    void happyPathRunsMockServerProvidersCrossVerifiedByClientProviders() {
        assertCrossVerifySuite(
                REST_SUITE,
                "local_wiremock_http",
                "MOCK-SERVER-CROSS-VERIFY-REST-v0.2",
                "wiremock_http_mock,rest_client");
        assertCrossVerifySuite(
                SOAP_SUITE,
                "local_soap_mock",
                "MOCK-SERVER-CROSS-VERIFY-SOAP-v0.2",
                "soap_mock,rest_client");
        assertCrossVerifySuite(
                GRPC_SUITE,
                "local_grpc_mock",
                "MOCK-SERVER-CROSS-VERIFY-GRPC-v0.2",
                "grpc_mock,grpc_client");
    }

    private void assertCrossVerifySuite(
            Path suite,
            String profile,
            String suiteId,
            String providerTypes) {
        CommandResult validation = execute("validate", "--suite", suite.toString());
        assertThat(validation.exit()).as(validation.stderr() + validation.stdout()).isZero();
        assertThat(validation.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: " + suiteId);

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", profile);
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: " + providerTypes)
                .contains("result_json:");

        CommandResult report = execute("report", "--result", extractPath(run.stdout(), "result_json").toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: " + suiteId)
                .contains("provider_evidence_summary:")
                .contains("masking_status: passed");
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).trim())
                .map(Path::of)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path line for " + key + " in:\n" + stdout));
    }

    private long countFiles(Path dir, String suffix) throws Exception {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix)).count();
        }
    }

    private Path firstFile(Path dir, String suffix) throws Exception {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing " + suffix + " file in " + dir));
        }
    }

    private Path writeSuiteGroupManifest(String filename, String content) throws Exception {
        Path suiteDir = tempDir.resolve(filename.replace(".yaml", ""));
        Files.createDirectories(suiteDir);
        Path manifest = suiteDir.resolve(filename);
        Files.writeString(manifest, content);
        return manifest;
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
