package com.specdriven.regression.assertion;

import com.specdriven.regression.oracle.OracleResolver;
import com.specdriven.regression.oracle.ResolvedOracle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class AssertionEngine {

    private final OracleResolver oracleResolver;

    public AssertionEngine() {
        this(new OracleResolver());
    }

    public AssertionEngine(OracleResolver oracleResolver) {
        this.oracleResolver = oracleResolver;
    }

    public AssertionEvaluation evaluateFileDiff(
            Path packageRoot,
            Map<String, Object> testCase,
            Path actualOutput,
            Path runDir) {
        return evaluateFileDiff(packageRoot, testCase, actualOutput, runDir, Map.of());
    }

    public AssertionEvaluation evaluateFileDiff(
            Path packageRoot,
            Map<String, Object> testCase,
            Path actualOutput,
            Path runDir,
            Map<String, Map<String, Object>> dbFixtureContracts) {
        Map<?, ?> assertion = firstAssertion(testCase);
        String assertionType = stringValue(assertion.get("type"));
        if ("json_path_equals".equals(assertionType)) {
            return evaluateJsonPathEquals(testCase, assertion, actualOutput, runDir);
        }
        if ("db_row_matches".equals(assertionType)) {
            return evaluateDbRowMatches(packageRoot, testCase, assertion, runDir, dbFixtureContracts);
        }
        String oracleReference = stringValue(assertion.get("oracle"));
        ResolvedOracle oracle = resolveOracle(packageRoot, testCase, oracleReference);
        boolean passed = sameContent(oracle.expectedPath(), actualOutput);
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String diffSummary = passed
                ? "files match"
                : "expected `%s` differs from actual `%s`".formatted(oracle.expectedRef(), actualRef);
        Path evidencePath = runDir.resolve("assertions.yaml");
        writeEvidence(evidencePath, testCase, assertionType, oracle, actualRef, status, assertionType, diffSummary);
        return new AssertionEvaluation(
                passed,
                status,
                assertionType,
                oracleReference,
                oracle.expectedRef(),
                actualRef,
                assertionType,
                diffSummary,
                evidencePath);
    }

    private AssertionEvaluation evaluateDbRowMatches(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path runDir,
            Map<String, Map<String, Object>> dbFixtureContracts) {
        String oracleReference = stringValue(assertion.get("oracle"));
        String oracleName = oracleName(oracleReference);
        Map<?, ?> oracle = oracleDefinition(testCase, oracleName);
        String provider = firstText(oracle, "provider", "fixture_provider");
        Map<String, Object> contract = dbFixtureContracts.getOrDefault(provider, Map.of());
        String queryName = firstText(oracle, "query", "query_name");
        String queryRef = firstText(oracle, "ref", "sql_ref");
        int expectedCount = intValue(firstText(oracle, "expected_count", "row_count"), 0);
        int actualCount = executeCountQuery(packageRoot, contract, queryRef);
        boolean passed = actualCount == expectedCount;
        String status = passed ? "passed" : "failed";
        String expectedRef = queryRef + " expected_count=" + expectedCount;
        String actualRef = "db:" + provider + "/" + queryName;
        String diffSummary = passed
                ? "query `%s` returned expected row_count `%s`".formatted(queryName, expectedCount)
                : "query `%s` expected row_count `%s` but was `%s`"
                        .formatted(queryName, expectedCount, actualCount);
        Path evidencePath = runDir.resolve("assertions.yaml");
        writeEvidence(
                evidencePath,
                testCase,
                "db_row_matches",
                oracleReference,
                expectedRef,
                actualRef,
                status,
                "db_row_matches",
                diffSummary);
        return new AssertionEvaluation(
                passed,
                status,
                "db_row_matches",
                oracleReference,
                expectedRef,
                actualRef,
                "db_row_matches",
                diffSummary,
                evidencePath);
    }

    private AssertionEvaluation evaluateJsonPathEquals(
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
        String path = firstText(assertion, "path", "json_path", "actual");
        String expectedValue = expectedValue(assertion);
        Object actualValue = valueAtPath(readYamlMap(actualOutput), path);
        boolean passed = expectedValue.equals(stringValue(actualValue));
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String expectedRef = "inline:" + expectedValue;
        String diffSummary = passed
                ? "json path `%s` matched `%s`".formatted(path, expectedValue)
                : "json path `%s` expected `%s` but was `%s`"
                        .formatted(path, expectedValue, stringValue(actualValue));
        Path evidencePath = runDir.resolve("assertions.yaml");
        writeEvidence(
                evidencePath,
                testCase,
                "json_path_equals",
                "inline",
                expectedRef,
                actualRef,
                status,
                "json_path_equals",
                diffSummary);
        return new AssertionEvaluation(
                passed,
                status,
                "json_path_equals",
                "inline",
                expectedRef,
                actualRef,
                "json_path_equals",
                diffSummary,
                evidencePath);
    }

    private void writeEvidence(
            Path evidencePath,
            Map<String, Object> testCase,
            String assertionType,
            ResolvedOracle oracle,
            String actualRef,
            String status,
            String decisionRule,
            String diffSummary) {
        writeEvidence(
                evidencePath,
                testCase,
                assertionType,
                oracle.oracleReference(),
                oracle.expectedRef(),
                actualRef,
                status,
                decisionRule,
                diffSummary);
    }

    private void writeEvidence(
            Path evidencePath,
            Map<String, Object> testCase,
            String assertionType,
            String oracleReference,
            String expectedRef,
            String actualRef,
            String status,
            String decisionRule,
            String diffSummary) {
        try {
            Files.createDirectories(evidencePath.getParent());
            Files.writeString(evidencePath, """
                    assertions:
                      - test_case_id: %s
                        ac_id: %s
                        type: %s
                        status: %s
                        oracle: %s
                        expected_ref: %s
                        actual_ref: %s
                        decision_rule: %s
                        diff_summary: %s
                    """.formatted(
                    stringValue(testCase.get("test_case_id")),
                    stringValue(testCase.get("ac_id")),
                    assertionType,
                    status,
                    oracleReference,
                    expectedRef,
                    actualRef,
                    decisionRule,
                    diffSummary));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write assertion evidence.", e);
        }
    }

    private ResolvedOracle resolveOracle(
            Path packageRoot,
            Map<String, Object> testCase,
            String oracleReference) {
        String oracleName = oracleName(oracleReference);
        Object oracles = testCase.get("oracles");
        if (oracles instanceof Map<?, ?> oracleMap && oracleMap.get(oracleName) instanceof Map<?, ?> oracleDefinition) {
            return oracleResolver.resolveExpectedResultArtifact(
                    packageRoot,
                    oracleName,
                    oracleReference,
                    oracleDefinition);
        }
        return new ResolvedOracle(oracleName, "", oracleReference, "", packageRoot);
    }

    private Map<?, ?> oracleDefinition(Map<String, Object> testCase, String oracleName) {
        Object oracles = testCase.get("oracles");
        if (oracles instanceof Map<?, ?> oracleMap && oracleMap.get(oracleName) instanceof Map<?, ?> definition) {
            return definition;
        }
        return Map.of();
    }

    private int executeCountQuery(
            Path packageRoot,
            Map<String, Object> contract,
            String queryRef) {
        String connectionRef = stringValue(contract.get("connection_ref"));
        if (connectionRef.isBlank()) {
            throw new IllegalArgumentException("DB row assertion requires fixture provider connection_ref.");
        }
        try (Connection connection = DriverManager.getConnection(connectionRef);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(readSqlRef(packageRoot, queryRef))) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute DB row assertion query.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read DB row assertion query.", e);
        }
    }

    private String readSqlRef(Path packageRoot, String sqlRef) throws IOException {
        Path normalizedRoot = packageRoot.toAbsolutePath().normalize();
        Path sqlPath = normalizedRoot.resolve(sqlRef).normalize();
        if (!sqlPath.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("DB row assertion sql_ref must stay under the RP package: " + sqlRef);
        }
        return Files.readString(sqlPath);
    }

    private String oracleName(String oracleReference) {
        String prefix = "${oracles.";
        if (oracleReference.startsWith(prefix) && oracleReference.endsWith("}")) {
            return oracleReference.substring(prefix.length(), oracleReference.length() - 1);
        }
        return oracleReference;
    }

    private boolean sameContent(Path expected, Path actual) {
        try {
            return Files.exists(expected) && Files.exists(actual)
                    && Files.readString(expected).equals(Files.readString(actual));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compare assertion files.", e);
        }
    }

    private Map<?, ?> firstAssertion(Map<String, Object> testCase) {
        Object assertions = testCase.get("assertions");
        if (assertions instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> assertion) {
            return assertion;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read assertion actual output.", e);
        }
    }

    private Object valueAtPath(Object root, String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        if ("$".equals(normalized)) {
            return root;
        }
        Object current = root;
        for (String segment : normalized.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && isInteger(segment)) {
                int index = Integer.parseInt(segment);
                current = index >= 0 && index < list.size() ? list.get(index) : null;
            } else {
                return "";
            }
            if (current == null) {
                return "";
            }
        }
        return current;
    }

    private String expectedValue(Map<?, ?> assertion) {
        String direct = stringValue(assertion.get("expected_value"));
        if (!direct.isBlank()) {
            return direct;
        }
        Object oracle = assertion.get("oracle");
        if (oracle instanceof Map<?, ?> inlineOracle) {
            return stringValue(inlineOracle.get("value"));
        }
        return "";
    }

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private int intValue(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
