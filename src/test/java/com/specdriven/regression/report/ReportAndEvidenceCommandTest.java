package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportAndEvidenceCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path VALID_RESULT = Path.of("samples/40-evidence-reporting/evidence_hardening/valid_result.json");

    @TempDir
    Path tempDir;

    @Test
    void validResultSupportsTextYamlJsonReportAndEvidenceValidation() {
        CommandResult textReport = execute("report", "--result", VALID_RESULT.toString(), "--format", "text");
        CommandResult yamlReport = execute("report", "--result", VALID_RESULT.toString(), "--format", "yaml");
        CommandResult jsonReport = execute("report", "--result", VALID_RESULT.toString(), "--format", "json");
        CommandResult evidence = execute("validate-evidence", "--result", VALID_RESULT.toString());

        assertThat(textReport.exit()).as(textReport.stderr() + textReport.stdout()).isZero();
        assertThat(textReport.stdout())
                .contains("report_status: review_ready")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed")
                .doesNotContain("step_timings");
        assertThat(yamlReport.exit()).as(yamlReport.stderr() + yamlReport.stdout()).isZero();
        assertThat(yamlReport.stdout())
                .contains("report_status: review_ready")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");
        assertThat(jsonReport.exit()).as(jsonReport.stderr() + jsonReport.stdout()).isZero();
        assertThat(jsonReport.stdout())
                .contains("\"report_status\":\"review_ready\"")
                .contains("\"missing_evidence_count\":0")
                .contains("\"masking_status\":\"passed\"")
                .doesNotContain("step_timings");
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");
    }

    @Test
    void reportShowsOnlyRecordedStepTimingsAndKeepsProviderDataOutOfAllFormats() throws Exception {
        Path result = mutableEvidenceResult("timed").toRealPath();
        ObjectNode document = (ObjectNode) JSON.readTree(result.toFile());
        String testId = document.path("test_case_id").asText();
        var steps = document.withArray("steps");
        steps.addObject().put("test_case_id", testId).put("id", "prepare").put("phase", "setup")
                .put("operation", "load_stubs").put("duration_ms", 0);
        ObjectNode slow = steps.addObject().put("test_case_id", testId).put("id", "read_rows")
                .put("phase", "execute").put("operation", "db_query").put("duration_ms", 93)
                .put("suite_path", "billing");
        slow.putObject("inputs").put("query", "private-query-input");
        slow.putObject("outputs").put("rows", "private-query-output");
        steps.addObject().put("test_case_id", testId).put("id", "untimed_step").put("phase", "cleanup")
                .put("operation", "reset_mock");
        JSON.writeValue(result.toFile(), document);

        CommandResult json = execute("report", "--result", result.toString(), "--format", "json",
                "--coverage-dir", tempDir.toRealPath().resolve("coverage").toString());
        assertThat(json.exit()).as(json.stderr() + json.stdout()).isZero();
        JsonNode report = JSON.readTree(json.stdout());
        assertThat(report.path("step_timings")).isEqualTo(JSON.valueToTree(List.of(
                Map.of("suite_path", "billing", "test_case_id", testId, "step_id", "read_rows", "phase", "execute",
                        "operation", "db_query", "duration_ms", 93),
                Map.of("test_case_id", testId, "step_id", "prepare", "phase", "setup",
                        "operation", "load_stubs", "duration_ms", 0))));
        assertThat(report.path("masking_status").asText()).isEqualTo("passed");
        assertThat(Files.isRegularFile(Path.of(report.path("coverage_json").asText()))).isTrue();
        assertThat(json.stdout()).doesNotContain("private-query-input", "private-query-output");

        for (String format : List.of("text", "yaml")) {
            CommandResult formatted = execute("report", "--result", result.toString(), "--format", format);
            assertThat(formatted.exit()).as(formatted.stderr() + formatted.stdout()).isZero();
            assertThat(formatted.stdout()).contains("step_timings:", "test_case_id: " + testId, "step_id: read_rows",
                    "phase: execute", "operation: db_query", "duration_ms: 93", "duration_ms: 0", "suite_path: billing")
                    .doesNotContain("untimed_step", "private-query-input", "private-query-output");
        }

        slow.putObject("outputs").put("password", "private-output-credential");
        JSON.writeValue(result.toFile(), document);
        CommandResult blocked = execute("report", "--result", result.toString(), "--format", "json");
        assertThat(blocked.exit()).isEqualTo(1);
        assertThat(blocked.stdout()).contains("SECRET_GUARDRAIL")
                .doesNotContain("step_timings", "private-output-credential");
    }

    @Test
    void unsupportedReportFormatStillFailsAsUsageError() {
        CommandResult result = execute("report", "--result", VALID_RESULT.toString(), "--format", "xml");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr()).contains("Unsupported --format: xml");
        assertThat(result.stdout()).isBlank();
    }

    @Test
    void invalidEvidenceAndMalformedResultFailReleaseGateCommands() throws Exception {
        Path invalidEvidence = mutableEvidenceResult("missing_provider_evidence");
        Files.delete(invalidEvidence.getParent().resolve("evidence/provider_evidence.json"));
        Path malformed = tempDir.resolve("malformed-result.json");
        Files.writeString(malformed, "{ not valid json");

        CommandResult evidence = execute("validate-evidence", "--result", invalidEvidence.toString());
        CommandResult report = execute("report", "--result", invalidEvidence.toString(), "--format", "text");
        CommandResult malformedReport = execute("report", "--result", malformed.toString());

        assertThat(evidence.exit()).isEqualTo(1);
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: failed")
                .contains("reason: missing_evidence_file");
        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_file");
        assertThat(malformedReport.exit()).isEqualTo(1);
        assertThat(malformedReport.stdout())
                .contains("report_status: invalid")
                .contains("reason: invalid_result_json");
    }

    private Path mutableEvidenceResult(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/40-evidence-reporting/evidence_hardening"), target);
        return target.resolve("valid_result.json");
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
