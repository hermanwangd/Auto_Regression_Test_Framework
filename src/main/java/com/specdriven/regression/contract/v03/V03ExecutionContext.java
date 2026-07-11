package com.specdriven.regression.contract.v03;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record V03ExecutionContext(
        Path suiteRoot,
        Path runDir,
        String profile,
        Map<String, V03ResolvedTarget> targets,
        Map<String, Map<String, Object>> generatedOutputsByTarget,
        Map<String, Object> envProfile,
        Instant testStartTime) {
}
