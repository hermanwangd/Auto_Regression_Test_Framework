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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JdbcProviderCapabilityCommandTest {

    private static final Path JDBC_SUITE = Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest.yaml");
    private static final Path JDBC_EXTERNAL_ORACLE_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml");
    private static final Path JDBC_EXTERNAL_DB2_SUITE =
            Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml");

    @TempDir
    Path tempDir;

    @Test
    void jdbcSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest_external_oracle.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/suite_manifest_external_db2.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/test_case.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/test_case_oracle_crud.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/test_case_db2_crud.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/test_case_external_oracle_crud.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/test_case_external_db2_crud.yaml",
                "docs/02-architecture/contracts/provider-contracts/jdbc.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/provider_instances/oracle_like.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/provider_instances/db2_like.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/env_profiles/local_jdbc.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/env_profiles/external_jdbc_oracle_env_secret_ref.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/env_profiles/external_jdbc_db2_env_secret_ref.yaml",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/db_seed.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/db_cleanup.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/crud_insert_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/crud_update_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/crud_delete_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/oracle_crud_insert_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/oracle_crud_update_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/oracle_crud_delete_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/db2_crud_insert_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/db2_crud_update_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/fixtures/db2_crud_delete_order.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/queries/order_exists_oracle.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/queries/order_exists_db2.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/queries/crud_order_by_id_oracle.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/queries/crud_order_by_id_db2.sql",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/expected_results/db_expected.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/expected_results/crud_expected.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/expected_results/crud_deleted_expected.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/result/expected_result_shape.json",
                "samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/evidence/expected_evidence_index.yaml");

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

    @ParameterizedTest
    @CsvSource({
            "suite_manifest.yaml, local_jdbc",
            "suite_manifest_external_oracle.yaml, external_jdbc_oracle_env_secret_ref",
            "suite_manifest_external_db2.yaml, external_jdbc_db2_env_secret_ref"
    })
    void realRunRefusesLocalAndExternalLegacyDatabases(String manifest, String profile) {
        Path suite = JDBC_SUITE.resolveSibling(manifest).toAbsolutePath();
        CommandResult run = execute("run", "--root", tempDir.toString(), "--suite", suite.toString(), "--profile", profile);
        assertThat(run.exit()).as(run.stdout()).isOne();
        assertThat(run.stdout()).contains("run_status: blocked", "reason: legacy_db_execution_disabled",
                "provider_runtime_invoked: false").doesNotContain("result_json:", "evidence_dir:");
        assertThat(tempDir.resolve("target")).doesNotExist();
    }

    @Test
    void groupRefusesDatabaseBeforeTheFirstEligibleChildStarts() throws IOException {
        Path first = Path.of("samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e");
        copyDirectory(first, tempDir.resolve("first"));
        copyDirectory(JDBC_SUITE.getParent(), tempDir.resolve("second"));
        Path group = tempDir.resolve("group.yaml");
        Files.writeString(group, """
                contract_version: v0.2
                suite_id: LEGACY-DB-GROUP
                profile: local_golden
                child_suites:
                  - id: first
                    ref: first/suite_manifest.yaml
                    profile: local_golden
                  - id: second
                    ref: second/suite_manifest.yaml
                    profile: local_jdbc
                """);
        CommandResult dry = execute("run", "--root", tempDir.toString(), "--suite", group.toString(), "--dry-run");
        assertThat(dry.exit()).as(dry.stdout()).isZero();
        CommandResult run = execute("run", "--root", tempDir.toString(), "--suite", group.toString(), "--profile", "local_golden");
        assertThat(run.exit()).as(run.stdout()).isOne();
        assertThat(run.stdout()).contains("run_status: blocked", "reason: legacy_db_execution_disabled", "second")
                .doesNotContain("result_json:", "evidence_dir:");
        assertThat(tempDir.resolve("target")).doesNotExist();
    }

    @Test
    void doctorDriversReportsMissingDriversWithoutConnectingToDatabase() {
        CommandResult result = execute(
                "doctor",
                "drivers",
                "--driver-dir", tempDir.resolve("missing-drivers").toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stderr()).isBlank();
        assertThat(result.stdout())
                .contains("driver_diagnostics_status: failed")
                .contains("driver_source: cli_driver_dir")
                .contains("driver_status: missing")
                .contains("oracle_driver_loadable: false")
                .contains("db2_driver_loadable: false")
                .contains("owner_action: Restore JDBC driver jar files");
    }

    @Test
    void unknownDoctorSubcommandExitsUsageError() {
        CommandResult result = execute("doctor", "database");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.stderr())
                .contains("Unknown doctor subcommand: database")
                .contains("usage: regress doctor drivers");
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

    private Path mutableJdbc() throws IOException {
        Path target = tempDir.resolve("jdbc_" + System.nanoTime());
        copyDirectory(Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc"), target);
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

    private String read(Path path) {
        try {
            return Files.readString(path);
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
