package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseFixtureProviderTest {

    @TempDir
    Path tempDir;

    private final DatabaseFixtureProvider provider = new DatabaseFixtureProvider();

    @Test
    void setupAndCleanupExecuteSqlRefsAndWriteFixtureEvidence() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fixture_provider_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Path packageRoot = packageRootWithSql(
                "CREATE TABLE orders (id INT PRIMARY KEY, status VARCHAR(20));"
                        + "INSERT INTO orders VALUES (1, 'READY');",
                "DELETE FROM orders",
                "SELECT COUNT(*) FROM orders");
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "username", "sa",
                "isolation_key", "test_run_id",
                "setup_actions", Map.of("seed_orders", Map.of("sql_ref", "fixtures/db/setup.sql")),
                        "cleanup_actions", Map.of("cleanup_orders", Map.of("sql_ref", "fixtures/db/cleanup.sql")),
                        "verification_queries", Map.of(
                                "scalar_query", "not-a-map",
                                "missing_sql", Map.of(),
                                "no_matching_rows", Map.of("sql", "SELECT id FROM orders WHERE id = 999"),
                                "count_orders", Map.of("sql_ref", "fixtures/db/count.sql"))));
        Map<String, Object> testCase = fixtureTestCase();
        Path runDir = tempDir.resolve("run");

        provider.setup(packageRoot, testCase, contracts, runDir);
        provider.cleanup(packageRoot, testCase, contracts, runDir);

        assertThat(Files.readString(runDir.resolve("fixture_setup.yaml")))
                .contains("status: passed")
                .contains("action: seed_orders")
                .contains("isolation_key: test_run_id")
                .contains("verification_queries:")
                .contains("name: count_orders")
                .contains("row_count: 1")
                .contains("name: no_matching_rows")
                .contains("row_count: 0")
                .doesNotContain("scalar_query")
                .doesNotContain("missing_sql");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml")))
                .contains("status: passed")
                .contains("action: cleanup_orders");
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
            result.next();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void setupReturnsWithoutEvidenceWhenFixtureSectionOrPhaseIsAbsent() {
        Path runDir = tempDir.resolve("run-no-fixtures");

        provider.setup(tempDir, Map.of(), Map.of(), runDir);
        provider.cleanup(tempDir, Map.of("fixture", Map.of("setup", java.util.List.of())), Map.of(), runDir);

        assertThat(Files.exists(runDir.resolve("fixture_setup.yaml"))).isFalse();
        assertThat(Files.exists(runDir.resolve("cleanup.yaml"))).isFalse();
    }

    @Test
    void setupReturnsWithoutEvidenceWhenSetupPhaseIsEmpty() {
        Path runDir = tempDir.resolve("run-empty-fixtures");

        provider.setup(tempDir, Map.of("fixture", Map.of("setup", List.of())), Map.of(), runDir);

        assertThat(Files.exists(runDir.resolve("fixture_setup.yaml"))).isFalse();
    }

    @Test
    void setupIgnoresIncompleteOrUnknownFixtureActions() {
        Path runDir = tempDir.resolve("run-ignored-fixtures");
        Map<String, Object> testCase = Map.of("fixture", Map.of(
                "setup", List.of(
                        "not-an-action",
                        Map.of("provider", "", "action", "seed_orders"),
                        Map.of("provider", "relational_db", "action", ""),
                        Map.of("provider", "missing_db", "action", "seed_orders"))));

        provider.setup(tempDir, testCase, Map.of("relational_db", Map.of("connection_ref", "unused")), runDir);

        assertThat(Files.exists(runDir.resolve("fixture_setup.yaml"))).isFalse();
    }

    @Test
    void setupRejectsContractWithoutConnectionRefBeforeRunningSql() throws Exception {
        Path packageRoot = packageRootWithSql("CREATE TABLE orders (id INT)", "", "");
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "setup_actions", Map.of("seed_orders", Map.of("sql_ref", "fixtures/db/setup.sql"))));

        assertThatThrownBy(() -> provider.setup(packageRoot, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB fixture contract requires connection_ref");
    }

    @Test
    void setupRejectsFixtureActionWithoutSqlRef() {
        String jdbcUrl = "jdbc:h2:mem:fixture_missing_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "setup_actions", Map.of("other_action", Map.of("sql_ref", "fixtures/db/setup.sql"))));

        assertThatThrownBy(() -> provider.setup(tempDir, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB fixture action `seed_orders` requires sql_ref");
    }

    @Test
    void setupRejectsUnsafeSqlRefOutsidePackageRoot() {
        String jdbcUrl = "jdbc:h2:mem:fixture_unsafe_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "setup_actions", Map.of("seed_orders", Map.of("sql_ref", "../outside.sql"))));

        assertThatThrownBy(() -> provider.setup(tempDir, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DB fixture sql_ref must stay under the RP package");
    }

    @Test
    void setupConnectsWithoutCredentialsWhenUsernameAndPasswordAreBlank() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fixture_no_credentials_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Path packageRoot = packageRootWithSql(
                "CREATE TABLE no_credentials (id INT PRIMARY KEY);"
                        + "INSERT INTO no_credentials VALUES (1);",
                "",
                "SELECT COUNT(*) FROM no_credentials");
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "setup_actions", Map.of("seed_orders", Map.of("sql_ref", "fixtures/db/setup.sql")),
                "verification_queries", Map.of("count_rows", Map.of("sql_ref", "fixtures/db/count.sql"))));

        provider.setup(packageRoot, fixtureTestCase(), contracts, tempDir.resolve("run"));

        assertThat(Files.readString(tempDir.resolve("run/fixture_setup.yaml")))
                .contains("name: count_rows")
                .contains("row_count: 1");
    }

    @Test
    void setupWrapsMissingSqlFileAsUncheckedIoException() {
        String jdbcUrl = "jdbc:h2:mem:fixture_missing_setup_file_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "setup_actions", Map.of("seed_orders", Map.of("sql_ref", "fixtures/db/missing.sql"))));

        assertThatThrownBy(() -> provider.setup(tempDir, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to execute DB fixture setup.");
    }

    @Test
    void setupWrapsSqlFailureAsIllegalStateException() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fixture_invalid_setup_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Path packageRoot = packageRootWithSql("SELECT * FROM table_that_does_not_exist", "", "");
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "setup_actions", Map.of("seed_orders", Map.of("sql_ref", "fixtures/db/setup.sql"))));

        assertThatThrownBy(() -> provider.setup(packageRoot, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to execute DB fixture setup.");
    }

    @Test
    void cleanupWrapsMissingSqlFileAsUncheckedIoException() {
        String jdbcUrl = "jdbc:h2:mem:fixture_missing_cleanup_file_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "cleanup_actions", Map.of("cleanup_orders", Map.of("sql_ref", "fixtures/db/missing.sql"))));

        assertThatThrownBy(() -> provider.cleanup(tempDir, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to execute DB fixture cleanup.");
    }

    @Test
    void cleanupWrapsSqlFailureAsIllegalStateException() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:fixture_invalid_cleanup_sql_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        Path packageRoot = packageRootWithSql("", "DELETE FROM table_that_does_not_exist", "");
        Map<String, Map<String, Object>> contracts = Map.of("relational_db", Map.of(
                "connection_ref", jdbcUrl,
                "cleanup_actions", Map.of("cleanup_orders", Map.of("sql_ref", "fixtures/db/cleanup.sql"))));

        assertThatThrownBy(() -> provider.cleanup(packageRoot, fixtureTestCase(), contracts, tempDir.resolve("run")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to execute DB fixture cleanup.");
    }

    private Path packageRootWithSql(String setupSql, String cleanupSql, String countSql) throws Exception {
        Path packageRoot = tempDir.resolve("package");
        Path sqlDir = packageRoot.resolve("fixtures/db");
        Files.createDirectories(sqlDir);
        Files.writeString(sqlDir.resolve("setup.sql"), setupSql);
        Files.writeString(sqlDir.resolve("cleanup.sql"), cleanupSql);
        Files.writeString(sqlDir.resolve("count.sql"), countSql);
        return packageRoot;
    }

    private Map<String, Object> fixtureTestCase() {
        return Map.of("fixture", Map.of(
                "setup", java.util.List.of(Map.of("provider", "relational_db", "action", "seed_orders")),
                "cleanup", java.util.List.of(Map.of("provider", "relational_db", "action", "cleanup_orders"))));
    }
}
