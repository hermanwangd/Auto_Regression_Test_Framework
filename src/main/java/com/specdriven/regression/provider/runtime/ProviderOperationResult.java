package com.specdriven.regression.provider.runtime;

import java.util.List;
import java.util.Map;

public record ProviderOperationResult(
        boolean passed,
        Map<String, Object> outputs,
        List<ProviderEvidence> evidence,
        ProviderFailure failure) {

    public static ProviderOperationResult passed(Map<String, Object> outputs, List<ProviderEvidence> evidence) {
        return new ProviderOperationResult(true, outputs, List.copyOf(evidence), null);
    }

    public static ProviderOperationResult failed(
            Map<String, Object> outputs,
            List<ProviderEvidence> evidence,
            ProviderFailure failure) {
        return new ProviderOperationResult(false, outputs, List.copyOf(evidence), failure);
    }
}
