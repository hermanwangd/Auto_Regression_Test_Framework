package com.specdriven.regression.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class SampleFakeProvider {

    private final Yaml yaml = new Yaml();

    public SetupResult setup(Path setupFixture, Path inputFile, Path evidenceDir) {
        return setup(setupFixture, inputFile, evidenceDir, "sample-fake-runtime");
    }

    public SetupResult setup(Path setupFixture, Path inputFile, Path evidenceDir, String providerId) {
        createDirectories(evidenceDir.resolve("fixture"));
        String status = Files.isRegularFile(setupFixture) && Files.isRegularFile(inputFile) ? "passed" : "failed";
        write(evidenceDir.resolve("fixture/setup.yaml"), """
                evidence_type: fixture_setup
                evidence_classification: framework_verification_only
                downstream_release_evidence: false
                %s
                operation: setup_fixture
                status: %s
                setup_fixture_ref: %s
                input_ref: %s
                """.formatted(providerIdLine(providerId), status, setupFixture, inputFile));
        return new SetupResult("passed".equals(status), evidenceDir.resolve("fixture/setup.yaml"));
    }

    public ExecutionResult execute(Path inputFile, String generatedAt, Path evidenceDir) {
        createDirectories(evidenceDir.resolve("actual"));
        createDirectories(evidenceDir.resolve("logs"));
        Map<String, Object> input = readMap(inputFile);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("requestId", stringValue(input.get("requestId")));
        actual.put("status", "OK");
        actual.put("message", stringValue(input.get("input")));
        actual.put("items", java.util.List.of(Map.of("name", "sample", "result", "verified")));
        actual.put("generatedAt", generatedAt);

        Path actualJson = evidenceDir.resolve("actual/actual_output.json");
        Path actualText = evidenceDir.resolve("actual/actual_output.txt");
        Path executionLog = evidenceDir.resolve("logs/execution.log");
        write(actualJson, toJson(actual) + "\n");
        write(actualText, stringValue(input.get("input")) + "\n");
        write(executionLog, """
                evidence_classification: framework_verification_only
                provider_type: sample_fake_provider
                operation: execute_sample
                status: passed
                input_ref: %s
                output_ref: actual/actual_output.json
                text_output_ref: actual/actual_output.txt
                """.formatted(inputFile));
        return new ExecutionResult(actual, actualJson, actualText, executionLog);
    }

    public CleanupResult cleanup(Path cleanupFixture, Path evidenceDir) {
        return cleanup(cleanupFixture, evidenceDir, "sample-fake-runtime");
    }

    public CleanupResult cleanup(Path cleanupFixture, Path evidenceDir, String providerId) {
        return cleanup(cleanupFixture, evidenceDir, providerId, "legacy-workspace");
    }

    public CleanupResult cleanup(Path cleanupFixture, Path evidenceDir, String providerId, String workspaceRef) {
        createDirectories(evidenceDir.resolve("fixture"));
        String status = Files.isRegularFile(cleanupFixture) && workspaceRef != null && !workspaceRef.isBlank()
                ? "passed" : "failed";
        write(evidenceDir.resolve("fixture/cleanup.yaml"), """
                evidence_type: fixture_cleanup
                evidence_classification: framework_verification_only
                downstream_release_evidence: false
                %s
                operation: cleanup_fixture
                status: %s
                cleanup_fixture_ref: %s
                """.formatted(providerIdLine(providerId), status, cleanupFixture));
        return new CleanupResult("passed".equals(status), evidenceDir.resolve("fixture/cleanup.yaml"));
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
            throw new UncheckedIOException("Failed to read fake provider input: " + path, e);
        }
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create directory: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fake provider evidence: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String providerIdLine(String providerId) {
        return providerId == null || providerId.isBlank() ? "" : "provider_id: " + providerId;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escape(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(String.valueOf(entry.getKey()))).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record SetupResult(boolean passed, Path evidenceRef) {
    }

    public record ExecutionResult(Map<String, Object> actual, Path actualJson, Path actualText, Path executionLog) {
    }

    public record CleanupResult(boolean passed, Path evidenceRef) {
    }
}
