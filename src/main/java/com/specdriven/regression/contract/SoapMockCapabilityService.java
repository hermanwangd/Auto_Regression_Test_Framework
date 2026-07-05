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
import com.specdriven.regression.provider.soap.SoapMockProviderRuntime;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class SoapMockCapabilityService {

    private static final String SOAP_MOCK = "soap_mock";
    private static final String REST_CLIENT = "rest_client";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public SoapRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return SoapRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!isSupportedProviderSet(validation.providerTypesUsed())) {
            return SoapRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "SOAP mock sample mode supports provider_types `soap_mock` and `rest_client` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return SoapRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    SOAP_MOCK + "," + REST_CLIENT,
                    "",
                    "",
                    "Provide --profile for SOAP mock sample run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile);
        if (!profileFindings.isEmpty()) {
            return SoapRunResult.blocked(validation.suiteId(), requestedProfile, profileFindings);
        }

        List<RuntimeSelection> selections = selectRuntimes(suiteManifest, requestedProfile, outputBase);
        if (selections.isEmpty()) {
            return SoapRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_provider_targets",
                    "",
                    SOAP_MOCK + "," + REST_CLIENT,
                    requestedProfile,
                    "",
                    "Declare one soap_mock target and one rest_client target in the SOAP sample DSL.")));
        }
        RuntimeSelection firstSelection = selections.get(0);
        recreateDirectory(firstSelection.suiteRunDir());

        SoapMockProviderRuntime soapRuntime = new SoapMockProviderRuntime();
        RestClientProviderRuntime restRuntime = new RestClientProviderRuntime();
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(new ProviderRuntimeRegistry(Map.of(
                SOAP_MOCK, soapRuntime,
                REST_CLIENT, restRuntime)));

        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        ProviderFailure cleanupFailure = null;
        RuntimeSelection failureSelection = null;
        boolean providerRuntimeExecuted = false;
        Map<String, Object> aggregateOutputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> testResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();

        for (RuntimeSelection selection : selections) {
            TestExecution execution = executeTestCase(resolver, soapRuntime, selection, requestedProfile);
            providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeExecuted();
            aggregateOutputs.put(safe(stringValue(selection.testCase().get("test_case_id"))), execution.outputs());
            stepResults.addAll(execution.stepResults());
            verifyResults.addAll(execution.verifyResults());
            testResults.add(execution.testResult());
            providerResults.addAll(execution.providerResults());
            evidenceRefs.addAll(prefixEvidenceRefs(selection, execution.evidenceRefs()));
            if (execution.cleanupFailure() != null && cleanupFailure == null) {
                cleanupFailure = execution.cleanupFailure();
            }
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
            evidenceRefs.add("provider-evidence/soap_mock/failure_detail.yaml");
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
                cleanupFailure,
                startedAt,
                finishedAt);
        return new SoapRunResult(
                "passed".equals(status),
                status,
                stringValue(firstSelection.suite().get("suite_id")),
                firstSelection.batchId(),
                firstSelection.runId(),
                topLevelTestCaseId(firstSelection, selections.size()),
                requestedProfile,
                List.of(firstSelection.soapProviderId(), firstSelection.restProviderId()),
                List.of(SOAP_MOCK, REST_CLIENT),
                resultJson,
                firstSelection.suiteRunDir(),
                selections.size(),
                providerRuntimeExecuted,
                List.of());
    }

    private boolean isSupportedProviderSet(List<String> providerTypes) {
        Set<String> types = new LinkedHashSet<>(providerTypes);
        return types.size() == 2 && types.contains(SOAP_MOCK) && types.contains(REST_CLIENT);
    }

    private SoapRunResult blocked(RuntimeSelection selection, String profile, ProviderFailure failure) {
        return SoapRunResult.blocked(
                stringValue(selection.suite().get("suite_id")),
                profile,
                List.of(new ContractFinding(
                        selection.suiteRoot().resolve("suite_manifest.yaml").toString(),
                        "provider_runtime",
                        failure.code().toLowerCase(),
                        "",
                        SOAP_MOCK + "," + REST_CLIENT,
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
            SoapMockProviderRuntime soapRuntime,
            RuntimeSelection selection,
            String requestedProfile) {
        recreateDirectory(selection.runDir());
        String status = "passed";
        ProviderFailure failure = null;
        ProviderFailure cleanupFailure = null;
        String cleanupStatus = "not_run";
        boolean providerRuntimeExecuted = false;
        Map<String, Map<String, Object>> capturedOutputs = new LinkedHashMap<>();
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Map<String, Object> restBindingValues = new LinkedHashMap<>(selection.restBindingValues());
        ProviderExecutionContext soapContext = context(
                selection,
                selection.soapBindingValues(),
                selection.soapProviderId(),
                SOAP_MOCK,
                selection.soapRuntimeMode(),
                selection.soapContract(),
                selection.soapInstance());

        try {
            SelectedOperation setup = operation(selection.testCase(), "setup", selection.soapTarget(), "load_soap_stub");
            ProviderRuntimeResolution setupResolution = resolver.resolve(soapContext, setup.request());
            if (!setupResolution.valid()) {
                return blockedExecution(selection, requestedProfile, setupResolution.failure());
            }
            ProviderOperationResult setupResult = setupResolution.runtime().execute(soapContext, setup.request());
            providerRuntimeExecuted = true;
            capture(setup.id(), "load_soap_stub", setupResult, capturedOutputs, outputs, stepResults, evidenceRefs);
            if (!setupResult.passed()) {
                status = "failed";
                failure = setupResult.failure();
            } else {
                restBindingValues.put("base_url", stringValue(setupResult.outputs().get("endpoint_url")));
                ProviderExecutionContext restContext = context(
                        selection,
                        restBindingValues,
                        selection.restProviderId(),
                        REST_CLIENT,
                        selection.restRuntimeMode(),
                        selection.restContract(),
                        selection.restInstance());
                SelectedOperation execute = operation(selection.testCase(), "execute", selection.restTarget(), "http_request");
                ProviderRuntimeResolution executeResolution = resolver.resolve(restContext, execute.request());
                if (!executeResolution.valid()) {
                    return blockedExecution(selection, requestedProfile, executeResolution.failure());
                }
                ProviderOperationResult executeResult = executeResolution.runtime().execute(restContext, execute.request());
                capture(execute.id(), "http_request", executeResult, capturedOutputs, outputs, stepResults, evidenceRefs);
                if (!executeResult.passed()) {
                    status = "failed";
                    failure = executeResult.failure();
                } else {
                    SelectedOperation observe =
                            operation(selection.testCase(), "execute", selection.soapTarget(), "soap_request_received");
                    ProviderRuntimeResolution observeResolution = resolver.resolve(soapContext, observe.request());
                    if (!observeResolution.valid()) {
                        return blockedExecution(selection, requestedProfile, observeResolution.failure());
                    }
                    ProviderOperationResult observeResult =
                            observeResolution.runtime().execute(soapContext, observe.request());
                    capture(observe.id(), "soap_request_received", observeResult,
                            capturedOutputs, outputs, stepResults, evidenceRefs);
                    if (!observeResult.passed()) {
                        status = "failed";
                        failure = observeResult.failure();
                    } else {
                        VerificationOutcome verification = verify(selection, capturedOutputs);
                        verifyResults.addAll(verification.verifyResults());
                        evidenceRefs.addAll(verification.evidenceRefs());
                        if (!verification.passed()) {
                            status = "failed";
                            failure = ProviderFailure.of(
                                    "ASSERTION_FAILED",
                                    "ASSERTION_FAILED",
                                    "One or more SOAP mock verification checks failed.",
                                    "Review SOAP request journal, HTTP response evidence, and assertion evidence.");
                        }
                    }
                }
            }
        } finally {
            SelectedOperation cleanup = operation(selection.testCase(), "cleanup", selection.soapTarget(), "reset_mock");
            ProviderOperationResult cleanupResult = soapRuntime.execute(soapContext, cleanup.request());
            cleanupStatus = cleanupResult.passed() ? "passed" : "failed";
            capture(cleanup.id(), "reset_mock", cleanupResult,
                    capturedOutputs, outputs, stepResults, evidenceRefs);
            if (!cleanupResult.passed()) {
                cleanupFailure = cleanupResult.failure();
                if ("passed".equals(status)) {
                    status = "failed";
                    failure = cleanupResult.failure();
                }
            }
        }

        writeExecutionLog(selection.runDir(), selection, status, outputs);
        evidenceRefs.add("logs/execution.log");
        writeBatch(selection.runDir(), selection, status, 1);
        evidenceRefs.add("batch/batch.yaml");
        List<Map<String, Object>> providerResults = List.of(
                providerResult(selection.soapProviderId(), SOAP_MOCK, selection.soapRuntimeMode(), status, outputs,
                        "load_soap_stub", cleanupStatus),
                providerResult(selection.restProviderId(), REST_CLIENT, selection.restRuntimeMode(), status, outputs,
                        "http_request", "not_applicable"));
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_ids", List.of(selection.soapProviderId(), selection.restProviderId()));
        testResult.put("provider_types", List.of(SOAP_MOCK, REST_CLIENT));
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
                failure,
                cleanupFailure);
    }

    private TestExecution blockedExecution(RuntimeSelection selection, String requestedProfile, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("PROVIDER_RUNTIME_BLOCKED", "PROVIDER_RUNTIME_ERROR", "Provider runtime blocked.", "Review provider runtime validation.")
                : failure;
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_ids", List.of(selection.soapProviderId(), selection.restProviderId()));
        testResult.put("provider_types", List.of(SOAP_MOCK, REST_CLIENT));
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
                List.of("provider-evidence/soap_mock/failure_detail.yaml"),
                resolvedFailure,
                null);
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
            String operation,
            ProviderOperationResult result,
            Map<String, Map<String, Object>> capturedOutputs,
            Map<String, Object> aggregateOutputs,
            List<Map<String, Object>> stepResults,
            List<String> evidenceRefs) {
        capturedOutputs.put(operationId, result.outputs());
        aggregateOutputs.putAll(result.outputs());
        stepResults.add(step(operationId, operation, result.passed() ? "passed" : "failed", result.outputs()));
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
            TargetSelection soapTarget = null;
            TargetSelection restTarget = null;
            for (Map.Entry<String, Object> targetEntry : mapValue(testCase.get("targets")).entrySet()) {
                String providerId = stringValue(mapValue(targetEntry.getValue()).get("provider_id"));
                Map<String, Object> providerInstance = instancesById.get(providerId);
                if (providerInstance == null) {
                    continue;
                }
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
                if (SOAP_MOCK.equals(providerType)) {
                    soapTarget = target;
                } else if (REST_CLIENT.equals(providerType)) {
                    restTarget = target;
                }
            }
            if (soapTarget != null && restTarget != null) {
                Path runDir = multiTestSuite
                        ? suiteRunDir.resolve("tests").resolve(safe(stringValue(testCase.get("test_case_id"))))
                        : suiteRunDir;
                selections.add(new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        requestedProfile,
                        soapTarget.targetName(),
                        soapTarget.providerId(),
                        soapTarget.runtimeMode(),
                        soapTarget.contract(),
                        soapTarget.instance(),
                        soapTarget.bindingValues(),
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
        return SuiteProfileGate.validate(suiteManifest, suite, testCases, requestedProfile, SOAP_MOCK + "," + REST_CLIENT);
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
        for (Object verifyValue : listValue(mapValue(selection.testCase().get("verify")).get("checks"))) {
            Map<String, Object> verify = mapValue(verifyValue);
            String id = stringValue(verify.get("id"));
            String type = stringValue(verify.get("type"));
            Object actual = resolveActual(capturedOutputs, stringValue(mapValue(verify.get("actual")).get("ref")));
            boolean checkPassed = "value_equals".equals(type) && valuesEqual(actual, verify.get("expected"));
            String ref = "assertions/" + id + ".yaml";
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

    private void writeAssertion(
            Path path,
            String id,
            String type,
            boolean passed,
            Object actual,
            Map<String, Object> verify) {
        String status = passed ? "passed" : "failed";
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
                provider_types: [soap_mock, rest_client]
                provider_ids: [%s, %s]
                status: %s
                response_status: %s
                matched_count: %s
                """.formatted(
                        selection.soapProviderId(),
                        selection.restProviderId(),
                        status,
                        outputs.getOrDefault("response.status", ""),
                        outputs.getOrDefault("matched_count", "")));
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
                provider_types: [soap_mock, rest_client]
                status: %s
                """.formatted(
                        selection.suite().get("suite_id"),
                        selection.batchId(),
                        selection.runId(),
                        topLevelTestCaseId(selection, testCount),
                        testCount,
                        status));
    }

    private void writeFailureDetail(Path runDir, RuntimeSelection selection, ProviderFailure failure) {
        ProviderFailure resolvedFailure = failure == null
                ? ProviderFailure.of("ASSERTION_FAILED", "ASSERTION_FAILED", "SOAP mock capability failed.", "Review SOAP mock, HTTP response, and assertion evidence.")
                : failure;
        write(runDir.resolve("provider-evidence/soap_mock/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_types: [soap_mock, rest_client]
                provider_ids: [%s, %s]
                profile: %s
                failure_code: %s
                classification: %s
                reason: %s
                owner_action: %s
                masking:
                  raw_secret_found: false
                """.formatted(
                selection.soapProviderId(),
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
        String status = evidenceFailed(content) ? "failed" : "passed";
        String failureCode = "failed".equals(status) ? firstNonBlank(failureCode(content), "ASSERTION_FAILED") : "";
        String providerType = "";
        String providerId = "";
        if (ref.contains("provider-evidence/soap/")) {
            providerType = SOAP_MOCK;
            providerId = selection.soapProviderId();
        } else if (ref.contains("provider-evidence/http/")) {
            providerType = REST_CLIENT;
            providerId = selection.restProviderId();
        } else if (ref.contains("provider-evidence/soap_mock/")) {
            providerType = SOAP_MOCK;
            providerId = selection.soapProviderId();
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
        if (normalized.contains("server_log")) {
            return "wiremock_server_log";
        }
        if (normalized.contains("cleanup")) {
            return "fixture_cleanup";
        }
        if (normalized.contains("injected_stubs")) {
            return "fixture_setup";
        }
        return "execution_log";
    }

    private String linkedResultField(String evidenceType) {
        return switch (evidenceType) {
            case "execution_log", "batch_summary" -> "evidence_refs";
            case "assertion_diff" -> "verify_results";
            case "fixture_cleanup" -> "provider_results.cleanup_status";
            case "fixture_setup" -> "steps";
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
            ProviderFailure cleanupFailure,
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
                cleanupFailure,
                true));
    }

    private Map<String, Object> providerResult(
            String providerId,
            String providerType,
            String runtimeMode,
            String status,
            Map<String, Object> outputs,
            String operation,
            String cleanupStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", providerId);
        result.put("provider_type", providerType);
        result.put("runtime_mode", runtimeMode);
        result.put("cleanup_status", cleanupStatus);
        result.put("resolved_operation_result", Map.of(
                "operation", operation,
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private String topLevelTestCaseId(RuntimeSelection selection, int testCount) {
        return ProviderCapabilityResultWriter.topLevelTestCaseId(
                selection.suite().get("suite_id"), selection.testCase().get("test_case_id"), testCount);
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

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-SOAP-MOCK-" + suffix, "RUN-SOAP-MOCK-" + suffix);
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read directory: " + directory, e);
        }
        return documents;
    }

    private void recreateDirectory(Path directory) {
        deleteRecursively(directory);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create run directory: " + directory, e);
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean run directory: " + path, e);
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
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
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
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (seen.add(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private boolean evidenceFailed(String content) {
        String lower = content.toLowerCase();
        return lower.contains("comparison_status: failed")
                || lower.contains("status: failed")
                || lower.contains("\"status\": \"failed\"")
                || !failureCode(content).isBlank();
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
        String normalized = ref.toLowerCase();
        if (normalized.endsWith(".json")) {
            return "application/json";
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return "application/x-yaml";
        }
        if (normalized.endsWith(".xml")) {
            return "text/xml";
        }
        if (normalized.endsWith(".log")) {
            return "text/plain";
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

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write SOAP mock output: " + path, e);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public record SoapRunResult(
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

        static SoapRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new SoapRunResult(
                    false, "blocked", suiteId, "", "", "", profile,
                    List.of(), List.of(), null, null, 0, false, List.copyOf(findings));
        }
    }

    private record RuntimeSelection(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            String soapTarget,
            String soapProviderId,
            String soapRuntimeMode,
            Map<String, Object> soapContract,
            Map<String, Object> soapInstance,
            Map<String, Object> soapBindingValues,
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
            ProviderFailure failure,
            ProviderFailure cleanupFailure) {
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

    private record RunIds(String batchId, String runId) {
    }
}
