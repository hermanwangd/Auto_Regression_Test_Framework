package com.specdriven.regression.contract.v03;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies Provider Contract output sensitivity before values leave runtime memory. */
public final class V03OutputRedactor {
    public static final String MASKED = "[MASKED]";

    public Map<String, Object> redact(V03ExecutionPlan plan, V03ExecutionStep step, Map<String, Object> outputs) {
        V03ProviderContract contract = plan.providerContracts().get(step.providerContract());
        V03ProviderContract.V03OperationDefinition operation = contract == null
                ? null : contract.operations().get(step.operation());
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> output : outputs.entrySet()) {
            if (operation == null || !operation.outputDefinitions().containsKey(output.getKey())) {
                continue;
            }
            V03OutputDefinition definition = operation.outputDefinitions().get(output.getKey());
            result.put(output.getKey(), definition.sensitivity() == V03Sensitivity.PUBLIC
                    ? redactValue(output.getValue())
                    : MASKED);
        }
        return Map.copyOf(result);
    }

    public Object redactAssertionValue(
            V03ExecutionPlan plan,
            V03ExecutionStep assertion,
            Object raw,
            Object value,
            Map<String, V03ProducedOutput> producedOutputs) {
        V03OutputDefinition definition = outputDefinitionForReference(assertion, raw, producedOutputs);
        if (definition != null && definition.sensitivity() != V03Sensitivity.PUBLIC) {
            return MASKED;
        }
        return redactValue(value);
    }

    private V03OutputDefinition outputDefinitionForReference(
            V03ExecutionStep assertion,
            Object raw,
            Map<String, V03ProducedOutput> producedOutputs) {
        if (!(raw instanceof String reference)) return null;
        if (reference.startsWith("step://")) {
            String body = reference.substring("step://".length());
            int slash = body.indexOf('/');
            if (slash < 1) return null;
            String stepId = body.substring(0, slash);
            String output = body.substring(slash + 1).split("#", 2)[0];
            V03ProducedOutput produced = producedOutput(producedOutputs, assertion.testCaseId(), stepId, output);
            if (produced != null) return new V03OutputDefinition(
                    produced.valueType(), produced.sensitivity(), produced.bindable(), true);
            return null;
        }
        if (reference.startsWith("generated://")) {
            String body = reference.substring("generated://".length()).split("#", 2)[0];
            int slash = body.indexOf('/');
            if (slash < 1) return null;
            String targetName = body.substring(0, slash);
            String output = body.substring(slash + 1);
            List<V03ProducedOutput> produced = producedOutputs.values().stream()
                    .filter(item -> targetName.equals(item.target()) && matchesOutputPath(item, output) && item.bindable())
                    .toList();
            if (produced.size() == 1) {
                V03ProducedOutput item = produced.get(0);
                return new V03OutputDefinition(item.valueType(), item.sensitivity(), item.bindable(), true);
            }
            return null;
        }
        return null;
    }

    private V03ProducedOutput producedOutput(
            Map<String, V03ProducedOutput> producedOutputs, String testCaseId, String stepId, String outputPath) {
        V03ProducedOutput exact = producedOutputs.get(testCaseId + "\n" + stepId + "\n" + outputPath);
        if (exact != null) return exact;
        String root = outputPath.contains(".") ? outputPath.substring(0, outputPath.indexOf('.')) : outputPath;
        V03ProducedOutput parent = producedOutputs.get(testCaseId + "\n" + stepId + "\n" + root);
        return matchesOutputPath(parent, outputPath) ? parent : null;
    }

    private boolean matchesOutputPath(V03ProducedOutput output, String outputPath) {
        if (output == null) return false;
        if (output.outputName().equals(outputPath)) return true;
        return outputPath.startsWith(output.outputName() + ".")
                && (output.valueType() == V03ValueType.OBJECT || output.valueType() == V03ValueType.ANY);
    }

    public String redactMessage(String message) {
        if (message == null) return "";
        return message.replaceAll("(?i)([\\\"']?(?:password|token|secret|authorization|api[_-]?key|credential|private_key)[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:bearer\\s+)?[^\\s,;\\\"'}]+", "$1" + MASKED)
                .replaceAll("(?i)bearer\\s+[^\\s,;\\\"'}]+", "Bearer " + MASKED)
                .replaceAll("(?i)(https?|grpc|grpcs)://[^/@\\s]+@", "$1://" + MASKED + "@")
                .replaceAll("(?i)jdbc:[^\\s,;]+", MASKED)
                .replaceAll("(?i)nats://[^\\s,;]+", MASKED);
    }

    public Object redactValue(Object value) {
        return redactNested(value);
    }

    public Object redactEvidenceValue(Object value, java.util.Set<String> contractKeys) {
        return redactEvidenceNested(value, contractKeys);
    }

    private Object redactEvidenceNested(Object value, java.util.Set<String> contractKeys) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, sensitiveKey(name) || contractKeys.contains(name) ? MASKED
                        : redactEvidenceNested(item, contractKeys));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> redactEvidenceNested(item, contractKeys)).toList();
        return value instanceof String text ? redactMessage(text) : value;
    }

    @SuppressWarnings("unchecked")
    private Object redactNested(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), sensitiveKey(String.valueOf(key)) ? MASKED : redactNested(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(redactNested(item)));
            return List.copyOf(result);
        }
        if (value instanceof String text) return redactMessage(text);
        return value;
    }

    private boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("authorization") || normalized.contains("api_key") || normalized.equals("apikey")
                || normalized.contains("credential") || normalized.contains("private_key");
    }
}
