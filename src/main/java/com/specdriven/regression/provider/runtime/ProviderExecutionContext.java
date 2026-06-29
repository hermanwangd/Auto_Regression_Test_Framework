package com.specdriven.regression.provider.runtime;

import java.nio.file.Path;
import java.util.Map;

public record ProviderExecutionContext(
        String providerId,
        String providerType,
        String profile,
        Path suiteRoot,
        Path runDir,
        Map<String, Object> providerContract,
        Map<String, Object> providerInstance,
        Map<String, Object> bindingValues) {
}
