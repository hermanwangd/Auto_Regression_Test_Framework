package com.specdriven.regression.adapter;

import java.nio.file.Path;

public record AdapterExecutionResult(
        int exitCode,
        boolean timeout,
        Path stdoutLog,
        Path stderrLog,
        Path actualOutput) {
}
