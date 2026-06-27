package com.specdriven.regression.execution;

import com.specdriven.regression.adapter.AdapterExecutionRequest;
import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.adapter.DataPipelineAdapter;
import com.specdriven.regression.assertion.AssertionEngine;
import com.specdriven.regression.assertion.AssertionEvaluation;
import com.specdriven.regression.binding.BindingResolutionReport;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.evidence.EvidenceWriter;
import com.specdriven.regression.provider.ProviderContractResolutionReport;
import com.specdriven.regression.provider.ProviderContractResolver;
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
    private final BindingResolver bindingResolver;
    private final ProviderContractResolver providerContractResolver;

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
        this(adapter, assertionEngine, evidenceWriter, new BindingResolver(), new ProviderContractResolver());
    }

    public ExecutionEngine(
            DataPipelineAdapter adapter,
            AssertionEngine assertionEngine,
            EvidenceWriter evidenceWriter,
            BindingResolver bindingResolver,
            ProviderContractResolver providerContractResolver) {
        this.adapter = adapter;
        this.assertionEngine = assertionEngine;
        this.evidenceWriter = evidenceWriter;
        this.bindingResolver = bindingResolver;
        this.providerContractResolver = providerContractResolver;
    }

    public ExecutionResult execute(Path packageRoot, Path testCasePath, String batchId, String runId) {
        Map<String, Object> testCase = readYamlMap(testCasePath);
        String testCaseId = stringValue(testCase.get("test_case_id"));
        String acId = stringValue(testCase.get("ac_id"));
        String adapterName = adapterName(testCase);
        String targetRuId = targetRuId(testCase);
        BindingResolutionReport bindingReport = bindingResolver.resolve(testCasePath);
        ProviderContractResolutionReport providerReport = providerContractResolver.resolve(
                packageRoot.resolve("rp_ru_mapping.yaml"),
                adapterName,
                bindingReport.resolvedBindings().stream().map(ResolvedBinding::bindingType).toList(),
                fixtureProviders(testCase));
        Map<String, Object> contract = adapterContract(packageRoot.resolve("rp_ru_mapping.yaml"), adapterName, targetRuId);
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
        evidenceWriter.writeExecutionRun(
                runDir,
                batchId,
                runId,
                testCase,
                status,
                adapterResult,
                assertionEvaluation,
                bindingReport.resolvedBindings(),
                providerReport.resolvedContracts());
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
    private Map<String, Object> adapterContract(Path mappingYaml, String adapterName, String targetRuId) {
        Map<String, Object> root = readYamlMap(mappingYaml);
        Object unitsValue = root.get("release_units");
        if (!(unitsValue instanceof List<?> units)) {
            return Map.of();
        }
        Map<String, Object> fallback = Map.of();
        for (Object entry : units) {
            if (!(entry instanceof Map<?, ?> unit)) {
                continue;
            }
            Map<String, Object> contract = adapterContract(unit, adapterName);
            if (contract.isEmpty()) {
                continue;
            }
            if (!targetRuId.isBlank() && targetRuId.equals(stringValue(unit.get("ru_id")))) {
                return contract;
            }
            if (fallback.isEmpty()) {
                fallback = contract;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adapterContract(Map<?, ?> unit, String adapterName) {
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

    private String targetRuId(Map<String, Object> testCase) {
        Object executionTarget = testCase.get("execution_target");
        if (executionTarget instanceof Map<?, ?> target) {
            return stringValue(target.get("ru_id"));
        }
        return "";
    }

    private List<String> fixtureProviders(Map<String, Object> testCase) {
        Object fixture = testCase.get("fixture");
        if (!(fixture instanceof Map<?, ?> fixtureMap)) {
            return List.of();
        }
        List<String> providers = new ArrayList<>();
        addFixtureProviders(providers, fixtureMap.get("setup"));
        addFixtureProviders(providers, fixtureMap.get("cleanup"));
        return List.copyOf(providers);
    }

    private void addFixtureProviders(List<String> providers, Object entries) {
        if (!(entries instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> action) {
                String provider = stringValue(action.get("provider"));
                if (!provider.isBlank() && !providers.contains(provider)) {
                    providers.add(provider);
                }
            }
        }
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
