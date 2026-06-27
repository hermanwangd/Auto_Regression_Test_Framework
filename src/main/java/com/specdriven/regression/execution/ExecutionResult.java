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
        Path actualOutput,
        String parameterCaseId) {

    public ExecutionResult(
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
        this(runId, testCaseId, acId, status, exitCode, timeout, runDir, stdoutLog, stderrLog, actualOutput, "");
    }

    public boolean passed() {
        return "passed".equals(status);
    }
}
