package com.specdriven.regression.contract.v03;

import java.util.Map;

public record V03ExecutionStep(
        String testCaseId,
        V03ExecutionStepKind kind,
        String phase,
        String id,
        String target,
        String providerContract,
        String providerType,
        String profile,
        String runtimeMode,
        String operation,
        Map<String, Object> inputs,
        String providerInstanceRef) {

    /** Compatibility constructor for internal tests; compiler-created steps always provide a kind. */
    public V03ExecutionStep(
            String testCaseId,
            String phase,
            String id,
            String target,
            String providerContract,
            String providerType,
            String profile,
            String runtimeMode,
            String operation,
            Map<String, Object> inputs,
            String providerInstanceRef) {
        this(testCaseId,
                target == null || target.isBlank() ? V03ExecutionStepKind.ASSERTION : V03ExecutionStepKind.PROVIDER_OPERATION,
                phase, id, target, providerContract, providerType, profile, runtimeMode, operation, inputs, providerInstanceRef);
    }
}
