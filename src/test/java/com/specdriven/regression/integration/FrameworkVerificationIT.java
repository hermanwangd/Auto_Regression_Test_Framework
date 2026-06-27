package com.specdriven.regression.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameworkVerificationIT {

    private static final String SAMPLE_RP_ID = "RP-FWK-SAMPLE";

    @TempDir
    Path tempDir;

    @Test
    void sampleProductRepoFixtureRunsThroughCheckRunAndReportWithoutSitDeployment() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        RegressionCommand command = command();

        CommandResult check = execute(command, "check-rp", productRepo, "--strict-schema");
        CommandResult run = execute(command, "run", productRepo, "--env", "ci_ephemeral");
        CommandResult report = execute(command, "report", productRepo, "--batch-id", "BATCH-001");

        assertThat(check.exitCode()).isZero();
        assertThat(check.stdout()).contains("status: pass");
        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).contains("adapter_execution_started: true");
        assertThat(run.stdout()).contains("batch_id: BATCH-001");
        assertThat(report.exitCode()).isZero();
        assertThat(report.stdout()).contains("coverage_percent: 100.0");
        assertThat(Files.readString(productRepo.resolve("FRAMEWORK_VERIFICATION_FIXTURE.md")))
                .contains("not downstream Product/RP release evidence");
        assertThat(Files.readString(packageRoot.resolve("package.yaml")))
                .contains("fixture_scope: framework_verification");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("rp_id: RP-FWK-SAMPLE")
                .contains("execution_mode: ci_ephemeral")
                .contains("environment_ref: ci://framework-verification/RP-FWK-SAMPLE");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: passed")
                .contains("test_case_id: RP-FWK-SAMPLE-TC-001");
        assertThat(Files.readString(packageRoot.resolve("evidence/review/BATCH-001/coverage_report.yaml")))
                .contains("coverage_percent: 100.0")
                .contains("review_ready: true");
    }

    @Test
    void strictRpCheckReportsPackageSchemaAndMappingGapsBeforeExecution() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writePackageYaml(packageRoot, Files.readString(packageRoot.resolve("package.yaml"))
                .replace("owner: platform", "owner: "));
        writeMappingYaml(packageRoot, Files.readString(packageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("    repo: /repo/framework-sample\n", ""));

        CommandResult check = execute(command(), "check-rp", productRepo, "--strict-schema");

        assertThat(check.exitCode()).isEqualTo(1);
        assertThat(check.stdout()).contains("status: fail");
        assertThat(check.stdout()).contains("field_path: owner");
        assertThat(check.stdout()).contains("blocks: generation");
        assertThat(check.stdout()).contains("field_path: release_units[0].repo");
        assertThat(check.stdout()).contains("blocks_execution: true");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("evidence/batches"))).isFalse();
    }

    @Test
    void runBlocksBeforeAdapterExecutionWhenProviderContractIsIncomplete() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writeMappingYaml(packageRoot, Files.readString(packageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("          command: 'echo framework-sample-ok; echo framework-sample-warn >&2'\n", ""));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: false");
        assertThat(run.stdout()).contains("contract_path: provider_contracts.adapters.spring_boot_cli.command");
        assertThat(run.stdout()).contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("adapter_execution_started: false")
                .contains("failure_details: failure_details.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("provider_contracts.adapters.spring_boot_cli.command")
                .contains("Declare executable adapter command before execution.");
    }

    @Test
    void runBlocksWhenNoApprovedDslTestCaseIsCheckedIn() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        Files.delete(packageRoot.resolve("tests/approved/RP-FWK-SAMPLE-TC-001.yaml"));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: false");
        assertThat(run.stdout()).contains("field_path: tests/approved");
        assertThat(run.stdout()).contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("test_case_id: ")
                .contains("adapter_execution_started: false");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("tests/approved")
                .contains("Add approved_for_regression DSL test cases before run.");
    }

    @Test
    void failedAssertionProducesRunEvidenceAndNotReviewReadyReport() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writeMappingYaml(packageRoot, Files.readString(packageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("          command: 'echo framework-sample-ok; echo framework-sample-warn >&2'",
                        "          command: 'echo unexpected-framework-output'"));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");
        CommandResult report = execute(command(), "report", productRepo, "--batch-id", "BATCH-001");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: true");
        assertThat(run.stdout()).contains("status: failed");
        assertThat(run.stdout()).contains("run_status: failed");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: failed")
                .contains("exit_code: 0")
                .contains("assertion_status: failed")
                .contains("assertions: assertions.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/assertions.yaml")))
                .contains("status: failed")
                .contains("expected `expected/output/orders.csv` differs from actual `actual/output.txt`");
        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(report.stdout()).contains("report_status: not_review_ready");
        assertThat(report.stdout()).contains("coverage_percent: 0.0");
        assertThat(report.stdout()).contains("run_status: failed");
        assertThat(Files.readString(packageRoot.resolve("evidence/review/BATCH-001/failure_summary.yaml")))
                .contains("assertion_status: failed")
                .contains("expected_ref: expected/output/orders.csv")
                .contains("actual_ref: actual/output.txt");
    }

    private Path sampleProductRepo() throws Exception {
        Path productRepo = tempDir.resolve("sample-product-repo-" + System.nanoTime());
        copyResourceDirectory("framework-verification/sample-product-repo", productRepo);
        return productRepo;
    }

    private Path packageRoot(Path productRepo) {
        return productRepo.resolve("docs/08-release/release-packages").resolve(SAMPLE_RP_ID);
    }

    private RegressionCommand command() {
        return new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
    }

    private CommandResult execute(RegressionCommand command, String commandName, Path productRepo, String... extraArgs) {
        String[] args = new String[5 + extraArgs.length];
        args[0] = commandName;
        args[1] = "--root";
        args[2] = productRepo.toString();
        args[3] = "--rp-id";
        args[4] = SAMPLE_RP_ID;
        System.arraycopy(extraArgs, 0, args, 5, extraArgs.length);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    private void writePackageYaml(Path packageRoot, String content) throws Exception {
        Files.writeString(packageRoot.resolve("package.yaml"), content);
    }

    private void writeMappingYaml(Path packageRoot, String content) throws Exception {
        Files.writeString(packageRoot.resolve("rp_ru_mapping.yaml"), content);
    }

    private void copyResourceDirectory(String resourceName, Path target) throws Exception {
        URL resource = getClass().getClassLoader().getResource(resourceName);
        assertThat(resource).as("sample Product Repo fixture resource").isNotNull();
        Path source = Path.of(resource.toURI());
        try (Stream<Path> paths = Files.walk(source)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private PrintStream print(ByteArrayOutputStream output) {
        return new PrintStream(output);
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
