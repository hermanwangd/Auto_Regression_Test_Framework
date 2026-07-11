package com.specdriven.regression.contract.v03.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V03ReferenceResolverTest {

    @TempDir Path tempDir;
    private final V03ReferenceParser parser = new V03ReferenceParser();

    @Test
    void resolvesArtifactAliasAndJsonPointer() throws Exception {
        Path fixtures = tempDir.resolve("fixtures");
        Files.createDirectories(fixtures);
        Files.writeString(fixtures.resolve("order.json"), "{\"order\":{\"id\":\"A-100\"}}");
        V03ReferenceResolver resolver = new V03ReferenceResolver(
                ignored -> Map.of("order", Map.of("id", "A-100")));
        V03ReferenceResolutionContext context = context(Map.of(), Map.of(), Map.of());

        V03Reference.Artifact reference =
                (V03Reference.Artifact) parser.parse("artifact://payloads/order.json#/order/id");

        assertThat(resolver.artifactPath(reference, context))
                .isEqualTo(fixtures.resolve("order.json").toRealPath());
        assertThat(resolver.resolveValue(reference, context)).isEqualTo("A-100");
        assertThat(resolver.resolveProviderValue(
                parser.parse("artifact://payloads/order.json"), context))
                .isEqualTo(Path.of("fixtures", "order.json").toString());
    }

    @Test
    void resolvesStepGeneratedAndEnvironmentReferences() {
        V03ReferenceResolver resolver = new V03ReferenceResolver(ignored -> Map.of());
        V03ReferenceResolutionContext context = context(
                Map.of("TC-1\ncall", Map.of("response.body", Map.of("id", "S-1"))),
                Map.of("mock", Map.of("base_url", "http://localhost")),
                Map.of("TOKEN", "secret"));

        assertThat(resolver.resolveValue(parser.parse("step://call/response.body#/id"), context))
                .isEqualTo("S-1");
        assertThat(resolver.resolveValue(parser.parse("generated://mock/base_url"), context))
                .isEqualTo("http://localhost");
        assertThat(resolver.resolveValue(parser.parse("env://TOKEN"), context))
                .isEqualTo("secret");
    }

    @Test
    void failsClosedForMissingGeneratedAndEnvironmentValues() {
        V03ReferenceResolver resolver = new V03ReferenceResolver(ignored -> Map.of());
        V03ReferenceResolutionContext context = context(Map.of(), Map.of(), Map.of());

        assertThatThrownBy(() -> resolver.resolveValue(
                parser.parse("generated://mock/base_url"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved_generated_ref");
        assertThatThrownBy(() -> resolver.resolveValue(parser.parse("env://TOKEN"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing_environment_value");
    }

    private V03ReferenceResolutionContext context(
            Map<String, Map<String, Object>> stepOutputs,
            Map<String, Map<String, Object>> generatedOutputs,
            Map<String, String> environment) {
        return new V03ReferenceResolutionContext(
                tempDir,
                Map.of("payloads", tempDir.resolve("fixtures")),
                "TC-1",
                stepOutputs,
                generatedOutputs,
                environment);
    }
}
