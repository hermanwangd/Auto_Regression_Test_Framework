package com.specdriven.regression.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DatabaseFixtureProvider {

    public void setup(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<String, Map<String, Object>> fixtureContracts,
            Path runDir) {
        List<FixtureAction> actions = fixtureActions(testCase, "setup", fixtureContracts);
        if (actions.isEmpty()) {
            return;
        }
        List<ActionEvidence> actionEvidence = new ArrayList<>();
        Map<String, Integer> verificationRows = new LinkedHashMap<>();
        try {
            Files.createDirectories(runDir);
            for (FixtureAction action : actions) {
                Map<String, Object> contract = fixtureContracts.get(action.provider());
                try (Connection connection = connection(contract)) {
                    executeActionSql(packageRoot, connection, contract, "setup_actions", action);
                    actionEvidence.add(new ActionEvidence(action.provider(), action.action(), "passed"));
                    verificationRows.putAll(verificationRows(packageRoot, connection, contract));
                }
            }
            writeSetupEvidence(runDir.resolve("fixture_setup.yaml"), actionEvidence, verificationRows);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute DB fixture setup.", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute DB fixture setup.", e);
        }
    }

    public void cleanup(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<String, Map<String, Object>> fixtureContracts,
            Path runDir) {
        List<FixtureAction> actions = fixtureActions(testCase, "cleanup", fixtureContracts);
        if (actions.isEmpty()) {
            return;
        }
        List<ActionEvidence> actionEvidence = new ArrayList<>();
        try {
            Files.createDirectories(runDir);
            for (FixtureAction action : actions) {
                Map<String, Object> contract = fixtureContracts.get(action.provider());
                try (Connection connection = connection(contract)) {
                    executeActionSql(packageRoot, connection, contract, "cleanup_actions", action);
                    actionEvidence.add(new ActionEvidence(action.provider(), action.action(), "passed"));
                }
            }
            writeCleanupEvidence(runDir.resolve("cleanup.yaml"), actionEvidence);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute DB fixture cleanup.", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute DB fixture cleanup.", e);
        }
    }

    private Connection connection(Map<String, Object> contract) throws SQLException {
        String connectionRef = stringValue(contract.get("connection_ref"));
        if (connectionRef.isBlank()) {
            throw new IllegalArgumentException("DB fixture contract requires connection_ref.");
        }
        String username = firstText(contract, "username", "username_ref");
        String password = firstText(contract, "password", "password_ref");
        if (username.isBlank() && password.isBlank()) {
            return DriverManager.getConnection(connectionRef);
        }
        return DriverManager.getConnection(connectionRef, username, password);
    }

    private void executeActionSql(
            Path packageRoot,
            Connection connection,
            Map<String, Object> contract,
            String section,
            FixtureAction action) throws IOException, SQLException {
        Map<?, ?> actionConfig = nestedMap(contract, section, action.action());
        String sqlRef = stringValue(actionConfig.get("sql_ref"));
        if (sqlRef.isBlank()) {
            throw new IllegalArgumentException("DB fixture action `" + action.action() + "` requires sql_ref.");
        }
        String script = readSqlRef(packageRoot, sqlRef);
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                String trimmed = sql.trim();
                if (!trimmed.isBlank()) {
                    statement.execute(trimmed);
                }
            }
        }
    }

    private Map<String, Integer> verificationRows(Path packageRoot, Connection connection, Map<String, Object> contract)
            throws SQLException, IOException {
        Object queries = contract.get("verification_queries");
        if (!(queries instanceof Map<?, ?> queryMap) || queryMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> rows = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement()) {
            for (Map.Entry<?, ?> entry : queryMap.entrySet()) {
                Map<?, ?> query = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
                String sqlRef = stringValue(query.get("sql_ref"));
                String sql = sqlRef.isBlank() ? stringValue(query.get("sql")) : readSqlRef(packageRoot, sqlRef);
                if (sql.isBlank()) {
                    continue;
                }
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    int rowCount = 0;
                    if (resultSet.next()) {
                        rowCount = resultSet.getInt(1);
                    }
                    rows.put(stringValue(entry.getKey()), rowCount);
                }
            }
        }
        return rows;
    }

    private String readSqlRef(Path packageRoot, String sqlRef) throws IOException {
        Path normalizedRoot = packageRoot.toAbsolutePath().normalize();
        Path sqlPath = normalizedRoot.resolve(sqlRef).normalize();
        if (!sqlPath.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("DB fixture sql_ref must stay under the RP package: " + sqlRef);
        }
        return Files.readString(sqlPath);
    }

    private List<FixtureAction> fixtureActions(
            Map<String, Object> testCase,
            String phase,
            Map<String, Map<String, Object>> fixtureContracts) {
        Object fixture = testCase.get("fixture");
        if (!(fixture instanceof Map<?, ?> fixtureMap)) {
            return List.of();
        }
        Object entries = fixtureMap.get(phase);
        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<FixtureAction> actions = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> actionMap) {
                String provider = stringValue(actionMap.get("provider"));
                String action = stringValue(actionMap.get("action"));
                if (!provider.isBlank() && !action.isBlank() && fixtureContracts.containsKey(provider)) {
                    actions.add(new FixtureAction(provider, action));
                }
            }
        }
        return List.copyOf(actions);
    }

    private void writeSetupEvidence(
            Path setupEvidence,
            List<ActionEvidence> actionEvidence,
            Map<String, Integer> verificationRows) throws IOException {
        StringBuilder builder = new StringBuilder("status: passed\nsetup_actions:\n");
        appendActions(builder, actionEvidence);
        if (!verificationRows.isEmpty()) {
            builder.append("verification_queries:\n");
            for (Map.Entry<String, Integer> entry : verificationRows.entrySet()) {
                builder.append("  - name: ").append(entry.getKey()).append("\n");
                builder.append("    row_count: ").append(entry.getValue()).append("\n");
            }
        }
        Files.writeString(setupEvidence, builder.toString());
    }

    private void writeCleanupEvidence(Path cleanupEvidence, List<ActionEvidence> actionEvidence) throws IOException {
        StringBuilder builder = new StringBuilder("status: passed\ncleanup_actions:\n");
        appendActions(builder, actionEvidence);
        Files.writeString(cleanupEvidence, builder.toString());
    }

    private void appendActions(StringBuilder builder, List<ActionEvidence> actionEvidence) {
        for (ActionEvidence action : actionEvidence) {
            builder.append("  - provider: ").append(action.provider()).append("\n");
            builder.append("    action: ").append(action.action()).append("\n");
            builder.append("    status: ").append(action.status()).append("\n");
        }
    }

    private Map<?, ?> nestedMap(Map<String, Object> root, String section, String name) {
        Object sectionValue = root.get(section);
        if (sectionValue instanceof Map<?, ?> sectionMap && sectionMap.get(name) instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private String firstText(Map<String, Object> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record FixtureAction(String provider, String action) {
    }

    private record ActionEvidence(String provider, String action, String status) {
    }
}
