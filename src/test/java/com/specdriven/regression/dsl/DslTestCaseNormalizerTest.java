package com.specdriven.regression.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DslTestCaseNormalizerTest {

    @Test
    void returnsEmptyMapForNullOrEmptyInput() {
        DslTestCaseNormalizer normalizer = new DslTestCaseNormalizer();

        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize(Map.of())).isEmpty();
    }

    @Test
    void normalizesLegacyTraceabilityDslIntoRuntimeShape() {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("dsl_version", "v1");
        testCase.put("test_case_id", "RP-001-TC-001");
        testCase.put("status", "approved_for_regression");
        testCase.put("revision", 1);
        testCase.put("traceability", Map.of(
                "package_id", "RP-001",
                "acceptance_criteria_id", "RP-001-AC-001",
                "source", "acceptance_criteria.md#RP-001-AC-001"));
        testCase.put("targets", Map.of(
                "RU-api", Map.of(
                        "type", "application",
                        "runner", "request_response",
                        "environment", "ci://api")));
        testCase.put("setup", Map.of(
                "fixtures", Map.of(
                        "orders_seed", Map.of(
                                "type", "database_seed",
                                "ref", "fixtures/db/orders_seed.yaml",
                                "provider", "relational_db",
                                "setup_action", "seed_orders",
                                "cleanup_action", "cleanup_orders"))));
        testCase.put("execute", List.of(Map.of(
                "id", "submit_payment",
                "target", "RU-api",
                "operation", "submit",
                "with", Map.of(
                        "api_payload", Map.of(
                                "type", "api_payload",
                                "ref", "fixtures/request.json")))));
        testCase.put("expected_results", Map.of(
                "primary", Map.of(
                        "type", "expected_result_artifact",
                        "ref", "expected-results/approved/RP-001-ER-001.yaml")));
        testCase.put("verify", List.of(Map.of(
                "type", "file_diff",
                "actual", "${execute.submit_payment.outputs.actual_response}",
                "expected", "${expected_results.primary.ref}")));
        testCase.put("evidence", Map.of("required", List.of("execution_log", "assertion_result")));
        testCase.put("runtime", Map.of(
                "cleanup_required", true,
                "destructive_actions_allowed", false));

        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(testCase);

        assertThat(normalized).containsEntry("rp_id", "RP-001");
        assertThat(normalized).containsEntry("ac_id", "RP-001-AC-001");
        assertThat(normalized).containsEntry("artifact_status", "approved_for_regression");
        Map<?, ?> executionTarget = (Map<?, ?>) normalized.get("execution_target");
        assertThat(executionTarget.get("ru_id")).isEqualTo("RU-api");
        assertThat(executionTarget.get("provider")).isEqualTo("request_response");
        assertThat(executionTarget.get("execution_mode")).isEqualTo("ci_ephemeral");
        assertThat(executionTarget.get("environment_ref")).isEqualTo("ci://api");
        Map<?, ?> inputs = (Map<?, ?>) ((Map<?, ?>) normalized.get("package_inputs")).get("inputs");
        assertThat(inputs.keySet().stream().map(Object::toString).toList()).contains("orders_seed", "api_payload");
        Map<?, ?> primaryOracle = (Map<?, ?>) ((Map<?, ?>) normalized.get("oracles")).get("primary");
        assertThat(primaryOracle.get("type")).isEqualTo("expected_result_artifact");
        assertThat(primaryOracle.get("ref")).isEqualTo("expected-results/approved/RP-001-ER-001.yaml");
        assertThat((List<?>) normalized.get("steps"))
                .singleElement()
                .satisfies(step -> assertThat(((Map<?, ?>) step).get("action")).isEqualTo("submit"));
        assertThat((List<?>) normalized.get("assertions"))
                .singleElement()
                .satisfies(assertion -> {
                    Map<?, ?> assertionMap = (Map<?, ?>) assertion;
                    assertThat(assertionMap.get("type")).isEqualTo("file_diff");
                    assertThat(assertionMap.get("oracle")).isEqualTo("${oracles.primary}");
                });
        assertThat(((Map<?, ?>) normalized.get("policy")).get("cleanup_required")).isEqualTo(true);
    }

    @Test
    void normalizesProviderInputsDslIntoRuntimeBridgeShape() {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("dsl_version", "v0.2");
        testCase.put("test_case_id", "GOLDEN-E2E-TC-001");
        testCase.put("status", "active");
        testCase.put("revision", 1);
        testCase.put("source_refs", Map.of(
                "acceptance_criteria", "docs/03-acceptance/04_acceptance_criteria.md#track-b-golden-e2e"));
        testCase.put("targets", Map.of(
                "sample_runtime", Map.of("provider_id", "sample-fake-runtime")));
        testCase.put("data", Map.of(
                "input", Map.of("ref", "fixtures/input.json"),
                "setup_fixture", Map.of("ref", "fixtures/setup_fixture.yaml"),
                "expected_output", Map.of("ref", "expected_results/expected_output.json")));
        Map<String, Object> setupInputs = new LinkedHashMap<>();
        setupInputs.put("fixture.setup_ref", Map.of("ref", "${data.setup_fixture}"));
        setupInputs.put("fixture.input_ref", Map.of("ref", "${data.input}"));
        testCase.put("setup", Map.of("operations", List.of(Map.of(
                "id", "prepare_sample_workspace",
                "target", "sample_runtime",
                "operation", "setup_fixture",
                "inputs", setupInputs))));
        Map<String, Object> executeInputs = new LinkedHashMap<>();
        executeInputs.put("sample.input_ref", Map.of("ref", "${data.input}"));
        executeInputs.put("sample.expected_ref", Map.of("ref", "${data.expected_output}"));
        testCase.put("execute", Map.of("operations", List.of(Map.of(
                "id", "produce_sample_output",
                "target", "sample_runtime",
                "operation", "execute_sample",
                "inputs", executeInputs,
                "outputs", Map.of("actual_json", "actual_json")))));
        testCase.put("verify", Map.of("checks", List.of(Map.of(
                "id", "output_matches_expected_json",
                "type", "json_match",
                "actual", Map.of("ref", "${execute.produce_sample_output.outputs.actual_json}"),
                "expected_ref", "expected_results/expected_output.json"))));

        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(testCase);

        Map<?, ?> inputs = (Map<?, ?>) ((Map<?, ?>) normalized.get("package_inputs")).get("inputs");
        assertThat(inputs.keySet().stream().map(Object::toString).toList())
                .contains(
                        "prepare_sample_workspace_fixture_setup_ref",
                        "prepare_sample_workspace_fixture_input_ref",
                        "produce_sample_output_sample_input_ref",
                        "produce_sample_output_sample_expected_ref");
        Map<?, ?> sampleInput = (Map<?, ?>) inputs.get("produce_sample_output_sample_input_ref");
        assertThat(sampleInput.get("ref")).isEqualTo("${data.input}");
        assertThat(sampleInput.get("bind_as")).isEqualTo("sample.input_ref");
        assertThat((List<?>) normalized.get("steps"))
                .singleElement()
                .satisfies(step -> {
                    Map<?, ?> stepMap = (Map<?, ?>) step;
                    assertThat(stepMap.get("action")).isEqualTo("execute_sample");
                    assertThat(stepMap.get("input"))
                            .isEqualTo("${package_inputs.inputs.produce_sample_output_sample_input_ref}");
                });
        assertThat(((Map<?, ?>) normalized.get("expected")).get("ref"))
                .isEqualTo("expected_results/expected_output.json");
        assertThat((List<?>) normalized.get("assertions"))
                .singleElement()
                .satisfies(assertion -> {
                    Map<?, ?> assertionMap = (Map<?, ?>) assertion;
                    assertThat(assertionMap.get("type")).isEqualTo("json_match");
                    assertThat(assertionMap.get("actual"))
                            .isEqualTo("${execute.produce_sample_output.outputs.actual_json}");
                    assertThat(assertionMap.get("oracle"))
                            .isEqualTo("${oracles.output_matches_expected_json_expected}");
                });
        Map<?, ?> oracles = (Map<?, ?>) normalized.get("oracles");
        Map<?, ?> expectedOracle = (Map<?, ?>) oracles.get("output_matches_expected_json_expected");
        assertThat(expectedOracle.get("type")).isEqualTo("expected_result_artifact");
        assertThat(expectedOracle.get("ref")).isEqualTo("expected_results/expected_output.json");
    }

    @Test
    void normalizesDirectVerifyExpectedRefIntoPrivateOracle() {
        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(Map.of(
                "dsl_version", "v0.2",
                "test_case_id", "TC-DIRECT-EXPECTED-001",
                "verify", Map.of("checks", List.of(Map.of(
                        "id", "payload_matches",
                        "type", "file_diff",
                        "actual", Map.of("ref", "${execute.run.outputs.actual}"),
                        "expected", "expected-results/approved/ER-001.yaml")))));

        Map<?, ?> oracles = (Map<?, ?>) normalized.get("oracles");
        Map<?, ?> expectedOracle = (Map<?, ?>) oracles.get("payload_matches_expected");
        assertThat(expectedOracle.get("type")).isEqualTo("expected_result_artifact");
        assertThat(expectedOracle.get("ref")).isEqualTo("expected-results/approved/ER-001.yaml");
        assertThat(((Map<?, ?>) normalized.get("expected")).get("ref"))
                .isEqualTo("expected-results/approved/ER-001.yaml");
        assertThat((List<?>) normalized.get("assertions"))
                .singleElement()
                .satisfies(assertion -> assertThat(((Map<?, ?>) assertion).get("oracle"))
                        .isEqualTo("${oracles.payload_matches_expected}"));
    }

    @Test
    void mapsVerifyExpectedDirectNameToMatchingExpectedResult() {
        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(Map.of(
                "test_case_id", "TC-001",
                "expected_results", Map.of(
                        "schema", Map.of("type", "schema", "ref", "schemas/payment.yaml"),
                        "contract", Map.of("type", "contract", "ref", "contracts/payment.yaml")),
                "verify", List.of(Map.of(
                        "type", "contract_matches",
                        "expected", "contract"))));

        assertThat((List<?>) normalized.get("assertions"))
                .singleElement()
                .satisfies(assertion -> assertThat(((Map<?, ?>) assertion).get("oracle"))
                        .isEqualTo("${oracles.contract}"));
    }

    @Test
    void mapsCanonicalVerifySelectorToRuntimeAssertionPath() {
        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(Map.of(
                "test_case_id", "TC-SELECTOR-001",
                "expected_results", Map.of(),
                "verify", List.of(Map.of(
                        "type", "json_path_equals",
                        "actual", "${execute.submit_payment.outputs.response_body}",
                        "selector", "$.status",
                        "expected", "ACCEPTED"))));

        assertThat((List<?>) normalized.get("assertions"))
                .singleElement()
                .satisfies(assertion -> {
                    Map<?, ?> assertionMap = (Map<?, ?>) assertion;
                    assertThat(assertionMap.get("type")).isEqualTo("json_path_equals");
                    assertThat(assertionMap.get("actual"))
                            .isEqualTo("${execute.submit_payment.outputs.response_body}");
                    assertThat(assertionMap.get("path")).isEqualTo("$.status");
                    assertThat(assertionMap.get("expected_value")).isEqualTo("ACCEPTED");
                });
    }

    @Test
    void normalizesListFixturesScalarInputsFallbackTargetsAndSitEnvironment() {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("test_case_id", "RP-002-TC-001");
        testCase.put("labels", Map.of("package", "RP-002"));
        testCase.put("source_refs", Map.of("acceptance_criteria", "docs/ac.md#RP-002-AC-009"));
        testCase.put("targets", Map.of(
                "RU-api", Map.of(
                        "ru_id", "RU-payment-api",
                        "runner", "spring_boot_cli",
                        "environment", "sit://payment/api")));
        testCase.put("setup", Map.of(
                "fixtures", List.of(
                        Map.of(
                                "id", "orders_file",
                                "type", "file_input",
                                "data_ref", "fixtures/orders.csv",
                                "provider", "file_fixture",
                                "action", "prepare_file",
                                "cleanup_action", "delete_file"),
                        Map.of(
                                "type", "message_event",
                                "payload_ref", "fixtures/event.json"))));
        testCase.put("execute", List.of(Map.of(
                "id", "run_cli",
                "target", "RU-missing-but-fallback-is-used",
                "operation", "execute_command",
                "with", Map.of(
                        "raw_arg", "fixtures/raw.txt",
                        "event_payload", Map.of(
                                "binding_type", "message",
                                "payload_ref", "fixtures/event.json")),
                "outputs", Map.of(
                        "actual_output", Map.of("ref", "actual/output.json")))));
        testCase.put("expected_results", Map.of(
                "golden", Map.of(
                        "type", "golden",
                        "file", "expected/golden.json"),
                "db_count", Map.of(
                        "type", "query",
                        "fixture_provider", "relational_db",
                        "query_name", "orders_count",
                        "sql_ref", "queries/count_orders.sql",
                        "row_count", 2)));
        testCase.put("verify", List.of(
                Map.of(
                        "id", "verify_status",
                        "type", "output_equals",
                        "actual", "$.status",
                        "expected", "APPROVED"),
                Map.of(
                        "id", "verify_db",
                        "type", "db_row_matches",
                        "expected", "db_count")));
        testCase.put("evidence", Map.of("required", List.of("${execute.run_cli.outputs.actual_output}")));
        testCase.put("runtime", Map.of("timeout", "PT5M"));

        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(testCase);

        assertThat(normalized).containsEntry("rp_id", "RP-002");
        assertThat(normalized).containsEntry("ac_id", "RP-002-AC-009");
        Map<?, ?> executionTarget = (Map<?, ?>) normalized.get("execution_target");
        assertThat(executionTarget.get("ru_id")).isEqualTo("RU-payment-api");
        assertThat(executionTarget.get("execution_mode")).isEqualTo("sit_deployed");
        Map<?, ?> inputs = (Map<?, ?>) ((Map<?, ?>) normalized.get("package_inputs")).get("inputs");
        assertThat(inputs.keySet().stream().map(Object::toString).toList())
                .contains("orders_file", "fixture_2", "raw_arg", "event_payload");
        Map<?, ?> ordersFile = (Map<?, ?>) inputs.get("orders_file");
        assertThat(ordersFile.get("bind_as")).isEqualTo("input_file");
        assertThat(ordersFile.get("lifecycle")).isEqualTo("read_only");
        Map<?, ?> secondFixture = (Map<?, ?>) inputs.get("fixture_2");
        assertThat(secondFixture.get("bind_as")).isEqualTo("message_event");
        assertThat(secondFixture.get("lifecycle")).isEqualTo("state_mutating");
        assertThat(((Map<?, ?>) inputs.get("raw_arg")).get("ref")).isEqualTo("fixtures/raw.txt");
        Map<?, ?> eventPayload = (Map<?, ?>) inputs.get("event_payload");
        assertThat(eventPayload.get("bind_as")).isEqualTo("message_event");
        assertThat(eventPayload.get("lifecycle")).isEqualTo("state_mutating");
        Map<?, ?> fixture = (Map<?, ?>) normalized.get("fixture");
        assertThat((List<?>) fixture.get("setup")).singleElement().satisfies(action -> {
            Map<?, ?> actionMap = (Map<?, ?>) action;
            assertThat(actionMap.get("id")).isEqualTo("setup_orders_file");
            assertThat(actionMap.get("provider")).isEqualTo("file_fixture");
            assertThat(actionMap.get("action")).isEqualTo("prepare_file");
        });
        assertThat((List<?>) fixture.get("cleanup")).singleElement().satisfies(action -> {
            Map<?, ?> actionMap = (Map<?, ?>) action;
            assertThat(actionMap.get("id")).isEqualTo("cleanup_orders_file");
            assertThat(actionMap.get("action")).isEqualTo("delete_file");
        });
        Map<?, ?> oracles = (Map<?, ?>) normalized.get("oracles");
        Map<?, ?> golden = (Map<?, ?>) oracles.get("golden");
        assertThat(golden.get("type")).isEqualTo("golden_file");
        assertThat(golden.get("ref")).isEqualTo("expected/golden.json");
        Map<?, ?> dbCount = (Map<?, ?>) oracles.get("db_count");
        assertThat(dbCount.get("type")).isEqualTo("query_result");
        assertThat(dbCount.get("fixture_provider")).isEqualTo("relational_db");
        assertThat(dbCount.get("query_name")).isEqualTo("orders_count");
        assertThat(dbCount.get("ref")).isEqualTo("queries/count_orders.sql");
        assertThat(dbCount.get("row_count")).isEqualTo(2);
        assertThat((List<?>) normalized.get("assertions"))
                .anySatisfy(assertion -> {
                    Map<?, ?> assertionMap = (Map<?, ?>) assertion;
                    assertThat(assertionMap.get("type")).isEqualTo("file_diff");
                    assertThat(assertionMap.get("path")).isEqualTo("$.status");
                    assertThat(assertionMap.get("oracle").toString()).startsWith("${oracles.");
                })
                .anySatisfy(assertion -> {
                    Map<?, ?> assertionMap = (Map<?, ?>) assertion;
                    assertThat(assertionMap.get("type")).isEqualTo("db_row_matches");
                    assertThat(assertionMap.get("oracle")).isEqualTo("${oracles.db_count}");
                });
    }

    @Test
    void preservesExistingLegacyRuntimeShapeWhenPresent() {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("rp_id", "RP-existing");
        testCase.put("ac_id", "AC-existing");
        testCase.put("artifact_status", "active");
        testCase.put("source_refs", Map.of("acceptance_criteria", "kept"));
        testCase.put("execution_target", Map.of("ru_id", "RU-existing"));
        testCase.put("package_inputs", Map.of("inputs", Map.of("kept", Map.of("ref", "kept"))));
        testCase.put("fixture", Map.of("setup", List.of()));
        testCase.put("steps", List.of(Map.of("id", "kept")));
        testCase.put("expected", Map.of("ref", "kept"));
        testCase.put("oracles", Map.of("kept", Map.of("type", "kept")));
        testCase.put("assertions", List.of(Map.of("type", "kept")));
        testCase.put("evidence_required", List.of("kept"));
        testCase.put("policy", Map.of("kept", true));
        testCase.put("labels", Map.of("package", "RP-derived"));
        testCase.put("targets", Map.of(
                "RU-local", Map.of(
                        "runner", "local_runner",
                        "environment_ref", "local://fixture")));
        testCase.put("execute", List.of(Map.of(
                "id", "run_local",
                "operation", "run_application",
                "outputs", Map.of("actual_output", Map.of("ref", "actual/local.txt")))));

        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(testCase);

        assertThat(normalized).containsEntry("rp_id", "RP-existing")
                .containsEntry("ac_id", "AC-existing")
                .containsEntry("artifact_status", "active");
        assertThat(normalized.get("execution_target")).isEqualTo(Map.of("ru_id", "RU-existing"));
        assertThat(normalized.get("package_inputs")).isEqualTo(Map.of("inputs", Map.of("kept", Map.of("ref", "kept"))));
        assertThat(normalized.get("fixture")).isEqualTo(Map.of("setup", List.of()));
        assertThat(normalized.get("steps")).isEqualTo(List.of(Map.of("id", "kept")));
        assertThat(normalized.get("expected")).isEqualTo(Map.of("ref", "kept"));
        assertThat(normalized.get("oracles")).isEqualTo(Map.of("kept", Map.of("type", "kept")));
        assertThat(normalized.get("assertions")).isEqualTo(List.of(Map.of("type", "kept")));
        assertThat(normalized.get("evidence_required")).isEqualTo(List.of("kept"));
        assertThat(normalized.get("policy")).isEqualTo(Map.of("kept", true));
        assertThat(normalized.get("source_refs")).isEqualTo(Map.of("acceptance_criteria", "kept"));
    }

    @Test
    void normalizesFallbacksForLocalEnvironmentDatasetBindingsAndImplicitExpectedResultOracle() {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("test_case_id", "RP-003-TC-001");
        testCase.put("source_refs", Map.of("acceptance_criteria", "RP-003-AC-001"));
        testCase.put("targets", Map.of(
                "RU-local", Map.of(
                        "runner", "local_cli",
                        "environment", "local://fixture/RP-003")));
        testCase.put("setup", Map.of(
                "fixtures", Map.of(
                        "customer_dataset", Map.of(
                                "type", "dataset",
                                "ref", "fixtures/customer-dataset.csv"))));
        testCase.put("expected_results", Map.of(
                "implicit_expected", Map.of("ref", "expected/default.json")));
        testCase.put("verify", List.of(Map.of(
                "type", "contains",
                "expected", "literal-value")));
        testCase.put("execute", List.of(Map.of(
                "operation", "run_application",
                "with", Map.of())));

        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(testCase);

        assertThat(normalized).containsEntry("ac_id", "RP-003-AC-001");
        Map<?, ?> executionTarget = (Map<?, ?>) normalized.get("execution_target");
        assertThat(executionTarget.get("execution_mode")).isEqualTo("local_fixture");
        Map<?, ?> inputs = (Map<?, ?>) ((Map<?, ?>) normalized.get("package_inputs")).get("inputs");
        Map<?, ?> customerDataset = (Map<?, ?>) inputs.get("customer_dataset");
        assertThat(customerDataset.get("bind_as")).isEqualTo("dataset");
        assertThat(customerDataset.get("lifecycle")).isEqualTo("read_only");
        Map<?, ?> oracle = (Map<?, ?>) ((Map<?, ?>) normalized.get("oracles")).get("implicit_expected");
        assertThat(oracle.get("type")).isEqualTo("expected_result_artifact");
        assertThat((List<?>) normalized.get("assertions"))
                .singleElement()
                .satisfies(assertion -> {
                    Map<?, ?> assertionMap = (Map<?, ?>) assertion;
                    assertThat(assertionMap.get("type")).isEqualTo("contains");
                    assertThat(assertionMap.get("oracle")).isEqualTo("${oracles.implicit_expected}");
                });
    }

    @Test
    void doesNotInventExpectedRefWhenExpectedResultsFirstEntryIsNotAMapping() {
        Map<String, Object> normalized = new DslTestCaseNormalizer().normalize(Map.of(
                "test_case_id", "RP-004-TC-001",
                "expected_results", Map.of("literal_expected", "plain text expected result")));

        assertThat(normalized).doesNotContainKey("expected");
        assertThat((Map<?, ?>) normalized.get("oracles")).isEmpty();
    }
}
