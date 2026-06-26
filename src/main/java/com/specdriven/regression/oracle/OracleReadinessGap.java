package com.specdriven.regression.oracle;

public record OracleReadinessGap(
        String testCaseId,
        String acId,
        String fieldPath,
        String oracleType,
        String ownerAction) {
}
