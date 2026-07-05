package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.provider.http.RestClientProviderRuntime;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class WireMockHttpRequestCapabilityService {

    private static final String WIREMOCK = "wiremock_http_mock";
    private static final String REST_CLIENT = "rest_client";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public MixedRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return MixedRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!isSupportedProviderSet(validation.providerTypesUsed())) {
            return MixedRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "WireMock + HTTP request sample mode supports provider_types `wiremock_http_mock` and `rest_client` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return MixedRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    WIREMOCK + "," + REST_CLIENT,
                    "",
                    "",
                    "Provide --profile for WireMock + HTTP request sample run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile);
        if (!profileFindings.isEmpty()) {
            return MixedRunResult.blocked(validation.suiteId(), requestedProfile, profileFindings);
        }

        List<RuntimeSelection> selections = selectRuntimes(suiteManifest, requestedProfile, outputBase);
        if (selections.isEmpty()) {
            return MixedRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_provider_targets",
                    "",
                    WIREMOCK + "," + REST_CLIENT,
                    requestedProfile,
                    "",
                    "Declare one WireMock target and one rest_client target in the sample DSL.")));
        }
        RuntimeSelection firstSelection = selections.get(0);
        recreateDirectory(firstSelection.suiteRunDir());

        WireMockHttpMockProviderRuntime wireMockRuntime = new WireMockHttpMockProviderRuntime();
        RestClientProviderRuntime restRuntime = new RestClientProviderRuntime();
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(new ProviderRuntimeRegistry(Map.of(
                WIREMOCK, wireMockRuntime,
                REST_CLIENT, restRuntime)));

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
            aggregateOutputs.put(safe(stringValue(selection.testCase().get("test_case_id"))), execution.outputs());
            stepResults.addAll(execution.stepResults());
            verifyResults.addAll(execution.verifyResults());
            testResults.add(execution.testResult());
            providerResults.addAll(execution.providerResults());
            evidenceRefs.addAll(prefixEvidenceRefs(selection, execution.evidenceRefs()));
            if (!execution.passed() && "passed".equals(status)) {
                status = "failed";
                failure = execution.failure();
                failureSelection = selection;
            }
        }

        Instant finishedAt = Instant.now();
        writeExecutionLog(firstSelection.suiteRunDir(), firstSelection, status, aggregateOutputs);
        evidenceRefs.add("logs/execution.log");
        writeBatch(firstSelection.suiteRunDir(), firstSelection, status, selections.size());
        evidenceRefs.add("batch/batch.yaml");
        if ("failed".equals(status)) {
            RuntimeSelection failedSelection = failureSelection == null ? firstSelection : failureSelection;
            writeFailureDetail(firstSelection.suiteRunDir(), failedSelection, failure);
            evidenceRefs.add("provider-evidence/wiremock_http_request/failure_detail.yaml");
        }
        writeEvidenceIndex(
                firstSelection.suiteRunDir(),
                firstSelection,
                topLevelTestCaseId(firstSelection, selections.size()),
                evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = writeResult(
                firstSelection.suiteRunDir(),
                firstSelection,
                requestedProfile,
                topLevelTestCaseId(firstSelection, selections.size()),
                selections.size(),
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
        return new MixedRunResult(
                "passed".equals(status),
                status,
                stringValue(firstSelection.suite().get("suite_id")),
                firstSelection.batchId(),
                firstSelection.runId(),
                topLevelTestCaseId(firstSelection, selections.size()),
                requestedProfile,
                List.of(firstSelection.wireProviderId(), firstSelection.restProviderId()),
                List.of(WIREMOCK, REST_CLIENT),
                resultJson,
                firstSelection.suiteRunDir(),
                selections.size(),
                providerRuntimeExecuted,
                List.of());
    }

    private boolean isSupportedProviderSet(List<String> providerTypes) {
        Set<String> types = new LinkedHashSet<>(providerTypes);
        return types.size() == 2 && types.contains(WIREMOCK) && types.contains(REST_CLIENT);
    }

    private MixedRunResult blocked(RuntimeSelection selection, String profile, ProviderFailure failure) {
        return MixedRunResult.blocked(
                stringValue(selection.suite().get("suite_id")),
                profile,
                List.of(new ContractFinding(
                        selection.suiteRoot().resolve("suite_manifest.yaml").toString(),
                        "provider_runtime",
                        failure.code().toLowerCase(),
                        "",
                        WIREMOCK + "," + REST_CLIENT,
                        profile,
                        "",
                        failure.ownerAction())));
    }

    private ProviderExecutionContext context(
            RuntimeSelection selection,
            Map<String, Object> bindingValues,
            String providerId,
            String providerType,
            String runtimeMode,
            Map<String, Object> contract,
            Map<String, Object> instance) {
        return new ProviderExecutionContext(
                providerId,
                providerType,
                selection.profile(),
                runtimeMode,
                selection.suiteRoot(),
                selection.runDir(),
                contract,
                instance,
                bindingValues);
    }

    private TestExecution executeTestCase(
            ProviderRuntimeResolver resolver,
            WireMockHttpMockProviderRuntime wireMockRuntime,
            RuntimeSelection selection,
            String requestedProfile) {
        recreateDirectory(selection.runDir());
        String testCaseId = stringValue(selection.testCase().get("test_case_id"));
        String status = "passed";
        ProviderFailure failure = null;
        boolean providerRuntimeExecuted = false;
        Map<String, Map<String, Object>> capturedOutputs = new LinkedHashMap<>();
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Map<String, Object> restBindingValues = new LinkedHashMap<>(selection.restBindingValues());
        ProviderExecutionContext wireContext = context(selection, selection.wireBindingValues(), selection.wireProviderId(),
                WIREMOCK, selection.wireRuntimeMode(), selection.wireContract(), selection.wireInstance());

        try {
            SelectedOperation setup = operation(selection.testCase(), "setup", selection.wireTarget(), "load_stubs");
            ProviderRuntimeResolution setupResolution = resolver.resolve(wireContext, setup.request());
            if (!setupResolution.valid()) {
                return blockedExecution(selection, requestedProfile, setupResolution.failure());
            }
            ProviderOperationResult setupResult = setupResolution.runtime().execute(wireContext, setup.request());
            providerRuntimeExecuted = true;
            capture(setup.id(), setupResult, capturedOutputs, outputs, stepResults, evidenceRefs);
            if (!setupResult.passed()) {
                status = "failed";
                failure = setupResult.failure();
            } else {
                restBindingValues.put("base_url", stringValue(setupResult.outputs().get("base_url")));
                ProviderExecutionContext restContext = context(selection, restBindingValues, selection.restProviderId(),
                        REST_CLIENT, selection.restRuntimeMode(), selection.restContract(), selection.restInstance());
                SelectedOperation execute = operation(selection.testCase(), "execute", selection.restTarget(), "http_request");
                ProviderRuntimeResolution executeResolution = resolver.resolve(restContext, execute.request());
                if (!executeResolution.valid()) {
                    return blockedExecution(selection, requestedProfile, executeResolution.failure());
                }
                ProviderOperationResult executeResult = executeResolution.runtime().execute(restContext, execute.request());
                capture(execute.id(), executeResult, capturedOutputs, outputs, stepResults, evidenceRefs);
                writeWireMockRequestJournal(selection, stringValue(setupResult.outputs().get("base_url")));
                evidenceRefs.add("provider-evidence/wiremock/request_journal.json");
                if (!executeResult.passed()) {
                    status = "failed";
                    failure = executeResult.failure();
                } else {
                    VerificationOutcome verification = verify(selection, capturedOutputs);
                    verifyResults.addAll(verification.verifyResults());
                    evidenceRefs.addAll(verification.evidenceRefs());
                    if (!verification.passed()) {
                        status = "failed";
                        failure = ProviderFailure.of(
                                "ASSERTION_FAILED",
                                "ASSERTION_FAILED",
                                "One or more WireMock + HTTP request verification checks failed.",
                                "Review assertion evidence and provider request/response evidence.");
                    }
                }
            }
        } finally {
            SelectedOperation cleanup = operation(selection.testCase(), "cleanup", selection.wireTarget(), "reset_mock");
            ProviderOperationResult cleanupResult = wireMockRuntime.execute(wireContext, cleanup.request());
            capture(cleanup.id(), cleanupResult, capturedOutputs, outputs, stepResults, evidenceRefs);
            if (!cleanupResult.passed() && "passed".equals(status)) {
                status = "failed";
                failure = cleanupResult.failure();
            }
        }

        writeExecutionLog(selection.runDir(), selection, status, outputs);
        evidenceRefs.add("logs/execution.log");
        writeBatch(selection.runDir(), selection, status, 1);
        evidenceRefs.add("batch/batch.yaml");
        List<Map<String, Object>> providerResults = List.of(
                providerResult(selection.wireProviderId(), WIREMOCK, selection.wireRuntimeMode(), status, outputs),
                providerResult(selection.restProviderId(), REST_CLIENT, selection.restRuntimeMode(), status, outputs));
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", testCaseId);
        testResult.put("profile", requestedProfile);
        testResult.put("provider_ids", List.of(selection.wireProviderId(), selection.restProviderId()));
        testResult.put("provider_types", List.of(WIREMOCK, REST_CLIENT));
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
                providerResults,
                evidenceRefs,
                failure);
    }

    private TestExecution blockedExecution(RuntimeSelection selection, String requestedProfile, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("PROVIDER_RUNTIME_BLOCKED", "PROVIDER_RUNTIME_ERROR", "Provider runtime blocked.", "Review provider runtime validation.")
                : failure;
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_ids", List.of(selection.wireProviderId(), selection.restProviderId()));
        testResult.put("provider_types", List.of(WIREMOCK, REST_CLIENT));
        testResult.put("status", "failed");
        testResult.put("failure_code", resolvedFailure.code());
        testResult.put("failure_classification", resolvedFailure.classification());
        writeFailureDetail(selection.runDir(), selection, resolvedFailure);
        return new TestExecution(
                false,
                "failed",
                false,
                Map.of(),
                List.of(),
                List.of(),
                testResult,
                List.of(),
                List.of("provider-evidence/wiremock_http_request/failure_detail.yaml"),
                resolvedFailure);
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

    private void capture(
            String operationId,
            ProviderOperationResult result,
            Map<String, Map<String, Object>> capturedOutputs,
            Map<String, Object> aggregateOutputs,
            List<Map<String, Object>> stepResults,
            List<String> evidenceRefs) {
        capturedOutputs.put(operationId, result.outputs());
        aggregateOutputs.putAll(result.outputs());
        stepResults.add(step(operationId, result.passed() ? "passed" : "failed", result.outputs()));
        evidenceRefs.addAll(result.evidence().stream()
                .map(evidence -> evidence.ref())
                .filter(ref -> ref != null && !ref.isBlank())
                .toList());
    }

    private List<RuntimeSelection> selectRuntimes(Path suiteManifest, String requestedProfile, Path outputBase) {
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
        List<RuntimeSelection> selections = new ArrayList<>();
        List<Object> testRefs = listValue(suite.get("tests"));
        boolean multiTestSuite = testRefs.size() > 1;
        for (Object testRef : testRefs) {
            Map<String, Object> testCase = readMap(suiteRoot.resolve(stringValue(testRef)).normalize());
            TargetSelection wireTarget = null;
            TargetSelection restTarget = null;
            for (Map.Entry<String, Object> targetEntry : mapValue(testCase.get("targets")).entrySet()) {
                String providerId = stringValue(mapValue(targetEntry.getValue()).get("provider_id"));
                Map<String, Object> providerInstance = instancesById.get(providerId);
                String providerType = stringValue(providerInstance.get("provider_type"));
                Map<String, Object> providerBinding =
                        runtimeBindingResolver.providerBinding(suiteRoot, requestedProfile, providerId);
                TargetSelection target = new TargetSelection(
                        targetEntry.getKey(),
                        providerId,
                        stringValue(providerBinding.get("runtime_mode")),
                        providerInstance,
                        mapValue(providerBinding.get("binding_values")),
                        contractsByType.get(providerType));
                if (WIREMOCK.equals(providerType)) {
                    wireTarget = target;
                } else if (REST_CLIENT.equals(providerType)) {
                    restTarget = target;
                }
            }
            if (wireTarget != null && restTarget != null) {
                Path runDir = multiTestSuite
                        ? suiteRunDir.resolve("tests").resolve(safe(stringValue(testCase.get("test_case_id"))))
                        : suiteRunDir;
                selections.add(new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        requestedProfile,
                        wireTarget.targetName(),
                        wireTarget.providerId(),
                        wireTarget.runtimeMode(),
                        wireTarget.contract(),
                        wireTarget.instance(),
                        wireTarget.bindingValues(),
                        restTarget.targetName(),
                        restTarget.providerId(),
                        restTarget.runtimeMode(),
                        restTarget.contract(),
                        restTarget.instance(),
                        restTarget.bindingValues(),
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
        return SuiteProfileGate.validate(suiteManifest, suite, testCases, requestedProfile, WIREMOCK + "," + REST_CLIENT);
    }

    private SelectedOperation operation(Map<String, Object> testCase, String section, String targetName, String operationName) {
        for (Object value : listValue(mapValue(testCase.get(section)).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            if (targetName.equals(stringValue(operation.get("target")))
                    && operationName.equals(stringValue(operation.get("operation")))) {
                String id = stringValue(operation.get("id"));
                return new SelectedOperation(
                        id.isBlank() ? operationName : id,
                        new ProviderOperationRequest(
                                operationName,
                                resolvedOperationInputs(testCase, operation),
                                mapValue(operation.get("outputs"))));
            }
        }
        return new SelectedOperation(operationName, new ProviderOperationRequest(operationName, List.of(), Map.of()));
    }

    private List<Map<String, Object>> resolvedOperationInputs(Map<String, Object> testCase, Map<String, Object> operation) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Map.Entry<String, Object> entry : mapValue(operation.get("inputs")).entrySet()) {
            Map<String, Object> input = new LinkedHashMap<>(mapValue(entry.getValue()));
            input.put("bind_as", entry.getKey());
            input.put("name", entry.getKey());
            input.put("ref", resolveRef(testCase, firstNonBlank(
                    stringValue(input.get("ref")),
                    stringValue(input.get("value")))));
            parameters.add(input);
        }
        return List.copyOf(parameters);
    }

    private String resolveRef(Map<String, Object> testCase, String ref) {
        if (!ref.startsWith("${data.") || !ref.endsWith("}")) {
            return ref;
        }
        String key = ref.substring("${data.".length(), ref.length() - 1);
        String resolved = stringValue(mapValue(mapValue(testCase.get("data")).get(key)).get("ref"));
        return resolved.isBlank() ? ref : resolved;
    }

    private VerificationOutcome verify(RuntimeSelection selection, Map<String, Map<String, Object>> capturedOutputs) {
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        boolean passed = true;
        for (Object verifyValue : verifyValues(selection.testCase())) {
            Map<String, Object> verify = mapValue(verifyValue);
            String id = stringValue(verify.get("id"));
            String type = stringValue(verify.get("type"));
            Object actual = resolveActual(capturedOutputs, stringValue(mapValue(verify.get("actual")).get("ref")));
            boolean checkPassed = switch (type) {
                case "value_equals" -> valuesEqual(actual, verify.get("expected"));
                case "json_match" -> jsonMatches(selection.suiteRoot(), actual, verify);
                default -> false;
            };
            String ref = "json_match".equals(type)
                    ? "assertions/" + id + ".json"
                    : "assertions/" + id + ".yaml";
            writeAssertion(selection.runDir().resolve(ref), id, type, checkPassed, actual, verify);
            verifyResults.add(assertion(id, type, checkPassed ? "passed" : "failed"));
            evidenceRefs.add(ref);
            passed = passed && checkPassed;
        }
        return new VerificationOutcome(passed, List.copyOf(verifyResults), List.copyOf(evidenceRefs));
    }

    private Object resolveActual(Map<String, Map<String, Object>> capturedOutputs, String ref) {
        if (!ref.startsWith("${execute.") || !ref.endsWith("}")) {
            return ref;
        }
        String body = ref.substring("${execute.".length(), ref.length() - 1);
        int marker = body.indexOf(".outputs.");
        if (marker < 0) {
            return "";
        }
        String operationId = body.substring(0, marker);
        String outputRef = body.substring(marker + ".outputs.".length());
        return mapValue(capturedOutputs.get(operationId)).getOrDefault(outputRef, "");
    }

    private boolean valuesEqual(Object actual, Object expected) {
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            return Double.compare(actualNumber.doubleValue(), expectedNumber.doubleValue()) == 0;
        }
        return stringValue(actual).equals(stringValue(expected));
    }

    private boolean jsonMatches(Path suiteRoot, Object actual, Map<String, Object> verify) {
        Map<String, Object> actualJson = mapValue(yaml.load(stringValue(actual)));
        Map<String, Object> expectedJson = readMap(suiteRoot.resolve(stringValue(verify.get("expected_ref"))).normalize());
        for (String ignorePath : stringList(verify.get("ignore_paths"))) {
            removePath(actualJson, ignorePath);
            removePath(expectedJson, ignorePath);
        }
        return actualJson.equals(expectedJson);
    }

    private void removePath(Map<String, Object> value, String path) {
        if (path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        Map<String, Object> current = value;
        for (int index = 0; index < parts.length - 1; index++) {
            Object next = current.get(parts[index]);
            if (!(next instanceof Map<?, ?>)) {
                return;
            }
            current = mapValue(next);
        }
        current.remove(parts[parts.length - 1]);
    }

    private void writeAssertion(
            Path path,
            String id,
            String type,
            boolean passed,
            Object actual,
            Map<String, Object> verify) {
        String status = passed ? "passed" : "failed";
        if (path.toString().endsWith(".json")) {
            write(path, """
                    {
                      "evidence_type": "assertion_diff",
                      "assertion_id": "%s",
                      "type": "%s",
                      "status": "%s",
                      "comparison_status": "%s",
                      "expected_ref": %s,
                      "actual_sample": %s,
                      "masking_applied": true%s
                    }
                    """.formatted(
                            escape(id),
                            escape(type),
                            status,
                            status,
                            toJson(stringValue(verify.get("expected_ref"))),
                            toJson(actual),
                            passed ? "" : ",\n  \"failure_code\": \"ASSERTION_FAILED\""));
            return;
        }
        write(path, """
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
                        id,
                        type,
                        status,
                        status,
                        stringValue(verify.get("expected")),
                        stringValue(actual),
                        passed ? "" : "failure_code: ASSERTION_FAILED"));
    }

    private void writeExecutionLog(Path runDir, RuntimeSelection selection, String status, Map<String, Object> outputs) {
        write(runDir.resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_types: [wiremock_http_mock, rest_client]
                provider_ids: [%s, %s]
                status: %s
                response_status: %s
                """.formatted(
                        selection.wireProviderId(),
                        selection.restProviderId(),
                        status,
                        outputs.getOrDefault("response.status", "")));
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
                provider_types: [wiremock_http_mock, rest_client]
                status: %s
                """.formatted(
                        selection.suite().get("suite_id"),
                        selection.batchId(),
                        selection.runId(),
                        topLevelTestCaseId(selection, testCount),
                        testCount,
                        status));
    }

    private void writeWireMockRequestJournal(RuntimeSelection selection, String baseUrl) {
        if (baseUrl.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/__admin/requests"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            write(selection.runDir().resolve("provider-evidence/wiremock/request_journal.json"), response.body() + "\n");
        } catch (IOException e) {
            write(selection.runDir().resolve("provider-evidence/wiremock/request_journal.json"), """
                    {
                      "requests": [],
                      "status": "failed",
                      "failure_code": "WIREMOCK_JOURNAL_UNAVAILABLE",
                      "error": "%s"
                    }
                    """.formatted(escape(e.getMessage() == null ? e.getClass().getName() : e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            write(selection.runDir().resolve("provider-evidence/wiremock/request_journal.json"), """
                    {
                      "requests": [],
                      "status": "failed",
                      "failure_code": "WIREMOCK_JOURNAL_INTERRUPTED",
                      "error": "interrupted"
                    }
                    """);
        }
    }

    private void writeFailureDetail(Path runDir, RuntimeSelection selection, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("ASSERTION_FAILED", "ASSERTION_FAILED", "WireMock HTTP request capability failed.", "Review provider and assertion evidence.")
                : failure;
        write(runDir.resolve("provider-evidence/wiremock_http_request/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_types: [wiremock_http_mock, rest_client]
                provider_ids: [%s, %s]
                profile: %s
                failure_code: %s
                classification: %s
                reason: %s
                owner_action: %s
                masking:
                  raw_secret_found: false
                """.formatted(
                selection.wireProviderId(),
                selection.restProviderId(),
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
            EvidenceEntry entry = evidenceEntry(runDir, selection, ref);
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
            index.append("    status: ").append(entry.status()).append('\n');
            index.append("    created_at: ").append(Instant.now()).append('\n');
            index.append("    masking_applied: true\n");
            index.append("    linked_result_field: ").append(entry.linkedResultField()).append('\n');
            if (!entry.failureCode().isBlank()) {
                index.append("    failure_code: ").append(entry.failureCode()).append('\n');
            }
        }
        index.append("masking:\n  raw_secret_found: false\n");
        write(runDir.resolve("evidence_index.yaml"), index.toString());
    }

    private EvidenceEntry evidenceEntry(Path runDir, RuntimeSelection selection, String ref) {
        String evidenceType = evidenceType(ref);
        String content = read(runDir.resolve(ref));
        String status = evidenceFailed(evidenceType, content) ? "failed" : "passed";
        String failureCode = "failed".equals(status) ? firstNonBlank(failureCode(content), "ASSERTION_FAILED") : "";
        String providerType = "";
        String providerId = "";
        if (ref.contains("provider-evidence/wiremock/")) {
            providerType = WIREMOCK;
            providerId = selection.wireProviderId();
        } else if (ref.contains("provider-evidence/http/")) {
            providerType = REST_CLIENT;
            providerId = selection.restProviderId();
        } else if (ref.contains("provider-evidence/wiremock_http_request/")) {
            providerType = WIREMOCK;
            providerId = selection.wireProviderId();
        }
        return new EvidenceEntry(
                evidenceType + "-" + safe(ref),
                evidenceType,
                providerType.isBlank() && !"assertion_diff".equals(evidenceType) ? "framework"
                        : ("assertion_diff".equals(evidenceType) ? "assertion_engine" : "provider"),
                providerType,
                providerId,
                status,
                failureCode,
                linkedResultField(evidenceType));
    }

    private String evidenceType(String ref) {
        String normalized = ref.toLowerCase();
        if (normalized.contains("logs/execution")) {
            return "execution_log";
        }
        if (normalized.contains("batch/")) {
            return "batch_summary";
        }
        if (normalized.contains("assertions/")) {
            return "assertion_diff";
        }
        if (normalized.contains("provider-evidence/http/")) {
            return "http_request_response";
        }
        if (normalized.contains("request_journal")) {
            return "wiremock_request_journal";
        }
        if (normalized.contains("cleanup")) {
            return "fixture_cleanup";
        }
        if (normalized.contains("injected_stubs")) {
            return "fixture_setup";
        }
        return "wiremock_server_log";
    }

    private String linkedResultField(String evidenceType) {
        return switch (evidenceType) {
            case "execution_log", "batch_summary" -> "evidence_refs";
            case "assertion_diff" -> "verify_results";
            case "fixture_cleanup" -> "provider_results.cleanup_status";
            default -> "provider_results.resolved_operation_result";
        };
    }

    private Path writeResult(
            Path runDir,
            RuntimeSelection selection,
            String profile,
            String testCaseId,
            int testCount,
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
                Map.of(),
                failure,
                null,
                true));
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
        result.put("resolved_operation_result", Map.of(
                "operation", REST_CLIENT.equals(providerType) ? "http_request" : "load_stubs",
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private String topLevelTestCaseId(RuntimeSelection selection, int testCount) {
        return ProviderCapabilityResultWriter.topLevelTestCaseId(
                selection.suite().get("suite_id"), selection.testCase().get("test_case_id"), testCount);
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

    private Map<String, Object> assertion(String id, String type, String status) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("id", id);
        assertion.put("type", type);
        assertion.put("status", status);
        return assertion;
    }

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-WIREMOCK-HTTP-" + suffix, "RUN-WIREMOCK-HTTP-" + suffix);
    }

    private Path frameworkProviderContractsDirectory(Path suiteRoot) {
        return FrameworkProviderContractCatalog.resolveDirectory(suiteRoot, FRAMEWORK_PROVIDER_CONTRACTS);
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

    private List<Object> verifyValues(Map<String, Object> testCase) {
        return listValue(mapValue(testCase.get("verify")).get("checks"));
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
            throw new UncheckedIOException("Failed to read mixed provider sample artifact: " + path, e);
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

    private List<String> stringList(Object value) {
        return listValue(value).stream().map(this::stringValue).filter(text -> !text.isBlank()).toList();
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

    private boolean evidenceFailed(String evidenceType, String content) {
        if (!"assertion_diff".equals(evidenceType) && failureCode(content).isBlank()) {
            return false;
        }
        String lower = content.toLowerCase();
        return lower.contains("status: failed")
                || lower.contains("\"status\": \"failed\"")
                || lower.contains("comparison_status: failed")
                || lower.contains("\"comparison_status\": \"failed\"");
    }

    private String failureCode(String content) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("failure_code:")) {
                return trimmed.substring("failure_code:".length()).trim();
            }
            if (trimmed.startsWith("\"failure_code\"")) {
                int colon = trimmed.indexOf(':');
                if (colon >= 0) {
                    return trimmed.substring(colon + 1).replace("\"", "").replace(",", "").trim();
                }
            }
        }
        return "";
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

    private String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void recreateDirectory(Path directory) {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to clean generated mixed provider output: " + directory, e);
            }
        }
        write(directory.resolve(".keep"), "");
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write mixed provider output: " + path, e);
        }
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

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escape(text) + "\"";
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

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public record MixedRunResult(
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

        static MixedRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new MixedRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    profile,
                    List.of(),
                    List.of(WIREMOCK, REST_CLIENT),
                    null,
                    null,
                    0,
                    false,
                    List.copyOf(findings));
        }
    }

    private record RuntimeSelection(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            String wireTarget,
            String wireProviderId,
            String wireRuntimeMode,
            Map<String, Object> wireContract,
            Map<String, Object> wireInstance,
            Map<String, Object> wireBindingValues,
            String restTarget,
            String restProviderId,
            String restRuntimeMode,
            Map<String, Object> restContract,
            Map<String, Object> restInstance,
            Map<String, Object> restBindingValues,
            String batchId,
            String runId,
            Path suiteRunDir,
            Path runDir) {
    }

    private record TargetSelection(
            String targetName,
            String providerId,
            String runtimeMode,
            Map<String, Object> instance,
            Map<String, Object> bindingValues,
            Map<String, Object> contract) {
    }

    private record SelectedOperation(String id, ProviderOperationRequest request) {
    }

    private record RunIds(String batchId, String runId) {
    }

    private record VerificationOutcome(
            boolean passed,
            List<Map<String, Object>> verifyResults,
            List<String> evidenceRefs) {
    }

    private record TestExecution(
            boolean passed,
            String status,
            boolean providerRuntimeExecuted,
            Map<String, Object> outputs,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            Map<String, Object> testResult,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs,
            ProviderFailure failure) {
    }

    private record EvidenceEntry(
            String evidenceId,
            String evidenceType,
            String producedBy,
            String providerType,
            String providerId,
            String status,
            String failureCode,
            String linkedResultField) {
    }
}
