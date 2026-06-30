package com.specdriven.regression.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedRuntimeContextTest {

    @Test
    void matchingTargetsReturnsAllRunnerMatchesForAmbiguityDetection() {
        GeneratedRuntimeContext context = contextWithTargets(
                target("target-blue", "request_response"),
                target("target-green", "request_response"));

        assertThat(context.matchingTargets("", "request_response"))
                .extracting(GeneratedRuntimeTarget::targetId)
                .containsExactly("target-blue", "target-green");
    }

    @Test
    void targetDoesNotFallbackWhenExplicitTargetIsUnknown() {
        GeneratedRuntimeContext context = contextWithTargets(target("target-blue", "request_response"));

        assertThat(context.target("target-missing", "request_response")).isNull();
    }

    @Test
    void targetRequiresRunnerMatchWhenExplicitTargetIsKnown() {
        GeneratedRuntimeContext context = contextWithTargets(target("target-blue", "request_response"));

        assertThat(context.matchingTargets("target-blue", "messaging")).isEmpty();
        assertThat(context.target("target-blue", "messaging")).isNull();
    }

    @Test
    void targetFallsBackToOnlyTargetWhenTargetAndRunnerAreBlank() {
        GeneratedRuntimeContext context = contextWithTargets(target("target-blue", "request_response"));

        assertThat(context.target("", "")).extracting(GeneratedRuntimeTarget::targetId)
                .isEqualTo("target-blue");
        assertThat(context.matchingTargets("", "")).extracting(GeneratedRuntimeTarget::targetId)
                .containsExactly("target-blue");
    }

    @Test
    void targetDoesNotFallbackWhenTargetAndRunnerAreBlankButMultipleTargetsExist() {
        GeneratedRuntimeContext context = contextWithTargets(
                target("target-blue", "request_response"),
                target("target-green", "messaging"));

        assertThat(context.target("", "")).isNull();
        assertThat(context.matchingTargets("", "")).extracting(GeneratedRuntimeTarget::targetId)
                .containsExactly("target-blue", "target-green");
    }

    @Test
    void explicitTargetMatchesWhenRunnerIsBlank() {
        GeneratedRuntimeContext context = contextWithTargets(target("target-blue", "request_response"));

        assertThat(context.target("target-blue", "")).extracting(GeneratedRuntimeTarget::runner)
                .isEqualTo("request_response");
    }

    @Test
    void dependenciesAndOrderReturnDefaultsWhenTargetIsUnknown() {
        GeneratedRuntimeContext context = contextWithTargets(target("target-blue", "request_response"));

        assertThat(context.dependencies("target-missing", "request_response")).isEmpty();
        assertThat(context.order("target-missing", "request_response")).isEqualTo(Integer.MAX_VALUE);
    }

    private GeneratedRuntimeContext contextWithTargets(GeneratedRuntimeTarget... targets) {
        return new GeneratedRuntimeContext(
                true,
                "ci_ephemeral",
                "ci_ephemeral",
                "ci://sample",
                List.of(targets),
                List.of());
    }

    private GeneratedRuntimeTarget target(String targetId, String runner) {
        return new GeneratedRuntimeTarget(
                targetId,
                runner,
                "provider_contracts/" + targetId + ".yaml#providers." + runner,
                "ci://" + targetId,
                List.of(),
                0);
    }
}
