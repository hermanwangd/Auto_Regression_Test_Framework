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

    public Object redactAssertionValue(V03ExecutionPlan plan, V03ExecutionStep assertion, Object raw, Object value) {
        V03OutputDefinition definition = outputDefinitionForReference(plan, assertion, raw);
        if (definition != null && definition.sensitivity() != V03Sensitivity.PUBLIC) {
            return MASKED;
        }
        return redactValue(value);
    }

    private V03OutputDefinition outputDefinitionForReference(
            V03ExecutionPlan plan, V03ExecutionStep assertion, Object raw) {
        if (!(raw instanceof String reference)) return null;
        if (reference.startsWith("step://")) {
            String body = reference.substring("step://".length());
            int slash = body.indexOf('/');
            if (slash < 1) return null;
            String stepId = body.substring(0, slash);
            String output = body.substring(slash + 1).split("#", 2)[0];
            return plan.steps().stream()
                    .filter(step -> assertion.testCaseId().equals(step.testCaseId()) && stepId.equals(step.id()))
                    .findFirst().map(step -> outputDefinition(plan, step, output)).orElse(null);
        }
        if (reference.startsWith("generated://")) {
            String body = reference.substring("generated://".length()).split("#", 2)[0];
            int slash = body.indexOf('/');
            if (slash < 1) return null;
            V03ResolvedTarget target = plan.targets().get(body.substring(0, slash));
            if (target == null) return null;
            String exactOutput = body.substring(slash + 1);
            V03ProviderContract contract = plan.providerContracts().get(target.providerContract());
            if (contract == null) return null;
            V03OutputDefinition exact = contract.operations().values().stream()
                    .map(operation -> operation.outputDefinitions().get(exactOutput))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (exact != null) return exact;
            String rootOutput = rootOutput(exactOutput);
            return contract.operations().values().stream()
                    .map(operation -> operation.outputDefinitions().get(rootOutput))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }
        return null;
    }

    private V03OutputDefinition outputDefinition(V03ExecutionPlan plan, V03ExecutionStep step, String output) {
        V03ProviderContract contract = plan.providerContracts().get(step.providerContract());
        if (contract == null) return null;
        V03ProviderContract.V03OperationDefinition operation = contract.operations().get(step.operation());
        if (operation == null) return null;
        V03OutputDefinition exact = operation.outputDefinitions().get(output);
        return exact == null ? operation.outputDefinitions().get(rootOutput(output)) : exact;
    }

    private String rootOutput(String output) {
        int separator = output.indexOf('.');
        return separator < 0 ? output : output.substring(0, separator);
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
