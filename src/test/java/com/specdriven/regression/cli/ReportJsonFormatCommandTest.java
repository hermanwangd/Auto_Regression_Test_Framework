package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportJsonFormatCommandTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path VALID_RESULT =
            Path.of("samples/40-evidence-reporting/evidence_hardening/valid_result.json");
    private static final Path MISSING_EVIDENCE_RESULT =
            Path.of("samples/40-evidence-reporting/evidence_hardening/invalid_missing_evidence_result.json");
    private static final Path SECRET_LEAK_RESULT =
            Path.of("samples/40-evidence-reporting/evidence_hardening/invalid_secret_leak_result.json");

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

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
