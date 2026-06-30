package com.specdriven.regression.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class GeneratedRuntimeArtifacts {

    private static final List<String> REQUIRED_RUN_PROFILE_FIELDS = List.of(
            "profile_id",
            "execution_mode",
            "environment_binding_ref",
            "isolation_scope",
            "dependency_policy",
            "max_duration",
            "data_policy");
    private static final Set<String> ALLOWED_EXECUTION_MODES = Set.of(
            "local_fixture",
            "ci_ephemeral",
            "sit_deployed",
            "evidence_only");

    public GeneratedRuntimeContext resolve(Path packageRoot, String requestedProfile) {
        String profileId = requestedProfile == null || requestedProfile.isBlank() ? "default" : requestedProfile;
        Path generatedRoot = packageRoot.resolve("generated-framework");
        Path runPlanPath = generatedRoot.resolve("run_plan.yaml");
        Path profilePath = generatedRoot.resolve("run_profiles").resolve(profileId + ".yaml");
        List<GeneratedRuntimeGap> gaps = new ArrayList<>();

        Map<String, Object> runPlan = readRequiredMap(runPlanPath, "generated-framework/run_plan.yaml", gaps);
        Map<String, Object> profile = readRequiredMap(
                profilePath,
                "generated-framework/run_profiles/" + profileId + ".yaml",
                gaps);
        validateRunProfile(profile, profileId, gaps);
        String environmentBindingRef = firstNonBlank(
                firstText(profile, "environment_binding_ref"),
                firstText(runPlan, "environment_binding_ref"),
                "environment_bindings/" + profileId + ".yaml");
        Path environmentBindingPath = generatedPath(packageRoot, environmentBindingRef);
        Map<String, Object> environmentBinding = readRequiredMap(
                environmentBindingPath,
                "generated-framework/" + environmentBindingRef,
                gaps);
        String executionMode = firstNonBlank(
                firstText(profile, "execution_mode"),
                firstText(runPlan, "execution_mode"),
                profileId);

        Map<String, List<String>> dependencyMap = targetDependencies(runPlan);
        List<GeneratedRuntimeTarget> targets = targets(environmentBinding, dependencyMap, executionMode, gaps);
        String environmentRef = targets.stream()
                .map(GeneratedRuntimeTarget::environmentRef)
                .filter(ref -> !ref.isBlank())
                .findFirst()
                .orElse("");
        return new GeneratedRuntimeContext(gaps.isEmpty(), profileId, executionMode, environmentRef,
                List.copyOf(targets), List.copyOf(gaps));
    }

    private void validateRunProfile(Map<String, Object> profile, String profileId, List<GeneratedRuntimeGap> gaps) {
        if (profile.isEmpty()) {
            return;
        }
        String profilePath = "generated-framework/run_profiles/" + profileId + ".yaml";
        for (String field : REQUIRED_RUN_PROFILE_FIELDS) {
            if (isMissing(profile.get(field))) {
                gaps.add(new GeneratedRuntimeGap(
                        profilePath + "#" + field,
                        "Declare run profile " + field + " before execution."));
            }
        }
        String executionMode = firstText(profile, "execution_mode");
        if (!executionMode.isBlank() && !ALLOWED_EXECUTION_MODES.contains(executionMode)) {
            gaps.add(new GeneratedRuntimeGap(
                    profilePath + "#execution_mode",
                    "Use supported execution_mode for run profile `" + profileId + "`."));
        }
    }

    public ProviderContractRef providerContractRef(String ref) {
        String[] parts = ref.split("#", 2);
        String fileRef = parts.length == 0 ? "" : parts[0];
        String fragment = parts.length == 2 ? parts[1] : "";
        String[] fragmentParts = fragment.split("\\.", 2);
        String section = fragmentParts.length > 0 ? fragmentParts[0] : "";
        String providerName = fragmentParts.length == 2 ? fragmentParts[1] : "";
        return new ProviderContractRef(fileRef, section, providerName);
    }

    public Map<String, Object> providerContract(Path packageRoot, ProviderContractRef ref) {
        return providerContract(packageRoot, ref.fileRef(), ref.section(), ref.providerName());
    }

    public Map<String, Object> providerContract(Path packageRoot, String fileRef, String section, String providerName) {
        if (fileRef == null || fileRef.isBlank() || section == null || section.isBlank()
                || providerName == null || providerName.isBlank()) {
            return Map.of();
        }
        Map<String, Object> root = readYamlMap(generatedPath(packageRoot, fileRef));
        Map<String, Object> contracts = nestedMap(root, "provider_contracts");
        if (contracts.isEmpty()) {
            contracts = root;
        }
        Map<String, Object> sectionMap = nestedMap(contracts, section);
        return nestedMap(sectionMap, providerName);
    }

    public String contractPath(String fallbackRef, Map<String, Object> contract) {
        String declared = firstText(contract, "contract_path");
        if (!declared.isBlank()) {
            return declared;
        }
        return fallbackRef.startsWith("generated-framework/")
                ? fallbackRef
                : "generated-framework/" + fallbackRef;
    }

    public Path generatedPath(Path packageRoot, String ref) {
        String cleanRef = ref == null ? "" : ref.split("#", 2)[0];
        if (cleanRef.startsWith("generated-framework/")) {
            return packageRoot.resolve(cleanRef).normalize();
        }
        return packageRoot.resolve("generated-framework").resolve(cleanRef).normalize();
    }

    private List<GeneratedRuntimeTarget> targets(
            Map<String, Object> environmentBinding,
            Map<String, List<String>> dependencyMap,
            String executionMode,
            List<GeneratedRuntimeGap> gaps) {
        Map<String, Object> targetMap = nestedMap(environmentBinding, "targets");
        if (targetMap.isEmpty()) {
            gaps.add(new GeneratedRuntimeGap(
                    "generated-framework/environment_bindings.targets",
                    "Generate environment binding targets before framework runtime execution."));
            return List.of();
        }
        List<String> orderedIds = executionOrder(targetMap.keySet(), dependencyMap);
        Map<String, Integer> orderByTarget = new LinkedHashMap<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            orderByTarget.put(orderedIds.get(index), index);
        }
        List<GeneratedRuntimeTarget> targets = new ArrayList<>();
        int fallbackOrder = orderedIds.size();
        for (Map.Entry<String, Object> entry : targetMap.entrySet()) {
            String targetKey = entry.getKey();
            Map<String, Object> target = entry.getValue() instanceof Map<?, ?> map ? copyMap(map) : Map.of();
            String targetId = firstNonBlank(firstText(target, "target_id"), targetKey);
            String runner = firstText(target, "provider", "runner");
            String providerContractRef = firstText(target, "provider_contract_ref");
            String environmentRef = firstText(target, "environment_ref");
            if (runner.isBlank()) {
                gaps.add(new GeneratedRuntimeGap(
                        "generated-framework/environment_bindings.targets." + targetKey + ".provider",
                        "Generate provider for target `" + targetId + "` before execution."));
            }
            if (providerContractRef.isBlank()) {
                gaps.add(new GeneratedRuntimeGap(
                        "generated-framework/environment_bindings.targets." + targetKey + ".provider_contract_ref",
                        "Generate provider_contract_ref for target `" + targetId + "` before execution."));
            }
            if (environmentRef.isBlank()) {
                gaps.add(new GeneratedRuntimeGap(
                        "generated-framework/environment_bindings.targets." + targetKey + ".environment_ref",
                        "Generate environment_ref for target `" + targetId + "` before execution."));
            }
            if ("sit_deployed".equals(executionMode) && firstText(target, "readiness_ref", "readiness_check").isBlank()) {
                gaps.add(new GeneratedRuntimeGap(
                        "generated-framework/environment_bindings.targets." + targetKey + ".readiness_ref",
                        "Declare readiness_ref for target `" + targetId + "` before sit_deployed execution."));
            }
            targets.add(new GeneratedRuntimeTarget(
                    targetId,
                    runner,
                    providerContractRef,
                    environmentRef,
                    List.copyOf(dependencyMap.getOrDefault(targetId, dependencyMap.getOrDefault(targetKey, List.of()))),
                    orderByTarget.getOrDefault(targetId, orderByTarget.getOrDefault(targetKey, fallbackOrder++))));
        }
        return targets.stream()
                .sorted((left, right) -> Integer.compare(left.order(), right.order()))
                .toList();
    }

    private List<String> executionOrder(Set<String> targetIds, Map<String, List<String>> dependencyMap) {
        Set<String> knownTargets = new LinkedHashSet<>(targetIds);
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();
        for (String targetId : targetIds) {
            visit(targetId, knownTargets, dependencyMap, visiting, visited, ordered);
        }
        return ordered;
    }

    private void visit(
            String targetId,
            Set<String> knownTargets,
            Map<String, List<String>> dependencyMap,
            Set<String> visiting,
            Set<String> visited,
            List<String> ordered) {
        if (visited.contains(targetId) || visiting.contains(targetId)) {
            return;
        }
        visiting.add(targetId);
        for (String dependency : dependencyMap.getOrDefault(targetId, List.of())) {
            if (knownTargets.contains(dependency)) {
                visit(dependency, knownTargets, dependencyMap, visiting, visited, ordered);
            }
        }
        visiting.remove(targetId);
        visited.add(targetId);
        ordered.add(targetId);
    }

    private Map<String, List<String>> targetDependencies(Map<String, Object> runPlan) {
        Map<String, Object> rawDependencies = nestedMap(runPlan, "target_dependencies");
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawDependencies.entrySet()) {
            List<String> targetDependencies = new ArrayList<>();
            if (entry.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    String dependency = dependencyTargetId(item);
                    if (!dependency.isBlank()) {
                        targetDependencies.add(dependency);
                    }
                }
            }
            dependencies.put(entry.getKey(), List.copyOf(targetDependencies));
        }
        return Map.copyOf(dependencies);
    }

    private String dependencyTargetId(Object item) {
        if (item instanceof Map<?, ?> map) {
            String required = firstText(map, "required");
            if ("false".equalsIgnoreCase(required)) {
                return "";
            }
            return firstText(map, "target_id", "target");
        }
        return stringValue(item);
    }

    private Map<String, Object> readRequiredMap(Path path, String fieldPath, List<GeneratedRuntimeGap> gaps) {
        if (!Files.isRegularFile(path)) {
            gaps.add(new GeneratedRuntimeGap(
                    fieldPath,
                    "Generate `" + fieldPath + "` before framework runtime execution."));
            return Map.of();
        }
        return readYamlMap(path);
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
            throw new UncheckedIOException("Failed to read generated runtime artifact: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
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

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    public record ProviderContractRef(String fileRef, String section, String providerName) {

        public String with(String newSection, String newProviderName) {
            return fileRef + "#" + newSection + "." + newProviderName;
        }
    }
}
