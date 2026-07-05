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

class CommonVerifyCapabilityCommandTest {

    private static final Path COMMON_VERIFY_SUITE = Path.of("samples/provider_capability/common_verify/suite_manifest.yaml");
    private static final Path POLLING_SUITE = Path.of("samples/provider_capability/polling/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void commonVerifySampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/provider_capability/common_verify/suite_manifest.yaml",
                "samples/provider_capability/common_verify/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/common_verify.yaml",
                "samples/provider_capability/common_verify/provider_instances/local_verify.yaml",
                "samples/provider_capability/common_verify/execution_profiles/local_verify.yaml",
                "samples/provider_capability/common_verify/environment_bindings/local_verify.yaml",
                "samples/provider_capability/common_verify/expected_results/expected_payload.json",
                "samples/provider_capability/common_verify/expected_results/expected_schema.json",
                "samples/provider_capability/common_verify/expected_results/expected_file.txt",
                "samples/provider_capability/common_verify/actual_samples/actual_payload.json",
                "samples/provider_capability/common_verify/actual_samples/actual_file.txt",
                "samples/provider_capability/polling/suite_manifest.yaml",
                "samples/provider_capability/polling/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/common_verify.yaml",
                "samples/provider_capability/polling/provider_instances/local_polling.yaml",
                "samples/provider_capability/polling/provider_instances/local_polling_observer_ephemeral.yaml",
                "samples/provider_capability/polling/execution_profiles/local_polling.yaml",
                "samples/provider_capability/polling/environment_bindings/local_polling.yaml",
                "samples/provider_capability/polling/expected_results/expected_timeout_result.json",
                "samples/provider_capability/polling/actual_samples/observed_event.json");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void commonVerifySuiteValidatesRunsAndReports() {
        CommandResult validate = execute("validate", "--suite", COMMON_VERIFY_SUITE.toString());
        CommandResult run = execute("run", "--suite", COMMON_VERIFY_SUITE.toString(), "--profile", "local_verify");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("COMMON-VERIFY-CAPABILITY-v0.2")
                .contains("local-common-verifier")
                .contains("common_verify");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: false")
                .contains("provider_type: common_verify")
                .contains("profile: local_verify")
                .contains("evidence_classification: framework_provider_capability_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertThat(read(resultJson))
                .contains("\"id\": \"payload_matches\"")
                .contains("\"type\": \"json_match\"")
                .contains("\"runtime_mode\": \"stub\"")
                .contains("\"id\": \"payload_schema_matches\"")
                .contains("\"type\": \"schema_match\"")
                .contains("\"id\": \"file_matches\"")
                .contains("\"type\": \"file_diff\"")
                .contains("\"ignore_paths\"")
                .contains("\"normalize\": \"canonical_json\"")
                .contains("\"ignore_order\": true")
                .contains("\"provider_summary\"")
                .contains("\"evidence_index_ref\": \"evidence_index.yaml\"")
                .contains("\"provider_evidence_refs\"")
                .contains("\"release_evidence_eligible\": false");
        assertThat(read(evidenceDir.resolve("assertions/payload_matches.yaml")))
                .contains("verifier_type: json_match")
                .contains("ignore_paths:")
                .contains("comparison_status: passed");
        assertThat(read(evidenceDir.resolve("assertions/file_matches.yaml")))
                .contains("verifier_type: file_diff")
                .contains("normalize: trim_lines")
                .contains("comparison_status: passed");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: COMMON-VERIFY-CAPABILITY-v0.2")
                .contains("verify_results_count: 3")
                .contains("status: passed");
    }

    @Test
    void commonVerifyRunProducesOwnerActionableDiffsForVerifierFailures() throws Exception {
        Path suite = mutableCommonVerify("common_verify_failures");
        Path expectedPayload = suite.getParent().resolve("expected_results/expected_payload.json");
        Path expectedSchema = suite.getParent().resolve("expected_results/expected_schema.json");
        Path expectedFile = suite.getParent().resolve("expected_results/expected_file.txt");
        Files.writeString(expectedPayload, read(expectedPayload).replace("\"READY\"", "\"SHIPPED\""));
        Files.writeString(expectedSchema, read(expectedSchema).replace("\"status\"", "\"missingStatus\""));
        Files.writeString(expectedFile, "different file\n");

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_verify");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("payload_matches:json_match:failed")
                .contains("payload_schema_matches:schema_match:failed")
                .contains("file_matches:file_diff:failed")
                .contains("\"classification\": \"ASSERTION_FAILED\"");
        assertThat(read(evidenceDir.resolve("diffs/payload_matches.diff")))
                .contains("expected:")
                .contains("actual:");
        assertThat(read(evidenceDir.resolve("assertions/payload_schema_matches.yaml")))
                .contains("failure_code: SCHEMA_MISMATCH")
                .contains("owner_action: Review schema mismatch");
    }

    @Test
    void commonVerifyRunExecutesAllTestsInSuiteWithSharedProfile() throws Exception {
        Path suite = mutableCommonVerify("multi_test_suite");
        Files.copy(suite.getParent().resolve("test_case.yaml"), suite.getParent().resolve("second_test_case.yaml"));
        Files.writeString(suite.getParent().resolve("second_test_case.yaml"),
                read(suite.getParent().resolve("second_test_case.yaml"))
                        .replace("COMMON-VERIFY-TC-001", "COMMON-VERIFY-TC-002"));
        Files.writeString(suite, read(suite).replace("  - test_case.yaml", "  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_verify");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("test_count: 2")
                .contains("profile: local_verify");
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"test_case_id\": \"COMMON-VERIFY-CAPABILITY-v0.2-MULTI\"")
                .contains("\"test_count\": 2")
                .contains("\"test_case_id\": \"COMMON-VERIFY-TC-001\"")
                .contains("\"test_case_id\": \"COMMON-VERIFY-TC-002\"");
        assertThat(evidenceDir.resolve("tests/COMMON-VERIFY-TC-001/assertions/payload_matches.yaml"))
                .isRegularFile();
        assertThat(evidenceDir.resolve("tests/COMMON-VERIFY-TC-002/assertions/payload_matches.yaml"))
                .isRegularFile();
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("test_case_id: COMMON-VERIFY-TC-001")
                .contains("test_case_id: COMMON-VERIFY-TC-002");
    }

    @Test
    void commonVerifyFailedMultiTestRunKeepsSuiteLevelRunIdentifierInCliOutput() throws Exception {
        Path suite = mutableCommonVerify("multi_test_failure_suite");
        Path secondTestCase = suite.getParent().resolve("second_test_case.yaml");
        Path badActual = suite.getParent().resolve("actual_samples/actual_payload_bad.json");
        Files.copy(suite.getParent().resolve("test_case.yaml"), secondTestCase);
        Files.copy(suite.getParent().resolve("actual_samples/actual_payload.json"), badActual);
        Files.writeString(badActual, read(badActual).replace("\"id\": \"ORDER-100\"", "\"id\": \"ORDER-BAD\""));
        Files.writeString(secondTestCase, read(secondTestCase)
                .replace("COMMON-VERIFY-TC-001", "COMMON-VERIFY-TC-002")
                .replace("actual_samples/actual_payload.json", "actual_samples/actual_payload_bad.json"));
        Files.writeString(suite, read(suite).replace("  - test_case.yaml", "  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_verify");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("test_case_id: COMMON-VERIFY-CAPABILITY-v0.2-MULTI")
                .contains("test_count: 2")
                .doesNotContain("test_case_id: COMMON-VERIFY-TC-002");
        assertThat(read(extractPath(result.stdout(), "result_json")))
                .contains("\"test_case_id\": \"COMMON-VERIFY-CAPABILITY-v0.2-MULTI\"")
                .contains("\"test_case_id\": \"COMMON-VERIFY-TC-002\"")
                .contains("\"status\": \"failed\"");
    }

    @Test
    void schemaMatchValidatesArrayItems() throws Exception {
        Path suite = mutableCommonVerify("schema_array_items");
        Path schema = suite.getParent().resolve("expected_results/expected_schema.json");
        Files.writeString(schema, read(schema).replace(
                "\"items\": {\n      \"type\": \"array\"\n    }",
                "\"items\": {\n      \"type\": \"array\",\n      \"items\": {\n        \"type\": \"object\",\n        \"required\": [\"sku\", \"qty\"],\n        \"properties\": {\n          \"sku\": {\"type\": \"string\"},\n          \"qty\": {\"type\": \"integer\"}\n        }\n      }\n    }"));
        Path actual = suite.getParent().resolve("actual_samples/actual_payload.json");
        Files.writeString(actual, read(actual).replace("\"qty\": 1", "\"qty\": \"one\""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_verify");

        assertThat(result.exit()).isEqualTo(1);
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("diffs/payload_schema_matches.diff")))
                .contains("$.items[0].qty")
                .contains("expected type integer");
    }

    @Test
    void assertionEvidenceAndDiffMaskRawSecrets() throws Exception {
        Path suite = mutableCommonVerify("secret_masking");
        Path actual = suite.getParent().resolve("actual_samples/actual_payload.json");
        Files.writeString(actual, read(actual).replace(
                "\"id\": \"ORDER-100\"",
                "\"id\": \"ORDER-100\", \"password\": \"raw-secret-value\""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_verify");

        assertThat(result.exit()).isEqualTo(1);
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("assertions/payload_matches.yaml")))
                .contains("raw_secret_found: true")
                .doesNotContain("raw-secret-value");
        assertThat(read(evidenceDir.resolve("diffs/payload_matches.diff")))
                .contains("***MASKED***")
                .doesNotContain("raw-secret-value");
    }

    @Test
    void commonVerifyRunBlocksClearlyForMissingExpectedOrActualArtifactRefs() throws Exception {
        Path missingExpected = mutableCommonVerify("missing_expected");
        Files.delete(missingExpected.getParent().resolve("expected_results/expected_payload.json"));
        Path missingActual = mutableCommonVerify("missing_actual");
        Files.delete(missingActual.getParent().resolve("actual_samples/actual_payload.json"));

        CommandResult expectedResult = execute("run", "--suite", missingExpected.toString(), "--profile", "local_verify");
        CommandResult actualResult = execute("run", "--suite", missingActual.toString(), "--profile", "local_verify");

        assertThat(expectedResult.exit()).isEqualTo(1);
        assertThat(expectedResult.stdout())
                .contains("run_status: blocked")
                .contains("reason: unresolved_artifact_ref")
                .contains("expected_results/expected_payload.json");
        assertThat(actualResult.exit()).isEqualTo(1);
        assertThat(actualResult.stdout())
                .contains("run_status: blocked")
                .contains("reason: unresolved_artifact_ref")
                .contains("actual_samples/actual_payload.json");
    }

    @Test
    void commonVerifyRunFailsClearlyForMissingJsonPointerFragment() throws Exception {
        Path suite = mutableCommonVerify("missing_json_fragment");
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase)
                .replace("expected_ref: expected_results/expected_payload.json", "expected_ref: expected_results/expected_payload.json#/missing")
                .replace("actual_ref: actual_samples/actual_payload.json", "actual_ref: actual_samples/actual_payload.json#/missing"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_verify");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(read(extractPath(result.stdout(), "result_json")))
                .contains("EXPECTED_ARTIFACT_MISSING")
                .contains("#/missing");
    }

    @Test
    void validateRejectsUnsupportedVerifyTypeEvenWithoutParameters() throws Exception {
        Path suite = mutableCommonVerify("unsupported_verify_without_parameters");
        Path testCase = suite.getParent().resolve("test_case.yaml");
        String text = read(testCase).replace("type: json_match", "type: not_a_verifier");
        int start = text.indexOf("      inputs:\n");
        int end = text.indexOf("    - id: payload_schema_matches", start);
        Files.writeString(testCase, text.substring(0, start) + text.substring(end));

        CommandResult validate = execute("validate", "--suite", suite.toString());

        assertThat(validate.exit()).isEqualTo(1);
        assertThat(validate.stdout())
                .contains("reason: unsupported_operation")
                .contains("not_a_verifier");
    }

    @Test
    void commonVerifyOptionsAreRequiredForCanonicalComparisonSemantics() throws Exception {
        Path ignorePathsSuite = mutableCommonVerify("ignore_paths_missing");
        Path ignoreOrderSuite = mutableCommonVerify("ignore_order_missing");
        Path normalizeSuite = mutableCommonVerify("normalize_missing");
        Files.writeString(ignorePathsSuite.getParent().resolve("test_case.yaml"),
                read(ignorePathsSuite.getParent().resolve("test_case.yaml"))
                        .replace("        ignore_paths: [metadata.generated_at]\n", ""));
        Files.writeString(ignoreOrderSuite.getParent().resolve("test_case.yaml"),
                read(ignoreOrderSuite.getParent().resolve("test_case.yaml"))
                        .replace("        ignore_order: true", "        ignore_order: false")
                        .replace("          value: \"true\"", "          value: \"false\""));
        Files.writeString(normalizeSuite.getParent().resolve("test_case.yaml"),
                read(normalizeSuite.getParent().resolve("test_case.yaml"))
                        .replace("        normalize: trim_lines", "        normalize: none"));

        assertThat(read(extractPath(execute("run", "--suite", ignorePathsSuite.toString(), "--profile", "local_verify")
                        .stdout(), "result_json")))
                .contains("payload_matches")
                .contains("\"status\": \"failed\"");
        assertThat(read(extractPath(execute("run", "--suite", ignoreOrderSuite.toString(), "--profile", "local_verify")
                        .stdout(), "result_json")))
                .contains("payload_matches")
                .contains("\"status\": \"failed\"");
        assertThat(read(extractPath(execute("run", "--suite", normalizeSuite.toString(), "--profile", "local_verify")
                        .stdout(), "result_json")))
                .contains("file_matches")
                .contains("\"status\": \"failed\"");
    }

    @Test
    void pollingSuiteValidatesRunsAndRecordsPollingEvidence() {
        CommandResult validate = execute("validate", "--suite", POLLING_SUITE.toString());
        CommandResult run = execute("run", "--suite", POLLING_SUITE.toString(), "--profile", "local_polling");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: false")
                .contains("provider_type: common_verify");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"id\": \"event_is_published\"")
                .contains("\"type\": \"event_published\"")
                .contains("\"polling\"")
                .contains("\"attempts\": 1");
        assertThat(read(evidenceDir.resolve("polling/event_is_published.yaml")))
                .contains("verifier_type: event_published")
                .contains("attempts: 1")
                .contains("last_observed_value:")
                .contains("final_status: passed");
    }

    @Test
    void pollingTimeoutProducesResultEvidenceAndLastObservedValue() throws Exception {
        Path suite = mutablePolling("polling_timeout");
        Path actual = suite.getParent().resolve("actual_samples/observed_event.json");
        Files.writeString(actual, read(actual).replace("\"published\": true", "\"published\": false"));
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase)
                .replace("timeout: PT1S", "timeout: PT0S")
                .replace("poll_interval: PT0S", "poll_interval: PT0S"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_polling");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("POLLING_TIMEOUT")
                .contains("\"last_observed_ref\": \"polling/event_is_published.yaml\"");
        assertThat(read(evidenceDir.resolve("polling/event_is_published.yaml")))
                .contains("failure_code: POLLING_TIMEOUT")
                .contains("last_observed_value:")
                .contains("published: false")
                .contains("final_status: failed");
    }

    @Test
    void pollingRejectsInvalidConfigAndReportRejectsMissingEvidenceRef() throws Exception {
        Path invalidSuite = mutablePolling("polling_invalid_config");
        Path testCase = invalidSuite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("poll_interval: PT0S", "poll_interval: not-a-duration"));

        CommandResult invalid = execute("run", "--suite", invalidSuite.toString(), "--profile", "local_polling");
        assertThat(invalid.exit()).isEqualTo(1);
        assertThat(invalid.stdout())
                .contains("run_status: blocked")
                .contains("reason: invalid_polling_config")
                .contains("owner_action:");

        CommandResult run = execute("run", "--suite", POLLING_SUITE.toString(), "--profile", "local_polling");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        Files.delete(evidenceDir.resolve("polling/event_is_published.yaml"));

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_ref")
                .contains("polling/event_is_published.yaml");
    }

    private Path mutableCommonVerify(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/provider_capability/common_verify"), target);
        return target.resolve("suite_manifest.yaml");
    }

    private Path mutablePolling(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/provider_capability/polling"), target);
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

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path extractPath(String output, String key) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(key + ": ")) {
                return Path.of(line.substring((key + ": ").length()).trim());
            }
        }
        throw new AssertionError("Missing " + key + " in output:\n" + output);
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Failed to read " + path, e);
        }
    }

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
