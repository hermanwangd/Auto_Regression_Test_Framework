package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DslV03VerificationRuntimeCommandTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path ARTIFACT_COMPARE_SUITE =
            Path.of("samples/20-provider-capability-p0/verification/artifact_compare/suite_manifest.yaml");
    private static final Path COMMON_VERIFY_SUITE =
            Path.of("samples/20-provider-capability-p0/verification/common_verify/suite_manifest.yaml");
    private static final Path POLLING_OBSERVER_SUITE =
            Path.of("samples/20-provider-capability-p0/verification/polling_observer/suite_manifest.yaml");
    private static final Path MULTI_TEST_SUITE =
            Path.of("samples/20-provider-capability-p0/verification/multi_test_shared_env/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void runV03ArtifactCompareExecutesFrameworkAssertionsWithoutProviderRuntime() throws Exception {
        CommandResult validate = execute("validate", "--suite", ARTIFACT_COMPARE_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("artifact_compare.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult dryRun = execute("run", "--suite", ARTIFACT_COMPARE_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false");

        CommandResult run = execute("run", "--suite", ARTIFACT_COMPARE_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: ARTIFACT-COMPARE-v0.3")
                .contains("provider_runtime_executed: false");

        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"type\": \"json_match\"")
                .contains("\"type\": \"schema_match\"")
                .contains("\"type\": \"file_diff\"")
                .contains("\"evidence_index_ref\": \"evidence_index.yaml\"")
                .doesNotContain("provider_instance");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: ARTIFACT-COMPARE-v0.3")
                .contains("status: passed")
                .contains("verify_results_count: 3")
                .contains("missing_evidence_count: 0");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("provider_evidence_summary:")
                .contains("  []");
    }

    @Test
    void runV03CommonVerifyExecutesAssertionOnlyVerificationWithoutProviderRuntime() throws Exception {
        CommandResult validate = execute("validate", "--suite", COMMON_VERIFY_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("common_verify.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult dryRun = execute("run", "--suite", COMMON_VERIFY_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false");

        CommandResult run = execute("run", "--suite", COMMON_VERIFY_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: COMMON-VERIFY-v0.3")
                .contains("provider_runtime_executed: false");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"type\": \"json_match\"")
                .contains("\"type\": \"schema_match\"")
                .contains("\"type\": \"file_diff\"")
                .contains("\"verify_results\"")
                .doesNotContain("provider_instance");
        JsonNode assertionEvidence = OBJECT_MAPPER.readTree(evidenceDir
                .resolve("assertions/COMMON-VERIFY-V03-TC-001/payload_matches.json")
                .toFile());
        assertThat(assertionEvidence.path("assertion_id").asText()).isEqualTo("payload_matches");
        assertThat(assertionEvidence.path("type").asText()).isEqualTo("json_match");
        assertThat(assertionEvidence.path("status").asText()).isEqualTo("passed");
        assertThat(Files.readString(evidenceDir.resolve("evidence_index.yaml")))
                .doesNotContain("failure_code: ASSERTION_FAILED");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: COMMON-VERIFY-v0.3")
                .contains("status: passed")
                .contains("verify_results_count: 3");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout()).contains("evidence_validation_status: passed");
    }

    @Test
    void runV03PollingObserverExecutesProviderCheckAndRecordsPollingEvidence() throws Exception {
        CommandResult validate = execute("validate", "--suite", POLLING_OBSERVER_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("polling_observer.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult dryRun = execute("run", "--suite", POLLING_OBSERVER_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false");

        CommandResult run = execute("run", "--suite", POLLING_OBSERVER_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: POLLING-OBSERVER-v0.3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: polling_observer");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        String result = Files.readString(resultJson);
        JsonNode resultNode = OBJECT_MAPPER.readTree(resultJson.toFile());
        assertThat(result)
                .contains("\"provider_contract\": \"polling_observer.v0.3\"")
                .contains("\"operation\": \"event_published\"")
                .contains("\"last_observed_ref\": \"polling/event_is_published.yaml\"")
                .doesNotContain("provider_instance");
        assertThat(textValues(resultNode.path("provider_evidence_refs")))
                .contains("polling/event_is_published.yaml");
        assertEvidenceRefsExist(resultNode, evidenceDir);
        assertThat(Files.readString(evidenceDir.resolve("polling/event_is_published.yaml")))
                .contains("verifier_type: event_published")
                .contains("attempts: 1")
                .contains("last_observed_value:")
                .contains("final_status: passed");
        assertThat(Files.readString(evidenceDir.resolve("evidence_index.yaml")))
                .contains("evidence_type: polling_observation")
                .contains("provider_type: polling_observer");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: POLLING-OBSERVER-v0.3")
                .contains("status: passed")
                .contains("provider_results_count: 1");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout()).contains("evidence_validation_status: passed");
    }

    @Test
    void runV03UsesEnvProfileEvidenceClassification() throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/verification/multi_test_shared_env"), "evidence_classification");
        Path envProfile = suite.getParent().resolve("env_profiles/local_v03.yaml");
        Files.writeString(envProfile, Files.readString(envProfile)
                .replace("evidence_classification: framework_verification_only",
                        "evidence_classification: project_environment_evidence"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout()).contains("evidence_classification: project_environment_evidence");
        JsonNode result = OBJECT_MAPPER.readTree(extractPath(run.stdout(), "result_json").toFile());
        assertThat(result.path("labels").path("evidence_classification").asText())
                .isEqualTo("project_environment_evidence");
        assertThat(result.path("labels").path("downstream_release_evidence").asBoolean())
                .isTrue();
    }

    @Test
    void runV03MultiTestSuiteReportsEveryTestCaseWithSharedProfile() throws Exception {
        CommandResult validate = execute("validate", "--suite", MULTI_TEST_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("common_verify.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult run = execute("run", "--suite", MULTI_TEST_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: MULTI-TEST-v0.3")
                .contains("test_case_id: MULTI-TEST-v0.3-MULTI")
                .contains("test_count: 2")
                .contains("provider_runtime_executed: false");

        Path resultJson = extractPath(run.stdout(), "result_json");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"test_count\": 2")
                .contains("\"test_case_id\": \"MULTI-TEST-v0.3-MULTI\"")
                .contains("\"test_case_id\": \"MULTI-TEST-V03-TC-001\"")
                .contains("\"test_case_id\": \"MULTI-TEST-V03-TC-002\"")
                .contains("\"id\": \"multi_payload_matches\"")
                .contains("\"id\": \"multi_file_matches\"");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: MULTI-TEST-v0.3")
                .contains("test_count: 2")
                .contains("status: passed");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout()).contains("evidence_validation_status: passed");
    }

    @Test
    void runV03MultiTestSuiteScopesAssertionEvidenceWhenAssertionIdsRepeat() throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/verification/multi_test_shared_env"), "multi_test_duplicate_assertion_ids");
        Path firstTestCase = suite.getParent().resolve("test_cases/order_payload_success.yaml");
        Path secondTestCase = suite.getParent().resolve("test_cases/order_file_success.yaml");
        Files.writeString(firstTestCase, Files.readString(firstTestCase)
                .replace("multi_payload_matches", "shared_assertion"));
        Files.writeString(secondTestCase, Files.readString(secondTestCase)
                .replace("multi_file_matches", "shared_assertion"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        JsonNode result = OBJECT_MAPPER.readTree(resultJson.toFile());
        assertThat(verifyResultIds(result, "shared_assertion"))
                .containsExactlyInAnyOrder("MULTI-TEST-V03-TC-001", "MULTI-TEST-V03-TC-002");
        assertThat(evidenceDir.resolve("assertions/MULTI-TEST-V03-TC-001/shared_assertion.json"))
                .isRegularFile();
        assertThat(evidenceDir.resolve("assertions/MULTI-TEST-V03-TC-002/shared_assertion.yaml"))
                .isRegularFile();
        assertThat(Files.readString(evidenceDir.resolve("evidence_index.yaml")))
                .contains("test_case_id: MULTI-TEST-V03-TC-001")
                .contains("test_case_id: MULTI-TEST-V03-TC-002")
                .contains("file_path: assertions/MULTI-TEST-V03-TC-001/shared_assertion.json")
                .contains("file_path: assertions/MULTI-TEST-V03-TC-002/shared_assertion.yaml");
        assertEvidenceRefsExist(result, evidenceDir);
    }

    @Test
    void runV03MultiTestFailureIsScopedToFailingTestCase() throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/verification/multi_test_shared_env"), "multi_test_one_failure");
        Path expectedPayload = suite.getParent().resolve("expected_results/order_payload.json");
        Files.writeString(expectedPayload, Files.readString(expectedPayload)
                .replace("\"status\": \"READY\"", "\"status\": \"BROKEN\""));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("passed_count: 1")
                .contains("failed_count: 1");
        JsonNode testResults = OBJECT_MAPPER.readTree(extractPath(run.stdout(), "result_json").toFile())
                .path("test_results");
        assertThat(statusFor(testResults, "MULTI-TEST-V03-TC-001")).isEqualTo("failed");
        assertThat(statusFor(testResults, "MULTI-TEST-V03-TC-002")).isEqualTo("passed");
    }

    @Test
    void runV03PollingTimeoutRetriesUntilTimeoutAndKeepsLastObservedEvidence() throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/verification/polling_observer"), "polling_timeout");
        Path testCase = suite.getParent().resolve("test_cases/event_published_success.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("timeout: PT1S", "timeout: PT0.5S")
                .replace("poll_interval: PT0S", "poll_interval: PT0.05S"));
        Path expected = suite.getParent().resolve("expected_results/expected_event.json");
        Files.writeString(expected, Files.readString(expected)
                .replace("\"subject\": \"orders.ready\"", "\"subject\": \"orders.never\""));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("failure_code: POLLING_TIMEOUT");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        String evidence = Files.readString(evidenceDir.resolve("polling/event_is_published.yaml"));
        assertThat(attempts(evidence)).isGreaterThan(1);
        assertThat(evidence)
                .contains("last_observed_value:")
                .contains("final_status: failed")
                .contains("failure_code: POLLING_TIMEOUT");

        CommandResult evidenceValidation = execute("validate-evidence", "--result",
                extractPath(run.stdout(), "result_json").toString());
        assertThat(evidenceValidation.exit()).as(evidenceValidation.stderr() + evidenceValidation.stdout()).isZero();
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

    private Path mutableSuite(Path source, String name) throws IOException {
        Path target = Files.createTempDirectory(tempDir, name + "_");
        copyDirectory(source, target);
        return target.resolve("suite_manifest.yaml");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
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

    private String statusFor(JsonNode testResults, String testCaseId) {
        for (JsonNode result : testResults) {
            if (testCaseId.equals(result.path("test_case_id").asText())) {
                return result.path("status").asText();
            }
        }
        throw new AssertionError("Missing test result for " + testCaseId + " in " + testResults);
    }

    private int attempts(String pollingEvidence) {
        return pollingEvidence.lines()
                .filter(line -> line.startsWith("attempts: "))
                .map(line -> Integer.parseInt(line.substring("attempts: ".length()).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing attempts in:\n" + pollingEvidence));
    }

    private List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return values;
    }

    private List<String> verifyResultIds(JsonNode result, String assertionId) {
        List<String> testCaseIds = new ArrayList<>();
        for (JsonNode verifyResult : result.path("verify_results")) {
            if (assertionId.equals(verifyResult.path("id").asText())) {
                testCaseIds.add(verifyResult.path("test_case_id").asText());
            }
        }
        return testCaseIds;
    }

    private void assertEvidenceRefsExist(JsonNode result, Path evidenceDir) {
        for (String ref : textValues(result.path("evidence_refs"))) {
            assertThat(evidenceDir.resolve(ref)).as("evidence ref " + ref).isRegularFile();
        }
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
