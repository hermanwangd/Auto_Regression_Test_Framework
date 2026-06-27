package com.specdriven.regression.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class OracleReadinessService {

    private static final List<String> SUPPORTED_M1_ORACLES = List.of(
            "expected_result_artifact",
            "golden_file",
            "schema",
            "contract",
            "query_result");

    public OracleReadinessReport check(Path testCasePath) {
        Map<String, Object> testCase = readYamlMap(testCasePath);
        String testCaseId = stringValue(testCase.get("test_case_id"));
        String acId = stringValue(testCase.get("ac_id"));
        List<OracleReadinessGap> gaps = new ArrayList<>();
        Object oracles = testCase.get("oracles");
        if (oracles instanceof Map<?, ?> oracleMap) {
            for (Map.Entry<?, ?> entry : oracleMap.entrySet()) {
                String oracleName = entry.getKey().toString();
                if (entry.getValue() instanceof Map<?, ?> oracleDefinition) {
                    String oracleType = stringValue(oracleDefinition.get("type"));
                    if (!SUPPORTED_M1_ORACLES.contains(oracleType)) {
                        gaps.add(new OracleReadinessGap(
                                testCaseId,
                                acId,
                                "oracles." + oracleName + ".type",
                                oracleType,
                                "Use supported M1 oracle type expected_result_artifact or golden_file; or implement provider support."));
                    }
                }
            }
        }
        return new OracleReadinessReport(gaps.isEmpty(), List.copyOf(gaps));
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
            throw new UncheckedIOException("Failed to read oracle readiness artifact: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
