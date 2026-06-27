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
import java.util.ArrayList;
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
        List<AssertionDetail> details = new ArrayList<>();
        for (Map<?, ?> assertion : assertions(testCase)) {
            details.add(evaluateAssertion(
                    packageRoot,
                    testCase,
                    assertion,
                    actualOutput,
                    runDir,
                    dbFixtureContracts));
        }
        if (details.isEmpty()) {
            details.add(evaluateAssertion(
                    packageRoot,
                    testCase,
                    Map.of(),
                    actualOutput,
                    runDir,
                    dbFixtureContracts));
        }
        Path evidencePath = runDir.resolve("assertions.yaml");
        writeEvidence(evidencePath, testCase, details);
        return aggregate(details, evidencePath);
    }

    private AssertionDetail evaluateAssertion(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir,
            Map<String, Map<String, Object>> dbFixtureContracts) {
        String assertionType = stringValue(assertion.get("type"));
        if ("json_path_equals".equals(assertionType)) {
            return evaluateJsonPathEquals(testCase, assertion, actualOutput, runDir);
        }
        if ("json_path_absent".equals(assertionType)) {
            return evaluateJsonPathAbsent(testCase, assertion, actualOutput, runDir);
        }
        if ("numeric_tolerance".equals(assertionType)) {
            return evaluateNumericTolerance(testCase, assertion, actualOutput, runDir);
        }
        if ("response_status_equals".equals(assertionType)) {
            return evaluateResponseStatusEquals(testCase, assertion, actualOutput, runDir);
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
        return new AssertionDetail(
                passed,
                status,
                assertionType,
                oracleReference,
                oracle.expectedRef(),
                actualRef,
                assertionType,
                diffSummary);
    }

    private AssertionDetail evaluateDbRowMatches(
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
        return new AssertionDetail(
                passed,
                status,
                "db_row_matches",
                oracleReference,
                expectedRef,
                actualRef,
                "db_row_matches",
                diffSummary);
    }

    private AssertionDetail evaluateJsonPathEquals(
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
        return new AssertionDetail(
                passed,
                status,
                "json_path_equals",
                "inline",
                expectedRef,
                actualRef,
                "json_path_equals",
                diffSummary);
    }

    private AssertionDetail evaluateJsonPathAbsent(
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
        String path = firstText(assertion, "path", "json_path", "actual");
        boolean present = pathExists(readYamlMap(actualOutput), path);
        String status = present ? "failed" : "passed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String expectedRef = "absent:" + path;
        String diffSummary = present
                ? "json path `%s` was present".formatted(path)
                : "json path `%s` was absent".formatted(path);
        return new AssertionDetail(
                !present,
                status,
                "json_path_absent",
                "inline",
                expectedRef,
                actualRef,
                "json_path_absent",
                diffSummary);
    }

    private AssertionDetail evaluateNumericTolerance(
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
        String path = firstText(assertion, "path", "json_path", "actual");
        String expectedText = expectedValue(assertion);
        String toleranceText = firstText(assertion, "tolerance", "epsilon");
        Object actualValue = valueAtPath(readYamlMap(actualOutput), path);
        double expected = doubleValue(expectedText, 0);
        double tolerance = doubleValue(toleranceText, 0);
        double actual = doubleValue(stringValue(actualValue), Double.NaN);
        boolean passed = !Double.isNaN(actual) && Math.abs(actual - expected) <= tolerance;
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String expectedRef = "inline:" + expectedText + " tolerance=" + toleranceText;
        String diffSummary = passed
                ? "numeric path `%s` matched `%s` within tolerance `%s` (actual `%s`)"
                        .formatted(path, expectedText, toleranceText, stringValue(actualValue))
                : "numeric path `%s` expected `%s` within tolerance `%s` but was `%s`"
                        .formatted(path, expectedText, toleranceText, stringValue(actualValue));
        return new AssertionDetail(
                passed,
                status,
                "numeric_tolerance",
                "inline",
                expectedRef,
                actualRef,
                "numeric_tolerance",
                diffSummary);
    }

    private AssertionDetail evaluateResponseStatusEquals(
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
        String path = firstText(assertion, "path", "json_path", "actual");
        Object actualRoot = readYamlMap(actualOutput);
        String source = "";
        Object actualValue;
        if (path.isBlank()) {
            path = firstExistingPath(actualRoot,
                    "$.status_code",
                    "$.statusCode",
                    "$.http_status",
                    "$.httpStatus",
                    "$.response.status");
            if (pathExists(actualRoot, path)) {
                actualValue = valueAtPath(actualRoot, path);
                source = "path `%s`".formatted(path);
            } else {
                actualValue = requestResponseHttpStatus(runDir);
                source = "`http_status`";
            }
        } else {
            actualValue = valueAtPath(actualRoot, path);
            source = "path `%s`".formatted(path);
        }
        String expectedStatus = firstText(assertion, "expected_status", "status", "expected_value");
        boolean passed = expectedStatus.equals(stringValue(actualValue));
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String expectedRef = "inline:" + expectedStatus;
        String diffSummary = passed
                ? "response status %s matched `%s`".formatted(source, expectedStatus)
                : "response status %s expected `%s` but was `%s`"
                        .formatted(source, expectedStatus, stringValue(actualValue));
        return new AssertionDetail(
                passed,
                status,
                "response_status_equals",
                "inline",
                expectedRef,
                actualRef,
                "response_status_equals",
                diffSummary);
    }

    private AssertionEvaluation aggregate(List<AssertionDetail> details, Path evidencePath) {
        boolean passed = details.stream().allMatch(AssertionDetail::passed);
        String status = passed ? "passed" : "failed";
        if (details.size() == 1) {
            AssertionDetail detail = details.get(0);
            return new AssertionEvaluation(
                    detail.passed(),
                    detail.status(),
                    detail.assertionType(),
                    detail.oracleReference(),
                    detail.expectedRef(),
                    detail.actualRef(),
                    detail.decisionRule(),
                    detail.diffSummary(),
                    evidencePath);
        }
        return new AssertionEvaluation(
                passed,
                status,
                "multiple",
                aggregateField(details, AssertionDetailField.ORACLE),
                aggregateField(details, AssertionDetailField.EXPECTED),
                aggregateField(details, AssertionDetailField.ACTUAL),
                "all_assertions_must_pass",
                aggregateField(details, AssertionDetailField.SUMMARY),
                evidencePath);
    }

    private void writeEvidence(
            Path evidencePath,
            Map<String, Object> testCase,
            List<AssertionDetail> details) {
        try {
            Files.createDirectories(evidencePath.getParent());
            StringBuilder builder = new StringBuilder();
            builder.append("assertions:\n");
            for (AssertionDetail detail : details) {
                builder.append("  - test_case_id: ").append(stringValue(testCase.get("test_case_id"))).append("\n");
                builder.append("    ac_id: ").append(stringValue(testCase.get("ac_id"))).append("\n");
                builder.append("    type: ").append(detail.assertionType()).append("\n");
                builder.append("    status: ").append(detail.status()).append("\n");
                builder.append("    oracle: ").append(detail.oracleReference()).append("\n");
                builder.append("    expected_ref: ").append(detail.expectedRef()).append("\n");
                builder.append("    actual_ref: ").append(detail.actualRef()).append("\n");
                builder.append("    decision_rule: ").append(detail.decisionRule()).append("\n");
                builder.append("    diff_summary: ").append(detail.diffSummary()).append("\n");
            }
            Files.writeString(evidencePath, builder.toString());
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

    private List<Map<?, ?>> assertions(Map<String, Object> testCase) {
        Object assertions = testCase.get("assertions");
        if (!(assertions instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> assertionMaps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> assertion) {
                assertionMaps.add(assertion);
            }
        }
        return assertionMaps;
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

    private boolean pathExists(Object root, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        if ("$".equals(normalized)) {
            return true;
        }
        Object current = root;
        for (String segment : normalized.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(segment)) {
                    return false;
                }
                current = map.get(segment);
            } else if (current instanceof List<?> list && isInteger(segment)) {
                int index = Integer.parseInt(segment);
                if (index < 0 || index >= list.size()) {
                    return false;
                }
                current = list.get(index);
            } else {
                return false;
            }
        }
        return true;
    }

    private String firstExistingPath(Object root, String... paths) {
        for (String path : paths) {
            if (pathExists(root, path)) {
                return path;
            }
        }
        return paths.length == 0 ? "" : paths[0];
    }

    private String requestResponseHttpStatus(Path runDir) {
        Path evidencePath = runDir.resolve("request_response.yaml");
        if (!Files.exists(evidencePath)) {
            return "";
        }
        Map<String, Object> evidence = readYamlMap(evidencePath);
        return firstText(evidence, "http_status", "status_code", "response_status");
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

    private double doubleValue(String value, double fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    private String aggregateField(List<AssertionDetail> details, AssertionDetailField field) {
        StringBuilder builder = new StringBuilder();
        for (AssertionDetail detail : details) {
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append(switch (field) {
                case ORACLE -> detail.oracleReference();
                case EXPECTED -> detail.expectedRef();
                case ACTUAL -> detail.actualRef();
                case SUMMARY -> detail.diffSummary();
            });
        }
        return builder.toString();
    }

    private enum AssertionDetailField {
        ORACLE,
        EXPECTED,
        ACTUAL,
        SUMMARY
    }

    private record AssertionDetail(
            boolean passed,
            String status,
            String assertionType,
            String oracleReference,
            String expectedRef,
            String actualRef,
            String decisionRule,
            String diffSummary) {
    }
}
