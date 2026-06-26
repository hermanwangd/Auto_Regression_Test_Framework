package com.specdriven.regression.provider;

import java.util.List;

public record ProviderContractResolutionReport(
        boolean ready,
        List<ResolvedProviderContract> resolvedContracts,
        List<ProviderContractGap> gaps) {
}
