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
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V023SuiteModeFrameworkFixCommandTest {

    private static final Path CONTRACT_BASELINE_SUITE = Path.of("samples/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml");
    private static final Path DUMMY_REST_SUITE = Path.of("samples/90-compatibility/dummy_rest/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void contractBaselineMixedProviderSuiteRunsAndReports() {
        CommandResult run = execute("run", "--suite", CONTRACT_BASELINE_SUITE.toString(), "--profile", "ci");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: wiremock_http_mock,jdbc,nats")
                .contains("test_count: 1")
                .contains("failed_count: 0");

        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"provider_type\": \"wiremock_http_mock\"")
                .contains("\"provider_type\": \"jdbc\"")
                .contains("\"provider_type\": \"nats\"")
                .contains("\"release_evidence_eligible\": false");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("provider_results_count: 3")
                .contains("release_evidence_eligible: false");
    }

    @Test
    void contractBaselineMixedProviderSuiteRunsWithEnvBackedNatsConnectionAndMaterializedClassification() throws Exception {
        Path suite = mutableCopy(CONTRACT_BASELINE_SUITE.getParent(), "contract_baseline_env_nats")
                .resolve("suite_manifest.yaml");
        Files.writeString(suite, read(suite) + """

                evidence_policy:
                  evidence_classification: local_ci_ephemeral_only
                  downstream_release_evidence: false
                """);
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("  tags: [contract, dry-run, p0-provider]",
                """
                  tags: [contract, dry-run, p0-provider]
                  evidence_classification: local_ci_ephemeral_only"""));
        Path envProfile = suite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(envProfile, read(envProfile)
                .replace("local_ref: approved_local_nats_ref", "secret_ref: env://NATS_CONNECTION"));

        try (FakeNatsServer server = new FakeNatsServer()) {
            System.setProperty("NATS_CONNECTION", "nats://127.0.0.1:" + server.port());
            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "ci");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            assertThat(run.stdout())
                    .contains("run_status: passed")
                    .contains("provider_types: wiremock_http_mock,jdbc,nats")
                    .contains("evidence_classification: local_ci_ephemeral_only")
                    .doesNotContain("nats://127.0.0.1");
            Path resultJson = extractPath(run.stdout(), "result_json");
            Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
            assertThat(read(resultJson))
                    .contains("\"provider_type\": \"nats\"")
                    .contains("\"evidence_classification\": \"local_ci_ephemeral_only\"")
                    .doesNotContain("nats://127.0.0.1");
            assertThat(read(evidenceDir.resolve("provider-evidence/nats/publish_payment_event.yaml")))
                    .contains("evidence_classification: local_ci_ephemeral_only")
                    .doesNotContain("nats://127.0.0.1");
        } finally {
            System.clearProperty("NATS_CONNECTION");
        }
    }

    @Test
    void validateRejectsUnmaterializedGeneratedConnectionRef() throws Exception {
        Path suite = mutableCopy(CONTRACT_BASELINE_SUITE.getParent(), "contract_baseline_unmaterialized_generated_ref")
                .resolve("suite_manifest.yaml");
        Path envProfile = suite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(envProfile, read(envProfile)
                .replace("local_ref: approved_local_h2_oracle",
                        "secret_ref: generated://oracle-ephemeral.connection"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "ci");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("provider_runtime_invoked: false")
                .contains("reason: unresolved_generated_ref")
                .contains("failure_code: CONFIGURATION_UNRESOLVED_GENERATED_REF")
                .contains("field_path: providers.oracle-database.bindings.connection.secret_ref")
                .contains("provider_id: oracle-database")
                .contains("owner_action:");
    }

    @Test
    void validateAllowsEnvProfileDeclaredDependencyGeneratedRef() throws Exception {
        Path suite = mutableCopy(DUMMY_REST_SUITE.getParent(), "dummy_rest_declared_dependency_output")
                .resolve("suite_manifest.yaml");
        Path envProfile = suite.getParent().resolve("env_profiles/local_dummy.yaml");
        Files.writeString(envProfile, read(envProfile)
                .replace("allowed_provisioners: [framework_demo_server]",
                        """
                        allowed_provisioners: [framework_demo_server]
                          generated_outputs:
                            - generated://dummy-rest-app.base_url""")
                .replace("""
                      base_url:
                        value: local://framework-demo-server""", """
                      base_url:
                        generated_ref: generated://dummy-rest-app.base_url"""));

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_dummy");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .doesNotContain("unresolved_generated_ref");
    }

    @Test
    void restClientReleaseEvidenceEligibilityCanBeEnabledByExplicitProductLabels() throws Exception {
        Path suite = mutableCopy(DUMMY_REST_SUITE.getParent(), "dummy_rest_product_candidate")
                .resolve("suite_manifest.yaml");
        markAsProductReleaseCandidate(suite);
        markAsProductReleaseCandidate(suite.getParent().resolve("test_case.yaml"));
        markAsProductReleaseCandidate(suite.getParent().resolve("provider_instances/dummy_rest_client.yaml"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_dummy");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(read(resultJson)).contains("\"release_evidence_eligible\": true");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout()).contains("release_evidence_eligible: true");
    }

    @Test
    void restClientReleaseEvidenceEligibilityRequiresSuiteTestAndProviderLabels() throws Exception {
        for (String missingLabelSource : new String[] { "suite", "test_case", "provider_instance" }) {
            Path suite = mutableCopy(DUMMY_REST_SUITE.getParent(), "dummy_rest_partial_candidate_" + missingLabelSource)
                    .resolve("suite_manifest.yaml");
            Path testCase = suite.getParent().resolve("test_case.yaml");
            Path providerInstance = suite.getParent().resolve("provider_instances/dummy_rest_client.yaml");
            markAsProductReleaseCandidate(suite);
            markAsProductReleaseCandidate(testCase);
            markAsProductReleaseCandidate(providerInstance);
            switch (missingLabelSource) {
                case "suite" -> markAsFrameworkOnly(suite);
                case "test_case" -> markAsFrameworkOnly(testCase);
                case "provider_instance" -> markAsFrameworkOnly(providerInstance);
                default -> throw new AssertionError("Unknown missing label source: " + missingLabelSource);
            }

            CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_dummy");

            assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
            Path resultJson = extractPath(run.stdout(), "result_json");
            assertThat(read(resultJson))
                    .as("missing release evidence label source: " + missingLabelSource)
                    .contains("\"release_evidence_eligible\": false");
        }
    }

    @Test
    void compatibilityRestClientSampleIsNotReleaseEvidenceEligible() {
        CommandResult run = execute("run", "--suite", DUMMY_REST_SUITE.toString(), "--profile", "local_dummy");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(read(resultJson)).contains("\"release_evidence_eligible\": false");
    }

    private void markAsProductReleaseCandidate(Path path) throws IOException {
        Files.writeString(path, read(path)
                .replace("evidence_classification: framework_provider_capability_only",
                        "evidence_classification: product_release_evidence_candidate")
                .replace("downstream_release_evidence: false", "downstream_release_evidence: true"));
    }

    private void markAsFrameworkOnly(Path path) throws IOException {
        Files.writeString(path, read(path)
                .replace("evidence_classification: product_release_evidence_candidate",
                        "evidence_classification: framework_provider_capability_only")
                .replace("downstream_release_evidence: true", "downstream_release_evidence: false"));
    }

    private Path mutableCopy(Path source, String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(source, target);
        return target;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
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

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Failed to read " + path, e);
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
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private record CommandResult(int exit, String stdout, String stderr) {
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
