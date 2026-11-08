package com.specdriven.regression.contract.v03;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class V03ProviderRuntimeRegistry {

    private final Map<String, V03ProviderRuntimeAdapter> adapters;

    public V03ProviderRuntimeRegistry(List<V03ProviderRuntimeAdapter> adapters) {
        Map<String, V03ProviderRuntimeAdapter> byProviderType = new LinkedHashMap<>();
        for (V03ProviderRuntimeAdapter adapter : adapters) {
            V03ProviderRuntimeAdapter existing = byProviderType.putIfAbsent(adapter.providerType(), adapter);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate v0.3 runtime adapter for provider_type `" + adapter.providerType() + "`.");
            }
        }
        this.adapters = Map.copyOf(byProviderType);
    }

    /** Per-run replacement; never mutates a registry shared by other invocations. */
    public V03ProviderRuntimeRegistry replacing(V03ProviderRuntimeAdapter replacement) {
        java.util.Objects.requireNonNull(replacement);var selected=new java.util.ArrayList<V03ProviderRuntimeAdapter>(adapters.values());
        selected.removeIf(adapter->adapter.providerType().equals(replacement.providerType()));selected.add(replacement);return new V03ProviderRuntimeRegistry(selected);
    }

    public V03ProviderRuntimeAdapter resolve(String providerType) {
        if ("ibm_mq".equals(providerType) && !com.specdriven.regression.build.RunnerBuildVariant.ibmMqAvailable())
            throw new IllegalArgumentException(com.specdriven.regression.build.RunnerBuildVariant.mqUnsupportedReason());
        V03ProviderRuntimeAdapter adapter = adapters.get(providerType);
        if (adapter == null) {
            throw new IllegalArgumentException("No v0.3 runtime adapter for provider_type `" + providerType + "`.");
        }
        return adapter;
    }
}
