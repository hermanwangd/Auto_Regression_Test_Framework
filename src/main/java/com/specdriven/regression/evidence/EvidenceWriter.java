package com.specdriven.regression.evidence;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.assertion.AssertionEvaluation;
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
            String runId,
            Map<String, Object> testCase,
            String status,
            AdapterExecutionResult adapterResult,
            AssertionEvaluation assertionEvaluation) {
        Map<?, ?> executionTarget = executionTarget(testCase);
        try {
            Files.createDirectories(runDir);
            String cleanupResult = writeCleanupEvidence(runDir, testCase);
            Files.writeString(runDir.resolve("run.yaml"), """
                    run_id: %s
                    rp_id: %s
                    test_case_id: %s
                    ac_id: %s
                    status: %s
                    adapter_execution_started: true
                    execution_mode: %s
                    environment_ref: %s
                    ru_refs:
                      - %s
                    resolved_dependencies: []
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
                    stringValue(testCase.get("rp_id")),
                    stringValue(testCase.get("test_case_id")),
                    stringValue(testCase.get("ac_id")),
                    status,
                    stringValue(executionTarget.get("execution_mode")),
                    stringValue(executionTarget.get("environment_ref")),
                    stringValue(executionTarget.get("ru_id")),
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

    public Path writeBlockedRun(Path packageRoot, List<Path> approvedTests, List<String> failureDetails) {
        Path runDir = packageRoot.resolve("evidence/runs/RUN-001");
        Map<String, Object> testCase = approvedTests.isEmpty() ? Map.of() : readYamlMap(approvedTests.get(0));
        Map<?, ?> executionTarget = executionTarget(testCase);
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("run.yaml"), """
                    run_id: RUN-001
                    rp_id: %s
                    test_case_id: %s
                    ac_id: %s
                    status: blocked
                    adapter_execution_started: false
                    execution_mode: %s
                    environment_ref: %s
                    ru_refs:
                      - %s
                    resolved_dependencies: []
                    failure_details: failure_details.yaml
                    """.formatted(
                    stringValue(testCase.get("rp_id")),
                    stringValue(testCase.get("test_case_id")),
                    stringValue(testCase.get("ac_id")),
                    stringValue(executionTarget.get("execution_mode")),
                    stringValue(executionTarget.get("environment_ref")),
                    stringValue(executionTarget.get("ru_id"))));
            Files.writeString(runDir.resolve("failure_details.yaml"), failureDetailsYaml(failureDetails));
            return runDir;
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
}
