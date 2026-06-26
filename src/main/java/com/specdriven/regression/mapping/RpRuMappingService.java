package com.specdriven.regression.mapping;

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
            "adapter",
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
        List<ReleaseUnitMapping> releaseUnits = new ArrayList<>();

        Object releaseUnitsValue = document.get("release_units");
        if (!(releaseUnitsValue instanceof List<?> units) || units.isEmpty()) {
            gaps.add(gap("release_units", "Declare at least one owner-authored release unit."));
            return report(gaps, releaseUnits);
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

            releaseUnits.add(new ReleaseUnitMapping(
                    stringValue(unit.get("ru_id")),
                    stringValue(unit.get("repo")),
                    stringValue(unit.get("unit_type")),
                    stringValue(unit.get("execution_mode")),
                    stringValue(unit.get("environment_ref")),
                    stringValue(unit.get("adapter"))));
        }

        return report(gaps, releaseUnits);
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
}
