package com.specdriven.regression.provider.runtime;

import java.util.List;
import java.util.Map;

public record ProviderOperationResult(
        String status,
        Map<String, Object> outputs,
        List<ProviderEvidence> evidence,
        ProviderFailure failure) {

    private static final List<String> ALLOWED_STATUSES = List.of("passed", "failed", "blocked");

    public ProviderOperationResult {
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Provider operation status must be passed, failed, or blocked.");
        }
    }

    public ProviderOperationResult(
            boolean passed,
            Map<String, Object> outputs,
            List<ProviderEvidence> evidence,
            ProviderFailure failure) {
        this(passed ? "passed" : "failed", outputs, evidence, failure);
    }

    public boolean passed() {
        return "passed".equals(status);
    }

    public static ProviderOperationResult passed(Map<String, Object> outputs, List<ProviderEvidence> evidence) {
        return new ProviderOperationResult("passed", outputs, List.copyOf(evidence), null);
    }

    public static ProviderOperationResult failed(
            Map<String, Object> outputs,
            List<ProviderEvidence> evidence,
            ProviderFailure failure) {
        return new ProviderOperationResult("failed", outputs, List.copyOf(evidence), failure);
    }

    public static ProviderOperationResult blocked(
            Map<String, Object> outputs,
            List<ProviderEvidence> evidence,
            ProviderFailure failure) {
        return new ProviderOperationResult("blocked", outputs, List.copyOf(evidence), failure);
    }
}
