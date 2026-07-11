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
            Path.of("samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml");
    private static final Path REST_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/suite_manifest.yaml");
    private static final Path SOAP_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/soap_mock_http_client/suite_manifest.yaml");
    private static final Path GRPC_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/grpc_mock_grpc_client/suite_manifest.yaml");
    private static final Path PROVIDER_CAPABILITY_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void mockServerCrossVerifySampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/README.md",
                REST_SUITE.toString(),
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/suite_manifest_failure.yaml",
                SOAP_SUITE.toString(),
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/soap_mock_http_client/suite_manifest_failure.yaml",
                GRPC_SUITE.toString(),
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/grpc_mock_grpc_client/suite_manifest_failure.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/test_case_failure.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/rest_wiremock_http/test_case_boundary.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/soap_mock_http_client/test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/soap_mock_http_client/test_case_failure.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/soap_mock_http_client/test_case_boundary.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/grpc_mock_grpc_client/test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/grpc_mock_grpc_client/test_case_failure.yaml",
                "samples/90-compatibility/legacy-v0.2/30-cross-provider-groups/mock_server_cross_verify/grpc_mock_grpc_client/test_case_boundary.yaml");

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
                .contains("test_count: 6");

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
                .contains("test_count: 6")
                .contains("passed_count: 6")
                .contains("failed_count: 0")
                .contains("expected_failure_count: 3")
                .contains("suite_summary_json:")
                .contains("allure_results_dir:");

        Path suiteSummary = extractPath(run.stdout(), "suite_summary_json");
        Path allureResults = extractPath(run.stdout(), "allure_results_dir");
        String parentBatchId = extractValue(run.stdout(), "batch_id");
        assertThat(suiteSummary.toString())
                .contains("target/suite-groups")
                .doesNotContain("target/provider-capability/mock_server_cross_verify");
        String summaryDocument = Files.readString(suiteSummary);
        assertThat(summaryDocument)
                .contains("\"suite_id\": \"MOCK-SERVER-CROSS-VERIFY-v0.2\"")
                .contains("\"test_count\": 6")
                .contains("\"passed_count\": 6")
                .contains("\"expected_failure_count\": 3")
                .contains("\"expected_failed_observed_count\": 3")
                .contains("\"status_taxonomy\": \"expected_failed_observed\"")
                .contains("\"expected_status\": \"failed\"")
                .contains("\"observed_status\": \"failed\"")
                .contains("\"status\": \"passed\"")
                .contains("\"child_suite_id\": \"MOCK-SERVER-CROSS-VERIFY-REST-v0.2\"")
                .contains("\"child_suite_id\": \"MOCK-SERVER-CROSS-VERIFY-SOAP-v0.2\"")
                .contains("\"child_suite_id\": \"MOCK-SERVER-CROSS-VERIFY-GRPC-v0.2\"");
        assertThat(summaryDocument.split("\"batch_id\": \"" + parentBatchId + "\"", -1).length - 1)
                .as("parent and every executed child must share the suite-group batch ID")
                .isEqualTo(7);
        assertThat(countFiles(allureResults, "-result.json")).isEqualTo(6);
        assertThat(countFiles(allureResults, "-container.json")).isEqualTo(1);
        assertThat(Files.readString(firstFile(allureResults, "-result.json")))
                .contains("\"status\": \"passed\"")
                .contains("\"labels\"")
                .contains("\"suite\"");
    }

    @Test
    void providerCapabilityParentSuiteRunsEveryChildThroughSharedRuntimeDispatch() throws Exception {
        CommandResult run = execute(
                "run",
                "--suite",
                PROVIDER_CAPABILITY_SUITE.toString(),
                "--profile",
                "local_provider");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: PROVIDER-CAPABILITY-P0-v0.2")
                .contains("test_count: 12")
                .contains("passed_count: 12")
                .contains("failed_count: 0");

        Path suiteSummary = extractPath(run.stdout(), "suite_summary_json");
        String summary = Files.readString(suiteSummary);
        assertThat(summary)
                .contains("\"child_suite_id\": \"WIREMOCK-CAPABILITY-v0.2\"")
                .contains("\"child_suite_id\": \"JDBC-CAPABILITY-v0.2\"")
                .contains("\"child_suite_id\": \"NATS-CAPABILITY-v0.2\"")
                .contains("\"child_suite_id\": \"COMMON-VERIFY-CAPABILITY-v0.2\"")
                .contains("\"child_suite_id\": \"PROVIDER-CAPABILITY-COMPARE-v0.2\"")
                .contains("\"child_suite_id\": \"KAFKA-CAPABILITY-v0.2\"")
                .contains("\"child_suite_id\": \"IBM-MQ-CAPABILITY-v0.2\"")
                .contains("\"unsupported_count\": 0")
                .doesNotContain("unsupported_provider_type")
                .doesNotContain("VALIDATION_UNSUPPORTED_PROVIDER_TYPE");
    }

    @Test
    void suiteGroupSummaryClassifiesUnsupportedChildSeparatelyFromBlocked() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "unsupported_child_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_id: UNSUPPORTED-CHILD-SUITE
                profile: local_unsupported
                child_suites:
                  - id: unsupported_child
                    ref: child/suite_manifest.yaml
                    profile: local_unsupported
                    expected_status: passed
                """);
        Path childDir = manifest.getParent().resolve("child");
        Files.createDirectories(childDir.resolve("provider_instances"));
        Files.createDirectories(childDir.resolve("env_profiles"));
        Files.createDirectories(childDir.resolve("custom-provider-contracts"));
        Files.writeString(
                childDir.resolve("suite_manifest.yaml"),
                """
                contract_version: v0.2
                suite_id: CHILD-UNSUPPORTED-v0.2
                profile: local_unsupported
                provider_contract_resolution:
                  mode: suite_override
                  custom_provider_contracts: custom-provider-contracts
                  allowed_provider_types: [unsupported_provider]
                selection:
                  mode: suite
                  suite: unsupported-child
                tests:
                  - test_case.yaml
                artifact_roots:
                  provider_instances: provider_instances/
                  env_profiles: env_profiles/
                """);
        Files.writeString(
                childDir.resolve("test_case.yaml"),
                """
                dsl_version: v0.2
                test_case_id: CHILD-UNSUPPORTED-TC-001
                title: Unsupported provider child
                status: active
                revision: 1
                compatible_profiles: [local_unsupported]
                targets:
                  unsupported_target:
                    provider_id: unsupported-provider
                execute: []
                verify:
                  checks: []
                evidence:
                  required: []
                runtime:
                  timeout: PT30S
                  retry:
                    max_attempts: 1
                """);
        Files.writeString(
                childDir.resolve("provider_instances/unsupported-provider.yaml"),
                """
                provider_instance_version: v0.2
                provider_id: unsupported-provider
                provider_type: unsupported_provider
                runtime_modes: [external]
                """);
        Files.writeString(
                childDir.resolve("custom-provider-contracts/unsupported_provider.yaml"),
                """
                provider_contract_version: v0.2
                provider_type: unsupported_provider
                runtime_modes: [external]
                binding_keys: {}
                valid_provider_instance_shape:
                  required_fields: [provider_id, provider_type, runtime_modes]
                  allowed_fields: [provider_instance_version, provider_id, provider_type, runtime_modes]
                operations:
                  observe:
                    allowed_inputs: []
                    required_inputs: []
                    output_refs: []
                evidence:
                  outputs: []
                  redact: []
                failure_mapping:
                  allowed_codes: [UNSUPPORTED_RUNTIME]
                """);
        Files.writeString(
                childDir.resolve("env_profiles/local_unsupported.yaml"),
                """
                env_profile_id: local_unsupported
                execution_mode: local
                providers:
                  unsupported-provider:
                    runtime_mode: external
                    bindings: {}
                dependency_policy:
                  require_readiness_evidence: false
                  allow_framework_managed_dependencies: false
                dependency_substitution_policy:
                  allowed_runtime_modes: [external]
                dependency_provisioning_policy:
                  allowed_provisioners: []
                data_policy:
                  approved_expected_results_required: false
                  production_data_allowed: false
                  generated_data_allowed: true
                  secrets_must_use_refs: true
                """);

        CommandResult run = execute("run", "--suite", manifest.toString(), "--profile", "local_unsupported");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("unsupported_provider")
                .contains("unsupported_suite_runtime");
        Path suiteSummary = extractPath(run.stdout(), "suite_summary_json");
        assertThat(Files.readString(suiteSummary))
                .contains("\"status_taxonomy\": \"unsupported\"")
                .contains("\"unsupported_count\": 1")
                .contains("\"blocked_count\": 0");
    }

    @Test
    void suiteGroupSummaryClassifiesExpectedFailureMissingWhenChildPasses() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "expected_failure_missing_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_id: EXPECTED-FAILURE-MISSING-SUITE
                profile: local_expected_failure_missing
                child_suites:
                  - id: REST-EXPECTED-FAILED
                    ref: rest_wiremock_http/suite_manifest.yaml
                    profile: local_wiremock_http
                    expected_status: failed
                """);
        Path childDir = manifest.getParent().resolve("rest_wiremock_http");
        copyDirectory(REST_SUITE.getParent(), childDir);

        CommandResult run = execute("run", "--suite", manifest.toString(), "--profile", "local_expected_failure_missing");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("expected_failure_count: 1")
                .contains("expected_failed_observed_count: 0");
        Path suiteSummary = extractPath(run.stdout(), "suite_summary_json");
        assertThat(Files.readString(suiteSummary))
                .contains("\"status_taxonomy\": \"expected_failed_missing\"")
                .contains("\"expected_failed_missing_count\": 1")
                .contains("\"expected_status\": \"failed\"")
                .contains("\"observed_status\": \"passed\"");
    }

    @Test
    void suiteGroupSummaryClassifiesUnexpectedFailedChild() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "unexpected_failed_child_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_id: UNEXPECTED-FAILED-CHILD-SUITE
                profile: local_unexpected_failed_child
                child_suites:
                  - id: REST-UNEXPECTED-FAILED
                    ref: rest_wiremock_http/suite_manifest_failure.yaml
                    profile: local_wiremock_http
                    expected_status: passed
                """);
        Path childDir = manifest.getParent().resolve("rest_wiremock_http");
        copyDirectory(REST_SUITE.getParent(), childDir);

        CommandResult run = execute("run", "--suite", manifest.toString(), "--profile", "local_unexpected_failed_child");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("expected_failure_count: 0")
                .contains("failed_count: 1");
        Path suiteSummary = extractPath(run.stdout(), "suite_summary_json");
        assertThat(Files.readString(suiteSummary))
                .contains("\"status_taxonomy\": \"failed\"")
                .contains("\"expected_status\": \"passed\"")
                .contains("\"observed_status\": \"failed\"")
                .contains("\"expected_failed_missing_count\": 0");
    }

    @Test
    void suiteGroupDryRunValidatesChildrenWithoutExecutingProviderRuntime() {
        CommandResult dryRun = execute("run", "--suite", CROSS_VERIFY_SUITE.toString(), "--dry-run");

        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("suite_id: MOCK-SERVER-CROSS-VERIFY-v0.2")
                .contains("test_count: 6")
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
                suite_id: ESCAPE-SUITE
                profile: local_escape
                child_suites:
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
                suite_id: DUPLICATE-SUITE
                profile: local_duplicate
                child_suites:
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
                .contains("child_suites[1].id");
    }

    @Test
    void suiteGroupValidationRejectsMalformedChildEntryShape() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "malformed_child_entry_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_id: MALFORMED-CHILD-ENTRY-SUITE
                profile: local_malformed
                child_suites:
                  - invalid-child-entry
                """);

        CommandResult validation = execute("validate", "--suite", manifest.toString());

        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("invalid_field_type")
                .contains("child_suites[0]")
                .contains("owner_action:");
    }

    @Test
    void suiteGroupValidationRejectsMissingChildFieldsAndUnsupportedExpectedStatus() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "missing_child_fields_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_id: MISSING-CHILD-FIELDS-SUITE
                profile: local_missing_fields
                child_suites:
                  - expected_status: blocked
                """);

        CommandResult validation = execute("validate", "--suite", manifest.toString());

        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("child_suites[0].id")
                .contains("child_suites[0].ref")
                .contains("child_suites[0].profile")
                .contains("child_suites[0].expected_status")
                .contains("unsupported_expected_status");
    }

    @Test
    void legacySuiteGroupManifestFieldsRemainReadableForCompatibility() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "legacy_suite_manifest.yaml",
                """
                contract_version: v0.2
                suite_type: suite_group
                suite_id: LEGACY-SUITE
                profile: local_legacy
                test_cases:
                  - id: LEGACY-001
                    ref: child/suite_manifest.yaml
                    profile: local_legacy
                    expected_status: passed
                """);

        CommandResult validation = execute("validate", "--suite", manifest.toString());

        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("suite_id: LEGACY-SUITE")
                .contains("missing_required_file")
                .contains("test_cases[0].ref");
    }

    @Test
    void suiteTypeWithoutLegacyChildrenDoesNotSelectAggregationRunner() throws Exception {
        Path manifest = writeSuiteGroupManifest(
                "standard_manifest_with_legacy_type.yaml",
                """
                contract_version: v0.2
                suite_type: suite_group
                suite_id: STANDARD-SUITE
                profile: local_standard
                selection:
                  mode: suite
                  suite: standard-suite
                tests: []
                """);

        CommandResult validation = execute("validate", "--suite", manifest.toString());

        assertThat(validation.exit()).isEqualTo(1);
        assertThat(validation.stdout())
                .contains("validation_status: failed")
                .contains("suite_id: STANDARD-SUITE")
                .contains("prohibited_legacy_field")
                .contains("suite_type")
                .doesNotContain("child_suites:")
                .doesNotContain("test_count:");
    }

    @Test
    void suiteGroupValidationHandlesRootLevelManifestWithMissingChildSuite() throws Exception {
        Path manifest = tempDir.resolve("suite_manifest.yaml");
        Files.writeString(
                manifest,
                """
                contract_version: v0.2
                suite_id: ROOT-LEVEL-SUITE
                profile: local_root
                child_suites:
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
                suite_id: MISSING-CHILD-RUN-SUITE
                profile: local_missing_child
                child_suites:
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
                .contains("test_count: 2")
                .contains("result_json:");

        CommandResult report = execute("report", "--result", extractPath(run.stdout(), "result_json").toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: " + suiteId)
                .contains("test_count: 2")
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

    private String extractValue(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing value line for " + key + " in:\n" + stdout));
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

    private void copyDirectory(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path destination = target.resolve(source.relativize(path));
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
