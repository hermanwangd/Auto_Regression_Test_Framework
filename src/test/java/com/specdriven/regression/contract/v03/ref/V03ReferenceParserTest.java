package com.specdriven.regression.contract.v03.ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V03ReferenceParserTest {

    private final V03ReferenceParser parser = new V03ReferenceParser();

    @Test
    void parsesGeneratedReferenceWithTargetAndOutput() {
        V03Reference.Generated reference = (V03Reference.Generated) parser.parse("generated://payment_mock/base_url");

        assertThat(reference.target()).isEqualTo("payment_mock");
        assertThat(reference.output()).isEqualTo("base_url");
    }

    @Test
    void parsesStepReferenceWithJsonPointer() {
        V03Reference.Step reference =
                (V03Reference.Step) parser.parse("step://call/response.body#/order/id");

        assertThat(reference.stepId()).isEqualTo("call");
        assertThat(reference.outputPath()).isEqualTo("response.body");
        assertThat(reference.jsonPointer()).isEqualTo("/order/id");
    }

    @Test
    void rejectsLegacyDotGeneratedReference() {
        assertThatThrownBy(() -> parser.parse("generated://payment_mock.base_url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_generated_ref");
    }

    @Test
    void blocksArtifactTraversalOutsideDeclaredRoot() {
        V03Reference.Artifact reference = (V03Reference.Artifact) parser.parse("artifact://fixtures/../secret.json");

        assertThatThrownBy(() -> new V03ReferenceResolver().artifactPath(
                reference, Map.of("fixtures", Path.of("samples/fixtures").toAbsolutePath().normalize())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ref_outside_suite_root");
    }
}
