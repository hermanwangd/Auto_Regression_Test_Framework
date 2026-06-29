package com.specdriven.regression.provider.runtime;

public record ProviderFailure(
        String code,
        String classification,
        String reason,
        String ownerAction) {

    public static ProviderFailure of(String code, String classification, String reason, String ownerAction) {
        return new ProviderFailure(code, classification, reason, ownerAction);
    }
}
