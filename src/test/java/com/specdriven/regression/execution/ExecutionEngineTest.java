package com.specdriven.regression.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.adapter.DataPipelineAdapter;
import com.specdriven.regression.assertion.AssertionEngine;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.evidence.EvidenceWriter;
import com.specdriven.regression.provider.DatabaseFixtureProvider;
import com.specdriven.regression.provider.DeploymentReadinessProvider;
import com.specdriven.regression.provider.MessagingProvider;
import com.specdriven.regression.provider.ProviderContractResolver;
import com.specdriven.regression.provider.RequestResponseProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void executesSelectedProviderRuntimeFromRegistry() throws Exception {
        writeRequestResponsePackage();
        AtomicBoolean runtimeCalled = new AtomicBoolean(false);
        ProviderRuntime fakeRuntime = request -> {
            runtimeCalled.set(true);
            writeRuntimeOutput(request);
            return new AdapterExecutionResult(0, false, request.stdoutLog(), request.stderrLog(), request.actualOutput());
        };
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(Map.of("request_response/rest", fakeRuntime));
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new DatabaseFixtureProvider(),
                registry);

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-REGISTRY-001.yaml"),
                "BATCH-REGISTRY",
                "RUN-REGISTRY");

        assertThat(runtimeCalled).isTrue();
        assertThat(result.passed()).isTrue();
        assertThat(Files.readString(result.actualOutput())).isEqualTo("registry-runtime\n");
    }

    @Test
    void publicConstructorsRemainAvailableForFrameworkComposition() {
        DataPipelineAdapter adapter = new DataPipelineAdapter();
        AssertionEngine assertionEngine = new AssertionEngine();
        EvidenceWriter evidenceWriter = new EvidenceWriter();
        BindingResolver bindingResolver = new BindingResolver();
        ProviderContractResolver contractResolver = new ProviderContractResolver();
        RequestResponseProvider requestResponseProvider = new RequestResponseProvider();
        DatabaseFixtureProvider databaseFixtureProvider = new DatabaseFixtureProvider();
        MessagingProvider messagingProvider = new MessagingProvider();

        assertThat(new ExecutionEngine()).isNotNull();
        assertThat(new ExecutionEngine(adapter)).isNotNull();
        assertThat(new ExecutionEngine(adapter, assertionEngine)).isNotNull();
        assertThat(new ExecutionEngine(adapter, assertionEngine, evidenceWriter)).isNotNull();
        assertThat(new ExecutionEngine(adapter, assertionEngine, evidenceWriter, bindingResolver, contractResolver))
                .isNotNull();
        assertThat(new ExecutionEngine(
                adapter,
                assertionEngine,
                evidenceWriter,
                bindingResolver,
                contractResolver,
                requestResponseProvider))
                .isNotNull();
        assertThat(new ExecutionEngine(
                adapter,
                assertionEngine,
                evidenceWriter,
                bindingResolver,
                contractResolver,
                requestResponseProvider,
                databaseFixtureProvider))
                .isNotNull();
        assertThat(new ExecutionEngine(
                adapter,
                assertionEngine,
                evidenceWriter,
                bindingResolver,
                contractResolver,
                requestResponseProvider,
                databaseFixtureProvider,
                messagingProvider))
                .isNotNull();
        assertThat(new ExecutionEngine(
                adapter,
                assertionEngine,
                evidenceWriter,
                bindingResolver,
                contractResolver,
                requestResponseProvider,
                databaseFixtureProvider,
                messagingProvider,
                new DeploymentReadinessProvider()))
                .isNotNull();
    }

    private void writeRuntimeOutput(ProviderRuntimeRequest request) {
        try {
            Files.createDirectories(request.stdoutLog().getParent());
            Files.createDirectories(request.stderrLog().getParent());
            Files.createDirectories(request.actualOutput().getParent());
            Files.writeString(request.stdoutLog(), "registry runtime selected\n");
            Files.writeString(request.stderrLog(), "");
            Files.writeString(request.actualOutput(), "registry-runtime\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write fake runtime output.", e);
        }
    }

    private void writeRequestResponsePackage() throws IOException {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.createDirectories(tempDir.resolve("fixtures"));
        Files.createDirectories(tempDir.resolve("expected"));
        Files.createDirectories(tempDir.resolve("expected-results/approved"));
        Files.writeString(tempDir.resolve("fixtures/request.json"), "{\"request\":\"ok\"}\n");
        Files.writeString(tempDir.resolve("expected/response.txt"), "registry-runtime\n");
        Files.writeString(tempDir.resolve("expected-results/approved/ER-REGISTRY-001.yaml"), """
                expected_result_id: ER-REGISTRY-001
                status: approved
                expected_outputs:
                  output_ref: expected/response.txt
                """);
        Files.writeString(tempDir.resolve("tests/approved/TC-REGISTRY-001.yaml"), """
                test_case_id: TC-REGISTRY-001
                ac_id: AC-REGISTRY-001
                rp_id: RP-REGISTRY
                title: Registry runtime dispatch
                status: approved
                execution_target:
                  ru_id: RU-api
                  adapter: request_response
                package_inputs:
                  inputs:
                    api_payload:
                      bind_as: api_payload
                      ref: fixtures/request.json
                steps:
                  - action: submit
                    input: ${inputs.api_payload}
                expected:
                  ref: expected-results/approved/ER-REGISTRY-001.yaml
                oracles:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/ER-REGISTRY-001.yaml
                assertions:
                  - type: file_diff
                    oracle: ${oracles.primary}
                """);
        Files.writeString(tempDir.resolve("rp_ru_mapping.yaml"), """
                rp_id: RP-REGISTRY
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://api
                    adapter: request_response
                    provider_contracts:
                      adapters:
                        request_response:
                          provider_family: request_response
                          provider_type: rest
                          endpoint_ref: http://127.0.0.1:1
                          timeout_seconds: 1
                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/response.txt
                          actions:
                            submit:
                              method: POST
                              path: /submit
                              request_binding: api_payload
                      bindings:
                        api_payload:
                          provider_family: request_response
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """);
    }
}
