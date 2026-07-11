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
            V03OutputDefinition definition = operation == null
                    ? V03OutputDefinition.legacy(false)
                    : operation.outputDefinitions().getOrDefault(output.getKey(), V03OutputDefinition.legacy(false));
            result.put(output.getKey(), definition.sensitivity() == V03Sensitivity.PUBLIC
                    ? redactValue(output.getValue())
                    : MASKED);
        }
        return Map.copyOf(result);
    }

    public String redactMessage(String message) {
        if (message == null) return "";
        return message.replaceAll("(?i)([\\\"']?(?:password|token|secret|authorization|api[_-]?key|credential|private_key)[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:bearer\\s+)?[^\\s,;\\\"'}]+", "$1" + MASKED)
                .replaceAll("(?i)bearer\\s+[^\\s,;\\\"'}]+", "Bearer " + MASKED)
                .replaceAll("(?i)jdbc:[^\\s,;]+", MASKED)
                .replaceAll("(?i)nats://[^\\s,;]+", MASKED);
    }

    public Object redactValue(Object value) {
        return redactNested(value);
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
