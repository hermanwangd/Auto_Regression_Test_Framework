package com.specdriven.regression.execution;

import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ProviderRuntimeRequest(
        ResolvedProviderContract providerContract,
        Path packageRoot,
        Map<String, Object> contract,
        Map<String, Object> testCase,
        List<ResolvedBinding> resolvedBindings,
        Path workingDirectory,
        Path runDir,
        Path stdoutLog,
        Path stderrLog,
        Path actualOutput,
        List<Integer> successExitCodes) {

    public String providerName() {
        return providerContract.providerName();
    }
}
