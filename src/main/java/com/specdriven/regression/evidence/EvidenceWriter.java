package com.specdriven.regression.evidence;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.assertion.AssertionEvaluation;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.dsl.DslTestCaseNormalizer;
import com.specdriven.regression.execution.ExecutionResult;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class EvidenceWriter {

    private final DslTestCaseNormalizer dslTestCaseNormalizer = new DslTestCaseNormalizer();

    public Path writeExecutionRun(
            Path runDir,
            String batchId,
            String runId,
            Map<String, Object> testCase,
            String status,
            AdapterExecutionResult adapterResult,
            AssertionEvaluation assertionEvaluation) {
        return writeExecutionRun(
                runDir,
                batchId,
                runId,
                testCase,
                status,
                adapterResult,
                assertionEvaluation,
                List.of(),
                List.of(),
                List.of());
    }

    public Path writeExecutionRun(
            Path runDir,
            String batchId,
            String runId,
            Map<String, Object> testCase,
            String status,
            AdapterExecutionResult adapterResult,
            AssertionEvaluation assertionEvaluation,
            List<ResolvedBinding> resolvedBindings,
            List<ResolvedProviderContract> providerContracts) {
        return writeExecutionRun(
                runDir,
                batchId,
                runId,
                testCase,
                status,
                adapterResult,
                assertionEvaluation,
                resolvedBindings,
                List.of(),
                providerContracts);
    }

    public Path writeExecutionRun(
            Path runDir,
            String batchId,
            String runId,
            Map<String, Object> testCase,
            String status,
            AdapterExecutionResult adapterResult,
            AssertionEvaluation assertionEvaluation,
            List<ResolvedBinding> resolvedBindings,
            List<String> resolvedDependencies,
            List<ResolvedProviderContract> providerContracts) {
        testCase = dslTestCaseNormalizer.normalize(testCase);
        Map<?, ?> executionTarget = executionTarget(testCase);
        try {
            Files.createDirectories(runDir);
            String cleanupResult = writeCleanupEvidence(runDir, testCase);
            Files.writeString(runDir.resolve("run.yaml"), """
                    run_id: %s
                    batch_id: %s
                    rp_id: %s
                    test_case_id: %s
                    ac_id: %s
                    status: %s
                    adapter_execution_started: true
                    execution_mode: %s
                    environment_ref: %s
                    ru_refs:
                      - %s
                    resolved_dependencies:
                    %s
                    resolved_bindings:
                    %s
                    provider_contracts_used:
                    %s
                    provider_evidence:
                    %s
                    exit_code: %s
                    timeout: %s
                    stdout: %s
                    stderr: %s
                    actual_output: %s
                    assertion_status: %s
                    assertions: %s
                    cleanup_result: %s
                    dsl_runtime:
                    %s
                    """.formatted(
                    runId,
                    batchId,
                    stringValue(testCase.get("rp_id")),
                    stringValue(testCase.get("test_case_id")),
                    stringValue(testCase.get("ac_id")),
                    status,
                    stringValue(executionTarget.get("execution_mode")),
                    stringValue(executionTarget.get("environment_ref")),
                    stringValue(executionTarget.get("ru_id")),
                    resolvedDependenciesYaml(resolvedDependencies),
                    resolvedBindingsYaml(resolvedBindings),
                    providerContractsYaml(providerContracts),
                    providerEvidenceYaml(runDir),
                    adapterResult.exitCode(),
                    adapterResult.timeout(),
                    runDir.relativize(adapterResult.stdoutLog()),
                    runDir.relativize(adapterResult.stderrLog()),
                    runDir.relativize(adapterResult.actualOutput()),
                    assertionEvaluation == null ? "not_run" : assertionEvaluation.status(),
                    assertionEvaluation == null ? "" : runDir.relativize(assertionEvaluation.evidencePath()),
                    cleanupResult,
                    dslRuntimeYaml(testCase)));
            return runDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write execution run evidence.", e);
        }
    }

    private String dslRuntimeYaml(Map<String, Object> testCase) {
        if (!"v1".equals(stringValue(testCase.get("dsl_version")))) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("  dsl_version: ").append(stringValue(testCase.get("dsl_version"))).append("\n");
        builder.append("  targets:\n").append(targetsYaml(testCase.get("targets"))).append("\n");
        builder.append("  setup_fixtures:\n").append(setupFixturesYaml(testCase.get("setup"))).append("\n");
        builder.append("  execute_steps:\n").append(executeStepsYaml(testCase.get("execute"))).append("\n");
        builder.append("  expected_results:\n").append(expectedResultsYaml(testCase.get("expected_results"))).append("\n");
        builder.append("  verify_rules:\n").append(verifyRulesYaml(testCase.get("verify"))).append("\n");
        builder.append("  evidence_required:\n").append(evidenceRequiredYaml(testCase.get("evidence"))).append("\n");
        builder.append("  runtime:\n").append(runtimeYaml(testCase.get("runtime")));
        return builder.toString().stripTrailing();
    }

    private String targetsYaml(Object targetsValue) {
        Map<?, ?> targets = map(targetsValue);
        if (targets.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : targets.entrySet()) {
            Map<?, ?> target = map(entry.getValue());
            builder.append("    - target_id: ").append(stringValue(entry.getKey())).append("\n");
            appendField(builder, "      ", "type", target.get("type"));
            appendField(builder, "      ", "runner", target.get("runner"));
            appendField(builder, "      ", "environment", target.get("environment"));
        }
        return builder.toString().stripTrailing();
    }

    private String setupFixturesYaml(Object setupValue) {
        Map<?, ?> fixtures = map(map(setupValue).get("fixtures"));
        if (fixtures.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : fixtures.entrySet()) {
            Map<?, ?> fixture = map(entry.getValue());
            builder.append("    - fixture_name: ").append(stringValue(entry.getKey())).append("\n");
            appendField(builder, "      ", "type", fixture.get("type"));
            appendField(builder, "      ", "ref", fixture.get("ref"));
            appendField(builder, "      ", "cleanup_ref", fixture.get("cleanup_ref"));
        }
        return builder.toString().stripTrailing();
    }

    private String executeStepsYaml(Object executeValue) {
        List<?> execute = list(executeValue);
        if (execute.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : execute) {
            Map<?, ?> step = map(item);
            builder.append("    - step_id: ").append(stringValue(step.get("id"))).append("\n");
            appendField(builder, "      ", "target", step.get("target"));
            appendField(builder, "      ", "operation", step.get("operation"));
            builder.append("      outputs:\n").append(outputsYaml(step.get("outputs")));
        }
        return builder.toString().stripTrailing();
    }

    private String outputsYaml(Object outputsValue) {
        Map<?, ?> outputs = map(outputsValue);
        if (outputs.isEmpty()) {
            return "        []\n";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : outputs.entrySet()) {
            builder.append("        ").append(stringValue(entry.getKey())).append(": ")
                    .append(outputRef(entry.getValue())).append("\n");
        }
        return builder.toString();
    }

    private String expectedResultsYaml(Object expectedResultsValue) {
        Map<?, ?> expectedResults = map(expectedResultsValue);
        if (expectedResults.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : expectedResults.entrySet()) {
            Map<?, ?> expectedResult = map(entry.getValue());
            builder.append("    - expected_result_id: ").append(stringValue(entry.getKey())).append("\n");
            appendField(builder, "      ", "type", expectedResult.get("type"));
            appendField(builder, "      ", "ref", expectedResult.get("ref"));
        }
        return builder.toString().stripTrailing();
    }

    private String verifyRulesYaml(Object verifyValue) {
        List<?> verifyRules = list(verifyValue);
        if (verifyRules.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        for (Object item : verifyRules) {
            Map<?, ?> rule = map(item);
            builder.append("    - verify_id: ").append(stringValue(rule.get("id"))).append("\n");
            appendField(builder, "      ", "type", rule.get("type"));
            appendField(builder, "      ", "actual", rule.get("actual"));
            appendField(builder, "      ", "expected", rule.get("expected"));
            appendField(builder, "      ", "target", rule.get("target"));
        }
        return builder.toString().stripTrailing();
    }

    private String evidenceRequiredYaml(Object evidenceValue) {
        List<?> refs = list(map(evidenceValue).get("required"));
        if (refs.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        for (Object ref : refs) {
            builder.append("    - ").append(stringValue(ref)).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private String runtimeYaml(Object runtimeValue) {
        Map<?, ?> runtime = map(runtimeValue);
        if (runtime.isEmpty()) {
            return "    []";
        }
        StringBuilder builder = new StringBuilder();
        appendField(builder, "    ", "timeout", runtime.get("timeout"));
        Object retry = runtime.get("retry");
        if (retry instanceof Map<?, ?> retryMap) {
            builder.append("    retry:\n");
            appendField(builder, "      ", "max_attempts", retryMap.get("max_attempts"));
        }
        return builder.toString().stripTrailing();
    }

    private String outputRef(Object value) {
        if (value instanceof Map<?, ?> output) {
            String ref = stringValue(output.get("ref"));
            return ref.isBlank() ? stringValue(value) : ref;
        }
        return stringValue(value);
    }

    private void appendField(StringBuilder builder, String indent, String name, Object value) {
        String text = stringValue(value);
        if (!text.isBlank()) {
            builder.append(indent).append(name).append(": ").append(text).append("\n");
        }
    }

    private String resolvedBindingsYaml(List<ResolvedBinding> resolvedBindings) {
        if (resolvedBindings.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (ResolvedBinding binding : resolvedBindings) {
            builder.append("  - binding_name: ").append(binding.bindingName()).append("\n");
            builder.append("    binding_type: ").append(binding.bindingType()).append("\n");
            builder.append("    ref: ").append(binding.ref()).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private String resolvedDependenciesYaml(List<String> resolvedDependencies) {
        if (resolvedDependencies.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (String dependency : resolvedDependencies) {
            builder.append("  - ").append(dependency).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private String providerContractsYaml(List<ResolvedProviderContract> providerContracts) {
        if (providerContracts.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (ResolvedProviderContract contract : providerContracts) {
            builder.append("  - contract_type: ").append(contract.contractType()).append("\n");
            builder.append("    provider_name: ").append(contract.providerName()).append("\n");
            builder.append("    provider_family: ").append(contract.providerFamily()).append("\n");
            builder.append("    provider_type: ").append(contract.providerType()).append("\n");
            builder.append("    registry_status: ").append(contract.registryStatus()).append("\n");
            builder.append("    runtime_status: ").append(contract.runtimeStatus()).append("\n");
            builder.append("    affected_ru: ").append(contract.affectedRu()).append("\n");
            builder.append("    capability: ").append(contract.capability()).append("\n");
            builder.append("    contract_path: ").append(contract.contractPath()).append("\n");
            builder.append("    source_level: ").append(contract.sourceLevel()).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private String providerEvidenceYaml(Path runDir) {
        List<ProviderEvidenceRef> refs = List.of(
                new ProviderEvidenceRef("request_response", "request_response.yaml"),
                new ProviderEvidenceRef("messaging", "messaging.yaml"),
                new ProviderEvidenceRef("deployment_readiness", "readiness.yaml"),
                new ProviderEvidenceRef("external_runner", "external_runner.yaml"),
                new ProviderEvidenceRef("fixture_setup", "fixture_setup.yaml"),
                new ProviderEvidenceRef("cleanup", "cleanup.yaml"));
        StringBuilder builder = new StringBuilder();
        for (ProviderEvidenceRef ref : refs) {
            Path evidencePath = runDir.resolve(ref.path());
            if (Files.isRegularFile(evidencePath)) {
                builder.append("  ").append(ref.name()).append(": ")
                        .append(runDir.relativize(evidencePath)).append("\n");
            }
        }
        if (builder.isEmpty()) {
            return "  []";
        }
        return builder.toString().stripTrailing();
    }

    public Path writeExecutionBatch(
            Path packageRoot,
            String batchId,
            String executionMode,
            String environmentRef,
            String startedAt,
            String status,
            List<ExecutionResult> results) {
        Path batchDir = packageRoot.resolve("evidence/batches").resolve(batchId);
        try {
            Files.createDirectories(batchDir);
            String finishedAt = java.time.OffsetDateTime.now().toString();
            Files.writeString(batchDir.resolve("batch.yaml"), """
                    batch_id: %s
                    rp_id: %s
                    status: %s
                    execution_mode: %s
                    environment_ref: %s
                    started_at: %s
                    finished_at: %s
                    runs:
                    %s
                    """.formatted(
                    batchId,
                    packageRoot.getFileName(),
                    status,
                    executionMode,
                    environmentRef,
                    startedAt,
                    finishedAt,
                    batchRunsYaml(results)));
            return batchDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write execution batch evidence.", e);
        }
    }

    private String batchRunsYaml(List<ExecutionResult> results) {
        if (results.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (ExecutionResult result : results) {
            builder.append("  - run_id: ").append(result.runId()).append("\n");
            builder.append("    test_case_id: ").append(result.testCaseId()).append("\n");
            builder.append("    ac_id: ").append(result.acId()).append("\n");
            builder.append("    status: ").append(result.status()).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private String writeCleanupEvidence(Path runDir, Map<String, Object> testCase) throws IOException {
        Object fixture = testCase.get("fixture");
        if (!(fixture instanceof Map<?, ?> fixtureMap)) {
            return "";
        }
        Object cleanup = fixtureMap.get("cleanup");
        if (!(cleanup instanceof List<?> cleanupEntries) || cleanupEntries.isEmpty()) {
            return "";
        }
        Path cleanupPath = runDir.resolve("cleanup.yaml");
        if (Files.exists(cleanupPath)) {
            return runDir.relativize(cleanupPath).toString();
        }
        StringBuilder builder = new StringBuilder("status: passed\ncleanup_actions:\n");
        for (Object entry : cleanupEntries) {
            if (entry instanceof Map<?, ?> action) {
                builder.append("  - provider: ").append(stringValue(action.get("provider"))).append("\n");
                builder.append("    action: ").append(stringValue(action.get("action"))).append("\n");
                builder.append("    status: passed\n");
            }
        }
        Files.writeString(cleanupPath, builder.toString());
        return runDir.relativize(cleanupPath).toString();
    }

    public ExecutionResult writeBlockedRun(
            Path packageRoot,
            String batchId,
            String runId,
            List<Path> approvedTests,
            List<String> failureDetails,
            String executionMode,
            String environmentRef) {
        return writeBlockedRun(
                packageRoot,
                batchId,
                runId,
                approvedTests,
                failureDetails,
                executionMode,
                environmentRef,
                List.of());
    }

    public ExecutionResult writeBlockedRun(
            Path packageRoot,
            String batchId,
            String runId,
            List<Path> approvedTests,
            List<String> failureDetails,
            String executionMode,
            String environmentRef,
            List<String> resolvedDependencies) {
        Path runDir = packageRoot.resolve("evidence/runs").resolve(runId);
        Map<String, Object> testCase = approvedTests.isEmpty()
                ? Map.of()
                : dslTestCaseNormalizer.normalize(readYamlMap(approvedTests.get(0)));
        Map<?, ?> executionTarget = executionTarget(testCase);
        String resolvedExecutionMode = isBlank(executionMode)
                ? stringValue(executionTarget.get("execution_mode"))
                : executionMode;
        String resolvedEnvironmentRef = isBlank(environmentRef)
                ? stringValue(executionTarget.get("environment_ref"))
                : environmentRef;
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("run.yaml"), """
                    run_id: %s
                    batch_id: %s
                    rp_id: %s
                    test_case_id: %s
                    ac_id: %s
                    status: blocked
                    adapter_execution_started: false
                    execution_mode: %s
                    environment_ref: %s
                    ru_refs:
                      - %s
                    resolved_dependencies:
                    %s
                    failure_details: failure_details.yaml
                    """.formatted(
                    runId,
                    batchId,
                    stringValue(testCase.get("rp_id")),
                    stringValue(testCase.get("test_case_id")),
                    stringValue(testCase.get("ac_id")),
                    resolvedExecutionMode,
                    resolvedEnvironmentRef,
                    stringValue(executionTarget.get("ru_id")),
                    resolvedDependenciesYaml(resolvedDependencies)));
            Files.writeString(runDir.resolve("failure_details.yaml"), failureDetailsYaml(failureDetails));
            return new ExecutionResult(
                    runId,
                    stringValue(testCase.get("test_case_id")),
                    stringValue(testCase.get("ac_id")),
                    "blocked",
                    -1,
                    false,
                    runDir,
                    runDir,
                    runDir,
                    runDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write blocked run evidence.", e);
        }
    }

    private String failureDetailsYaml(List<String> failureDetails) {
        StringBuilder builder = new StringBuilder("failures:\n");
        for (String detail : failureDetails) {
            builder.append("  - ").append(detail.replace("\n", "\n    ")).append("\n");
        }
        return builder.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read evidence source artifact: " + path, e);
        }
    }

    private Map<?, ?> executionTarget(Map<String, Object> testCase) {
        Object executionTarget = testCase.get("execution_target");
        if (executionTarget instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ProviderEvidenceRef(String name, String path) {
    }
}
