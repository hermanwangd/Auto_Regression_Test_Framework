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

class JdbcExternalEnvSecretRefTest {

    @TempDir
    Path tempDir;

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
