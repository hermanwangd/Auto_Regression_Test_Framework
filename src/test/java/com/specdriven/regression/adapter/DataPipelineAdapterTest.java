package com.specdriven.regression.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataPipelineAdapterTest {

    @TempDir
    Path tempDir;

    private final DataPipelineAdapter adapter = new DataPipelineAdapter();

    @Test
    void executesCommandAndCopiesStdoutToActualOutput() throws Exception {
        AdapterExecutionResult result = adapter.execute(request("printf 'ready\\n'", 0));

        assertThat(result.exitCode()).isZero();
        assertThat(result.timeout()).isFalse();
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("ready\n");
        assertThat(Files.readString(result.stderrLog())).isEmpty();
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
    }

    @Test
    void returnsNonZeroExitCodeAndStillCopiesStdout() throws Exception {
        AdapterExecutionResult result = adapter.execute(request("printf 'partial\\n'; printf 'failed\\n' >&2; exit 7", 1));

        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.timeout()).isFalse();
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("partial\n");
        assertThat(Files.readString(result.stderrLog())).isEqualTo("failed\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("partial\n");
    }

    @Test
    void timesOutLongRunningCommandAndReturnsMinusOneExitCode() {
        AdapterExecutionResult result = adapter.execute(request("sleep 2", 1));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timeout()).isTrue();
        assertThat(result.stdoutLog()).exists();
    }

    @Test
    void wrapsProcessStartIoFailure() {
        Path missingWorkingDirectory = tempDir.resolve("missing-workdir");

        assertThatThrownBy(() -> adapter.execute(new AdapterExecutionRequest(
                        "printf 'never runs'",
                        missingWorkingDirectory,
                        1,
                        List.of(0),
                        tempDir.resolve("run"),
                        tempDir.resolve("run/logs/stdout.log"),
                        tempDir.resolve("run/logs/stderr.log"),
                        tempDir.resolve("run/actual/output.txt"))))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to execute provider command.");
    }

    @Test
    void preservesInterruptedStatusWhenProcessWaitIsInterrupted() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> adapter.execute(request("sleep 1", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider execution interrupted.");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    private AdapterExecutionRequest request(String command, int timeoutSeconds) {
        return new AdapterExecutionRequest(
                command,
                tempDir,
                timeoutSeconds,
                List.of(0),
                tempDir.resolve("run"),
                tempDir.resolve("run/logs/stdout.log"),
                tempDir.resolve("run/logs/stderr.log"),
                tempDir.resolve("run/actual/output.txt"));
    }
}
