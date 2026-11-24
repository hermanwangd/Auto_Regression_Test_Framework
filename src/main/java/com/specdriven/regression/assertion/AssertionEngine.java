package com.specdriven.regression.assertion;

import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.provider.SuiteAssetReader;
import com.specdriven.regression.contract.SecurityFieldNames;
import com.specdriven.regression.contract.v03.V03OutputRedactor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import com.specdriven.regression.dsl.DslTestCaseNormalizer;
import com.specdriven.regression.oracle.OracleResolver;
import com.specdriven.regression.oracle.ResolvedOracle;
import com.specdriven.regression.provider.DatabaseFixtureProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AssertionEngine {

    private final V03OutputRedactor redactor = new V03OutputRedactor();
    private final OracleResolver oracleResolver;
    private final DslTestCaseNormalizer dslTestCaseNormalizer = new DslTestCaseNormalizer();

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
        testCase = dslTestCaseNormalizer.normalize(testCase);
        if (DatabaseFixtureProvider.hasRowAssertion(testCase)) {
            throw new UnsupportedOperationException(DatabaseFixtureProvider.RETIRED);
        }
        List<AssertionDetail> details = new ArrayList<>();
        for (Map<?, ?> assertion : assertions(testCase)) {
            details.add(evaluateAssertion(
                    packageRoot,
                    testCase,
                    assertion,
                    actualOutput,
                    runDir));
        }
        if (details.isEmpty()) {
            details.add(evaluateAssertion(
                    packageRoot,
                    testCase,
                    Map.of(),
                    actualOutput,
                    runDir));
        }
        Path evidencePath = writeEvidence(runDir, testCase, details);
        return aggregate(details, evidencePath);
    }

    /** Retains the legacy Java signature for existing callers. */
    public AssertionEvaluation evaluateFileDiff(
            Path packageRoot, Map<String, Object> testCase, Path actualOutput, Path runDir,
            Map<String, Map<String, Object>> dbFixtureContracts) {
        return evaluateFileDiff(packageRoot, testCase, actualOutput, runDir);
    }

    private AssertionDetail evaluateAssertion(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
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
        if ("schema_matches".equals(assertionType)) {
            return evaluateSchemaMatches(packageRoot, testCase, assertion, actualOutput, runDir);
        }
        if ("contract_matches".equals(assertionType)) {
            return evaluateContractMatches(packageRoot, testCase, assertion, actualOutput, runDir);
        }
        String oracleReference = stringValue(assertion.get("oracle"));
        ResolvedOracle oracle = resolveOracle(packageRoot, testCase, oracleReference);
        boolean passed = sameContent(packageRoot, oracle.expectedPath(), actualOutput);
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
        expectedValue = displayValue(path, expectedValue);
        actualValue = displayValue(path, actualValue);
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
        expectedText = displayValue(path, expectedText);
        actualValue = displayValue(path, actualValue);
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
        expectedStatus = displayValue(path, expectedStatus);
        actualValue = displayValue(path, actualValue);
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

    private AssertionDetail evaluateSchemaMatches(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
        String oracleReference = stringValue(assertion.get("oracle"));
        String oracleName = oracleName(oracleReference);
        Map<?, ?> oracle = oracleDefinition(testCase, oracleName);
        String schemaRef = firstText(oracle, "ref", "schema_ref");
        Map<String, Object> schema = readYamlMap(packageRoot, schemaRef, "schema assertion");
        Object actualRoot = readYamlMap(actualOutput);
        List<Map<?, ?>> rules = ruleMaps(schema.get("fields"));
        List<String> failures = new ArrayList<>();
        for (Map<?, ?> rule : rules) {
            evaluateRule(actualRoot, rule, failures);
        }
        boolean passed = failures.isEmpty();
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String diffSummary = passed
                ? "schema `%s` matched %s rule(s)".formatted(oracleName, rules.size())
                : "schema `%s` failed: %s".formatted(oracleName, String.join("; ", failures));
        return new AssertionDetail(
                passed,
                status,
                "schema_matches",
                oracleReference,
                schemaRef,
                actualRef,
                "schema_matches",
                diffSummary);
    }

    private AssertionDetail evaluateContractMatches(
            Path packageRoot,
            Map<String, Object> testCase,
            Map<?, ?> assertion,
            Path actualOutput,
            Path runDir) {
        String oracleReference = stringValue(assertion.get("oracle"));
        String oracleName = oracleName(oracleReference);
        Map<?, ?> oracle = oracleDefinition(testCase, oracleName);
        String contractRef = firstText(oracle, "ref", "contract_ref");
        Map<String, Object> contract = readYamlMap(packageRoot, contractRef, "contract assertion");
        Object actualRoot = readYamlMap(actualOutput);
        List<Map<?, ?>> expectations = contractExpectations(contract);
        List<String> failures = new ArrayList<>();
        for (Map<?, ?> expectation : expectations) {
            evaluateRule(actualRoot, expectation, failures);
        }
        boolean passed = failures.isEmpty();
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String diffSummary = passed
                ? "contract `%s` matched %s expectation(s)".formatted(oracleName, expectations.size())
                : "contract `%s` failed: %s".formatted(oracleName, String.join("; ", failures));
        return new AssertionDetail(
                passed,
                status,
                "contract_matches",
                oracleReference,
                contractRef,
                actualRef,
                "contract_matches",
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

    private Path writeEvidence(
            Path runDir,
            Map<String, Object> testCase,
            List<AssertionDetail> details) {
        try {
            Path path = runDir.resolve("assertions.yaml");
            List<Map<String, Object>> assertions = new ArrayList<>();
            for (AssertionDetail detail : details) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("test_case_id", stringValue(testCase.get("test_case_id")));
                entry.put("ac_id", stringValue(testCase.get("ac_id")));
                entry.put("type", detail.assertionType());
                entry.put("status", detail.status());
                entry.put("oracle", detail.oracleReference());
                entry.put("expected_ref", detail.expectedRef());
                entry.put("actual_ref", detail.actualRef());
                entry.put("decision_rule", detail.decisionRule());
                entry.put("diff_summary", detail.diffSummary());
                assertions.add(entry);
            }
            ProviderDataYaml.writeEvidence(path, Map.of("assertions", assertions));
            return path;
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

    private String oracleName(String oracleReference) {
        String prefix = "${oracles.";
        if (oracleReference.startsWith(prefix) && oracleReference.endsWith("}")) {
            return oracleReference.substring(prefix.length(), oracleReference.length() - 1);
        }
        return oracleReference;
    }

    private boolean sameContent(Path root, Path expected, Path actual) {
        try {
            return Files.exists(expected) && Files.exists(actual)
                    && SuiteAssetReader.read(root, expected.toAbsolutePath().toString(), "Oracle output must stay inside the RP package.").equals(Files.readString(actual));
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
            Object loaded = ProviderDataYaml.create().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read assertion actual output.", e);
        }
    }

    private Map<String, Object> readYamlMap(Path packageRoot, String ref, String context) {
        try {
            Object document = ProviderDataYaml.create().load(SuiteAssetReader.read(packageRoot, ref,
                    context + " ref must stay under the RP package: " + ref));
            return document instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read assertion actual output.", error);
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

    private List<Map<?, ?>> contractExpectations(Map<String, Object> contract) {
        List<Map<?, ?>> expectations = ruleMaps(contract.get("expectations"));
        if (!expectations.isEmpty()) {
            return expectations;
        }
        Object interactions = contract.get("interactions");
        if (!(interactions instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> collected = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> interaction) {
                collected.addAll(ruleMaps(interaction.get("expectations")));
            }
        }
        return collected;
    }

    private List<Map<?, ?>> ruleMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> rules = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> rule) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private void evaluateRule(Object actualRoot, Map<?, ?> rule, List<String> failures) {
        String path = firstText(rule, "path", "actual");
        boolean absentExpected = booleanValue(rule.get("absent"), false);
        boolean exists = pathExists(actualRoot, path);
        if (absentExpected) {
            if (exists) {
                failures.add("path `%s` must be absent".formatted(path));
            }
            return;
        }
        boolean required = booleanValue(rule.get("required"), true);
        if (required && !exists) {
            failures.add("required path `%s` is missing".formatted(path));
            return;
        }
        if (!exists) {
            return;
        }
        Object actualValue = valueAtPath(actualRoot, path);
        String expectedType = firstText(rule, "type");
        if (!expectedType.isBlank() && !matchesType(actualValue, expectedType)) {
            failures.add("path `%s` expected type `%s` but was `%s`"
                    .formatted(path, expectedType, valueType(actualValue)));
        }
        if (rule.containsKey("equals") && !stringValue(rule.get("equals")).equals(stringValue(actualValue))) {
            failures.add("path `%s` expected `%s` but was `%s`"
                    .formatted(path, displayValue(path, rule.get("equals")), displayValue(path, actualValue)));
        }
        Object enumValue = rule.containsKey("enum") ? rule.get("enum") : rule.get("values");
        if (enumValue instanceof List<?> allowedValues
                && allowedValues.stream().map(this::stringValue).noneMatch(stringValue(actualValue)::equals)) {
            failures.add("path `%s` value `%s` is outside allowed values `%s`"
                    .formatted(path, displayValue(path, actualValue), displayValue(path, allowedValues)));
        }
    }

    private String displayValue(String path, Object value) {
        if (Arrays.stream(path.split("[./\\[\\]]+")).anyMatch(SecurityFieldNames::isCredential)) {
            return V03OutputRedactor.MASKED;
        }
        return stringValue(redactor.redactValue(value));
    }

    private boolean matchesType(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> false;
        };
    }

    private String valueType(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        return "missing";
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(text);
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
