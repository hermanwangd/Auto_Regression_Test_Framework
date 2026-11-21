package com.featuredeliveryintelligence.fdi.producttest;

import java.util.Map;

/** Invocation facts and framework verdict. This is neither a Swarm VerificationResult nor Human DONE. */
public record ProductTestReceipt(
        String contractVersion, String moduleId, String moduleVersion, String invocationId,
        ProductTestRequest request, Outcome outcome, String frameworkVerdict, String evidenceValidation,
        Integer frameworkExitCode, String runId, String outputDirectory,
        Map<String, Artifact> artifacts, boolean releaseEvidenceEligible, String evidenceClassification,
        String diagnostic) {
    public ProductTestReceipt { artifacts = Map.copyOf(artifacts); }
    public enum Outcome { COMPLETED, REJECTED, FAILED_TO_LAUNCH, TIMED_OUT, OUTPUT_LIMIT, INVALID_EVIDENCE, FRAMEWORK_REJECTED }
    public record Artifact(String path, String sha256) {}
}
