package com.specdriven.regression.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class ArtifactSchemaValidator {

    private static final List<String> REQUIRED_PACKAGE_FIELDS = List.of(
            "product_id",
            "rp_id",
            "name",
            "owner",
            "target_release",
            "package_type",
            "lifecycle_status",
            "default_execution_mode");

    private static final List<String> REQUIRED_ARTIFACT_PATHS = List.of(
            "feature_spec",
            "acceptance_criteria",
            "ru_mapping",
            "tests",
            "expected_results",
            "traceability",
            "evidence_index");

    public ArtifactValidationReport validatePackageYaml(Path packageYaml) {
        Map<String, Object> document = readYamlMap(packageYaml);
        List<ArtifactValidationError> errors = new ArrayList<>();

        for (String field : REQUIRED_PACKAGE_FIELDS) {
            if (isMissing(document.get(field))) {
                errors.add(error(packageYaml, field, blocksFor(field)));
            }
        }

        Object artifactPaths = document.get("artifact_paths");
        if (!(artifactPaths instanceof Map<?, ?> artifactPathMap)) {
            errors.add(error(packageYaml, "artifact_paths", "execution"));
        } else {
            for (String field : REQUIRED_ARTIFACT_PATHS) {
                if (isMissing(artifactPathMap.get(field))) {
                    errors.add(error(packageYaml, "artifact_paths." + field, blocksFor("artifact_paths." + field)));
                }
            }
        }

        return new ArtifactValidationReport(errors.isEmpty(), List.copyOf(errors));
    }

    private ArtifactValidationError error(Path file, String fieldPath, String blocks) {
        return new ArtifactValidationError(
                file,
                fieldPath,
                "error",
                blocks,
                "Add required field `" + fieldPath + "` to " + file.getFileName() + ".");
    }

    private String blocksFor(String fieldPath) {
        if (fieldPath.contains("evidence")) {
            return "release_evidence";
        }
        if (fieldPath.startsWith("artifact_paths")) {
            return "execution";
        }
        return "generation";
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
            throw new UncheckedIOException("Failed to read YAML artifact: " + path, e);
        }
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }
}
