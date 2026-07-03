package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class WireMockProviderCapabilityService {

    private static final String PROVIDER_TYPE = "wiremock_http_mock";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final String EVIDENCE_CLASSIFICATION = "framework_provider_capability_only";
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public WireMockRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return WireMockRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!validation.providerTypesUsed().equals(List.of(PROVIDER_TYPE))) {
            return WireMockRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "WireMock provider capability mode supports provider_type `wiremock_http_mock` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return WireMockRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    PROVIDER_TYPE,
                    "",
                    "",
                    "Provide --profile for WireMock provider capability run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile);
        if (!profileFindings.isEmpty()) {
            return WireMockRunResult.blocked(validation.suiteId(), requestedProfile, profileFindings);
        }

        List<RuntimeSelection> selections = selectWireMockRuntimes(suiteManifest, requestedProfile, outputBase);
        if (selections.isEmpty()) {
            return WireMockRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_wiremock_target",
                    "",
                    PROVIDER_TYPE,
                    requestedProfile,
                    "",
                    "Add a DSL target using provider_type `wiremock_http_mock` for profile `" + requestedProfile + "`.")));
        }
        RuntimeSelection firstSelection = selections.get(0);
        recreateDirectory(firstSelection.suiteRunDir());

        WireMockHttpMockProviderRuntime wireMockRuntime = new WireMockHttpMockProviderRuntime();
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(Map.of(PROVIDER_TYPE, wireMockRuntime));
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(registry);

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
            TestExecution execution = executeTestCase(resolver, wireMockRuntime, selection, requestedProfile);
            providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeExecuted();
            if (selections.size() == 1) {
                aggregateOutputs.putAll(execution.outputs());
            } else {
                aggregateOutputs.put(safe(stringValue(selection.testCase().get("test_case_id"))), execution.outputs());
            }
            stepResults.addAll(execution.stepResults());
            verifyResults.addAll(execution.verifyResults());
            testResults.add(execution.testResult());
            providerResults.add(providerResult(selection, requestedProfile, execution.status(), execution.outputs(), execution.operationName()));
            evidenceRefs.addAll(prefixEvidenceRefs(selection, execution.evidenceRefs()));
            if (!execution.passed() && "passed".equals(status)) {
                status = "failed";
                failure = execution.failure();
                failureSelection = selection;
            }
        }

        Instant finishedAt = Instant.now();
        writeExecutionLog(firstSelection.suiteRunDir(), firstSelection.providerId(), firstSelection.providerType(), status, aggregateOutputs);
        evidenceRefs.add("logs/execution.log");
        writeBatch(firstSelection.suiteRunDir(), firstSelection, status, selections.size());
        evidenceRefs.add("batch/batch.yaml");
        if ("failed".equals(status)) {
            evidenceRefs.add("provider-evidence/wiremock/failure_detail.yaml");
            RuntimeSelection failedSelection = failureSelection == null ? firstSelection : failureSelection;
            writeFailureDetailIfMissing(firstSelection.suiteRunDir(), failedSelection.providerId(), failedSelection.providerType(), failure);
        }
        writeEvidenceIndex(firstSelection.suiteRunDir(), firstSelection, selections.size(), evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = writeResult(
                firstSelection.suiteRunDir(),
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
        return new WireMockRunResult(
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
                stringValue(aggregateOutputs.get("base_url")),
                resultJson,
                firstSelection.suiteRunDir(),
                providerRuntimeExecuted,
                List.of());
    }

    private TestExecution executeTestCase(
            ProviderRuntimeResolver resolver,
            WireMockHttpMockProviderRuntime wireMockRuntime,
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

        createDirectories(selection.runDir());
        String status = "passed";
        ProviderFailure failure = null;
        boolean providerRuntimeExecuted = false;
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        SelectedOperation execute = null;
        try {
            SelectedOperation setup = setupOperation(selection.testCase(), selection.targetName());
            ProviderRuntimeResolution setupResolution = resolver.resolve(context, setup.request());
            if (!setupResolution.valid()) {
                return blockedExecution(selection, setupResolution.failure());
            }
            ProviderOperationResult setupResult = setupResolution.runtime().execute(context, setup.request());
            providerRuntimeExecuted = true;
            outputs.putAll(setupResult.outputs());
            stepResults.add(step(setup.id(), setup.request().operation(), setupResult.passed() ? "passed" : "failed", setupResult.outputs()));
            evidenceRefs.addAll(refs(setupResult));
            if (!setupResult.passed()) {
                status = "failed";
                failure = setupResult.failure();
            } else {
                execute = executeOperation(selection.testCase(), selection.targetName());
                ProviderRuntimeResolution executeResolution = resolver.resolve(context, execute.request());
                if (!executeResolution.valid()) {
                    return blockedExecution(selection, executeResolution.failure());
                }
                ProviderOperationResult executeResult = executeResolution.runtime().execute(context, execute.request());
                outputs.putAll(executeResult.outputs());
                stepResults.add(step(
                        execute.id(),
                        execute.request().operation(),
                        executeResult.passed() ? "passed" : "failed",
                        executeResult.outputs()));
                evidenceRefs.addAll(refs(executeResult));
                if (!executeResult.passed()) {
                    status = "failed";
                    failure = executeResult.failure();
                } else {
                    VerificationOutcome verification = verify(
                            selection.testCase(),
                            outputs,
                            selection.runDir(),
                            selection.suiteRoot(),
                            selection.providerId(),
                            selection.providerType());
                    verifyResults.addAll(verification.verifyResults());
                    evidenceRefs.addAll(verification.evidenceRefs());
                    if (!verification.passed()) {
                        status = "failed";
                        failure = ProviderFailure.of(
                                "ASSERTION_FAILED",
                                "ASSERTION_FAILED",
                                "One or more WireMock verification checks failed.",
                                "Review request journal, assertion evidence, and failure detail.");
                    }
                }
            }
        } finally {
            SelectedOperation cleanupSelection = cleanupOperation(selection.testCase(), selection.targetName());
            ProviderOperationResult cleanupResult = wireMockRuntime.execute(
                    context,
                    cleanupSelection.request());
            stepResults.add(step(
                    cleanupSelection.id(),
                    cleanupSelection.request().operation(),
                    cleanupResult.passed() ? "passed" : "failed",
                    cleanupResult.outputs()));
            evidenceRefs.addAll(refs(cleanupResult));
        }
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_id", selection.providerId());
        testResult.put("provider_type", selection.providerType());
        testResult.put("status", status);
        testResult.put("step_count", stepResults.size());
        testResult.put("verify_count", verifyResults.size());
        if (failure != null) {
            testResult.put("failure_code", failure.code());
            testResult.put("failure_classification", failure.classification());
        }
        return new TestExecution(
                "passed".equals(status),
                status,
                providerRuntimeExecuted,
                outputs,
                stepResults,
                verifyResults,
                testResult,
                evidenceRefs,
                failure,
                execute == null ? "" : execute.request().operation());
    }

    private TestExecution blockedExecution(RuntimeSelection selection, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("PROVIDER_RUNTIME_BLOCKED", "PROVIDER_RUNTIME_ERROR", "Provider runtime blocked.", "Review provider runtime validation.")
                : failure;
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("provider_id", selection.providerId());
        testResult.put("provider_type", selection.providerType());
        testResult.put("status", "failed");
        testResult.put("failure_code", resolvedFailure.code());
        testResult.put("failure_classification", resolvedFailure.classification());
        writeFailureDetailIfMissing(selection.runDir(), selection.providerId(), selection.providerType(), resolvedFailure);
        return new TestExecution(
                false,
                "failed",
                false,
                Map.of(),
                List.of(),
                List.of(),
                testResult,
                List.of("provider-evidence/wiremock/failure_detail.yaml"),
                resolvedFailure,
                "");
    }

    private List<RuntimeSelection> selectWireMockRuntimes(Path suiteManifest, String requestedProfile, Path outputBase) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        Map<String, Map<String, Object>> contractsByType =
                readDirectoryByField(frameworkProviderContractsDirectory(suiteRoot), "provider_type");
        RunIds runIds = newRunIds();
        Path suiteRunDir = outputBase
                .resolve(safe(stringValue(suite.get("suite_id"))))
                .resolve(runIds.batchId())
                .resolve(runIds.runId());
        List<Object> testRefs = listValue(suite.get("tests"));
        boolean multiTestSuite = testRefs.size() > 1;
        List<RuntimeSelection> selections = new ArrayList<>();
        for (Object testRef : testRefs) {
            Map<String, Object> testCase = readMap(suiteRoot.resolve(stringValue(testRef)).normalize());
            for (Map.Entry<String, Object> targetEntry : mapValue(testCase.get("targets")).entrySet()) {
                Map<String, Object> target = mapValue(targetEntry.getValue());
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
                Path runDir = multiTestSuite
                        ? suiteRunDir.resolve("tests").resolve(safe(stringValue(testCase.get("test_case_id"))))
                        : suiteRunDir;
                selections.add(new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        targetEntry.getKey(),
                        providerId,
                        providerType,
                        requestedProfile,
                        stringValue(providerBinding.get("runtime_mode")),
                        providerContract,
                        providerInstance,
                        mapValue(providerBinding.get("binding_values")),
                        runIds.batchId(),
                        runIds.runId(),
                        suiteRunDir,
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

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-WIREMOCK-" + suffix, "RUN-WIREMOCK-" + suffix);
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

    private VerificationOutcome verify(
            Map<String, Object> testCase,
            Map<String, Object> outputs,
            Path runDir,
            Path suiteRoot,
            String providerId,
            String providerType) {
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        boolean passed = true;
        for (Object verifyValue : verifyValues(testCase)) {
            Map<String, Object> verify = mapValue(verifyValue);
            String id = stringValue(verify.get("id"));
            String type = stringValue(verify.get("type"));
            boolean checkPassed;
            if ("http_mock_called".equals(type)) {
                int actual = intValue(outputs.get("matched_count"), 0);
                int expected = intValue(verify.get("expected"), 0);
                checkPassed = actual == expected;
                writeAssertion(runDir, id, type, checkPassed, "actual_count: " + actual + "\nexpected_count: " + expected);
            } else if ("http_mock_request_body_match".equals(type)) {
                Path expected = suiteRoot.resolve(stringValue(verify.get("expected_ref"))).normalize();
                Path requestJournal = runDir.resolve(stringValue(outputs.get("request_journal"))).normalize();
                checkPassed = requestBodyMatches(requestJournal, expected);
                writeAssertion(runDir, id, type, checkPassed, "expected_ref: " + expected);
                if (!checkPassed) {
                    writeFailureDetailIfMissing(runDir, providerId, providerType, ProviderFailure.of(
                            "ASSERTION_FAILED",
                            "ASSERTION_FAILED",
                            "WireMock request body did not match expected request.",
                            "Review provider-evidence/wiremock/request_journal.json."));
                }
            } else {
                checkPassed = false;
                writeAssertion(runDir, id, type, false, "unsupported verifier");
            }
            verifyResults.add(assertion(id, type, checkPassed ? "passed" : "failed"));
            evidenceRefs.add("assertions/" + id + ".yaml");
            passed = passed && checkPassed;
        }
        return new VerificationOutcome(passed, List.copyOf(verifyResults), List.copyOf(evidenceRefs));
    }

    private boolean requestBodyMatches(Path requestJournal, Path expectedRequest) {
        Map<String, Object> expected = readMap(expectedRequest);
        String expectedPath = stringValue(expected.get("path"));
        String expectedMethod = stringValue(expected.get("method"));
        Map<String, Object> expectedBody = mapValue(expected.get("body"));
        Map<String, Object> journal = readMap(requestJournal);
        for (Object requestValue : listValue(journal.get("requests"))) {
            Map<String, Object> request = mapValue(mapValue(requestValue).get("request"));
            if (!expectedPath.equals(stringValue(request.get("url")))
                    || !expectedMethod.equals(stringValue(request.get("method")))) {
                continue;
            }
            Map<String, Object> actualBody = readJsonText(stringValue(request.get("body")));
            if (expectedBody.equals(actualBody)) {
                return true;
            }
        }
        return false;
    }

    private SelectedOperation setupOperation(Map<String, Object> testCase, String targetName) {
        for (Object value : listValue(mapValue(testCase.get("setup")).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            if (targetName.equals(stringValue(operation.get("target")))
                    && "load_stubs".equals(stringValue(operation.get("operation")))) {
                String id = stringValue(operation.get("id"));
                return new SelectedOperation(
                        id.isBlank() ? "load_stubs" : id,
                        new ProviderOperationRequest(
                                stringValue(operation.get("operation")),
                                resolvedOperationInputs(testCase, operation),
                                Map.of()));
            }
        }
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("setup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            if (targetName.equals(stringValue(fixture.get("target")))
                    && "load_stubs".equals(stringValue(fixture.get("operation")))) {
                return new SelectedOperation(
                        entry.getKey(),
                        new ProviderOperationRequest(
                                stringValue(fixture.get("operation")),
                                resolvedParameters(testCase, listValue(fixture.get("parameters"))),
                                Map.of()));
            }
        }
        return new SelectedOperation("load_stubs", new ProviderOperationRequest("", List.of(), Map.of()));
    }

    private SelectedOperation executeOperation(Map<String, Object> testCase, String targetName) {
        for (Object value : executeOperations(testCase)) {
            Map<String, Object> step = mapValue(value);
            if (targetName.equals(stringValue(step.get("target")))) {
                String id = stringValue(step.get("id"));
                return new SelectedOperation(
                        id.isBlank() ? "execute" : id,
                        new ProviderOperationRequest(
                                stringValue(step.get("operation")),
                                resolvedOperationInputs(testCase, step),
                                mapValue(step.get("outputs"))));
            }
        }
        return new SelectedOperation("execute", new ProviderOperationRequest("", List.of(), Map.of()));
    }

    private SelectedOperation cleanupOperation(Map<String, Object> testCase, String targetName) {
        for (Object value : listValue(mapValue(testCase.get("cleanup")).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            if (targetName.equals(stringValue(operation.get("target")))
                    && "reset_mock".equals(stringValue(operation.get("operation")))) {
                String id = stringValue(operation.get("id"));
                return new SelectedOperation(
                        id.isBlank() ? "reset_mock" : id,
                        new ProviderOperationRequest(
                                stringValue(operation.get("operation")),
                                resolvedOperationInputs(testCase, operation),
                                Map.of()));
            }
        }
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("cleanup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            if (targetName.equals(stringValue(fixture.get("target")))
                    && "reset_mock".equals(stringValue(fixture.get("operation")))) {
                return new SelectedOperation(
                        entry.getKey(),
                        new ProviderOperationRequest(
                                stringValue(fixture.get("operation")),
                                resolvedParameters(testCase, listValue(fixture.get("parameters"))),
                                Map.of()));
            }
        }
        return new SelectedOperation("reset_mock", new ProviderOperationRequest("reset_mock", List.of(), Map.of()));
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

    private List<Object> executeOperations(Map<String, Object> testCase) {
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

    private ContractFinding finding(Path suiteManifest, RuntimeSelection selection, ProviderFailure failure) {
        return new ContractFinding(
                suiteManifest.toString(),
                "provider_runtime",
                failure.code().toLowerCase(),
                selection.providerId(),
                selection.providerType(),
                selection.profile(),
                "",
                failure.ownerAction());
    }

    private Map<String, Object> step(String id, String operation, String status, Map<String, Object> outputs) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("operation", operation);
        step.put("status", status);
        if (!outputs.isEmpty()) {
            step.put("outputs", outputs);
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

    private void writeExecutionLog(
            Path runDir,
            String providerId,
            String providerType,
            String status,
            Map<String, Object> outputs) {
        write(runDir.resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_type: %s
                provider_id: %s
                status: %s
                base_url: %s
                """.formatted(providerType, providerId, status, outputs.getOrDefault("base_url", "")));
    }

    private void writeAssertion(Path runDir, String id, String type, boolean passed, String detail) {
        write(runDir.resolve("assertions/" + id + ".yaml"), """
                evidence_type: assertion_result
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                assertion_id: %s
                type: %s
                status: %s
                %s
                """.formatted(id, type, passed ? "passed" : "failed", detail));
    }

    private void writeFailureDetailIfMissing(Path runDir, String providerId, String providerType, ProviderFailure failure) {
        Path path = runDir.resolve("provider-evidence/wiremock/failure_detail.yaml");
        if (Files.exists(path)) {
            return;
        }
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("OPERATION_FAILED", "FRAMEWORK_ERROR", "WireMock capability failed.", "Review provider evidence.")
                : failure;
        write(path, """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_type: %s
                provider_id: %s
                classification: %s
                reason: %s
                owner_action: %s
                """.formatted(
                        providerType,
                        providerId,
                        resolvedFailure.classification(),
                        resolvedFailure.reason(),
                        resolvedFailure.ownerAction()));
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
                provider_type: %s
                provider_id: %s
                status: %s
                """.formatted(
                        selection.suite().get("suite_id"),
                        selection.batchId(),
                        selection.runId(),
                        topLevelTestCaseId(selection, testCount),
                        testCount,
                        selection.providerType(),
                        selection.providerId(),
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
                "local_wiremock",
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
            Map<String, Object> outputs,
            String operationName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", selection.providerId());
        result.put("provider_type", selection.providerType());
        result.put("profile", profile);
        result.put("runtime_mode", selection.runtimeMode());
        result.put("base_url", outputs.getOrDefault("base_url", ""));
        result.put("resolved_operation_result", Map.of(
                "operation", operationName.isBlank() ? "send_http_request" : operationName,
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private List<String> prefixEvidenceRefs(RuntimeSelection selection, List<String> refs) {
        if (selection.runDir().equals(selection.suiteRunDir())) {
            return refs.stream()
                    .filter(ref -> ref != null && !ref.isBlank())
                    .toList();
        }
        String prefix = "tests/" + safe(stringValue(selection.testCase().get("test_case_id"))) + "/";
        return refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(ref -> prefix + ref)
                .toList();
    }

    private String topLevelTestCaseId(RuntimeSelection selection, int testCount) {
        if (testCount == 1) {
            return stringValue(selection.testCase().get("test_case_id"));
        }
        return stringValue(selection.suite().get("suite_id")) + "-MULTI";
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
            throw new UncheckedIOException("Failed to read WireMock capability artifact: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonText(String json) {
        Object loaded = yaml.load(json);
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
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
                throw new UncheckedIOException("Failed to clean generated WireMock output: " + directory, e);
            }
        }
        createDirectories(directory);
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated WireMock output: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write WireMock capability output: " + path, e);
        }
    }

    public record WireMockRunResult(
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
            String baseUrl,
            Path resultJson,
            Path evidenceDir,
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static WireMockRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new WireMockRunResult(
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
            Map<String, Object> providerContract,
            Map<String, Object> providerInstance,
            Map<String, Object> bindingValues,
            String batchId,
            String runId,
            Path suiteRunDir,
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
            ProviderFailure failure,
            String operationName) {
    }

    private record RunIds(String batchId, String runId) {
    }

    private record VerificationOutcome(
            boolean passed,
            List<Map<String, Object>> verifyResults,
            List<String> evidenceRefs) {
    }
}
