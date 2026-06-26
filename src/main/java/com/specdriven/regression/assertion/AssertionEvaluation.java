package com.specdriven.regression.assertion;

import java.nio.file.Path;

public record AssertionEvaluation(
        boolean passed,
        String status,
        String assertionType,
        String oracleReference,
        String expectedRef,
        String actualRef,
        String decisionRule,
        String diffSummary,
        Path evidencePath) {
}
