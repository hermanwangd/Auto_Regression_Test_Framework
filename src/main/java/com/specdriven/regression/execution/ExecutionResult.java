package com.specdriven.regression.execution;

import java.nio.file.Path;

public record ExecutionResult(
        String runId,
        String testCaseId,
        String acId,
        String status,
        int exitCode,
        boolean timeout,
        Path runDir,
        Path stdoutLog,
        Path stderrLog,
        Path actualOutput) {

    public boolean passed() {
        return "passed".equals(status);
    }
}
