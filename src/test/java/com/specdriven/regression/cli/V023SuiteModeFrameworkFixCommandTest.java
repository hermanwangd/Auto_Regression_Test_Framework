package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V023SuiteModeFrameworkFixCommandTest {

    private static final Path CONTRACT_BASELINE_SUITE = Path.of("samples/90-compatibility/legacy-v0.2/10-contract-baseline/mixed_wiremock_jdbc_nats/suite_manifest.yaml");
    private static final Path DUMMY_REST_SUITE = Path.of("samples/90-compatibility/dummy_rest/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void mixedLegacySuiteRefusesJdbcBeforeStartingAnyProvider() {
        String suite = CONTRACT_BASELINE_SUITE.toAbsolutePath().toString();
        CommandResult dry = execute("run", "--root", tempDir.toString(), "--suite", suite, "--profile", "ci", "--dry-run");
        assertThat(dry.exit()).as(dry.stdout()).isZero();
        CommandResult run = execute("run", "--root", tempDir.toString(), "--suite", suite, "--profile", "ci");
        assertThat(run.exit()).as(run.stdout()).isOne();
        assertThat(run.stdout()).contains("run_status: blocked", "reason: legacy_db_execution_disabled",
                "provider_runtime_invoked: false").doesNotContain("result_json:", "evidence_dir:");
        assertThat(tempDir.resolve("target")).doesNotExist();
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
}
