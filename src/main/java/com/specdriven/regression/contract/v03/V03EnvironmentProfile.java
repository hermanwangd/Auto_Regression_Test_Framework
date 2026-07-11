package com.specdriven.regression.contract.v03;

/** Runtime-safe profile metadata retained after v0.3 YAML compilation. */
public record V03EnvironmentProfile(
        String profileId,
        String executionMode,
        String isolation,
        String evidenceClassification,
        boolean downstreamReleaseEvidence) {
}
