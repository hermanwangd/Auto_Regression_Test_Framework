package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import com.specdriven.regression.provider.jdbc.JdbcProviderRuntime;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntimeRegistry;
import com.specdriven.regression.provider.runtime.ProviderRuntimeResolver;
import com.specdriven.regression.provider.runtime.ProviderRuntimeResolver.ProviderRuntimeResolution;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class JdbcProviderCapabilityService {

    private static final String PROVIDER_TYPE = "jdbc";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public JdbcRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return JdbcRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!validation.providerTypesUsed().equals(List.of(PROVIDER_TYPE))) {
            return JdbcRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "JDBC provider capability mode supports provider_type `jdbc` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return JdbcRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    PROVIDER_TYPE,
                    "",
                    "",
                    "Provide --profile for JDBC provider capability run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile);
        if (!profileFindings.isEmpty()) {
            return JdbcRunResult.blocked(validation.suiteId(), requestedProfile, profileFindings);
        }

        RuntimeSelection selection = selectJdbcRuntime(suiteManifest, requestedProfile, outputBase);
        if (selection == null) {
            return JdbcRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_jdbc_target",
                    "",
                    PROVIDER_TYPE,
                    requestedProfile,
                    "",
                    "Add a DSL target using provider_type `jdbc` for profile `" + requestedProfile + "`.")));
        }
        recreateDirectory(selection.runDir());

        JdbcProviderRuntime jdbcRuntime = new JdbcProviderRuntime();
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(
                new ProviderRuntimeRegistry(Map.of(PROVIDER_TYPE, jdbcRuntime)));
        ProviderExecutionContext context = new ProviderExecutionContext(
                selection.providerId(),
                selection.providerType(),
                requestedProfile,
                selection.runtimeMode(),
                selection.suiteRoot(),
                selection.runDir(),
                selection.providerContract(),
                selection.providerInstance(),
                selection.bindingValues());

        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        ProviderFailure cleanupFailure = null;
        boolean providerRuntimeExecuted = false;
        Map<String, Object> aggregateOutputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();

        try {
            for (SelectedOperation operation : setupOperations(selection.testCase(), selection.targetName())) {
                ProviderOperationResult result = executeOperation(resolver, context, operation);
                providerRuntimeExecuted = true;
                recordOperationOutput(aggregateOutputs, operation, result);
                stepResults.add(step(operation.id(), operation.request().operation(), result));
                evidenceRefs.addAll(refs(result));
                if (!result.passed()) {
                    status = "failed";
                    failure = result.failure();
                    break;
                }
            }
            if ("passed".equals(status)) {
                for (SelectedOperation operation : executeOperations(selection.testCase(), selection.targetName())) {
                    ProviderOperationResult result = executeOperation(resolver, context, operation);
                    providerRuntimeExecuted = true;
                    recordOperationOutput(aggregateOutputs, operation, result);
                    stepResults.add(step(operation.id(), operation.request().operation(), result));
                    evidenceRefs.addAll(refs(result));
                    if (!result.passed()) {
                        status = "failed";
                        failure = result.failure();
                        break;
                    }
                }
            }
            if ("passed".equals(status)) {
                for (SelectedOperation operation : verifyOperations(
                        selection.suiteRoot(), selection.testCase(), selection.targetName())) {
                    ProviderOperationResult result = executeOperation(resolver, context, operation);
                    providerRuntimeExecuted = true;
                    recordOperationOutput(aggregateOutputs, operation, result);
                    verifyResults.add(assertion(operation.id(), "db_record_exists", result.passed() ? "passed" : "failed"));
                    evidenceRefs.addAll(refs(result));
                    writeAssertion(selection.runDir(), operation.id(), result);
                    evidenceRefs.add("assertions/" + operation.id() + ".yaml");
                    if (!result.passed()) {
                        status = "failed";
                        failure = result.failure();
                        break;
                    }
                }
            }
        } finally {
            for (SelectedOperation operation : cleanupOperations(selection.testCase(), selection.targetName())) {
                ProviderOperationResult result = executeOperation(resolver, context, operation);
                providerRuntimeExecuted = true;
                recordOperationOutput(aggregateOutputs, operation, result);
                stepResults.add(step(operation.id(), operation.request().operation(), result));
                evidenceRefs.addAll(refs(result));
                if (!result.passed()) {
                    cleanupFailure = result.failure();
                    if ("passed".equals(status)) {
                        status = "failed";
                        failure = result.failure();
                    }
                }
            }
        }

        Instant finishedAt = Instant.now();
        writeExecutionLog(selection.runDir(), selection, status);
        evidenceRefs.add("logs/execution.log");
        writeBatch(selection.runDir(), selection, status);
        evidenceRefs.add("batch/batch.yaml");
        if ("failed".equals(status)) {
            writeFailureDetail(selection.runDir(), selection, failure, cleanupFailure);
            evidenceRefs.add("provider-evidence/jdbc/failure_detail.yaml");
        }
        writeEvidenceIndex(selection.runDir(), selection, evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = writeResult(
                selection.runDir(),
                selection,
                requestedProfile,
                status,
                aggregateOutputs,
                stepResults,
                verifyResults,
                evidenceRefs,
                failure,
                cleanupFailure,
                startedAt,
                finishedAt);
        return new JdbcRunResult(
                "passed".equals(status),
                status,
                stringValue(selection.suite().get("suite_id")),
                selection.batchId(),
                selection.runId(),
                stringValue(selection.testCase().get("test_case_id")),
                requestedProfile,
                selection.providerId(),
                selection.providerType(),
                selection.runtimeMode(),
                selection.dialect(),
                resultJson,
                selection.runDir(),
                providerRuntimeExecuted,
                List.of());
    }

    private ProviderOperationResult executeOperation(
            ProviderRuntimeResolver resolver,
            ProviderExecutionContext context,
            SelectedOperation operation) {
        ProviderRuntimeResolution resolution = resolver.resolve(context, operation.request());
        if (!resolution.valid()) {
            return ProviderOperationResult.failed(Map.of(), List.of(), resolution.failure());
        }
        return resolution.runtime().execute(context, operation.request());
    }

    private void recordOperationOutput(
            Map<String, Object> aggregateOutputs,
            SelectedOperation operation,
            ProviderOperationResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("operation", operation.request().operation());
        output.put("status", result.passed() ? "passed" : "failed");
        output.put("outputs", result.outputs());
        if (!result.passed() && result.failure() != null) {
            output.put("failure_code", result.failure().code());
            output.put("classification", result.failure().classification());
        }
        aggregateOutputs.put(operation.id(), output);
    }

    private RuntimeSelection selectJdbcRuntime(Path suiteManifest, String requestedProfile, Path outputBase) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        Map<String, Map<String, Object>> contractsByType =
                readDirectoryByField(frameworkProviderContractsDirectory(suiteRoot), "provider_type");
        for (Object testRef : listValue(suite.get("tests"))) {
            Map<String, Object> testCase = readMap(suiteRoot.resolve(stringValue(testRef)).normalize());
            for (Map.Entry<String, Object> targetEntry : mapValue(testCase.get("targets")).entrySet()) {
                Map<String, Object> target = mapValue(targetEntry.getValue());
                if (!targetReferencedByLifecycle(testCase, targetEntry.getKey())) {
                    continue;
                }
                String providerId = stringValue(target.get("provider_id"));
                Map<String, Object> providerInstance = instancesById.get(providerId);
                if (providerInstance == null) {
                    continue;
                }
                String providerType = stringValue(providerInstance.get("provider_type"));
                if (!PROVIDER_TYPE.equals(providerType)) {
                    continue;
                }
                Map<String, Object> providerContract = contractsByType.get(providerType);
                if (providerContract == null) {
                    continue;
                }
                Map<String, Object> providerBinding =
                        runtimeBindingResolver.providerBinding(suiteRoot, requestedProfile, providerId);
                Map<String, Object> bindingValues = mapValue(providerBinding.get("binding_values"));
                RunIds runIds = newRunIds();
                Path runDir = outputBase
                        .resolve(safe(stringValue(suite.get("suite_id"))))
                        .resolve(runIds.batchId())
                        .resolve(runIds.runId());
                return new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        targetEntry.getKey(),
                        providerId,
                        providerType,
                        requestedProfile,
                        stringValue(providerBinding.get("runtime_mode")),
                        stringValue(valueAtPath(bindingValues, "dialect")),
                        providerContract,
                        providerInstance,
                        bindingValues,
                        runIds.batchId(),
                        runIds.runId(),
                        runDir);
            }
        }
        return null;
    }

    private List<ContractFinding> requestedProfileFindings(Path suiteManifest, String requestedProfile) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<SuiteProfileGate.TestCaseDocument> testCases = new ArrayList<>();
        for (Object testRef : listValue(suite.get("tests"))) {
            Path testCasePath = suiteRoot.resolve(stringValue(testRef)).normalize();
            testCases.add(new SuiteProfileGate.TestCaseDocument(testCasePath, readMap(testCasePath)));
        }
        return SuiteProfileGate.validate(suiteManifest, suite, testCases, requestedProfile, PROVIDER_TYPE);
    }

    private Path frameworkProviderContractsDirectory(Path suiteRoot) {
        Path cwdCandidate = Path.of("").toAbsolutePath().normalize().resolve(FRAMEWORK_PROVIDER_CONTRACTS);
        if (Files.isDirectory(cwdCandidate)) {
            return cwdCandidate;
        }
        for (Path cursor = suiteRoot; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(FRAMEWORK_PROVIDER_CONTRACTS).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return cwdCandidate;
    }

    private boolean targetReferencedByLifecycle(Map<String, Object> testCase, String targetName) {
        for (Object fixtureValue : mapValue(mapValue(testCase.get("setup")).get("fixtures")).values()) {
            if (targetName.equals(stringValue(mapValue(fixtureValue).get("target")))) {
                return true;
            }
        }
        for (Object stepValue : executeOperationValues(testCase)) {
            if (targetName.equals(stringValue(mapValue(stepValue).get("target")))) {
                return true;
            }
        }
        for (Object verifyValue : verifyValues(testCase)) {
            if (targetName.equals(stringValue(mapValue(verifyValue).get("target")))) {
                return true;
            }
        }
        for (Object operationValue : listValue(mapValue(testCase.get("setup")).get("operations"))) {
            if (targetName.equals(stringValue(mapValue(operationValue).get("target")))) {
                return true;
            }
        }
        for (Object operationValue : listValue(mapValue(testCase.get("cleanup")).get("operations"))) {
            if (targetName.equals(stringValue(mapValue(operationValue).get("target")))) {
                return true;
            }
        }
        for (Object fixtureValue : mapValue(mapValue(testCase.get("cleanup")).get("fixtures")).values()) {
            if (targetName.equals(stringValue(mapValue(fixtureValue).get("target")))) {
                return true;
            }
        }
        return false;
    }

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-JDBC-" + suffix, "RUN-JDBC-" + suffix);
    }

    private List<SelectedOperation> setupOperations(Map<String, Object> testCase, String targetName) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : listValue(mapValue(testCase.get("setup")).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            if (targetName.equals(stringValue(operation.get("target")))
                    && "db_seed".equals(stringValue(operation.get("operation")))) {
                String id = stringValue(operation.get("id"));
                operations.add(new SelectedOperation(
                        id.isBlank() ? "db_seed" : id,
                        new ProviderOperationRequest(
                                stringValue(operation.get("operation")),
                                resolvedOperationInputs(testCase, operation),
                                runtimeOutputs(id, Map.of()))));
            }
        }
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("setup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            if (targetName.equals(stringValue(fixture.get("target")))
                    && "db_seed".equals(stringValue(fixture.get("operation")))) {
                operations.add(new SelectedOperation(
                        entry.getKey(),
                        new ProviderOperationRequest(
                                stringValue(fixture.get("operation")),
                                resolvedParameters(testCase, listValue(fixture.get("parameters"))),
                                runtimeOutputs(entry.getKey(), Map.of()))));
            }
        }
        return List.copyOf(operations);
    }

    private List<SelectedOperation> executeOperations(Map<String, Object> testCase, String targetName) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : executeOperationValues(testCase)) {
            Map<String, Object> step = mapValue(value);
            if (targetName.equals(stringValue(step.get("target")))) {
                String id = stringValue(step.get("id"));
                operations.add(new SelectedOperation(
                        id.isBlank() ? "execute" : id,
                        new ProviderOperationRequest(
                                stringValue(step.get("operation")),
                                resolvedOperationInputs(testCase, step),
                                runtimeOutputs(id, mapValue(step.get("outputs"))))));
            }
        }
        return List.copyOf(operations);
    }

    private List<SelectedOperation> verifyOperations(Path suiteRoot, Map<String, Object> testCase, String targetName) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : verifyValues(testCase)) {
            Map<String, Object> verify = mapValue(value);
            if (!targetName.equals(stringValue(verify.get("target")))
                    || !"db_record_exists".equals(stringValue(verify.get("type")))) {
                continue;
            }
            String expectedRef = stringValue(verify.get("expected_ref"));
            List<Map<String, Object>> parameters = new ArrayList<>();
            parameters.add(Map.of("name", "query", "ref", stringValue(mapValue(verify.get("query")).get("ref")), "bind_as", "query_ref"));
            Object orderId = resolveValue(suiteRoot, expectedRef + "#/order_id");
            if (!stringValue(orderId).isBlank()) {
                parameters.add(Map.of("name", "order_id", "ref", orderId, "bind_as", "params.order_id"));
            }
            Object minRows = resolveValue(suiteRoot, expectedRef + "#/min_rows");
            if (!stringValue(minRows).isBlank()) {
                parameters.add(Map.of("name", "min_rows", "ref", minRows, "bind_as", "expected.min_rows"));
            } else {
                Object expectedRowCount = resolveValue(suiteRoot, expectedRef + "#/expected_row_count");
                if (!stringValue(expectedRowCount).isBlank()) {
                    parameters.add(Map.of("name", "row_count", "ref", expectedRowCount, "bind_as", "expected.row_count"));
                }
            }
            String id = stringValue(verify.get("id"));
            operations.add(new SelectedOperation(
                    id.isBlank() ? "db_record_exists" : id,
                    new ProviderOperationRequest("db_record_exists", List.copyOf(parameters), runtimeOutputs(id, Map.of()))));
        }
        return List.copyOf(operations);
    }

    private List<SelectedOperation> cleanupOperations(Map<String, Object> testCase, String targetName) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : listValue(mapValue(testCase.get("cleanup")).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            if (targetName.equals(stringValue(operation.get("target")))
                    && "db_cleanup".equals(stringValue(operation.get("operation")))) {
                String id = stringValue(operation.get("id"));
                operations.add(new SelectedOperation(
                        id.isBlank() ? "db_cleanup" : id,
                        new ProviderOperationRequest(
                                stringValue(operation.get("operation")),
                                resolvedOperationInputs(testCase, operation),
                                runtimeOutputs(id, Map.of()))));
            }
        }
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("cleanup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            if (targetName.equals(stringValue(fixture.get("target")))
                    && "db_cleanup".equals(stringValue(fixture.get("operation")))) {
                operations.add(new SelectedOperation(
                        entry.getKey(),
                        new ProviderOperationRequest(
                                stringValue(fixture.get("operation")),
                                resolvedParameters(testCase, listValue(fixture.get("parameters"))),
                                runtimeOutputs(entry.getKey(), Map.of()))));
            }
        }
        return List.copyOf(operations);
    }

    private Map<String, Object> runtimeOutputs(String operationId, Map<String, Object> outputs) {
        Map<String, Object> resolved = new LinkedHashMap<>(outputs);
        resolved.put("_operation_id", operationId);
        resolved.put("_strict_params", true);
        return resolved;
    }

    private List<Map<String, Object>> resolvedParameters(Map<String, Object> testCase, List<Object> parameters) {
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Object value : parameters) {
            Map<String, Object> parameter = new LinkedHashMap<>(mapValue(value));
            parameter.put("ref", resolveRef(testCase,
                    firstNonBlank(stringValue(parameter.get("ref")), stringValue(parameter.get("value")))));
            resolved.add(parameter);
        }
        return List.copyOf(resolved);
    }

    private List<Map<String, Object>> resolvedOperationInputs(Map<String, Object> testCase, Map<String, Object> operation) {
        if (!(operation.get("inputs") instanceof Map<?, ?> inputs)) {
            return resolvedParameters(testCase, listValue(operation.get("parameters")));
        }
        List<Object> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : inputs.entrySet()) {
            Map<String, Object> parameter = new LinkedHashMap<>(mapValue(entry.getValue()));
            parameter.putIfAbsent("name", stringValue(entry.getKey()));
            parameter.put("bind_as", stringValue(entry.getKey()));
            parameters.add(parameter);
        }
        return resolvedParameters(testCase, parameters);
    }

    private List<Object> executeOperationValues(Map<String, Object> testCase) {
        Object execute = testCase.get("execute");
        if (execute instanceof Map<?, ?> executeMap) {
            return listValue(executeMap.get("operations"));
        }
        return listValue(execute);
    }

    private List<Object> verifyValues(Map<String, Object> testCase) {
        Object verify = testCase.get("verify");
        if (verify instanceof Map<?, ?> verifyMap) {
            return listValue(verifyMap.get("checks"));
        }
        return listValue(verify);
    }

    private String resolveRef(Map<String, Object> testCase, String ref) {
        if (!ref.startsWith("${data.") || !ref.endsWith("}")) {
            return ref;
        }
        String path = ref.substring("${data.".length(), ref.length() - 1);
        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            String resolved = stringValue(mapValue(mapValue(testCase.get("data")).get(parts[0])).get("ref"));
            return resolved.isBlank() ? ref : resolved;
        }
        if (parts.length != 2) {
            return ref;
        }
        return stringValue(mapValue(mapValue(mapValue(testCase.get("data_binding")).get(parts[0])).get(parts[1])).get("ref"));
    }

    private Object resolveValue(Path suiteRoot, String ref) {
        if (!ref.contains("#")) {
            return "";
        }
        String[] parts = ref.split("#", 2);
        Path dataPath = suiteRoot.resolve(parts[0]).normalize();
        if (!Files.isRegularFile(dataPath)) {
            return "";
        }
        Object loaded = readObject(dataPath);
        Object current = loaded;
        String pointer = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        for (String part : pointer.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(part);
        }
        return current == null ? "" : current;
    }

    private Map<String, Object> step(String id, String operation, ProviderOperationResult result) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("operation", operation);
        step.put("status", result.passed() ? "passed" : "failed");
        if (!result.outputs().isEmpty()) {
            step.put("outputs", result.outputs());
        }
        return step;
    }

    private Map<String, Object> assertion(String id, String type, String status) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("id", id);
        assertion.put("type", type);
        assertion.put("status", status);
        return assertion;
    }

    private List<String> refs(ProviderOperationResult result) {
        return result.evidence().stream()
                .map(evidence -> evidence.ref())
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
    }

    private void writeAssertion(Path runDir, String id, ProviderOperationResult result) {
        write(runDir.resolve("assertions/" + id + ".yaml"), """
                evidence_type: assertion_result
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                assertion_id: %s
                type: db_record_exists
                status: %s
                row_count: %s
                query_evidence_ref: %s
                """.formatted(
                id,
                result.passed() ? "passed" : "failed",
                result.outputs().getOrDefault("row_count", ""),
                result.outputs().getOrDefault("query_evidence_ref", "")));
    }

    private void writeExecutionLog(Path runDir, RuntimeSelection selection, String status) {
        write(runDir.resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_type: %s
                provider_id: %s
                profile: %s
                runtime_mode: %s
                dialect: %s
                status: %s
                """.formatted(
                        selection.providerType(),
                        selection.providerId(),
                        selection.profile(),
                        selection.runtimeMode(),
                        selection.dialect(),
                        status));
    }

    private void writeBatch(Path runDir, RuntimeSelection selection, String status) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                profile: %s
                provider_type: %s
                provider_id: %s
                runtime_mode: %s
                dialect: %s
                status: %s
                """.formatted(
                        selection.suite().get("suite_id"),
                        selection.batchId(),
                        selection.runId(),
                        selection.testCase().get("test_case_id"),
                        selection.profile(),
                        selection.providerType(),
                        selection.providerId(),
                        selection.runtimeMode(),
                        selection.dialect(),
                        status));
    }

    private void writeEvidenceIndex(Path runDir, RuntimeSelection selection, List<String> evidenceRefs) {
        write(runDir.resolve("evidence_index.yaml"), EvidenceIndexFormatter.format(
                new EvidenceIndexFormatter.Context(
                        stringValue(selection.suite().get("suite_id")),
                        selection.batchId(),
                        selection.runId(),
                        stringValue(selection.testCase().get("test_case_id")),
                        selection.profile(),
                        selection.providerType(),
                        selection.providerId()),
                runDir,
                evidenceRefs));
    }

    private void writeFailureDetail(
            Path runDir,
            RuntimeSelection selection,
            ProviderFailure failure,
            ProviderFailure cleanupFailure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("OPERATION_FAILED", "FRAMEWORK_ERROR", "JDBC capability failed.", "Review provider evidence.")
                : failure;
        StringBuilder detail = new StringBuilder();
        detail.append("evidence_type: failure_detail\n");
        detail.append("evidence_classification: framework_provider_capability_only\n");
        detail.append("downstream_release_evidence: false\n");
        detail.append("provider_type: ").append(selection.providerType()).append('\n');
        detail.append("provider_id: ").append(selection.providerId()).append('\n');
        detail.append("profile: ").append(selection.profile()).append('\n');
        detail.append("runtime_mode: ").append(selection.runtimeMode()).append('\n');
        detail.append("dialect: ").append(selection.dialect()).append('\n');
        detail.append("classification: ").append(resolvedFailure.classification()).append('\n');
        detail.append("reason: ").append(resolvedFailure.reason()).append('\n');
        detail.append("owner_action: ").append(resolvedFailure.ownerAction()).append('\n');
        if (cleanupFailure != null) {
            detail.append("cleanup_failure:\n");
            detail.append("  code: ").append(cleanupFailure.code()).append('\n');
            detail.append("  classification: ").append(cleanupFailure.classification()).append('\n');
            detail.append("  reason: ").append(cleanupFailure.reason()).append('\n');
        }
        detail.append("masking:\n  raw_secret_found: false\n");
        write(runDir.resolve("provider-evidence/jdbc/failure_detail.yaml"), detail.toString());
    }

    private Path writeResult(
            Path runDir,
            RuntimeSelection selection,
            String profile,
            String status,
            Map<String, Object> outputs,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<String> evidenceRefs,
            ProviderFailure failure,
            ProviderFailure cleanupFailure,
            Instant startedAt,
            Instant finishedAt) {
        String started = startedAt.toString();
        String finished = finishedAt.toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework_version", "v0.2");
        result.put("dsl_version", selection.testCase().get("dsl_version"));
        result.put("suite_id", selection.suite().get("suite_id"));
        result.put("batch_id", selection.batchId());
        result.put("run_id", selection.runId());
        result.put("test_case_id", selection.testCase().get("test_case_id"));
        result.put("profile", profile);
        result.put("environment", profile);
        result.put("provider_type", selection.providerType());
        result.put("provider_id", selection.providerId());
        result.put("runtime_mode", selection.runtimeMode());
        result.put("dialect", selection.dialect());
        result.put("status", status);
        result.put("start_time", started);
        result.put("end_time", finished);
        result.put("duration_ms", Duration.between(startedAt, finishedAt).toMillis());
        result.put("timestamps", Map.of("started_at", started, "finished_at", finished));
        result.put("labels", selection.testCase().get("labels"));
        result.put("source_refs", selection.testCase().get("source_refs"));
        result.put("step_results", stepResults);
        result.put("steps", stepResults);
        result.put("verify_results", verifyResults);
        result.put("provider_results", List.of(providerResult(selection, profile, status, outputs)));
        result.put("provider_evidence_refs", distinct(evidenceRefs));
        result.put("evidence_refs", distinct(evidenceRefs));
        Map<String, Object> failureObject = new LinkedHashMap<>();
        failureObject.put("code", failure == null ? null : failure.code());
        failureObject.put("classification", failure == null ? null : failure.classification());
        failureObject.put("reason", failure == null ? null : failure.reason());
        failureObject.put("owner_action", failure == null ? null : failure.ownerAction());
        if (cleanupFailure != null) {
            failureObject.put("cleanup_failure", Map.of(
                    "code", cleanupFailure.code(),
                    "classification", cleanupFailure.classification(),
                    "reason", cleanupFailure.reason(),
                    "owner_action", cleanupFailure.ownerAction()));
        }
        result.put("failure", failureObject);
        Path resultJson = runDir.resolve("result.json");
        write(resultJson, toJson(result) + "\n");
        return resultJson;
    }

    private Map<String, Object> providerResult(
            RuntimeSelection selection,
            String profile,
            String status,
            Map<String, Object> outputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", selection.providerId());
        result.put("provider_type", selection.providerType());
        result.put("profile", profile);
        result.put("runtime_mode", selection.runtimeMode());
        result.put("dialect", selection.dialect());
        result.put("resolved_operation_result", Map.of(
                "operation", "jdbc_capability_flow",
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private Map<String, Map<String, Object>> readDirectoryByField(Path directory, String field) {
        Map<String, Map<String, Object>> documents = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                Map<String, Object> document = readMap(path);
                String key = stringValue(document.get(field));
                if (!key.isBlank()) {
                    documents.put(key, document);
                }
            }
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read directory: " + directory, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Path path) {
        try {
            Object loaded = yaml.load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JDBC capability artifact: " + path, e);
        }
    }

    private Object readObject(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read JDBC capability artifact: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private Object valueAtPath(Map<String, Object> map, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
    }

    private List<String> distinct(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void recreateDirectory(Path directory) {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clean generated JDBC output: " + directory, e);
            }
        }
        createDirectories(directory);
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated JDBC output: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JDBC capability output: " + path, e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    public record JdbcRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            String profile,
            String providerId,
            String providerType,
            String runtimeMode,
            String dialect,
            Path resultJson,
            Path evidenceDir,
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static JdbcRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new JdbcRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    profile,
                    "",
                    PROVIDER_TYPE,
                    "",
                    "",
                    null,
                    null,
                    false,
                    List.copyOf(findings));
        }
    }

    private record RuntimeSelection(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String targetName,
            String providerId,
            String providerType,
            String profile,
            String runtimeMode,
            String dialect,
            Map<String, Object> providerContract,
            Map<String, Object> providerInstance,
            Map<String, Object> bindingValues,
            String batchId,
            String runId,
            Path runDir) {
    }

    private record SelectedOperation(String id, ProviderOperationRequest request) {
    }

    private record RunIds(String batchId, String runId) {
    }
}
