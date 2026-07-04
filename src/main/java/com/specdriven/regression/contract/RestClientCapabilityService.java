package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.provider.http.RestClientProviderRuntime;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

public class RestClientCapabilityService {

    private static final String REST_CLIENT = "rest_client";
    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final AtomicLong RUN_SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter RUN_ID_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final Yaml yaml = new Yaml();

    public RestClientRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return RestClientRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (!validation.providerTypesUsed().equals(List.of(REST_CLIENT))) {
            return RestClientRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "REST suite mode supports provider_type `rest_client` only.")));
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return RestClientRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    REST_CLIENT,
                    "",
                    "",
                    "Provide --profile for rest_client suite run.")));
        }

        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<SuiteProfileGate.TestCaseDocument> testDocuments = new ArrayList<>();
        for (Object testRef : listValue(suite.get("tests"))) {
            Path testCasePath = suiteRoot.resolve(stringValue(testRef)).normalize();
            testDocuments.add(new SuiteProfileGate.TestCaseDocument(testCasePath, readMap(testCasePath)));
        }
        List<ContractFinding> profileFindings =
                SuiteProfileGate.validate(suiteManifest, suite, testDocuments, requestedProfile, REST_CLIENT);
        if (!profileFindings.isEmpty()) {
            return RestClientRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, profileFindings);
        }

        List<RuntimeSelection> selections = selectRuntimes(suiteManifest, requestedProfile, outputBase, testDocuments);
        if (selections.isEmpty()) {
            return RestClientRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "targets",
                    "missing_provider_targets",
                    "",
                    REST_CLIENT,
                    requestedProfile,
                    "",
                    "Declare at least one DSL target with provider_type `rest_client`.")));
        }

        RuntimeSelection first = selections.get(0);
        recreateDirectory(first.suiteRunDir());
        Instant startedAt = Instant.now();
        String status = "passed";
        ProviderFailure failure = null;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> testResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        boolean providerRuntimeExecuted = false;

        try (DemoRestServer demoServer = DemoRestServer.startIfNeeded(first.bindingValues())) {
            RestClientProviderRuntime runtime = new RestClientProviderRuntime();
            for (RuntimeSelection selection : selections) {
                Map<String, Object> bindingValues = new LinkedHashMap<>(selection.bindingValues());
                if (!demoServer.baseUrl().isBlank()) {
                    bindingValues.put("base_url", demoServer.baseUrl());
                }
                TestExecution execution = executeTestCase(runtime, selection, requestedProfile, bindingValues);
                providerRuntimeExecuted = providerRuntimeExecuted || execution.providerRuntimeExecuted();
                stepResults.addAll(execution.stepResults());
                verifyResults.addAll(execution.verifyResults());
                testResults.add(execution.testResult());
                providerResults.add(execution.providerResult());
                evidenceRefs.addAll(prefixEvidenceRefs(selection, execution.evidenceRefs()));
                if (!execution.passed() && "passed".equals(status)) {
                    status = "failed";
                    failure = execution.failure();
                }
            }
        }

        Instant finishedAt = Instant.now();
        writeExecutionLog(first.suiteRunDir(), first, status);
        evidenceRefs.add("logs/execution.log");
        writeBatch(first.suiteRunDir(), first, status, selections.size());
        evidenceRefs.add("batch/batch.yaml");
        if ("failed".equals(status)) {
            writeFailureDetail(first.suiteRunDir(), first, failure);
            evidenceRefs.add("provider-evidence/http/failure_detail.yaml");
        }
        String topLevelTestCaseId = topLevelTestCaseId(first, selections.size());
        writeEvidenceIndex(first.suiteRunDir(), first, topLevelTestCaseId, evidenceRefs);
        evidenceRefs.add(0, "evidence_index.yaml");
        Path resultJson = ProviderCapabilityResultWriter.write(
                first.suiteRunDir(),
                new ProviderCapabilityResultWriter.ResultDocument(
                        first.testCase().get("dsl_version"),
                        first.suite().get("suite_id"),
                        first.batchId(),
                        first.runId(),
                        topLevelTestCaseId,
                        selections.size(),
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
                        ProviderCapabilityResultWriter.singleProviderRootFields(providerResults),
                        failure,
                        null,
                        true));
        return new RestClientRunResult(
                "passed".equals(status),
                status,
                stringValue(first.suite().get("suite_id")),
                first.batchId(),
                first.runId(),
                topLevelTestCaseId,
                selections.size(),
                requestedProfile,
                providerRuntimeExecuted,
                first.providerId(),
                REST_CLIENT,
                resultJson,
                first.suiteRunDir(),
                List.of());
    }

    private TestExecution executeTestCase(
            RestClientProviderRuntime runtime,
            RuntimeSelection selection,
            String requestedProfile,
            Map<String, Object> bindingValues) {
        recreateDirectory(selection.runDir());
        ProviderExecutionContext context = new ProviderExecutionContext(
                selection.providerId(),
                REST_CLIENT,
                requestedProfile,
                selection.runtimeMode(),
                selection.suiteRoot(),
                selection.runDir(),
                selection.contract(),
                selection.instance(),
                bindingValues);
        SelectedOperation execute = operation(selection.testCase(), "execute", selection.target(), "http_request");
        ProviderOperationResult result = runtime.execute(context, execute.request());
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Map<String, Map<String, Object>> capturedOutputs = new LinkedHashMap<>();
        capturedOutputs.put(execute.id(), result.outputs());
        stepResults.add(step(execute.id(), result.passed() ? "passed" : "failed", result.outputs()));
        evidenceRefs.addAll(result.evidence().stream()
                .map(evidence -> evidence.ref())
                .filter(ref -> ref != null && !ref.isBlank())
                .toList());

        String status = result.passed() ? "passed" : "failed";
        ProviderFailure failure = result.failure();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        if (result.passed()) {
            VerificationOutcome verification = verify(selection, capturedOutputs);
            verifyResults.addAll(verification.verifyResults());
            evidenceRefs.addAll(verification.evidenceRefs());
            if (!verification.passed()) {
                status = "failed";
                failure = ProviderFailure.of(
                        "ASSERTION_FAILED",
                        "ASSERTION_FAILED",
                        "One or more REST verification checks failed.",
                        "Review assertion evidence and HTTP provider evidence.");
            }
        }

        Map<String, Object> providerResult = providerResult(selection, status, result.outputs());
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", selection.testCase().get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_ids", List.of(selection.providerId()));
        testResult.put("provider_types", List.of(REST_CLIENT));
        testResult.put("status", status);
        testResult.put("step_count", stepResults.size());
        testResult.put("verify_count", verifyResults.size());
        if (failure != null) {
            testResult.put("failure_code", failure.code());
            testResult.put("failure_classification", failure.classification());
        }
        return new TestExecution(
                "passed".equals(status),
                true,
                stepResults,
                verifyResults,
                testResult,
                providerResult,
                evidenceRefs,
                failure);
    }

    private List<RuntimeSelection> selectRuntimes(
            Path suiteManifest,
            String requestedProfile,
            Path outputBase,
            List<SuiteProfileGate.TestCaseDocument> testDocuments) {
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
        boolean multiTestSuite = testDocuments.size() > 1;
        List<RuntimeSelection> selections = new ArrayList<>();
        for (SuiteProfileGate.TestCaseDocument document : testDocuments) {
            for (Map.Entry<String, Object> targetEntry : mapValue(document.document().get("targets")).entrySet()) {
                String providerId = stringValue(mapValue(targetEntry.getValue()).get("provider_id"));
                Map<String, Object> instance = instancesById.getOrDefault(providerId, Map.of());
                if (!REST_CLIENT.equals(stringValue(instance.get("provider_type")))) {
                    continue;
                }
                Map<String, Object> binding = runtimeBindingResolver.providerBinding(suiteRoot, requestedProfile, providerId);
                Path runDir = multiTestSuite
                        ? suiteRunDir.resolve("tests").resolve(safe(stringValue(document.document().get("test_case_id"))))
                        : suiteRunDir;
                selections.add(new RuntimeSelection(
                        suiteRoot,
                        suite,
                        document.document(),
                        requestedProfile,
                        targetEntry.getKey(),
                        providerId,
                        stringValue(binding.get("runtime_mode")),
                        instance,
                        contractsByType.getOrDefault(REST_CLIENT, Map.of()),
                        mapValue(binding.get("binding_values")),
                        runIds.batchId(),
                        runIds.runId(),
                        suiteRunDir,
                        runDir));
            }
        }
        return List.copyOf(selections);
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
        return actualJson.equals(expectedJson);
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

    private void writeExecutionLog(Path runDir, RuntimeSelection selection, String status) {
        write(runDir.resolve("logs/execution.log"), """
                evidence_type: execution_log
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_type: rest_client
                provider_id: %s
                status: %s
                """.formatted(selection.providerId(), status));
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
                provider_type: rest_client
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
                ? ProviderFailure.of("ASSERTION_FAILED", "ASSERTION_FAILED", "REST suite failed.", "Review provider and assertion evidence.")
                : failure;
        write(runDir.resolve("provider-evidence/http/failure_detail.yaml"), """
                evidence_type: http_request_response
                provider_type: rest_client
                provider_id: %s
                status: failed
                failure_code: %s
                classification: %s
                reason: %s
                owner_action: %s
                masking_applied: true
                """.formatted(
                selection.providerId(),
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
        String providerType = ref.contains("provider-evidence/http/") ? REST_CLIENT : "";
        return new EvidenceEntry(
                evidenceType + "-" + safe(ref),
                evidenceType,
                providerType.isBlank() && !"assertion_diff".equals(evidenceType) ? "framework"
                        : ("assertion_diff".equals(evidenceType) ? "assertion_engine" : "provider"),
                providerType,
                providerType.isBlank() ? "" : selection.providerId(),
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
        return "http_request_response";
    }

    private String linkedResultField(String evidenceType) {
        return switch (evidenceType) {
            case "execution_log", "batch_summary" -> "evidence_refs";
            case "assertion_diff" -> "verify_results";
            default -> "provider_results.resolved_operation_result";
        };
    }

    private Map<String, Object> providerResult(RuntimeSelection selection, String status, Map<String, Object> outputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", selection.providerId());
        result.put("provider_type", REST_CLIENT);
        result.put("runtime_mode", selection.runtimeMode());
        result.put("status", status);
        result.put("resolved_operation_result", Map.of(
                "operation", "http_request",
                "status", status,
                "outputs", outputs));
        result.put("release_evidence_eligible", true);
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

    private RunIds newRunIds() {
        String suffix = RUN_ID_TIME_FORMAT.format(Instant.now()) + "-" + RUN_SEQUENCE.incrementAndGet();
        return new RunIds("BATCH-REST-" + suffix, "RUN-REST-" + suffix);
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
            throw new UncheckedIOException("Failed to read REST provider sample artifact: " + path, e);
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
                throw new UncheckedIOException("Failed to clean generated REST provider output: " + directory, e);
            }
        }
        write(directory.resolve(".keep"), "");
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write REST provider output: " + path, e);
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

    public record RestClientRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            boolean providerRuntimeExecuted,
            String providerId,
            String providerType,
            Path resultJson,
            Path evidenceDir,
            List<ContractFinding> findings) {

        static RestClientRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new RestClientRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
                    profile,
                    false,
                    "",
                    REST_CLIENT,
                    null,
                    null,
                    List.copyOf(findings));
        }
    }

    private record RuntimeSelection(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            String target,
            String providerId,
            String runtimeMode,
            Map<String, Object> instance,
            Map<String, Object> contract,
            Map<String, Object> bindingValues,
            String batchId,
            String runId,
            Path suiteRunDir,
            Path runDir) {

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
            boolean providerRuntimeExecuted,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            Map<String, Object> testResult,
            Map<String, Object> providerResult,
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

    private static final class DemoRestServer implements AutoCloseable {
        private final HttpServer server;
        private final String baseUrl;

        private DemoRestServer(HttpServer server, String baseUrl) {
            this.server = server;
            this.baseUrl = baseUrl;
        }

        static DemoRestServer startIfNeeded(Map<String, Object> bindingValues) {
            String baseUrl = stringValue(bindingValues.get("base_url"));
            if (!"generated://dummy-rest-app.base_url".equals(baseUrl)) {
                return new DemoRestServer(null, "");
            }
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/orders/1001", exchange -> {
                    byte[] body = """
                            {"order_id":"1001","status":"PAID","total":128.5}
                            """.strip().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
                server.start();
                int port = server.getAddress().getPort();
                return new DemoRestServer(server, "http://127.0.0.1:" + port);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to start dummy REST demo server.", e);
            }
        }

        String baseUrl() {
            return baseUrl;
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop(0);
            }
        }

        private static String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }
}
