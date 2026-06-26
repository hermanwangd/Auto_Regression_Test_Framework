package com.specdriven.regression.productrepo;

import java.util.List;

public record ProductRepoReadinessReport(
        boolean ready,
        String status,
        List<String> checkedItems,
        List<ReadinessGap> gaps,
        String nextRequiredStep,
        boolean rpScopeInvented,
        boolean rpRuMembershipInvented) {
}
