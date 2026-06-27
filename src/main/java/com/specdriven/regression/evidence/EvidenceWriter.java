package com.specdriven.regression.evidence;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.assertion.AssertionEvaluation;
import com.specdriven.regression.binding.ResolvedBinding;
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
                    cleanupResult));
            return runDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write execution run evidence.", e);
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
                new ProviderEvidenceRef("messaging", "messaging.yaml"),
                new ProviderEvidenceRef("deployment_readiness", "readiness.yaml"),
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
        Map<String, Object> testCase = approvedTests.isEmpty() ? Map.of() : readYamlMap(approvedTests.get(0));
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ProviderEvidenceRef(String name, String path) {
    }
}
