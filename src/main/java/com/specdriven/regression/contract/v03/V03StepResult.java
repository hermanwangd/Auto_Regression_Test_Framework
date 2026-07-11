package com.specdriven.regression.contract.v03;

import java.util.List;
import java.util.Map;

public record V03StepResult(
        String stepId,
        String status,
        Map<String, Object> outputs,
        List<String> evidenceRefs,
        String failureCode,
        String message) {

    private static final List<String> ALLOWED_STATUSES = List.of("passed", "failed", "blocked");

    public V03StepResult {
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Provider adapter status must be passed, failed, or blocked.");
        }
    }

    public boolean passed() {
        return "passed".equals(status);
    }
}
