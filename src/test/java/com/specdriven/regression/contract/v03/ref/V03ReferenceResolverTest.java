package com.specdriven.regression.contract.v03.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.specdriven.regression.contract.v03.V03ProducedOutput;
import com.specdriven.regression.contract.v03.V03Sensitivity;
import com.specdriven.regression.contract.v03.V03ValueType;
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
        V03ReferenceResolutionContext context = context(Map.of(), Map.of());

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
                Map.of("TOKEN", "secret"),
                Map.of(
                        "TC-1\ncall\nresponse.body", output("call", "api", "response.body", Map.of("id", "S-1"), false),
                        "TC-1\nmock-start\nbase_url", output("mock-start", "mock", "base_url", "http://localhost", true)));

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
        V03ReferenceResolutionContext context = context(Map.of(), Map.of());

        assertThatThrownBy(() -> resolver.resolveValue(
                parser.parse("generated://mock/base_url"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unresolved_generated_ref");
        assertThatThrownBy(() -> resolver.resolveValue(parser.parse("env://TOKEN"), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing_environment_value");
    }

    @Test
    void rejectsRawOutputMapsThatDoNotHaveProviderOutputProvenance() {
        V03ReferenceResolver resolver = new V03ReferenceResolver(ignored -> Map.of());
        V03ReferenceResolutionContext context = context(Map.of(), Map.of());

        assertThatThrownBy(() -> resolver.resolveValue(parser.parse("step://call/response.body"), context))
                .hasMessageContaining("unresolved_step_ref");
        assertThatThrownBy(() -> resolver.resolveValue(parser.parse("generated://mock/base_url"), context))
                .hasMessageContaining("unresolved_generated_ref");
    }

    private V03ReferenceResolutionContext context(
            Map<String, String> environment,
            Map<String, V03ProducedOutput> producedOutputs) {
        return new V03ReferenceResolutionContext(
                tempDir,
                Map.of("payloads", tempDir.resolve("fixtures")),
                "TC-1",
                producedOutputs,
                environment);
    }

    private V03ProducedOutput output(String step, String target, String name, Object value, boolean bindable) {
        return new V03ProducedOutput("TC-1", step, target, "test.v0.3", "operation", name,
                V03ValueType.ANY, V03Sensitivity.PUBLIC, bindable, value);
    }
}
