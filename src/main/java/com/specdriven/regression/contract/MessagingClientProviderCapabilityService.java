package com.specdriven.regression.contract;

import com.specdriven.regression.summary.SuiteExecutionContext;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import com.specdriven.regression.provider.ibmmq.IbmMqProviderRuntime;
import com.specdriven.regression.provider.kafka.KafkaProviderRuntime;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import com.specdriven.regression.provider.runtime.ProviderRuntimeRegistry;
import com.specdriven.regression.provider.runtime.ProviderRuntimeResolver;
import com.specdriven.regression.provider.runtime.ProviderRuntimeResolver.ProviderRuntimeResolution;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class MessagingClientProviderCapabilityService {

    private static final List<String> SUPPORTED_PROVIDER_TYPES = List.of("kafka", "ibm_mq");
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public MessagingClientRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        return run(suiteManifest, requestedProfile, SuiteExecutionContext.standalone(requestedProfile, outputBase, "MESSAGING"));
    }

    public MessagingClientRunResult run(
            Path suiteManifest, String requestedProfile, SuiteExecutionContext executionContext) {
        requireMatchingProfile(requestedProfile, executionContext);
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return MessagingClientRunResult.blocked(validation.suiteId(), requestedProfile, "", validation.findings());
        }
        List<String> providerTypes = validation.providerTypesUsed();
        String providerTypeLabel = providerTypeLabel(providerTypes);
        if (providerTypes.isEmpty() || providerTypes.stream().anyMatch(providerType -> !SUPPORTED_PROVIDER_TYPES.contains(providerType))) {
            return MessagingClientRunResult.blocked(validation.suiteId(), requestedProfile, "", List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    providerTypeLabel,
                    requestedProfile,
                    "",
                    "Messaging client provider capability mode supports only provider_type `kafka` and/or `ibm_mq`.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return MessagingClientRunResult.blocked(validation.suiteId(), requestedProfile, providerTypeLabel, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    providerTypeLabel,
                    "",
                    "",
                    "Provide --profile for messaging client provider capability run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile, providerTypeLabel);
        if (!profileFindings.isEmpty()) {
            return MessagingClientRunResult.blocked(validation.suiteId(), requestedProfile, providerTypeLabel, profileFindings);
        }
        List<ContractFinding> suiteShapeFindings = suiteShapeFindings(suiteManifest, requestedProfile, providerTypes);
        if (!suiteShapeFindings.isEmpty()) {
            return MessagingClientRunResult.blocked(validation.suiteId(), requestedProfile, providerTypeLabel, suiteShapeFindings);
        }

        List<RuntimeSelection> selections = selectRuntimes(suiteManifest, requestedProfile, executionContext, providerTypes);
        if (selections.isEmpty()) {
            return MessagingClientRunResult.blocked(validation.suiteId(), requestedProfile, providerTypeLabel, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_messaging_target",
                    "",
                    providerTypeLabel,
                    requestedProfile,
                    "",
                    "Add DSL targets using provider_type `kafka` or `ibm_mq` for profile `" + requestedProfile + "`.")));
        }
        RuntimeSelection firstSelection = selections.get(0);
        recreateDirectory(firstSelection.runDir());

        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(
                new ProviderRuntimeRegistry(Map.of(
                        "kafka", new KafkaProviderRuntime(),
                        "ibm_mq", new IbmMqProviderRuntime())));

        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        RuntimeSelection failureSelection = null;
        boolean providerRuntimeExecuted = false;
        Map<String, Object> aggregateOutputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> testResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();

        for (RuntimeSelection selection : selections) {
            TestExecution execution = executeTestCase(resolver, selection, requestedProfile);
            providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeExecuted();
            aggregateOutputs.put(safe(stringValue(selection.testCase().get("test_case_id"))), execution.outputs());
            stepResults.addAll(execution.stepResults());
            verifyResults.addAll(execution.verifyResults());
            testResults.add(execution.testResult());
            providerResults.add(providerResult(selection, requestedProfile, execution.status(), execution.outputs()));
            evidenceRefs.addAll(execution.evidenceRefs());
            if (!execution.passed() && "passed".equals(status)) {
                status = "failed";
                failure = execution.failure();
                failureSelection = selection;
            }
        }

        Instant finishedAt = Instant.now();
        writeExecutionLog(firstSelection.runDir(), firstSelection, status);
        evidenceRefs.add("logs/execution.log");
        writeBatch(firstSelection.runDir(), firstSelection, status, selections.size());
        evidenceRefs.add("batch/batch.yaml");
        if ("failed".equals(status)) {
            RuntimeSelection failedSelection = failureSelection == null ? firstSelection : failureSelection;
            writeFailureDetail(firstSelection.runDir(), failedSelection, failure);
            evidenceRefs.add("provider-evidence/" + failedSelection.providerType() + "/failure_detail.yaml");
        }
        writeEvidenceIndex(firstSelection.runDir(), firstSelection, selections.size(), evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = writeResult(
                firstSelection.runDir(),
                selections,
                requestedProfile,
                status,
                aggregateOutputs,
                stepResults,
                verifyResults,
                testResults,
                providerResults,
                evidenceRefs,
                failure,
                startedAt,
                finishedAt);
        return new MessagingClientRunResult(
                "passed".equals(status),
                status,
                stringValue(firstSelection.suite().get("suite_id")),
                firstSelection.batchId(),
                firstSelection.runId(),
                topLevelTestCaseId(firstSelection, selections.size()),
                requestedProfile,
                firstSelection.providerId(),
                firstSelection.providerType(),
                firstSelection.runtimeMode(),
                firstSelection.destinationLabel(),
                firstSelection.destination(),
                resultJson,
                firstSelection.runDir(),
                selections.size(),
                providerRuntimeExecuted,
                ProviderCapabilityResultWriter.providerSummary(providerResults),
                providerOutputLines(firstSelection, providerResults),
                List.of());
    }

    private TestExecution executeTestCase(
            ProviderRuntimeResolver resolver,
            RuntimeSelection selection,
            String requestedProfile) {
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
        Instant testStartedAt = Instant.now();
        String testStatus = "passed";
        ProviderFailure testFailure = null;
        boolean providerRuntimeExecuted = false;
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        String operationPrefix = safe(stringValue(selection.testCase().get("test_case_id"))) + "__";

        for (SelectedOperation operation : executeOperations(
                selection.testCase(),
                selection.targetName(),
                selection.suiteRoot(),
                testStartedAt,
                operationPrefix)) {
            OperationExecution execution = executeOperation(resolver, context, operation);
            ProviderOperationResult result = execution.result();
            providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeInvoked();
            recordOperationOutput(outputs, operation, result);
            stepResults.add(step(operation.id(), operation.request().operation(), result));
            evidenceRefs.addAll(refs(result));
            if (!result.passed()) {
                testStatus = "failed";
                testFailure = result.failure();
                break;
            }
        }
        if ("passed".equals(testStatus)) {
            for (SelectedOperation operation : verifyOperations(
                    selection.testCase(),
                    selection.targetName(),
                    selection.suiteRoot(),
                    testStartedAt,
                    selection.providerType(),
                    operationPrefix,
                    outputs)) {
                OperationExecution execution = executeOperation(resolver, context, operation);
                ProviderOperationResult result = execution.result();
                providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeInvoked();
                recordOperationOutput(outputs, operation, result);
                verifyResults.add(assertion(operation.id(), operation.request().operation(), result, selection.providerType()));
                evidenceRefs.addAll(refs(result));
                writeAssertion(selection.runDir(), operation.id(), operation.request().operation(), result, selection.providerType());
                evidenceRefs.add("assertions/" + operation.id() + ".yaml");
                if (!result.passed()) {
                    testStatus = "failed";
                    testFailure = result.failure();
                    break;
                }
            }
        }

        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_id", selection.providerId());
        testResult.put("provider_type", selection.providerType());
        testResult.put("status", testStatus);
        testResult.put("step_count", stepResults.size());
        testResult.put("verify_count", verifyResults.size());
        if (testFailure != null) {
            testResult.put("failure_code", testFailure.code());
            testResult.put("failure_classification", testFailure.classification());
        }
        return new TestExecution(
                "passed".equals(testStatus),
                testStatus,
                providerRuntimeExecuted,
                outputs,
                stepResults,
                verifyResults,
                testResult,
                evidenceRefs,
                testFailure);
    }

    private OperationExecution executeOperation(
            ProviderRuntimeResolver resolver,
            ProviderExecutionContext context,
            SelectedOperation operation) {
        ProviderRuntimeResolution resolution = resolver.resolve(context, operation.request());
        if (!resolution.valid()) {
            return new OperationExecution(
                    ProviderOperationResult.failed(Map.of(), List.of(), resolution.failure()),
                    false);
        }
        return new OperationExecution(resolution.runtime().execute(context, operation.request()), true);
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

    private List<RuntimeSelection> selectRuntimes(
            Path suiteManifest,
            String requestedProfile,
            SuiteExecutionContext executionContext,
            List<String> expectedProviderTypes) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        Map<String, Map<String, Object>> contractsByType =
                readDirectoryByField(frameworkProviderContractsDirectory(suiteRoot), "provider_type");
        RunIds runIds = new RunIds(executionContext.parentBatchId(),
                executionContext.newRunId(providerOutputLabel(expectedProviderTypes)));
        Path runDir = executionContext.childRunRoot(stringValue(suite.get("suite_id")), runIds.runId());
        List<RuntimeSelection> selections = new ArrayList<>();
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
                if (!expectedProviderTypes.contains(providerType)) {
                    continue;
                }
                Map<String, Object> providerContract = contractsByType.get(providerType);
                if (providerContract == null) {
                    continue;
                }
                Map<String, Object> providerBinding =
                        runtimeBindingResolver.providerBinding(suiteRoot, requestedProfile, providerId);
                Map<String, Object> bindingValues = mapValue(providerBinding.get("binding_values"));
                String destinationLabel = destinationLabel(providerType);
                selections.add(new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        targetEntry.getKey(),
                        providerId,
                        providerType,
                        requestedProfile,
                        stringValue(providerBinding.get("runtime_mode")),
                        destinationLabel,
                        bindingText(bindingValues, destinationLabel),
                        providerContract,
                        providerInstance,
                        bindingValues,
                        runIds.batchId(),
                        runIds.runId(),
                        runDir));
            }
        }
        return List.copyOf(selections);
    }

    private List<ContractFinding> requestedProfileFindings(Path suiteManifest, String requestedProfile, String providerType) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<SuiteProfileGate.TestCaseDocument> testCases = new ArrayList<>();
        for (Object testRef : listValue(suite.get("tests"))) {
            Path testCasePath = suiteRoot.resolve(stringValue(testRef)).normalize();
            testCases.add(new SuiteProfileGate.TestCaseDocument(testCasePath, readMap(testCasePath)));
        }
        return SuiteProfileGate.validate(suiteManifest, suite, testCases, requestedProfile, providerType);
    }

    private List<ContractFinding> suiteShapeFindings(Path suiteManifest, String requestedProfile, List<String> providerTypes) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<ContractFinding> findings = new ArrayList<>();
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        List<Object> testRefs = listValue(suite.get("tests"));
        for (int index = 0; index < testRefs.size(); index++) {
            Map<String, Object> testCase = readMap(suiteRoot.resolve(stringValue(testRefs.get(index))).normalize());
            long matchingTargets = mapValue(testCase.get("targets")).entrySet().stream()
                    .filter(targetEntry -> targetReferencedByLifecycle(testCase, targetEntry.getKey()))
                    .filter(targetEntry -> {
                        String providerId = stringValue(mapValue(targetEntry.getValue()).get("provider_id"));
                        return providerTypes.contains(stringValue(mapValue(instancesById.get(providerId)).get("provider_type")));
                    })
                    .count();
            if (matchingTargets != 1) {
                findings.add(new ContractFinding(
                        suiteManifest.toString(),
                        "tests[" + index + "].targets",
                        "unsupported_messaging_suite_shape",
                        "",
                        providerTypeLabel(providerTypes),
                        requestedProfile,
                        "",
                        "Messaging client provider capability currently supports exactly one runtime target per test case; split the test case or add multi-target aggregation support before runtime execution."));
            }
        }
        return List.copyOf(findings);
    }

    private Path frameworkProviderContractsDirectory(Path suiteRoot) {
        return FrameworkProviderContractCatalog.resolveDirectory(suiteRoot, FRAMEWORK_PROVIDER_CONTRACTS);
    }

    private boolean targetReferencedByLifecycle(Map<String, Object> testCase, String targetName) {
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
        return false;
    }

    private void requireMatchingProfile(String profile, SuiteExecutionContext context) {
        if (!context.matchesRequestedProfile(profile)) throw new IllegalArgumentException(
                "Execution context profile mismatch: context=" + context.profile() + ", requested=" + profile
                        + ". Use the parent suite profile for all child runtimes.");
    }

    private RunIds newRunIds(String providerType) {
        String providerLabel = "ibm_mq".equals(providerType) ? "IBMMQ" : providerType.toUpperCase();
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-" + providerLabel + "-" + suffix, "RUN-" + providerLabel + "-" + suffix);
    }

    private String providerOutputLabel(List<String> providerTypes) {
        return providerTypes.size() == 1 ? providerTypes.get(0) : "messaging";
    }

    private String providerTypeLabel(List<String> providerTypes) {
        return providerTypes.isEmpty() ? "" : String.join(",", providerTypes);
    }

    private List<SelectedOperation> executeOperations(
            Map<String, Object> testCase,
            String targetName,
            Path suiteRoot,
            Instant testStartTime,
            String operationPrefix) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : executeOperationValues(testCase)) {
            Map<String, Object> step = mapValue(value);
            if (targetName.equals(stringValue(step.get("target")))) {
                String id = stringValue(step.get("id"));
                String operationId = operationPrefix + (id.isBlank() ? "execute" : id);
                operations.add(new SelectedOperation(
                        operationId,
                        new ProviderOperationRequest(
                                stringValue(step.get("operation")),
                                resolvedOperationInputs(testCase, suiteRoot, step, Map.of()),
                                runtimeOutputs(operationId, mapValue(step.get("outputs")), testStartTime))));
            }
        }
        return List.copyOf(operations);
    }

    private List<SelectedOperation> verifyOperations(
            Map<String, Object> testCase,
            String targetName,
            Path suiteRoot,
            Instant testStartTime,
            String providerType,
            String operationPrefix,
            Map<String, Object> completedOutputs) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : verifyValues(testCase)) {
            Map<String, Object> verify = mapValue(value);
            String type = stringValue(verify.get("type"));
            if (!targetName.equals(stringValue(verify.get("target"))) || !verifyTypes(providerType).contains(type)) {
                continue;
            }
            String id = stringValue(verify.get("id"));
            String operationId = operationPrefix + (id.isBlank() ? type : id);
            operations.add(new SelectedOperation(
                    operationId,
                    new ProviderOperationRequest(
                            type,
                            resolvedOperationInputs(testCase, suiteRoot, verify, completedOutputs),
                            runtimeOutputs(operationId, Map.of(), testStartTime))));
        }
        return List.copyOf(operations);
    }

    private List<String> verifyTypes(String providerType) {
        return "kafka".equals(providerType)
                ? List.of("kafka_observe", "kafka_payload_match")
                : List.of("mq_browse", "mq_message_exists", "mq_payload_match");
    }

    private Map<String, Object> runtimeOutputs(String operationId, Map<String, Object> outputs, Instant testStartTime) {
        Map<String, Object> resolved = new LinkedHashMap<>(outputs);
        resolved.put("_operation_id", operationId);
        resolved.put("_test_start_time", testStartTime.toString());
        return resolved;
    }

    private List<Map<String, Object>> resolvedOperationInputs(
            Map<String, Object> testCase,
            Path suiteRoot,
            Map<String, Object> operation,
            Map<String, Object> completedOutputs) {
        if (!(operation.get("inputs") instanceof Map<?, ?> inputs)) {
            return resolvedParameters(testCase, suiteRoot, listValue(operation.get("parameters")), completedOutputs);
        }
        List<Object> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : inputs.entrySet()) {
            Map<String, Object> parameter = new LinkedHashMap<>(mapValue(entry.getValue()));
            parameter.putIfAbsent("name", stringValue(entry.getKey()));
            parameter.put("bind_as", stringValue(entry.getKey()));
            parameters.add(parameter);
        }
        return resolvedParameters(testCase, suiteRoot, parameters, completedOutputs);
    }

    private List<Map<String, Object>> resolvedParameters(
            Map<String, Object> testCase,
            Path suiteRoot,
            List<Object> parameters,
            Map<String, Object> completedOutputs) {
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Object value : parameters) {
            Map<String, Object> parameter = new LinkedHashMap<>(mapValue(value));
            String ref = resolveDataOrOutputRef(testCase, completedOutputs,
                    firstNonBlank(stringValue(parameter.get("ref")), stringValue(parameter.get("value"))));
            parameter.put("ref", resolveValue(suiteRoot, ref));
            resolved.add(parameter);
        }
        return List.copyOf(resolved);
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

    private String resolveDataOrOutputRef(Map<String, Object> testCase, Map<String, Object> completedOutputs, String ref) {
        if (ref.startsWith("${data.") && ref.endsWith("}")) {
            String path = ref.substring("${data.".length(), ref.length() - 1);
            String resolved = stringValue(mapValue(mapValue(testCase.get("data")).get(path)).get("ref"));
            return resolved.isBlank() ? ref : resolved;
        }
        if (ref.startsWith("${execute.") && ref.endsWith("}")) {
            String[] parts = ref.substring(2, ref.length() - 1).split("\\.");
            if (parts.length == 4 && "outputs".equals(parts[2])) {
                String operationId = parts[1];
                String outputName = parts[3];
                Object resolved = completedOutput(completedOutputs, operationId, outputName);
                if (resolved != null) {
                    return stringValue(resolved);
                }
            }
        }
        return ref;
    }

    private Object completedOutput(Map<String, Object> completedOutputs, String operationId, String outputName) {
        Object direct = completedOutputFromOperation(completedOutputs.get(operationId), outputName);
        if (direct != null) {
            return direct;
        }
        String suffix = "__" + operationId;
        for (Map.Entry<String, Object> entry : completedOutputs.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                Object resolved = completedOutputFromOperation(entry.getValue(), outputName);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private Object completedOutputFromOperation(Object operationOutput, String outputName) {
        Map<String, Object> operation = mapValue(operationOutput);
        Map<String, Object> outputs = mapValue(operation.get("outputs"));
        return outputs.get(outputName);
    }

    private Object resolveValue(Path suiteRoot, String ref) {
        if (!ref.contains("#")) {
            return ref;
        }
        String[] parts = ref.split("#", 2);
        Path dataPath = suiteFile(suiteRoot, parts[0]);
        if (!Files.isRegularFile(dataPath)) {
            return "";
        }
        Object current = readObject(dataPath);
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

    private Path suiteFile(Path suiteRoot, String ref) {
        Path root = suiteRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(ref).normalize();
        return resolved.startsWith(root) ? resolved : root.resolve("__ref_outside_suite_root__");
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

    private Map<String, Object> assertion(String id, String type, ProviderOperationResult result, String providerType) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("id", id);
        assertion.put("type", type);
        assertion.put("status", result.passed() ? "passed" : "failed");
        assertion.put("matched", result.outputs().getOrDefault("matched", false));
        assertion.put("evidence_ref", result.outputs().getOrDefault(evidenceOutputKey(providerType), ""));
        return assertion;
    }

    private List<String> refs(ProviderOperationResult result) {
        return result.evidence().stream()
                .map(evidence -> evidence.ref())
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
    }

    private void writeAssertion(Path runDir, String id, String type, ProviderOperationResult result, String providerType) {
        write(runDir.resolve("assertions/" + id + ".yaml"), """
                evidence_type: assertion_result
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                assertion_id: %s
                type: %s
                status: %s
                matched: %s
                observed_count: %s
                provider_evidence_ref: %s
                """.formatted(
                id,
                type,
                result.passed() ? "passed" : "failed",
                result.outputs().getOrDefault("matched", false),
                result.outputs().getOrDefault("observed_count", result.outputs().getOrDefault("browse_count", 0)),
                result.outputs().getOrDefault(evidenceOutputKey(providerType), "")));
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
                %s: %s
                status: %s
                """.formatted(
                selection.providerType(),
                selection.providerId(),
                selection.profile(),
                selection.runtimeMode(),
                selection.destinationLabel(),
                selection.destination(),
                status));
    }

    private void writeBatch(Path runDir, RuntimeSelection selection, String status, int testCount) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                test_count: %s
                profile: %s
                provider_type: %s
                provider_id: %s
                runtime_mode: %s
                %s: %s
                status: %s
                """.formatted(
                selection.suite().get("suite_id"),
                selection.batchId(),
                selection.runId(),
                topLevelTestCaseId(selection, testCount),
                testCount,
                selection.profile(),
                selection.providerType(),
                selection.providerId(),
                selection.runtimeMode(),
                selection.destinationLabel(),
                selection.destination(),
                status));
    }

    private void writeEvidenceIndex(Path runDir, RuntimeSelection selection, int testCount, List<String> evidenceRefs) {
        write(runDir.resolve("evidence_index.yaml"), EvidenceIndexFormatter.format(
                new EvidenceIndexFormatter.Context(
                        stringValue(selection.suite().get("suite_id")),
                        selection.batchId(),
                        selection.runId(),
                        topLevelTestCaseId(selection, testCount),
                        selection.profile(),
                        selection.providerType(),
                        selection.providerId()),
                runDir,
                evidenceRefs));
    }

    private void writeFailureDetail(Path runDir, RuntimeSelection selection, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("OPERATION_FAILED", "FRAMEWORK_ERROR", "Messaging capability failed.", "Review provider evidence.")
                : failure;
        write(runDir.resolve("provider-evidence/" + selection.providerType() + "/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_type: %s
                provider_id: %s
                profile: %s
                runtime_mode: %s
                %s: %s
                failure_code: %s
                classification: %s
                reason: %s
                owner_action: %s
                masking:
                  raw_secret_found: false
                """.formatted(
                selection.providerType(),
                selection.providerId(),
                selection.profile(),
                selection.runtimeMode(),
                selection.destinationLabel(),
                selection.destination(),
                resolvedFailure.code(),
                resolvedFailure.classification(),
                resolvedFailure.reason(),
                resolvedFailure.ownerAction()));
    }

    private Path writeResult(
            Path runDir,
            List<RuntimeSelection> selections,
            String profile,
            String status,
            Map<String, Object> outputs,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<Map<String, Object>> testResults,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs,
            ProviderFailure failure,
            Instant startedAt,
            Instant finishedAt) {
        RuntimeSelection selection = selections.get(0);
        return ProviderCapabilityResultWriter.write(runDir, new ProviderCapabilityResultWriter.ResultDocument(
                selection.testCase().get("dsl_version"),
                selection.suite().get("suite_id"),
                selection.batchId(),
                selection.runId(),
                topLevelTestCaseId(selection, testResults.size()),
                testResults.size(),
                profile,
                profile,
                status,
                startedAt,
                finishedAt,
                resultLabels(selections),
                resultSourceRefs(selections),
                stepResults,
                verifyResults,
                testResults,
                providerResults,
                distinct(evidenceRefs),
                distinct(evidenceRefs),
                ProviderCapabilityResultWriter.singleProviderRootFields(providerResults),
                failure,
                null,
                true));
    }

    private Map<String, Object> resultLabels(List<RuntimeSelection> selections) {
        if (selections.size() == 1) {
            Object labels = selections.get(0).testCase().get("labels");
            return labels instanceof Map<?, ?> map ? typedMap(map) : Map.of();
        }
        Map<String, Object> labels = new LinkedHashMap<>();
        List<String> capabilities = selections.stream()
                .map(selection -> {
                    Object testLabels = selection.testCase().get("labels");
                    if (testLabels instanceof Map<?, ?> map && map.get("capability") != null) {
                        return stringValue(map.get("capability"));
                    }
                    return selection.providerType();
                })
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (!capabilities.isEmpty()) {
            labels.put("capabilities", capabilities);
        }
        List<String> classifications = selections.stream()
                .map(selection -> {
                    Object testLabels = selection.testCase().get("labels");
                    if (testLabels instanceof Map<?, ?> map && map.get("evidence_classification") != null) {
                        return stringValue(map.get("evidence_classification"));
                    }
                    return "";
                })
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (classifications.size() == 1) {
            labels.put("evidence_classification", classifications.get(0));
        } else if (!classifications.isEmpty()) {
            labels.put("evidence_classifications", classifications);
        }
        return labels;
    }

    private Map<String, Object> resultSourceRefs(List<RuntimeSelection> selections) {
        if (selections.size() == 1) {
            Object sourceRefs = selections.get(0).testCase().get("source_refs");
            return sourceRefs instanceof Map<?, ?> map ? typedMap(map) : Map.of();
        }
        Map<String, List<String>> groupedRefs = new LinkedHashMap<>();
        for (RuntimeSelection selection : selections) {
            Object sourceRefs = selection.testCase().get("source_refs");
            if (sourceRefs instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = stringValue(entry.getKey());
                    String value = stringValue(entry.getValue());
                    if (!key.isBlank() && !value.isBlank()) {
                        groupedRefs.computeIfAbsent(key, unused -> new ArrayList<>());
                        if (!groupedRefs.get(key).contains(value)) {
                            groupedRefs.get(key).add(value);
                        }
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        groupedRefs.forEach((key, values) -> result.put(key, values.size() == 1 ? values.get(0) : values));
        return result;
    }

    private Map<String, Object> typedMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(stringValue(key), value));
        return result;
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
        result.put(selection.destinationLabel(), selection.destination());
        result.put("resolved_operation_result", Map.of(
                "operation", selection.providerType() + "_client_capability_flow",
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private List<String> providerOutputLines(RuntimeSelection firstSelection, List<Map<String, Object>> providerResults) {
        List<Map<String, Object>> providerSummary = ProviderCapabilityResultWriter.providerSummary(providerResults);
        if (providerSummary.size() == 1) {
            return List.of(
                    "provider_type: " + firstSelection.providerType(),
                    "provider_id: " + firstSelection.providerId(),
                    "runtime_mode: " + firstSelection.runtimeMode(),
                    firstSelection.destinationLabel() + ": " + firstSelection.destination());
        }
        List<String> lines = new ArrayList<>();
        lines.add("provider_summary:");
        for (Map<String, Object> provider : providerSummary) {
            lines.add("  - provider_type: " + provider.get("provider_type"));
            lines.add("    provider_id: " + provider.get("provider_id"));
            lines.add("    runtime_mode: " + provider.get("runtime_mode"));
            if (provider.containsKey("topic")) {
                lines.add("    topic: " + provider.get("topic"));
            }
            if (provider.containsKey("queue")) {
                lines.add("    queue: " + provider.get("queue"));
            }
        }
        return lines;
    }

    private String topLevelTestCaseId(RuntimeSelection selection, int testCount) {
        return ProviderCapabilityResultWriter.topLevelTestCaseId(
                selection.suite().get("suite_id"), selection.testCase().get("test_case_id"), testCount);
    }

    private String destinationLabel(String providerType) {
        return "kafka".equals(providerType) ? "topic" : "queue";
    }

    private String evidenceOutputKey(String providerType) {
        return "kafka".equals(providerType) ? "event_evidence_ref" : "mq_evidence_ref";
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
            throw new UncheckedIOException("Failed to read messaging capability artifact: " + path, e);
        }
    }

    private Object readObject(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read messaging capability artifact: " + path, e);
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

    private List<String> distinct(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String bindingText(Map<String, Object> bindingValues, String key) {
        Object value = bindingValues.get(key);
        if (value instanceof Map<?, ?> map) {
            return firstNonBlank(stringValue(map.get("local_ref")), stringValue(map.get("secret_ref")), stringValue(map.get("generated_ref")));
        }
        return stringValue(value);
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
                throw new UncheckedIOException("Failed to clean generated messaging output: " + directory, e);
            }
        }
        createDirectories(directory);
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated messaging output: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write messaging capability output: " + path, e);
        }
    }

    public record MessagingClientRunResult(
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
            String destinationLabel,
            String destination,
            Path resultJson,
            Path evidenceDir,
            int testCount,
            boolean providerRuntimeExecuted,
            List<Map<String, Object>> providerSummary,
            List<String> providerOutputLines,
            List<ContractFinding> findings) {

        static MessagingClientRunResult blocked(
                String suiteId,
                String profile,
                String providerType,
                List<ContractFinding> findings) {
            return new MessagingClientRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    profile,
                    "",
                    providerType,
                    "",
                    "",
                    "",
                    null,
                    null,
                    0,
                    false,
                    List.of(),
                    List.of(),
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
            String destinationLabel,
            String destination,
            Map<String, Object> providerContract,
            Map<String, Object> providerInstance,
            Map<String, Object> bindingValues,
            String batchId,
            String runId,
            Path runDir) {
    }

    private record SelectedOperation(String id, ProviderOperationRequest request) {
    }

    private record OperationExecution(ProviderOperationResult result, boolean providerRuntimeInvoked) {
    }

    private record TestExecution(
            boolean passed,
            String status,
            boolean providerRuntimeExecuted,
            Map<String, Object> outputs,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            Map<String, Object> testResult,
            List<String> evidenceRefs,
            ProviderFailure failure) {
    }

    private record RunIds(String batchId, String runId) {
    }
}
