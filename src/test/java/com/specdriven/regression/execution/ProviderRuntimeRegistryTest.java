package com.specdriven.regression.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.adapter.AdapterExecutionRequest;
import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.adapter.DataPipelineAdapter;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.provider.DeploymentReadinessProvider;
import com.specdriven.regression.provider.MessagingProvider;
import com.specdriven.regression.provider.RequestResponseProvider;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderRuntimeRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizesCustomRuntimeKeysAndRejectsMissingRuntime() {
        ProviderRuntime runtime = request -> result();
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(Map.of("Messaging/Kafka", runtime));

        assertThat(registry.runtimeFor(contract("MESSAGING", "KAFKA"))).isSameAs(runtime);
        assertThatThrownBy(() -> registry.runtimeFor(contract(null, "rest")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No provider runtime registered for `/rest`.");
    }

    @Test
    void routesCommandRuntimeToDataPipelineAdapterAndNormalizesCommandContracts() {
        RecordingDataPipelineAdapter adapter = new RecordingDataPipelineAdapter();
        ProviderRuntimeRegistry registry = registry(adapter);

        registry.runtimeFor(contract("file_batch", "shell"))
                .execute(runtimeRequest(
                        contract("file_batch", "shell"),
                        Map.of("command", "run-regression.sh", "timeout_seconds", 7)));
        registry.runtimeFor(contract("external_runner", "command_runner"))
                .execute(runtimeRequest(
                        contract("external_runner", "command_runner"),
                        Map.of("command", "run-external.sh", "timeout_seconds", "12")));
        registry.runtimeFor(contract("external_runner", "command_runner"))
                .execute(runtimeRequest(contract("external_runner", "command_runner"), Map.of()));

        assertThat(adapter.requests).hasSize(3);
        assertThat(adapter.requests.get(0).command()).isEqualTo("run-regression.sh");
        assertThat(adapter.requests.get(0).timeoutSeconds()).isEqualTo(7);
        assertThat(adapter.requests.get(1).command()).isEqualTo("run-external.sh");
        assertThat(adapter.requests.get(1).timeoutSeconds()).isEqualTo(12);
        assertThat(adapter.requests.get(2).command()).isEmpty();
        assertThat(adapter.requests.get(2).timeoutSeconds()).isEqualTo(300);
        assertThat(adapter.requests.get(2).successExitCodes()).containsExactly(0, 2);
    }

    @Test
    void routesRequestResponseRuntimeForRestAndGrpcProviders() {
        RecordingRequestResponseProvider requestResponseProvider = new RecordingRequestResponseProvider();
        ProviderRuntimeRegistry registry = registry(requestResponseProvider);

        registry.runtimeFor(contract("request_response", "rest"))
                .execute(runtimeRequest(contract("request_response", "rest"), Map.of("provider_type", "rest")));
        registry.runtimeFor(contract("request_response", "grpc"))
                .execute(runtimeRequest(contract("request_response", "grpc"), Map.of("provider_type", "grpc")));

        assertThat(requestResponseProvider.providerTypes).containsExactly("rest", "grpc");
    }

    @Test
    void routesMessagingRuntimeForSupportedMessagingProviders() {
        RecordingMessagingProvider messagingProvider = new RecordingMessagingProvider();
        ProviderRuntimeRegistry registry = registry(messagingProvider);

        for (String providerType : List.of("local", "mock", "kafka", "nats")) {
            registry.runtimeFor(contract("messaging", providerType))
                    .execute(runtimeRequest(contract("messaging", providerType), Map.of("provider_type", providerType)));
        }

        assertThat(messagingProvider.providerTypes).containsExactly("local", "mock", "kafka", "nats");
        assertThat(messagingProvider.providerNames).containsExactly(
                "messaging_local",
                "messaging_mock",
                "messaging_kafka",
                "messaging_nats");
    }

    @Test
    void routesDeploymentReadinessRuntimeForSupportedReadinessProviders() {
        RecordingDeploymentReadinessProvider readinessProvider = new RecordingDeploymentReadinessProvider();
        ProviderRuntimeRegistry registry = registry(readinessProvider);

        for (String providerType : List.of("local", "mock", "k8s", "vm")) {
            registry.runtimeFor(contract("deployment_readiness", providerType))
                    .execute(runtimeRequest(
                            contract("deployment_readiness", providerType),
                            Map.of("provider_type", providerType)));
        }

        assertThat(readinessProvider.providerTypes).containsExactly("local", "mock", "k8s", "vm");
        assertThat(readinessProvider.providerNames).containsExactly(
                "deployment_readiness_local",
                "deployment_readiness_mock",
                "deployment_readiness_k8s",
                "deployment_readiness_vm");
    }

    private ProviderRuntimeRegistry registry(DataPipelineAdapter adapter) {
        return new ProviderRuntimeRegistry(
                adapter,
                new RecordingRequestResponseProvider(),
                new RecordingMessagingProvider(),
                new RecordingDeploymentReadinessProvider());
    }

    private ProviderRuntimeRegistry registry(RequestResponseProvider requestResponseProvider) {
        return new ProviderRuntimeRegistry(
                new RecordingDataPipelineAdapter(),
                requestResponseProvider,
                new RecordingMessagingProvider(),
                new RecordingDeploymentReadinessProvider());
    }

    private ProviderRuntimeRegistry registry(MessagingProvider messagingProvider) {
        return new ProviderRuntimeRegistry(
                new RecordingDataPipelineAdapter(),
                new RecordingRequestResponseProvider(),
                messagingProvider,
                new RecordingDeploymentReadinessProvider());
    }

    private ProviderRuntimeRegistry registry(DeploymentReadinessProvider readinessProvider) {
        return new ProviderRuntimeRegistry(
                new RecordingDataPipelineAdapter(),
                new RecordingRequestResponseProvider(),
                new RecordingMessagingProvider(),
                readinessProvider);
    }

    private ResolvedProviderContract contract(String providerFamily, String providerType) {
        return new ResolvedProviderContract(
                "provider",
                providerFamily + "_" + providerType,
                "rp",
                providerFamily,
                providerType,
                "supported",
                "supported",
                "payment-service",
                "regression",
                "docs/02-architecture/contracts/provider.yaml");
    }

    private ProviderRuntimeRequest runtimeRequest(
            ResolvedProviderContract providerContract,
            Map<String, Object> contract) {
        return new ProviderRuntimeRequest(
                providerContract,
                tempDir,
                contract,
                Map.of("id", "TC-001"),
                List.<ResolvedBinding>of(),
                tempDir,
                tempDir.resolve("run"),
                tempDir.resolve("run/logs/stdout.log"),
                tempDir.resolve("run/logs/stderr.log"),
                tempDir.resolve("run/actual/output.txt"),
                List.of(0, 2));
    }

    private AdapterExecutionResult result() {
        return new AdapterExecutionResult(
                0,
                false,
                tempDir.resolve("stdout.log"),
                tempDir.resolve("stderr.log"),
                tempDir.resolve("actual.txt"));
    }

    private final class RecordingDataPipelineAdapter extends DataPipelineAdapter {

        private final List<AdapterExecutionRequest> requests = new ArrayList<>();

        @Override
        public AdapterExecutionResult execute(AdapterExecutionRequest request) {
            requests.add(request);
            return result();
        }
    }

    private final class RecordingRequestResponseProvider extends RequestResponseProvider {

        private final List<String> providerTypes = new ArrayList<>();

        @Override
        public AdapterExecutionResult execute(
                Path packageRoot,
                Map<String, Object> contract,
                Map<String, Object> testCase,
                List<ResolvedBinding> resolvedBindings,
                Path runDir,
                Path stdoutLog,
                Path stderrLog,
                Path actualOutput) {
            providerTypes.add(contract.get("provider_type").toString());
            return result();
        }
    }

    private final class RecordingMessagingProvider extends MessagingProvider {

        private final List<String> providerNames = new ArrayList<>();
        private final List<String> providerTypes = new ArrayList<>();

        @Override
        public AdapterExecutionResult execute(
                String providerName,
                Path packageRoot,
                Map<String, Object> contract,
                Map<String, Object> testCase,
                List<ResolvedBinding> resolvedBindings,
                Path runDir,
                Path stdoutLog,
                Path stderrLog,
                Path actualOutput) {
            providerNames.add(providerName);
            providerTypes.add(contract.get("provider_type").toString());
            return result();
        }
    }

    private final class RecordingDeploymentReadinessProvider extends DeploymentReadinessProvider {

        private final List<String> providerNames = new ArrayList<>();
        private final List<String> providerTypes = new ArrayList<>();

        @Override
        public AdapterExecutionResult execute(
                String providerName,
                Path packageRoot,
                Map<String, Object> contract,
                Path runDir,
                Path stdoutLog,
                Path stderrLog,
                Path actualOutput) {
            providerNames.add(providerName);
            providerTypes.add(contract.get("provider_type").toString());
            return result();
        }
    }
}
