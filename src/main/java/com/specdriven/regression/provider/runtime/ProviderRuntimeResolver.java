package com.specdriven.regression.provider.runtime;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ProviderRuntimeResolver {

    private final ProviderRuntimeRegistry registry;

    public ProviderRuntimeResolver(ProviderRuntimeRegistry registry) {
        this.registry = registry;
    }

    public ProviderRuntimeResolution resolve(ProviderExecutionContext context, ProviderOperationRequest request) {
        ProviderRuntimeRegistry.RuntimeResolution runtime = registry.tryResolve(context.providerType());
        if (!runtime.valid()) {
            return ProviderRuntimeResolution.failed(runtime.failure());
        }
        Map<String, Object> operation = operation(context.providerContract(), request.operation());
        if (operation.isEmpty()) {
            return ProviderRuntimeResolution.failed(ProviderFailure.of(
                    "UNSUPPORTED_OPERATION",
                    "TARGET_RESOLUTION_FAILED",
                    "Provider operation `" + request.operation() + "` is not declared by Provider Contract.",
                    "Use an operation declared by the selected Provider Contract."));
        }
        List<String> allowedInputs = operationInputs(operation, "allowed_inputs", "allowed_bind_as");
        for (Map<String, Object> parameter : request.parameters()) {
            String inputName = stringValue(parameter.get("bind_as"));
            if (!allowedBindAs(allowedInputs, inputName)) {
                return ProviderRuntimeResolution.failed(ProviderFailure.of(
                        "UNSUPPORTED_INPUT",
                        "TARGET_RESOLUTION_FAILED",
                        "input `" + inputName + "` is not allowed for operation `" + request.operation() + "`.",
                        "Use an input declared by the Provider Contract operation."));
            }
        }
        for (String requiredInput : operationInputs(operation, "required_inputs", "required_parameters")) {
            if (request.parameters().stream()
                    .noneMatch(parameter -> requiredInput.equals(stringValue(parameter.get("bind_as"))))) {
                return ProviderRuntimeResolution.failed(ProviderFailure.of(
                        "MISSING_REQUIRED_INPUT",
                        "TARGET_RESOLUTION_FAILED",
                        "Required input `" + requiredInput + "` is missing for operation `"
                                + request.operation() + "`.",
                        "Add input `" + requiredInput + "` to the DSL operation."));
            }
        }
        for (String bindingKey : requiredBindingKeys(context.providerContract())) {
            if (isMissing(valueAtPath(context.bindingValues(), bindingKey))) {
                return ProviderRuntimeResolution.failed(ProviderFailure.of(
                        "MISSING_BINDING_KEY",
                        "TARGET_RESOLUTION_FAILED",
                        "Required binding key `" + bindingKey + "` is missing.",
                        "Add the binding key to Environment Binding for provider `" + context.providerId() + "`."));
            }
        }
        return ProviderRuntimeResolution.resolved(runtime.runtime());
    }

    private Map<String, Object> operation(Map<String, Object> providerContract, String operation) {
        return mapValue(mapValue(providerContract.get("operations")).get(operation));
    }

    private List<String> requiredBindingKeys(Map<String, Object> providerContract) {
        return mapValue(providerContract.get("binding_keys")).entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(mapValue(entry.getValue()).get("required")))
                .map(Map.Entry::getKey)
                .toList();
    }

    private boolean allowedBindAs(List<String> allowedBindAs, String bindAs) {
        for (String allowed : allowedBindAs) {
            if (allowed.endsWith(".*") && bindAs.startsWith(allowed.substring(0, allowed.length() - 1))) {
                return true;
            }
            if (allowed.equals(bindAs)) {
                return true;
            }
        }
        return false;
    }

    private List<String> operationInputs(Map<String, Object> operation, String preferredField, String legacyField) {
        List<String> inputs = stringList(operation.get(preferredField));
        return inputs.isEmpty() ? stringList(operation.get(legacyField)) : inputs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    private boolean isMissing(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private Object valueAtPath(Map<String, Object> map, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ProviderRuntimeResolution(ProviderRuntime runtime, ProviderFailure failure) {

        static ProviderRuntimeResolution resolved(ProviderRuntime runtime) {
            return new ProviderRuntimeResolution(runtime, null);
        }

        static ProviderRuntimeResolution failed(ProviderFailure failure) {
            return new ProviderRuntimeResolution(null, failure);
        }

        public boolean valid() {
            return failure == null;
        }
    }
}
