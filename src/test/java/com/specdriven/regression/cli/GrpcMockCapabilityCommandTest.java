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

class GrpcMockCapabilityCommandTest {

    private static final Path SUITE =
            Path.of("samples/provider_capability/grpc_mock/suite_manifest.yaml");
    private static final Path FAILURE_SUITE =
            Path.of("samples/provider_capability/grpc_mock/suite_manifest_failure.yaml");
    private static final Path BOUNDARY_SUITE =
            Path.of("samples/provider_capability/grpc_mock/suite_manifest_boundary.yaml");

    @TempDir
    Path tempDir;

    @Test
    void sampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/provider_capability/grpc_mock/suite_manifest.yaml",
                "samples/provider_capability/grpc_mock/test_case.yaml",
                "samples/provider_capability/grpc_mock/provider_instances/customer_grpc_mock.yaml",
                "samples/provider_capability/grpc_mock/provider_instances/customer_grpc_client.yaml",
                "samples/provider_capability/grpc_mock/env_profiles/local_grpc_mock.yaml",
                "samples/provider_capability/grpc_mock/environment_bindings/local_grpc_mock.yaml",
                "samples/provider_capability/grpc_mock/execution_profiles/local_grpc_mock.yaml",
                "samples/provider_capability/grpc_mock/proto/customer.proto",
                "samples/provider_capability/grpc_mock/proto/customer.desc.b64",
                "samples/provider_capability/grpc_mock/fixtures/get_customer_request.json",
                "samples/provider_capability/grpc_mock/fixtures/get_customer_response.json",
                "samples/provider_capability/grpc_mock/fixtures/ping_request.json",
                "samples/provider_capability/grpc_mock/fixtures/ping_response.json",
                "samples/provider_capability/grpc_mock/suite_manifest_failure.yaml",
                "samples/provider_capability/grpc_mock/test_case_failure.yaml",
                "samples/provider_capability/grpc_mock/suite_manifest_boundary.yaml",
                "samples/provider_capability/grpc_mock/test_case_boundary.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void sampleValidatesGrpcMockAndGrpcClientProviderContracts() {
        CommandResult result = execute("validate", "--suite", SUITE.toString());

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: GRPC-MOCK-SAMPLE-v0.2")
                .contains("customer-grpc-mock")
                .contains("customer-grpc-client")
                .contains("grpc_mock")
                .contains("grpc_client");
    }

    @Test
    void sampleDryRunResolvesGrpcMockAndGrpcClientTargetsWithoutRuntimeDispatch() {
        CommandResult result = execute("run", "--suite", SUITE.toString(), "--dry-run");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_runtime_invoked: false")
                .contains("run_status: dry_run_ready")
                .contains("target: customer_grpc_mock")
                .contains("provider_id: customer-grpc-mock")
                .contains("provider_type: grpc_mock")
                .contains("target: customer_grpc_client")
                .contains("provider_id: customer-grpc-client")
                .contains("provider_type: grpc_client");
    }

    @Test
    void happyPathRunsGrpcMockAndGrpcClientProvidersAndReportConsumesResult() {
        CommandResult run = execute("run", "--suite", SUITE.toString(), "--profile", "local_grpc_mock");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: grpc_mock,grpc_client")
                .contains("provider_ids: customer-grpc-mock,customer-grpc-client")
                .contains("evidence_classification: framework_provider_capability_only");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertThat(evidenceDir.resolve("provider-evidence/grpc/request_journal.json")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/grpc/server_log.txt")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/grpc-client/response.json")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/get_customer_grpc_status.yaml")).isRegularFile();
        assertThat(evidenceDir.resolve("assertions/get_customer_grpc_request_count.yaml")).isRegularFile();
        assertThat(read(resultJson))
                .contains("\"status\": \"passed\"")
                .contains("\"provider_type\": \"grpc_mock\"")
                .contains("\"provider_type\": \"grpc_client\"")
                .contains("\"response.status\": \"OK\"")
                .contains("\"matched_count\": 1");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: GRPC-MOCK-SAMPLE-v0.2")
                .contains("status: passed")
                .contains("provider_results_count: 2")
                .contains("provider_type: grpc_mock")
                .contains("masking_status: passed");
    }

    @Test
    void failurePathProducesFailedResultAndAssertionEvidence() {
        CommandResult run = execute("run", "--suite", FAILURE_SUITE.toString(), "--profile", "local_grpc_mock");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("provider_runtime_executed: true")
                .contains("provider_types: grpc_mock,grpc_client");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"get_customer_grpc_request_count\"");
        assertThat(evidenceDir.resolve("assertions/get_customer_grpc_request_count.yaml")).isRegularFile();

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready_with_failures")
                .contains("failed_verify_summary:");
    }

    @Test
    void boundaryPathAllowsMinimalUnaryPingRequest() {
        CommandResult run = execute("run", "--suite", BOUNDARY_SUITE.toString(), "--profile", "local_grpc_mock");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"passed\"")
                .contains("\"response.status\": \"OK\"")
                .contains("\"matched_count\": 1");
        assertThat(read(evidenceDir.resolve("provider-evidence/grpc-client/response.json")))
                .contains("pong")
                .contains("\"status\": \"OK\"");
    }

    @Test
    void missingClientDescriptorProducesFailedResultInsteadOfCrashing() throws Exception {
        Path suite = mutableGrpcSample("missing_client_descriptor");
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace(
                "        grpc.descriptor_ref:\n          ref: ${data.descriptor}",
                "        grpc.descriptor_ref:\n          value: proto/missing-client.desc"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_grpc_mock");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("provider_runtime_executed: true")
                .contains("result_json:");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/grpc-client/response.json")).isRegularFile();
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"code\": \"OPERATION_FAILED\"")
                .contains("gRPC unary_call failed");
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

    private Path mutableGrpcSample(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/provider_capability/grpc_mock"), target);
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
