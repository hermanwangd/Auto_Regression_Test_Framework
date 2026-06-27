package com.specdriven.regression.dsl;

import java.util.List;

public record DslValidationReport(
        boolean ready,
        String testCaseId,
        String acId,
        List<DslValidationGap> gaps) {
}
