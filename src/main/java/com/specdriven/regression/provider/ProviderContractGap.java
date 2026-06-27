package com.specdriven.regression.provider;

public record ProviderContractGap(
        String fieldPath,
        String contractType,
        String providerName,
        String providerFamily,
        String providerType,
        String registryStatus,
        String runtimeStatus,
        String affectedRu,
        String capability,
        String ownerAction) {

    public ProviderContractGap(
            String fieldPath,
            String contractType,
            String providerName,
            String providerFamily,
            String affectedRu,
            String capability,
            String ownerAction) {
        this(fieldPath, contractType, providerName, providerFamily, "", "missing", "blocked",
                affectedRu, capability, ownerAction);
    }

    public ProviderContractGap(
            String fieldPath,
            String contractType,
            String providerName,
            String ownerAction) {
        this(fieldPath, contractType, providerName, "", "", "missing", "blocked",
                "", providerName, ownerAction);
    }
}
