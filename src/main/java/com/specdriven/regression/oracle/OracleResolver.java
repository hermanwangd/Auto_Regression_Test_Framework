package com.specdriven.regression.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class OracleResolver {

    public ResolvedOracle resolveExpectedResultArtifact(
            Path packageRoot,
            String oracleName,
            String oracleReference,
            Map<?, ?> oracleDefinition) {
        String expectedResultRef = stringValue(oracleDefinition.get("ref"));
        Map<String, Object> expectedResult = readYamlMap(packageRoot.resolve(expectedResultRef));
        String expectedOutputRef = expectedOutputRef(expectedResult);
        return new ResolvedOracle(
                oracleName,
                stringValue(oracleDefinition.get("type")),
                oracleReference,
                expectedOutputRef,
                packageRoot.resolve(expectedOutputRef));
    }

    private String expectedOutputRef(Map<String, Object> expectedResult) {
        Object outputs = expectedResult.get("expected_outputs");
        if (outputs instanceof Map<?, ?> outputMap) {
            return stringValue(outputMap.get("output_ref"));
        }
        return "";
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
            throw new UncheckedIOException("Failed to read oracle artifact: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
