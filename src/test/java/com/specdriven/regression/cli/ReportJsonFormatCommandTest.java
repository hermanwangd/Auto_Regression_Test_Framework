package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReportJsonFormatCommandTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path VALID_RESULT =
            Path.of("samples/40-evidence-reporting/evidence_hardening/valid_result.json");
    private static final Path MISSING_EVIDENCE_RESULT =
            Path.of("samples/40-evidence-reporting/evidence_hardening/invalid_missing_evidence_result.json");
    private static final Path SECRET_LEAK_RESULT =
            Path.of("samples/40-evidence-reporting/evidence_hardening/invalid_secret_leak_result.json");

    @TempDir
    Path tempDir;

    @Test
    void validResultSupportsJsonReportFormat() throws Exception {
        CommandResult report = execute("report", "--result", VALID_RESULT.toString(), "--format", "json");

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stderr()).isBlank();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("review_ready");
        assertThat(json.path("suite_id").asText()).isEqualTo("EVIDENCE-HARDENING-v0.2");
        assertThat(json.path("batch_id").asText()).isEqualTo("BATCH-EVIDENCE-001");
        assertThat(json.path("run_id").asText()).isEqualTo("RUN-EVIDENCE-001");
        assertThat(json.path("test_count").asInt()).isEqualTo(1);
        assertThat(json.path("passed_count").asInt()).isEqualTo(1);
        assertThat(json.path("failed_count").asInt()).isZero();
        assertThat(json.path("missing_evidence_count").asInt()).isZero();
        assertThat(json.path("provider_results_count").asInt()).isEqualTo(3);
        assertThat(json.path("release_evidence_eligible").asBoolean()).isFalse();
        assertThat(json.path("masking_status").asText()).isEqualTo("passed");
        assertThat(json.path("failure_codes")).isEmpty();
    }

    @Test
    void passedResultWithoutEvidenceIndexStillSupportsJsonReportFormat() throws Exception {
        Path resultJson = standardResult("passed");

        CommandResult report = execute("report", "--result", resultJson.toString(), "--format", "json");

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("review_ready");
        assertThat(json.path("test_count").asInt()).isEqualTo(1);
        assertThat(json.path("passed_count").asInt()).isEqualTo(1);
        assertThat(json.path("failed_count").asInt()).isZero();
        assertThat(json.path("evidence_dir").asText()).isBlank();
        assertThat(json.path("provider_evidence_summary")).isEmpty();
    }

    @Test
    void failedResultWithoutEvidenceIndexReturnsReviewReadyWithFailuresJson() throws Exception {
        Path resultJson = standardResult("failed");

        CommandResult report = execute("report", "--result", resultJson.toString(), "--format", "json");

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("review_ready_with_failures");
        assertThat(json.path("test_count").asInt()).isEqualTo(1);
        assertThat(json.path("passed_count").asInt()).isZero();
        assertThat(json.path("failed_count").asInt()).isEqualTo(1);
        assertThat(json.path("failed_verify_summary")).isNotEmpty();
    }

    @Test
    void invalidEvidenceResultReturnsOwnerActionableJsonFailure() throws Exception {
        CommandResult report = execute("report", "--result", MISSING_EVIDENCE_RESULT.toString(), "--format", "json");

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stderr()).isBlank();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("failed");
        assertThat(json.path("failure_code").asText()).isEqualTo("EVIDENCE_VALIDATION_FAILED");
        assertThat(json.path("category").asText()).isEqualTo("EVIDENCE_ERROR");
        assertThat(json.path("message").asText()).contains("Fix result evidence references");
        assertThat(json.path("findings")).isNotEmpty();
    }

    @Test
    void secretLeakResultReturnsJsonFailureWithoutClaimingReviewReady() throws Exception {
        CommandResult report = execute("report", "--result", SECRET_LEAK_RESULT.toString(), "--format", "json");

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stderr()).isBlank();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("failed");
        assertThat(json.path("failure_code").asText()).isEqualTo("SECRET_GUARDRAIL_ERROR");
        assertThat(json.path("category").asText()).isEqualTo("SECRET_GUARDRAIL_ERROR");
        assertThat(json.toString()).contains("raw_secret");
        assertThat(json.toString()).doesNotContain("review_ready");
    }

    @ParameterizedTest
    @MethodSource("ordinaryConnectionHeaders")
    void ordinaryHttpConnectionHeaderDoesNotBecomeASecret(
            String location, String headerName, List<String> values) throws Exception {
        Path resultJson = resultWithResponseHeaders(location, Map.of(headerName, values));

        CommandResult report = execute("report", "--result", resultJson.toString(), "--format", "json");

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("review_ready");
        assertThat(json.path("masking_status").asText()).isEqualTo("passed");
    }

    private static Stream<Arguments> ordinaryConnectionHeaders() {
        return Stream.of("steps", "step_results", "provider_results").flatMap(location -> Stream.of(
                Arguments.of(location, "connection", List.of("keep-alive")),
                Arguments.of(location, "Connection", List.of("CLOSE")),
                Arguments.of(location, "cOnNeCtIoN", List.of(" Keep-Alive,\tclose ", "keep-alive"))));
    }

    @ParameterizedTest
    @MethodSource("rawResponseHeaderValues")
    void responseHeadersStillRejectRawCredentials(String headerName, Object value) throws Exception {
        Path resultJson = resultWithResponseHeaders("steps", Map.of(headerName, value));

        assertRawSecretReport(resultJson, "steps[0].outputs.response.headers." + headerName);
    }

    private static Stream<Arguments> rawResponseHeaderValues() {
        return Stream.of(
                Arguments.of("connection", List.of("unapproved-connection-value")),
                Arguments.of("connection", List.of("jdbc:postgresql://127.0.0.1/example")),
                Arguments.of("connection", List.of("keep-alive", "Bearer dummy-test-token")),
                Arguments.of("connection", Map.of("value", "keep-alive")),
                Arguments.of("Authorization", List.of("dummy-test-authorization")),
                Arguments.of("password", List.of("dummy-test-password")));
    }

    @Test
    void connectionOutsideHttpResponseHeadersRemainsSecret() throws Exception {
        Path resultJson = standardResult("passed");
        ObjectNode result = (ObjectNode) OBJECT_MAPPER.readTree(resultJson.toFile());
        result.put("connection", "keep-alive");
        OBJECT_MAPPER.writeValue(resultJson.toFile(), result);

        assertRawSecretReport(resultJson, "connection");
    }

    @Test
    void responseHeaderShapeCannotClearInheritedSecretContext() throws Exception {
        Path resultJson = resultWithResponseHeaders("steps", Map.of("connection", List.of("keep-alive")));
        ObjectNode result = (ObjectNode) OBJECT_MAPPER.readTree(resultJson.toFile());
        result.putObject("credentials").set("steps", result.remove("steps"));
        // Keep the required result steps while the copied shape is inside a credential object.
        result.putArray("steps");
        OBJECT_MAPPER.writeValue(resultJson.toFile(), result);

        assertRawSecretReport(resultJson, "credentials.steps[0].outputs.response.headers.connection");
    }

    private Path resultWithResponseHeaders(String location, Map<String, Object> headers) throws Exception {
        Path resultJson = standardResult("passed");
        ObjectNode result = (ObjectNode) OBJECT_MAPPER.readTree(resultJson.toFile());
        ObjectNode operation = switch (location) {
            case "steps" -> (ObjectNode) result.path("steps").path(0);
            case "step_results" -> result.putArray("step_results").addObject()
                    .put("id", "sample_execute").put("status", "passed");
            case "provider_results" -> ((ObjectNode) result.path("provider_results").path(0))
                    .putArray("resolved_operation_results").addObject()
                    .put("operation", "http_request").put("status", "passed");
            default -> throw new IllegalArgumentException("Unknown canonical output location: " + location);
        };
        operation.putObject("outputs").set("response.headers", OBJECT_MAPPER.valueToTree(headers));
        OBJECT_MAPPER.writeValue(resultJson.toFile(), result);
        return resultJson;
    }

    private void assertRawSecretReport(Path resultJson, String fieldPath) throws Exception {
        CommandResult report = execute("report", "--result", resultJson.toString(), "--format", "json");
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isEqualTo(1);
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("failure_code").asText()).isEqualTo("SECRET_GUARDRAIL_RAW_SECRET");
        assertThat(json.path("category").asText()).isEqualTo("SECRET_GUARDRAIL_ERROR");
        assertThat(json.path("findings").toString()).contains("raw_secret", fieldPath);
        assertThat(json.toString()).doesNotContain("review_ready");
    }

    @Test
    void missingResultJsonReturnsOwnerActionableJsonFailure() throws Exception {
        Path missing = tempDir.resolve("missing-result.json");

        CommandResult report = execute("report", "--result", missing.toString(), "--format", "json");

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stderr()).isBlank();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("failed");
        assertThat(json.path("failure_code").asText()).isEqualTo("RESULT_JSON_MISSING");
        assertThat(json.path("category").asText()).isEqualTo("CONFIGURATION_ERROR");
        assertThat(json.path("message").asText()).contains("Missing result JSON");
    }

    @Test
    void invalidResultJsonReturnsOwnerActionableJsonFailure() throws Exception {
        Path invalid = tempDir.resolve("invalid-result.json");
        Files.writeString(invalid, "{}");

        CommandResult report = execute("report", "--result", invalid.toString(), "--format", "json");

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stderr()).isBlank();
        JsonNode json = OBJECT_MAPPER.readTree(report.stdout());
        assertThat(json.path("report_status").asText()).isEqualTo("failed");
        assertThat(json.path("failure_code").asText()).isEqualTo("VALIDATION_INVALID_RESULT_JSON");
        assertThat(json.path("category").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json.path("message").asText()).contains("Use a JSON object");
        assertThat(json.path("findings")).isNotEmpty();
    }

    @Test
    void unknownReportFormatStillFailsAsUsageError() {
        CommandResult report = execute("report", "--result", VALID_RESULT.toString(), "--format", "xml");

        assertThat(report.exit()).isEqualTo(2);
        assertThat(report.stderr()).contains("Unsupported --format: xml");
        assertThat(report.stdout()).isBlank();
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path standardResult(String status) throws Exception {
        Path resultJson = tempDir.resolve(status + "-result.json");
        Files.writeString(resultJson, """
                {
                  "framework_version": "0.3.0",
                  "dsl_version": "v0.2",
                  "suite_id": "JSON-REPORT-NO-EVIDENCE-v0.2",
                  "batch_id": "BATCH-JSON-REPORT",
                  "run_id": "RUN-JSON-REPORT",
                  "test_case_id": "JSON-REPORT-TC-001",
                  "test_count": 1,
                  "status": "%s",
                  "profile": "local_json_report",
                  "environment": "local_json_report",
                  "start_time": "2026-07-10T00:00:00Z",
                  "end_time": "2026-07-10T00:00:01Z",
                  "duration_ms": 1000,
                  "timestamps": {
                    "started_at": "2026-07-10T00:00:00Z",
                    "finished_at": "2026-07-10T00:00:01Z"
                  },
                  "test_results": [
                    {
                      "test_case_id": "JSON-REPORT-TC-001",
                      "status": "%s",
                      "profile": "local_json_report",
                      "provider_id": "json-report-provider",
                      "provider_type": "sample_fake_provider"
                    }
                  ],
                  "provider_summary": [
                    {
                      "provider_id": "json-report-provider",
                      "provider_type": "sample_fake_provider",
                      "runtime_mode": "mock"
                    }
                  ],
                  "provider_results": [
                    {
                      "provider_id": "json-report-provider",
                      "provider_type": "sample_fake_provider",
                      "profile": "local_json_report",
                      "runtime_mode": "mock",
                      "resolved_operation_result": {
                        "operation": "sample_execute",
                        "status": "%s"
                      },
                      "release_evidence_eligible": false
                    }
                  ],
                  "verify_results": [
                    {
                      "id": "sample_verify",
                      "status": "%s"
                    }
                  ],
                  "steps": [
                    {
                      "id": "sample_execute",
                      "status": "%s"
                    }
                  ],
                  "evidence_refs": [],
                  "failure": {
                    "code": null,
                    "classification": null,
                    "reason": null,
                    "owner_action": null
                  }
                }
                """.formatted(status, status, status, status, status));
        return resultJson;
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
