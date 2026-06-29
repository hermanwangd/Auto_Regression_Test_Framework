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

class ContractBaselineCommandTest {

    private static final Path BASELINE_SUITE = Path.of("samples/contract_baseline/suite_manifest.yaml");
    private static final Path BASELINE_RESULT = Path.of("samples/contract_baseline/result/sample_result.json");

    @TempDir
    Path tempDir;

    @Test
    void contractBaselineArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "schemas/test_case_dsl.v0.2.schema.yaml",
                "schemas/provider_contract.v0.2.schema.yaml",
                "schemas/provider_instance.v0.2.schema.yaml",
                "schemas/execution_profile.v0.2.schema.yaml",
                "schemas/environment_binding.v0.2.schema.yaml",
                "schemas/suite_manifest.v0.2.schema.yaml",
                "schemas/result.v0.2.schema.yaml",
                "samples/contract_baseline/suite_manifest.yaml",
                "samples/contract_baseline/test_case.yaml",
                "samples/contract_baseline/provider_contracts/wiremock_http_mock.yaml",
                "samples/contract_baseline/provider_contracts/jdbc.yaml",
                "samples/contract_baseline/provider_contracts/nats.yaml",
                "samples/contract_baseline/provider_instances/wiremock_payment_api.yaml",
                "samples/contract_baseline/provider_instances/oracle_database.yaml",
                "samples/contract_baseline/provider_instances/nats_event_bus.yaml",
                "samples/contract_baseline/execution_profiles/ci_pr.yaml",
                "samples/contract_baseline/execution_profiles/sit_regression.yaml",
                "samples/contract_baseline/environment_bindings/ci.yaml",
                "samples/contract_baseline/environment_bindings/sit.yaml",
                "samples/contract_baseline/result/sample_result.json",
                "samples/contract_baseline/evidence/evidence_index.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void validateSuitePassesForContractBaselineSample() {
        CommandResult result = execute("validate", "--suite", BASELINE_SUITE.toString());

        assertThat(result.exit()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: RP-FWK-CONTRACT-SAMPLE-regression")
                .contains("provider_instances_used:")
                .contains("wiremock-payment-api")
                .contains("oracle-database")
                .contains("nats-event-bus");
    }

    @Test
    void dryRunSuiteResolvesContractBaselineWithoutProviderRuntime() {
        CommandResult result = execute("run", "--suite", BASELINE_SUITE.toString(), "--dry-run");

        assertThat(result.exit()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false")
                .contains("resolved_execution_plan:")
                .contains("provider_id: wiremock-payment-api")
                .contains("provider_type: wiremock_http_mock")
                .contains("provider_id: oracle-database")
                .contains("provider_type: jdbc_database")
                .contains("provider_id: nats-event-bus")
                .contains("provider_type: nats_messaging");
    }

    @Test
    void reportSuiteResultPrintsDeterministicSummary() {
        CommandResult result = execute("report", "--result", BASELINE_RESULT.toString());

        assertThat(result.exit()).as(result.stderr()).isZero();
        assertThat(result.stdout())
                .contains("report_status: passed")
                .contains("test_case_id: RP-FWK-CONTRACT-SAMPLE-TC-001")
                .contains("profile: ci")
                .contains("provider_results_count: 3")
                .contains("verify_results_count: 3")
                .contains("release_evidence_eligible: false");
    }

    @Test
    void validateSuiteRejectsLegacyField() throws Exception {
        Path suite = mutableBaseline();
        Files.writeString(suite.getParent().resolve("test_case.yaml"),
                Files.readString(suite.getParent().resolve("test_case.yaml")) + "\nrp_id: RP-LEGACY\n");

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: rp_id")
                .contains("reason: prohibited_legacy_field")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsGovernanceField() throws Exception {
        Path suite = mutableBaseline();
        Files.writeString(suite.getParent().resolve("test_case.yaml"),
                Files.readString(suite.getParent().resolve("test_case.yaml")) + "\nrelease_gate: approved\n");

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: release_gate")
                .contains("reason: prohibited_governance_field")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsRawSecret() throws Exception {
        Path suite = mutableBaseline();
        Path binding = suite.getParent().resolve("environment_bindings/ci.yaml");
        Files.writeString(binding, Files.readString(binding)
                .replace("secret_ref: generated://oracle-ephemeral.password", "value: raw-password"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: raw_secret")
                .contains("field_path:")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsMissingProviderInstance() throws Exception {
        Path suite = mutableBaseline();
        Files.delete(suite.getParent().resolve("provider_instances/oracle_database.yaml"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_provider_instance")
                .contains("provider_id: oracle-database")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsMissingEnvironmentBinding() throws Exception {
        Path suite = mutableBaseline();
        Files.delete(suite.getParent().resolve("environment_bindings/ci.yaml"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_environment_binding")
                .contains("profile: ci")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsUnknownProviderType() throws Exception {
        Path suite = mutableBaseline();
        Path providerInstance = suite.getParent().resolve("provider_instances/oracle_database.yaml");
        Files.writeString(providerInstance, Files.readString(providerInstance)
                .replace("provider_type: jdbc_database", "provider_type: unknown_database"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unknown_provider_type")
                .contains("provider_type: unknown_database")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsUnsupportedOperation() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("operation: verify_requests", "operation: call_api"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_operation")
                .contains("operation: call_api")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsUnsupportedBindAs() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("bind_as: mock.request_filter", "bind_as: request.body"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_bind_as")
                .contains("bind_as: request.body")
                .contains("owner_action:");
    }

    @Test
    void reportFailsClearlyForMissingOrInvalidResultJson() throws Exception {
        Path invalid = tempDir.resolve("invalid-result.json");
        Files.writeString(invalid, "{\"status\":\"passed\"}");

        CommandResult missing = execute("report", "--result", tempDir.resolve("missing.json").toString());
        CommandResult invalidResult = execute("report", "--result", invalid.toString());

        assertThat(missing.exit()).isEqualTo(1);
        assertThat(missing.stderr()).contains("Missing result JSON:");
        assertThat(invalidResult.exit()).isEqualTo(1);
        assertThat(invalidResult.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field")
                .contains("owner_action:");
    }

    private Path mutableBaseline() throws IOException {
        Path target = tempDir.resolve("contract_baseline");
        copyDirectory(Path.of("samples/contract_baseline"), target);
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

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
