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

class NatsProviderCapabilityCommandTest {

    private static final Path NATS_SUITE = Path.of("samples/provider_capability/nats/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void natsSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/provider_capability/nats/suite_manifest.yaml",
                "samples/provider_capability/nats/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/nats.yaml",
                "samples/provider_capability/nats/provider_instances/local_nats.yaml",
                "samples/provider_capability/nats/env_profiles/local_nats.yaml",
                "samples/provider_capability/nats/execution_profiles/local_nats.yaml",
                "samples/provider_capability/nats/environment_bindings/local_nats.yaml",
                "samples/provider_capability/nats/fixtures/event_input.json",
                "samples/provider_capability/nats/expected_results/event_expected.json",
                "samples/provider_capability/nats/result/expected_result_shape.json",
                "samples/provider_capability/nats/evidence/expected_evidence_index.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void natsSuiteValidatesThroughPublicCli() {
        CommandResult result = execute("validate", "--suite", NATS_SUITE.toString());

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: NATS-CAPABILITY-v0.2")
                .contains("local-nats-event-bus")
                .contains("nats");
    }

    @Test
    void natsSuiteRunsProviderRuntimeAndReportConsumesGeneratedResult() {
        CommandResult run = execute("run", "--suite", NATS_SUITE.toString(), "--profile", "local_nats");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: nats")
                .contains("provider_id: local-nats-event-bus")
                .contains("runtime_mode: mock")
                .contains("subject: orders.ready")
                .contains("evidence_classification: framework_provider_capability_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertEvidenceFilesExist(evidenceDir);

        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"suite_id\": \"NATS-CAPABILITY-v0.2\"")
                .contains("\"provider_type\": \"nats\"")
                .contains("\"provider_id\": \"local-nats-event-bus\"")
                .contains("\"runtime_mode\": \"mock\"")
                .contains("\"subject\": \"orders.ready\"")
                .contains("\"status\": \"passed\"")
                .contains("\"matched\": true")
                .contains("\"event_evidence_ref\"")
                .contains("\"release_evidence_eligible\": false")
                .doesNotContain("nats://")
                .doesNotContain("plain-text-secret");
        assertThat(read(evidenceDir.resolve("provider-evidence/nats/event_payload_match.yaml")))
                .contains("provider_type: nats")
                .contains("provider_id: local-nats-event-bus")
                .contains("subject: orders.ready")
                .contains("consume_from: test_start_time")
                .contains("matched: true")
                .contains("masked_observed_payload:")
                .contains("raw_secret_found: false");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: NATS-CAPABILITY-v0.2")
                .contains("batch_id: BATCH-NATS-")
                .contains("run_id: RUN-NATS-")
                .contains("test_case_id: NATS-CAPABILITY-TC-001")
                .contains("status: passed");
    }

    @Test
    void natsSuiteRunsAllTestsWithSharedProfile() throws Exception {
        Path suite = mutableNats();
        Path secondTestCase = suite.getParent().resolve("second_test_case.yaml");
        Files.copy(suite.getParent().resolve("test_case.yaml"), secondTestCase);
        Files.writeString(secondTestCase, read(secondTestCase)
                .replace("NATS-CAPABILITY-TC-001", "NATS-CAPABILITY-TC-002"));
        Files.writeString(suite, read(suite)
                .replace("  - test_case.yaml", "  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("test_count: 2")
                .contains("profile: local_nats");
        Path resultJson = extractPath(run.stdout(), "result_json");
        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"test_case_id\": \"NATS-CAPABILITY-v0.2-MULTI\"")
                .contains("\"test_count\": 2")
                .contains("\"test_case_id\": \"NATS-CAPABILITY-TC-001\"")
                .contains("\"test_case_id\": \"NATS-CAPABILITY-TC-002\"");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("test_case_id: NATS-CAPABILITY-TC-001")
                .contains("test_case_id: NATS-CAPABILITY-TC-002");
    }

    @Test
    void natsValidateRejectsMissingSubjectBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path binding = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(binding, read(binding).replace("""
              subject:
                value: orders.ready
        """, ""));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_binding_key")
                .contains("field_path: providers.local-nats-event-bus.binding_keys.subject")
                .contains("provider_type: nats");
    }

