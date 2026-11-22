package com.specdriven.regression.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteExecutionContextTest {

    @TempDir Path tempDir;

    @Test
    void reusesParentBatchAndAllocatesContainedUniqueChildRoots() {
        var context = new SuiteExecutionContext("BATCH-PARENT", "ci", Instant.parse("2026-07-11T00:00:00Z"), tempDir);

        String firstRun = context.newRunId("jdbc");
        String secondRun = context.newRunId("jdbc");

        assertThat(firstRun).isNotEqualTo(secondRun);
        assertThat(context.parentBatchId()).isEqualTo("BATCH-PARENT");
        assertThat(context.childRunRoot("SUITE-A", firstRun).toString())
                .startsWith(context.outputRoot().toString());
        assertThat(context.childRunRoot("SUITE-A", firstRun).toString()).contains("BATCH-PARENT");
    }

    @Test
    void rejectsInvalidRequiredContextValues() {
        assertThatThrownBy(() -> new SuiteExecutionContext(" ", "ci", Instant.now(), tempDir))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SuiteExecutionContext("BATCH", " ", Instant.now(), tempDir))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsChildRootThatEscapesThroughExistingSymbolicLink() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        Files.createSymbolicLink(tempDir.resolve("SUITE-LINK"), outside);
        var context = new SuiteExecutionContext("BATCH-PARENT", "ci", Instant.now(), tempDir);

        assertThatThrownBy(() -> context.childRunRoot("SUITE-LINK", context.newRunId("REST")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }
}
