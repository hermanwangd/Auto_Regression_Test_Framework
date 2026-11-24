package com.specdriven.regression.oracle;

import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.provider.SuiteAssetReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OracleResolver {

    public ResolvedOracle resolveExpectedResultArtifact(
            Path packageRoot,
            String oracleName,
            String oracleReference,
            Map<?, ?> oracleDefinition) {
        String expectedResultRef = stringValue(oracleDefinition.get("ref"));
        Map<String, Object> expectedResult = readYamlMap(packageRoot, expectedResultRef);
        String expectedOutputRef = expectedOutputRef(expectedResult);
        if (expectedOutputRef.isBlank() && !expectedResult.containsKey("expected_outputs")) {
            expectedOutputRef = expectedResultRef;
        }
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
    private Map<String, Object> readYamlMap(Path root, String ref) {
        try {
            Object loaded = ProviderDataYaml.create().load(SuiteAssetReader.read(root, ref, "Oracle ref must stay inside the RP package."));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read oracle artifact: " + ref, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
