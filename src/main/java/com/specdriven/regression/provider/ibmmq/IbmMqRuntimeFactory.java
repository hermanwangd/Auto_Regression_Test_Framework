package com.specdriven.regression.provider.ibmmq;

import com.specdriven.regression.build.RunnerBuildVariant;
import com.specdriven.regression.provider.runtime.ProviderRuntime;

/** Keeps omitted SDK linkage outside every default adapter/capability construction path. */
public final class IbmMqRuntimeFactory {
    private IbmMqRuntimeFactory() { }
    public static ProviderRuntime create() {
        if (!RunnerBuildVariant.ibmMqAvailable()) return (context, request) -> {
            throw new UnsupportedOperationException(RunnerBuildVariant.mqUnsupportedReason());
        };
        return FullSdkRuntime.create();
    }
    private static final class FullSdkRuntime {
        private static ProviderRuntime create() { return new IbmMqProviderRuntime(); }
    }
}
