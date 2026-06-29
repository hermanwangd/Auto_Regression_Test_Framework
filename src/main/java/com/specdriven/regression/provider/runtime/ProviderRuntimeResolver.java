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
        List<String> allowedBindAs = stringList(operation.get("allowed_bind_as"));
        for (Map<String, Object> parameter : request.parameters()) {
            String bindAs = stringValue(parameter.get("bind_as"));
            if (!allowedBindAs(allowedBindAs, bindAs)) {
                return ProviderRuntimeResolution.failed(ProviderFailure.of(
                        "UNSUPPORTED_BIND_AS",
                        "TARGET_RESOLUTION_FAILED",
                        "bind_as `" + bindAs + "` is not allowed for operation `" + request.operation() + "`.",
                        "Use a bind_as value declared by the Provider Contract operation."));
            }
        }
        for (String requiredParameter : stringList(operation.get("required_parameters"))) {
            if (request.parameters().stream()
                    .noneMatch(parameter -> requiredParameter.equals(stringValue(parameter.get("bind_as"))))) {
                return ProviderRuntimeResolution.failed(ProviderFailure.of(
                        "MISSING_REQUIRED_PARAMETER",
                        "TARGET_RESOLUTION_FAILED",
                        "Required parameter `" + requiredParameter + "` is missing for operation `"
                                + request.operation() + "`.",
                        "Add a parameter with bind_as `" + requiredParameter + "` to the DSL operation."));
            }
        }
        for (String bindingKey : requiredBindingKeys(context.providerContract())) {
            if (isMissing(context.bindingValues().get(bindingKey))) {
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
