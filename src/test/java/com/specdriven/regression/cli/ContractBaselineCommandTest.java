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
                "schemas/env_profile.v0.2.schema.yaml",
                "schemas/execution_profile.v0.2.schema.yaml",
                "schemas/environment_binding.v0.2.schema.yaml",
                "schemas/suite_manifest.v0.2.schema.yaml",
                "schemas/result.v0.2.schema.yaml",
                "samples/contract_baseline/suite_manifest.yaml",
                "samples/contract_baseline/test_case.yaml",
                "docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml",
                "docs/02-architecture/contracts/provider-contracts/jdbc.yaml",
                "docs/02-architecture/contracts/provider-contracts/nats.yaml",
                "samples/contract_baseline/provider_instances/wiremock_payment_api.yaml",
                "samples/contract_baseline/provider_instances/oracle_database.yaml",
                "samples/contract_baseline/provider_instances/nats_event_bus.yaml",
                "samples/contract_baseline/env_profiles/ci.yaml",
                "samples/contract_baseline/env_profiles/sit.yaml",
                "samples/contract_baseline/execution_profiles/ci_pr.yaml",
                "samples/contract_baseline/execution_profiles/sit_regression.yaml",
                "samples/contract_baseline/environment_bindings/ci.yaml",
                "samples/contract_baseline/environment_bindings/sit.yaml",
                "samples/contract_baseline/result/sample_result.json",
                "samples/contract_baseline/evidence/evidence_index.yaml",
                "samples/contract_baseline/evidence/runs/RUN-CONTRACT-001/logs/execution.txt");

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
    void validateCommandsFailClearlyWhenRequiredPathArgumentsAreMissing() {
        CommandResult validate = execute("validate");
        assertThat(validate.exit()).isEqualTo(2);
        assertThat(validate.stderr()).contains("Missing required option: --suite");

        CommandResult validateEvidence = execute("validate-evidence");
        assertThat(validateEvidence.exit()).isEqualTo(2);
        assertThat(validateEvidence.stderr()).contains("Missing required option: --result");
    }

    @Test
    void validateSuitePassesWithoutSourceRefsBecauseTraceabilityIsNotRuntimeContract() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("""
                source_refs:
                  acceptance_criteria: docs/08-release/release-packages/RP-FWK-CONTRACT-SAMPLE/acceptance_criteria.md#AC-001
                """, ""));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).as(result.stdout()).isZero();
        assertThat(result.stdout()).contains("validation_status: passed");
    }

    @Test
    void validateSuiteAcceptsEnvProfileWithoutLegacyProfileAndBindingArtifacts() throws Exception {
        Path suite = mutableBaseline("env_profile_contract_baseline");
        Path root = suite.getParent();
        writeEnvProfiles(root);
        deleteDirectory(root.resolve("execution_profiles"));
        deleteDirectory(root.resolve("environment_bindings"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).as(result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("provider_instances_used:")
                .contains("wiremock-payment-api")
                .contains("oracle-database")
                .contains("nats-event-bus");
    }

    @Test
    void validateSuiteReportsMalformedEnvProfileYaml() throws Exception {
        Path suite = mutableBaseline();
        Files.writeString(suite.getParent().resolve("env_profiles/ci.yaml"), "env_profile_id: ci\nproviders:\n  wiremock-payment-api: [\n");

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_yaml")
                .contains("owner_action: Fix malformed YAML before validation.");
    }

    @Test
    void validateSuiteReportsMalformedSuiteYamlWithoutThrowing() throws Exception {
        Path suite = mutableBaseline();
        Files.writeString(suite, "contract_version: v0.2\nsuite_id: [\n");

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("validation_status: failed")
                .contains("reason: invalid_yaml")
                .contains("owner_action: Fix malformed YAML before validation.");
    }

    @Test
    void validateSuiteRejectsEnvProfileBindingKeyNotDeclaredByProviderContract() throws Exception {
        Path suite = mutableBaseline();
        Path envProfile = suite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(envProfile, Files.readString(envProfile)
                .replace("mappings_ref:", "unexpected_mappings_ref:"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: providers.wiremock-payment-api.binding_keys.unexpected_mappings_ref")
                .contains("reason: unknown_binding_key")
                .contains("category: CONTRACT_ERROR");
    }

    @Test
    void validateSuiteRejectsEnvProfileBindingValueKindNotAllowedByProviderContract() throws Exception {
        Path suite = mutableBaseline();
        Path envProfile = suite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(envProfile, Files.readString(envProfile)
                .replace("""
                              mappings_ref:
                                ref: fixtures/wiremock/payment-api/
                        """, """
                              port_strategy:
                                ref: fixtures/wiremock/payment-api/
                        """));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: providers.wiremock-payment-api.binding_keys.port_strategy.ref")
                .contains("reason: invalid_binding_key_value_kind")
                .contains("category: CONTRACT_ERROR");
    }

    @Test
    void validateSuiteRejectsExecutionArtifactKeysInSourceRefs() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("""
                  acceptance_criteria: docs/08-release/release-packages/RP-FWK-CONTRACT-SAMPLE/acceptance_criteria.md#AC-001
                """, """
                  acceptance_criteria: docs/08-release/release-packages/RP-FWK-CONTRACT-SAMPLE/acceptance_criteria.md#AC-001
                  expected_result: expected_results/sample_expected.json
                  fixture: fixtures/payment_request.json
                  sql: fixtures/sql/find_order.sql
                """));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: source_refs.expected_result")
                .contains("field_path: source_refs.fixture")
                .contains("field_path: source_refs.sql")
                .contains("reason: prohibited_source_ref")
                .contains("category: VALIDATION_ERROR");
    }

    @Test
    void validateSuiteRejectsNonMapSourceRefs() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("""
                source_refs:
                  acceptance_criteria: docs/08-release/release-packages/RP-FWK-CONTRACT-SAMPLE/acceptance_criteria.md#AC-001
                """, """
                source_refs:
                  - acceptance_criteria.md#AC-001
                """));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: source_refs")
                .contains("reason: invalid_source_refs")
                .contains("category: VALIDATION_ERROR")
                .contains("Declare `source_refs` as a map of traceability keys to source references.");
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
                .contains("provider_type: jdbc")
                .contains("provider_id: nats-event-bus")
                .contains("provider_type: nats");
    }

    @Test
    void reportSuiteResultPrintsDeterministicSummary() {
        CommandResult result = execute("report", "--result", BASELINE_RESULT.toString());

        assertThat(result.exit()).as(() -> commandOutput(result)).isZero();
        assertThat(result.stdout())
                .contains("report_status: review_ready")
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
    void validateSuitePassesWithoutScenarioSection() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, withoutScenarioBlock(Files.readString(testCase)));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).as(result.stdout()).isZero();
        assertThat(result.stdout()).contains("validation_status: passed");
    }

    @Test
    void validateSuiteRejectsDeprecatedScenarioSection() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, withoutScenarioBlock(Files.readString(testCase))
                .replace("""
                        targets:
                        """, """
                        scenario:
                          type: integration
                          scope: release_package
                          capabilities: [db_seed, http_request, json_assertion]
                        targets:
                        """));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: scenario")
                .contains("reason: prohibited_deprecated_field")
                .contains("owner_action: Remove `scenario`; declare behavior through setup/execute/verify and provider capability through Provider Contract.");
    }

    @Test
    void validateSuiteRejectsLegacyDataBindingCategory() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase) + """

                data_binding:
                  datasets:
                    legacy:
                      ref: fixtures/payment_request.json
                """);

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("field_path: data_binding.datasets")
                .contains("reason: prohibited_data_binding_category")
                .contains("owner_action: Move checked-in artifacts to `data.<name>.ref` and reference them from operation `inputs`.");
    }

    @Test
    void validateSuiteRejectsRawSecret() throws Exception {
        Path suite = mutableBaseline();
        Path binding = suite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(binding, Files.readString(binding)
                .replace("value: oracle", "value: jdbc:h2:mem:leaked_dialect;DB_CLOSE_DELAY=-1"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: raw_secret")
                .contains("field_path:")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsRawConnectionStringsAndSensitiveUsernames() throws Exception {
        Path rawConnectionSuite = mutableBaseline("raw_connection_string");
        Path rawConnectionBinding = rawConnectionSuite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(rawConnectionBinding, Files.readString(rawConnectionBinding)
                .replace("""
                              connection:
                                local_ref: approved_local_h2_oracle
                        """, """
                              connection:
                                value: jdbc:h2:mem:leaked_db;DB_CLOSE_DELAY=-1
                        """));

        Path sensitiveUsernameSuite = mutableBaseline("sensitive_username");
        Path sensitiveUsernameBinding = sensitiveUsernameSuite.getParent().resolve("env_profiles/ci.yaml");
        Files.writeString(sensitiveUsernameBinding, Files.readString(sensitiveUsernameBinding)
                .replace("""
                              dialect:
                                value: oracle
                        """, """
                              dialect:
                                value: oracle
                              username:
                                sensitive: true
                                value: dbadmin
                        """));

        CommandResult rawConnection = execute("validate", "--suite", rawConnectionSuite.toString());
        CommandResult sensitiveUsername = execute("validate", "--suite", sensitiveUsernameSuite.toString());

        assertThat(rawConnection.exit()).isEqualTo(1);
        assertThat(rawConnection.stdout())
                .contains("reason: raw_secret")
                .contains("category: SECRET_GUARDRAIL_ERROR");
        assertThat(sensitiveUsername.exit()).isEqualTo(1);
        assertThat(sensitiveUsername.stdout())
                .contains("field_path: providers.oracle-database.binding_keys.username.value")
                .contains("reason: raw_secret")
                .contains("category: SECRET_GUARDRAIL_ERROR");
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
    void validateSuiteRejectsMissingDirectExpectedArtifact() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("ref: expected_results/sample_expected.json#/event/payload",
                        "ref: expected_results/missing_expected.json#/event/payload"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("expected_results/missing_expected.json")
                .contains("reason: unresolved_artifact_ref")
                .contains("Restore runtime-critical artifact ref `expected_results/missing_expected.json` under the suite directory before execution.");
    }

    @Test
    void validateSuiteRejectsMissingEnvironmentBinding() throws Exception {
        Path suite = mutableBaseline();
        Files.delete(suite.getParent().resolve("env_profiles/ci.yaml"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_env_profile")
                .contains("profile: ci")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsMissingEnvironmentBindingForEverySelectedProfile() throws Exception {
        Path suite = mutableBaseline();
        Files.delete(suite.getParent().resolve("env_profiles/sit.yaml"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_env_profile")
                .contains("profile: sit")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsInvalidTargetRefBeforeRuntimeExecution() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("target: payment_api_mock", "target: missing_payment_api"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_target_ref")
                .contains("field_path: setup.load_payment_stub.target")
                .contains("category: CONFIGURATION_ERROR");
    }

    @Test
    void validateSuiteRejectsUnresolvedRuntimeCriticalArtifactRefs() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("fixtures/payment_event_payload.json",
                        "fixtures/missing_payment_event_payload.json"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unresolved_artifact_ref")
                .contains("fixtures/missing_payment_event_payload.json")
                .contains("category: CONFIGURATION_ERROR");
    }

    @Test
    void validateSuiteRejectsUnknownProviderType() throws Exception {
        Path suite = mutableBaseline();
        Path providerInstance = suite.getParent().resolve("provider_instances/oracle_database.yaml");
        Files.writeString(providerInstance, Files.readString(providerInstance)
                .replace("provider_type: jdbc", "provider_type: unknown_database"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unknown_provider_type")
                .contains("provider_type: unknown_database")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsLegacyNatsMessagingProviderType() throws Exception {
        Path suite = mutableBaseline();
        Path providerInstance = suite.getParent().resolve("provider_instances/nats_event_bus.yaml");
        Files.writeString(providerInstance, Files.readString(providerInstance)
                .replace("provider_type: nats", "provider_type: nats_messaging"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unknown_provider_type")
                .contains("provider_type: nats_messaging")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsProviderInstanceFieldsNotAllowedByProviderContract() throws Exception {
        Path suite = mutableBaseline();
        Path instance = suite.getParent().resolve("provider_instances/wiremock_payment_api.yaml");
        Files.writeString(instance, Files.readString(instance)
                .replace("provider_type: wiremock_http_mock\n", "provider_type: wiremock_http_mock\nprofile: ci\n"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_provider_instance_field")
                .contains("field_path: profile")
                .contains("provider_id: wiremock-payment-api")
                .contains("provider_type: wiremock_http_mock");
    }

    @Test
    void validateSuiteAllowsExplicitCustomProviderContractsWhenAllowlisted() throws Exception {
        Path suite = mutableBaseline("custom_provider_contract");
        Path root = suite.getParent();
        Files.writeString(suite, Files.readString(suite) + """

                provider_contract_resolution:
                  mode: suite_override
                  custom_provider_contracts: custom-provider-contracts
                  allowed_provider_types:
                    - custom_http_mock
                """);
        Files.createDirectories(root.resolve("custom-provider-contracts"));
        Files.writeString(root.resolve("custom-provider-contracts/custom_http_mock.yaml"),
                Files.readString(Path.of("docs/02-architecture/contracts/provider-contracts/wiremock_http_mock.yaml"))
                        .replace("provider_type: wiremock_http_mock", "provider_type: custom_http_mock"));
        Path instance = root.resolve("provider_instances/wiremock_payment_api.yaml");
        Files.writeString(instance, Files.readString(instance)
                .replace("provider_type: wiremock_http_mock", "provider_type: custom_http_mock"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).as(result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("custom_http_mock");
    }

    @Test
    void validateSuiteRejectsUnsupportedOperation() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("operation: send_http_request", "operation: call_api"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_operation")
                .contains("failure_code: CONTRACT_UNSUPPORTED_OPERATION")
                .contains("category: CONTRACT_ERROR")
                .contains("operation: call_api")
                .contains("owner_action:");
    }

    @Test
    void validateSuiteRejectsUnsupportedBindAs() throws Exception {
        Path suite = mutableBaseline();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace("mock.request_filter:", "request.body:"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_input")
                .contains("input: request.body")
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

    @Test
    void reportRejectsProviderResultWithoutStandardSuiteSummary() throws Exception {
        Path resultJson = tempDir.resolve("provider-result-without-suite-summary.json");
        Files.writeString(resultJson, withoutEvidenceRefs(removeStandardSuiteSummary(Files.readString(BASELINE_RESULT))));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsStandardSuiteResultWithoutBatchAndRunIds() throws Exception {
        Path resultJson = tempDir.resolve("standard-suite-missing-run-context.json");
        Files.writeString(resultJson, withoutEvidenceRefs(removeRunContext(Files.readString(BASELINE_RESULT))));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field")
                .contains("field_path: batch_id")
                .contains("field_path: run_id")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsMismatchedMultiTestResultCount() throws Exception {
        Path resultJson = tempDir.resolve("mismatched-test-count.json");
        Files.writeString(resultJson, addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                2,
                """
                    {
                      "test_case_id": "RP-FWK-CONTRACT-SAMPLE-TC-001",
                      "status": "passed",
                      "profile": "ci"
                    }
                """));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: invalid_test_count")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsNonIntegerMultiTestResultCount() throws Exception {
        Path resultJson = tempDir.resolve("non-integer-test-count.json");
        Files.writeString(resultJson, addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                "\"1\"",
                """
                    {
                      "test_case_id": "RP-FWK-CONTRACT-SAMPLE-TC-001",
                      "status": "passed",
                      "profile": "ci"
                    }
                """));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: invalid_test_count")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsTestResultMissingRequiredFields() throws Exception {
        Path resultJson = tempDir.resolve("invalid-test-results.json");
        Files.writeString(resultJson, addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                1,
                """
                    {
                      "test_case_id": "RP-FWK-CONTRACT-SAMPLE-TC-001",
                      "profile": "ci"
                    }
                """));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field")
                .contains("field_path: test_results[0].status")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsNonObjectTestResultEntries() throws Exception {
        Path resultJson = tempDir.resolve("non-object-test-results.json");
        Files.writeString(resultJson, addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                1,
                """
                    "not-a-test-result-object"
                """));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: invalid_test_results")
                .contains("field_path: test_results[0]")
                .contains("owner_action:")
                .doesNotContain("field_path: test_results[0].status")
                .doesNotContain("field_path: test_results[0].profile");
    }

    @Test
    void reportRejectsMultiProviderResultWithTopLevelProviderIdentity() throws Exception {
        Path resultJson = tempDir.resolve("multi-provider-root-provider.json");
        String multiProviderResult = addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                2,
                """
                    {
                      "test_case_id": "KAFKA-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "order-events",
                      "provider_type": "kafka"
                    },
                    {
                      "test_case_id": "IBM-MQ-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "payment-mq",
                      "provider_type": "ibm_mq"
                    }
                """).replaceFirst("  \\\"status\\\": \\\"passed\\\",\\n", "  \"provider_type\": \"kafka\",\n  \"status\": \"passed\",\n");
        Files.writeString(resultJson, multiProviderResult);

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: invalid_provider_summary")
                .contains("field_path: provider_type")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsMultiProviderResultWithoutProviderSummary() throws Exception {
        Path resultJson = tempDir.resolve("multi-provider-missing-summary.json");
        String multiProviderResult = addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                2,
                """
                    {
                      "test_case_id": "KAFKA-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "order-events",
                      "provider_type": "kafka"
                    },
                    {
                      "test_case_id": "IBM-MQ-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "payment-mq",
                      "provider_type": "ibm_mq"
                    }
                """);
        Files.writeString(resultJson, multiProviderResult);

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field")
                .contains("field_path: provider_summary")
                .contains("owner_action:");
    }

    @Test
    void reportRejectsMultiProviderResultWithoutProviderSummaryWhenInferredFromProviderResults() throws Exception {
        Path resultJson = tempDir.resolve("multi-provider-provider-results-missing-summary.json");
        String multiProviderResult = addMultiTestResultFields(
                Files.readString(BASELINE_RESULT),
                1,
                """
                    {
                      "test_case_id": "RP-FWK-CONTRACT-SAMPLE-TC-001",
                      "status": "passed",
                      "profile": "ci"
                    }
                """);
        Files.writeString(resultJson, multiProviderResult);

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_field")
                .contains("field_path: provider_summary")
                .contains("owner_action:");
    }

    private Path mutableBaseline() throws IOException {
        return mutableBaseline("contract_baseline");
    }

    private Path mutableBaseline(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/contract_baseline"), target);
        return target.resolve("suite_manifest.yaml");
    }

    private void writeEnvProfiles(Path root) throws IOException {
        Files.createDirectories(root.resolve("env_profiles"));
        Files.writeString(root.resolve("env_profiles/ci.yaml"), """
                env_profile_id: ci
                execution_mode: ci
                isolation_scope: per_run
                dependency_policy:
                  require_readiness_evidence: false
                  allow_framework_managed_dependencies: true
                dependency_substitution_policy:
                  allowed_runtime_modes: [mock, stub, ephemeral]
                  mock_evidence_release_claim: prohibited
                dependency_provisioning_policy:
                  allowed_provisioners: [embedded_h2, in_memory]
                data_policy:
                  approved_expected_results_required: true
                  production_data_allowed: false
                  generated_data_allowed: true
                  secrets_must_use_refs: true
                providers:
                  wiremock-payment-api:
                    runtime_mode: mock
                    binding_keys:
                      mappings_ref:
                        ref: fixtures/wiremock/payment-api/
                  oracle-database:
                    runtime_mode: ephemeral
                    binding_keys:
                      connection:
                        local_ref: approved_local_h2_oracle
                      dialect:
                        value: oracle
                  nats-event-bus:
                    runtime_mode: ephemeral
                    binding_keys:
                      connection:
                        local_ref: approved_local_nats_ref
                      subject:
                        value: payments.accepted
                      timeout:
                        value: PT30S
                      poll_interval:
                        value: PT0.5S
                """);
        Files.writeString(root.resolve("env_profiles/sit.yaml"), """
                env_profile_id: sit
                execution_mode: sit
                isolation_scope: shared_sit_environment
                dependency_policy:
                  require_readiness_evidence: true
                  allow_framework_managed_dependencies: false
                dependency_substitution_policy:
                  allowed_runtime_modes: [native]
                  mock_evidence_release_claim: prohibited
                dependency_provisioning_policy:
                  allowed_provisioners: [none]
                data_policy:
                  approved_expected_results_required: true
                  production_data_allowed: false
                  generated_data_allowed: false
                  secrets_must_use_refs: true
                providers:
                  wiremock-payment-api:
                    runtime_mode: mock
                    binding_keys:
                      mappings_ref:
                        ref: fixtures/wiremock/payment-api/
                  oracle-database:
                    runtime_mode: native
                    binding_keys:
                      connection.secret_ref:
                        secret_ref: vault://sit/oracle/connection
                      dialect:
                        value: oracle
                  nats-event-bus:
                    runtime_mode: native
                    binding_keys:
                      connection:
                        secret_ref: vault://sit/nats/connection
                      subject:
                        value: payments.accepted
                """);
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
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private String withoutScenarioBlock(String yaml) {
        return yaml.replaceFirst("(?m)^scenario:\\n(?:  [^\\n]*\\n)+", "");
    }

    private String addMultiTestResultFields(String resultJson, int testCount, String testResultsJson) {
        return addMultiTestResultFields(resultJson, String.valueOf(testCount), testResultsJson);
    }

    private String addMultiTestResultFields(String resultJson, String testCountJson, String testResultsJson) {
        String stripped = withoutEvidenceRefs(removeStandardSuiteSummary(resultJson));
        return stripped.replace(
                "  \"environment\": \"ci-contract-sample\",\n",
                "  \"environment\": \"ci-contract-sample\",\n"
                        + "  \"test_count\": " + testCountJson + ",\n"
                        + "  \"test_results\": [\n"
                        + testResultsJson.indent(4)
                        + "  ],\n");
    }

    private String addStandardSuiteResultFields(String resultJson) {
        return resultJson.replace(
                "  \"provider_results\": [\n",
                """
                  "test_count": 1,
                  "test_results": [
                    {
                      "test_case_id": "RP-FWK-CONTRACT-SAMPLE-TC-001",
                      "status": "passed",
                      "profile": "ci",
                      "provider_ids": ["wiremock-payment-api", "oracle-database", "nats-event-bus"],
                      "provider_types": ["wiremock_http_mock", "jdbc", "nats"]
                    }
                  ],
                  "provider_summary": [
                    {
                      "provider_id": "wiremock-payment-api",
                      "provider_type": "wiremock_http_mock",
                      "runtime_mode": "mock"
                    },
                    {
                      "provider_id": "oracle-database",
                      "provider_type": "jdbc",
                      "runtime_mode": "ephemeral"
                    },
                    {
                      "provider_id": "nats-event-bus",
                      "provider_type": "nats",
                      "runtime_mode": "ephemeral"
                    }
                  ],
                  "provider_results": [
                """);
    }

    private String removeStandardSuiteSummary(String resultJson) {
        return resultJson
                .replaceFirst("(?m)^  \\\"test_count\\\": 1,\\n", "")
                .replaceFirst("(?s)  \\\"test_results\\\": \\[.*?\\n  \\],\\n", "")
                .replaceFirst("(?s)  \\\"provider_summary\\\": \\[.*?\\n  \\],\\n", "");
    }

    private String removeRunContext(String resultJson) {
        return resultJson
                .replaceFirst("(?m)^  \\\"batch_id\\\": \\\"BATCH-CONTRACT-001\\\",\\n", "")
                .replaceFirst("(?m)^  \\\"run_id\\\": \\\"RUN-CONTRACT-001\\\",\\n", "");
    }

    private String withoutEvidenceRefs(String resultJson) {
        return resultJson.replaceFirst("(?s)  \\\"evidence_refs\\\": \\[.*?\\n  \\],\\n", "  \"evidence_refs\": [],\n");
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private String commandOutput(CommandResult result) {
        return """
                stdout:
                %s
                stderr:
                %s
                """.formatted(result.stdout(), result.stderr());
    }

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
