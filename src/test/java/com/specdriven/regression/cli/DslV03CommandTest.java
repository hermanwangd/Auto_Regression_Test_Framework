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
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DslV03CommandTest {

    private static final Path V03_SUITE = Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml");
    private static final Path V03_HTTP_MOCK_SUITE =
            Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void v03ContractArtifactsAreCheckedInAtRequiredPaths() throws IOException {
        List<String> requiredPaths = List.of(
                "schemas/test_case_dsl.v0.3.schema.yaml",
                "schemas/provider_contract.v0.3.schema.yaml",
                "schemas/env_profile.v0.3.schema.yaml",
                "schemas/suite_manifest.v0.3.schema.yaml",
                "samples/00-getting-started/golden_e2e/suite_manifest.yaml",
                "docs/02-architecture/contracts/provider-contracts/sample_fake_provider_v0_3.yaml",
                "docs/02-architecture/contracts/provider-contracts/http_mock_v0_3.yaml",
                "docs/02-architecture/contracts/provider-contracts/rest_client_v0_3.yaml",
                "samples/00-getting-started/golden_e2e/env_profiles/local_v03.yaml",
                "samples/00-getting-started/golden_e2e/test_cases/golden_success.yaml",
                "samples/00-getting-started/golden_e2e/fixtures/input.json",
                "samples/00-getting-started/golden_e2e/fixtures/setup_fixture.yaml",
                "samples/00-getting-started/golden_e2e/fixtures/cleanup_fixture.yaml",
                "samples/00-getting-started/golden_e2e/expected_results/expected_output.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/suite_manifest.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/env_profiles/local_v03.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/test_cases/payment_success.yaml",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/fixtures/wiremock/payment_success_stub.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/fixtures/payment_request.json",
                "samples/20-provider-capability-p0/http/rest_client_with_wiremock/expected_results/payment_response.json");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
        assertThat(Files.readString(Path.of("docs/02-architecture/contracts/provider-contracts/sample_fake_provider_v0_3.yaml")))
                .contains("contract_version: v0.3")
                .contains("provider_contract: sample_fake_provider.v0.3");
        assertThat(Files.readString(Path.of("docs/02-architecture/contracts/provider-contracts/http_mock_v0_3.yaml")))
                .contains("contract_version: v0.3")
                .contains("provider_contract: http_mock.v0.3")
                .contains("runtime_implementation: wiremock");
        assertThat(Files.readString(Path.of("docs/02-architecture/contracts/provider-contracts/rest_client_v0_3.yaml")))
                .contains("contract_version: v0.3")
                .contains("provider_contract: rest_client.v0.3");
    }

    @Test
    void validateV03SuiteResolvesSuiteTargetsWithoutProviderInstances() {
        CommandResult result = execute("validate", "--suite", V03_SUITE.toString(), "--profile", "local_v03");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("targets_used:")
                .contains("sample_runtime")
                .contains("provider_contracts_used:")
                .contains("sample_fake_provider.v0.3")
                .contains("provider_types_used:")
                .contains("sample_fake_provider")
                .doesNotContain("sample-fake-runtime");
    }

    @Test
    void dryRunV03SuiteEmitsResolvedPlanWithoutProviderRuntime() {
        CommandResult result = execute("run", "--suite", V03_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("target: sample_runtime")
                .contains("provider_contract: sample_fake_provider.v0.3")
                .contains("provider_type: sample_fake_provider")
                .contains("profile: local_v03")
                .contains("runtime_mode: stub")
                .contains("phase: execute")
                .contains("operation: execute_sample")
                .doesNotContain("provider_id:");
    }

    @Test
    void validateV03HttpMockRestClientSampleResolvesProtocolTargetsWithoutProviderInstances() {
        CommandResult result = execute("validate", "--suite", V03_HTTP_MOCK_SUITE.toString(), "--profile", "local_v03");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: HTTP-MOCK-REST-CLIENT-v0.3")
                .contains("targets_used:")
                .contains("payment_mock")
                .contains("payment_api")
                .contains("provider_contracts_used:")
                .contains("http_mock.v0.3")
                .contains("rest_client.v0.3")
                .contains("provider_types_used:")
                .contains("http_mock")
                .contains("rest_client")
                .doesNotContain("payment-mock")
                .doesNotContain("payment-api-client");
    }

    @Test
    void dryRunV03HttpMockRestClientSampleEmitsProtocolTargetPlan() {
        CommandResult result = execute(
                "run", "--suite", V03_HTTP_MOCK_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("suite_id: HTTP-MOCK-REST-CLIENT-v0.3")
                .contains("target: payment_mock")
                .contains("provider_contract: http_mock.v0.3")
                .contains("provider_type: http_mock")
                .contains("target: payment_api")
                .contains("provider_contract: rest_client.v0.3")
                .contains("provider_type: rest_client")
                .doesNotContain("provider_id:");
    }

    @Test
    void runV03GoldenSuiteProducesReportableResultWithoutProviderInstance() throws Exception {
        CommandResult run = execute("run", "--suite", V03_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("provider_runtime_executed: true")
                .doesNotContain("sample-fake-runtime");
        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"framework_version\": \"0.3.0\"")
                .contains("\"dsl_version\": \"v0.3\"")
                .contains("\"target\": \"sample_runtime\"")
                .contains("\"provider_contract\": \"sample_fake_provider.v0.3\"")
                .doesNotContain("sample-fake-runtime");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: GOLDEN-E2E-v0.3")
                .contains("status: passed")
                .doesNotContain("sample-fake-runtime");
    }

    @Test
    void runV03GoldenSuiteResolvesAssertionActualByDeclaredStepId() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("id: produce_sample_output", "id: make_sample_output")
                .replace("step://produce_sample_output/actual_json.status", "step://make_sample_output/actual_json.status");
        Files.writeString(testCase, yaml);

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout()).contains("run_status: passed");
        String result = Files.readString(extractPath(run.stdout(), "result_json"));
        assertThat(result)
                .contains("\"id\": \"make_sample_output\"")
                .contains("\"status\": \"passed\"");
    }

    @Test
    void validateV03SuiteRejectsLegacyProviderIdField() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        Files.writeString(testCase, Files.readString(testCase) + "\nprovider_id: sample-fake-runtime\n");

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: provider_id")
                .contains("reason: prohibited_legacy_field")
                .contains("category: VALIDATION_ERROR");
    }

    @Test
    void validateV03SuiteRejectsAssertionSelfReference() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("step://produce_sample_output/actual_json.status", "step://status_is_ok/result");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: verify.status_is_ok.assert.actual")
                .contains("reason: invalid_step_ref")
                .contains("Reference only prior steps");
    }

    @Test
    void validateV03SuiteRejectsNestedLegacyBindAsField() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("      sample.input_ref: artifact://fixtures/input.json",
                        "      sample.input_ref:\n"
                                + "        ref: artifact://fixtures/input.json\n"
                                + "        bind_as: sample.input_ref");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: execute[0].with.sample.input_ref.bind_as")
                .contains("reason: prohibited_legacy_field");
    }

    @Test
    void validateV03SuiteRejectsMissingVerifyType() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("    type: assertion\n", ""));

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: verify.status_is_ok.type")
                .contains("reason: missing_verify_type");
    }

    @Test
    void validateV03SuiteRejectsMissingAssertDefinition() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase).replace(
                "    assert:\n"
                        + "      actual: step://produce_sample_output/actual_json.status\n"
                        + "      operator: equals\n"
                        + "      expected: OK\n",
                "");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: verify.status_is_ok.assert")
                .contains("reason: missing_assert_definition");
    }

    @Test
    void validateV03SuiteRejectsUnknownAssertionOperator() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("operator: equals", "operator: equlas"));

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: verify.status_is_ok.assert.operator")
                .contains("reason: unsupported_assertion_operator");
    }

    @Test
    void runV03SuiteResolvesArtifactAliasJsonPointerAndNumericOperator() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        Files.writeString(suite, Files.readString(suite).replace("  fixtures: fixtures/", "  payloads: fixtures/"));
        String yaml = Files.readString(testCase)
                .replace("artifact://fixtures/", "artifact://payloads/")
                .replace(
                        "      actual: step://produce_sample_output/actual_json.status\n"
                                + "      operator: equals\n"
                                + "      expected: OK",
                        "      actual_ref: artifact://payloads/input.json#/amount\n"
                                + "      operator: gte\n"
                                + "      expected: 100");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout()).contains("run_status: passed");
    }

    @Test
    void validateV03SuiteRejectsUnknownVerifyType() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase).replace("type: assertion", "type: custom_check");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: verify.status_is_ok.type")
                .contains("reason: unsupported_verify_type");
    }

    @Test
    void validateV03LeafSuiteRejectsManifestAndTestCaseVersionMismatch() throws Exception {
        Path suite = mutableV03Golden();
        Files.writeString(suite, Files.readString(suite).replace("manifest_version: v0.3", "manifest_version: v0.2"));

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("reason: mixed_dsl_versions")
                .contains("field_path: manifest_version");
    }

    @Test
    void validateV03SuiteRejectsGenericProviderCheckExpect() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase).replace(
                "  - id: status_is_ok\n    type: assertion",
                "  - id: status_is_ok\n    type: provider_check\n    target: sample_runtime\n    op: execute_sample\n    with:\n      sample.input_ref: artifact://fixtures/input.json\n    expect:\n      - actual: status\n        operator: equals\n        expected: OK\n\n  - id: status_assertion\n    type: assertion");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: verify.status_is_ok.expect")
                .contains("reason: prohibited_provider_check_expect");
    }

    @Test
    void validateV03SuiteRejectsLegacyDotGeneratedReference() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("artifact://fixtures/input.json", "generated://sample_runtime.actual_json"));

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: invalid_generated_ref");
    }

    @Test
    void validateV03SuiteRejectsDuplicateStepIds() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("id: cleanup_sample_workspace", "id: produce_sample_output");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: cleanup.produce_sample_output.id")
                .contains("reason: duplicate_step_id");
    }

    @Test
    void validateV03SuiteRejectsSyntheticProviderContractAlias() throws Exception {
        Path suite = mutableV03Golden();
        String manifest = Files.readString(suite)
                .replace("provider_contract: sample_fake_provider.v0.3", "provider_contract: sample_fake_provider");
        Files.writeString(suite, manifest);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: targets.sample_runtime.provider_contract")
                .contains("reason: missing_provider_contract");
    }

    @Test
    void validateV03SuiteRejectsDuplicateProviderContractIdsBeforeRuntime() throws Exception {
        Path suite = mutableV03Golden();
        Path root = suite.getParent();
        Files.createDirectories(root.resolve("custom-provider-contracts"));
        Files.writeString(root.resolve("custom-provider-contracts/duplicate_sample_fake_provider.yaml"),
                Files.readString(Path.of("docs/02-architecture/contracts/provider-contracts/sample_fake_provider_v0_3.yaml"))
                        .replace("purpose: Framework-owned deterministic fake provider for DSL v0.3 golden lifecycle verification only.",
                                "purpose: Duplicate test contract that must be rejected before runtime."));
        String manifest = Files.readString(suite)
                .replace("default_profile: local_v03",
                        "default_profile: local_v03\n"
                                + "\n"
                                + "provider_contract_resolution:\n"
                                + "  mode: suite_override\n"
                                + "  custom_provider_contracts: custom-provider-contracts");
        Files.writeString(suite, manifest);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("reason: duplicate_provider_contract")
                .contains("sample_fake_provider.v0.3");
    }

    @Test
    void validateV03SuiteRejectsInvalidProviderContractVersion() throws Exception {
        Path suite = mutableV03Golden();
        String manifest = Files.readString(suite)
                .replace("provider_contract: sample_fake_provider.v0.3", "provider_contract: sample_fake_provider.v0.2");
        Files.writeString(suite, manifest);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: targets.sample_runtime.provider_contract")
                .contains("reason: invalid_provider_contract_version");
    }

    @Test
    void validateV03SuiteRejectsInvalidArtifactJsonPointer() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("artifact://expected_results/expected_output.json",
                        "artifact://expected_results/expected_output.json#status");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: execute.produce_sample_output.with.sample.expected_ref")
                .contains("reason: invalid_json_pointer");
    }

    @Test
    void validateV03SuiteRejectsMissingArtifactJsonPointerFragment() throws Exception {
        Path suite = mutableV03Golden();
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("artifact://expected_results/expected_output.json",
                        "artifact://expected_results/expected_output.json#/missing");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: execute.produce_sample_output.with.sample.expected_ref")
                .contains("reason: missing_json_pointer_fragment");
    }

    @Test
    void validateV03SuiteRejectsUnsafeArtifactRefs() throws Exception {
        for (InvalidArtifactRefCase invalid : invalidArtifactRefs()) {
            Path suite = mutableV03Golden();
            Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
            String yaml = Files.readString(testCase)
                    .replace("artifact://fixtures/input.json", invalid.ref());
            Files.writeString(testCase, yaml);

            CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

            assertThat(result.exit()).as(invalid.ref() + "\n" + result.stdout() + result.stderr()).isEqualTo(1);
            assertThat(result.stdout())
                    .as(invalid.ref())
                    .contains("validation_status: failed")
                    .contains("field_path: setup.prepare_sample_workspace.with.fixture.input_ref")
                    .contains("reason: " + invalid.reason());
        }
    }

    @Test
    void validateV03SuiteRejectsArtifactSymlinkEscape() throws Exception {
        Path suite = mutableV03Golden();
        Path outside = tempDir.resolve("outside.json");
        Files.writeString(outside, "{\"status\":\"OK\"}");
        Path link = suite.getParent().resolve("fixtures/outside_link.json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are not supported on this filesystem: " + e.getMessage());
        }
        Path testCase = suite.getParent().resolve("test_cases/golden_success.yaml");
        String yaml = Files.readString(testCase)
                .replace("artifact://fixtures/input.json", "artifact://fixtures/outside_link.json");
        Files.writeString(testCase, yaml);

        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("field_path: setup.prepare_sample_workspace.with.fixture.input_ref")
                .contains("reason: ref_outside_suite_root");
    }

    private Path mutableV03Golden() throws IOException {
        Path source = Path.of("samples/00-getting-started/golden_e2e");
        Path target = Files.createTempDirectory(tempDir, "golden_v03_");
        copyDirectory(source, target);
        return target.resolve("suite_manifest.yaml");
    }

    private List<InvalidArtifactRefCase> invalidArtifactRefs() {
        return List.of(
                new InvalidArtifactRefCase("artifact://fixtures/../expected_results/expected_output.json",
                        "invalid_artifact_ref"),
                new InvalidArtifactRefCase("artifact://fixtures//tmp/secret.json", "invalid_artifact_ref"),
                new InvalidArtifactRefCase("artifact://fixtures/~/secret.json", "invalid_artifact_ref"),
                new InvalidArtifactRefCase("artifact://fixtures/C:secret.json", "invalid_artifact_ref"),
                new InvalidArtifactRefCase("artifact://fixtures/sub\\secret.json", "invalid_artifact_ref"),
                new InvalidArtifactRefCase("artifact://fixtures/%2e%2e/secret.json", "invalid_artifact_ref"),
                new InvalidArtifactRefCase("artifact://fixtures/missing.json", "unresolved_artifact_ref"));
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

    private record CommandResult(int exit, String stdout, String stderr) {
    }

    private record InvalidArtifactRefCase(String ref, String reason) {
    }
}
