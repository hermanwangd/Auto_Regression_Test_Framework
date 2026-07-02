package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoapMockCapabilityCommandTest {

    private static final Path SUITE =
            Path.of("samples/provider_capability/soap_mock/suite_manifest.yaml");
    private static final Path FAILURE_SUITE =
            Path.of("samples/provider_capability/soap_mock/suite_manifest_failure.yaml");
    private static final Path BOUNDARY_SUITE =
            Path.of("samples/provider_capability/soap_mock/suite_manifest_boundary.yaml");

    @TempDir
    Path tempDir;

    @Test
    void sampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/provider_capability/soap_mock/suite_manifest.yaml",
                "samples/provider_capability/soap_mock/test_case.yaml",
                "samples/provider_capability/soap_mock/provider_instances/payment_soap_mock.yaml",
                "samples/provider_capability/soap_mock/provider_instances/payment_soap_client.yaml",
                "samples/provider_capability/soap_mock/env_profiles/local_soap_mock.yaml",
                "samples/provider_capability/soap_mock/environment_bindings/local_soap_mock.yaml",
                "samples/provider_capability/soap_mock/execution_profiles/local_soap_mock.yaml",
                "samples/provider_capability/soap_mock/fixtures/payment_submit_request.xml",
                "samples/provider_capability/soap_mock/fixtures/payment_submit_response.xml",
                "samples/provider_capability/soap_mock/fixtures/payment_boundary_request.xml",
                "samples/provider_capability/soap_mock/fixtures/payment_boundary_response.xml",
                "samples/provider_capability/soap_mock/suite_manifest_failure.yaml",
                "samples/provider_capability/soap_mock/test_case_failure.yaml",
                "samples/provider_capability/soap_mock/suite_manifest_boundary.yaml",
                "samples/provider_capability/soap_mock/test_case_boundary.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void sampleValidatesSoapMockAndHttpRequestProviderContracts() {
        CommandResult result = execute("validate", "--suite", SUITE.toString());

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: SOAP-MOCK-SAMPLE-v0.2")
                .contains("payment-soap-mock")
                .contains("payment-soap-client")
                .contains("soap_mock")
                .contains("rest_client");
    }

    @Test
    void sampleDryRunResolvesSoapMockAndHttpClientTargetsWithoutRuntimeDispatch() {
        CommandResult result = execute("run", "--suite", SUITE.toString(), "--dry-run");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("target: payment_soap_mock")
                .contains("provider_id: payment-soap-mock")
                .contains("provider_type: soap_mock")
                .contains("target: payment_soap_client")
                .contains("provider_id: payment-soap-client")
                .contains("provider_type: rest_client");
    }

    @Test
    void happyPathRunsSoapMockAndHttpRequestProvidersAndReportConsumesResult() {
        CommandResult run = execute("run", "--suite", SUITE.toString(), "--profile", "local_soap_mock");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: soap_mock,rest_client")
                .contains("provider_ids: payment-soap-mock,payment-soap-client")
                .contains("evidence_classification: framework_provider_capability_only");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertThat(evidenceDir.resolve("provider-evidence/soap/request_journal.json")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/soap/server_log.txt")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/http/response.json")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/payment_soap_status.yaml")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/payment_soap_request_count.yaml")).isRegularFile();
        assertThat(read(resultJson))
                .contains("\"status\": \"passed\"")
                .contains("\"provider_type\": \"soap_mock\"")
                .contains("\"provider_type\": \"rest_client\"")
                .contains("\"response.status\": 200")
                .contains("\"matched_count\": 1");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: SOAP-MOCK-SAMPLE-v0.2")
                .contains("status: passed")
                .contains("provider_results_count: 2")
                .contains("provider_type: soap_mock")
                .contains("masking_status: passed");
    }

    @Test
    void failurePathProducesFailedResultAndAssertionEvidence() {
        CommandResult run = execute("run", "--suite", FAILURE_SUITE.toString(), "--profile", "local_soap_mock");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: soap_mock,rest_client");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"payment_soap_request_count\"");
        assertThat(evidenceDir.resolve("assertions/payment_soap_request_count.yaml")).isRegularFile();

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready_with_failures")
                .contains("failed_verify_summary:");
    }

    @Test
    void boundaryPathAllowsMinimalSoapEnvelopeWithoutSoapAction() {
        CommandResult run = execute("run", "--suite", BOUNDARY_SUITE.toString(), "--profile", "local_soap_mock");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"passed\"")
                .contains("\"response.status\": 200")
                .contains("\"matched_count\": 1");
        assertThat(read(evidenceDir.resolve("provider-evidence/http/response.json")))
                .contains("PingResponse")
                .contains("\"status\": 200");
    }

    @Test
    void reportFailsWhenSoapMockRequestJournalEvidenceIsMissingFromIndex() throws Exception {
        Path suite = mutableSoapSample("missing_soap_journal_index_entry");
        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_soap_mock");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceIndex = extractPath(run.stdout(), "evidence_dir").resolve("evidence_index.yaml");
        Files.writeString(evidenceIndex, removeEvidenceEntry(read(evidenceIndex),
                "file_path: provider-evidence/soap/request_journal.json"));

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_required_evidence")
                .contains("wiremock_request_journal");
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).trim())
                .map(Path::of)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path line for " + key + " in:\n" + stdout));
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Path mutableSoapSample(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/provider_capability/soap_mock"), target);
        return target.resolve("suite_manifest.yaml");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private String removeEvidenceEntry(String index, String marker) {
        String[] entries = index.split("(?m)^  - evidence_id: ");
        StringBuilder rebuilt = new StringBuilder(entries[0]);
        for (int i = 1; i < entries.length; i++) {
            String entry = entries[i];
            if (!entry.contains(marker)) {
                rebuilt.append("  - evidence_id: ").append(entry);
            }
        }
        return rebuilt.toString();
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
