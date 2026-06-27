package com.specdriven.regression.execution;

import com.specdriven.regression.adapter.AdapterExecutionResult;

@FunctionalInterface
public interface ProviderRuntime {

    AdapterExecutionResult execute(ProviderRuntimeRequest request);
}
