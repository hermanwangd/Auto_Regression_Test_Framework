package com.specdriven.regression.execution;

import com.specdriven.regression.adapter.AdapterExecutionRequest;
import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.adapter.DataPipelineAdapter;
import com.specdriven.regression.assertion.AssertionEngine;
import com.specdriven.regression.assertion.AssertionEvaluation;
import com.specdriven.regression.evidence.EvidenceWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class ExecutionEngine {

    private final DataPipelineAdapter adapter;
    private final AssertionEngine assertionEngine;
    private final EvidenceWriter evidenceWriter;

    public ExecutionEngine() {
        this(new DataPipelineAdapter(), new AssertionEngine(), new EvidenceWriter());
    }

    public ExecutionEngine(DataPipelineAdapter adapter) {
        this(adapter, new AssertionEngine(), new EvidenceWriter());
    }

    public ExecutionEngine(DataPipelineAdapter adapter, AssertionEngine assertionEngine) {
        this(adapter, assertionEngine, new EvidenceWriter());
    }

    public ExecutionEngine(DataPipelineAdapter adapter, AssertionEngine assertionEngine, EvidenceWriter evidenceWriter) {
        this.adapter = adapter;
        this.assertionEngine = assertionEngine;
        this.evidenceWriter = evidenceWriter;
    }

    public ExecutionResult execute(Path packageRoot, Path testCasePath, String batchId, String runId) {
        Map<String, Object> testCase = readYamlMap(testCasePath);
        String testCaseId = stringValue(testCase.get("test_case_id"));
        String acId = stringValue(testCase.get("ac_id"));
        String adapterName = adapterName(testCase);
        Map<String, Object> contract = adapterContract(packageRoot.resolve("rp_ru_mapping.yaml"), adapterName);
        Path runDir = packageRoot.resolve("evidence/runs").resolve(runId);
        Path stdoutLog = runDir.resolve(pathValue(contract, "logs", "stdout", "logs/stdout.log"));
        Path stderrLog = runDir.resolve(pathValue(contract, "logs", "stderr", "logs/stderr.log"));
        Path actualOutput = runDir.resolve(pathValue(contract, "outputs", "actual_output_ref", "actual/output.txt"));
        Path workingDirectory = workingDirectory(packageRoot, contract);

        AdapterExecutionResult adapterResult = adapter.execute(new AdapterExecutionRequest(
                stringValue(contract.get("command")),
                workingDirectory,
                intValue(contract.get("timeout_seconds"), 300),
                successExitCodes(contract.get("success_exit_codes")),
                runDir,
                stdoutLog,
                stderrLog,
                actualOutput));
        boolean passed = !adapterResult.timeout()
                && successExitCodes(contract.get("success_exit_codes")).contains(adapterResult.exitCode());
        AssertionEvaluation assertionEvaluation = null;
        if (passed) {
            assertionEvaluation = assertionEngine.evaluateFileDiff(
                    packageRoot,
                    testCase,
                    adapterResult.actualOutput(),
                    runDir);
            passed = assertionEvaluation.passed();
        }
        String status = passed ? "passed" : "failed";
        evidenceWriter.writeExecutionRun(runDir, batchId, runId, testCase, status, adapterResult, assertionEvaluation);
        return new ExecutionResult(
                runId,
                testCaseId,
                acId,
                status,
                adapterResult.exitCode(),
                adapterResult.timeout(),
                runDir,
                adapterResult.stdoutLog(),
                adapterResult.stderrLog(),
                adapterResult.actualOutput());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adapterContract(Path mappingYaml, String adapterName) {
        Map<String, Object> root = readYamlMap(mappingYaml);
        Object unitsValue = root.get("release_units");
        if (!(unitsValue instanceof List<?> units) || units.isEmpty() || !(units.get(0) instanceof Map<?, ?> unit)) {
            return Map.of();
        }
        Object contracts = unit.get("provider_contracts");
        if (!(contracts instanceof Map<?, ?> contractMap)) {
            return Map.of();
        }
        Object adapters = contractMap.get("adapters");
        if (!(adapters instanceof Map<?, ?> adapterMap)) {
            return Map.of();
        }
        Object contract = adapterMap.get(adapterName);
        if (contract instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String adapterName(Map<String, Object> testCase) {
        Object executionTarget = testCase.get("execution_target");
        if (executionTarget instanceof Map<?, ?> target) {
            return stringValue(target.get("adapter"));
        }
        return "";
    }

    private Path workingDirectory(Path packageRoot, Map<String, Object> contract) {
        String value = stringValue(contract.get("working_directory"));
        if (value.isBlank()) {
            return packageRoot;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path : packageRoot.resolve(path);
    }

    private String pathValue(Map<String, Object> contract, String section, String field, String fallback) {
        Object sectionValue = contract.get(section);
        if (sectionValue instanceof Map<?, ?> map) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private List<Integer> successExitCodes(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return List.of(0);
        }
        List<Integer> codes = new ArrayList<>();
        for (Object entry : values) {
            codes.add(intValue(entry, 0));
        }
        return List.copyOf(codes);
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
            throw new UncheckedIOException("Failed to read YAML artifact: " + path, e);
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
