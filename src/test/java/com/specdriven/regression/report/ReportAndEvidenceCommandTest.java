package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportAndEvidenceCommandTest {

    private static final Path VALID_RESULT = Path.of("samples/evidence_hardening/valid_result.json");

    @TempDir
    Path tempDir;

    @Test
    void validResultSupportsTextReportYamlReportAndEvidenceValidation() {
        CommandResult textReport = execute("report", "--result", VALID_RESULT.toString(), "--format", "text");
        CommandResult yamlReport = execute("report", "--result", VALID_RESULT.toString(), "--format", "yaml");
        CommandResult evidence = execute("validate-evidence", "--result", VALID_RESULT.toString());

        assertThat(textReport.exit()).as(textReport.stderr() + textReport.stdout()).isZero();
        assertThat(textReport.stdout())
                .contains("report_status: review_ready")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");
        assertThat(yamlReport.exit()).as(yamlReport.stderr() + yamlReport.stdout()).isZero();
        assertThat(yamlReport.stdout())
                .contains("report_status: review_ready")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");
    }

    @Test
    void jsonReportFormatIsNotAPublicV024Contract() {
        CommandResult result = execute("report", "--result", VALID_RESULT.toString(), "--format", "json");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr()).contains("Unsupported --format: json");
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
        copyDirectory(Path.of("samples/evidence_hardening"), target);
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
