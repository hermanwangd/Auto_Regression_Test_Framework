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
import com.specdriven.regression.provider.DatabaseFixtureProvider;
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
import java.util.LinkedHashMap;
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
    private final RequestResponseProvider requestResponseProvider;
    private final DatabaseFixtureProvider databaseFixtureProvider;
    private final MessagingProvider messagingProvider;

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
        this(adapter, assertionEngine, evidenceWriter, bindingResolver, providerContractResolver,
                new RequestResponseProvider(), new DatabaseFixtureProvider(), new MessagingProvider());
    }

    public ExecutionEngine(
            DataPipelineAdapter adapter,
            AssertionEngine assertionEngine,
            EvidenceWriter evidenceWriter,
            BindingResolver bindingResolver,
            ProviderContractResolver providerContractResolver,
            RequestResponseProvider requestResponseProvider) {
        this(adapter, assertionEngine, evidenceWriter, bindingResolver, providerContractResolver,
                requestResponseProvider, new DatabaseFixtureProvider(), new MessagingProvider());
    }

    public ExecutionEngine(
            DataPipelineAdapter adapter,
            AssertionEngine assertionEngine,
            EvidenceWriter evidenceWriter,
            BindingResolver bindingResolver,
            ProviderContractResolver providerContractResolver,
            RequestResponseProvider requestResponseProvider,
            DatabaseFixtureProvider databaseFixtureProvider) {
        this(adapter, assertionEngine, evidenceWriter, bindingResolver, providerContractResolver,
                requestResponseProvider, databaseFixtureProvider, new MessagingProvider());
    }

    public ExecutionEngine(
            DataPipelineAdapter adapter,
            AssertionEngine assertionEngine,
            EvidenceWriter evidenceWriter,
            BindingResolver bindingResolver,
            ProviderContractResolver providerContractResolver,
            RequestResponseProvider requestResponseProvider,
            DatabaseFixtureProvider databaseFixtureProvider,
            MessagingProvider messagingProvider) {
        this.adapter = adapter;
        this.assertionEngine = assertionEngine;
        this.evidenceWriter = evidenceWriter;
        this.bindingResolver = bindingResolver;
        this.providerContractResolver = providerContractResolver;
        this.requestResponseProvider = requestResponseProvider;
        this.databaseFixtureProvider = databaseFixtureProvider;
        this.messagingProvider = messagingProvider;
    }

    public ExecutionResult execute(Path packageRoot, Path testCasePath, String batchId, String runId) {
        Map<String, Object> testCase = readYamlMap(testCasePath);
        String testCaseId = stringValue(testCase.get("test_case_id"));
        String acId = stringValue(testCase.get("ac_id"));
        String adapterName = adapterName(testCase);
        String targetRuId = targetRuId(testCase);
        Path mappingYaml = packageRoot.resolve("rp_ru_mapping.yaml");
        BindingResolutionReport bindingReport = bindingResolver.resolve(testCasePath);
        ProviderContractResolutionReport providerReport = providerContractResolver.resolve(
                mappingYaml,
                adapterName,
                bindingReport.resolvedBindings().stream().map(ResolvedBinding::bindingType).toList(),
                fixtureProviders(testCase));
        Map<String, Object> contract = adapterContract(mappingYaml, adapterName, targetRuId);
        Map<String, Map<String, Object>> dbFixtureContracts = dbFixtureContracts(mappingYaml, providerReport);
        Path runDir = packageRoot.resolve("evidence/runs").resolve(runId);
        Path stdoutLog = runDir.resolve(pathValue(contract, "logs", "stdout", "logs/stdout.log"));
        Path stderrLog = runDir.resolve(pathValue(contract, "logs", "stderr", "logs/stderr.log"));
        Path actualOutput = runDir.resolve(pathValue(contract, "outputs", "actual_output_ref", "actual/output.txt"));
        Path workingDirectory = workingDirectory(packageRoot, contract);

        AdapterExecutionResult adapterResult;
        AssertionEvaluation assertionEvaluation = null;
        boolean passed;
        try {
            databaseFixtureProvider.setup(packageRoot, testCase, dbFixtureContracts, runDir);
            adapterResult = executeProvider(
                    providerReport,
                    packageRoot,
                    contract,
                    testCase,
                    bindingReport.resolvedBindings(),
                    workingDirectory,
                    runDir,
                    stdoutLog,
                    stderrLog,
                    actualOutput);
            passed = !adapterResult.timeout()
                    && successExitCodes(contract.get("success_exit_codes")).contains(adapterResult.exitCode());
            if (passed) {
                assertionEvaluation = assertionEngine.evaluateFileDiff(
                        packageRoot,
                        testCase,
                        adapterResult.actualOutput(),
                        runDir);
                passed = assertionEvaluation.passed();
            }
        } finally {
            databaseFixtureProvider.cleanup(packageRoot, testCase, dbFixtureContracts, runDir);
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

    private AdapterExecutionResult executeProvider(
            ProviderContractResolutionReport providerReport,
            Path packageRoot,
            Map<String, Object> contract,
            Map<String, Object> testCase,
            List<ResolvedBinding> resolvedBindings,
            Path workingDirectory,
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput) {
        String providerFamily = adapterProviderFamily(providerReport);
        if ("request_response".equals(providerFamily)) {
            return requestResponseProvider.execute(
                    packageRoot,
                    contract,
                    testCase,
                    resolvedBindings,
                    runDir,
                    stdoutLog,
                    stderrLog,
                    actualOutput);
        }
        if ("messaging".equals(providerFamily)) {
            return messagingProvider.execute(
                    adapterName(testCase),
                    packageRoot,
                    contract,
                    testCase,
                    resolvedBindings,
                    runDir,
                    stdoutLog,
                    stderrLog,
                    actualOutput);
        }
        return adapter.execute(new AdapterExecutionRequest(
                stringValue(contract.get("command")),
                workingDirectory,
                intValue(contract.get("timeout_seconds"), 300),
                successExitCodes(contract.get("success_exit_codes")),
                runDir,
                stdoutLog,
                stderrLog,
                actualOutput));
    }

    private String adapterProviderFamily(ProviderContractResolutionReport providerReport) {
        for (ResolvedProviderContract contract : providerReport.resolvedContracts()) {
            if ("adapter".equals(contract.contractType())) {
                return contract.providerFamily();
            }
        }
        return "";
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

    private Map<String, Map<String, Object>> dbFixtureContracts(
            Path mappingYaml,
            ProviderContractResolutionReport providerReport) {
        Map<String, Map<String, Object>> contracts = new LinkedHashMap<>();
        for (ResolvedProviderContract contract : providerReport.resolvedContracts()) {
            if ("fixture".equals(contract.contractType()) && "db_fixture".equals(contract.providerFamily())) {
                Map<String, Object> fixtureContract =
                        fixtureContract(mappingYaml, contract.providerName(), contract.affectedRu());
                if (isJdbcFixtureContract(fixtureContract)) {
                    contracts.put(contract.providerName(), fixtureContract);
                }
            }
        }
        return Map.copyOf(contracts);
    }

    private boolean isJdbcFixtureContract(Map<String, Object> contract) {
        return "jdbc".equalsIgnoreCase(stringValue(contract.get("provider_type")))
                && !stringValue(contract.get("connection_ref")).isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixtureContract(Path mappingYaml, String providerName, String affectedRu) {
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
            Map<String, Object> contract = fixtureContract(unit, providerName);
            if (contract.isEmpty()) {
                continue;
            }
            if (!affectedRu.isBlank() && affectedRu.equals(stringValue(unit.get("ru_id")))) {
                return contract;
            }
            if (fallback.isEmpty()) {
                fallback = contract;
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixtureContract(Map<?, ?> unit, String providerName) {
        Object contracts = unit.get("provider_contracts");
        if (!(contracts instanceof Map<?, ?> contractMap)) {
            return Map.of();
        }
        Object fixtures = contractMap.get("fixtures");
        if (!(fixtures instanceof Map<?, ?> fixtureMap)) {
            return Map.of();
        }
        Object contract = fixtureMap.get(providerName);
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
