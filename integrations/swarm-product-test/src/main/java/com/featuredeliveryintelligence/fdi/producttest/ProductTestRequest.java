package com.featuredeliveryintelligence.fdi.producttest;

import java.util.List;
import java.util.Map;

/** Reviewed inputs and correlation facts; executable paths and process limits are deployment configuration. */
public record ProductTestRequest(
        String contractVersion, String moduleId, String moduleVersion,
        String missionRef, String requestRef, String executionRevision, String workspaceRef, String projectRef,
        Map<String, String> repositoryRevisions, Map<String, List<String>> requirementTestMapping,
        String testLevel, String testType, String environmentStage, String knowledgeMode,
        String artifactRoot, String suiteManifest, String suiteId, String profile,
        String reviewedHandoff, String expectedHandoffSha256) {
    public ProductTestRequest {
        repositoryRevisions = repositoryRevisions == null ? Map.of() : Map.copyOf(repositoryRevisions);
        requirementTestMapping = requirementTestMapping == null ? Map.of() : requirementTestMapping.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
}
