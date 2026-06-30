package com.specdriven.regression.testcase;

import java.util.List;

public record ExecutionContextReadiness(
        boolean ready,
        String ruId,
        String provider,
        String executionMode,
        String environmentRef,
        List<String> capabilities,
        List<String> gaps) {

    public static ExecutionContextReadiness ready(
            String ruId,
            String provider,
            String executionMode,
            String environmentRef,
            List<String> capabilities) {
        return new ExecutionContextReadiness(
                true,
                ruId,
                provider,
                executionMode,
                environmentRef,
                List.copyOf(capabilities),
                List.of());
    }

    public static ExecutionContextReadiness incomplete(List<String> gaps) {
        return new ExecutionContextReadiness(false, "", "", "", "", List.of(), List.copyOf(gaps));
    }
}
