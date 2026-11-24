package com.specdriven.regression.provider.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class JdbcExternalEnvSecretRefTest {

    @TempDir
    Path tempDir;

    private static final String DIGEST = "a0a22c9dfa428cdce6fb3e58a40d4052335e17e5fbe42efc2f17f9ca813be62c";
    private static final String ORACLE_APPROVAL = "oracle:JDBC_CONNECTION:db_query:" + DIGEST;

    private Path approvedQuery() throws Exception {
        Path sql = tempDir.resolve("suite/queries/order_exists.sql");
        Files.createDirectories(sql.getParent());
        return Files.writeString(sql, "SELECT 1\n");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"oracle", "db2"})
    void onlyRunnerApprovalForTheExactFileBindingAndOperationReachesTheDriver(String dialect) throws Exception {
        Path sql = approvedQuery();
        String approval = dialect + ":JDBC_CONNECTION:db_query:" + DIGEST;
        var environment = new java.util.HashMap<String, String>();
        environment.put("JDBC_CONNECTION", "jdbc:" + dialect + ":fixture");
        var loads = new java.util.concurrent.atomic.AtomicInteger();
        var loader = new JdbcDriverLoader() {
            @Override public LoadResult load(String selected, JdbcDriverDiscovery.DiscoveryResult discovery) {
                loads.incrementAndGet();
                return new LoadResult(false, "TEST_DRIVER_REACHED", "Recorder reached", "Test fixture");
            }
        };
        var runtime = new JdbcProviderRuntime(environment::get, JdbcDriverDiscovery.missing(tempDir), loader);
        var context = new ProviderExecutionContext("db", "jdbc", "external", "native", tempDir.resolve("suite"),
                tempDir.resolve("run"), Map.of(), Map.of(), Map.of("dialect", dialect,
                        "connection", Map.of("secret_ref", "env://JDBC_CONNECTION"),
                        "REGRESS_JDBC_APPROVED_SQL_SHA256", approval));
        for (String operation : List.of("db_query", "db_record_exists", "db_seed", "db_cleanup")) {
            String pin = approval.replace("db_query", operation);
            String binding = operation.equals("db_seed") || operation.equals("db_cleanup") ? "sql_ref" : "query_ref";
            var request = new ProviderOperationRequest(operation,
                    List.of(Map.of("bind_as", binding, "ref", "queries/order_exists.sql")), Map.of());
            int before = loads.get();
            for (String candidate : List.of("", pin.replace("JDBC_CONNECTION", "OTHER_CONNECTION"),
                    pin.replace(dialect + ":", (dialect.equals("oracle") ? "db2" : "oracle") + ":"),
                    pin.replace(":" + operation + ":", operation.equals("db_query") ? ":db_seed:" : ":db_query:"),
                    pin + ",malformed", pin.replace(DIGEST, "0".repeat(64)))) {
                environment.put("REGRESS_JDBC_APPROVED_SQL_SHA256", candidate);
                assertThat(runtime.execute(context, request).failure().code()).isEqualTo("JDBC_SQL_NOT_APPROVED");
            }
            environment.put("REGRESS_JDBC_APPROVED_SQL_SHA256", pin);
            Files.writeString(sql, "SELECT 1\r\n");
            assertThat(runtime.execute(context, request).failure().code()).isEqualTo("JDBC_SQL_NOT_APPROVED");
            assertThat(loads.get()).isEqualTo(before);
            Files.writeString(sql, "SELECT 1\n");
            assertThat(runtime.execute(context, request).failure().code()).isEqualTo("TEST_DRIVER_REACHED");
            assertThat(loads.get()).isEqualTo(before + 1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"oracle", "db2"})
    void executesOnlyTheApprovedSnapshotEvenIfTheFileChangesBeforeConnection(String dialect) throws Exception {
        Path file = approvedQuery();
        Files.writeString(file, "SELECT 1 AS OBSERVED\n");
        String url = "jdbc:" + dialect + ":local-test:" + java.util.UUID.randomUUID();
        String pin = dialect + ":JDBC_CONNECTION:db_query:09d54f1514db7d0e5727d5f104e0dd0f2595f651baf8d6b3e9aade644e2fb958";
        var env = Map.of("JDBC_CONNECTION", url, "REGRESS_JDBC_APPROVED_SQL_SHA256", pin);
        var loader = new JdbcDriverLoader() {
            @Override public LoadResult load(String selected, JdbcDriverDiscovery.DiscoveryResult discovery) {
                try {
                    Files.writeString(file, "SELECT 2 AS OBSERVED\n");
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
                return new LoadResult(true, "", "", "");
            }
        };
        var driver = new java.sql.Driver() {
            public boolean acceptsURL(String requested) { return url.equals(requested); }
            public java.sql.Connection connect(String requested, java.util.Properties properties) throws java.sql.SQLException {
                if (!acceptsURL(requested)) return null;
                var localCredentials = new java.util.Properties();
                localCredentials.setProperty("user", "snapshot_fixture");
                localCredentials.setProperty("password", java.util.UUID.randomUUID().toString());
                return java.sql.DriverManager.getConnection("jdbc:h2:mem:", localCredentials);
            }
            public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) { return new java.sql.DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        };
        java.sql.DriverManager.registerDriver(driver);
        try {
            var runtime = new JdbcProviderRuntime(env::get, JdbcDriverDiscovery.missing(tempDir), loader);
            var context = new ProviderExecutionContext("db", "jdbc", "external", "native", tempDir.resolve("suite"),
                    tempDir.resolve("run"), Map.of(), Map.of(), Map.of("dialect", dialect,
                            "connection", Map.of("secret_ref", "env://JDBC_CONNECTION")));
            var request = new ProviderOperationRequest("db_query",
                    List.of(Map.of("bind_as", "query_ref", "ref", "queries/order_exists.sql")), Map.of());
            var result = runtime.execute(context, request);
            assertThat(result.passed()).isTrue();
            assertThat(result.outputs().get("sample_rows")).isEqualTo(List.of(Map.of("OBSERVED", 1)));
            assertThat(runtime.execute(context, request).failure().code()).isEqualTo("JDBC_SQL_NOT_APPROVED");
        } finally {
            java.sql.DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void quotedSeparatorsAndParameterNamesRemainData() throws Exception {
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        String script = "CREATE TABLE NOTES (NOTE VARCHAR(100)); "
                + "INSERT INTO NOTES VALUES ('first;:literal'); -- :comment\n"
                + "INSERT INTO NOTES VALUES (:value);";
        Files.writeString(suite.resolve("setup.sql"), script);
        Files.writeString(suite.resolve("lookup.sql"), "SELECT NOTE FROM NOTES WHERE NOTE = :value");
        var context = nativeContext(suite, Map.of("connection", Map.of("local_ref", "approved_local_h2_oracle")));
        var runtime = new JdbcProviderRuntime();
        var seed = runtime.execute(context, new ProviderOperationRequest("db_seed", List.of(
                Map.of("bind_as", "sql_ref", "ref", "setup.sql"),
                Map.of("bind_as", "params.value", "ref", "note;:kept")), Map.of()));
        assertThat(seed.passed()).isTrue();
        var result = runtime.execute(context, new ProviderOperationRequest("db_query", List.of(
                Map.of("bind_as", "query_ref", "ref", "lookup.sql"),
                Map.of("bind_as", "params.value", "ref", "first;:literal")), Map.of()));
        assertThat(result.outputs().get("sample_rows")).isEqualTo(List.of(Map.of("NOTE", "first;:literal")));
    }

    @Test
    void countsEveryRowWhileExposingOnlyFiveSamples() throws Exception {
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Files.writeString(suite.resolve("count.sql"), "SELECT X FROM SYSTEM_RANGE(1, 9)");
        var result = new JdbcProviderRuntime().execute(nativeContext(suite,
                Map.of("connection", Map.of("local_ref", "approved_local_h2_oracle"))),
                new ProviderOperationRequest("db_query", List.of(Map.of("bind_as", "query_ref", "ref", "count.sql")), Map.of()));
        assertThat(result.passed()).isTrue();
        assertThat(result.outputs().get("row_count")).isEqualTo(9);
        assertThat((List<?>) result.outputs().get("sample_rows")).hasSize(5);
    }

    @Test
    void nativeJdbcRuntimeResolvesEnvConnectionSecretRefWithoutLeakingConnectionValue() throws Exception {
        Path suiteRoot = tempDir.resolve("suite");
        Files.createDirectories(suiteRoot.resolve("fixtures"));
        Files.writeString(suiteRoot.resolve("fixtures/db_seed.sql"), """
                create table if not exists ORDERS (
                  ORDER_ID varchar(64) primary key,
                  STATUS varchar(32)
                );
                merge into ORDERS (ORDER_ID, STATUS) key (ORDER_ID) values (:order_id, 'READY');
                """);
        String jdbcConnection = "jdbc:h2:mem:external-jdbc-env;MODE=Oracle;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true;USER=sa";
        JdbcProviderRuntime runtime = new JdbcProviderRuntime(Map.of("JDBC_CONNECTION", jdbcConnection)::get);

        ProviderOperationResult result = runtime.execute(
                nativeContext(suiteRoot, Map.of("connection", Map.of("secret_ref", "env://JDBC_CONNECTION"))),
                new ProviderOperationRequest(
                        "db_seed",
                        List.of(
                                Map.of("bind_as", "sql_ref", "ref", "fixtures/db_seed.sql"),
                                Map.of("bind_as", "params.order_id", "ref", "ORD-JDBC-ENV-001")),
                        Map.of("_operation_id", "external_seed")));

        assertThat(result.passed()).isTrue();
        assertThat(result.outputs()).containsKey("seed_evidence_ref");
        Path evidence = tempDir.resolve("run/provider-evidence/jdbc/seed_external_seed.yaml");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("runtime_mode: native")
                .contains("provider_id: oracle-like-db")
                .contains("status: passed")
                .contains("raw_secret_found: false")
                .doesNotContain(jdbcConnection)
                .doesNotContain("external-jdbc-env");
    }

    @Test
    void nativeJdbcRuntimeFailsOwnerActionablyWhenEnvConnectionSecretRefIsMissing() throws Exception {
        JdbcProviderRuntime runtime = new JdbcProviderRuntime(Map.<String, String>of()::get);

        ProviderOperationResult result = runtime.execute(
                nativeContext(tempDir.resolve("suite"), Map.of("connection", Map.of("secret_ref", "env://JDBC_CONNECTION"))),
                new ProviderOperationRequest(
                        "db_query",
                        List.of(Map.of("bind_as", "query_ref", "ref", "queries/order_exists.sql")),
                        Map.of("_operation_id", "missing_env_query")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("SECRET_RESOLUTION_ERROR");
        assertThat(result.failure().reason()).contains("env ref `env://JDBC_CONNECTION` is not set");
        assertThat(result.failure().ownerAction()).contains("Set environment variable `JDBC_CONNECTION`");
        assertThat(result.outputs()).doesNotContainKey("failure_detail_ref");
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceType()).isEqualTo("failure_detail");
            assertThat(evidence.ref()).isEqualTo("provider-evidence/jdbc/failure_missing_env_query.yaml");
        });
        Path evidence = tempDir.resolve("run/provider-evidence/jdbc/failure_missing_env_query.yaml");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("failure_code: SECRET_RESOLUTION_ERROR")
                .contains("raw_secret_found: false")
                .doesNotContain("JDBC_CONNECTION=");
    }

    @Test
    void nativeJdbcRuntimeRejectsUnsupportedSecretRefSchemeBeforeDispatch() {
        JdbcProviderRuntime runtime = new JdbcProviderRuntime();

        ProviderOperationResult result = runtime.execute(
                nativeContext(tempDir.resolve("suite"), Map.of("connection", Map.of("secret_ref", "vault://jdbc/connection"))),
                new ProviderOperationRequest(
                        "db_query",
                        List.of(Map.of("bind_as", "query_ref", "ref", "queries/order_exists.sql")),
                        Map.of("_operation_id", "unsupported_scheme_query")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("UNSUPPORTED_SECRET_REF_SCHEME");
        assertThat(result.failure().reason()).contains("Unsupported JDBC secret_ref scheme");
        assertThat(result.failure().ownerAction()).contains("Use env://");
    }

    @Test
    void nativeJdbcRuntimeRequiresExternalDriverForNonH2ConnectionBeforeOpeningConnection() throws Exception {
        approvedQuery();
        JdbcProviderRuntime runtime = new JdbcProviderRuntime(
                Map.of("JDBC_CONNECTION", "jdbc:oracle:thin:@//db.example.test:1521/ORCLPDB1", "REGRESS_JDBC_APPROVED_SQL_SHA256", ORACLE_APPROVAL)::get,
                JdbcDriverDiscovery.missing(tempDir));

        ProviderOperationResult result = runtime.execute(
                nativeContext(tempDir.resolve("suite"), Map.of("connection", Map.of("secret_ref", "env://JDBC_CONNECTION"))),
                new ProviderOperationRequest(
                        "db_query",
                        List.of(Map.of("bind_as", "query_ref", "ref", "queries/order_exists.sql")),
                        Map.of("_operation_id", "missing_driver_query")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("JDBC_DRIVER_NOT_FOUND");
        assertThat(result.failure().classification()).isEqualTo("CONFIGURATION_ERROR");
        assertThat(result.failure().reason()).contains("No JDBC driver jar is available");
        assertThat(result.failure().ownerAction()).contains("--driver-path");
    }

    @Test
    void defaultRuntimeDiscoveryUsesRegressDriverPathFromItsEnvironment() throws Exception {
        approvedQuery();
        Path invalidDriver = tempDir.resolve("invalid-oracle-driver.jar");
        Files.writeString(invalidDriver, "not a JDBC driver jar");
        JdbcProviderRuntime runtime = new JdbcProviderRuntime(Map.of(
                "JDBC_CONNECTION", "jdbc:oracle:thin:@//db.example.test:1521/ORCLPDB1",
                "REGRESS_JDBC_APPROVED_SQL_SHA256", ORACLE_APPROVAL,
                "REGRESS_DRIVER_PATH", invalidDriver.toString())::get);

        ProviderOperationResult result = runtime.execute(
                nativeContext(tempDir.resolve("suite"), Map.of("connection", Map.of("secret_ref", "env://JDBC_CONNECTION"))),
                new ProviderOperationRequest(
                        "db_query",
                        List.of(Map.of("bind_as", "query_ref", "ref", "queries/order_exists.sql")),
                        Map.of("_operation_id", "invalid_discovered_driver_query")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("JDBC_DRIVER_INVALID");
    }

    @Test
    void nativeJdbcConnectionFailureIsNotClassifiedAsSqlExecutionFailure() throws Exception {
        JdbcProviderRuntime runtime = new JdbcProviderRuntime(Map.of(
                "JDBC_CONNECTION", "jdbc:h2:mem:synthetic;SYNTHETIC_DRIVER_OPTION=fixture")::get);

        ProviderOperationResult result = runtime.execute(
                nativeContext(tempDir.resolve("suite"), Map.of("connection", Map.of("secret_ref", "env://JDBC_CONNECTION"))),
                new ProviderOperationRequest(
                        "db_query",
                        List.of(Map.of("bind_as", "query_ref", "ref", "queries/order_exists.sql")),
                        Map.of("_operation_id", "unreachable_connection_query")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("DB_CONNECTION_FAILED");
        assertThat(result.failure().classification()).isEqualTo("CONFIGURATION_ERROR");
        assertThat(result.failure().reason()).isEqualTo("JDBC driver could not open the configured connection.");
        assertThat(Files.readString(tempDir.resolve("run").resolve(result.evidence().get(0).ref())))
                .doesNotContain("SYNTHETIC_DRIVER_OPTION", "jdbc:h2:");
        assertThat(result.outputs()).doesNotContainKey("failure_detail_ref");
    }

    @Test
    void masksCredentialLabelsAndRoundTripsPublicScalarsInQueryEvidence() throws Exception {
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Files.writeString(suite.resolve("params.yaml"), "note: |-\n  ready: yes\n  second line\n");
        Files.writeString(suite.resolve("query.sql"), """
                select cast(:private_key as varchar) as "ＡＰＩ－ＫＥＹ",
                       cast(:note as varchar) as "public: note"
                """);
        var result = new JdbcProviderRuntime().execute(
                nativeContext(suite, Map.of("connection", Map.of("local_ref", "approved_local_h2_oracle"))),
                new ProviderOperationRequest("db_query", List.of(
                        Map.of("bind_as", "query_ref", "ref", "query.sql"),
                        Map.of("bind_as", "params.private_key", "ref", "synthetic-row-value"),
                        Map.of("bind_as", "params.ＴＯＫＥＮ", "ref", "synthetic-parameter-value"),
                        Map.of("bind_as", "params.note", "ref", "params.yaml#/note")),
                        Map.of("_strict_params", false)));

        assertThat(result.passed()).as(String.valueOf(result.failure())).isTrue();
        String publicNote = "ready: yes\nsecond line";
        var rows = List.of(Map.of("ＡＰＩ－ＫＥＹ", "***", "public: note", publicNote));
        assertThat(result.outputs()).containsEntry("sample_rows", rows).containsEntry("row_count", 1);
        String text = Files.readString(tempDir.resolve("run").resolve(result.evidence().get(0).ref()));
        assertThat(text).doesNotContain("synthetic-row-value", "synthetic-parameter-value");
        Map<String, Object> evidence = new Yaml().load(text);
        assertThat(evidence).containsEntry("masked_sample_result", rows)
                .containsEntry("masked_params", Map.of("private_key", "***", "ＴＯＫＥＮ", "***", "note", publicNote));
    }

    @Test
    void rejectsAnOutOfSuiteParameterRefBeforeJdbcExecution() throws Exception {
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Path otherSuiteData = Files.writeString(tempDir.resolve("other-suite.yaml"), "id: 42");
        var result = new JdbcProviderRuntime().execute(
                nativeContext(suite, Map.of("connection", Map.of("local_ref", "approved_local_h2_oracle"))),
                new ProviderOperationRequest("db_query", List.of(
                        Map.of("bind_as", "query_ref", "ref", "query.sql"),
                        Map.of("bind_as", "params.id", "ref", otherSuiteData + "#/id")), Map.of()));

        assertThat(result.failure().reason()).isEqualTo("JDBC parameter data ref could not be read as a suite data document.");
        assertThat(result.failure().ownerAction()).contains("under the suite root");
        assertThat(Files.readString(tempDir.resolve("run").resolve(result.evidence().get(0).ref())))
                .contains(result.failure().reason()).doesNotContain("other-suite.yaml");
    }

    @Test
    void sqlDiagnosticsRetainFailureClassificationWithoutDriverTextOrSqlLiterals() throws Exception {
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Files.writeString(suite.resolve("query.sql"), "select cast('synthetic-conversion-input' as integer)");
        var result = new JdbcProviderRuntime().execute(
                nativeContext(suite, Map.of("connection", Map.of("local_ref", "approved_local_h2_oracle"))),
                new ProviderOperationRequest("db_query",
                        List.of(Map.of("bind_as", "query_ref", "ref", "query.sql")), Map.of()));

        assertThat(result.failure().code()).isEqualTo("DB_QUERY_FAILED");
        assertThat(result.failure().reason()).isEqualTo("JDBC driver could not complete the SQL operation.");
        String text = Files.readString(tempDir.resolve("run").resolve(result.evidence().get(0).ref()));
        assertThat(text).doesNotContain("synthetic-conversion-input", "select cast");
        Map<String, Object> evidence = new Yaml().load(text);
        assertThat(evidence).containsEntry("status", "failed")
                .containsEntry("reason", result.failure().reason());
    }

    @Test
    void nonSqlExceptionDetailsStayOutOfFailureResultAndEvidence() throws Exception {
        Path suite = Files.createDirectory(tempDir.resolve("suite"));
        Files.writeString(suite.resolve("query.sql"), "select 1");
        var result = new JdbcProviderRuntime().execute(
                nativeContext(suite, Map.of("connection", Map.of("local_ref", "approved_local_h2_oracle"))),
                new ProviderOperationRequest("db_record_exists", List.of(
                        Map.of("bind_as", "query_ref", "ref", "query.sql"),
                        Map.of("bind_as", "expected.row_count", "ref", "synthetic-invalid-count")), Map.of()));

        assertThat(result.failure().code()).isEqualTo("OPERATION_FAILED");
        assertThat(result.failure().reason()).doesNotContain("synthetic-invalid-count");
        assertThat(Files.readString(tempDir.resolve("run").resolve(result.evidence().get(0).ref())))
                .contains("OPERATION_FAILED").doesNotContain("synthetic-invalid-count");
    }

    private ProviderExecutionContext nativeContext(Path suiteRoot, Map<String, Object> bindingValues) {
        return new ProviderExecutionContext(
                "oracle-like-db",
                "jdbc",
                "external_jdbc_oracle_env_secret_ref",
                "native",
                suiteRoot,
                tempDir.resolve("run"),
                Map.of(
                        "provider_type", "jdbc",
                        "binding_keys", Map.of("connection.secret_ref", Map.of("required", true)),
                        "operations", Map.of("db_seed", Map.of(), "db_query", Map.of())),
                Map.of("provider_id", "oracle-like-db", "provider_type", "jdbc"),
                withDefaults(bindingValues));
    }

    private Map<String, Object> withDefaults(Map<String, Object> bindingValues) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>(bindingValues);
        values.put("dialect", "oracle");
        values.put("strict_params", true);
        return values;
    }
}
