package com.specdriven.regression.assertion;

import com.specdriven.regression.oracle.OracleResolver;
import com.specdriven.regression.oracle.ResolvedOracle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

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
        Map<?, ?> assertion = firstAssertion(testCase);
        String assertionType = stringValue(assertion.get("type"));
        String oracleReference = stringValue(assertion.get("oracle"));
        ResolvedOracle oracle = resolveOracle(packageRoot, testCase, oracleReference);
        boolean passed = sameContent(oracle.expectedPath(), actualOutput);
        String status = passed ? "passed" : "failed";
        String actualRef = runDir.relativize(actualOutput).toString();
        String diffSummary = passed
                ? "files match"
                : "expected `%s` differs from actual `%s`".formatted(oracle.expectedRef(), actualRef);
        Path evidencePath = runDir.resolve("assertions.yaml");
        writeEvidence(evidencePath, testCase, assertionType, oracle, actualRef, status, diffSummary);
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

    private void writeEvidence(
            Path evidencePath,
            Map<String, Object> testCase,
            String assertionType,
            ResolvedOracle oracle,
            String actualRef,
            String status,
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
                    oracle.oracleReference(),
                    oracle.expectedRef(),
                    actualRef,
                    assertionType,
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
