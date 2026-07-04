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

class GoldenE2eCommandTest {

    private static final Path GOLDEN_SUITE = Path.of("samples/golden_e2e/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void goldenSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/golden_e2e/suite_manifest.yaml",
                "samples/golden_e2e/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/sample_fake_provider.yaml",
                "samples/golden_e2e/provider_instances/sample_fake_instance.yaml",
                "samples/golden_e2e/env_profiles/local_golden.yaml",
                "samples/golden_e2e/execution_profiles/local_golden.yaml",
                "samples/golden_e2e/environment_bindings/local_golden.yaml",
                "samples/golden_e2e/fixtures/input.json",
                "samples/golden_e2e/fixtures/setup_fixture.yaml",
                "samples/golden_e2e/fixtures/cleanup_fixture.yaml",
                "samples/golden_e2e/expected_results/expected_output.json",
                "samples/golden_e2e/result/expected_result_shape.json",
                "samples/golden_e2e/evidence/expected_evidence_index.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void goldenSampleValidatesThroughPublicCli() {
        CommandResult result = execute("validate", "--suite", GOLDEN_SUITE.toString());

        assertThat(result.exit()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: GOLDEN-E2E-v0.2")
                .contains("sample-fake-runtime")
                .contains("sample_fake_provider");
    }

    @Test
    void goldenSampleRunsFakeProviderAndReportConsumesGeneratedResult() throws Exception {
        CommandResult run = execute("run", "--suite", GOLDEN_SUITE.toString(), "--profile", "local_golden");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("fake_provider_executed: true")
                .contains("evidence_classification: framework_verification_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertEvidenceFilesExist(evidenceDir);
        assertThat(Files.readString(evidenceDir.resolve("evidence_index.yaml")))
                .contains("provider_id: sample-fake-runtime");

        String resultText = Files.readString(resultJson);
        assertThat(resultText)
                .contains("\"framework_version\": \"0.2.2\"")
                .contains("\"suite_id\": \"GOLDEN-E2E-v0.2\"")
                .contains("\"batch_id\": \"BATCH-GOLDEN-E2E-001\"")
                .contains("\"run_id\": \"RUN-GOLDEN-E2E-001\"")
                .contains("\"status\": \"passed\"")
                .contains("\"evidence_classification\": \"framework_verification_only\"")
                .contains("\"step_results\"")
                .contains("\"provider_summary\"")
                .contains("\"provider_results\"")
                .contains("\"verify_results\"")
                .contains("\"evidence_index_ref\": \"evidence_index.yaml\"")
                .contains("\"provider_evidence_refs\"")
                .contains("\"evidence_refs\"")
                .contains("\"failure\"");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: GOLDEN-E2E-v0.2")
                .contains("batch_id: BATCH-GOLDEN-E2E-001")
                .contains("run_id: RUN-GOLDEN-E2E-001")
                .contains("test_case_id: GOLDEN-E2E-TC-001")
                .contains("status: passed");
    }

    @Test
    void goldenSuiteRunsAllTestsWithSharedProfile() throws Exception {
        Path suite = mutableGolden();
        Path secondTestCase = suite.getParent().resolve("second_test_case.yaml");
        Files.copy(suite.getParent().resolve("test_case.yaml"), secondTestCase);
        Files.writeString(secondTestCase, Files.readString(secondTestCase)
                .replace("GOLDEN-E2E-TC-001", "GOLDEN-E2E-TC-002"));
        Files.writeString(suite, Files.readString(suite)
                .replace("  - test_case.yaml", "  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("test_count: 2")
                .contains("profile: local_golden");
        Path resultJson = extractPath(run.stdout(), "result_json");
        String resultText = Files.readString(resultJson);
        assertThat(resultText)
                .contains("\"test_case_id\": \"GOLDEN-E2E-v0.2-MULTI\"")
                .contains("\"test_count\": 2")
                .contains("\"test_case_id\": \"GOLDEN-E2E-TC-001\"")
                .contains("\"test_case_id\": \"GOLDEN-E2E-TC-002\"");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(evidenceDir.resolve("tests/GOLDEN-E2E-TC-001/assertions/status_is_ok.yaml")).isRegularFile();
        assertThat(evidenceDir.resolve("tests/GOLDEN-E2E-TC-002/assertions/status_is_ok.yaml")).isRegularFile();
        assertThat(Files.readString(evidenceDir.resolve("evidence_index.yaml")))
                .contains("test_case_id: GOLDEN-E2E-TC-001")
                .contains("test_case_id: GOLDEN-E2E-TC-002");
    }

    @Test
    void goldenRunUsesCliProfileAndFrameworkProviderContractCatalog() throws Exception {
        Path suite = mutableGolden();
        Path suiteRoot = suite.getParent();
        Path testCase = suiteRoot.resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("    profile: local_golden\n", ""));
        deleteDirectory(suiteRoot.resolve("provider_contracts"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("fake_provider_executed: true");
    }

    @Test
    void goldenRunResolvesRuntimeBindingFromEnvProfileBeforeLegacyEnvironmentBinding() throws Exception {
        Path suite = mutableGolden();
        Path suiteRoot = suite.getParent();
        Files.writeString(suiteRoot.resolve("env_profiles/local_golden.yaml"),
                Files.readString(suiteRoot.resolve("env_profiles/local_golden.yaml"))
                        .replace("fixed://2026-06-29T00:00:00Z", "fixed://2030-01-02T03:04:05Z"));
        Files.writeString(suiteRoot.resolve("environment_bindings/local_golden.yaml"),
                Files.readString(suiteRoot.resolve("environment_bindings/local_golden.yaml"))
                        .replace("fixed://2026-06-29T00:00:00Z", "fixed://1999-01-01T00:00:00Z"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(Files.readString(evidenceDir.resolve("actual/actual_output.json")))
                .contains("\"generatedAt\": \"2030-01-02T03:04:05Z\"")
                .doesNotContain("1999-01-01T00:00:00Z");
    }

    @Test
    void goldenRunRejectsInvalidDslBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("dsl_version: v0.2\n", ""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: missing_required_field")
                .contains("field_path: dsl_version");
        assertThat(result.stdout()).doesNotContain("fake_provider_executed: true");
    }

    @Test
    void goldenRunRejectsMissingProviderInstanceBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        Files.delete(suite.getParent().resolve("provider_instances/sample_fake_instance.yaml"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: missing_provider_instance")
                .contains("provider_id: sample-fake-runtime");
        assertThat(result.stdout()).doesNotContain("result_json:");
    }

    @Test
    void goldenRunRejectsUnknownProviderTypeBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        Path providerInstance = suite.getParent().resolve("provider_instances/sample_fake_instance.yaml");
        Files.writeString(providerInstance, Files.readString(providerInstance)
                .replace("provider_type: sample_fake_provider", "provider_type: missing_fake_provider"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unknown_provider_type")
                .contains("provider_type: missing_fake_provider");
        assertThat(result.stdout()).doesNotContain("fake_provider_executed: true");
    }

    @Test
    void goldenRunRejectsMissingExecutionProfileBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        deleteDirectory(suite.getParent().resolve("env_profiles"));
        Files.delete(suite.getParent().resolve("execution_profiles/local_golden.yaml"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: missing_execution_profile")
                .contains("profile: local_golden");
        assertThat(result.stdout()).doesNotContain("fake_provider_executed: true");
    }

    @Test
    void goldenRunRejectsMissingEnvironmentBindingBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        deleteDirectory(suite.getParent().resolve("env_profiles"));
        Files.delete(suite.getParent().resolve("environment_bindings/local_golden.yaml"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: missing_environment_binding")
                .contains("profile: local_golden");
        assertThat(result.stdout()).doesNotContain("fake_provider_executed: true");
    }

    @Test
    void goldenRunRejectsUnsupportedOperationBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("operation: execute_sample", "operation: send_to_nats"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unsupported_operation")
                .contains("operation: send_to_nats");
        assertThat(result.stdout()).doesNotContain("fake_provider_executed: true");
    }

    @Test
    void goldenRunBlocksWhenExpectedResultReferenceIsMissingBeforeExecution() throws Exception {
        Path suite = mutableGolden();
        Files.delete(suite.getParent().resolve("expected_results/expected_output.json"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unresolved_artifact_ref")
                .contains("category: CONFIGURATION_ERROR");
        assertThat(result.stdout()).doesNotContain("fake_provider_executed: true");
    }

    @Test
    void goldenRunWritesFailedResultAndDiffWhenVerificationMismatches() throws Exception {
        Path suite = mutableGolden();
        Path expected = suite.getParent().resolve("expected_results/expected_output.json");
        Files.writeString(expected, Files.readString(expected).replace("\"status\": \"OK\"", "\"status\": \"BROKEN\""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("run_status: failed");
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(Files.readString(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"status_is_ok\"")
                .contains("\"output_matches_expected_json\"");
        assertThat(evidenceDir.resolve("diffs/output_matches_expected_json.diff")).isRegularFile();
        assertThat(Files.readString(evidenceDir.resolve("diffs/output_matches_expected_json.diff")))
                .contains("expected")
                .contains("actual");
        assertThat(evidenceDir.resolve("fixture/cleanup.yaml")).isRegularFile();

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready_with_failures")
                .contains("failed_verify_summary:")
                .contains("output_matches_expected_json:json_match:failed");
    }

    @Test
    void goldenReportRejectsMissingEvidenceRef() throws Exception {
        CommandResult run = execute("run", "--suite", GOLDEN_SUITE.toString(), "--profile", "local_golden");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        Files.delete(evidenceDir.resolve("actual/actual_output.json"));

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_ref")
                .contains("actual/actual_output.json");
    }

    @Test
    void goldenRunBlocksRawSecretInDslOrBinding() throws Exception {
        Path suite = mutableGolden();
        Path binding = suite.getParent().resolve("env_profiles/local_golden.yaml");
        Files.writeString(binding, Files.readString(binding) + "\npassword: plain-text-secret\n");

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_golden");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: raw_secret")
                .contains("owner_action:");
    }

    @Test
    void goldenReportRejectsInvalidResultJson() throws Exception {
        Path invalid = tempDir.resolve("invalid-golden-result.json");
        Files.writeString(invalid, "{\"suite_id\":\"GOLDEN-E2E-v0.2\"}");

        CommandResult result = execute("report", "--result", invalid.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field");
    }

    private void assertEvidenceFilesExist(Path evidenceDir) {
        List<String> evidenceFiles = List.of(
                "evidence_index.yaml",
                "logs/execution.log",
                "fixture/setup.yaml",
                "fixture/cleanup.yaml",
                "actual/actual_output.json",
                "actual/actual_output.txt",
                "expected/expected_output.ref",
                "assertions/status_is_ok.yaml",
                "assertions/output_matches_expected_json.yaml",
                "diffs/output_matches_expected_json.diff",
                "batch/batch.yaml");

        assertThat(evidenceFiles).allSatisfy(path -> assertThat(evidenceDir.resolve(path))
                .as(path)
                .isRegularFile());
    }

    private Path mutableGolden() throws IOException {
        Path target = tempDir.resolve("golden_e2e_" + System.nanoTime());
        copyDirectory(Path.of("samples/golden_e2e"), target);
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

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(path);
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
