package com.specdriven.regression.contract.v03;

/** Framework-private identity for a test-case-scoped step output. */
public record V03StepOutputKey(String testCaseId, String stepId, String outputPath) {
}
