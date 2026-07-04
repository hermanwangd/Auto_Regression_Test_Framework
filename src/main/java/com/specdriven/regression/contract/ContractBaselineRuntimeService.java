package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.provider.jdbc.JdbcProviderRuntime;
import com.specdriven.regression.provider.nats.NatsProviderRuntime;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import com.specdriven.regression.provider.runtime.ProviderRuntimeRegistry;
import com.specdriven.regression.provider.runtime.ProviderRuntimeResolver;
import com.specdriven.regression.provider.runtime.ProviderRuntimeResolver.ProviderRuntimeResolution;
import com.specdriven.regression.provider.wiremock.WireMockHttpMockProviderRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class ContractBaselineRuntimeService {

    private static final String WIREMOCK = "wiremock_http_mock";
    private static final String JDBC = "jdbc";
    private static final String NATS = "nats";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public ContractBaselineRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return ContractBaselineRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!isSupportedProviderSet(validation.providerTypesUsed())) {
            return ContractBaselineRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_types_used",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "Contract baseline mixed runtime supports provider_types `wiremock_http_mock`, `jdbc`, and `nats` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return ContractBaselineRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    WIREMOCK + "," + JDBC + "," + NATS,
                    "",
                    "",
                    "Provide --profile for contract baseline mixed provider run.")));
        }

        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<SuiteProfileGate.TestCaseDocument> testDocuments = testDocuments(suiteRoot, suite);
        List<ContractFinding> profileFindings =
                SuiteProfileGate.validate(suiteManifest, suite, testDocuments, requestedProfile,
                        WIREMOCK + "," + JDBC + "," + NATS);
        if (!profileFindings.isEmpty()) {
            return ContractBaselineRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, profileFindings);
        }

        RuntimeModel model = runtimeModel(suiteRoot, suite, testDocuments, requestedProfile, outputBase);
        if (model.selections().isEmpty()) {
            return ContractBaselineRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_provider_targets",
                    "",
                    WIREMOCK + "," + JDBC + "," + NATS,
                    requestedProfile,
                    "",
                    "Declare DSL targets for wiremock_http_mock, jdbc, and nats providers.")));
        }

        recreateDirectory(model.suiteRunDir());
        WireMockHttpMockProviderRuntime wireMockRuntime = new WireMockHttpMockProviderRuntime();
        JdbcProviderRuntime jdbcRuntime = new JdbcProviderRuntime();
        NatsProviderRuntime natsRuntime = new NatsProviderRuntime();
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(new ProviderRuntimeRegistry(Map.of(
                WIREMOCK, wireMockRuntime,
                JDBC, jdbcRuntime,
                NATS, natsRuntime)));
        Map<String, ProviderRuntime> runtimes = Map.of(
                WIREMOCK, wireMockRuntime,
                JDBC, jdbcRuntime,
                NATS, natsRuntime);

        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        boolean providerRuntimeExecuted = false;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> testResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();

        for (RuntimeSelection selection : model.selections()) {
            TestExecution execution = executeTestCase(resolver, runtimes, selection, requestedProfile);
            providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeExecuted();
            stepResults.addAll(execution.stepResults());
            verifyResults.addAll(execution.verifyResults());
            testResults.add(execution.testResult());
            providerResults.addAll(execution.providerResults());
            evidenceRefs.addAll(prefixEvidenceRefs(selection, execution.evidenceRefs()));
            if (!execution.passed() && "passed".equals(status)) {
                status = "failed";
                failure = execution.failure();
            }
        }

        Instant finishedAt = Instant.now();
        RuntimeSelection first = model.selections().get(0);
        String testCaseId = ProviderCapabilityResultWriter.topLevelTestCaseId(
                first.suite().get("suite_id"), first.testCase().get("test_case_id"), model.selections().size());
        writeExecutionLog(model.suiteRunDir(), first, status);
        evidenceRefs.add("logs/execution.log");
        writeBatch(model.suiteRunDir(), first, status, model.selections().size(), testCaseId);
        evidenceRefs.add("batch/batch.yaml");
        if ("failed".equals(status)) {
            writeFailureDetail(model.suiteRunDir(), first, failure);
            evidenceRefs.add("provider-evidence/contract-baseline/failure_detail.yaml");
        }
        writeEvidenceIndex(model.suiteRunDir(), first, testCaseId, evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = ProviderCapabilityResultWriter.write(
                model.suiteRunDir(),
                new ProviderCapabilityResultWriter.ResultDocument(
                        first.testCase().get("dsl_version"),
                        first.suite().get("suite_id"),
                        first.batchId(),
                        first.runId(),
                        testCaseId,
                        model.selections().size(),
                        requestedProfile,
                        requestedProfile,
                        status,
                        startedAt,
                        finishedAt,
                        first.testCase().get("labels"),
                        first.testCase().get("source_refs"),
                        stepResults,
                        verifyResults,
                        testResults,
                        providerResults,
                        distinct(evidenceRefs),
                        distinct(evidenceRefs),
                        Map.of(),
                        failure,
                        null,
                        true));
        return new ContractBaselineRunResult(
                "passed".equals(status),
                status,
                stringValue(first.suite().get("suite_id")),
                first.batchId(),
                first.runId(),
                testCaseId,
                requestedProfile,
                providerResults.stream().map(result -> stringValue(result.get("provider_id"))).distinct().toList(),
                providerResults.stream().map(result -> stringValue(result.get("provider_type"))).distinct().toList(),
                resultJson,
                model.suiteRunDir(),
                model.selections().size(),
                providerRuntimeExecuted,
                List.of());
    }

    private TestExecution executeTestCase(
            ProviderRuntimeResolver resolver,
            Map<String, ProviderRuntime> runtimes,
            RuntimeSelection selection,
            String requestedProfile) {
        recreateDirectory(selection.runDir());
        Instant testStart = Instant.now();
        Map<String, Map<String, Object>> capturedOutputs = new LinkedHashMap<>();
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Map<String, String> providerStatuses = new LinkedHashMap<>();
        ProviderFailure failure = null;
        boolean providerRuntimeExecuted = false;

        try {
            for (SelectedOperation operation : lifecycleOperations(selection, "setup")) {
                OperationOutcome outcome = executeProviderOperation(resolver, runtimes, selection, operation, testStart);
                providerRuntimeExecuted = providerRuntimeExecuted || outcome.providerRuntimeExecuted();
                capture(outcome, capturedOutputs, outputs, stepResults, evidenceRefs, providerStatuses);
                if (!outcome.result().passed() && failure == null) {
                    failure = outcome.result().failure();
                    break;
                }
            }
            if (failure == null) {
                for (SelectedOperation operation : lifecycleOperations(selection, "execute")) {
                    OperationOutcome outcome = executeProviderOperation(resolver, runtimes, selection, operation, testStart);
                    providerRuntimeExecuted = providerRuntimeExecuted || outcome.providerRuntimeExecuted();
                    capture(outcome, capturedOutputs, outputs, stepResults, evidenceRefs, providerStatuses);
                    if (!outcome.result().passed() && failure == null) {
                        failure = outcome.result().failure();
                        break;
                    }
                }
            }
            if (failure == null) {
                for (SelectedOperation operation : verifyOperations(selection, capturedOutputs)) {
                    if (operation.providerType().isBlank()) {
                        VerificationOutcome assertion = verifyValueEquals(selection, operation, capturedOutputs);
                        verifyResults.add(assertion.verifyResult());
                        evidenceRefs.add(assertion.evidenceRef());
                        if (!assertion.passed() && failure == null) {
                            failure = ProviderFailure.of(
                                    "ASSERTION_FAILED",
                                    "ASSERTION_FAILED",
                                    "Value assertion `" + operation.id() + "` failed.",
                                    "Review assertion evidence and operation outputs.");
                        }
                        continue;
                    }
                    OperationOutcome outcome = executeProviderOperation(resolver, runtimes, selection, operation, testStart);
                    providerRuntimeExecuted = providerRuntimeExecuted || outcome.providerRuntimeExecuted();
                    capture(outcome, capturedOutputs, outputs, stepResults, evidenceRefs, providerStatuses);
                    verifyResults.add(verifyResult(operation.id(), operation.operation(), outcome.result().passed()));
                    if (!outcome.result().passed() && failure == null) {
                        failure = outcome.result().failure();
                    }
                }
            }
        } finally {
            for (SelectedOperation operation : lifecycleOperations(selection, "cleanup")) {
                OperationOutcome outcome = executeProviderOperation(resolver, runtimes, selection, operation, testStart);
                providerRuntimeExecuted = providerRuntimeExecuted || outcome.providerRuntimeExecuted();
                capture(outcome, capturedOutputs, outputs, stepResults, evidenceRefs, providerStatuses);
                if (!outcome.result().passed() && failure == null) {
                    failure = outcome.result().failure();
                }
            }
        }

        String status = failure == null ? "passed" : "failed";
        writeTestExecutionLog(selection.runDir(), selection, status);
        evidenceRefs.add("logs/execution.log");
        writeTestBatch(selection.runDir(), selection, status);
        evidenceRefs.add("batch/batch.yaml");
        for (String providerId : selection.providerIdsByType().values()) {
            String providerType = selection.providerType(providerId);
            providerResults.add(providerResult(
                    providerId,
                    providerType,
                    selection.runtimeMode(providerId),
                    providerStatuses.getOrDefault(providerId, status),
                    outputs));
        }
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_ids", List.copyOf(selection.providerIdsByType().values()));
        testResult.put("provider_types", List.of(WIREMOCK, JDBC, NATS));
        testResult.put("status", status);
        testResult.put("step_count", stepResults.size());
        testResult.put("verify_count", verifyResults.size());
        if (failure != null) {
            testResult.put("failure_code", failure.code());
            testResult.put("failure_classification", failure.classification());
        }
        return new TestExecution(
                "passed".equals(status),
                providerRuntimeExecuted,
                stepResults,
                verifyResults,
                testResult,
                providerResults,
                evidenceRefs,
                failure);
    }

    private OperationOutcome executeProviderOperation(
            ProviderRuntimeResolver resolver,
            Map<String, ProviderRuntime> runtimes,
            RuntimeSelection selection,
            SelectedOperation operation,
            Instant testStart) {
        ProviderExecutionContext context = context(selection, operation.providerId());
        Map<String, Object> outputs = new LinkedHashMap<>(operation.request().outputs());
        outputs.put("_operation_id", operation.id());
        outputs.put("_test_start_time", testStart.toString());
        ProviderOperationRequest request = new ProviderOperationRequest(
                operation.request().operation(),
                operation.request().parameters(),
                outputs);
        ProviderRuntimeResolution resolution = resolver.resolve(context, request);
        if (!resolution.valid()) {
            return new OperationOutcome(operation, ProviderOperationResult.failed(Map.of(), List.of(), resolution.failure()), false);
        }
        ProviderOperationResult result = runtimes.get(operation.providerType()).execute(context, request);
        return new OperationOutcome(operation, result, true);
    }

    private void capture(
            OperationOutcome outcome,
            Map<String, Map<String, Object>> capturedOutputs,
            Map<String, Object> aggregateOutputs,
            List<Map<String, Object>> stepResults,
            List<String> evidenceRefs,
            Map<String, String> providerStatuses) {
        ProviderOperationResult result = outcome.result();
        capturedOutputs.put(outcome.operation().id(), result.outputs());
        aggregateOutputs.putAll(result.outputs());
        stepResults.add(step(outcome.operation().id(), result.passed() ? "passed" : "failed", result.outputs()));
        evidenceRefs.addAll(result.evidence().stream()
                .map(evidence -> evidence.ref())
                .filter(ref -> ref != null && !ref.isBlank())
                .toList());
        String providerId = outcome.operation().providerId();
        if (!providerId.isBlank()) {
            providerStatuses.put(providerId, mergeStatus(providerStatuses.get(providerId), result.passed() ? "passed" : "failed"));
        }
    }

    private VerificationOutcome verifyValueEquals(
            RuntimeSelection selection,
            SelectedOperation operation,
            Map<String, Map<String, Object>> capturedOutputs) {
        Object actual = resolveOutputRef(capturedOutputs, stringValue(operation.raw().get("actual_ref")));
        if (isMissing(actual)) {
            actual = resolveOutputRef(capturedOutputs, stringValue(mapValue(operation.raw().get("actual")).get("ref")));
        }
        Object expected = operation.raw().get("expected");
        boolean passed = String.valueOf(expected).equals(String.valueOf(actual));
        String ref = "assertions/" + safe(operation.id()) + ".yaml";
        write(selection.runDir().resolve(ref), """
                evidence_type: assertion_diff
                assertion_id: %s
                type: %s
                status: %s
                comparison_status: %s
                expected: %s
                actual: %s
                masking_applied: true
                %s
                """.formatted(
                operation.id(),
                operation.operation(),
                passed ? "passed" : "failed",
                passed ? "passed" : "failed",
                expected,
                actual,
                passed ? "" : "failure_code: ASSERTION_FAILED"));
        return new VerificationOutcome(passed, verifyResult(operation.id(), operation.operation(), passed), ref);
    }

    private RuntimeModel runtimeModel(
            Path suiteRoot,
            Map<String, Object> suite,
            List<SuiteProfileGate.TestCaseDocument> testDocuments,
            String requestedProfile,
            Path outputBase) {
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        Map<String, Map<String, Object>> contractsByType =
                readDirectoryByField(frameworkProviderContractsDirectory(suiteRoot), "provider_type");
        RunIds runIds = newRunIds();
        Path suiteRunDir = outputBase
                .resolve(safe(stringValue(suite.get("suite_id"))))
                .resolve(runIds.batchId())
                .resolve(runIds.runId());
        boolean multiTestSuite = testDocuments.size() > 1;
        List<RuntimeSelection> selections = new ArrayList<>();
        for (SuiteProfileGate.TestCaseDocument document : testDocuments) {
            Map<String, String> providerIdsByType = new LinkedHashMap<>();
            Map<String, String> providerTypesById = new LinkedHashMap<>();
            for (Map.Entry<String, Object> targetEntry : mapValue(document.document().get("targets")).entrySet()) {
                String providerId = stringValue(mapValue(targetEntry.getValue()).get("provider_id"));
                Map<String, Object> instance = instancesById.getOrDefault(providerId, Map.of());
                String providerType = stringValue(instance.get("provider_type"));
                if (List.of(WIREMOCK, JDBC, NATS).contains(providerType)) {
                    providerIdsByType.put(providerType, providerId);
                    providerTypesById.put(providerId, providerType);
                }
            }
            if (!providerIdsByType.keySet().containsAll(List.of(WIREMOCK, JDBC, NATS))) {
                continue;
            }
            Map<String, Map<String, Object>> bindingsByProviderId = new LinkedHashMap<>();
            for (String providerId : providerTypesById.keySet()) {
                bindingsByProviderId.put(providerId,
                        runtimeBindingResolver.providerBinding(suiteRoot, requestedProfile, providerId));
            }
            Path runDir = multiTestSuite
                    ? suiteRunDir.resolve("tests").resolve(safe(stringValue(document.document().get("test_case_id"))))
                    : suiteRunDir;
            selections.add(new RuntimeSelection(
                    suiteRoot,
                    suite,
                    document.document(),
                    requestedProfile,
                    providerIdsByType,
                    providerTypesById,
                    instancesById,
                    contractsByType,
                    bindingsByProviderId,
                    runIds.batchId(),
                    runIds.runId(),
                    suiteRunDir,
                    runDir));
        }
        return new RuntimeModel(suiteRunDir, List.copyOf(selections));
    }

    private List<SelectedOperation> lifecycleOperations(RuntimeSelection selection, String section) {
        return listValue(mapValue(selection.testCase().get(section)).get("operations")).stream()
                .map(value -> selectedOperation(selection, mapValue(value), section))
                .toList();
    }

    private List<SelectedOperation> verifyOperations(
            RuntimeSelection selection,
            Map<String, Map<String, Object>> capturedOutputs) {
        List<SelectedOperation> operations = new ArrayList<>();
        for (Object value : listValue(mapValue(selection.testCase().get("verify")).get("checks"))) {
            Map<String, Object> verify = mapValue(value);
            String id = firstNonBlank(stringValue(verify.get("id")), stringValue(verify.get("type")));
            String type = stringValue(verify.get("type"));
            if ("value_equals".equals(type)) {
                Map<String, Object> raw = new LinkedHashMap<>(verify);
                raw.put("actual_ref", stringValue(mapValue(verify.get("actual")).get("ref")));
                operations.add(new SelectedOperation(id, "", "", type,
                        new ProviderOperationRequest(type, List.of(), Map.of()), raw));
            } else {
                operations.add(selectedOperation(selection, verify, "verify"));
            }
        }
        return List.copyOf(operations);
    }

    private SelectedOperation selectedOperation(RuntimeSelection selection, Map<String, Object> operation, String section) {
        String target = stringValue(operation.get("target"));
        String providerId = providerIdForTarget(selection.testCase(), target);
        String providerType = selection.providerType(providerId);
        String operationName = "verify".equals(section) ? stringValue(operation.get("type")) : stringValue(operation.get("operation"));
        String id = firstNonBlank(stringValue(operation.get("id")), operationName);
        return new SelectedOperation(id, providerId, providerType, operationName,
                new ProviderOperationRequest(
                        operationName,
                        resolvedOperationInputs(selection.testCase(), operation),
                        mapValue(operation.get("outputs"))),
                operation);
    }

    private ProviderExecutionContext context(RuntimeSelection selection, String providerId) {
        String providerType = selection.providerType(providerId);
        Map<String, Object> binding = selection.bindingsByProviderId().getOrDefault(providerId, Map.of());
        return new ProviderExecutionContext(
                providerId,
                providerType,
                selection.profile(),
                stringValue(binding.get("runtime_mode")),
                selection.suiteRoot(),
                selection.runDir(),
                selection.contractsByType().getOrDefault(providerType, Map.of()),
                selection.instancesById().getOrDefault(providerId, Map.of()),
                mapValue(binding.get("binding_values")));
    }

    private List<Map<String, Object>> resolvedOperationInputs(Map<String, Object> testCase, Map<String, Object> operation) {
        Map<String, Object> inputs = mapValue(operation.get("inputs"));
        if (inputs.isEmpty()) {
            return mapList(operation.get("parameters")).stream()
                    .map(input -> resolvedInput(testCase, input, stringValue(input.get("bind_as"))))
                    .toList();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            normalized.add(resolvedInput(testCase, mapValue(entry.getValue()), stringValue(entry.getKey())));
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> resolvedInput(Map<String, Object> testCase, Map<String, Object> source, String bindAs) {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("bind_as", bindAs);
        if (source.containsKey("value")) {
            parameter.put("ref", source.get("value"));
        } else {
            parameter.put("ref", resolveDataRef(testCase, stringValue(source.get("ref"))));
        }
        return parameter;
    }

    private String resolveDataRef(Map<String, Object> testCase, String ref) {
        if (!ref.startsWith("${data.") || !ref.endsWith("}")) {
            return ref;
        }
        String key = ref.substring("${data.".length(), ref.length() - 1);
        return stringValue(mapValue(mapValue(testCase.get("data")).get(key)).get("ref"));
    }

    private Object resolveOutputRef(Map<String, Map<String, Object>> outputs, String ref) {
        String prefix = "${execute.";
        if (!ref.startsWith(prefix) || !ref.endsWith("}")) {
            return ref;
        }
        String path = ref.substring(prefix.length(), ref.length() - 1);
        String marker = ".outputs.";
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 1) {
            return "";
        }
        String operationId = path.substring(0, markerIndex);
        String output = path.substring(markerIndex + marker.length());
        return outputs.getOrDefault(operationId, Map.of()).getOrDefault(output, "");
    }

    private String providerIdForTarget(Map<String, Object> testCase, String target) {
        return stringValue(mapValue(mapValue(testCase.get("targets")).get(target)).get("provider_id"));
    }

    private boolean isSupportedProviderSet(List<String> providerTypes) {
        Set<String> types = new LinkedHashSet<>(providerTypes);
        return types.size() == 3 && types.contains(WIREMOCK) && types.contains(JDBC) && types.contains(NATS);
    }

    private Map<String, Object> providerResult(
            String providerId,
            String providerType,
            String runtimeMode,
            String status,
            Map<String, Object> outputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", providerId);
        result.put("provider_type", providerType);
        result.put("runtime_mode", runtimeMode);
        result.put("status", status);
        result.put("resolved_operation_result", Map.of(
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private Map<String, Object> step(String id, String status, Map<String, Object> outputs) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("status", status);
        if (!outputs.isEmpty()) {
            step.put("outputs", outputs);
        }
        return step;
    }

    private Map<String, Object> verifyResult(String id, String type, boolean passed) {
        Map<String, Object> verify = new LinkedHashMap<>();
        verify.put("id", id);
        verify.put("type", type);
        verify.put("status", passed ? "passed" : "failed");
        return verify;
    }

    private String mergeStatus(String current, String incoming) {
        if ("failed".equals(current) || "failed".equals(incoming)) {
            return "failed";
        }
        if ("passed".equals(current) || "passed".equals(incoming)) {
            return "passed";
        }
        return firstNonBlank(current, incoming);
    }

    private void writeExecutionLog(Path runDir, RuntimeSelection selection, String status) {
        write(runDir.resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_types: [wiremock_http_mock, jdbc, nats]
                provider_ids: %s
                status: %s
                """.formatted(selection.providerIdsByType().values(), status));
    }

    private void writeTestExecutionLog(Path runDir, RuntimeSelection selection, String status) {
        write(runDir.resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                test_case_id: %s
                provider_types: [wiremock_http_mock, jdbc, nats]
                status: %s
                """.formatted(selection.testCase().get("test_case_id"), status));
    }

    private void writeBatch(Path runDir, RuntimeSelection selection, String status, int testCount, String testCaseId) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                test_count: %s
                provider_types: [wiremock_http_mock, jdbc, nats]
                status: %s
                """.formatted(
                selection.suite().get("suite_id"),
                selection.batchId(),
                selection.runId(),
                testCaseId,
                testCount,
                status));
    }

    private void writeTestBatch(Path runDir, RuntimeSelection selection, String status) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                test_count: 1
                provider_types: [wiremock_http_mock, jdbc, nats]
                status: %s
                """.formatted(
                selection.suite().get("suite_id"),
                selection.batchId(),
                selection.runId(),
                selection.testCase().get("test_case_id"),
                status));
    }

    private void writeFailureDetail(Path runDir, RuntimeSelection selection, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("CONTRACT_BASELINE_FAILED", "PROVIDER_RUNTIME_ERROR",
                        "Contract baseline mixed provider run failed.", "Review provider and assertion evidence.")
                : failure;
        write(runDir.resolve("provider-evidence/contract-baseline/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_types: [wiremock_http_mock, jdbc, nats]
                provider_ids: %s
                profile: %s
                failure_code: %s
                classification: %s
                reason: %s
                owner_action: %s
                masking:
                  raw_secret_found: false
                """.formatted(
                selection.providerIdsByType().values(),
                selection.profile(),
                resolvedFailure.code(),
                resolvedFailure.classification(),
                resolvedFailure.reason(),
                resolvedFailure.ownerAction()));
    }

    private void writeEvidenceIndex(Path runDir, RuntimeSelection selection, String testCaseId, List<String> evidenceRefs) {
        StringBuilder index = new StringBuilder();
        index.append("evidence_index_version: v0.2\n");
        index.append("suite_id: ").append(selection.suite().get("suite_id")).append('\n');
        index.append("batch_id: ").append(selection.batchId()).append('\n');
        index.append("run_id: ").append(selection.runId()).append('\n');
        index.append("test_case_id: ").append(testCaseId).append('\n');
        index.append("profile: ").append(selection.profile()).append('\n');
        index.append("entries:\n");
        for (String ref : distinct(evidenceRefs)) {
            if (ref == null || ref.isBlank() || ref.endsWith("evidence_index.yaml")) {
                continue;
            }
            EvidenceEntry entry = evidenceEntry(selection, ref);
            index.append("  - evidence_id: ").append(entry.evidenceId()).append('\n');
            index.append("    evidence_type: ").append(entry.evidenceType()).append('\n');
            index.append("    produced_by: ").append(entry.producedBy()).append('\n');
            if (!entry.providerType().isBlank()) {
                index.append("    provider_type: ").append(entry.providerType()).append('\n');
                index.append("    provider_id: ").append(entry.providerId()).append('\n');
            }
            index.append("    test_case_id: ").append(testCaseId).append('\n');
            index.append("    run_id: ").append(selection.runId()).append('\n');
            index.append("    batch_id: ").append(selection.batchId()).append('\n');
            index.append("    file_path: ").append(ref).append('\n');
            index.append("    content_type: ").append(contentType(ref)).append('\n');
            index.append("    status: passed\n");
            index.append("    created_at: ").append(Instant.now()).append('\n');
            index.append("    masking_applied: true\n");
            index.append("    linked_result_field: ").append(entry.linkedResultField()).append('\n');
        }
        index.append("masking:\n  raw_secret_found: false\n");
        write(runDir.resolve("evidence_index.yaml"), index.toString());
    }

    private EvidenceEntry evidenceEntry(RuntimeSelection selection, String ref) {
        String normalized = ref.toLowerCase();
        String evidenceType;
        String providerType = "";
        if (normalized.contains("logs/execution")) {
            evidenceType = "execution_log";
        } else if (normalized.contains("batch/")) {
            evidenceType = "batch_summary";
        } else if (normalized.contains("assertions/")) {
            evidenceType = "assertion_diff";
        } else if (normalized.contains("provider-evidence/jdbc/seed")) {
            evidenceType = "jdbc_seed";
            providerType = JDBC;
        } else if (normalized.contains("provider-evidence/jdbc/cleanup")) {
            evidenceType = "jdbc_cleanup";
            providerType = JDBC;
        } else if (normalized.contains("provider-evidence/jdbc/query")) {
            evidenceType = "jdbc_query";
            providerType = JDBC;
        } else if (normalized.contains("provider-evidence/nats/")) {
            evidenceType = "nats_event";
            providerType = NATS;
        } else if (normalized.contains("request_journal")) {
            evidenceType = "wiremock_request_journal";
            providerType = WIREMOCK;
        } else if (normalized.contains("cleanup")) {
            evidenceType = "fixture_cleanup";
            providerType = WIREMOCK;
        } else if (normalized.contains("injected_stubs")) {
            evidenceType = "fixture_setup";
            providerType = WIREMOCK;
        } else {
            evidenceType = "wiremock_server_log";
            providerType = WIREMOCK;
        }
        String providerId = providerType.isBlank() ? "" : selection.providerIdsByType().get(providerType);
        return new EvidenceEntry(
                evidenceType + "-" + safe(ref),
                evidenceType,
                providerType.isBlank() && !"assertion_diff".equals(evidenceType) ? "framework"
                        : ("assertion_diff".equals(evidenceType) ? "assertion_engine" : "provider"),
                providerType,
                providerId,
                linkedResultField(evidenceType));
    }

    private String linkedResultField(String evidenceType) {
        return switch (evidenceType) {
            case "execution_log", "batch_summary" -> "evidence_refs";
            case "assertion_diff" -> "verify_results";
            case "fixture_cleanup", "jdbc_cleanup" -> "provider_results.cleanup_status";
            default -> "provider_results.resolved_operation_result";
        };
    }

    private List<String> prefixEvidenceRefs(RuntimeSelection selection, List<String> refs) {
        if (selection.runDir().equals(selection.suiteRunDir())) {
            return refs.stream().filter(ref -> ref != null && !ref.isBlank()).toList();
        }
        String prefix = "tests/" + safe(stringValue(selection.testCase().get("test_case_id"))) + "/";
        return refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(ref -> prefix + ref)
                .toList();
    }

    private List<SuiteProfileGate.TestCaseDocument> testDocuments(Path suiteRoot, Map<String, Object> suite) {
        return listValue(suite.get("tests")).stream()
                .map(ref -> suiteRoot.resolve(stringValue(ref)).normalize())
                .map(path -> new SuiteProfileGate.TestCaseDocument(path, readMap(path)))
                .toList();
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
            throw new UncheckedIOException("Failed to read contract baseline runtime artifact: " + path, e);
        }
    }

    private void recreateDirectory(Path directory) {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clean generated contract baseline output: " + directory, e);
            }
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated contract baseline output: " + directory, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write contract baseline evidence: " + path, e);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    private List<String> distinct(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && seen.add(value)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private String contentType(String ref) {
        if (ref.endsWith(".json")) {
            return "application/json";
        }
        if (ref.endsWith(".yaml") || ref.endsWith(".yml")) {
            return "application/x-yaml";
        }
        return "text/plain";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isMissing(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record ContractBaselineRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            String profile,
            List<String> providerIds,
            List<String> providerTypes,
            Path resultJson,
            Path evidenceDir,
            int testCount,
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static ContractBaselineRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new ContractBaselineRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    profile,
                    List.of(),
                    List.of(WIREMOCK, JDBC, NATS),
                    null,
                    null,
                    0,
                    false,
                    List.copyOf(findings));
        }
    }

    private record RuntimeModel(Path suiteRunDir, List<RuntimeSelection> selections) {
    }

    private record RuntimeSelection(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            Map<String, String> providerIdsByType,
            Map<String, String> providerTypesById,
            Map<String, Map<String, Object>> instancesById,
            Map<String, Map<String, Object>> contractsByType,
            Map<String, Map<String, Object>> bindingsByProviderId,
            String batchId,
            String runId,
            Path suiteRunDir,
            Path runDir) {

        String providerType(String providerId) {
            return providerTypesById.getOrDefault(providerId, "");
        }

        String runtimeMode(String providerId) {
            return stringValueStatic(bindingsByProviderId.getOrDefault(providerId, Map.of()).get("runtime_mode"));
        }
    }

    private record SelectedOperation(
            String id,
            String providerId,
            String providerType,
            String operation,
            ProviderOperationRequest request,
            Map<String, Object> raw) {
    }

    private record OperationOutcome(
            SelectedOperation operation,
            ProviderOperationResult result,
            boolean providerRuntimeExecuted) {
    }

    private record VerificationOutcome(
            boolean passed,
            Map<String, Object> verifyResult,
            String evidenceRef) {
    }

    private record TestExecution(
            boolean passed,
            boolean providerRuntimeExecuted,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            Map<String, Object> testResult,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs,
            ProviderFailure failure) {
    }

    private record RunIds(String batchId, String runId) {
    }

    private record EvidenceEntry(
            String evidenceId,
            String evidenceType,
            String producedBy,
            String providerType,
            String providerId,
            String linkedResultField) {
    }

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-CONTRACT-" + suffix, "RUN-CONTRACT-" + suffix);
    }

    private static String stringValueStatic(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
