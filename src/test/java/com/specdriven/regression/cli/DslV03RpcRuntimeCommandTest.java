package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DslV03RpcRuntimeCommandTest {

    private static final Path SOAP_SUITE =
            Path.of("samples/20-provider-capability-p0/rpc/soap_mock/suite_manifest.yaml");
    private static final Path GRPC_SUITE =
            Path.of("samples/20-provider-capability-p0/rpc/grpc_mock/suite_manifest.yaml");

    @Test
    void runV03SoapMockRestClientExecutesRpcProviderRuntime() throws Exception {
        CommandResult validate = execute("validate", "--suite", SOAP_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("soap_mock.v0.3")
                .contains("rest_client.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult dryRun = execute("run", "--suite", SOAP_SUITE.toString(), "--profile", "local_v03", "--dry-run");
        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false");

        CommandResult run = execute("run", "--suite", SOAP_SUITE.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: SOAP-MOCK-REST-CLIENT-v0.3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: soap_mock")
                .contains("provider_type: rest_client");

        Path resultJson = extractPath(run.stdout(), "result_json");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"provider_contract\": \"soap_mock.v0.3\"")
                .contains("\"provider_contract\": \"rest_client.v0.3\"")
                .contains("\"operation\": \"load_soap_stub,soap_request_received,reset_mock\"")
                .doesNotContain("provider_instance");

        assertReportAndEvidencePass(resultJson, "SOAP-MOCK-REST-CLIENT-v0.3");
    }

    @Test
    void runV03GrpcMockGrpcClientExecutesRpcProviderRuntime() throws Exception {
        CommandResult validate = execute("validate", "--suite", GRPC_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("grpc_mock.v0.3")
                .contains("grpc_client.v0.3")
                .doesNotContain("provider_instances_used");

        CommandResult dryRun = execute("run", "--suite", GRPC_SUITE.toString(), "--profile", "local_v03", "--dry-run");
        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false");

        CommandResult run = execute("run", "--suite", GRPC_SUITE.toString(), "--profile", "local_v03");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: GRPC-MOCK-GRPC-CLIENT-v0.3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: grpc_mock")
                .contains("provider_type: grpc_client");

        Path resultJson = extractPath(run.stdout(), "result_json");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"provider_contract\": \"grpc_mock.v0.3\"")
                .contains("\"provider_contract\": \"grpc_client.v0.3\"")
                .contains("\"operation\": \"load_grpc_stub,grpc_request_received,reset_mock\"")
                .doesNotContain("provider_instance");

        assertReportAndEvidencePass(resultJson, "GRPC-MOCK-GRPC-CLIENT-v0.3");
    }

    private void assertReportAndEvidencePass(Path resultJson, String suiteId) {
        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: " + suiteId)
                .contains("status: passed")
                .contains("provider_results_count: 2")
                .contains("missing_evidence_count: 0");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout()).contains("evidence_validation_status: passed");
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, new PrintStream(stdout), new PrintStream(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> Path.of(line.substring((key + ": ").length()).trim()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path for " + key + " in:\n" + stdout));
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
