package com.specdriven.regression.provider;

public record ResolvedProviderContract(
        String contractType,
        String providerName,
        String sourceLevel,
        String providerFamily,
        String affectedRu,
        String capability,
        String contractPath) {

    public ResolvedProviderContract(String contractType, String providerName, String sourceLevel) {
        this(contractType, providerName, sourceLevel, "", "", providerName, "");
    }
}
