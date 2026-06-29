package com.specdriven.regression.provider.runtime;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class ProviderRuntimeRegistry {

    private final Map<String, ProviderRuntime> runtimes;

    public ProviderRuntimeRegistry(Map<String, ProviderRuntime> runtimes) {
        Map<String, ProviderRuntime> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderRuntime> entry : runtimes.entrySet()) {
            normalized.put(normalize(entry.getKey()), entry.getValue());
        }
        this.runtimes = Map.copyOf(normalized);
    }

    public ProviderRuntime resolve(String providerType) {
        ProviderRuntime runtime = runtimes.get(normalize(providerType));
        if (runtime == null) {
            throw new IllegalStateException("No provider runtime registered for provider_type `" + providerType + "`.");
        }
        return runtime;
    }

    public RuntimeResolution tryResolve(String providerType) {
        ProviderRuntime runtime = runtimes.get(normalize(providerType));
        if (runtime == null) {
            return RuntimeResolution.failed(ProviderFailure.of(
                    "UNKNOWN_PROVIDER_TYPE",
                    "TARGET_RESOLUTION_FAILED",
                    "No provider runtime registered for provider_type `" + providerType + "`.",
                    "Register a ProviderRuntime for the Provider Contract provider_type."));
        }
        return RuntimeResolution.resolved(runtime);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record RuntimeResolution(ProviderRuntime runtime, ProviderFailure failure) {

        static RuntimeResolution resolved(ProviderRuntime runtime) {
            return new RuntimeResolution(runtime, null);
        }

        static RuntimeResolution failed(ProviderFailure failure) {
            return new RuntimeResolution(null, failure);
        }

        public boolean valid() {
            return failure == null;
        }
    }
}
