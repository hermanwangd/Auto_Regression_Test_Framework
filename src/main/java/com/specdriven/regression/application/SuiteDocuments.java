package com.specdriven.regression.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class SuiteDocuments {

    private SuiteDocuments() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> readYamlMap(Path path) {
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    static List<Object> objectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
