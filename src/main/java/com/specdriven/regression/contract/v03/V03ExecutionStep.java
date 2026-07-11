package com.specdriven.regression.contract.v03;

import java.util.Map;

public record V03ExecutionStep(
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
}
