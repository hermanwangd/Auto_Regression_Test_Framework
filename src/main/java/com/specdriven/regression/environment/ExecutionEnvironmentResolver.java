package com.specdriven.regression.environment;

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
public class ExecutionEnvironmentResolver {

    private static final List<String> SIT_DEPLOYMENT_FIELDS = List.of(
            "deployment_ref",
            "readiness_check",
            "deployed_version_ref");

    public ExecutionEnvironmentReport resolve(Path mappingYaml, String requestedExecutionMode) {
        Map<String, Object> document = readYamlMap(mappingYaml);
        Object unitsValue = document.get("release_units");
        if (!(unitsValue instanceof List<?> units) || units.isEmpty() || !(units.get(0) instanceof Map<?, ?> unit)) {
            return new ExecutionEnvironmentReport(
                    false,
                    requestedExecutionMode,
                    "",
                    List.of(new ExecutionEnvironmentGap(
                            "release_units",
                            "Provide owner-authored RP/RU mapping before execution.")));
        }

        String executionMode = requestedExecutionMode == null || requestedExecutionMode.isBlank()
                ? stringValue(unit.get("execution_mode"))
                : requestedExecutionMode;
        String environmentRef = stringValue(unit.get("environment_ref"));
        List<ExecutionEnvironmentGap> gaps = new ArrayList<>();

        if (requiresEnvironmentRef(executionMode) && environmentRef.isBlank()) {
            gaps.add(new ExecutionEnvironmentGap(
                    "release_units[0].environment_ref",
                    "Provide environment_ref for execution mode `" + executionMode + "`."));
        }

        if ("sit_deployed".equals(executionMode)) {
            Object deployment = unit.get("deployment");
            if (!(deployment instanceof Map<?, ?> deploymentMap)) {
                for (String field : SIT_DEPLOYMENT_FIELDS) {
                    gaps.add(sitGap(field));
                }
            } else {
                for (String field : SIT_DEPLOYMENT_FIELDS) {
                    if (isMissing(deploymentMap.get(field))) {
                        gaps.add(sitGap(field));
                    }
                }
            }
        }

        return new ExecutionEnvironmentReport(gaps.isEmpty(), executionMode, environmentRef, List.copyOf(gaps));
    }

    private boolean requiresEnvironmentRef(String executionMode) {
        return List.of("ci_ephemeral", "sit_deployed", "evidence_only").contains(executionMode);
    }

    private ExecutionEnvironmentGap sitGap(String field) {
        return new ExecutionEnvironmentGap(
                "release_units[0].deployment." + field,
                "Provide SIT deployment readiness evidence before sit_deployed regression.");
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
            throw new UncheckedIOException("Failed to read execution environment mapping: " + path, e);
        }
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
