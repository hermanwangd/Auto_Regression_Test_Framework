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

    public V03ProviderRuntimeAdapter resolve(String providerType) {
        V03ProviderRuntimeAdapter adapter = adapters.get(providerType);
        if (adapter == null) {
            throw new IllegalArgumentException("No v0.3 runtime adapter for provider_type `" + providerType + "`.");
        }
        return adapter;
    }
}
