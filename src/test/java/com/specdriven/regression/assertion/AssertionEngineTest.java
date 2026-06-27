package com.specdriven.regression.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssertionEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void evaluatesSchemaAndContractAssertionsAgainstActualOutput() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-SCHEMA-CONTRACT");
        Path actualOutput = runDir.resolve("actual/response.json");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("schemas"));
        Files.createDirectories(tempDir.resolve("contracts"));
        Files.writeString(actualOutput, """
                {"paymentId":"PAY-001","status":"ACCEPTED","riskScore":0.049}
                """);
        Files.writeString(tempDir.resolve("schemas/payment-response-schema.yaml"), """
                fields:
                  - path: $.paymentId
                    type: string
                  - path: $.status
                    type: string
                    enum: [ACCEPTED, PENDING]
                  - path: $.riskScore
                    type: number
                """);
        Files.writeString(tempDir.resolve("contracts/payment-submit-contract.yaml"), """
                contract_id: payment-submit-v1
                expectations:
                  - path: $.paymentId
                    type: string
                  - path: $.status
                    equals: ACCEPTED
                  - path: $.error
                    absent: true
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                schemaAndContractTestCase(),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isTrue();
        assertThat(evidence)
                .contains("type: schema_matches")
                .contains("schema `payment_response_schema` matched 3 rule(s)")
                .contains("type: contract_matches")
                .contains("contract `payment_submit_contract` matched 3 expectation(s)");
    }

    @Test
    void failsSchemaAssertionWhenRequiredPathIsMissing() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-SCHEMA-FAIL");
        Path actualOutput = runDir.resolve("actual/response.json");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("schemas"));
        Files.writeString(actualOutput, """
                {"status":"ACCEPTED"}
                """);
        Files.writeString(tempDir.resolve("schemas/payment-response-schema.yaml"), """
                fields:
                  - path: $.paymentId
                    type: string
                  - path: $.status
                    type: string
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-SCHEMA-FAIL",
                        "ac_id", "AC-SCHEMA-FAIL",
                        "oracles", Map.of(
                                "payment_response_schema", Map.of(
                                        "type", "schema",
                                        "ref", "schemas/payment-response-schema.yaml")),
                        "assertions", List.of(Map.of(
                                "type", "schema_matches",
                                "oracle", "${oracles.payment_response_schema}"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("type: schema_matches")
                .contains("status: failed")
                .contains("required path `$.paymentId` is missing");
    }

    @Test
    void reportsSchemaAndContractRuleMismatches() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-SCHEMA-CONTRACT-FAIL");
        Path actualOutput = runDir.resolve("actual/response.json");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("schemas"));
        Files.createDirectories(tempDir.resolve("contracts"));
        Files.writeString(actualOutput, """
                {
                  "paymentId": 123,
                  "status": "DECLINED",
                  "error": "duplicate",
                  "enabled": true,
                  "metadata": {"source": "api"},
                  "items": [{"id": "I-001"}],
                  "count": 2
                }
                """);
        Files.writeString(tempDir.resolve("schemas/payment-response-schema.yaml"), """
                fields:
                  - path: $.paymentId
                    type: string
                  - path: $.status
                    values: [ACCEPTED, PENDING]
                  - path: $.error
                    absent: true
                  - path: $.optionalNote
                    type: string
                    required: false
                  - path: $.enabled
                    type: boolean
                  - path: $.metadata
                    type: object
                  - path: $.items
                    type: array
                  - path: $.count
                    type: integer
                """);
        Files.writeString(tempDir.resolve("contracts/payment-submit-contract.yaml"), """
                interactions:
                  - name: accepted-payment
                    expectations:
                      - path: $.status
                        equals: ACCEPTED
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                schemaAndContractTestCase(),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("path `$.paymentId` expected type `string` but was `integer`")
                .contains("path `$.status` value `DECLINED` is outside allowed values `[ACCEPTED, PENDING]`")
                .contains("path `$.error` must be absent")
                .contains("contract `payment_submit_contract` failed")
                .contains("path `$.status` expected `ACCEPTED` but was `DECLINED`");
    }

    private Map<String, Object> schemaAndContractTestCase() {
        return Map.of(
                "test_case_id", "TC-SCHEMA-CONTRACT",
                "ac_id", "AC-SCHEMA-CONTRACT",
                "oracles", Map.of(
                        "payment_response_schema", Map.of(
                                "type", "schema",
                                "ref", "schemas/payment-response-schema.yaml"),
                        "payment_submit_contract", Map.of(
                                "type", "contract",
                                "ref", "contracts/payment-submit-contract.yaml")),
                "assertions", List.of(
                        Map.of(
                                "type", "schema_matches",
                                "oracle", "${oracles.payment_response_schema}"),
                        Map.of(
                                "type", "contract_matches",
                                "oracle", "${oracles.payment_submit_contract}")));
    }
}
