package com.specdriven.regression.binding;

import com.specdriven.regression.dsl.DslTestCaseNormalizer;
import com.specdriven.regression.parameter.ParameterSetGap;
import com.specdriven.regression.parameter.ParameterSetResolution;
import com.specdriven.regression.parameter.ParameterSetResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class BindingResolver {

    private static final List<String> SUPPORTED_M1_BINDINGS =
            List.of("input_file", "dataset", "db_seed", "api_payload", "message_event");
    private static final Pattern PARAMETER_REFERENCE = Pattern.compile("\\$\\{parameters\\.([A-Za-z0-9_-]+)}");
    private final DslTestCaseNormalizer dslTestCaseNormalizer = new DslTestCaseNormalizer();
    private final ParameterSetResolver parameterSetResolver = new ParameterSetResolver();

    public BindingResolutionReport resolve(Path testCasePath) {
        Map<String, Object> rawTestCase = readYamlMap(testCasePath);
        Map<String, Object> testCase = dslTestCaseNormalizer.normalize(rawTestCase);
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
                    String bindingFieldPath = bindingFieldPath(rawTestCase, bindingName);
                    if (bindingType.isBlank()) {
                        gaps.add(gap(testCaseId, acId, bindingFieldPath,
                                bindingName, "", "Declare binding type for package input `" + bindingName + "`."));
                    } else if (!SUPPORTED_M1_BINDINGS.contains(bindingType)) {
                        gaps.add(gap(testCaseId, acId, bindingFieldPath,
                                bindingName, bindingType,
                                "Use supported M1 binding type input_file, dataset, db_seed, api_payload, or message_event; or implement provider support."));
                    } else {
                        resolvedBindings.add(new ResolvedBinding(bindingName, bindingType, ref));
                    }
                }
            }
        }

        ParameterSetResolution parameterSetResolution = parameterSetResolver.resolve(testCasePath, rawTestCase);
        if (parameterSetResolution.parameterized()) {
            gaps.addAll(parameterSetGaps(parameterSetResolution, testCaseId, acId));
        } else {
            gaps.addAll(parameterGaps(rawTestCase, testCaseId, acId));
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

    private List<BindingGap> parameterSetGaps(
            ParameterSetResolution parameterSetResolution,
            String testCaseId,
            String acId) {
        List<BindingGap> gaps = new ArrayList<>();
        for (ParameterSetGap parameterGap : parameterSetResolution.gaps()) {
            gaps.add(gap(testCaseId, acId, parameterGap.fieldPath(), "", "", parameterGap.ownerAction()));
        }
        return gaps;
    }

    private List<BindingGap> parameterGaps(Map<String, Object> rawTestCase, String testCaseId, String acId) {
        Object parametersValue = rawTestCase.get("parameters");
        if (!(parametersValue instanceof Map<?, ?> parameters)) {
            return List.of();
        }
        List<BindingGap> gaps = new ArrayList<>();
        String strategy = stringValue(parameters.get("strategy"));
        if (!"explicit_cases".equals(strategy)) {
            gaps.add(gap(testCaseId, acId, "parameters.strategy", "", strategy,
                    "Use M1-supported parameter strategy explicit_cases."));
            return gaps;
        }
        Object casesValue = parameters.get("cases");
        if (!(casesValue instanceof List<?> cases) || cases.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "parameters.cases", "", "",
                    "Declare at least one explicit parameter case."));
            return gaps;
        }
        Set<String> caseIds = new HashSet<>();
        Set<String> references = parameterReferences(rawTestCase);
        for (int i = 0; i < cases.size(); i++) {
            Object caseValue = cases.get(i);
            if (!(caseValue instanceof Map<?, ?> parameterCase)) {
                gaps.add(gap(testCaseId, acId, "parameters.cases[" + i + "]", "", "",
                        "Declare each parameter case as a map with case_id and values."));
                continue;
            }
            String caseId = stringValue(parameterCase.get("case_id"));
            if (caseId.isBlank()) {
                gaps.add(gap(testCaseId, acId, "parameters.cases[" + i + "].case_id", "", "",
                        "Declare a stable case_id for each explicit parameter case."));
            } else if (!caseIds.add(caseId)) {
                gaps.add(gap(testCaseId, acId, "parameters.cases[" + i + "].case_id", caseId, caseId,
                        "Use a unique case_id for each explicit parameter case."));
            }
            Object valuesValue = parameterCase.get("values");
            if (!(valuesValue instanceof Map<?, ?> values) || values.isEmpty()) {
                gaps.add(gap(testCaseId, acId, "parameters.cases[" + i + "].values", caseId, "",
                        "Declare non-empty values for each explicit parameter case."));
                continue;
            }
            for (String reference : references) {
                if (!values.containsKey(reference) || isMissing(values.get(reference))) {
                    gaps.add(gap(testCaseId, acId,
                            "parameters.cases[" + i + "].values." + reference,
                            caseId,
                            reference,
                            "Declare a value for parameter reference `${parameters." + reference + "}`."));
                }
            }
        }
        return gaps;
    }

    private Set<String> parameterReferences(Object value) {
        Set<String> references = new LinkedHashSet<>();
        collectParameterReferences(value, references);
        return references;
    }

    private void collectParameterReferences(Object value, Set<String> references) {
        if (value instanceof String text) {
            Matcher matcher = PARAMETER_REFERENCE.matcher(text);
            while (matcher.find()) {
                references.add(matcher.group(1));
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                collectParameterReferences(nested, references);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object nested : list) {
                collectParameterReferences(nested, references);
            }
        }
    }

    private String bindingFieldPath(Map<String, Object> rawTestCase, String bindingName) {
        Object setup = rawTestCase.get("setup");
        if (setup instanceof Map<?, ?> setupMap
                && setupMap.get("fixtures") instanceof Map<?, ?> fixtures
                && fixtures.containsKey(bindingName)) {
            return "setup.fixtures." + bindingName + ".type";
        }
        Object execute = rawTestCase.get("execute");
        if (execute instanceof List<?> executeSteps) {
            for (int i = 0; i < executeSteps.size(); i++) {
                Object step = executeSteps.get(i);
                if (step instanceof Map<?, ?> stepMap
                        && stepMap.get("with") instanceof Map<?, ?> with
                        && with.containsKey(bindingName)) {
                    return "execute[" + i + "].with." + bindingName + ".type";
                }
            }
        }
        return "package_inputs.inputs." + bindingName + ".bind_as";
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
