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

    @TempDir
    Path tempDir;

    @Test
    void sampleProductRepoFixtureRunsThroughCheckRunAndReportWithoutSitDeployment() throws Exception {
        Path productRepo = tempDir.resolve("sample-product-repo");
        copyResourceDirectory("framework-verification/sample-product-repo", productRepo);
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());

        ByteArrayOutputStream checkOutput = new ByteArrayOutputStream();
        int checkExit = command.execute(new String[] {
                "check-rp", "--root", productRepo.toString(), "--rp-id", "RP-FWK-SAMPLE", "--strict-schema"},
                print(checkOutput), print(new ByteArrayOutputStream()));

        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();
        int runExit = command.execute(new String[] {
                "run", "--root", productRepo.toString(), "--rp-id", "RP-FWK-SAMPLE", "--env", "ci_ephemeral"},
                print(runOutput), print(new ByteArrayOutputStream()));

        ByteArrayOutputStream reportOutput = new ByteArrayOutputStream();
        int reportExit = command.execute(new String[] {
                "report", "--root", productRepo.toString(), "--rp-id", "RP-FWK-SAMPLE", "--batch-id", "BATCH-001"},
                print(reportOutput), print(new ByteArrayOutputStream()));

        Path packageRoot = productRepo.resolve("docs/08-release/release-packages/RP-FWK-SAMPLE");
        assertThat(checkExit).isZero();
        assertThat(checkOutput.toString()).contains("status: pass");
        assertThat(runExit).isZero();
        assertThat(runOutput.toString()).contains("adapter_execution_started: true");
        assertThat(runOutput.toString()).contains("batch_id: BATCH-001");
        assertThat(reportExit).isZero();
        assertThat(reportOutput.toString()).contains("coverage_percent: 100.0");
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
}
