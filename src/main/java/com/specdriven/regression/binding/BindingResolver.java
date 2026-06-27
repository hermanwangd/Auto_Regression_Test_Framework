package com.specdriven.regression.binding;

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
public class BindingResolver {

    private static final List<String> SUPPORTED_M1_BINDINGS =
            List.of("input_file", "dataset", "db_seed", "api_payload", "message_event");

    public BindingResolutionReport resolve(Path testCasePath) {
        Map<String, Object> testCase = readYamlMap(testCasePath);
        String testCaseId = stringValue(testCase.get("test_case_id"));
        String acId = stringValue(testCase.get("ac_id"));
        List<ResolvedBinding> resolvedBindings = new ArrayList<>();
        List<BindingGap> gaps = new ArrayList<>();

        Object packageInputs = testCase.get("package_inputs");
        if (packageInputs instanceof Map<?, ?> packageInputsMap
                && packageInputsMap.get("inputs") instanceof Map<?, ?> inputs) {
            for (Map.Entry<?, ?> entry : inputs.entrySet()) {
                String bindingName = entry.getKey().toString();
                if (entry.getValue() instanceof Map<?, ?> binding) {
                    String bindingType = stringValue(binding.get("bind_as"));
                    String ref = stringValue(binding.get("ref"));
                    if (bindingType.isBlank()) {
                        gaps.add(gap(testCaseId, acId, "package_inputs.inputs." + bindingName + ".bind_as",
                                bindingName, "", "Declare binding type for package input `" + bindingName + "`."));
                    } else if (!SUPPORTED_M1_BINDINGS.contains(bindingType)) {
                        gaps.add(gap(testCaseId, acId, "package_inputs.inputs." + bindingName + ".bind_as",
                                bindingName, bindingType,
                                "Use supported M1 binding type input_file, dataset, db_seed, api_payload, or message_event; or implement provider support."));
                    } else {
                        resolvedBindings.add(new ResolvedBinding(bindingName, bindingType, ref));
                    }
                }
            }
        }

        if (testCase.containsKey("parameters")) {
            gaps.add(gap(testCaseId, acId, "parameters", "", "",
                    "Parameter expansion is not implemented yet; remove parameters or implement explicit case expansion before execution."));
        }

        if (usesExpectedResultArtifact(testCase) && missingExpectedRef(testCase)) {
            gaps.add(gap(testCaseId, acId, "expected.ref", "", "",
                    "Reference approved expected-result artifact when oracle type is expected_result_artifact."));
        }

        return new BindingResolutionReport(
                gaps.isEmpty(),
                testCaseId,
                acId,
                List.copyOf(resolvedBindings),
                List.copyOf(gaps));
    }

    private boolean usesExpectedResultArtifact(Map<String, Object> testCase) {
        Object oracles = testCase.get("oracles");
        if (!(oracles instanceof Map<?, ?> oracleMap)) {
            return false;
        }
        for (Object oracleValue : oracleMap.values()) {
            if (oracleValue instanceof Map<?, ?> oracle && "expected_result_artifact".equals(oracle.get("type"))) {
                return true;
            }
        }
        return false;
    }

    private boolean missingExpectedRef(Map<String, Object> testCase) {
        Object expected = testCase.get("expected");
        return !(expected instanceof Map<?, ?> expectedMap) || isMissing(expectedMap.get("ref"));
    }

    private BindingGap gap(
            String testCaseId,
            String acId,
            String fieldPath,
            String bindingName,
            String bindingType,
            String ownerAction) {
        return new BindingGap(testCaseId, acId, fieldPath, bindingName, bindingType, ownerAction);
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
            throw new UncheckedIOException("Failed to read DSL test case: " + path, e);
        }
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
