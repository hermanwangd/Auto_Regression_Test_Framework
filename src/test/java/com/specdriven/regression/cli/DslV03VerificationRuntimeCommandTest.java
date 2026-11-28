package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void selectedCaseControlsCompilationVerdictAndEvidence() throws Exception {
        Path suite = mutableSuite(MULTI_TEST_SUITE.getParent(), "selected_case");
        Files.writeString(suite.getParent().resolve("expected_results/order_status.txt"), "unexpected value");
        String first = "MULTI-TEST-V03-TC-001";
        String second = "MULTI-TEST-V03-TC-002";
        CommandResult dry = execute("run", "--suite", suite.toString(), "--profile", "local_v03",
                "--test-case", first, "--dry-run");
        assertThat(dry.exit()).as(dry.stdout()).isZero();
        assertThat(dry.stdout()).contains(first).doesNotContain(second);
        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03", "--test-case", first);
        assertThat(run.exit()).as(run.stdout()).isZero();
        assertThat(run.stdout()).contains("test_count: 1", "passed_count: 1", "failed_count: 0");
        Path result = extractPath(run.stdout(), "result_json");
        JsonNode canonical = OBJECT_MAPPER.readTree(result.toFile());
        assertThat(canonical.path("selected_test_case").asText()).isEqualTo(first);
        assertThat(canonical.path("test_results").size()).isOne();
        assertThat(canonical.path("test_results").get(0).path("test_case_id").asText()).isEqualTo(first);
        assertThat(dry.stdout()).contains("plan_digest: " + canonical.path("plan_digest").asText());
        assertThat(canonical.toString()).doesNotContain(second, "multi_file_matches");
        CommandResult evidence = execute("validate-evidence", "--result", result.toString());
        assertThat(evidence.exit()).as(evidence.stdout()).isZero();
        CommandResult report = execute("report", "--result", result.toString());
        assertThat(report.exit()).as(report.stdout()).isZero();
        assertThat(report.stdout()).contains("test_count: 1");
        CommandResult failure = execute("run", "--suite", suite.toString(), "--profile", "local_v03", "--test-case=" + second);
        assertThat(failure.exit()).isOne();
        assertThat(failure.stdout()).contains("test_count: 1", "failed_count: 1", second).doesNotContain(first);
        CommandResult full = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(full.exit()).isOne();
        assertThat(full.stdout()).contains("test_count: 2", "failed_count: 1");
        JsonNode fullResult = OBJECT_MAPPER.readTree(extractPath(full.stdout(), "result_json").toFile());
        assertThat(fullResult.has("selected_test_case")).isFalse();
        assertThat(fullResult.path("plan_digest")).isNotEqualTo(canonical.path("plan_digest"));
    }

    @Test
    void ambiguousSelectionAndUnsupportedSuiteTypesProduceNoRun() {
        for (List<String> args : List.of(
                List.of(MULTI_TEST_SUITE.toString(), "--test-case="),
                List.of(MULTI_TEST_SUITE.toString(), "--test-case", "one", "--test-case=two"),
                List.of("samples/30-cross-provider-groups/mock_server_cross_verify/suite_manifest.yaml", "--test-case", "one"))) {
            List<String> command = new java.util.ArrayList<>(List.of("run", "--profile", "local_v03", "--suite"));
            command.addAll(args);
            CommandResult run = execute(command.toArray(String[]::new));
            assertThat(run.exit()).as(run.stdout()).isEqualTo(2);
            assertThat(run.stderr()).contains("--test-case");
            assertThat(run.stdout()).doesNotContain("result_json:");
        }
    }

    @Test
    void unknownCaseBlocksRealAndDryRunsBeforeExecution() {
        for (boolean dry : List.of(false, true)) {
            List<String> args = new java.util.ArrayList<>(List.of("run", "--suite", MULTI_TEST_SUITE.toString(),
                    "--profile", "local_v03", "--test-case", "MISSING-CASE"));
            if (dry) args.add("--dry-run");
            CommandResult run = execute(args.toArray(String[]::new));
            assertThat(run.exit()).as(run.stdout()).isOne();
            assertThat(run.stdout()).contains("run_status: blocked", "unknown_test_case: MISSING-CASE")
                    .doesNotContain("result_json:", "MULTI-TEST-V03-TC-001", "MULTI-TEST-V03-TC-002");
        }
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

    @Test
    void schemaFragmentsAreCheckedBeforeRunAndRuntimeReportsTheEscapedInstancePath() throws Exception {
        Path suite = mutableSuite(COMMON_VERIFY_SUITE.getParent(), "recursive_schema");
        Path schema = suite.getParent().resolve("expected_results/expected_schema.json");
        Path test = suite.getParent().resolve("test_cases/common_verify_success.yaml");
        Files.writeString(test, """
                dsl_version: v0.3
                test_case_id: SCHEMA-CASE
                title: Schema fragment comparison
                execute: []
                verify:
                  - id: verify_schema
                    type: assertion
                    assert:
                      operator: schema_match
                      schema_ref: artifact://expected_results/expected_schema.json#/selected
                      actual: {items: [{"a/b": "wrong"}]}
                """);
        Files.writeString(schema, """
                {"selected":{"properties":{"items":{"items":{"properties":{"a/b":{"$ref":"example"}}}}}}}
                """);
        for (String[] args : List.of(new String[]{"validate", "--suite", suite.toString()},
                new String[]{"run", "--suite", suite.toString(), "--profile", "local_v03", "--dry-run"},
                new String[]{"run", "--suite", suite.toString(), "--profile", "local_v03"})) {
            CommandResult result = execute(args);
            assertThat(result.exit()).as(result.stdout() + result.stderr()).isNotZero();
            assertThat(result.stdout() + result.stderr()).contains("/properties/items/items/properties/a~1b/$ref");
        }
        Files.writeString(schema, Files.readString(schema).replace("\"$ref\":\"example\"", "\"type\":\"integer\""));
        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(result.exit()).as(result.stdout()).isEqualTo(1);
        JsonNode assertion = OBJECT_MAPPER.readTree(extractPath(result.stdout(), "result_json").toFile())
                .path("verify_results").get(0);
        assertThat(assertion.path("evaluation_error").asText()).contains("/items/0/a~1b").doesNotContain("wrong");
        assertThat(Files.readString(extractPath(result.stdout(), "evidence_dir")
                .resolve(assertion.path("evidence_ref").asText()))).contains("/items/0/a~1b");
    }

    @Test
    void strictEqualityDistinguishesTypesAndResolvesExplicitNullThroughTheCli() throws Exception {
        Path suite = mutableSuite(COMMON_VERIFY_SUITE.getParent(), "strict_comparison");
        Path test = suite.getParent().resolve("test_cases/common_verify_success.yaml");
        Files.writeString(suite.getParent().resolve("expected_results/decimals.json"),
                "[1.000000000000000000001,1.000000000000000000002]");
        Files.writeString(test, """
                dsl_version: v0.3
                test_case_id: STRICT-CASE
                title: Strict comparison opt-in
                execute: []
                verify:
                  - id: legacy_coercion
                    type: assertion
                    assert: {operator: equals, actual: "200", expected: 200}
                  - id: strict_null
                    type: assertion
                    assert: {operator: equals, strict: true, actual: null, expected: null}
                  - id: different_types
                    type: assertion
                    assert: {operator: not_equals, strict: true, actual: "true", expected: true}
                  - id: exact_decimals
                    type: assertion
                    assert:
                      operator: not_equals
                      strict: true
                      actual_ref: artifact://expected_results/decimals.json#/0
                      expected_ref: artifact://expected_results/decimals.json#/1
                  - id: wrong_type
                    type: assertion
                    assert: {operator: equals, strict: true, actual: "200", expected: 200}
                """);
        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isEqualTo(1);
        JsonNode assertions = OBJECT_MAPPER.readTree(extractPath(run.stdout(), "result_json").toFile()).path("verify_results");
        assertThat(assertions).extracting(row -> row.path("status").asText())
                .containsExactly("passed", "passed", "passed", "passed", "failed");
    }

    @Test
    void jsonMatchAcceptsNullAndNestedUnorderedArraysThroughTheCli() throws Exception {
        Path suite = mutableSuite(COMMON_VERIFY_SUITE.getParent(), "json_match");
        Files.writeString(suite.getParent().resolve("expected_results/data.json"), """
                {"nil":null,"nested":[{"values":["1",1,1]},false],"01":"literal"}
                """);
        Files.writeString(suite.getParent().resolve("test_cases/common_verify_success.yaml"), """
                dsl_version: v0.3
                test_case_id: JSON-MATCH
                title: JSON comparison
                execute: []
                verify:
                  - id: explicit_null
                    type: assertion
                    assert:
                      operator: json_match
                      actual: null
                      expected_ref: artifact://expected_results/data.json#/nil
                  - id: referenced_null
                    type: assertion
                    assert:
                      operator: json_match
                      actual_ref: artifact://expected_results/data.json#/nil
                      expected_ref: artifact://expected_results/data.json#/nil
                  - id: nested_multiset
                    type: assertion
                    assert:
                      operator: json_match
                      actual: [false, {values: [1.0, "1", 1]}]
                      expected_ref: artifact://expected_results/data.json#/nested
                      ignore_order: true
                  - id: literal_object_member
                    type: assertion
                    assert:
                      operator: json_match
                      actual: literal
                      expected_ref: artifact://expected_results/data.json#/01
                """);
        CommandResult validate = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(validate.exit()).as(validate.stdout() + validate.stderr()).isZero();
        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isZero();
        Path result = extractPath(run.stdout(), "result_json");
        JsonNode rows = OBJECT_MAPPER.readTree(result.toFile()).path("verify_results");
        assertThat(rows).extracting(row -> row.path("status").asText())
                .containsExactly("passed", "passed", "passed", "passed");
        CommandResult evidence = execute("validate-evidence", "--result", result.toString());
        assertThat(evidence.exit()).as(evidence.stdout() + evidence.stderr()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(textBlock = """
            scalar_type,   text,      number,    false
            decimal_value, decimalA,  decimalB,  false
            multiplicity,  repeatedA, repeatedB, true
            ordering,      orderedA,  orderedB,  false
            """)
    void jsonMismatchProducesFailedAssertionEvidence(String name, String actual, String expected,
            boolean ignoreOrder) throws Exception {
        Path suite = mutableSuite(COMMON_VERIFY_SUITE.getParent(), name);
        Files.writeString(suite.getParent().resolve("expected_results/data.json"), """
                {"text":"200","number":200,"decimalA":1.000000000000000000001,"decimalB":1.000000000000000000002,
                 "repeatedA":[1,1,"1"],"repeatedB":[1,"1","1"],"orderedA":[1,2],"orderedB":[2,1]}
                """);
        Files.writeString(suite.getParent().resolve("test_cases/common_verify_success.yaml"), """
                dsl_version: v0.3
                test_case_id: JSON-MISMATCH
                title: JSON mismatch
                execute: []
                verify:
                  - id: comparison
                    type: assertion
                    assert:
                      operator: json_match
                      actual_ref: artifact://expected_results/data.json#/%s
                      expected_ref: artifact://expected_results/data.json#/%s
                      ignore_order: %s
                """.formatted(actual, expected, ignoreOrder));
        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isOne();
        Path result = extractPath(run.stdout(), "result_json");
        JsonNode row = OBJECT_MAPPER.readTree(result.toFile()).path("verify_results").get(0);
        assertThat(row.path("status").asText()).isEqualTo("failed");
        assertThat(row.path("failure_code").asText()).isEqualTo("ASSERTION_FAILED");
        CommandResult evidence = execute("validate-evidence", "--result", result.toString());
        assertThat(evidence.exit()).as(evidence.stdout() + evidence.stderr()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "+1"})
    void invalidArrayIndexOnAnArtifactFailsCliPreflightEvenForNotExists(String token) throws Exception {
        Path suite = mutableSuite(COMMON_VERIFY_SUITE.getParent(), "artifact_index");
        Files.writeString(suite.getParent().resolve("expected_results/items.json"), "[0,1]");
        Files.writeString(suite.getParent().resolve("test_cases/common_verify_success.yaml"), """
                dsl_version: v0.3
                test_case_id: INVALID-INDEX
                title: Invalid artifact array index
                execute: []
                verify:
                  - id: malformed_index
                    type: assertion
                    assert:
                      operator: not_exists
                      actual_ref: artifact://expected_results/items.json#/%s
                """.formatted(token));
        CommandResult result = execute("validate", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(result.exit()).isNotZero();
        assertThat(result.stdout() + result.stderr()).contains("invalid_json_pointer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "+1"})
    void stepPointerDistinguishesInvalidArrayIndexFromAbsentValue(String token) throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock"),
                "pointer_index");
        String body = """
                {"items":["first","200"],"01":"literal","+1":"literal"}
                """;
        Files.writeString(suite.getParent().resolve("expected_results/payment_response.json"), body);
        Path stubFile = suite.getParent().resolve("fixtures/wiremock/payment_success_stub.json");
        JsonNode stub = OBJECT_MAPPER.readTree(stubFile.toFile());
        ((ObjectNode) stub.required("response")).set("jsonBody", OBJECT_MAPPER.readTree(body));
        OBJECT_MAPPER.writeValue(stubFile.toFile(), stub);
        Path test = suite.getParent().resolve("test_cases/payment_success.yaml");
        String checks = """
                  - id: string_member
                    type: assertion
                    assert:
                      operator: json_match
                      actual_ref: step://call_payment_api/response.body#/items/1
                      expected_ref: artifact://expected_results/payment_response.json#/items/1
                  - id: object_member
                    type: assertion
                    assert: {operator: equals, actual_ref: step://call_payment_api/response.body#/%s, expected: literal}
                  - id: absent_element
                    type: assertion
                    assert: {operator: not_exists, actual_ref: step://call_payment_api/response.body#/items/9}
                  - id: malformed_index
                    type: assertion
                    assert: {operator: not_exists, actual_ref: step://call_payment_api/response.body#/items/%s}
                """.formatted(token, token);
        Files.writeString(test, Files.readString(test).replace("cleanup:", checks + "\ncleanup:"));
        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isOne();
        JsonNode rows = OBJECT_MAPPER.readTree(extractPath(run.stdout(), "result_json").toFile()).path("verify_results");
        assertThat(rows).as(run.stdout() + run.stderr()).extracting(row -> row.path("status").asText())
                .containsExactly("passed", "passed", "passed", "passed", "passed", "failed");
        JsonNode last = rows.get(rows.size() - 1);
        assertThat(Files.readString(extractPath(run.stdout(), "evidence_dir").resolve(last.path("evidence_ref").asText())))
                .contains("invalid_json_pointer");
    }

    @ParameterizedTest(name = "{0} with fractional suffix {1}")
    @CsvSource(textBlock = """
            gt,  1,  false
            gt,  20, false
            gt,  3,  true
            gte, 1,  false
            gte, 20, true
            gte, 3,  true
            lt,  1,  true
            lt,  20, false
            lt,  3,  false
            lte, 1,  true
            lte, 20, true
            lte, 3,  false
            """)
    void numericOrderingKeepsDecimalBoundariesInVerdictAndEvidence(
            String operator, String suffix, boolean passes) throws Exception {
        Path suite = mutableSuite(COMMON_VERIFY_SUITE.getParent(), "decimal_boundaries");
        String prefix = "1.00000000000000000000";
        String actual = prefix + suffix;
        String expected = prefix + "2";
        Path fixtures = suite.getParent().resolve("expected_results");
        Files.writeString(fixtures.resolve("measured.json"), "{\"value\":" + actual + "}");
        Files.writeString(fixtures.resolve("threshold.json"), expected);
        Files.writeString(suite.getParent().resolve("test_cases/common_verify_success.yaml"), """
                dsl_version: v0.3
                test_case_id: DECIMAL-BOUNDARY
                title: Compare adjacent decimal values
                execute: []
                verify:
                  - id: boundary
                    type: assertion
                    assert:
                      operator: %s
                      actual_ref: artifact://expected_results/measured.json#/value
                      expected_ref: artifact://expected_results/threshold.json
                """.formatted(operator));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isEqualTo(passes ? 0 : 1);
        Path result = extractPath(run.stdout(), "result_json");
        JsonNode row = OBJECT_MAPPER.readTree(result.toFile()).path("verify_results").get(0);
        assertThat(row.path("status").asText()).isEqualTo(passes ? "passed" : "failed");
        Path evidenceFile = extractPath(run.stdout(), "evidence_dir").resolve(row.required("evidence_ref").asText());
        String evidence = Files.readString(evidenceFile);
        assertThat(evidence.lines()).contains("actual: " + actual, "expected: " + expected);
        CommandResult validation = execute("validate-evidence", "--result", result.toString());
        assertThat(validation.exit()).as(validation.stdout() + validation.stderr()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void numericOrderingChecksSelectedRestValueAgainstQuotedThreshold(boolean numeric) throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/http/rest_client_with_wiremock"),
                "rest_decimal");
        String body = numeric ? "{\"measurement\":1.000000000000000000002e-100}" : "{\"measurement\":false}";
        Files.writeString(suite.getParent().resolve("expected_results/payment_response.json"), body);
        Path mapping = suite.getParent().resolve("fixtures/wiremock/payment_success_stub.json");
        JsonNode stub = OBJECT_MAPPER.readTree(mapping.toFile());
        ObjectNode response = (ObjectNode) stub.required("response");
        response.remove("jsonBody");
        response.put("body", body);
        OBJECT_MAPPER.writeValue(mapping.toFile(), stub);
        Path test = suite.getParent().resolve("test_cases/payment_success.yaml");
        String comparison = """
                  - id: decimal_threshold
                    type: assertion
                    assert:
                      operator: gt
                      actual_ref: step://call_payment_api/response.body#/measurement
                      expected: '1.000000000000000000001e-100'
                """;
        Files.writeString(test, Files.readString(test).replace("cleanup:", comparison + "\ncleanup:"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stdout() + run.stderr()).isEqualTo(numeric ? 0 : 1);
        Path result = extractPath(run.stdout(), "result_json");
        JsonNode rows = OBJECT_MAPPER.readTree(result.toFile()).required("verify_results");
        JsonNode last = rows.get(rows.size() - 1);
        assertThat(last.required("id").asText()).isEqualTo("decimal_threshold");
        assertThat(last.required("status").asText()).isEqualTo(numeric ? "passed" : "failed");
        Path evidenceFile = extractPath(run.stdout(), "evidence_dir").resolve(last.required("evidence_ref").asText());
        String evidence = Files.readString(evidenceFile);
        assertThat(evidence).contains("1.000000000000000000001e-100");
        assertThat(evidence).contains(numeric ? "1.000000000000000000002E-100" : "invalid_numeric_assertion_operand");
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
