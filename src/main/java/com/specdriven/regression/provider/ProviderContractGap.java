package com.specdriven.regression.provider;

public record ProviderContractGap(
        String fieldPath,
        String contractType,
        String providerName,
        String ownerAction) {
}
