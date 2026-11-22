package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DslV03JdbcRuntimeCommandTest {

    private static final Path JDBC_SUITE = Path.of("samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml");
    private static final Path CLEANUP_FAILURE_SUITE =
            Path.of("samples/80-negative/runtime/cleanup_failure_preservation/suite_manifest.yaml");

    @TempDir
    Path tempDir;

    @Test
    void runV03JdbcExecutesProviderRuntimeAndValidatesEvidence() throws Exception {
        CommandResult validate = execute("validate", "--suite", JDBC_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();
        assertThat(validate.stdout())
                .contains("validation_status: passed")
                .contains("jdbc.v0.3")
                .doesNotContain("provider_instance");

        CommandResult dryRun = execute("run", "--suite", JDBC_SUITE.toString(), "--profile", "local_v03", "--dry-run");

        assertThat(dryRun.exit()).as(dryRun.stderr() + dryRun.stdout()).isZero();
        assertThat(dryRun.stdout())
                .contains("run_status: dry_run_ready")
                .contains("provider_runtime_invoked: false");

        CommandResult run = execute("run", "--suite", JDBC_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("suite_id: JDBC-v0.3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: jdbc");

        Path resultJson = extractPath(run.stdout(), "result_json");
        assertThat(resultJson).isRegularFile();
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"dsl_version\": \"v0.3\"")
                .contains("\"provider_contract\": \"jdbc.v0.3\"")
                .contains("\"target\": \"order_db\"")
                .contains("\"operation\": \"db_seed,db_query,db_cleanup\"")
                .contains("\"row_count\": 1")
                .doesNotContain("provider_instance");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("suite_id: JDBC-v0.3")
                .contains("status: passed")
                .contains("provider_results_count: 1")
                .contains("missing_evidence_count: 0");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("provider_type: jdbc")
                .contains("provider_id: order_db");
    }

    @Test
    void runV03JdbcUsesStepIdForDistinctOperationEvidenceRefs() throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/data/jdbc"), "jdbc_two_queries");
        Path testCase = suite.getParent().resolve("test_cases/order_query_success.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("""
                              params.order_id: ORD-V03-001

                        verify:
                        """, """
                              params.order_id: ORD-V03-001
                          - id: query_order_again
                            target: order_db
                            op: db_query
                            with:
                              query_ref: artifact://queries/find_order_by_id.sql
                              params.order_id: ORD-V03-001

                        verify:
                        """));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(evidenceDir.resolve("provider-evidence/jdbc/query_query_order.yaml")).isRegularFile();
        assertThat(evidenceDir.resolve("provider-evidence/jdbc/query_query_order_again.yaml")).isRegularFile();
        String result = Files.readString(extractPath(run.stdout(), "result_json"));
        assertThat(result)
                .contains("\"query_evidence_ref\": \"provider-evidence/jdbc/query_query_order.yaml\"")
                .contains("\"query_evidence_ref\": \"provider-evidence/jdbc/query_query_order_again.yaml\"");
    }

    @Test
    void runV03JdbcFailureKeepsFailureEvidenceOutsideContractOutputs() throws Exception {
        Path suite = mutableSuite(Path.of("samples/20-provider-capability-p0/data/jdbc"), "jdbc-invalid-local-ref");
        Path profile = suite.getParent().resolve("env_profiles/local_v03.yaml");
        Files.writeString(profile, Files.readString(profile)
                .replace("approved_local_h2_oracle", "unapproved_local_connection"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("failure_code: SECRET_RESOLUTION_ERROR")
                .doesNotContain("undeclared_provider_output");
        Path resultJson = extractPath(run.stdout(), "result_json");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"code\": \"SECRET_RESOLUTION_ERROR\"")
                .contains("provider-evidence/jdbc/failure_seed_order.yaml")
                .doesNotContain("\"failure_detail_ref\"");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());
        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("SECRET_RESOLUTION_ERROR");
    }

    @Test
    void runV03JdbcPreservesPrimaryFailureWhenCleanupAlsoFails() throws Exception {
        CommandResult validate = execute("validate", "--suite", CLEANUP_FAILURE_SUITE.toString(), "--profile", "local_v03");

        assertThat(validate.exit()).as(validate.stderr() + validate.stdout()).isZero();

        CommandResult run = execute("run", "--suite", CLEANUP_FAILURE_SUITE.toString(), "--profile", "local_v03");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("failure_code: ASSERTION_FAILED")
                .contains("provider_runtime_executed: true");

        Path resultJson = extractPath(run.stdout(), "result_json");
        String result = Files.readString(resultJson);
        assertThat(result)
                .contains("\"code\": \"ASSERTION_FAILED\"")
                .contains("\"cleanup_failures\"")
                .contains("\"failure_code\": \"DB_CLEANUP_FAILED\"")
                .contains("\"cleanup_status\": \"failed\"")
                .contains("\"failure_codes\": [\"DB_CLEANUP_FAILED\"]")
                .doesNotContain("\"code\": \"DB_CLEANUP_FAILED\"");

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("status: failed")
                .contains("failed_evidence_summary:")
                .contains("ASSERTION_FAILED")
                .contains("DB_CLEANUP_FAILED");

        CommandResult evidence = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(evidence.exit()).as(evidence.stderr() + evidence.stdout()).isZero();
        assertThat(evidence.stdout())
                .contains("evidence_validation_status: passed")
                .contains("failed_evidence_count: 2")
                .contains("ASSERTION_FAILED")
                .contains("DB_CLEANUP_FAILED");
    }

    private Path mutableSuite(Path source, String name) throws IOException {
        Path target = Files.createTempDirectory(tempDir, name + "_");
        copyDirectory(source, target);
        return target.resolve("suite_manifest.yaml");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
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
