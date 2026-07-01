package com.specdriven.regression.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OracleResolverTest {

    @TempDir
    Path tempDir;

    private final OracleResolver resolver = new OracleResolver();

    @Test
    void resolvesSparseExpectedResultArtifactWithBlankOptionalFields() throws Exception {
        Files.writeString(tempDir.resolve("expected.yaml"), """
                expected_outputs: {}
                """);

        ResolvedOracle oracle = resolver.resolveExpectedResultArtifact(
                tempDir,
                "primary",
                "${oracles.primary}",
                Map.of("ref", "expected.yaml"));

        assertThat(oracle.name()).isEqualTo("primary");
        assertThat(oracle.type()).isEmpty();
        assertThat(oracle.oracleReference()).isEqualTo("${oracles.primary}");
        assertThat(oracle.expectedRef()).isEmpty();
        assertThat(oracle.expectedPath()).isEqualTo(tempDir);
    }

    @Test
    void resolvesDirectExpectedPayloadWhenWrapperOutputIsAbsent() throws Exception {
        Files.writeString(tempDir.resolve("expected.json"), """
                {"status":"OK"}
                """);

        ResolvedOracle oracle = resolver.resolveExpectedResultArtifact(
                tempDir,
                "primary",
                "${oracles.primary}",
                Map.of("ref", "expected.json"));

        assertThat(oracle.expectedRef()).isEqualTo("expected.json");
        assertThat(oracle.expectedPath()).isEqualTo(tempDir.resolve("expected.json"));
    }

    @Test
    void throwsUncheckedIoWhenExpectedResultArtifactCannotBeRead() throws Exception {
        Files.createDirectories(tempDir.resolve("expected.yaml"));

        assertThatThrownBy(() -> resolver.resolveExpectedResultArtifact(
                tempDir,
                "primary",
                "${oracles.primary}",
                Map.of("ref", "expected.yaml", "type", "expected_result_artifact")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read oracle artifact");
    }
}
