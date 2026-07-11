package com.specdriven.regression.contract.v03;

import java.util.List;
import java.util.Map;

public record V03CompiledTestCase(
        String testCaseId,
        Map<String, Object> document,
        List<V03ExecutionStep> setup,
        List<V03ExecutionStep> execute,
        List<V03ExecutionStep> verify,
        List<V03ExecutionStep> cleanup) {
}
