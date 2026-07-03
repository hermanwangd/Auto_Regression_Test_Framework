package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import com.specdriven.regression.provider.nats.NatsProviderRuntime;
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

public class NatsProviderCapabilityService {

    private static final String PROVIDER_TYPE = "nats";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public NatsRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return NatsRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!validation.providerTypesUsed().equals(List.of(PROVIDER_TYPE))) {
            return NatsRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "NATS provider capability mode supports provider_type `nats` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return NatsRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    PROVIDER_TYPE,
                    "",
                    "",
                    "Provide --profile for NATS provider capability run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile);
        if (!profileFindings.isEmpty()) {
            return NatsRunResult.blocked(validation.suiteId(), requestedProfile, profileFindings);
        }

        List<RuntimeSelection> selections = selectNatsRuntimes(suiteManifest, requestedProfile, outputBase);
        if (selections.isEmpty()) {
            return NatsRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_nats_target",
                    "",
                    PROVIDER_TYPE,
                    requestedProfile,
                    "",
                    "Add a DSL target using provider_type `nats` for profile `" + requestedProfile + "`.")));
        }
        RuntimeSelection firstSelection = selections.get(0);
        recreateDirectory(firstSelection.runDir());

        NatsProviderRuntime natsRuntime = new NatsProviderRuntime();
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(
                new ProviderRuntimeRegistry(Map.of(PROVIDER_TYPE, natsRuntime)));

        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        RuntimeSelection failureSelection = null;
        boolean providerRuntimeExecuted = false;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> testResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();

        boolean multiTestSuite = selections.size() > 1;
        for (RuntimeSelection selection : selections) {
            TestExecution execution = executeTestCase(resolver, selection, requestedProfile, multiTestSuite);
            providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeExecuted();
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
            evidenceRefs.add("provider-evidence/nats/failure_detail.yaml");
        }
        writeEvidenceIndex(firstSelection.runDir(), firstSelection, selections.size(), evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = writeResult(
                firstSelection.runDir(),
                firstSelection,
                requestedProfile,
                topLevelTestCaseId(firstSelection, selections.size()),
                selections.size(),
                status,
                stepResults,
                verifyResults,
                testResults,
                providerResults,
                evidenceRefs,
                failure,
                startedAt,
                finishedAt);
        return new NatsRunResult(
                "passed".equals(status),
                status,
                stringValue(firstSelection.suite().get("suite_id")),
                firstSelection.batchId(),
                firstSelection.runId(),
                topLevelTestCaseId(firstSelection, selections.size()),
                selections.size(),
                requestedProfile,
                firstSelection.providerId(),
                firstSelection.providerType(),
                firstSelection.runtimeMode(),
                firstSelection.subject(),
                resultJson,
                firstSelection.runDir(),
                providerRuntimeExecuted,
                List.of());
    }

    private TestExecution executeTestCase(
            ProviderRuntimeResolver resolver,
            RuntimeSelection selection,
            String requestedProfile,
            boolean multiTestSuite) {
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
        String operationPrefix = multiTestSuite ? safe(stringValue(selection.testCase().get("test_case_id"))) + "__" : "";

        for (SelectedOperation operation : executeOperations(selection.testCase(), selection.targetName(), selection.suiteRoot(), testStartedAt, operationPrefix)) {
            ProviderOperationResult result = executeOperation(resolver, context, operation);
            providerRuntimeExecuted = true;
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
            for (SelectedOperation operation : verifyOperations(selection.testCase(), selection.targetName(), selection.suiteRoot(), testStartedAt, operationPrefix)) {
                ProviderOperationResult result = executeOperation(resolver, context, operation);
                providerRuntimeExecuted = true;
                recordOperationOutput(outputs, operation, result);
                verifyResults.add(assertion(operation.id(), operation.request().operation(), result));
                evidenceRefs.addAll(refs(result));
                writeAssertion(selection.runDir(), operation.id(), operation.request().operation(), result);
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

    private List<RuntimeSelection> selectNatsRuntimes(Path suiteManifest, String requestedProfile, Path outputBase) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        Map<String, Map<String, Object>> contractsByType =
                readDirectoryByField(frameworkProviderContractsDirectory(suiteRoot), "provider_type");
        RunIds runIds = newRunIds();
        Path runDir = outputBase
                .resolve(safe(stringValue(suite.get("suite_id"))))
                .resolve(runIds.batchId())
                .resolve(runIds.runId());
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
                selections.add(new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        targetEntry.getKey(),
                        providerId,
                        providerType,
                        requestedProfile,
                        stringValue(providerBinding.get("runtime_mode")),
                        stringValue(bindingValues.get("subject")),
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

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-NATS-" + suffix, "RUN-NATS-" + suffix);
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
                                resolvedOperationInputs(testCase, suiteRoot, step),
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
            String operationPrefix) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : verifyValues(testCase)) {
            Map<String, Object> verify = mapValue(value);
            String type = stringValue(verify.get("type"));
            if (!targetName.equals(stringValue(verify.get("target")))
                    || !List.of("event_published", "event_payload_match").contains(type)) {
                continue;
            }
            String id = stringValue(verify.get("id"));
            String operationId = operationPrefix + (id.isBlank() ? type : id);
            operations.add(new SelectedOperation(
                    operationId,
                    new ProviderOperationRequest(
                            type,
                            resolvedOperationInputs(testCase, suiteRoot, verify),
                            runtimeOutputs(operationId, Map.of(), testStartTime))));
        }
        return List.copyOf(operations);
    }

    private Map<String, Object> runtimeOutputs(String operationId, Map<String, Object> outputs, Instant testStartTime) {
        Map<String, Object> resolved = new LinkedHashMap<>(outputs);
        resolved.put("_operation_id", operationId);
        resolved.put("_test_start_time", testStartTime.toString());
        return resolved;
    }

    private List<Map<String, Object>> resolvedParameters(
            Map<String, Object> testCase,
            Path suiteRoot,
            List<Object> parameters) {
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Object value : parameters) {
            Map<String, Object> parameter = new LinkedHashMap<>(mapValue(value));
            String bindAs = stringValue(parameter.get("bind_as"));
            String ref = resolveDataRef(testCase,
                    firstNonBlank(stringValue(parameter.get("ref")), stringValue(parameter.get("value"))));
            if ("subject".equals(bindAs)) {
                parameter.put("ref", resolveValue(suiteRoot, ref));
            } else {
                parameter.put("ref", ref);
            }
            resolved.add(parameter);
        }
        return List.copyOf(resolved);
    }

    private List<Map<String, Object>> resolvedOperationInputs(
            Map<String, Object> testCase,
            Path suiteRoot,
            Map<String, Object> operation) {
        if (!(operation.get("inputs") instanceof Map<?, ?> inputs)) {
            return resolvedParameters(testCase, suiteRoot, listValue(operation.get("parameters")));
        }
        List<Object> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : inputs.entrySet()) {
            Map<String, Object> parameter = new LinkedHashMap<>(mapValue(entry.getValue()));
            parameter.putIfAbsent("name", stringValue(entry.getKey()));
            parameter.put("bind_as", stringValue(entry.getKey()));
            parameters.add(parameter);
        }
        return resolvedParameters(testCase, suiteRoot, parameters);
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

    private String resolveDataRef(Map<String, Object> testCase, String ref) {
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
        String resolved = stringValue(mapValue(mapValue(mapValue(testCase.get("data_binding")).get(parts[0]))
                .get(parts[1])).get("ref"));
        return resolved.isBlank() ? ref : resolved;
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

    private Map<String, Object> assertion(String id, String type, ProviderOperationResult result) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("id", id);
        assertion.put("type", type);
        assertion.put("status", result.passed() ? "passed" : "failed");
        assertion.put("matched", result.outputs().getOrDefault("matched", false));
        assertion.put("evidence_ref", result.outputs().getOrDefault("event_evidence_ref", ""));
        return assertion;
    }

    private List<String> refs(ProviderOperationResult result) {
        return result.evidence().stream()
                .map(evidence -> evidence.ref())
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .toList();
    }

    private void writeAssertion(Path runDir, String id, String type, ProviderOperationResult result) {
        write(runDir.resolve("assertions/" + id + ".yaml"), """
                evidence_type: assertion_result
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                assertion_id: %s
                type: %s
                status: %s
                matched: %s
                observed_count: %s
                event_evidence_ref: %s
                """.formatted(
                id,
                type,
                result.passed() ? "passed" : "failed",
                result.outputs().getOrDefault("matched", false),
                result.outputs().getOrDefault("observed_count", 0),
                result.outputs().getOrDefault("event_evidence_ref", "")));
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
                subject: %s
                status: %s
                """.formatted(
                selection.providerType(),
                selection.providerId(),
                selection.profile(),
                selection.runtimeMode(),
                selection.subject(),
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
                subject: %s
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
                selection.subject(),
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
                ? ProviderFailure.of("NATS_OBSERVE_FAILED", "FRAMEWORK_ERROR", "NATS capability failed.", "Review provider evidence.")
                : failure;
        write(runDir.resolve("provider-evidence/nats/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_type: %s
                provider_id: %s
                profile: %s
                runtime_mode: %s
                subject: %s
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
                selection.subject(),
                resolvedFailure.code(),
                resolvedFailure.classification(),
                resolvedFailure.reason(),
                resolvedFailure.ownerAction()));
    }

    private Path writeResult(
            Path runDir,
            RuntimeSelection selection,
            String profile,
            String testCaseId,
            int testCount,
            String status,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<Map<String, Object>> testResults,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs,
            ProviderFailure failure,
            Instant startedAt,
            Instant finishedAt) {
        return ProviderCapabilityResultWriter.write(runDir, new ProviderCapabilityResultWriter.ResultDocument(
                selection.testCase().get("dsl_version"),
                selection.suite().get("suite_id"),
                selection.batchId(),
                selection.runId(),
                testCaseId,
                testCount,
                profile,
                profile,
                status,
                startedAt,
                finishedAt,
                selection.testCase().get("labels"),
                selection.testCase().get("source_refs"),
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
        result.put("subject", selection.subject());
        result.put("resolved_operation_result", Map.of(
                "operation", "nats_capability_flow",
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private String topLevelTestCaseId(RuntimeSelection selection, int testCount) {
        if (testCount == 1) {
            return stringValue(selection.testCase().get("test_case_id"));
        }
        return stringValue(selection.suite().get("suite_id")) + "-MULTI";
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
            throw new UncheckedIOException("Failed to read NATS capability artifact: " + path, e);
        }
    }

    private Object readObject(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read NATS capability artifact: " + path, e);
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
                throw new UncheckedIOException("Failed to clean generated NATS output: " + directory, e);
            }
        }
        createDirectories(directory);
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated NATS output: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write NATS capability output: " + path, e);
        }
    }

    public record NatsRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            String providerId,
            String providerType,
            String runtimeMode,
            String subject,
            Path resultJson,
            Path evidenceDir,
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static NatsRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new NatsRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
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
            String subject,
            Map<String, Object> providerContract,
            Map<String, Object> providerInstance,
            Map<String, Object> bindingValues,
            String batchId,
            String runId,
            Path runDir) {
    }

    private record SelectedOperation(String id, ProviderOperationRequest request) {
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
