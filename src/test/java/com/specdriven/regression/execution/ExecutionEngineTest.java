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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

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
    void executesLegacyTraceabilityDslThroughRuntimeCompatibilityLayer() throws Exception {
        writeRequestResponsePackage();
        Files.writeString(tempDir.resolve("tests/approved/TC-REGISTRY-001.yaml"), """
                dsl_version: v1
                test_case_id: TC-REGISTRY-001
                status: approved_for_regression
                revision: 1
                traceability:
                  package_id: RP-REGISTRY
                  acceptance_criteria_id: AC-REGISTRY-001
                  source: acceptance_criteria.md#AC-REGISTRY-001
                targets:
                  RU-api:
                    type: application
                    runner: request_response
                    environment: ci://api
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [api_payload, response_assertion]
                setup:
                  fixtures: {}
                execute:
                  - id: submit_payment
                    target: RU-api
                    operation: submit
                    with:
                      api_payload:
                        type: api_payload
                        ref: fixtures/request.json
                    outputs:
                      actual_response:
                        ref: actual/response.txt
                expected_results:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/ER-REGISTRY-001.yaml
                verify:
                  - type: file_diff
                    actual: ${execute.submit_payment.outputs.actual_response}
                    expected: ${expected_results.primary.ref}
                evidence:
                  required: [execution_log, assertion_result]
                runtime:
                  cleanup_required: false
                  destructive_actions_allowed: false
                """);
        AtomicBoolean runtimeCalled = new AtomicBoolean(false);
        ProviderRuntime fakeRuntime = request -> {
            runtimeCalled.set(true);
            writeRuntimeOutput(request);
            return new AdapterExecutionResult(0, false, request.stdoutLog(), request.stderrLog(), request.actualOutput());
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("request_response/rest", fakeRuntime)));

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-REGISTRY-001.yaml"),
                "BATCH-DSL-V1",
                "RUN-DSL-V1");

        assertThat(runtimeCalled).isTrue();
        assertThat(result.passed()).isTrue();
        assertThat(result.acId()).isEqualTo("AC-REGISTRY-001");
        assertThat(Files.readString(tempDir.resolve("evidence/runs/RUN-DSL-V1/run.yaml")))
                .contains("rp_id: RP-REGISTRY")
                .contains("ac_id: AC-REGISTRY-001")
                .contains("ru_refs:\n  - RU-api")
                .contains("binding_name: api_payload")
                .contains("status: passed");
    }

    @Test
    void evaluatesJsonPathEqualsAssertionAgainstProviderActualOutput() throws Exception {
        writeJsonResponseAssertionPackage();
        ProviderRuntime fakeRuntime = request -> {
            try {
                Files.createDirectories(request.stdoutLog().getParent());
                Files.createDirectories(request.stderrLog().getParent());
                Files.createDirectories(request.actualOutput().getParent());
                Files.writeString(request.stdoutLog(), "response captured\n");
                Files.writeString(request.stderrLog(), "");
                Files.writeString(request.actualOutput(), "{\"status\":\"APPROVED\",\"paymentId\":\"PAY-001\"}\n");
                return new AdapterExecutionResult(0, false, request.stdoutLog(), request.stderrLog(), request.actualOutput());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write fake JSON response.", e);
            }
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("request_response/rest", fakeRuntime)));

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-JSON-PATH-001.yaml"),
                "BATCH-JSON-PATH",
                "RUN-JSON-PATH");

        Path runDir = tempDir.resolve("evidence/runs/RUN-JSON-PATH");
        assertThat(result.passed()).isTrue();
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("type: json_path_equals")
                .contains("expected_ref: inline:APPROVED")
                .contains("actual_ref: actual/response.json")
                .contains("decision_rule: json_path_equals")
                .contains("json path `$.status` matched `APPROVED`");
    }

    @Test
    void evaluatesAllResponseAssertionsAgainstProviderActualOutput() throws Exception {
        writeCompositeResponseAssertionPackage();
        ProviderRuntime fakeRuntime = request -> {
            try {
                Files.createDirectories(request.stdoutLog().getParent());
                Files.createDirectories(request.stderrLog().getParent());
                Files.createDirectories(request.actualOutput().getParent());
                Files.writeString(request.stdoutLog(), "response captured\n");
                Files.writeString(request.stderrLog(), "");
                Files.writeString(request.actualOutput(), """
                        {"status":"ACCEPTED","riskScore":0.049}
                        """);
                Files.writeString(request.runDir().resolve("request_response.yaml"), """
                        provider_family: request_response
                        provider_type: rest
                        status: passed
                        http_status: 202
                        actual_output: actual/response.json
                        """);
                return new AdapterExecutionResult(0, false, request.stdoutLog(), request.stderrLog(), request.actualOutput());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write fake composite JSON response.", e);
            }
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("request_response/rest", fakeRuntime)));

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-RESPONSE-ASSERTIONS-001.yaml"),
                "BATCH-RESPONSE-ASSERTIONS",
                "RUN-RESPONSE-ASSERTIONS");

        Path runDir = tempDir.resolve("evidence/runs/RUN-RESPONSE-ASSERTIONS");
        String assertionEvidence = Files.readString(runDir.resolve("assertions.yaml"));
        assertThat(result.passed()).isTrue();
        assertThat(assertionEvidence)
                .contains("type: response_status_equals")
                .contains("response status `http_status` matched `202`")
                .contains("type: json_path_equals")
                .contains("json path `$.status` matched `ACCEPTED`")
                .contains("type: json_path_absent")
                .contains("json path `$.error` was absent")
                .contains("type: numeric_tolerance")
                .contains("numeric path `$.riskScore` matched `0.05` within tolerance `0.005`");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("assertion_status: passed")
                .contains("assertions: assertions.yaml");
    }

    @Test
    void evaluatesDbRowMatchesAssertionAgainstFixtureDatabase() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:execution_db_assertion_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        writeDbRowAssertionPackage(jdbcUrl);
        ProviderRuntime fakeRuntime = request -> {
            try {
                Files.createDirectories(request.stdoutLog().getParent());
                Files.createDirectories(request.stderrLog().getParent());
                Files.createDirectories(request.actualOutput().getParent());
                Files.writeString(request.stdoutLog(), "db assertion target executed\n");
                Files.writeString(request.stderrLog(), "");
                Files.writeString(request.actualOutput(), "ok\n");
                return new AdapterExecutionResult(0, false, request.stdoutLog(), request.stderrLog(), request.actualOutput());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write fake DB assertion output.", e);
            }
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("request_response/rest", fakeRuntime)));

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-DB-ASSERT-001.yaml"),
                "BATCH-DB-ASSERT",
                "RUN-DB-ASSERT");

        Path runDir = tempDir.resolve("evidence/runs/RUN-DB-ASSERT");
        assertThat(result.passed()).isTrue();
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("type: db_row_matches")
                .contains("oracle: ${oracles.order_projection}")
                .contains("expected_ref: queries/count_payment_orders.sql expected_count=1")
                .contains("actual_ref: db:relational_db/count_payment_orders")
                .contains("decision_rule: db_row_matches")
                .contains("query `count_payment_orders` returned expected row_count `1`");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("provider_family: db_fixture")
                .contains("assertion_status: passed")
                .contains("cleanup_result: cleanup.yaml");
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
    void defaultProviderRuntimeRegistryResolvesNativeProviderRuntimes() {
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(
                new DataPipelineAdapter(),
                new RequestResponseProvider(),
                new MessagingProvider(),
                new DeploymentReadinessProvider());

        assertThat(registry.runtimeFor(resolvedAdapter("request_response", "grpc"))).isNotNull();
        assertThat(registry.runtimeFor(resolvedAdapter("messaging", "kafka"))).isNotNull();
        assertThat(registry.runtimeFor(resolvedAdapter("messaging", "nats"))).isNotNull();
        assertThat(registry.runtimeFor(resolvedAdapter("deployment_readiness", "k8s"))).isNotNull();
        assertThat(registry.runtimeFor(resolvedAdapter("deployment_readiness", "vm"))).isNotNull();
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

    @Test
    void blocksExecutionWhenGeneratedContractsContainOnlyFixtureContracts() throws Exception {
        writeMinimalTestCase("TC-FIXTURE-ONLY");
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new FixtureOnlyProviderContractResolver(),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of()));

        assertThatThrownBy(() -> engine.execute(
                        tempDir,
                        tempDir.resolve("tests/approved/TC-FIXTURE-ONLY.yaml"),
                        "BATCH-FIXTURE-ONLY",
                        "RUN-FIXTURE-ONLY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No resolved adapter provider contract available");
    }

    @Test
    void filtersFixtureProvidersAndIgnoresNonRunnableDbFixtureContracts() throws Exception {
        writeRequestResponsePackage();
        CapturingFixtureProviderResolver resolver = new CapturingFixtureProviderResolver();
        Files.writeString(tempDir.resolve("tests/approved/TC-REGISTRY-001.yaml"), """
                test_case_id: TC-REGISTRY-001
                ac_id: AC-REGISTRY-001
                rp_id: RP-REGISTRY
                execution_target:
                  ru_id: RU-api
                  adapter: request_response
                fixture:
                  setup:
                    - malformed-setup-entry
                    - provider: ""
                    - provider: relational_db
                    - provider: relational_db
                  cleanup:
                    - provider: relational_db
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
        ProviderRuntime fakeRuntime = request -> {
            writeRuntimeOutput(request);
            return new AdapterExecutionResult(0, false, request.stdoutLog(), request.stderrLog(), request.actualOutput());
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                resolver,
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("request_response/rest", fakeRuntime)));

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-REGISTRY-001.yaml"),
                "BATCH-FIXTURE-FILTER",
                "RUN-FIXTURE-FILTER");

        assertThat(result.passed()).isTrue();
        assertThat(resolver.fixtureProviders).containsExactly("relational_db");
        assertThat(Files.exists(tempDir.resolve("evidence/runs/RUN-FIXTURE-FILTER/fixture_setup.yaml"))).isFalse();
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

    @Test
    void passesExternalRunnerWithEmptyEvidenceMapAndStringExitCodeConfig() throws Exception {
        writeExternalRunnerPackageWithoutEvidenceMap("TC-EXTERNAL-EMPTY-MAP", "external-output\n");
        ExecutionEngine engine = executionEngineWithExternalRunnerRuntimeAndContract(
                false,
                "external-output\n",
                externalRunnerContractWithEmptyEvidenceMap());

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-EXTERNAL-EMPTY-MAP.yaml"),
                "BATCH-EXTERNAL",
                "RUN-EXTERNAL-EMPTY-MAP");

        Path runDir = tempDir.resolve("evidence/runs/RUN-EXTERNAL-EMPTY-MAP");
        assertThat(result.passed()).isTrue();
        assertThat(Files.readString(runDir.resolve("external_runner.yaml")))
                .contains("evidence_complete: true")
                .contains("evidence_map:\n  {}")
                .contains("mapped_artifacts:\n  []")
                .contains("missing_mapped_artifacts:\n  []");
    }

    @Test
    void passesExternalRunnerWithScalarEvidenceMapAsNoMappedArtifacts() throws Exception {
        writeExternalRunnerPackageWithoutEvidenceMap("TC-EXTERNAL-SCALAR-MAP", "external-output\n");
        Map<String, Object> contract = externalRunnerContractWithEmptyEvidenceMap();
        contract.put("evidence_map", "not-a-map");
        ExecutionEngine engine = executionEngineWithExternalRunnerRuntimeAndContract(
                false,
                "external-output\n",
                contract);

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-EXTERNAL-SCALAR-MAP.yaml"),
                "BATCH-EXTERNAL",
                "RUN-EXTERNAL-SCALAR-MAP");

        assertThat(result.passed()).isTrue();
        assertThat(Files.readString(tempDir.resolve("evidence/runs/RUN-EXTERNAL-SCALAR-MAP/external_runner.yaml")))
                .contains("evidence_complete: true")
                .contains("evidence_map:\n  {}")
                .contains("mapped_artifacts:\n  []")
                .contains("missing_mapped_artifacts:\n  []");
    }

    @Test
    void failsExternalRunnerWhenRuntimeTimesOutBeforeAssertions() throws Exception {
        writeExternalRunnerPackageWithoutEvidenceMap("TC-EXTERNAL-TIMEOUT", "external-output\n");
        ProviderRuntime runtime = request -> {
            try {
                Files.createDirectories(request.stdoutLog().getParent());
                Files.createDirectories(request.stderrLog().getParent());
                Files.createDirectories(request.actualOutput().getParent());
                Files.writeString(request.stdoutLog(), "external runner timed out\n");
                Files.writeString(request.stderrLog(), "");
                Files.writeString(request.actualOutput(), "external-output\n");
                return new AdapterExecutionResult(
                        0,
                        true,
                        request.stdoutLog(),
                        request.stderrLog(),
                        request.actualOutput());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write fake external runner timeout output.", e);
            }
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new StaticExternalRunnerContractResolver(externalRunnerContractWithEmptyEvidenceMap()),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("external_runner/command_runner", runtime)));

        ExecutionResult result = engine.execute(
                tempDir,
                tempDir.resolve("tests/approved/TC-EXTERNAL-TIMEOUT.yaml"),
                "BATCH-EXTERNAL",
                "RUN-EXTERNAL-TIMEOUT");

        assertThat(result.passed()).isFalse();
        assertThat(result.timeout()).isTrue();
        assertThat(Files.readString(tempDir.resolve("evidence/runs/RUN-EXTERNAL-TIMEOUT/run.yaml")))
                .contains("status: failed")
                .contains("timeout: true")
                .contains("assertion_status: not_run");
    }

    @Test
    void throwsUncheckedIoWhenExternalRunnerEvidenceCannotBeWritten() throws Exception {
        writeExternalRunnerPackageWithoutEvidenceMap("TC-EXTERNAL-EVIDENCE-IO", "external-output\n");
        ProviderRuntime runtime = request -> {
            try {
                Files.createDirectories(request.stdoutLog().getParent());
                Files.createDirectories(request.stderrLog().getParent());
                Files.createDirectories(request.actualOutput().getParent());
                Files.writeString(request.stdoutLog(), "external runner selected\n");
                Files.writeString(request.stderrLog(), "");
                Files.writeString(request.actualOutput(), "external-output\n");
                deleteRecursively(request.runDir());
                Files.writeString(request.runDir(), "not a directory");
                return new AdapterExecutionResult(
                        0,
                        false,
                        request.stdoutLog(),
                        request.stderrLog(),
                        request.actualOutput());
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to prepare blocked external evidence path.", e);
            }
        };
        ExecutionEngine engine = new ExecutionEngine(
                new DataPipelineAdapter(),
                new AssertionEngine(),
                new EvidenceWriter(),
                new BindingResolver(),
                new StaticExternalRunnerContractResolver(externalRunnerContractWithEmptyEvidenceMap()),
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("external_runner/command_runner", runtime)));

        assertThatThrownBy(() -> engine.execute(
                        tempDir,
                        tempDir.resolve("tests/approved/TC-EXTERNAL-EVIDENCE-IO.yaml"),
                        "BATCH-EXTERNAL",
                        "RUN-EXTERNAL-EVIDENCE-IO"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write external runner evidence.");
    }

    @Test
    void treatsNonMapYamlTestCaseAsMissingAdapterContract() throws Exception {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.writeString(tempDir.resolve("tests/approved/TC-NON-MAP.yaml"), """
                - not
                - a
                - map
                """);
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
                        tempDir.resolve("tests/approved/TC-NON-MAP.yaml"),
                        "BATCH-NON-MAP",
                        "RUN-NON-MAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No resolved adapter provider contract available");
    }

    @Test
    void throwsUncheckedIoWhenTestCaseYamlCannotBeRead() {
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
                        tempDir.resolve("tests/approved/missing.yaml"),
                        "BATCH-MISSING",
                        "RUN-MISSING"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read YAML artifact:");
    }

    private ExecutionEngine executionEngineWithExternalRunnerRuntime(boolean writeMappedEvidence, String actualOutput) {
        return executionEngineWithExternalRunnerRuntimeAndContract(
                writeMappedEvidence,
                actualOutput,
                new ProviderContractResolver());
    }

    private ExecutionEngine executionEngineWithExternalRunnerRuntimeAndContract(
            boolean writeMappedEvidence,
            String actualOutput,
            Map<String, Object> adapterContract) {
        return executionEngineWithExternalRunnerRuntimeAndContract(
                writeMappedEvidence,
                actualOutput,
                new StaticExternalRunnerContractResolver(adapterContract));
    }

    private ExecutionEngine executionEngineWithExternalRunnerRuntimeAndContract(
            boolean writeMappedEvidence,
            String actualOutput,
            ProviderContractResolver providerContractResolver) {
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
                providerContractResolver,
                new DatabaseFixtureProvider(),
                new ProviderRuntimeRegistry(Map.of("external_runner/command_runner", runtime)));
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
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
        writeMappingAndGeneratedArtifacts("""
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

    private void writeExternalRunnerPackageWithoutEvidenceMap(String testCaseId, String expectedOutput) throws IOException {
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
                title: External runner bridge without mapped evidence
                status: approved
                execution_target:
                  ru_id: RU-external
                  adapter: external_runner
                fixture:
                  setup: {}
                  cleanup: {}
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
        writeMappingAndGeneratedArtifacts("""
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
                          success_exit_codes: ["0", ""]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      fixtures: {}
                    evidence_responsibility: []
                    dependencies: []
                """);
    }

    private Map<String, Object> externalRunnerContractWithEmptyEvidenceMap() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("provider_family", "external_runner");
        contract.put("provider_type", "command_runner");
        contract.put("approval_ref", "ADR-EXTERNAL");
        contract.put("approved_by", "SA");
        contract.put("reason", "legacy protocol bridge");
        contract.put("container_ref", "registry.example.com/external-runner:1.0");
        contract.put("timeout_seconds", 10);
        contract.put("success_exit_codes", List.of("0", ""));
        contract.put("logs", Map.of(
                "stdout", "logs/stdout.log",
                "stderr", "logs/stderr.log"));
        contract.put("outputs", Map.of("actual_output_ref", "actual/output.txt"));
        contract.put("evidence_map", Map.of());
        return contract;
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
        writeMappingAndGeneratedArtifacts("""
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

    private void writeJsonResponseAssertionPackage() throws IOException {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.createDirectories(tempDir.resolve("fixtures"));
        Files.writeString(tempDir.resolve("fixtures/request.json"), "{\"request\":\"ok\"}\n");
        Files.writeString(tempDir.resolve("tests/approved/TC-JSON-PATH-001.yaml"), """
                test_case_id: TC-JSON-PATH-001
                ac_id: AC-JSON-PATH-001
                rp_id: RP-JSON-PATH
                title: JSON path response assertion
                status: approved
                execution_target:
                  ru_id: RU-api
                  adapter: request_response
                  execution_mode: ci_ephemeral
                  environment_ref: ci://api
                package_inputs:
                  inputs:
                    api_payload:
                      bind_as: api_payload
                      ref: fixtures/request.json
                steps:
                  - action: submit
                    input: ${inputs.api_payload}
                assertions:
                  - type: json_path_equals
                    path: $.status
                    expected_value: APPROVED
                """);
        writeMappingAndGeneratedArtifacts("""
                rp_id: RP-JSON-PATH
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
                            actual_output_ref: actual/response.json
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

    private void writeCompositeResponseAssertionPackage() throws IOException {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.createDirectories(tempDir.resolve("fixtures"));
        Files.writeString(tempDir.resolve("fixtures/request.json"), "{\"request\":\"ok\"}\n");
        Files.writeString(tempDir.resolve("tests/approved/TC-RESPONSE-ASSERTIONS-001.yaml"), """
                test_case_id: TC-RESPONSE-ASSERTIONS-001
                ac_id: AC-RESPONSE-ASSERTIONS-001
                rp_id: RP-RESPONSE-ASSERTIONS
                title: Composite response assertions
                status: approved
                execution_target:
                  ru_id: RU-api
                  adapter: request_response
                  execution_mode: ci_ephemeral
                  environment_ref: ci://api
                package_inputs:
                  inputs:
                    api_payload:
                      bind_as: api_payload
                      ref: fixtures/request.json
                steps:
                  - action: submit
                    input: ${inputs.api_payload}
                assertions:
                  - type: response_status_equals
                    expected_status: 202
                  - type: json_path_equals
                    path: $.status
                    expected_value: ACCEPTED
                  - type: json_path_absent
                    path: $.error
                  - type: numeric_tolerance
                    path: $.riskScore
                    expected_value: 0.05
                    tolerance: 0.005
                """);
        writeMappingAndGeneratedArtifacts("""
                rp_id: RP-RESPONSE-ASSERTIONS
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
                            actual_output_ref: actual/response.json
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

    private void writeDbRowAssertionPackage(String jdbcUrl) throws IOException {
        Files.createDirectories(tempDir.resolve("tests/approved"));
        Files.createDirectories(tempDir.resolve("fixtures/db"));
        Files.createDirectories(tempDir.resolve("queries"));
        Files.writeString(tempDir.resolve("fixtures/db/seed_orders.sql"), """
                CREATE TABLE IF NOT EXISTS orders (
                    order_id VARCHAR(40),
                    test_run_id VARCHAR(40),
                    status VARCHAR(20)
                );
                INSERT INTO orders(order_id, test_run_id, status)
                VALUES ('ORD-001', 'RUN-DB-ASSERT', 'PROJECTED');
                """);
        Files.writeString(tempDir.resolve("fixtures/db/cleanup_orders.sql"), """
                DELETE FROM orders WHERE test_run_id = 'RUN-DB-ASSERT';
                """);
        Files.writeString(tempDir.resolve("queries/count_payment_orders.sql"), """
                SELECT COUNT(*) FROM orders
                WHERE test_run_id = 'RUN-DB-ASSERT' AND status = 'PROJECTED'
                """);
        Files.writeString(tempDir.resolve("tests/approved/TC-DB-ASSERT-001.yaml"), """
                test_case_id: TC-DB-ASSERT-001
                ac_id: AC-DB-ASSERT-001
                rp_id: RP-DB-ASSERT
                title: DB row assertion
                status: approved
                execution_target:
                  ru_id: RU-api
                  adapter: request_response
                  execution_mode: ci_ephemeral
                  environment_ref: ci://api
                package_inputs:
                  inputs:
                    order_seed:
                      bind_as: db_seed
                      ref: fixtures/db/seed_orders.sql
                fixture:
                  setup:
                    - provider: relational_db
                      action: seed_orders
                  cleanup:
                    - provider: relational_db
                      action: cleanup_orders
                oracles:
                  order_projection:
                    type: query_result
                    provider: relational_db
                    query: count_payment_orders
                    ref: queries/count_payment_orders.sql
                    expected_count: 1
                assertions:
                  - type: db_row_matches
                    oracle: ${oracles.order_projection}
                """);
        writeMappingAndGeneratedArtifacts("""
                rp_id: RP-DB-ASSERT
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: db_assertion
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
                            actual_output_ref: actual/output.txt
                          actions:
                            submit:
                              method: POST
                              path: /submit
                      bindings:
                        order_seed:
                          provider_family: db_fixture
                          provider_type: jdbc
                          bind_as: jdbc_seed
                      fixtures:
                        relational_db:
                          provider_family: db_fixture
                          provider_type: jdbc
                          connection_ref: %s
                          isolation_key: test_run_id
                          cleanup_strategy: by_test_run_id
                          setup_actions:
                            seed_orders:
                              sql_ref: fixtures/db/seed_orders.sql
                          cleanup_actions:
                            cleanup_orders:
                              sql_ref: fixtures/db/cleanup_orders.sql
                          verification_queries:
                            count_payment_orders:
                              sql_ref: queries/count_payment_orders.sql
                    evidence_responsibility: [execution_log, fixture_setup, cleanup]
                    dependencies: []
                """.formatted(jdbcUrl));
    }

    private void writeMappingAndGeneratedArtifacts(String content) throws IOException {
        Files.writeString(tempDir.resolve("rp_ru_mapping.yaml"), content);
        writeGeneratedRuntimeArtifactsFromMapping(content);
    }

    @SuppressWarnings("unchecked")
    private void writeGeneratedRuntimeArtifactsFromMapping(String mappingYaml) throws IOException {
        Object loaded = new Yaml().load(mappingYaml);
        if (!(loaded instanceof Map<?, ?> root)
                || !(root.get("release_units") instanceof List<?> releaseUnits)
                || releaseUnits.isEmpty()) {
            return;
        }
        Path generated = tempDir.resolve("generated-framework");
        Files.createDirectories(generated.resolve("run_profiles"));
        Files.createDirectories(generated.resolve("environment_bindings"));
        Files.createDirectories(generated.resolve("provider_contracts"));
        String executionMode = firstUnitText(releaseUnits, "execution_mode", "ci_ephemeral");
        Files.writeString(generated.resolve("run_plan.yaml"), generatedRunPlanYaml(releaseUnits, executionMode));
        Files.writeString(generated.resolve("run_profiles/" + executionMode + ".yaml"), """
                profile_id: %s
                execution_mode: %s
                environment_binding_ref: environment_bindings/%s.yaml
                isolation_scope: target_graph
                dependency_policy: generated_target_graph
                max_duration: PT10M
                data_policy:
                  cleanup_required: true
                """.formatted(executionMode, executionMode, executionMode));
        Files.writeString(generated.resolve("environment_bindings/" + executionMode + ".yaml"),
                generatedEnvironmentBindingYaml(releaseUnits, executionMode));
        for (int index = 0; index < releaseUnits.size(); index++) {
            if (releaseUnits.get(index) instanceof Map<?, ?> unit) {
                writeGeneratedProviderContracts(generated, (Map<String, Object>) unit, index);
            }
        }
    }

    private String generatedRunPlanYaml(List<?> releaseUnits, String executionMode) {
        StringBuilder builder = new StringBuilder();
        builder.append("run_profile_ref: run_profiles/").append(executionMode).append(".yaml\n");
        builder.append("environment_binding_ref: environment_bindings/").append(executionMode).append(".yaml\n");
        builder.append("execution_mode: ").append(executionMode).append("\n");
        builder.append("target_dependencies:\n");
        for (Object entry : releaseUnits) {
            if (!(entry instanceof Map<?, ?> unit)) {
                continue;
            }
            String targetId = text(unit, "ru_id");
            Object dependencies = unit.get("dependencies");
            if (!(dependencies instanceof List<?> list) || list.isEmpty()) {
                builder.append("  ").append(targetId).append(": []\n");
                continue;
            }
            builder.append("  ").append(targetId).append(":\n");
            for (Object dependency : list) {
                builder.append("    - target_id: ").append(dependency).append("\n");
                builder.append("      required: true\n");
            }
        }
        return builder.toString();
    }

    private String generatedEnvironmentBindingYaml(List<?> releaseUnits, String executionMode) {
        StringBuilder builder = new StringBuilder();
        builder.append("environment_id: ").append(executionMode).append("\n");
        builder.append("environment_type: isolated\n");
        builder.append("targets:\n");
        for (Object entry : releaseUnits) {
            if (!(entry instanceof Map<?, ?> unit)) {
                continue;
            }
            String targetId = text(unit, "ru_id");
            String adapter = text(unit, "adapter");
            builder.append("  ").append(targetId).append(":\n");
            builder.append("    target_id: ").append(targetId).append("\n");
            builder.append("    runner: ").append(adapter).append("\n");
            builder.append("    execution_mode: ").append(executionMode).append("\n");
            builder.append("    environment_ref: ").append(text(unit, "environment_ref")).append("\n");
            builder.append("    provider_contract_ref: provider_contracts/")
                    .append(safeFileName(targetId))
                    .append(".yaml#adapters.")
                    .append(adapter)
                    .append("\n");
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeGeneratedProviderContracts(Path generated, Map<String, Object> unit, int unitIndex) throws IOException {
        Object contractsValue = unit.get("provider_contracts");
        Map<String, Object> contracts = contractsValue instanceof Map<?, ?> map
                ? deepCopy((Map<String, Object>) map)
                : new LinkedHashMap<>();
        addContractPaths(contracts, unitIndex);
        Files.writeString(
                generated.resolve("provider_contracts/" + safeFileName(text(unit, "ru_id")) + ".yaml"),
                new Yaml().dump(Map.of("provider_contracts", contracts)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copied.put(entry.getKey(), deepCopy((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copied.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copied.put(entry.getKey(), value);
            }
        }
        return copied;
    }

    @SuppressWarnings("unchecked")
    private void addContractPaths(Map<String, Object> contracts, int unitIndex) {
        for (Map.Entry<String, Object> sectionEntry : contracts.entrySet()) {
            if (!(sectionEntry.getValue() instanceof Map<?, ?> section)) {
                continue;
            }
            for (Map.Entry<?, ?> contractEntry : section.entrySet()) {
                if (contractEntry.getValue() instanceof Map<?, ?> contract
                        && !contract.containsKey("contract_path")) {
                    ((Map<String, Object>) contract).put(
                            "contract_path",
                            "release_units[" + unitIndex + "].provider_contracts."
                                    + sectionEntry.getKey() + "." + contractEntry.getKey());
                }
            }
        }
    }

    private String firstUnitText(List<?> releaseUnits, String field, String fallback) {
        for (Object entry : releaseUnits) {
            if (entry instanceof Map<?, ?> unit) {
                String value = text(unit, field);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private String text(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? "" : value.toString();
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
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

        @Override
        public ProviderContractResolutionReport resolveGenerated(
                Path packageRoot,
                String requestedProfile,
                String targetRuId,
                String adapter,
                List<String> bindingTypes,
                List<String> fixtureProviders) {
            return new ProviderContractResolutionReport(false, List.of(), List.of());
        }
    }

    private static class FixtureOnlyProviderContractResolver extends ProviderContractResolver {
        @Override
        public ProviderContractResolutionReport resolveGenerated(
                Path packageRoot,
                String requestedProfile,
                String targetRuId,
                String adapter,
                List<String> bindingTypes,
                List<String> fixtureProviders) {
            return new ProviderContractResolutionReport(
                    true,
                    List.of(new ResolvedProviderContract(
                            "fixture",
                            "db_fixture",
                            "generated",
                            "relational_db",
                            "jdbc",
                            "supported",
                            "supported",
                            "RU-api",
                            "relational_db",
                            "generated-framework/provider_contracts/RU-api.yaml#fixtures.relational_db")),
                    List.of());
        }
    }

    private static class CapturingFixtureProviderResolver extends ProviderContractResolver {

        private List<String> fixtureProviders = List.of();

        @Override
        public ProviderContractResolutionReport resolveGenerated(
                Path packageRoot,
                String requestedProfile,
                String targetRuId,
                String adapter,
                List<String> bindingTypes,
                List<String> fixtureProviders) {
            this.fixtureProviders = List.copyOf(fixtureProviders);
            return new ProviderContractResolutionReport(
                    true,
                    List.of(
                            new ResolvedProviderContract(
                                    "adapter",
                                    "request_response",
                                    "generated",
                                    "request_response",
                                    "rest",
                                    "supported",
                                    "supported",
                                    "RU-api",
                                    "request_response",
                                    "generated-framework/provider_contracts/RU-api.yaml#adapters.request_response"),
                            new ResolvedProviderContract(
                                    "fixture",
                                    "db_fixture",
                                    "generated",
                                    "relational_db",
                                    "jdbc",
                                    "supported",
                                    "supported",
                                    "RU-api",
                                    "relational_db",
                                    "generated-framework/provider_contracts/RU-api.yaml#fixtures.relational_db"),
                            new ResolvedProviderContract(
                                    "fixture",
                                    "file_batch",
                                    "generated",
                                    "file_seed",
                                    "file",
                                    "supported",
                                    "supported",
                                    "RU-api",
                                    "file_seed",
                                    "generated-framework/provider_contracts/RU-api.yaml#fixtures.file_seed")),
                    List.of());
        }

        @Override
        public Map<String, Object> generatedAdapterContract(
                Path packageRoot,
                String requestedProfile,
                String targetId,
                String adapter) {
            return Map.of(
                    "provider_family", "request_response",
                    "provider_type", "rest",
                    "endpoint_ref", "mock://api",
                    "actions", Map.of("submit", Map.of("method", "POST")));
        }

        @Override
        public Map<String, Object> generatedFixtureContract(
                Path packageRoot,
                String requestedProfile,
                String targetId,
                String adapter,
                String fixtureProvider) {
            return Map.of(
                    "provider_family", "db_fixture",
                    "provider_type", "jdbc");
        }
    }

    private static class StaticExternalRunnerContractResolver extends ProviderContractResolver {

        private final Map<String, Object> adapterContract;

        StaticExternalRunnerContractResolver(Map<String, Object> adapterContract) {
            this.adapterContract = adapterContract;
        }

        @Override
        public ProviderContractResolutionReport resolveGenerated(
                Path packageRoot,
                String requestedProfile,
                String targetRuId,
                String adapter,
                List<String> bindingTypes,
                List<String> fixtureProviders) {
            return new ProviderContractResolutionReport(
                    true,
                    List.of(new ResolvedProviderContract(
                            "adapter",
                            "external_runner",
                            "generated",
                            "external_runner",
                            "command_runner",
                            "supported",
                            "supported",
                            "RU-external",
                            "external_runner",
                            "generated-framework/provider_contracts/RU-external.yaml#adapters.external_runner")),
                    List.of());
        }

        @Override
        public Map<String, Object> generatedAdapterContract(
                Path packageRoot,
                String requestedProfile,
                String targetId,
                String adapter) {
            return adapterContract;
        }
    }
}
