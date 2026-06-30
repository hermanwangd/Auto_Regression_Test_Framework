package com.specdriven.regression.mapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class RpRuMappingService {

    private static final List<String> REQUIRED_RU_FIELDS = List.of(
            "ru_id",
            "repo",
            "unit_type",
            "owner",
            "version_ref",
            "validation_boundary",
            "execution_mode",
            "deployment_required",
            "environment_ref",
            "provider",
            "provider_contracts",
            "evidence_responsibility",
            "dependencies");

    private static final List<String> SIT_DEPLOYMENT_FIELDS = List.of(
            "deployment_ref",
            "readiness_check",
            "deployed_version_ref");

    public RpRuMappingValidationReport validate(Path mappingYaml) {
        Map<String, Object> document = readYamlMap(mappingYaml);
        List<RpRuMappingGap> gaps = new ArrayList<>();
        List<ParsedReleaseUnit> parsedReleaseUnits = new ArrayList<>();

        Object releaseUnitsValue = document.get("release_units");
        if (!(releaseUnitsValue instanceof List<?> units) || units.isEmpty()) {
            gaps.add(gap("release_units", "Declare at least one owner-authored release unit."));
            return report(gaps, List.of());
        }

        for (int index = 0; index < units.size(); index++) {
            Object unitValue = units.get(index);
            if (!(unitValue instanceof Map<?, ?> unit)) {
                gaps.add(gap("release_units[" + index + "]", "Replace release unit entry with a mapping object."));
                continue;
            }

            for (String field : REQUIRED_RU_FIELDS) {
                if (!unit.containsKey(field) || isMissing(unit.get(field))) {
                    gaps.add(gap("release_units[" + index + "]." + field,
                            "Update owner-authored rp_ru_mapping.yaml with required field `" + field + "`."));
                }
            }

            if (unit.containsKey("dependency_order") && !unit.containsKey("dependencies")) {
                gaps.add(gap("release_units[" + index + "].dependencies",
                        "Declare dependencies as a graph with `dependencies`; `dependency_order` is only a display hint."));
            }

            if ("sit_deployed".equals(unit.get("execution_mode"))) {
                Object deployment = unit.get("deployment");
                if (!(deployment instanceof Map<?, ?> deploymentMap)) {
                    for (String field : SIT_DEPLOYMENT_FIELDS) {
                        gaps.add(gap("release_units[" + index + "].deployment." + field,
                                "Update owner-authored rp_ru_mapping.yaml with SIT deployment readiness evidence."));
                    }
                } else {
                    for (String field : SIT_DEPLOYMENT_FIELDS) {
                        if (!deploymentMap.containsKey(field) || isMissing(deploymentMap.get(field))) {
                            gaps.add(gap("release_units[" + index + "].deployment." + field,
                                    "Update owner-authored rp_ru_mapping.yaml with SIT deployment readiness evidence."));
                        }
                    }
                }
            }

            String ruId = stringValue(unit.get("ru_id"));
            parsedReleaseUnits.add(new ParsedReleaseUnit(
                    index,
                    new ReleaseUnitMapping(
                            ruId,
                            stringValue(unit.get("repo")),
                            stringValue(unit.get("unit_type")),
                            stringValue(unit.get("execution_mode")),
                            stringValue(unit.get("environment_ref")),
                            firstText(unit, "provider", "runner")),
                    dependencies(index, unit, gaps)));
        }

        List<ReleaseUnitMapping> releaseUnits = orderedReleaseUnits(parsedReleaseUnits, gaps);
        return report(gaps, releaseUnits);
    }

    private List<DependencyRef> dependencies(int unitIndex, Map<?, ?> unit, List<RpRuMappingGap> gaps) {
        Object value = unit.get("dependencies");
        if (value == null || value instanceof List<?> list && list.isEmpty()) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            gaps.add(gap("release_units[" + unitIndex + "].dependencies",
                    "Declare dependencies as a list of existing RU IDs."));
            return List.of();
        }
        List<DependencyRef> dependencies = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            String dependency = stringValue(list.get(index));
            if (dependency.isBlank()) {
                gaps.add(gap("release_units[" + unitIndex + "].dependencies[" + index + "]",
                        "Declare dependency as a non-empty RU ID."));
            } else {
                dependencies.add(new DependencyRef(index, dependency));
            }
        }
        return List.copyOf(dependencies);
    }

    private List<ReleaseUnitMapping> orderedReleaseUnits(
            List<ParsedReleaseUnit> parsedReleaseUnits,
            List<RpRuMappingGap> gaps) {
        Map<String, ParsedReleaseUnit> unitsById = new LinkedHashMap<>();
        for (ParsedReleaseUnit unit : parsedReleaseUnits) {
            String ruId = unit.mapping().ruId();
            if (ruId.isBlank()) {
                continue;
            }
            if (unitsById.containsKey(ruId)) {
                gaps.add(gap("release_units[" + unit.index() + "].ru_id",
                        "Declare each release unit with a unique ru_id."));
            } else {
                unitsById.put(ruId, unit);
            }
        }

        int graphGapCount = gaps.size();
        Map<String, Integer> dependencyCounts = new LinkedHashMap<>();
        Map<String, List<String>> dependentsByDependency = new LinkedHashMap<>();
        for (ParsedReleaseUnit unit : parsedReleaseUnits) {
            String ruId = unit.mapping().ruId();
            if (ruId.isBlank() || !unitsById.containsKey(ruId)) {
                continue;
            }
            dependencyCounts.putIfAbsent(ruId, 0);
            for (DependencyRef dependency : unit.dependencies()) {
                if (!unitsById.containsKey(dependency.ruId())) {
                    gaps.add(gap("release_units[" + unit.index() + "].dependencies[" + dependency.index() + "]",
                            "Declare dependency `" + dependency.ruId() + "` as an existing RU ID in this RP."));
                    continue;
                }
                dependencyCounts.put(ruId, dependencyCounts.get(ruId) + 1);
                dependentsByDependency.computeIfAbsent(dependency.ruId(), ignored -> new ArrayList<>()).add(ruId);
            }
        }

        if (gaps.size() > graphGapCount) {
            return parsedReleaseUnits.stream().map(ParsedReleaseUnit::mapping).toList();
        }

        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : dependencyCounts.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<ReleaseUnitMapping> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String ruId = ready.remove();
            ordered.add(unitsById.get(ruId).mapping());
            for (String dependent : dependentsByDependency.getOrDefault(ruId, List.of())) {
                int remaining = dependencyCounts.get(dependent) - 1;
                dependencyCounts.put(dependent, remaining);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (ordered.size() != unitsById.size()) {
            gaps.add(gap("release_units.dependencies",
                    "Break the release unit dependency cycle before execution."));
            return parsedReleaseUnits.stream().map(ParsedReleaseUnit::mapping).toList();
        }
        return List.copyOf(ordered);
    }

    private RpRuMappingValidationReport report(List<RpRuMappingGap> gaps, List<ReleaseUnitMapping> releaseUnits) {
        return new RpRuMappingValidationReport(
                gaps.isEmpty(),
                !gaps.isEmpty(),
                List.copyOf(releaseUnits),
                List.copyOf(gaps),
                false);
    }

    private RpRuMappingGap gap(String fieldPath, String ownerAction) {
        return new RpRuMappingGap(fieldPath, "error", true, ownerAction);
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
            throw new UncheckedIOException("Failed to read RP/RU mapping: " + path, e);
        }
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
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

    private record ParsedReleaseUnit(int index, ReleaseUnitMapping mapping, List<DependencyRef> dependencies) {
    }

    private record DependencyRef(int index, String ruId) {
    }
}
