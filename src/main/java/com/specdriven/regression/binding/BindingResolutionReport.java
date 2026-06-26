package com.specdriven.regression.binding;

import java.util.List;

public record BindingResolutionReport(
        boolean ready,
        String testCaseId,
        String acId,
        List<ResolvedBinding> resolvedBindings,
        List<BindingGap> gaps) {
}
