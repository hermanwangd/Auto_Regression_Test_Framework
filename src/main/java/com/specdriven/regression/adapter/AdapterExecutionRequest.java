package com.specdriven.regression.adapter;

import java.nio.file.Path;
import java.util.List;

public record AdapterExecutionRequest(
        String command,
        Path workingDirectory,
        int timeoutSeconds,
        List<Integer> successExitCodes,
        Path runDir,
        Path stdoutLog,
        Path stderrLog,
        Path actualOutput) {
}
