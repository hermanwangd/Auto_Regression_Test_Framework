package com.specdriven.regression.provider.runtime;

@FunctionalInterface
public interface ProviderRuntime {

    ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request);
}
