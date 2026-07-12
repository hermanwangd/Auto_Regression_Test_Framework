package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbstractProviderRuntimeV03AdapterTest {

    @TempDir Path tempDir;

    @Test
    void preservesBlockedProviderOperationStatus() {
        AbstractProviderRuntimeV03Adapter adapter = new AbstractProviderRuntimeV03Adapter() { };
        V03ExecutionStep step = new V03ExecutionStep(
                "TC-1", "exercise", "call-provider", "provider", "provider.v0.3", "provider",
                "local", "external", "call", Map.of(), "");
        ProviderOperationResult blocked = ProviderOperationResult.blocked(
                Map.of(), List.of(), ProviderFailure.of(
                        "PROVIDER_UNAVAILABLE", "ENVIRONMENT_BLOCKED", "Unavailable", "Start provider"));

        assertThat(adapter.stepResult(step, blocked).status()).isEqualTo("blocked");
    }

    @Test
    void materializesArtifactAliasToSuiteRelativePhysicalPath() throws Exception {
        Path fixtures = tempDir.resolve("fixtures");
        Files.createDirectories(fixtures);
        Files.writeString(fixtures.resolve("input.json"), "{}");
        AbstractProviderRuntimeV03Adapter adapter = new AbstractProviderRuntimeV03Adapter() { };
        V03ExecutionStep step = new V03ExecutionStep(
                "TC-1", "execute", "call", "provider", "provider.v0.3", "provider",
                "local", "mock", "call", Map.of("body_ref", "artifact://payloads/input.json"), "");
        V03ExecutionContext context = context(
                Map.of("provider", new V03ResolvedTarget(
                        "provider", "provider.v0.3", "provider", "local", "mock", Map.of())),
                Map.of("payloads", fixtures),
                Map.of());

        Object ref = adapter.request(step, context).parameters().get(0).get("ref");

        assertThat(ref).isEqualTo(Path.of("fixtures", "input.json").toString());
    }

    @Test
    void failsClosedWhenGeneratedBindingIsUnavailable() {
        AbstractProviderRuntimeV03Adapter adapter = new AbstractProviderRuntimeV03Adapter() { };
        V03ExecutionStep step = new V03ExecutionStep(
                "TC-1", "execute", "call", "client", "client.v0.3", "client",
                "local", "mock", "call", Map.of(), "");
        V03ExecutionContext context = context(
                Map.of("client", new V03ResolvedTarget(
                        "client", "client.v0.3", "client", "local", "mock",
                        Map.of("base_url", "generated://mock/base_url"))),
                Map.of(),
                Map.of());

        assertThatThrownBy(() -> adapter.providerContext(step, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved_generated_ref");
    }

    private V03ExecutionContext context(
            Map<String, V03ResolvedTarget> targets,
            Map<String, Path> artifactRoots,
            Map<String, Map<String, Object>> generatedOutputs) {
        V03ReferenceResolver resolver = new V03ReferenceResolver(ignored -> Map.of());
        return new V03ExecutionContext(
                tempDir,
                tempDir.resolve("run"),
                "local",
                targets,
                artifactRoots,
                Map.of(),
                Map.of(),
                Map.of(),
                resolver,
                Instant.EPOCH);
    }

    @Test
    void rejectsStatusesOutsideFrozenProviderResultSet() {
        for (String status : new String[] {null, "", " ", "unknown", "timeout", "skipped"}) {
            assertThatThrownBy(() -> new ProviderOperationResult(status, Map.of(), List.of(), null))
                    .as("provider operation status %s", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("passed, failed, or blocked");

            assertThatThrownBy(() -> new V03StepResult("step", status, Map.of(), List.of(), "", ""))
                    .as("adapter result status %s", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("passed, failed, or blocked");
        }
    }
}
