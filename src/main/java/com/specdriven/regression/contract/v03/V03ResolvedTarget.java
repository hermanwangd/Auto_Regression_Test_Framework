package com.specdriven.regression.contract.v03;

import java.util.Map;

public record V03ResolvedTarget(
        String target,
        String providerContract,
        String providerType,
        String profile,
        String runtimeMode,
        Map<String, Object> bindings) {
}
