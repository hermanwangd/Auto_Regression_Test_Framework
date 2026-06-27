package com.specdriven.regression.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DslTestCaseNormalizerTest {

    @Test
    void normalizesExecutionFocusedDslV1IntoRuntimeShape() {
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
        assertThat(executionTarget.get("adapter")).isEqualTo("request_response");
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
}
