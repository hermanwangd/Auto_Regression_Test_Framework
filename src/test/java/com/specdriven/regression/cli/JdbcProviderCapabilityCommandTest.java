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

class JdbcProviderCapabilityCommandTest {

    private static final Path JDBC_SUITE = Path.of("samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml");
    private static final Path JDBC_EXTERNAL_ORACLE_SUITE =
            Path.of("samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml");
    private static final Path JDBC_EXTERNAL_DB2_SUITE =
            Path.of("samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml");

    @TempDir
    Path tempDir;

    @Test
    void jdbcSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/20-provider-capability-p0/data/jdbc/suite_manifest.yaml",
                "samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml",
                "samples/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml",
                "samples/20-provider-capability-p0/data/jdbc/test_case.yaml",
                "samples/20-provider-capability-p0/data/jdbc/test_case_oracle_crud.yaml",
                "samples/20-provider-capability-p0/data/jdbc/test_case_db2_crud.yaml",
                "samples/20-provider-capability-p0/data/jdbc/test_case_external_oracle_crud.yaml",
                "samples/20-provider-capability-p0/data/jdbc/test_case_external_db2_crud.yaml",
                "docs/02-architecture/contracts/provider-contracts/jdbc.yaml",
                "samples/20-provider-capability-p0/data/jdbc/provider_instances/oracle_like.yaml",
                "samples/20-provider-capability-p0/data/jdbc/provider_instances/db2_like.yaml",
                "samples/20-provider-capability-p0/data/jdbc/env_profiles/local_jdbc.yaml",
                "samples/20-provider-capability-p0/data/jdbc/env_profiles/external_jdbc_oracle_env_secret_ref.yaml",
                "samples/20-provider-capability-p0/data/jdbc/env_profiles/external_jdbc_db2_env_secret_ref.yaml",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/db_seed.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/db_cleanup.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/crud_insert_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/crud_update_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/crud_delete_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/oracle_crud_insert_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/oracle_crud_update_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/oracle_crud_delete_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/db2_crud_insert_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/db2_crud_update_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/fixtures/db2_crud_delete_order.sql",
                "samples/20-provider-capability-p0/data/jdbc/queries/order_exists_oracle.sql",
                "samples/20-provider-capability-p0/data/jdbc/queries/order_exists_db2.sql",
                "samples/20-provider-capability-p0/data/jdbc/queries/crud_order_by_id_oracle.sql",
                "samples/20-provider-capability-p0/data/jdbc/queries/crud_order_by_id_db2.sql",
                "samples/20-provider-capability-p0/data/jdbc/expected_results/db_expected.json",
                "samples/20-provider-capability-p0/data/jdbc/expected_results/crud_expected.json",
                "samples/20-provider-capability-p0/data/jdbc/expected_results/crud_deleted_expected.json",
                "samples/20-provider-capability-p0/data/jdbc/result/expected_result_shape.json",
                "samples/20-provider-capability-p0/data/jdbc/evidence/expected_evidence_index.yaml");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void jdbcSuiteValidatesThroughPublicCli() {
        CommandResult result = execute("validate", "--suite", JDBC_SUITE.toString());

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("validation_status: passed")
                .contains("suite_id: JDBC-CAPABILITY-v0.2")
                .contains("oracle-like-db")
                .contains("db2-like-db")
                .contains("jdbc");
    }

    @Test
    void jdbcExternalSuitesValidateThroughPublicCli() {
        CommandResult oracle = execute(
                "validate",
                "--suite", JDBC_EXTERNAL_ORACLE_SUITE.toString(),
                "--profile", "external_jdbc_oracle_env_secret_ref");
        CommandResult db2 = execute(
                "validate",
                "--suite", JDBC_EXTERNAL_DB2_SUITE.toString(),
                "--profile", "external_jdbc_db2_env_secret_ref");

        assertThat(oracle.exit()).as(oracle.stderr() + oracle.stdout()).isZero();
        assertThat(oracle.stdout())
                .contains("validation_status: passed")
                .contains("oracle-like-db")
                .doesNotContain("db2-like-db")
                .contains("jdbc");
        assertThat(db2.exit()).as(db2.stderr() + db2.stdout()).isZero();
        assertThat(db2.stdout())
                .contains("validation_status: passed")
                .contains("db2-like-db")
                .doesNotContain("oracle-like-db")
                .contains("jdbc");
    }

    @Test
    void jdbcSuiteRunsProviderRuntimeAndReportConsumesGeneratedResult() {
        CommandResult run = execute("run", "--suite", JDBC_SUITE.toString(), "--profile", "local_jdbc");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("run_status: passed")
                .contains("test_count: 3")
                .contains("provider_runtime_executed: true")
                .contains("provider_type: jdbc")
                .contains("provider_id: oracle-like-db")
                .contains("runtime_mode: ephemeral")
                .contains("dialect: oracle")
                .contains("evidence_classification: framework_provider_capability_only");

        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(resultJson).isRegularFile();
        assertThat(evidenceDir).isDirectory();
        assertEvidenceFilesExist(evidenceDir);

        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"suite_id\": \"JDBC-CAPABILITY-v0.2\"")
                .contains("\"test_case_id\": \"JDBC-CAPABILITY-v0.2-MULTI\"")
                .contains("\"test_count\": 3")
                .contains("\"provider_type\": \"jdbc\"")
                .contains("\"provider_id\": \"oracle-like-db\"")
                .contains("\"provider_id\": \"db2-like-db\"")
                .contains("\"runtime_mode\": \"ephemeral\"")
                .contains("\"dialect\": \"oracle\"")
                .contains("\"dialect\": \"db2\"")
                .contains("\"status\": \"passed\"")
                .contains("\"test_case_id\": \"JDBC-ORACLE-CRUD-TC-001\"")
                .contains("\"test_case_id\": \"JDBC-DB2-CRUD-TC-001\"")
                .contains("ORD-CRUD-001")
                .contains("\"seed_evidence_ref\"")
                .contains("\"query_evidence_ref\"")
                .contains("\"cleanup_evidence_ref\"")
                .contains("\"release_evidence_eligible\": false")
                .doesNotContain("plain-text-secret")
                .doesNotContain("jdbc:h2:");
        assertThat(read(evidenceDir.resolve("logs/execution.log")))
                .contains("profile: local_jdbc")
                .contains("runtime_mode: ephemeral");
        assertThat(read(evidenceDir.resolve("batch/batch.yaml")))
                .contains("profile: local_jdbc")
                .contains("runtime_mode: ephemeral");

        CommandResult report = execute("report", "--result", resultJson.toString());
        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("suite_id: JDBC-CAPABILITY-v0.2")
                .contains("batch_id: BATCH-JDBC-")
                .contains("run_id: RUN-JDBC-")
                .contains("test_case_id: JDBC-CAPABILITY-v0.2-MULTI")
                .contains("status: passed")
                .contains("test_count: 3")
                .contains("provider_results_count: 3")
                .contains("provider_id: oracle-like-db")
                .contains("provider_id: db2-like-db");
    }

    @Test
    void jdbcSuiteFailsOwnerActionablyWhenExternalEnvSecretRefIsMissing() {
        CommandResult run = execute(
                "run",
                "--suite", JDBC_EXTERNAL_ORACLE_SUITE.toString(),
                "--profile", "external_jdbc_oracle_env_secret_ref");

        assertThat(run.exit()).isEqualTo(1);
        assertThat(run.stdout())
                .contains("run_status: failed")
                .contains("SECRET_RESOLUTION_ERROR")
                .contains("JDBC connection.secret_ref env ref `env://JDBC_CONNECTION` is not set")
                .contains("Set environment variable `JDBC_CONNECTION`")
                .doesNotContain("jdbc:h2:");
    }

    @Test
    void jdbcSuiteRunsAllTestsWithSharedProfile() throws Exception {
        Path suite = mutableJdbc();
        Path secondTestCase = suite.getParent().resolve("second_test_case.yaml");
        Files.copy(suite.getParent().resolve("test_case.yaml"), secondTestCase);
        Files.writeString(secondTestCase, read(secondTestCase)
                .replace("JDBC-CAPABILITY-TC-001", "JDBC-CAPABILITY-TC-002"));
        Files.writeString(suite, read(suite)
                .replace("  - test_case.yaml", "  - test_case.yaml\n  - second_test_case.yaml"));

        CommandResult run = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(run.exit()).as(run.stderr() + run.stdout()).isZero();
        assertThat(run.stdout())
                .contains("test_count: 4")
                .contains("profile: local_jdbc");
        Path resultJson = extractPath(run.stdout(), "result_json");
        String resultText = read(resultJson);
        assertThat(resultText)
                .contains("\"test_case_id\": \"JDBC-CAPABILITY-v0.2-MULTI\"")
                .contains("\"test_count\": 4")
                .contains("\"test_case_id\": \"JDBC-CAPABILITY-TC-001\"")
                .contains("\"test_case_id\": \"JDBC-CAPABILITY-TC-002\"")
                .contains("\"test_case_id\": \"JDBC-ORACLE-CRUD-TC-001\"")
                .contains("\"test_case_id\": \"JDBC-DB2-CRUD-TC-001\"");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("evidence_index.yaml")))
                .contains("test_case_id: JDBC-CAPABILITY-TC-001")
                .contains("test_case_id: JDBC-CAPABILITY-TC-002")
                .contains("test_case_id: JDBC-ORACLE-CRUD-TC-001")
                .contains("test_case_id: JDBC-DB2-CRUD-TC-001");
    }

    @Test
    void jdbcRunRejectsUnsupportedDialectBeforeExecution() throws Exception {
        Path suite = mutableJdbc();
        Path envProfile = suite.getParent().resolve("env_profiles/local_jdbc.yaml");
        Files.writeString(envProfile, read(envProfile).replace("dialect: oracle", "dialect: mariadb"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unsupported_dialect")
                .contains("provider_type: jdbc");
        assertThat(result.stdout()).doesNotContain("provider_runtime_executed: true");
    }

    @Test
    void jdbcRunSupportsDb2DialectQueryPath() throws Exception {
        Path suite = mutableJdbc();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase)
                .replace("target: oracle_like_db", "target: db2_like_db")
                .replace("queries/order_exists_oracle.sql", "queries/order_exists_db2.sql")
                .replace("query_order_oracle", "query_order_db2"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(result.exit()).as(result.stderr() + result.stdout()).isZero();
        assertThat(result.stdout())
                .contains("provider_id: db2-like-db")
                .contains("dialect: db2");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(evidenceDir.resolve("provider-evidence/jdbc/query_JDBC-CAPABILITY-TC-001__query_order_db2.yaml")))
                .contains("dialect: db2")
                .contains("query_ref: queries/order_exists_db2.sql")
                .contains("status: passed");
    }

    @Test
    void jdbcValidateRejectsUnsupportedEnvProfileDialect() throws Exception {
        Path suite = mutableJdbc();
        Path envProfile = suite.getParent().resolve("env_profiles/local_jdbc.yaml");
        Files.writeString(envProfile, read(envProfile).replace("dialect: oracle", "dialect: mariadb"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_dialect")
                .contains("provider_type: jdbc")
                .contains("mariadb");
    }

    @Test
    void jdbcValidateRejectsRuntimeModeOutsideExecutionProfileContractOrInstance() throws Exception {
        Path suite = mutableJdbc();
        Path envProfile = suite.getParent().resolve("env_profiles/local_jdbc.yaml");
        Files.writeString(envProfile, read(envProfile).replace("runtime_mode: ephemeral", "runtime_mode: local"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_runtime_mode")
                .contains("runtime_mode")
                .contains("local");
    }

    @Test
    void jdbcValidateRejectsMissingEnvProfileDialect() throws Exception {
        Path suite = mutableJdbc();
        Path envProfile = suite.getParent().resolve("env_profiles/local_jdbc.yaml");
        Files.writeString(envProfile, read(envProfile).replace("""
              dialect: oracle
        """, ""));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_binding_key")
                .contains("field_path: providers.oracle-like-db.bindings.dialect")
                .contains("provider_type: jdbc");
    }

    @Test
    void jdbcValidateRejectsMongoProviderTypeInThisPr() throws Exception {
        Path suite = mutableJdbc();
        Path instance = suite.getParent().resolve("provider_instances/oracle_like.yaml");
        Files.writeString(instance, read(instance).replace("provider_type: jdbc", "provider_type: mongodb"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unknown_provider_type")
                .contains("provider_type: mongodb");
    }

    @Test
    void jdbcValidateRejectsUnsupportedOperationBeforeExecution() throws Exception {
        Path suite = mutableJdbc();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("operation: db_query", "operation: db_delete"));

        CommandResult result = execute("validate", "--suite", suite.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unsupported_operation")
                .contains("operation: db_delete")
                .contains("provider_type: jdbc");
    }

    @Test
    void jdbcRunRejectsMissingSecretRefBeforeExecution() throws Exception {
        Path suite = mutableJdbc();
        Path binding = suite.getParent().resolve("env_profiles/local_jdbc.yaml");
        Files.writeString(binding, read(binding)
                .replace("secret_ref: generated://provider-capability/oracle-like/connection", "value: raw-connection"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: raw_secret");
    }

    @Test
    void jdbcRunFailsOwnerActionablyWhenSqlParamIsMissing() throws Exception {
        Path suite = mutableJdbc();
        Path query = suite.getParent().resolve("queries/order_exists_oracle.sql");
        Files.writeString(query, read(query).replace(":order_id", ":missing_order_id"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("run_status: failed");
        Path resultJson = extractPath(result.stdout(), "result_json");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"DB_QUERY_FAILED\"")
                .contains("SQL_PARAM_MISSING")
                .contains("order_id");
    }

    @Test
    void jdbcRunClassifiesMissingSeedSqlRefAsExecutionFailure() throws Exception {
        Path suite = mutableJdbc();
        Path testCase = suite.getParent().resolve("test_case.yaml");
        Files.writeString(testCase, read(testCase).replace("fixtures/db_seed.sql", "fixtures/missing_seed.sql"));

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("run_status: blocked")
                .contains("reason: unresolved_artifact_ref")
                .contains("data.order_seed.ref")
                .contains("missing_seed.sql");
        assertThat(result.stdout()).doesNotContain("result_json:");
    }

    @Test
    void jdbcRunCapturesCleanupFailureWithoutHidingRecordFailure() throws Exception {
        Path suite = mutableJdbc();
        Path expected = suite.getParent().resolve("expected_results/db_expected.json");
        Files.writeString(expected, read(expected).replace("\"min_rows\": 1", "\"min_rows\": 2"));
        Path cleanup = suite.getParent().resolve("fixtures/db_cleanup.sql");
        Files.writeString(cleanup, "DELETE FROM missing_table WHERE order_id = :order_id");

        CommandResult result = execute("run", "--suite", suite.toString(), "--profile", "local_jdbc");

        assertThat(result.exit()).isEqualTo(1);
        Path resultJson = extractPath(result.stdout(), "result_json");
        Path evidenceDir = extractPath(result.stdout(), "evidence_dir");
        assertThat(read(resultJson))
                .contains("\"status\": \"failed\"")
                .contains("\"classification\": \"ASSERTION_FAILED\"")
                .contains("\"cleanup_failure\"")
                .contains("DB_CLEANUP_FAILED");
        assertThat(read(evidenceDir.resolve("provider-evidence/jdbc/cleanup_JDBC-CAPABILITY-TC-001__cleanup_order.yaml")))
                .contains("status: failed")
                .contains("failure_code: DB_CLEANUP_FAILED");
    }

    @Test
    void jdbcReportRejectsMissingRequiredEvidenceRef() {
        CommandResult run = execute("run", "--suite", JDBC_SUITE.toString(), "--profile", "local_jdbc");
        Path resultJson = extractPath(run.stdout(), "result_json");
        Path evidenceDir = extractPath(run.stdout(), "evidence_dir");
        delete(evidenceDir.resolve("provider-evidence/jdbc/query_JDBC-CAPABILITY-TC-001__query_order_oracle.yaml"));

        CommandResult report = execute("report", "--result", resultJson.toString());

        assertThat(report.exit()).isEqualTo(1);
        assertThat(report.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_ref")
                .contains("provider-evidence/jdbc/query_JDBC-CAPABILITY-TC-001__query_order_oracle.yaml");
    }

    private void assertEvidenceFilesExist(Path evidenceDir) {
        List<String> evidenceFiles = List.of(
                "evidence_index.yaml",
                "provider-evidence/jdbc/seed_JDBC-CAPABILITY-TC-001__seed_order.yaml",
                "provider-evidence/jdbc/query_JDBC-CAPABILITY-TC-001__query_order_oracle.yaml",
                "provider-evidence/jdbc/cleanup_JDBC-CAPABILITY-TC-001__cleanup_order.yaml",
                "provider-evidence/jdbc/seed_JDBC-ORACLE-CRUD-TC-001__oracle_create_order.yaml",
                "provider-evidence/jdbc/query_JDBC-ORACLE-CRUD-TC-001__oracle_read_updated_order.yaml",
                "provider-evidence/jdbc/query_JDBC-ORACLE-CRUD-TC-001__oracle_deleted_order_record_absent.yaml",
                "provider-evidence/jdbc/seed_JDBC-DB2-CRUD-TC-001__db2_create_order.yaml",
                "provider-evidence/jdbc/query_JDBC-DB2-CRUD-TC-001__db2_read_updated_order.yaml",
                "provider-evidence/jdbc/query_JDBC-DB2-CRUD-TC-001__db2_deleted_order_record_absent.yaml",
                "logs/execution.log",
                "assertions/JDBC-CAPABILITY-TC-001__order_record_exists.yaml",
                "assertions/JDBC-ORACLE-CRUD-TC-001__oracle_deleted_order_record_absent.yaml",
                "assertions/JDBC-DB2-CRUD-TC-001__db2_deleted_order_record_absent.yaml",
                "batch/batch.yaml");

        assertThat(evidenceFiles).allSatisfy(path -> assertThat(evidenceDir.resolve(path))
                .as(path)
                .isRegularFile());
    }

    private Path mutableJdbc() throws IOException {
        Path target = tempDir.resolve("jdbc_" + System.nanoTime());
        copyDirectory(Path.of("samples/20-provider-capability-p0/data/jdbc"), target);
        return target.resolve("suite_manifest.yaml");
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

    private void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new AssertionError(e);
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
