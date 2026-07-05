package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NatsProviderCapabilityCommandTest {

    private static final Path NATS_SUITE = Path.of("samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void natsSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/20-provider-capability-p0/messaging/nats/suite_manifest.yaml",
                "samples/20-provider-capability-p0/messaging/nats/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/nats.yaml",
                "samples/20-provider-capability-p0/messaging/nats/provider_instances/local_nats.yaml",
                "samples/20-provider-capability-p0/messaging/nats/env_profiles/local_nats.yaml",
                "samples/20-provider-capability-p0/messaging/nats/execution_profiles/local_nats.yaml",
                "samples/20-provider-capability-p0/messaging/nats/environment_bindings/local_nats.yaml",
                "samples/20-provider-capability-p0/messaging/nats/fixtures/event_input.json",
                "samples/20-provider-capability-p0/messaging/nats/expected_results/event_expected.json",
                "samples/20-provider-capability-p0/messaging/nats/result/expected_result_shape.json",
                "samples/20-provider-capability-p0/messaging/nats/evidence/expected_evidence_index.yaml");

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
    void natsSuiteRunsWithEnvSecretBackedExternalConnectionAndMaterializedClassification() throws Exception {
        Path suite = mutableNats();
        markEvidenceClassification(suite, "local_ci_ephemeral_only");
        Path instance = suite.getParent().resolve("provider_instances/local_nats.yaml");
        Files.writeString(instance, read(instance)
                .replace("runtime_modes: [mock]", "runtime_modes: [mock, ephemeral]"));
        Path envProfile = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(envProfile, read(envProfile)
                .replace("allowed_runtime_modes: [mock]", "allowed_runtime_modes: [mock, ephemeral]")
                .replace("runtime_mode: mock", "runtime_mode: ephemeral")
                .replace("local_ref: approved_local_nats_ref", "secret_ref: env://NATS_CONNECTION"));

        try (FakeNatsServer server = new FakeNatsServer()) {
            System.setProperty("NATS_CONNECTION", "nats://127.0.0.1:" + server.port());
            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            assertThat(run.stdout())
                    .contains("run_status: passed")
                    .contains("runtime_mode: ephemeral")
                    .contains("evidence_classification: local_ci_ephemeral_only")
                    .doesNotContain("nats://127.0.0.1");
            Path resultJson = extractPath(run.stdout(), "result_json");
            Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
            assertThat(read(resultJson))
                    .contains("\"evidence_classification\": \"local_ci_ephemeral_only\"")
                    .doesNotContain("nats://127.0.0.1");
            assertThat(read(evidenceDir.resolve("provider-evidence/nats/publish_event.yaml")))
                    .contains("evidence_classification: local_ci_ephemeral_only")
                    .doesNotContain("nats://127.0.0.1");
        } finally {
            System.clearProperty("NATS_CONNECTION");
        }
    }

    @Test
    void natsExternalConnectionFailsWhenTcpEndpointDoesNotSpeakNatsProtocol() throws Exception {
        Path suite = mutableNats();
        Path instance = suite.getParent().resolve("provider_instances/local_nats.yaml");
        Files.writeString(instance, read(instance)
                .replace("runtime_modes: [mock]", "runtime_modes: [mock, ephemeral]"));
        Path envProfile = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(envProfile, read(envProfile)
                .replace("allowed_runtime_modes: [mock]", "allowed_runtime_modes: [mock, ephemeral]")
                .replace("runtime_mode: mock", "runtime_mode: ephemeral")
                .replace("local_ref: approved_local_nats_ref", "secret_ref: env://NATS_CONNECTION"));

        try (NonNatsTcpServer server = new NonNatsTcpServer()) {
            System.setProperty("NATS_CONNECTION", "nats://127.0.0.1:" + server.port());
            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

            assertThat(run.exit()).isEqualTo(1);
            assertThat(run.stdout())
                    .contains("run_status: failed")
                    .contains("failure_code: NATS_CONNECTION_FAILED")
                    .contains("owner_action: Verify the execution profile starts NATS")
                    .doesNotContain("nats://127.0.0.1");
            Path resultJson = extractPath(run.stdout(), "result_json");
            assertThat(read(resultJson))
                    .contains("\"status\": \"failed\"")
                    .contains("NATS_CONNECTION_FAILED")
                    .doesNotContain("nats://127.0.0.1");
        } finally {
            System.clearProperty("NATS_CONNECTION");
        }
    }

    @Test
    void natsExternalPublishDoesNotCacheEmptyProtocolOnlyObservation() throws Exception {
        Path suite = mutableNats();
        Path instance = suite.getParent().resolve("provider_instances/local_nats.yaml");
        Files.writeString(instance, read(instance)
                .replace("runtime_modes: [mock]", "runtime_modes: [mock, ephemeral]"));
        Path envProfile = suite.getParent().resolve("env_profiles/local_nats.yaml");
        Files.writeString(envProfile, read(envProfile)
                .replace("allowed_runtime_modes: [mock]", "allowed_runtime_modes: [mock, ephemeral]")
                .replace("runtime_mode: mock", "runtime_mode: ephemeral")
                .replace("local_ref: approved_local_nats_ref", "secret_ref: env://NATS_CONNECTION"));
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("PT5S", "PT0.3S"));

        try (ProtocolOnlyNatsServer server = new ProtocolOnlyNatsServer()) {
            System.setProperty("NATS_CONNECTION", "nats://127.0.0.1:" + server.port());
            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_nats");

            assertThat(run.exit()).isEqualTo(1);
            assertThat(run.stdout())
                    .contains("run_status: failed")
                    .contains("failure_code: EVENT_NOT_FOUND")
                    .doesNotContain("nats://127.0.0.1");
            Path resultJson = extractPath(run.stdout(), "result_json");
            assertThat(read(resultJson))
                    .contains("\"status\": \"failed\"")
                    .contains("EVENT_NOT_FOUND")
                    .doesNotContain("\"matched\": true");
        } finally {
            System.clearProperty("NATS_CONNECTION");
        }
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
        assertThat(result.stdout())
                .contains("failure_code: PAYLOAD_MISMATCH")
                .contains("failure_reason: Observed NATS event payload did not match expected JSON fields.")
                .contains("owner_action: Review expected payload fields and event evidence.");
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
        copyDirectory(Path.of("samples/20-provider-capability-p0/messaging/nats"), target);
        return target.resolve("suite_manifest.yaml");
    }

    private void markEvidenceClassification(Path suite, String classification) throws IOException {
        Files.writeString(suite, read(suite)
                .replace("evidence_classification: framework_provider_capability_only",
                        "evidence_classification: " + classification));
        Files.writeString(suite.getParent().resolve("test_case.yaml"), read(suite.getParent().resolve("test_case.yaml"))
                .replace("evidence_classification: framework_provider_capability_only",
                        "evidence_classification: " + classification));
        Files.writeString(suite.getParent().resolve("provider_instances/local_nats.yaml"),
                read(suite.getParent().resolve("provider_instances/local_nats.yaml"))
                        .replace("evidence_classification: framework_provider_capability_only",
                                "evidence_classification: " + classification));
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

    private static final class NonNatsTcpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;

        NonNatsTcpServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.thread = new Thread(this::serve, "non-nats-tcp-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                socket.getOutputStream().write("HELLO\r\n".getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (IOException ignored) {
                // Test server intentionally does not implement NATS.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class ProtocolOnlyNatsServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;

        ProtocolOnlyNatsServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.thread = new Thread(this::serve, "protocol-only-nats-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(500);
                    socket.getOutputStream().write("INFO {}\r\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    String line;
                    while ((line = readLine(socket)) != null) {
                        if (line.startsWith("PUB ")) {
                            int length = Integer.parseInt(line.split("\\s+")[2]);
                            socket.getInputStream().readNBytes(length);
                            readLine(socket);
                        } else if (line.startsWith("PING")) {
                            socket.getOutputStream().write("PONG\r\n".getBytes(StandardCharsets.UTF_8));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (IOException ignored) {
                    // Test server intentionally acknowledges protocol without delivering messages.
                }
            }
        }

        private String readLine(Socket socket) throws IOException {
            StringBuilder line = new StringBuilder();
            int next;
            while ((next = socket.getInputStream().read()) >= 0) {
                if (next == '\n') {
                    return line.toString().strip();
                }
                if (next != '\r') {
                    line.append((char) next);
                }
            }
            return line.length() == 0 ? null : line.toString();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private static final class FakeNatsServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;

        FakeNatsServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.thread = new Thread(this::serve, "fake-nats-server");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write("INFO {}\r\n".getBytes(StandardCharsets.UTF_8));
                String subject = "";
                String sid = "1";
                String line;
                while ((line = readLine(socket)) != null) {
                    if (line.startsWith("SUB ")) {
                        String[] parts = line.split("\\s+");
                        subject = parts.length > 1 ? parts[1] : subject;
                        sid = parts.length > 2 ? parts[2] : sid;
                    } else if (line.startsWith("PUB ")) {
                        String[] parts = line.split("\\s+");
                        String publishSubject = parts.length > 1 ? parts[1] : subject;
                        int length = Integer.parseInt(parts[parts.length - 1]);
                        byte[] payload = socket.getInputStream().readNBytes(length);
                        readLine(socket);
                        String response = "MSG " + publishSubject + " " + sid + " " + length + "\r\n"
                                + new String(payload, StandardCharsets.UTF_8) + "\r\n";
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    } else if (line.startsWith("PING")) {
                        socket.getOutputStream().write("PONG\r\n".getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                }
            } catch (IOException ignored) {
                // Test server is intentionally tiny; client assertions cover failures.
            }
        }

        private String readLine(Socket socket) throws IOException {
            StringBuilder line = new StringBuilder();
            int next;
            while ((next = socket.getInputStream().read()) >= 0) {
                if (next == '\n') {
                    return line.toString().strip();
                }
                if (next != '\r') {
                    line.append((char) next);
                }
            }
            return line.length() == 0 ? null : line.toString();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
