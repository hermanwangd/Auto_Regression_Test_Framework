package com.specdriven.regression.provider.runtime;

public record ProviderEvidence(
        String evidenceType,
        String ref,
        boolean masked) {
}
