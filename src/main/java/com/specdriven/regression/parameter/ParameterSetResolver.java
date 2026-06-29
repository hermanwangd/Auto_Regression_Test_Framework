package com.specdriven.regression.parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public class ParameterSetResolver {

    private static final Pattern PARAMETER_REFERENCE =
            Pattern.compile("\\$\\{param\\.([A-Za-z0-9_-]+)\\.([A-Za-z0-9_.-]+)}");

    public ParameterSetResolution resolve(Path testCasePath, Map<String, Object> testCase) {
        Object parametersValue = testCase.get("parameters");
        if (!(parametersValue instanceof Map<?, ?> parameters)) {
            return notParameterized();
        }
        if (hasText(parameters.get("strategy"))) {
            return notParameterized();
        }

        String ref = stringValue(parameters.get("ref"));
        String bindAs = stringValue(parameters.get("bind_as"));

        List<ParameterSetGap> gaps = new ArrayList<>();
        if (ref.isBlank()) {
            gaps.add(gap("parameters.ref", "Reference a reviewed parameter set artifact."));
        }
        if (bindAs.isBlank()) {
            gaps.add(gap("parameters.bind_as", "Declare parameters.bind_as for `${param.<bind_as>.<field>}` references."));
        }
        if (!gaps.isEmpty()) {
            return new ParameterSetResolution(true, ref, bindAs, List.of(), List.copyOf(gaps));
        }

        Path parameterSetPath = resolveReference(testCasePath, ref);
        Map<String, Object> parameterSet;
        try {
            parameterSet = readYamlMap(parameterSetPath);
        } catch (IOException e) {
            return new ParameterSetResolution(true, ref, bindAs, List.of(), List.of(
                    gap("parameters.ref", "Make parameter set artifact readable at `" + ref + "`.")));
        }

        Object casesValue = parameterSet.get("cases");
        if (!(casesValue instanceof List<?> cases) || cases.isEmpty()) {
            return new ParameterSetResolution(true, ref, bindAs, List.of(), List.of(
                    gap("parameters.ref.cases", "Declare at least one reviewed parameter case.")));
        }

        Set<ParameterReference> references = parameterReferences(testCase);
        for (ParameterReference reference : references) {
            if (!bindAs.equals(reference.bindAs())) {
                gaps.add(gap("parameters.bind_as",
                        "Use declared parameter namespace `" + bindAs + "` for `${param." + reference.bindAs()
                                + "." + reference.field() + "}`."));
            }
        }

        Set<String> caseIds = new HashSet<>();
        List<ParameterCase> parameterCases = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Object caseValue = cases.get(i);
            if (!(caseValue instanceof Map<?, ?> parameterCase)) {
                gaps.add(gap("parameters.ref.cases[" + i + "]",
                        "Declare each parameter case as a map with case_id and values."));
                continue;
            }
            String caseId = stringValue(parameterCase.get("case_id"));
            if (caseId.isBlank()) {
                gaps.add(gap("parameters.ref.cases[" + i + "].case_id",
                        "Declare a stable case_id for each reviewed parameter case."));
            } else if (!caseIds.add(caseId)) {
                gaps.add(gap("parameters.ref.cases[" + i + "].case_id",
                        "Use a unique case_id for each reviewed parameter case."));
            }

            Object valuesValue = parameterCase.get("values");
            if (!(valuesValue instanceof Map<?, ?> values) || values.isEmpty()) {
                gaps.add(gap("parameters.ref.cases[" + i + "].values",
                        "Declare non-empty values for each reviewed parameter case."));
                continue;
            }

            Map<String, Object> copiedValues = copyValues(values);
            for (ParameterReference reference : references) {
                if (bindAs.equals(reference.bindAs())
                        && (!copiedValues.containsKey(reference.field())
                        || isMissing(copiedValues.get(reference.field())))) {
                    gaps.add(gap("parameters.ref.cases[" + i + "].values." + reference.field(),
                            "Declare a value for parameter reference `${param." + bindAs + "."
                                    + reference.field() + "}`."));
                }
            }
            if (!caseId.isBlank()) {
                parameterCases.add(new ParameterCase(caseId, bindAs, copiedValues));
            }
        }

        return new ParameterSetResolution(
                true,
                ref,
                bindAs,
                List.copyOf(parameterCases),
                List.copyOf(gaps));
    }

    public Object resolveReferences(Object value, String bindAs, Map<String, Object> values) {
        if (value instanceof String text) {
            Matcher matcher = PARAMETER_REFERENCE.matcher(text);
            StringBuffer resolved = new StringBuffer();
            while (matcher.find()) {
                if (!bindAs.equals(matcher.group(1)) || !values.containsKey(matcher.group(2))) {
                    continue;
                }
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(stringValue(values.get(matcher.group(2)))));
            }
            matcher.appendTail(resolved);
            return resolved.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                resolved.put(stringValue(entry.getKey()), resolveReferences(entry.getValue(), bindAs, values));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : list) {
                resolved.add(resolveReferences(item, bindAs, values));
            }
            return resolved;
        }
        return value;
    }

    private Set<ParameterReference> parameterReferences(Object value) {
        Set<ParameterReference> references = new LinkedHashSet<>();
        collectParameterReferences(value, references);
        return references;
    }

    private void collectParameterReferences(Object value, Set<ParameterReference> references) {
        if (value instanceof String text) {
            Matcher matcher = PARAMETER_REFERENCE.matcher(text);
            while (matcher.find()) {
                references.add(new ParameterReference(matcher.group(1), matcher.group(2)));
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

    private Path resolveReference(Path testCasePath, String ref) {
        Path refPath = Path.of(ref);
        if (refPath.isAbsolute()) {
            return refPath.normalize();
        }
        Path current = testCasePath.getParent();
        while (current != null) {
            Path candidate = current.resolve(ref).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return testCasePath.getParent().resolve(ref).normalize();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private Map<String, Object> copyValues(Map<?, ?> values) {
        Map<String, Object> copiedValues = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            copiedValues.put(stringValue(entry.getKey()), entry.getValue());
        }
        return copiedValues;
    }

    private ParameterSetResolution notParameterized() {
        return new ParameterSetResolution(false, "", "", List.of(), List.of());
    }

    private ParameterSetGap gap(String fieldPath, String ownerAction) {
        return new ParameterSetGap(fieldPath, ownerAction);
    }

    private boolean hasText(Object value) {
        return !stringValue(value).isBlank();
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ParameterReference(String bindAs, String field) {
    }
}