    @Test
    void natsValidateRejectsRawConnectionValue() throws Exception {
        Path suite = mutableNats();
        Path binding = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(binding, read(binding)
                .replace("local_ref: approved_local_nats_ref", "value: nats://plain-text-secret:4222"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: raw_secret");
    }

    @Test
    void natsValidateRejectsMissingConnectionBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path binding = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(binding, read(binding)
                .replace("      connection:\n        local_ref: approved_local_nats_ref\n", ""));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_binding_key")
                .contains("field_path: providers.local-nats-event-bus.binding_keys.connection")
                .contains("provider_type: nats");
    }

    @Test
    void natsValidateRejectsInvalidProviderInstanceBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path instance = suite.getParent().resolve("provider_instances/local_nats.yaml");
        Files.writeString(instance, read(instance).replace("provider_type: nats", "provider_type: missing_runtime"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unknown_provider_type")
                .contains("provider_type: missing_runtime");
    }

    @Test
    void natsValidateRejectsUnsupportedVerifierBindAsBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("expected_ref:", "unsupported.topic:"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_input")
                .contains("operation: event_payload_match")
                .contains("provider_type: nats");
    }

    @Test
    void natsValidateRejectsUnsupportedOperationBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("operation: nats_publish", "operation: unsupported_publish"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_operation")
                .contains("operation: unsupported_publish")
                .contains("provider_type: nats");
    }

    @Test
    void natsValidateRejectsRefsOutsideSuiteRootBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("ref: fixtures/event_input.json", "ref: ../outside.json"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: ref_outside_suite_root")
                .contains("field_path: execute.publish_event.inputs.payload_ref")
                .contains("owner_action:");
    }

    @Test
    void natsValidateRejectsInvalidDurationBeforeExecution() throws Exception {
        Path suite = mutableNats();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("value: PT5S", "value: not-a-duration"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_duration")
                .contains("input `timeout`")
                .contains("owner_action:");
    }

    @Test
    void natsRunFailsOwnerActionablyOnPayloadMismatch() throws Exception {
        Path suite = mutableNats();
        Path expected = suite.getParent().resolve("expected_results/event_expected.json");
        Files.writeString(expected, read(expected).replace("\"status\": \"READY\"", "\"status\": \"SHIPPED\""));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("PAYLOAD_MISMATCH")
                .contains("\"classification\": \"ASSERTION_FAILED\"");
    }

    @Test
    void natsRunFailsOwnerActionablyOnSecretRefConnectionError() throws Exception {
        Path suite = mutableNats();
        Path binding = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(binding, read(binding)
                .replace("local_ref: approved_local_nats_ref", "secret_ref: generated://provider-capability/nats/connection"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("NATS_CONNECTION_FAILED")
                .contains("\"classification\": \"NATS_CONNECTION_FAILED\"")
                .doesNotContain("generated://provider-capability/nats/connection");
    }

    @Test
    void natsRunFailsOwnerActionablyWhenEventIsNotFound() throws Exception {
        Path suite = mutableNats();
        Path expected = suite.getParent().resolve("expected_results/event_expected.json");
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(expected, read(expected).replace("\"subject\": \"orders.ready\"", "\"subject\": \"orders.missing\""));
        Files.writeString(testCase, read(testCase).replaceFirst(
                "ref: expected_results/event_expected.json#/subject",
                "ref: orders.ready"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("EVENT_NOT_FOUND")
                .contains("\"classification\": \"ASSERTION_FAILED\"");
    }

    @Test
    void natsReportRejectsMissingRequiredEvidenceRef() {
        CommandResult run = execute("run", "--suite", NATS_SUITE.toString(), "--profile", "local_nats");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        delete(evidenceDir.resolve("provider-evidence/nats/event_payload_match.yaml"));

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_ref")
                .contains("provider-evidence/nats/event_payload_match.yaml");
    }

    private void assertEvidenceFilesExist(Path evidenceDir) {
        List<String> evidenceFiles = List.of(
                "evidence_index.yaml",
                "provider-evidence/nats/publish_event.yaml",
                "provider-evidence/nats/event_published.yaml",
                "provider-evidence/nats/event_payload_match.yaml",
                "logs/execution.log",
                "assertions/event_published.yaml",
                "assertions/event_payload_match.yaml",
                "batch/batch.yaml");

        assertThat(evidenceFiles).allSatisfy(path -> assertThat(evidenceDir.resolve(path))
                .as(path)
                .isRegularFile());
    }

    private Path mutableNats() throws IOException {
        Path target = tempDir.resolve("nats_" + System.nanoTime());
        copyDirectory(Path.of("samples/provider_capability/nats"), target);
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
