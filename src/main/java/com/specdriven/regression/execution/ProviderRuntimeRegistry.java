package com.specdriven.regression.execution;

import com.specdriven.regression.adapter.AdapterExecutionRequest;
import com.specdriven.regression.adapter.DataPipelineAdapter;
import com.specdriven.regression.provider.DeploymentReadinessProvider;
import com.specdriven.regression.provider.MessagingProvider;
import com.specdriven.regression.provider.RequestResponseProvider;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class ProviderRuntimeRegistry {

    private final Map<String, ProviderRuntime> runtimes;

    public ProviderRuntimeRegistry(
            DataPipelineAdapter adapter,
            RequestResponseProvider requestResponseProvider,
            MessagingProvider messagingProvider,
            DeploymentReadinessProvider deploymentReadinessProvider) {
        this(defaultRuntimes(adapter, requestResponseProvider, messagingProvider, deploymentReadinessProvider));
    }

    public ProviderRuntimeRegistry(Map<String, ProviderRuntime> runtimes) {
        Map<String, ProviderRuntime> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderRuntime> entry : runtimes.entrySet()) {
            normalized.put(normalize(entry.getKey()), entry.getValue());
        }
        this.runtimes = Map.copyOf(normalized);
    }

    public ProviderRuntime runtimeFor(ResolvedProviderContract providerContract) {
        String key = key(providerContract.providerFamily(), providerContract.providerType());
        ProviderRuntime runtime = runtimes.get(key);
        if (runtime == null) {
            throw new IllegalStateException("No provider runtime registered for `" + key + "`.");
        }
        return runtime;
    }

    private static Map<String, ProviderRuntime> defaultRuntimes(
            DataPipelineAdapter adapter,
            RequestResponseProvider requestResponseProvider,
            MessagingProvider messagingProvider,
            DeploymentReadinessProvider deploymentReadinessProvider) {
        Map<String, ProviderRuntime> runtimes = new LinkedHashMap<>();
        ProviderRuntime commandRuntime = request -> adapter.execute(new AdapterExecutionRequest(
                stringValue(request.contract().get("command")),
                request.workingDirectory(),
                intValue(request.contract().get("timeout_seconds"), 300),
                request.successExitCodes(),
                request.runDir(),
                request.stdoutLog(),
                request.stderrLog(),
                request.actualOutput()));
        ProviderRuntime requestResponseRuntime = request -> requestResponseProvider.execute(
                request.packageRoot(),
                request.contract(),
                request.testCase(),
                request.resolvedBindings(),
                request.runDir(),
                request.stdoutLog(),
                request.stderrLog(),
                request.actualOutput());
        ProviderRuntime messagingRuntime = request -> messagingProvider.execute(
                request.providerName(),
                request.packageRoot(),
                request.contract(),
                request.testCase(),
                request.resolvedBindings(),
                request.runDir(),
                request.stdoutLog(),
                request.stderrLog(),
                request.actualOutput());
        ProviderRuntime readinessRuntime = request -> deploymentReadinessProvider.execute(
                request.providerName(),
                request.packageRoot(),
                request.contract(),
                request.runDir(),
                request.stdoutLog(),
                request.stderrLog(),
                request.actualOutput());

        runtimes.put(key("file_batch", "shell"), commandRuntime);
        runtimes.put(key("external_runner", "command_runner"), commandRuntime);
        runtimes.put(key("request_response", "rest"), requestResponseRuntime);
        runtimes.put(key("messaging", "local"), messagingRuntime);
        runtimes.put(key("messaging", "mock"), messagingRuntime);
        runtimes.put(key("messaging", "kafka"), messagingRuntime);
        runtimes.put(key("messaging", "nats"), messagingRuntime);
        runtimes.put(key("deployment_readiness", "local"), readinessRuntime);
        runtimes.put(key("deployment_readiness", "mock"), readinessRuntime);
        return runtimes;
    }

    private static String key(String providerFamily, String providerType) {
        return normalize(providerFamily) + "/" + normalize(providerType);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
