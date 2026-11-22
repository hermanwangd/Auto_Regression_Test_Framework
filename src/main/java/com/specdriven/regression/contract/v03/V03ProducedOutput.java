package com.specdriven.regression.contract.v03;

/** Runtime-owned provenance for one output emitted by one provider operation. */
public record V03ProducedOutput(
        String testCaseId,
        String stepId,
        String target,
        String providerContract,
        String operation,
        String outputName,
        V03ValueType valueType,
        V03Sensitivity sensitivity,
        boolean bindable,
        Object value) {
}
