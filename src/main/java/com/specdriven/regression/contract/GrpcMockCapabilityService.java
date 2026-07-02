package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.provider.grpc.GrpcClientProviderRuntime;
import com.specdriven.regression.provider.grpc.GrpcMockProviderRuntime;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.yaml.snakeyaml.Yaml;

public class GrpcMockCapabilityService {

    private static final String GRPC_MOCK = "grpc_mock";
    private static final String GRPC_CLIENT = "grpc_client";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public GrpcRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return GrpcRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!isSupportedProviderSet(validation.providerTypesUsed())) {
            return GrpcRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "gRPC mock sample mode supports provider_types `grpc_mock` and `grpc_client` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return GrpcRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    GRPC_MOCK + "," + GRPC_CLIENT,
                    "",
                    "",
                    "Provide --profile for gRPC mock sample run.")));
        }
        List<ContractFinding> profileFindings = requestedProfileFindings(suiteManifest, requestedProfile);
        if (!profileFindings.isEmpty()) {
            return GrpcRunResult.blocked(validation.suiteId(), requestedProfile, profileFindings);
        }

        RuntimeSelection selection = selectRuntime(suiteManifest, requestedProfile, outputBase);
        if (selection == null) {
            return GrpcRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_provider_targets",
                    "",
                    GRPC_MOCK + "," + GRPC_CLIENT,
                    requestedProfile,
                    "",
                    "Declare one grpc_mock target and one grpc_client target in the gRPC sample DSL.")));
        }
        recreateDirectory(selection.runDir());

        GrpcMockProviderRuntime mockRuntime = new GrpcMockProviderRuntime();
        GrpcClientProviderRuntime clientRuntime = new GrpcClientProviderRuntime();
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(new ProviderRuntimeRegistry(Map.of(
                GRPC_MOCK, mockRuntime,
                GRPC_CLIENT, clientRuntime)));

        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        ProviderFailure cleanupFailure = null;
        String cleanupStatus = "not_run";
        boolean providerRuntimeExecuted = false;
        Map<String, Map<String, Object>> capturedOutputs = new LinkedHashMap<>();
        Map<String, Object> aggregateOutputs = new LinkedHashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Map<String, Object> clientBindingValues = new LinkedHashMap<>(selection.clientBindingValues());
        ProviderExecutionContext mockContext = context(
                selection,
                selection.mockBindingValues(),
                selection.mockProviderId(),
                GRPC_MOCK,
                selection.mockRuntimeMode(),
                selection.mockContract(),
                selection.mockInstance());

        try {
            SelectedOperation setup = operation(selection.testCase(), "setup", selection.mockTarget(), "load_grpc_stub");
            ProviderRuntimeResolution setupResolution = resolver.resolve(mockContext, setup.request());
            if (!setupResolution.valid()) {
                return blocked(selection, requestedProfile, setupResolution.failure());
            }
            ProviderOperationResult setupResult = setupResolution.runtime().execute(mockContext, setup.request());
            providerRuntimeExecuted = true;
            capture(setup.id(), "load_grpc_stub", setupResult, capturedOutputs, aggregateOutputs, stepResults, evidenceRefs);
            if (!setupResult.passed()) {
                status = "failed";
                failure = setupResult.failure();
            } else {
                clientBindingValues.put("target", stringValue(setupResult.outputs().get("target_uri")));
                ProviderExecutionContext clientContext = context(
                        selection,
                        clientBindingValues,
                        selection.clientProviderId(),
                        GRPC_CLIENT,
                        selection.clientRuntimeMode(),
                        selection.clientContract(),
                        selection.clientInstance());
                SelectedOperation execute = operation(selection.testCase(), "execute", selection.clientTarget(), "unary_call");
                ProviderRuntimeResolution executeResolution = resolver.resolve(clientContext, execute.request());
                if (!executeResolution.valid()) {
                    return blocked(selection, requestedProfile, executeResolution.failure());
                }
                ProviderOperationResult executeResult = executeResolution.runtime().execute(clientContext, execute.request());
                capture(execute.id(), "unary_call", executeResult, capturedOutputs, aggregateOutputs, stepResults, evidenceRefs);
                if (!executeResult.passed()) {
                    status = "failed";
                    failure = executeResult.failure();
                } else {
                    SelectedOperation observe =
                            operation(selection.testCase(), "execute", selection.mockTarget(), "grpc_request_received");
                    ProviderRuntimeResolution observeResolution = resolver.resolve(mockContext, observe.request());
                    if (!observeResolution.valid()) {
                        return blocked(selection, requestedProfile, observeResolution.failure());
                    }
                    ProviderOperationResult observeResult =
                            observeResolution.runtime().execute(mockContext, observe.request());
                    capture(observe.id(), "grpc_request_received", observeResult,
                            capturedOutputs, aggregateOutputs, stepResults, evidenceRefs);
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
                                    "One or more gRPC mock verification checks failed.",
                                    "Review gRPC request journal, client response evidence, and assertion evidence.");
                        }
                    }
                }
            }
        } finally {
            SelectedOperation cleanup = operation(selection.testCase(), "cleanup", selection.mockTarget(), "reset_mock");
            ProviderOperationResult cleanupResult = mockRuntime.execute(mockContext, cleanup.request());
            cleanupStatus = cleanupResult.passed() ? "passed" : "failed";
            capture(cleanup.id(), "reset_mock", cleanupResult,
                    capturedOutputs, aggregateOutputs, stepResults, evidenceRefs);
            if (!cleanupResult.passed()) {
                cleanupFailure = cleanupResult.failure();
                if ("passed".equals(status)) {
                    status = "failed";
                    failure = cleanupResult.failure();
                }
            }
        }

        Instant finishedAt = Instant.now();
        writeExecutionLog(selection, status, aggregateOutputs);
        evidenceRefs.add("logs/execution.log");
        writeBatch(selection, status);
        evidenceRefs.add("batch/batch.yaml");
        writeEvidenceIndex(selection, evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = writeResult(
                selection,
                requestedProfile,
                status,
                aggregateOutputs,
                stepResults,
                verifyResults,
                evidenceRefs,
                failure,
                cleanupFailure,
                cleanupStatus,
                startedAt,
                finishedAt);
        return new GrpcRunResult(
                "passed".equals(status),
                status,
                stringValue(selection.suite().get("suite_id")),
                selection.batchId(),
                selection.runId(),
                stringValue(selection.testCase().get("test_case_id")),
                requestedProfile,
                List.of(selection.mockProviderId(), selection.clientProviderId()),
                List.of(GRPC_MOCK, GRPC_CLIENT),
                resultJson,
                selection.runDir(),
                providerRuntimeExecuted,
                List.of());
    }

    private boolean isSupportedProviderSet(List<String> providerTypes) {
        Set<String> types = new LinkedHashSet<>(providerTypes);
        return types.size() == 2 && types.contains(GRPC_MOCK) && types.contains(GRPC_CLIENT);
    }

    private GrpcRunResult blocked(RuntimeSelection selection, String profile, ProviderFailure failure) {
        return GrpcRunResult.blocked(
                stringValue(selection.suite().get("suite_id")),
                profile,
                List.of(new ContractFinding(
                        selection.suiteRoot().resolve("suite_manifest.yaml").toString(),
                        "provider_runtime",
                        failure.code().toLowerCase(),
                        "",
                        GRPC_MOCK + "," + GRPC_CLIENT,
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

    private RuntimeSelection selectRuntime(Path suiteManifest, String requestedProfile, Path outputBase) {
        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        Map<String, Map<String, Object>> instancesById =
                readDirectoryByField(suiteRoot.resolve("provider_instances"), "provider_id");
        Map<String, Map<String, Object>> contractsByType =
                readDirectoryByField(frameworkProviderContractsDirectory(suiteRoot), "provider_type");
        for (Object testRef : listValue(suite.get("tests"))) {
            Map<String, Object> testCase = readMap(suiteRoot.resolve(stringValue(testRef)).normalize());
            TargetSelection mockTarget = null;
            TargetSelection clientTarget = null;
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
                if (GRPC_MOCK.equals(providerType)) {
                    mockTarget = target;
                } else if (GRPC_CLIENT.equals(providerType)) {
                    clientTarget = target;
                }
            }
            if (mockTarget != null && clientTarget != null) {
                RunIds runIds = newRunIds();
                Path runDir = outputBase
                        .resolve(safe(stringValue(suite.get("suite_id"))))
                        .resolve(runIds.batchId())
                        .resolve(runIds.runId());
                return new RuntimeSelection(
                        suiteRoot,
                        suite,
                        testCase,
                        requestedProfile,
                        mockTarget.targetName(),
                        mockTarget.providerId(),
                        mockTarget.runtimeMode(),
                        mockTarget.contract(),
                        mockTarget.instance(),
                        mockTarget.bindingValues(),
                        clientTarget.targetName(),
                        clientTarget.providerId(),
                        clientTarget.runtimeMode(),
                        clientTarget.contract(),
                        clientTarget.instance(),
                        clientTarget.bindingValues(),
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
        return SuiteProfileGate.validate(suiteManifest, suite, testCases, requestedProfile, GRPC_MOCK + "," + GRPC_CLIENT);
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

    private void writeExecutionLog(RuntimeSelection selection, String status, Map<String, Object> outputs) {
        write(selection.runDir().resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_types: [grpc_mock, grpc_client]
                provider_ids: [%s, %s]
                status: %s
                response_status: %s
                matched_count: %s
                """.formatted(
                        selection.mockProviderId(),
                        selection.clientProviderId(),
                        status,
                        outputs.getOrDefault("response.status", ""),
                        outputs.getOrDefault("matched_count", "")));
    }

    private void writeBatch(RuntimeSelection selection, String status) {
        write(selection.runDir().resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                provider_types: [grpc_mock, grpc_client]
                status: %s
                """.formatted(
                        selection.suite().get("suite_id"),
                        selection.batchId(),
                        selection.runId(),
                        selection.testCase().get("test_case_id"),
                        status));
    }

    private void writeEvidenceIndex(RuntimeSelection selection, List<String> evidenceRefs) {
        StringBuilder index = new StringBuilder();
        index.append("evidence_index_version: v0.2\n");
        index.append("suite_id: ").append(selection.suite().get("suite_id")).append('\n');
        index.append("batch_id: ").append(selection.batchId()).append('\n');
        index.append("run_id: ").append(selection.runId()).append('\n');
        index.append("test_case_id: ").append(selection.testCase().get("test_case_id")).append('\n');
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
            index.append("    test_case_id: ").append(selection.testCase().get("test_case_id")).append('\n');
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
        write(selection.runDir().resolve("evidence_index.yaml"), index.toString());
    }

    private EvidenceEntry evidenceEntry(RuntimeSelection selection, String ref) {
        String evidenceType = evidenceType(ref);
        String content = read(selection.runDir().resolve(ref));
        String status = evidenceFailed(content) ? "failed" : "passed";
        String failureCode = "failed".equals(status) ? firstNonBlank(failureCode(content), "ASSERTION_FAILED") : "";
        String providerType = "";
        String providerId = "";
        if (ref.startsWith("provider-evidence/grpc/")) {
            providerType = GRPC_MOCK;
            providerId = selection.mockProviderId();
        } else if (ref.startsWith("provider-evidence/grpc-client/")) {
            providerType = GRPC_CLIENT;
            providerId = selection.clientProviderId();
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
        if (normalized.contains("provider-evidence/grpc-client/")) {
            return "grpc_request_response";
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
            RuntimeSelection selection,
            String profile,
            String status,
            Map<String, Object> outputs,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<String> evidenceRefs,
            ProviderFailure failure,
            ProviderFailure cleanupFailure,
            String cleanupStatus,
            Instant startedAt,
            Instant finishedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework_version", "v0.2");
        result.put("dsl_version", selection.testCase().get("dsl_version"));
        result.put("suite_id", selection.suite().get("suite_id"));
        result.put("batch_id", selection.batchId());
        result.put("run_id", selection.runId());
        result.put("test_case_id", selection.testCase().get("test_case_id"));
        result.put("profile", profile);
        result.put("environment", profile);
        result.put("status", status);
        result.put("start_time", startedAt.toString());
        result.put("end_time", finishedAt.toString());
        result.put("duration_ms", Duration.between(startedAt, finishedAt).toMillis());
        result.put("timestamps", Map.of("started_at", startedAt.toString(), "finished_at", finishedAt.toString()));
        result.put("labels", selection.testCase().get("labels"));
        result.put("source_refs", selection.testCase().get("source_refs"));
        result.put("step_results", stepResults);
        result.put("steps", stepResults);
        result.put("verify_results", verifyResults);
        result.put("provider_results", List.of(
                providerResult(selection.mockProviderId(), GRPC_MOCK, selection.mockRuntimeMode(), status, outputs,
                        "load_grpc_stub", cleanupStatus),
                providerResult(selection.clientProviderId(), GRPC_CLIENT, selection.clientRuntimeMode(), status, outputs,
                        "unary_call", "not_applicable")));
        result.put("evidence_index_ref", "evidence_index.yaml");
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
        Path resultJson = selection.runDir().resolve("result.json");
        write(resultJson, toJson(result) + "\n");
        return resultJson;
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
        return new RunIds("BATCH-GRPC-MOCK-" + suffix, "RUN-GRPC-MOCK-" + suffix);
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
            throw new UncheckedIOException("Failed to write gRPC mock output: " + path, e);
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
        if (value instanceof Iterable<?> iterable) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
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

    public record GrpcRunResult(
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
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static GrpcRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new GrpcRunResult(
                    false, "blocked", suiteId, "", "", "", profile,
                    List.of(), List.of(), null, null, false, List.copyOf(findings));
        }
    }

    private record RuntimeSelection(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            String mockTarget,
            String mockProviderId,
            String mockRuntimeMode,
            Map<String, Object> mockContract,
            Map<String, Object> mockInstance,
            Map<String, Object> mockBindingValues,
            String clientTarget,
            String clientProviderId,
            String clientRuntimeMode,
            Map<String, Object> clientContract,
            Map<String, Object> clientInstance,
            Map<String, Object> clientBindingValues,
            String batchId,
            String runId,
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
