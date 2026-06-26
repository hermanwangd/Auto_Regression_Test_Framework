package com.specdriven.regression.environment;

import java.util.List;

public record ExecutionEnvironmentReport(
        boolean ready,
        String executionMode,
        String environmentRef,
        List<ExecutionEnvironmentGap> gaps) {
}
