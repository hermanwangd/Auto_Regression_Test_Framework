package com.specdriven.regression.dsl;

public record DslValidationGap(
        String testCaseId,
        String acId,
        String section,
        String fieldPath,
        String verifyId,
        String ownerAction) {
}
