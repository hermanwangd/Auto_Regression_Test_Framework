package com.specdriven.regression.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

final class RuntimeBindingResolver {

    private static final Set<String> BINDING_VALUE_KIND_FIELDS = Set.of(
            "value",
            "ref",
            "secret_ref",
            "generated_ref",
            "local_ref");

    private final Yaml yaml = new Yaml();

    Map<String, Object> providerBinding(Path suiteRoot, String profile, String providerId) {
        Path envProfilesRoot = suiteRoot.resolve("env_profiles");
        if (Files.isDirectory(envProfilesRoot)) {
            return providerBindingFromEnvProfile(profileDocument(envProfilesRoot, "env_profile_id", profile), providerId);
        }
        return providerBindingFromEnvironmentBinding(
                profileDocument(suiteRoot.resolve("environment_bindings"), "profile", profile),
                providerId);
    }

    private Map<String, Object> providerBindingFromEnvProfile(Map<String, Object> envProfile, String providerId) {
        Map<String, Object> provider = mapValue(mapValue(envProfile.get("providers")).get(providerId));
        if (provider.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("provider_id", providerId);
        normalized.put("runtime_mode", provider.get("runtime_mode"));
        normalized.put("binding_values", normalizeBindingKeys(mapValue(provider.get("binding_keys"))));
        return normalized;
    }

    private Map<String, Object> providerBindingFromEnvironmentBinding(
            Map<String, Object> environmentBinding,
            String providerId) {
        for (Object value : listValue(environmentBinding.get("provider_bindings"))) {
            Map<String, Object> providerBinding = mapValue(value);
            if (providerId.equals(providerBinding.get("provider_id"))) {
                return providerBinding;
            }
        }
        return Map.of();
    }

    private Map<String, Object> profileDocument(Path directory, String profileField, String profile) {
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                Map<String, Object> document = readMap(path);
                if (profile.equals(stringValue(document.get(profileField)))) {
                    return document;
                }
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read runtime binding directory: " + directory, e);
        }
    }

    private Map<String, Object> normalizeBindingKeys(Map<String, Object> bindingKeys) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : bindingKeys.entrySet()) {
            putPath(normalized, entry.getKey(), normalizeBindingValue(entry.getValue()));
        }
        return normalized;
    }

    private Object normalizeBindingValue(Object value) {
        Map<String, Object> map = mapValue(value);
        if (map.isEmpty()) {
            return value;
        }
        for (String kind : BINDING_VALUE_KIND_FIELDS) {
            if (map.containsKey(kind)) {
                return "secret_ref".equals(kind) || "local_ref".equals(kind)
                        ? Map.of(kind, map.get(kind))
                        : map.get(kind);
            }
        }
        return map;
    }

    private void putPath(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(parts[i], next);
            }
            cursor = mapValue(next);
        }
        cursor.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Path path) {
        try {
            Object loaded = yaml.load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
