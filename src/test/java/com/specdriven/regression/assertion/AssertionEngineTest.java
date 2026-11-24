package com.specdriven.regression.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

    @Test
    void reportsSchemaTypeMismatchesForEveryRuntimeValueShape() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-SCHEMA-TYPE-SHAPES");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("schemas"));
        Files.writeString(actualOutput, """
                textValue: abc
                decimalValue: 1.25
                booleanValue: true
                objectValue:
                  id: O-001
                arrayValue:
                  - A-001
                unknownTypeValue: anything
                """);
        Files.writeString(tempDir.resolve("schemas/type-shapes.yaml"), """
                fields:
                  - path: $.textValue
                    type: number
                  - path: $.decimalValue
                    type: integer
                  - path: $.booleanValue
                    type: string
                  - path: $.objectValue
                    type: array
                  - path: $.arrayValue
                    type: object
                  - path: $.unknownTypeValue
                    type: custom_type
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-SCHEMA-TYPE-SHAPES",
                        "ac_id", "AC-SCHEMA-TYPE-SHAPES",
                        "oracles", Map.of(
                                "type_shapes", Map.of(
                                        "type", "schema",
                                        "ref", "schemas/type-shapes.yaml")),
                        "assertions", List.of(Map.of(
                                "type", "schema_matches",
                                "oracle", "${oracles.type_shapes}"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("path `$.textValue` expected type `number` but was `string`")
                .contains("path `$.decimalValue` expected type `integer` but was `number`")
                .contains("path `$.booleanValue` expected type `string` but was `boolean`")
                .contains("path `$.objectValue` expected type `array` but was `object`")
                .contains("path `$.arrayValue` expected type `object` but was `array`")
                .contains("path `$.unknownTypeValue` expected type `custom_type` but was `string`");
    }

    @Test
    void evaluatesStructuredAssertionsAndAggregatesMixedResults() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-STRUCTURED");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, """
                statusCode: 201
                paymentId: PAY-123
                riskScore: 0.051
                items:
                  - id: I-001
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-STRUCTURED",
                        "ac_id", "AC-STRUCTURED",
                        "assertions", List.of(
                                Map.of(
                                        "type", "json_path_equals",
                                        "path", "$.items.0.id",
                                        "expected_value", "I-001"),
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", "$.paymentId"),
                                Map.of(
                                        "type", "numeric_tolerance",
                                        "path", "$.riskScore",
                                        "expected_value", "0.05",
                                        "tolerance", "0.01"),
                                Map.of(
                                        "type", "response_status_equals",
                                        "path", "$.statusCode",
                                        "expected_status", "201"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.assertionType()).isEqualTo("multiple");
        assertThat(evidence)
                .contains("json path `$.items.0.id` matched `I-001`")
                .contains("json path `$.paymentId` was present")
                .contains("numeric path `$.riskScore` matched `0.05` within tolerance `0.01`")
                .contains("response status path `$.statusCode` matched `201`");
    }

    @Test
    void evaluatesResponseStatusFromProviderEvidenceWhenActualOutputOmitsStatus() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-HTTP-METADATA");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, """
                body: accepted
                """);
        Files.writeString(runDir.resolve("request_response.yaml"), """
                http_status: 204
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-HTTP-METADATA",
                        "ac_id", "AC-HTTP-METADATA",
                        "assertions", List.of(Map.of(
                                "type", "response_status_equals",
                                "expected_status", "204"))),
                actualOutput,
                runDir);

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.diffSummary()).isEqualTo("response status `http_status` matched `204`");
    }

    @Test
    void rejectsRetiredDbAssertionsBeforeReadingOraclesOrWritingEvidence() {
        Path runDir = tempDir.resolve("run");
        Map<String, Object> testCase = Map.of("assertions", List.of(
                Map.of("type", "file_diff"), Map.of("type", "db_row_matches")));
        var engine = new AssertionEngine();
        assertThatThrownBy(() -> engine.evaluateFileDiff(tempDir, testCase, tempDir.resolve("absent"), runDir))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("Legacy DB fixture execution is disabled");
        assertThatThrownBy(() -> engine.evaluateFileDiff(tempDir, testCase, tempDir.resolve("absent"), runDir,
                Map.of("db", Map.of("connection_ref", "fixture-marker"))))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("Legacy DB fixture execution is disabled");
        assertThat(runDir).doesNotExist();
    }

    @Test
    void fallsBackToFileDiffWhenNoStructuredAssertionsAreDeclared() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-FILE-DIFF");
        Path actualOutput = runDir.resolve("actual/output.txt");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("expected"));
        Files.writeString(actualOutput, "accepted\n");
        Files.writeString(tempDir.resolve("expected/output.txt"), "accepted\n");
        Files.writeString(tempDir.resolve("expected/result.yaml"), """
                expected_outputs:
                  output_ref: expected/output.txt
                """);
        Map<String, Object> testCase = new HashMap<>();
        testCase.put("test_case_id", "TC-FILE-DIFF");
        testCase.put("ac_id", "AC-FILE-DIFF");
        testCase.put("oracles", Map.of("", Map.of(
                "type", "expected_result",
                "ref", "expected/result.yaml")));

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                testCase,
                actualOutput,
                runDir);

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.diffSummary()).isEqualTo("files match");
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("decision_rule: ")
                .contains("diff_summary: files match");
    }

    @Test
    void reportsFileDiffFailureWhenExpectedArtifactDiffersFromActualOutput() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-FILE-DIFF-FAIL");
        Path actualOutput = runDir.resolve("actual/output.txt");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("expected"));
        Files.writeString(actualOutput, "actual-response\n");
        Files.writeString(tempDir.resolve("expected/output.txt"), "approved-response\n");
        Files.writeString(tempDir.resolve("expected/result.yaml"), """
                expected_outputs:
                  output_ref: expected/output.txt
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-FILE-DIFF-FAIL",
                        "ac_id", "AC-FILE-DIFF-FAIL",
                        "oracles", Map.of(
                                "approved_output", Map.of(
                                        "type", "expected_result",
                                        "ref", "expected/result.yaml")),
                        "assertions", List.of(Map.of(
                                "type", "file_diff",
                                "oracle", "${oracles.approved_output}"))),
                actualOutput,
                runDir);

        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.diffSummary())
                .isEqualTo("expected `expected/output.txt` differs from actual `actual/output.txt`");
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("status: failed")
                .contains("oracle: ${oracles.approved_output}");
    }

    @Test
    void reportsInlineAssertionFailuresForJsonNumericAndDetectedStatusPaths() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-INLINE-FAILURES");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, """
                status_code: 500
                riskScore: not-a-number
                payload:
                  id: PAY-404
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-INLINE-FAILURES",
                        "ac_id", "AC-INLINE-FAILURES",
                        "assertions", List.of(
                                Map.of(
                                        "type", "json_path_equals",
                                        "path", "$.payload.id",
                                        "expected_value", "PAY-001"),
                                Map.of(
                                        "type", "numeric_tolerance",
                                        "path", "$.riskScore",
                                        "oracle", Map.of("value", "0.05"),
                                        "tolerance", "0.01"),
                                Map.of(
                                        "type", "response_status_equals",
                                        "expected_status", "200"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("json path `$.payload.id` expected `PAY-001` but was `PAY-404`")
                .contains("numeric path `$.riskScore` expected `0.05` within tolerance `0.01` but was `not-a-number`")
                .contains("response status path `$.status_code` expected `200` but was `500`");
    }

    @Test
    void reportsMissingResponseStatusEvidenceAndJsonPathBoundaryResults() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-BOUNDARY-ASSERTIONS");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, """
                items:
                  - id: I-001
                body: accepted
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-BOUNDARY-ASSERTIONS",
                        "ac_id", "AC-BOUNDARY-ASSERTIONS",
                        "assertions", List.of(
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", "$.items.2.id"),
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", "$.items.nope"),
                                Map.of(
                                        "type", "json_path_equals",
                                        "path", "$.missing",
                                        "expected_value", ""),
                                Map.of(
                                        "type", "response_status_equals",
                                        "expected_status", "200"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("json path `$.items.2.id` was absent")
                .contains("json path `$.items.nope` was absent")
                .contains("json path `$.missing` matched ``")
                .contains("response status `http_status` expected `200` but was ``");
    }

    @Test
    void evaluatesRootBlankAndNonMapActualOutputBoundaryPaths() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-NON-MAP-ACTUAL");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, "[]\n");

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-NON-MAP-ACTUAL",
                        "ac_id", "AC-NON-MAP-ACTUAL",
                        "assertions", List.of(
                                Map.of(
                                        "type", "json_path_equals",
                                        "path", "",
                                        "expected_value", ""),
                                Map.of(
                                        "type", "json_path_equals",
                                        "path", "$",
                                        "expected_value", "{}"),
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", ""),
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", "$"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("json path `` matched ``")
                .contains("json path `$` matched `{}`")
                .contains("json path `` was absent")
                .contains("json path `$` was present");
    }

    @Test
    void evaluatesListPathExistenceAndScalarTraversalBoundaries() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-PATH-BOUNDARIES");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, """
                status: ACCEPTED
                items:
                  - id: I-001
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-PATH-BOUNDARIES",
                        "ac_id", "AC-PATH-BOUNDARIES",
                        "assertions", List.of(
                                Map.of(
                                        "type", "json_path_equals",
                                        "path", "$.status.code",
                                        "expected_value", ""),
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", "$.items.0.id"),
                                Map.of(
                                        "type", "json_path_absent",
                                        "path", "$.items.nope"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("json path `$.status.code` matched ``")
                .contains("json path `$.items.0.id` was present")
                .contains("json path `$.items.nope` was absent");
    }

    @Test
    void evaluatesSchemaStringBooleanFlagsAndEmptyContractExpectations() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-SCHEMA-STRING-FLAGS");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("schemas"));
        Files.createDirectories(tempDir.resolve("contracts"));
        Files.writeString(actualOutput, """
                status: ACCEPTED
                error: duplicate
                """);
        Files.writeString(tempDir.resolve("schemas/string-flags.yaml"), """
                fields:
                  - path: $.optionalNote
                    type: string
                    required: "false"
                  - path: $.error
                    absent: "true"
                """);
        Files.writeString(tempDir.resolve("contracts/empty-contract.yaml"), """
                contract_id: empty
                interactions: {}
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-SCHEMA-STRING-FLAGS",
                        "ac_id", "AC-SCHEMA-STRING-FLAGS",
                        "oracles", Map.of(
                                "string_flag_schema", Map.of(
                                        "type", "schema",
                                        "ref", "schemas/string-flags.yaml"),
                                "empty_contract", Map.of(
                                        "type", "contract",
                                        "ref", "contracts/empty-contract.yaml")),
                        "assertions", List.of(
                                Map.of(
                                        "type", "schema_matches",
                                        "oracle", "${oracles.string_flag_schema}"),
                                Map.of(
                                        "type", "contract_matches",
                                        "oracle", "${oracles.empty_contract}"))),
                actualOutput,
                runDir);

        String evidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evidence)
                .contains("path `$.error` must be absent")
                .contains("contract `empty_contract` matched 0 expectation(s)");
    }

    @Test
    void blocksUnsafeSchemaReferences() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-UNSAFE-REF");
        Path actualOutput = runDir.resolve("actual/output.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, "status: checked\n");

        assertThatThrownBy(() -> new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-UNSAFE-SCHEMA",
                        "ac_id", "AC-UNSAFE-SCHEMA",
                        "oracles", Map.of(
                                "unsafe_schema", Map.of(
                                        "type", "schema",
                                        "ref", "../outside/schema.yaml")),
                        "assertions", List.of(Map.of(
                                "type", "schema_matches",
                                "oracle", "${oracles.unsafe_schema}"))),
                actualOutput,
                runDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema assertion ref must stay under the RP package");
    }

    @Test
    void wrapsMissingActualOutputAndAssertionEvidenceWriteFailures() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-IO-FAILURES");
        Path missingActualOutput = runDir.resolve("actual/missing.yaml");

        assertThatThrownBy(() -> new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-MISSING-ACTUAL",
                        "ac_id", "AC-MISSING-ACTUAL",
                        "assertions", List.of(Map.of(
                                "type", "json_path_equals",
                                "path", "$.status",
                                "expected_value", "OK"))),
                missingActualOutput,
                runDir))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read assertion actual output");

        Path runDirAsFile = tempDir.resolve("evidence-file");
        Path actualOutput = tempDir.resolve("actual.yaml");
        Files.writeString(runDirAsFile, "not a directory\n");
        Files.writeString(actualOutput, "status: OK\n");

        assertThatThrownBy(() -> new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-EVIDENCE-WRITE-FAIL",
                        "ac_id", "AC-EVIDENCE-WRITE-FAIL",
                        "assertions", List.of(Map.of(
                                "type", "json_path_equals",
                                "path", "$.status",
                                "expected_value", "OK"))),
                actualOutput,
                runDirAsFile))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write assertion evidence");
    }

    @Test
    void wrapsFileDiffComparisonFailureWhenOracleDefinitionIsMissing() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-MISSING-FILE-DIFF-ORACLE");
        Path actualOutput = runDir.resolve("actual/output.txt");
        Files.createDirectories(actualOutput.getParent());
        Files.writeString(actualOutput, "actual\n");

        assertThatThrownBy(() -> new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-MISSING-FILE-DIFF-ORACLE",
                        "ac_id", "AC-MISSING-FILE-DIFF-ORACLE",
                        "assertions", List.of(Map.of(
                                "type", "file_diff",
                                "oracle", "${oracles.missing_output}"))),
                actualOutput,
                runDir))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to compare assertion files");
    }

    @Test
    void reportsSchemaTypeMismatchForUnknownRuntimeValueShape() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-SCHEMA-UNKNOWN-SHAPE");
        Path actualOutput = runDir.resolve("actual/response.yaml");
        Files.createDirectories(actualOutput.getParent());
        Files.createDirectories(tempDir.resolve("schemas"));
        Files.writeString(actualOutput, """
                observed_on: 2026-06-28
                """);
        Files.writeString(tempDir.resolve("schemas/unknown-shape.yaml"), """
                fields:
                  - path: $.observed_on
                    type: string
                """);

        AssertionEvaluation evaluation = new AssertionEngine().evaluateFileDiff(
                tempDir,
                Map.of(
                        "test_case_id", "TC-SCHEMA-UNKNOWN-SHAPE",
                        "ac_id", "AC-SCHEMA-UNKNOWN-SHAPE",
                        "oracles", Map.of(
                                "unknown_shape", Map.of(
                                        "type", "schema",
                                        "ref", "schemas/unknown-shape.yaml")),
                        "assertions", List.of(Map.of(
                                "type", "schema_matches",
                                "oracle", "${oracles.unknown_shape}"))),
                actualOutput,
                runDir);

        assertThat(evaluation.passed()).isFalse();
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("path `$.observed_on` expected type `string` but was `missing`");
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
