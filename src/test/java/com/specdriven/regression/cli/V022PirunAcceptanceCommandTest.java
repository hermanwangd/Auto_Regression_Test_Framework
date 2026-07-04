package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V022PirunAcceptanceCommandTest {

    private static final Path DUMMY_REST_SUITE = Path.of("pi_run_demo/dummy_rest/suite_manifest.yaml");
    private static final Path COMPARE_SUITE = Path.of("samples/provider_capability/compare/suite_manifest.yaml");
    private static final Path CONTRACT_BASELINE_RESULT =
            Path.of("samples/contract_baseline/result/sample_result.json");
    private static final Path MOCK_SERVER_CROSS_VERIFY_SUITE =
            Path.of("samples/provider_capability/mock_server_cross_verify/suite_manifest.yaml");

    @Test
    void dummyRestSuiteModeRunsCustomRestClientAndReportConsumesResult() {
        assertThat(DUMMY_REST_SUITE).isRegularFile();

        CommandResult validate = execute("validate", "--suite", DUMMY_REST_SUITE.toString(), "--profile", "local_dummy");
        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: PI-RUN-DUMMY-REST-v0.2")
                .contains("rest_client");

        CommandResult run = execute("run", "--suite", DUMMY_REST_SUITE.toString(), "--profile", "local_dummy");
        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: rest_client")
                .contains("test_count: 1")
                .contains("failed_count: 0");

        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("coverage_percent: 100.0")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed");
    }

    @Test
    void cliHelpNoCommandAndPiRunAliasAreUserFriendly() {
        CommandResult topLevelHelp = execute("--help");
        assertThat(topLevelHelp.exit()).isZero();
        assertThat(topLevelHelp.stdout())
                .contains("usage:")
                .contains("validate --suite")
                .contains("pi-run --suite")
                .doesNotContain("Unknown command");

        CommandResult noCommand = execute();
        assertThat(noCommand.exit()).isZero();
        assertThat(noCommand.stdout()).contains("usage:");
        assertThat(noCommand.stderr()).doesNotContain("Exception");

        CommandResult commandHelp = execute("run", "--help");
        assertThat(commandHelp.exit()).isZero();
        assertThat(commandHelp.stdout()).contains("usage: regress run --suite <suite_manifest>");

        CommandResult piRun = execute("pi-run", "--suite", DUMMY_REST_SUITE.toString(), "--profile", "local_dummy");
        assertThat(piRun.exit()).as(piRun.stderr() + piRun.stdout()).isZero();
        assertThat(piRun.stdout()).contains("run_status: passed");
    }

    @Test
    void obsoleteRpModeIsHardDeprecated() {
        CommandResult run = execute("run", "--root", ".", "--rp-id", "RP-DUMMY-REST-001", "--env", "local_dummy");

        assertThat(run.exit()).isNotZero();
        assertThat(run.stdout())
                .contains("run_status: blocked")
                .contains("failure_code: LEGACY_RP_MODE_DEPRECATED")
                .contains("owner_action: Use run --suite <suite_manifest> --profile <profile>.")
                .doesNotContain("run_status: passed")
                .doesNotContain("environment_gaps:")
                .doesNotContain("batch_id:")
                .doesNotContain("run_id:");
        assertThat(run.stderr()).isBlank();
    }

    @Test
    void reportResultSupportsYamlFormat() {
        CommandResult report = execute("report", "--result", CONTRACT_BASELINE_RESULT.toString(), "--format", "yaml");

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("coverage_percent: 100.0")
                .contains("missing_evidence_count: 0");
        assertThat(report.stderr()).doesNotContain("Unsupported --format");
    }

    @Test
    void wrongProfileFailsValidateDryRunAndFullRunConsistently() {
        CommandResult validate = execute(
                "validate",
                "--suite",
                MOCK_SERVER_CROSS_VERIFY_SUITE.toString(),
                "--profile",
                "local_mock_cross");
        CommandResult dryRun = execute(
                "run",
                "--suite",
                MOCK_SERVER_CROSS_VERIFY_SUITE.toString(),
                "--profile",
                "local_mock_cross",
                "--dry-run");
        CommandResult run = execute(
                "run",
                "--suite",
                MOCK_SERVER_CROSS_VERIFY_SUITE.toString(),
                "--profile",
                "local_mock_cross");

        assertThat(validate.exit()).isEqualTo(1);
        assertThat(dryRun.exit()).isEqualTo(1);
        assertThat(run.exit()).isEqualTo(1);
        assertThat(validate.stdout()).contains("profile_mismatch").contains("local_mock_server_cross_verify");
        assertThat(dryRun.stdout()).contains("profile_mismatch").contains("local_mock_server_cross_verify");
        assertThat(run.stdout()).contains("profile_mismatch").contains("local_mock_server_cross_verify");
    }

    @Test
    void compareSampleIsExecutableAndReportable() {
        CommandResult run = execute("run", "--suite", COMPARE_SUITE.toString(), "--profile", "local_compare");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("provider_type: common_verify")
                .contains("test_count: 1");
        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(Files.exists(resultJson)).isTrue();

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout()).contains("verify_results_count: 3");
    }

    private Path extractPath(String stdout, String key) {
        return stdout.lines()
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring((key + ": ").length()).trim())
                .map(Path::of)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing path line for " + key + " in:\n" + stdout));
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
