package com.specdriven.regression.runtime;

import java.util.List;

public record GeneratedRuntimeTarget(
        String targetId,
        String runner,
        String providerContractRef,
        String environmentRef,
        List<String> dependencies,
        int order) {
}
