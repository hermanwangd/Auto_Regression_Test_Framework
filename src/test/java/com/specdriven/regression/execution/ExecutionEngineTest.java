package com.specdriven.regression.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.adapter.DataPipelineAdapter;
import com.specdriven.regression.assertion.AssertionEngine;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.evidence.EvidenceWriter;
import com.specdriven.regression.provider.DatabaseFixtureProvider;
import com.specdriven.regression.provider.DeploymentReadinessProvider;
import com.specdriven.regression.provider.MessagingProvider;
import com.specdriven.regression.provider.ProviderContractResolutionReport;
import com.specdriven.regression.provider.ProviderContractResolver;
import com.specdriven.regression.provider.RequestResponseProvider;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void defaultProviderRuntimeRegistryResolvesNativeMessagingRuntimes() {
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(
                new DataPipelineAdapter(),
                new RequestResponseProvider(),
                new MessagingProvider(),
                new DeploymentReadinessProvider());

        assertThat(registry.runtimeFor(resolvedAdapter("messaging", "kafka"))).isNotNull();
        assertThat(registry.runtimeFor(resolvedAdapter("messaging", "nats"))).isNotNull();
    }

    @Test
    void blocksExecutionWhenProviderResolverDoesNotReturnAdapterContract() throws Exception {
        writeMinimalTestCase("TC-NO-ADAPTER");
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new EmptyProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of()));

        assertThatThrownBy(() -> engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-NO-ADAPTER.yaml"),
                "BATCH-NO-ADAPTER",
                "RUN-NO-ADAPTER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No resolved adapter provider contract available");
    }

    private ResolvedProviderContract resolvedAdapter(String providerFamily, String providerType) {
        return new ResolvedProviderContract(
                "adapter",
                providerFamily,
                "ru",
                providerFamily,
                providerType,
                "supported",
                "supported",
                "RU-native",
                providerFamily,
                "release_units[0].provider_contracts.adapters." + providerFamily);
    }

    @Test
    void failsExternalRunnerWhenMappedEvidenceIsMissing() throws Exception {
        writeExternalRunnerPackage("TC-EXTERNAL-MISSING", "external-output\n");
        ExecutionEngine engine = executionEngineWithExternalRunnerRuntime(false, "external-output\n");

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-EXTERNAL-MISSING.yaml"),
                "BATCH-EXTERNAL",
                "RUN-EXTERNAL-MISSING");

        Path runDir = tempDir.resolve("evidence/runs/RUN-EXTERNAL-MISSING");
        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(Files.readString(runDir.resolve("external_runner.yaml")))
                .contains("evidence_complete: false")
                .contains("runner_ref: registry.example.com/external-runner:1.0")
                .contains("missing_mapped_artifacts:")
                .contains("path: logs/external-runner.log");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: failed")
                .contains("external_runner: external_runner.yaml")
                .contains("assertion_status: not_run");
    }

    @Test
    void passesExternalRunnerWhenMappedEvidenceAndAssertionsPass() throws Exception {
        writeExternalRunnerPackage("TC-EXTERNAL-PASS", "external-output\n");
        ExecutionEngine engine = executionEngineWithExternalRunnerRuntime(true, "external-output\n");

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-EXTERNAL-PASS.yaml"),
                "BATCH-EXTERNAL",
                "RUN-EXTERNAL-PASS");

        Path runDir = tempDir.resolve("evidence/runs/RUN-EXTERNAL-PASS");
        assertThat(result.passed()).isTrue();
        assertThat(Files.readString(runDir.resolve("external_runner.yaml")))
                .contains("evidence_complete: true")
                .contains("runner_log: logs/external-runner.log")
                .contains("missing_mapped_artifacts:\n  []");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: passed")
                .contains("resolved_dependencies:\n  - RU-auth")
                .contains("assertion_status: passed");
    }

    private ExecutionEngine executionEngineWithExternalRunnerRuntime(boolean writeMappedEvidence, String actualOutput) {
        ProviderRuntime runtime = request -> {
            try {
                Files.createDirectories(request.stdoutLog().getParent());
                Files.createDirectories(request.stderrLog().getParent());
                Files.createDirectories(request.actualOutput().getParent());
                Files.writeString(request.stdoutLog(), "external runner selected\n");
                Files.writeString(request.stderrLog(), "");
                Files.writeString(request.actualOutput(), actualOutput);
                if (writeMappedEvidence) {
                    Files.writeString(request.runDir().resolve("logs/external-runner.log"), "runner evidence\n");
                }
                return new AdapterExecutionResult(
                        0,
                        false,
                        request.stdoutLog(),
                        request.stderrLog(),
                        request.actualOutput());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write fake external runner output.", e);
            }
        };
        return new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("external_runner/command_runner", runtime)));
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

    private void writeMinimalTestCase(String testCaseId) throws IOException {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.writeString(tempDir.resolve("tests/approved/" + testCaseId + ".yaml"), """
                test_case_id: %s
                ac_id: AC-NO-ADAPTER
                rp_id: RP-NO-ADAPTER
                execution_target:
                  ru_id: RU-api
                  adapter: request_response
                """.formatted(testCaseId));
    }

    private void writeExternalRunnerPackage(String testCaseId, String expectedOutput) throws IOException {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.createDirectories(tempDir.resolve("expected"));
        Files.createDirectories(tempDir.resolve("expected-results/approved"));
        Files.writeString(tempDir.resolve("expected/external-output.txt"), expectedOutput);
        Files.writeString(tempDir.resolve("expected-results/approved/ER-EXTERNAL.yaml"), """
                expected_result_id: ER-EXTERNAL
                status: approved
                expected_outputs:
                  output_ref: expected/external-output.txt
                """);
        Files.writeString(tempDir.resolve("tests/approved/" + testCaseId + ".yaml"), """
                test_case_id: %s
                ac_id: AC-EXTERNAL
                rp_id: RP-EXTERNAL
                title: External runner bridge
                status: approved
                execution_target:
                  ru_id: RU-external
                  adapter: external_runner
                expected:
                  ref: expected-results/approved/ER-EXTERNAL.yaml
                oracles:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/ER-EXTERNAL.yaml
                assertions:
                  - type: file_diff
                    oracle: ${oracles.primary}
                """.formatted(testCaseId));
        Files.writeString(tempDir.resolve("rp_ru_mapping.yaml"), """
                rp_id: RP-EXTERNAL
                release_units:
                  - ru_id: RU-external
                    repo: /repo/external
                    unit_type: external_test_runner
                    owner: qa
                    version_ref: runner-42
                    validation_boundary: external_runner_bridge
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://external
                    adapter: external_runner
                    provider_contracts:
                      adapters:
                        external_runner:
                          provider_family: external_runner
                          provider_type: command_runner
                          approval_ref: ADR-EXTERNAL
                          approved_by: SA
                          reason: legacy protocol bridge
                          container_ref: registry.example.com/external-runner:1.0
                          timeout_seconds: 10
                          inputs:
                            payload: fixtures/request.json
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                          evidence_map:
                            runner_log: logs/external-runner.log
                      fixtures: {}
                    evidence_responsibility: [runner_log]
                    dependencies: [RU-auth]
                """);
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

    private static class EmptyProviderContractResolver extends ProviderContractResolver {
        @Override
        public ProviderContractResolutionReport resolve(
                Path mappingYaml,
                String targetRuId,
                String adapter,
                List<String> bindingTypes,
                List<String> fixtureProviders) {
            return new ProviderContractResolutionReport(false, List.of(), List.of());
        }
    }
}
