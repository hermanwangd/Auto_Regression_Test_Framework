package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.ContractBaselineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.contract.v03.adapter.HttpMockV03Adapter;
import com.specdriven.regression.contract.v03.adapter.GrpcClientV03Adapter;
import com.specdriven.regression.contract.v03.adapter.GrpcMockV03Adapter;
import com.specdriven.regression.contract.v03.adapter.IbmMqV03Adapter;
import com.specdriven.regression.contract.v03.adapter.JdbcV03Adapter;
import com.specdriven.regression.contract.v03.adapter.KafkaV03Adapter;
import com.specdriven.regression.contract.v03.adapter.NatsV03Adapter;
import com.specdriven.regression.contract.v03.adapter.PollingObserverV03Adapter;
import com.specdriven.regression.contract.v03.adapter.RestClientV03Adapter;
import com.specdriven.regression.contract.v03.adapter.SoapMockV03Adapter;
import com.specdriven.regression.contract.v03.adapter.SampleFakeProviderV03Adapter;
import com.specdriven.regression.contract.v03.assertion.V03AssertionEvaluation;
import com.specdriven.regression.contract.v03.assertion.V03AssertionEvaluator;
import com.specdriven.regression.contract.v03.assertion.V03AssertionKind;
import com.specdriven.regression.contract.v03.assertion.V03MissingValue;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.specdriven.regression.summary.SuiteExecutionContext;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

public class V03RuntimeExecutionService {

    private static final String FRAMEWORK_VERSION = "0.3.0";

    private final ContractBaselineService contractBaselineService;
    private final V03ProviderRuntimeRegistry runtimeRegistry;
    private final Yaml yaml = new Yaml();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final V03ReferenceParser referenceParser = new V03ReferenceParser();
    private final V03ReferenceResolver referenceResolver = new V03ReferenceResolver(this::readYaml);
    private final V03AssertionEvaluator assertionEvaluator = new V03AssertionEvaluator();
    private final V03OutputRedactor outputRedactor = new V03OutputRedactor();

    public V03RuntimeExecutionService() {
        this(new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(
                new HttpMockV03Adapter(),
                new GrpcClientV03Adapter(),
                new GrpcMockV03Adapter(),
                new IbmMqV03Adapter(),
                new JdbcV03Adapter(),
                new KafkaV03Adapter(),
                new NatsV03Adapter(),
                new PollingObserverV03Adapter(),
                new RestClientV03Adapter(),
                new SoapMockV03Adapter(),
                new SampleFakeProviderV03Adapter())));
    }

    V03RuntimeExecutionService(
            ContractBaselineService contractBaselineService,
            V03ProviderRuntimeRegistry runtimeRegistry) {
        this.contractBaselineService = contractBaselineService;
        this.runtimeRegistry = runtimeRegistry;
    }

    public V03RuntimeRunResult run(Path suiteManifest, String profile, Path outputBase) {
        return run(suiteManifest, profile, SuiteExecutionContext.standalone(profile, outputBase, "V03"));
    }

    public V03RuntimeRunResult run(Path suiteManifest, String profile, SuiteExecutionContext executionContext) {
        requireMatchingProfile(profile, executionContext);
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        return run(suiteManifest, profile, executionContext, validation);
    }

    /** Executes a command-scoped validated v0.3 suite without validating it again. */
    public V03RuntimeRunResult run(
            Path suiteManifest,
            String profile,
            SuiteExecutionContext executionContext,
            ValidationResult validation) {
        requireMatchingProfile(profile, executionContext);
        if (!validation.valid()) {
            return V03RuntimeRunResult.blocked(validation.suiteId(), profile, validation.findings());
        }
        V03ExecutionPlan plan;
        try {
            plan = new V03ExecutionPlanBuilder(contractBaselineService)
                    .build(suiteManifest, profile, validation);
        } catch (IllegalArgumentException | IllegalStateException error) {
            return V03RuntimeRunResult.blocked(validation.suiteId(), profile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "execution_plan",
                    "v03_plan_compilation_failed",
                    "",
                    "",
                    profile,
                    "",
                    "Correct the v0.3 target bindings or Provider Contract metadata: " + error.getMessage())));
        }
        List<ContractFinding> supportFindings = unsupportedRuntimeFindings(suiteManifest, plan);
        if (!supportFindings.isEmpty()) {
            return V03RuntimeRunResult.blocked(plan.suiteId(), profile, supportFindings);
        }

        Path suiteRoot = plan.suiteRoot();
        List<V03CompiledTestCase> testCases = plan.tests();
        String evidenceClassification = plan.environmentProfile().evidenceClassification();
        boolean downstreamReleaseEvidence = plan.environmentProfile().downstreamReleaseEvidence()
                || downstreamReleaseEvidence(evidenceClassification);
        V03CompiledTestCase firstTestCase = testCases.isEmpty() ? null : testCases.get(0);
        String testCaseId = suiteLevelTestCaseId(plan.suiteId(), testCases);
        String batchId = executionContext.parentBatchId();
        String runId = executionContext.newRunId("V03");
        Path runDir = executionContext.childRunRoot(plan.suiteId(), runId);
        recreateDirectory(runDir);

        Instant startedAt = Instant.now();
        boolean providerRuntimeExecuted = false;
        Map<String, V03ResolvedTarget> targetsByName = targetsByName(plan);
        Map<V03StepOutputKey, V03ProducedOutput> stepOutputs = new LinkedHashMap<>();
        Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs = new LinkedHashMap<>();
        Map<String, String> testStatuses = initialTestStatuses(testCases);
        V03ExecutionContext context = new V03ExecutionContext(
                suiteRoot,
                runDir,
                plan.profile(),
                targetsByName,
                plan.artifactRoots(),
                stepOutputs,
                generatedOutputs,
                plan.bindingDependencies(),
                Map.of(),
                System.getenv(),
                referenceResolver,
                startedAt);
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<Map<String, Object>> cleanupFailures = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        Failure failure = Failure.none();

        for (V03ExecutionStep step : plan.steps()) {
            if (!"cleanup".equals(step.phase()) && testTerminal(testStatuses, step.testCaseId())) {
                continue;
            }
            if (step.kind() == V03ExecutionStepKind.ASSERTION) {
                AssertionResult assertion = evaluateAssertion(plan, step, context, runDir);
                verifyResults.add(assertion.toMap());
                evidenceRefs.add(assertion.evidenceRef());
                if (!assertion.passed()) {
                    markTestFailed(testStatuses, step.testCaseId());
                    Failure assertionFailure = new Failure("ASSERTION_FAILED", "VERIFICATION_FAILED",
                            "Assertion `" + step.id() + "` failed.", "Review assertion evidence.");
                    if (failure.isNone()) {
                        failure = assertionFailure;
                    }
                }
                continue;
            }
            V03ProviderRuntimeAdapter adapter = runtimeRegistry.resolve(step.providerType());
            V03StepResult result = adapter.execute(step, context);
            validateRuntimeOutputs(plan, step, result);
            providerRuntimeExecuted = true;
            recordProducedOutputs(plan, step, result.outputs(), stepOutputs, generatedOutputs);
            stepResults.add(stepResult(plan, step, result));
            providerResults.add(providerResult(plan, step, result));
            evidenceRefs.addAll(result.evidenceRefs());
            if (!result.passed() && "cleanup".equals(step.phase())) {
                applyCleanupOutcomeStatus(testStatuses, step.testCaseId(), result.status());
                cleanupFailures.add(cleanupFailure(step, result));
                if (failure.isNone()) {
                    failure = new Failure(
                            firstNonBlank(result.failureCode(), "CLEANUP_FAILED"),
                            "CLEANUP_ERROR",
                            outputRedactor.redactMessage(firstNonBlank(result.message(), "Cleanup operation failed.")),
                            "Review cleanup evidence for step `" + step.id() + "`.");
                }
            } else if (!result.passed()) {
                applyOutcomeStatus(testStatuses, step.testCaseId(), result.status());
                if (failure.isNone()) {
                    failure = new Failure(
                        firstNonBlank(result.failureCode(), "PROVIDER_RUNTIME_ERROR"),
                        "PROVIDER_RUNTIME_ERROR",
                        outputRedactor.redactMessage(firstNonBlank(result.message(), "Provider operation failed.")),
                        "Review provider evidence for step `" + step.id() + "`.");
                }
            }
        }

        String status = suiteStatus(testStatuses);
        writeExecutionLog(runDir, plan, status);
        evidenceRefs.add("logs/execution.log");
        writeBatch(runDir, plan, testCaseId, status);
        evidenceRefs.add("batch/batch.yaml");
        sanitizeEvidence(runDir, evidenceRefs, plan.providerContracts().values().stream()
                .flatMap(contract -> contract.evidenceRedact().stream()).collect(java.util.stream.Collectors.toSet()));
        rejectUnindexedEvidence(runDir, distinct(evidenceRefs));
        Instant finishedAt = Instant.now();
        Path resultJson = writeResult(
                runDir,
                firstTestCase,
                plan,
                batchId,
                runId,
                testCaseId,
                status,
                startedAt,
                finishedAt,
                stepResults,
                verifyResults,
                providerResults,
                distinct(evidenceRefs),
                testCases,
                testStatuses,
                cleanupFailures,
                failure,
                evidenceClassification,
                downstreamReleaseEvidence);
        return new V03RuntimeRunResult(
                "passed".equals(status),
                status,
                plan.suiteId(),
                batchId,
                runId,
                testCaseId,
                testCases.size(),
                plan.profile(),
                providerRuntimeExecuted,
                plan.targets().values().stream().map(V03ResolvedTarget::target).toList(),
                plan.targets().values().stream().map(V03ResolvedTarget::providerType).distinct().toList(),
                resultJson,
                runDir,
                evidenceClassification,
                List.of());
    }

    private void requireMatchingProfile(String profile, SuiteExecutionContext context) {
        if (!context.matchesRequestedProfile(profile)) {
            throw new IllegalArgumentException("Execution context profile `" + context.profile()
                    + "` does not match requested profile `" + profile + "`.");
        }
    }

    private List<ContractFinding> unsupportedRuntimeFindings(Path suiteManifest, V03ExecutionPlan plan) {
        List<ContractFinding> findings = new ArrayList<>();
        for (V03ExecutionStep step : plan.steps()) {
            if (step.kind() != V03ExecutionStepKind.PROVIDER_OPERATION && step.kind() != V03ExecutionStepKind.PROVIDER_CHECK) {
                continue;
            }
            try {
                V03ProviderRuntimeAdapter adapter = runtimeRegistry.resolve(step.providerType());
                if (!adapter.supports(step.providerContract(), step.operation())) {
                    findings.add(new ContractFinding(
                            suiteManifest.toString(),
                            step.phase() + "." + step.id() + ".op",
                            "unsupported_suite_runtime",
                            "",
                            step.providerType(),
                            step.profile(),
                            step.operation(),
                            "Add a v0.3 runtime adapter operation mapping for Provider Contract `"
                                    + step.providerContract() + "`."));
                }
            } catch (IllegalArgumentException e) {
                findings.add(new ContractFinding(
                        suiteManifest.toString(),
                        step.phase() + "." + step.id() + ".target",
                        "unsupported_suite_runtime",
                        "",
                        step.providerType(),
                        step.profile(),
                        step.operation(),
                        e.getMessage()));
            }
        }
        return List.copyOf(findings);
    }

    private void validateRuntimeOutputs(V03ExecutionPlan plan, V03ExecutionStep step, V03StepResult result) {
        V03ProviderContract contract = plan.providerContracts().get(step.providerContract());
        V03ProviderContract.V03OperationDefinition operation = contract == null
                ? null : contract.operations().get(step.operation());
        if (operation == null) {
            throw new IllegalArgumentException("missing_provider_contract_operation: step `" + step.id() + "`.");
        }
        for (String output : result.outputs().keySet()) {
            if (!operation.outputDefinitions().containsKey(output)) {
                throw new IllegalArgumentException("undeclared_provider_output: step `" + step.id()
                        + "` returned `" + output + "` outside Provider Contract `" + contract.id() + "`.");
            }
        }
        if (!result.failureCode().isBlank() && !contract.failureCodes().contains(result.failureCode())) {
            throw new IllegalArgumentException("undeclared_provider_failure_code: step `" + step.id()
                    + "` returned `" + result.failureCode() + "` outside Provider Contract `" + contract.id() + "`.");
        }
    }

    private AssertionResult evaluateAssertion(
            V03ExecutionPlan plan,
            V03ExecutionStep step,
            V03ExecutionContext context,
            Path runDir) {
        V03AssertionKind kind = V03AssertionKind.require(step.operation());
        Object actual = V03MissingValue.INSTANCE;
        Object expected = null;
        boolean passed = false;
        String evaluationError = "";
        try {
            switch (kind) {
                case JSON_MATCH -> {
                    actual = resolveAssertionOperand(step, "actual", "actual_ref", context, false);
                    expected = resolveRequiredReference(step.inputs().get("expected_ref"), step, context);
                    Object normalizedExpected = comparableJson(expected, step);
                    Object normalizedActual = comparableJson(actual, step);
                    passed = normalizedActual.equals(normalizedExpected);
                }
                case SCHEMA_MATCH -> {
                    actual = resolveAssertionOperand(step, "actual", "actual_ref", context, false);
                    expected = resolveRequiredReference(step.inputs().get("schema_ref"), step, context);
                    passed = schemaMatches(mapValue(expected), actual);
                }
                case FILE_DIFF -> {
                    Path expectedPath = requiredArtifactPath(step.inputs().get("expected_ref"), step, context);
                    Path actualPath = requiredArtifactPath(step.inputs().get("actual_ref"), step, context);
                    expected = readText(expectedPath);
                    actual = readText(actualPath);
                    passed = comparableText(stringValue(actual), step)
                            .equals(comparableText(stringValue(expected), step));
                }
                default -> {
                    boolean allowMissing = kind == V03AssertionKind.EXISTS || kind == V03AssertionKind.NOT_EXISTS;
                    actual = resolveAssertionOperand(step, "actual", "actual_ref", context, allowMissing);
                    if (kind != V03AssertionKind.EXISTS && kind != V03AssertionKind.NOT_EXISTS) {
                        expected = resolveAssertionOperand(step, "expected", "expected_ref", context, false);
                    }
                    V03AssertionEvaluation evaluation = assertionEvaluator.evaluate(kind, actual, expected);
                    passed = evaluation.passed();
                }
            }
        } catch (IllegalArgumentException | UncheckedIOException error) {
            passed = false;
            evaluationError = error.getMessage();
        }

        String operator = kind.dslValue();
        String evidenceRef = "assertions/"
                + safe(step.testCaseId())
                + "/"
                + safe(step.id())
                + (operator.equals("json_match") ? ".json" : ".yaml");
        if (operator.equals("json_match")) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("test_case_id", step.testCaseId());
            evidence.put("assertion_id", step.id());
            evidence.put("type", operator);
            evidence.put("status", passed ? "passed" : "failed");
            if (!passed) {
                evidence.put("failure_code", "ASSERTION_FAILED");
            }
            if (!evaluationError.isBlank()) {
                evidence.put("evaluation_error", outputRedactor.redactMessage(evaluationError));
            }
            evidence.put("actual", redactAssertionValue(plan, step, "actual", "actual_ref", actual, context));
            evidence.put("expected", redactAssertionValue(plan, step, "expected", "expected_ref", expected, context));
            write(runDir.resolve(evidenceRef), toJson(evidence) + "\n");
        } else {
            StringBuilder evidence = new StringBuilder();
            evidence.append("test_case_id: ").append(step.testCaseId()).append('\n');
            evidence.append("assertion_id: ").append(step.id()).append('\n');
            evidence.append("type: ").append(operator).append('\n');
            evidence.append("status: ").append(passed ? "passed" : "failed").append('\n');
            if (!passed) {
                evidence.append("failure_code: ASSERTION_FAILED\n");
            }
            if (!evaluationError.isBlank()) {
                evidence.append("evaluation_error: ").append(outputRedactor.redactMessage(evaluationError)).append('\n');
            }
            evidence.append("actual: ").append(stringValue(redactAssertionValue(plan, step, "actual", "actual_ref", actual, context))).append('\n');
            evidence.append("expected: ").append(stringValue(redactAssertionValue(plan, step, "expected", "expected_ref", expected, context))).append('\n');
            write(runDir.resolve(evidenceRef), evidence.toString());
        }
        return new AssertionResult(
                step.testCaseId(),
                step.id(),
                operator,
                passed ? "passed" : "failed",
                evidenceRef,
                passed ? "" : "ASSERTION_FAILED");
    }

    private Object resolveAssertionOperand(
            V03ExecutionStep step,
            String literalKey,
            String referenceKey,
            V03ExecutionContext context,
            boolean allowMissing) {
        Object raw = step.inputs().containsKey(referenceKey)
                ? step.inputs().get(referenceKey)
                : step.inputs().get(literalKey);
        try {
            return context.referenceResolver().resolveValue(
                    referenceParser.parse(raw),
                    context.referenceContext(step.testCaseId()));
        } catch (IllegalArgumentException error) {
            if (allowMissing && (error.getMessage().startsWith("unresolved_step_ref")
                    || error.getMessage().startsWith("unresolved_generated_ref")
                    || error.getMessage().startsWith("json_pointer_missing"))) {
                return V03MissingValue.INSTANCE;
            }
            throw error;
        }
    }

    private Object resolveRequiredReference(
            Object raw,
            V03ExecutionStep step,
            V03ExecutionContext context) {
        V03Reference reference = referenceParser.parse(raw);
        if (reference instanceof V03Reference.Literal) {
            throw new IllegalArgumentException("reference_required: `" + raw + "`.");
        }
        return context.referenceResolver().resolveValue(reference, context.referenceContext(step.testCaseId()));
    }

    private Path requiredArtifactPath(
            Object raw,
            V03ExecutionStep step,
            V03ExecutionContext context) {
        V03Reference reference = referenceParser.parse(raw);
        if (!(reference instanceof V03Reference.Artifact artifact)) {
            throw new IllegalArgumentException("artifact_ref_required: `" + raw + "`.");
        }
        if (!artifact.jsonPointer().isBlank()) {
            throw new IllegalArgumentException("file_diff_json_pointer_not_allowed: `" + raw + "`.");
        }
        return context.referenceResolver().artifactPath(artifact, context.referenceContext(step.testCaseId()));
    }

    private Object evidenceValue(Object value) {
        return value == V03MissingValue.INSTANCE ? "<missing>" : value;
    }

    private Object redactAssertionValue(
            V03ExecutionPlan plan,
            V03ExecutionStep step,
            String literalKey,
            String referenceKey,
            Object value,
            V03ExecutionContext context) {
        Object raw = step.inputs().containsKey(referenceKey)
                ? step.inputs().get(referenceKey)
                : step.inputs().get(literalKey);
        return outputRedactor.redactAssertionValue(plan, step, raw, evidenceValue(value), context.stepOutputs());
    }

    private Object comparableJson(Object value, V03ExecutionStep step) {
        Object normalized = removeIgnoredPaths(normalizeJson(value), stringList(step.inputs().get("ignore_paths")));
        if (boolValue(step.inputs().get("ignore_order"))) {
            return canonicalIgnoringOrder(normalized);
        }
        return normalized;
    }

    private Object normalizeJson(Object value) {
        if (value instanceof String text) {
            return readYamlText(text);
        }
        return value;
    }

    private Object removeIgnoredPaths(Object value, List<String> ignorePaths) {
        Object copy = deepCopy(value);
        for (String ignorePath : ignorePaths) {
            removePath(copy, ignorePath);
        }
        return copy;
    }

    private void removePath(Object value, String path) {
        if (!(value instanceof Map<?, ?> rawMap) || path == null || path.isBlank()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) rawMap;
        String[] parts = path.split("\\.");
        Object current = map;
        for (int index = 0; index < parts.length - 1; index++) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return;
            }
            current = currentMap.get(parts[index]);
        }
        if (current instanceof Map<?, ?> currentMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> target = (Map<String, Object>) currentMap;
            target.remove(parts[parts.length - 1]);
        }
    }

    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::deepCopy).toList();
        }
        return value;
    }

    private Object canonicalIgnoringOrder(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> canonical = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                    .forEach(entry -> canonical.put(String.valueOf(entry.getKey()), canonicalIgnoringOrder(entry.getValue())));
            return canonical;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::canonicalIgnoringOrder)
                    .sorted((left, right) -> stringValue(left).compareTo(stringValue(right)))
                    .toList();
        }
        return value;
    }

    private boolean schemaMatches(Map<String, Object> schema, Object actual) {
        String type = stringValue(schema.get("type"));
        if (!type.isBlank() && !typeMatches(type, actual)) {
            return false;
        }
        if (actual instanceof Map<?, ?> actualMap) {
            for (String required : stringList(schema.get("required"))) {
                if (!actualMap.containsKey(required)) {
                    return false;
                }
            }
            Map<String, Object> properties = mapValue(schema.get("properties"));
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!actualMap.containsKey(entry.getKey())) {
                    continue;
                }
                String propertyType = stringValue(mapValue(entry.getValue()).get("type"));
                if (!propertyType.isBlank() && !typeMatches(propertyType, actualMap.get(entry.getKey()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Collection<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && number.doubleValue() == number.longValue();
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }

    private String comparableText(String value, V03ExecutionStep step) {
        String normalize = stringValue(step.inputs().get("normalize"));
        String text = value.replace("\r\n", "\n");
        if ("trim_lines".equals(normalize)) {
            return text.lines().map(String::trim).reduce((left, right) -> left + "\n" + right).orElse("");
        }
        return text;
    }

    private String scopedStepKey(V03ExecutionStep step) {
        return scopedStepKey(step.testCaseId(), step.id());
    }

    private String scopedStepKey(String testCaseId, String stepId) {
        return testCaseId + "\n" + stepId;
    }

    private Map<String, V03ResolvedTarget> targetsByName(V03ExecutionPlan plan) {
        Map<String, V03ResolvedTarget> targets = new LinkedHashMap<>();
        for (V03ResolvedTarget target : plan.targets().values()) {
            targets.put(target.target(), target);
        }
        return targets;
    }

    private Map<String, Object> stepResult(V03ExecutionPlan plan, V03ExecutionStep step, V03StepResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("test_case_id", step.testCaseId());
        map.put("id", step.id());
        map.put("phase", step.phase());
        map.put("target", step.target());
        map.put("operation", step.operation());
        map.put("status", result.status());
        map.put("outputs", outputRedactor.redact(plan, step, result.outputs()));
        if (!result.failureCode().isBlank()) {
            map.put("failure_code", result.failureCode());
        }
        return map;
    }

    private Map<String, Object> providerResult(V03ExecutionPlan plan, V03ExecutionStep step, V03StepResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("test_case_id", step.testCaseId());
        map.put("target", step.target());
        map.put("provider_contract", step.providerContract());
        map.put("provider_type", step.providerType());
        map.put("profile", step.profile());
        map.put("runtime_mode", step.runtimeMode());
        map.put("operation", step.operation());
        map.put("status", result.status());
        map.put("evidence_refs", result.evidenceRefs());
        if (!result.failureCode().isBlank()) {
            map.put("failure_code", result.failureCode());
        }
        if (!result.message().isBlank()) {
            map.put("failure_reason", outputRedactor.redactMessage(result.message()));
        }
        if ("cleanup".equals(step.phase())) {
            map.put("cleanup_status", result.status());
        }
        map.put("resolved_operation_result", Map.of(
                "test_case_id", step.testCaseId(),
                "operation", step.operation(),
                "status", result.status(),
                "outputs", outputRedactor.redact(plan, step, result.outputs())));
        map.put("release_evidence_eligible", false);
        return map;
    }

    private Map<String, Object> cleanupFailure(V03ExecutionStep step, V03StepResult result) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("test_case_id", step.testCaseId());
        failure.put("step_id", step.id());
        failure.put("target", step.target());
        failure.put("provider_contract", step.providerContract());
        failure.put("provider_type", step.providerType());
        failure.put("operation", step.operation());
        failure.put("failure_code", firstNonBlank(result.failureCode(), "CLEANUP_FAILED"));
        failure.put("reason", outputRedactor.redactMessage(firstNonBlank(result.message(), "Cleanup operation failed.")));
        failure.put("evidence_refs", result.evidenceRefs());
        return failure;
    }

    private Path writeResult(
            Path runDir,
            V03CompiledTestCase testCase,
            V03ExecutionPlan plan,
            String batchId,
            String runId,
            String testCaseId,
            String status,
            Instant startedAt,
            Instant finishedAt,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs,
            List<V03CompiledTestCase> testCases,
            Map<String, String> testStatuses,
            List<Map<String, Object>> cleanupFailures,
            Failure failure,
            String evidenceClassification,
            boolean downstreamReleaseEvidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework_version", FRAMEWORK_VERSION);
        result.put("dsl_version", testCase == null ? "v0.3" : testCase.dslVersion());
        result.put("suite_id", plan.suiteId());
        result.put("plan_digest", plan.planDigest());
        result.put("batch_id", batchId);
        result.put("run_id", runId);
        result.put("test_case_id", testCaseId);
        result.put("test_count", testCases.size());
        result.put("profile", plan.profile());
        result.put("environment", plan.profile());
        List<Map<String, Object>> providerAggregates = providerAggregates(providerResults);
        result.put("provider_summary", providerSummary(providerAggregates));
        result.put("status", status);
        result.put("start_time", startedAt.toString());
        result.put("end_time", finishedAt.toString());
        result.put("duration_ms", Duration.between(startedAt, finishedAt).toMillis());
        result.put("timestamps", Map.of("started_at", startedAt.toString(), "finished_at", finishedAt.toString()));
        result.put("labels", Map.of(
                "evidence_classification", evidenceClassification,
                "downstream_release_evidence", downstreamReleaseEvidence));
        result.put("source_refs", testCase == null ? Map.of() : testCase.sourceRefs());
        result.put("step_results", stepResults);
        result.put("steps", stepResults);
        result.put("verify_results", verifyResults);
        result.put("test_results", testResults(testCases, testStatuses, plan.profile()));
        result.put("provider_results", providerAggregates);
        result.put("evidence_index_ref", "evidence_index.yaml");
        result.put("provider_evidence_refs", providerEvidenceRefs(evidenceRefs));
        result.put("evidence_refs", evidenceRefs);
        result.put("cleanup_failures", cleanupFailures);
        result.put("failure", failure.toMap(cleanupFailures));
        Path resultJson = runDir.resolve("result.json");
        write(resultJson, toJson(result) + "\n");
        writeEvidenceIndex(
                runDir,
                plan,
                batchId,
                runId,
                testCaseId,
                finishedAt,
                providerResults,
                verifyResults,
                distinct(evidenceRefs));
        return resultJson;
    }

    private List<Map<String, Object>> providerSummary(List<Map<String, Object>> providerResults) {
        Map<String, Map<String, Object>> summaries = new LinkedHashMap<>();
        for (Map<String, Object> provider : providerResults) {
            String key = stringValue(provider.get("target")) + "\n" + stringValue(provider.get("provider_type"));
            summaries.computeIfAbsent(key, ignored -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("target", provider.get("target"));
                summary.put("provider_contract", provider.get("provider_contract"));
                summary.put("provider_type", provider.get("provider_type"));
                summary.put("runtime_mode", provider.get("runtime_mode"));
                summary.put("status", provider.get("status"));
                return summary;
            });
        }
        return List.copyOf(summaries.values());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> providerAggregates(List<Map<String, Object>> providerResults) {
        Map<String, Map<String, Object>> aggregates = new LinkedHashMap<>();
        for (Map<String, Object> provider : providerResults) {
            String key = stringValue(provider.get("target")) + "\n"
                    + stringValue(provider.get("provider_contract")) + "\n"
                    + stringValue(provider.get("provider_type"));
            Map<String, Object> aggregate = aggregates.computeIfAbsent(key, ignored -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("target", provider.get("target"));
                map.put("provider_contract", provider.get("provider_contract"));
                map.put("provider_type", provider.get("provider_type"));
                map.put("profile", provider.get("profile"));
                map.put("runtime_mode", provider.get("runtime_mode"));
                map.put("test_case_ids", new ArrayList<String>());
                map.put("status", "passed");
                map.put("operation", "");
                map.put("operations", new ArrayList<String>());
                map.put("failure_codes", new ArrayList<String>());
                map.put("evidence_refs", new ArrayList<String>());
                map.put("resolved_operation_results", new ArrayList<Map<String, Object>>());
                map.put("cleanup_status", "passed");
                map.put("cleanup_failures", new ArrayList<Map<String, Object>>());
                map.put("release_evidence_eligible", false);
                return map;
            });
            boolean cleanupResult = provider.containsKey("cleanup_status");
            String providerStatus = stringValue(provider.get("status"));
            if (!"passed".equals(providerStatus)
                    && (!cleanupResult || "passed".equals(aggregate.get("status")))) {
                aggregate.put("status", providerStatus);
            }
            String failureCode = stringValue(provider.get("failure_code"));
            if (!failureCode.isBlank()) {
                ((List<String>) aggregate.get("failure_codes")).add(failureCode);
            }
            if (provider.containsKey("cleanup_status")) {
                aggregate.put("cleanup_status", provider.get("cleanup_status"));
                if (!"passed".equals(provider.get("cleanup_status"))) {
                    Map<String, Object> cleanupFailure = new LinkedHashMap<>();
                    cleanupFailure.put("test_case_id", provider.get("test_case_id"));
                    cleanupFailure.put("operation", provider.get("operation"));
                    cleanupFailure.put("failure_code", failureCode);
                    cleanupFailure.put("reason", provider.get("failure_reason"));
                    cleanupFailure.put("evidence_refs", provider.get("evidence_refs"));
                    ((List<Map<String, Object>>) aggregate.get("cleanup_failures")).add(cleanupFailure);
                }
            }
            ((List<String>) aggregate.get("test_case_ids")).add(stringValue(provider.get("test_case_id")));
            ((List<String>) aggregate.get("operations")).add(stringValue(provider.get("operation")));
            ((List<String>) aggregate.get("evidence_refs")).addAll(listOfStrings(provider.get("evidence_refs")));
            ((List<Map<String, Object>>) aggregate.get("resolved_operation_results"))
                    .add(mapValue(provider.get("resolved_operation_result")));
        }
        for (Map<String, Object> aggregate : aggregates.values()) {
            List<String> operations = distinct(listOfStrings(aggregate.get("operations")));
            List<String> failureCodes = distinct(listOfStrings(aggregate.get("failure_codes")));
            aggregate.put("test_case_ids", distinct(listOfStrings(aggregate.get("test_case_ids"))));
            aggregate.put("operations", operations);
            aggregate.put("operation", String.join(",", operations));
            aggregate.put("failure_codes", failureCodes);
            if (!failureCodes.isEmpty()) {
                aggregate.put("failure_code", failureCodes.get(0));
            }
            aggregate.put("evidence_refs", distinct(listOfStrings(aggregate.get("evidence_refs"))));
        }
        return List.copyOf(aggregates.values());
    }

    private void writeEvidenceIndex(
            Path runDir,
            V03ExecutionPlan plan,
            String batchId,
            String runId,
            String testCaseId,
            Instant createdAt,
            List<Map<String, Object>> providerOperationResults,
            List<Map<String, Object>> verifyResults,
            List<String> evidenceRefs) {
        Map<String, EvidenceMetadata> providerEvidence = providerEvidenceMetadata(providerOperationResults);
        Map<String, EvidenceMetadata> assertionEvidence = assertionEvidenceMetadata(verifyResults);
        StringBuilder index = new StringBuilder();
        index.append("evidence_index_version: v0.3\n");
        index.append("suite_id: ").append(plan.suiteId()).append('\n');
        index.append("batch_id: ").append(batchId).append('\n');
        index.append("run_id: ").append(runId).append('\n');
        index.append("test_case_id: ").append(testCaseId).append('\n');
        index.append("profile: ").append(plan.profile()).append('\n');
        index.append("entries:\n");
        int counter = 1;
        for (String evidenceRef : evidenceRefs) {
            if ("evidence_index.yaml".equals(evidenceRef)) {
                continue;
            }
            EvidenceEntryDraft entry = evidenceEntryDraft(
                    evidenceRef,
                    providerEvidence.getOrDefault(evidenceRef, assertionEvidence.get(evidenceRef)),
                    assertionEvidence.containsKey(evidenceRef),
                    testCaseId);
            index.append("  - evidence_id: ").append(entry.evidenceId(counter++)).append('\n');
            index.append("    evidence_type: ").append(entry.evidenceType()).append('\n');
            index.append("    produced_by: ").append(entry.producedBy()).append('\n');
            if (!entry.providerType().isBlank()) {
                index.append("    provider_type: ").append(entry.providerType()).append('\n');
                index.append("    provider_id: ").append(entry.providerId()).append('\n');
            }
            index.append("    test_case_id: ").append(entry.testCaseId()).append('\n');
            index.append("    run_id: ").append(runId).append('\n');
            index.append("    batch_id: ").append(batchId).append('\n');
            index.append("    file_path: ").append(evidenceRef).append('\n');
            index.append("    content_type: ").append(entry.contentType()).append('\n');
            index.append("    status: ").append(entry.status()).append('\n');
            if (!entry.failureCode().isBlank()) {
                index.append("    failure_code: ").append(entry.failureCode()).append('\n');
            }
            index.append("    created_at: \"").append(createdAt).append("\"\n");
            index.append("    masking_applied: true\n");
            index.append("    linked_result_field: ").append(entry.linkedResultField()).append('\n');
        }
        index.append("masking:\n");
        index.append("  raw_secret_found: false\n");
        write(runDir.resolve("evidence_index.yaml"), index.toString());
    }

    private Map<String, EvidenceMetadata> providerEvidenceMetadata(
            List<Map<String, Object>> providerOperationResults) {
        Map<String, EvidenceMetadata> metadata = new LinkedHashMap<>();
        for (Map<String, Object> result : providerOperationResults) {
            EvidenceMetadata entry = new EvidenceMetadata(
                    stringValue(result.get("test_case_id")),
                    stringValue(result.get("target")),
                    stringValue(result.get("provider_type")),
                    stringValue(result.get("status")),
                    stringValue(result.get("failure_code")));
            for (String evidenceRef : listOfStrings(result.get("evidence_refs"))) {
                metadata.put(evidenceRef, entry);
            }
        }
        return metadata;
    }

    private Map<String, EvidenceMetadata> assertionEvidenceMetadata(List<Map<String, Object>> verifyResults) {
        Map<String, EvidenceMetadata> refs = new LinkedHashMap<>();
        for (Map<String, Object> verifyResult : verifyResults) {
            String ref = stringValue(verifyResult.get("evidence_ref"));
            if (!ref.isBlank()) {
                refs.put(ref, new EvidenceMetadata(
                        stringValue(verifyResult.get("test_case_id")),
                        "",
                        "",
                        stringValue(verifyResult.get("status")),
                        stringValue(verifyResult.get("failure_code"))));
            }
        }
        return refs;
    }

    private EvidenceEntryDraft evidenceEntryDraft(
            String evidenceRef,
            EvidenceMetadata metadata,
            boolean assertionEvidence,
            String defaultTestCaseId) {
        String status = firstNonBlank(metadata == null ? "" : metadata.status(), "passed");
        String failureCode = "failed".equals(status) ? stringValue(metadata == null ? "" : metadata.failureCode()) : "";
        String entryTestCaseId = firstNonBlank(metadata == null ? "" : metadata.testCaseId(), defaultTestCaseId);
        if (evidenceRef.startsWith("logs/")) {
            return new EvidenceEntryDraft(
                    entryTestCaseId,
                    "execution_log",
                    "framework",
                    "",
                    "",
                    "text/plain",
                    "evidence_refs",
                    "passed",
                    "");
        }
        if (evidenceRef.startsWith("batch/")) {
            return new EvidenceEntryDraft(
                    entryTestCaseId,
                    "batch_summary",
                    "framework",
                    "",
                    "",
                    contentType(evidenceRef),
                    "evidence_refs",
                    "passed",
                    "");
        }
        if (assertionEvidence || evidenceRef.startsWith("assertions/")) {
            return new EvidenceEntryDraft(
                    entryTestCaseId,
                    "assertion_diff",
                    "assertion_engine",
                    "",
                    "",
                    contentType(evidenceRef),
                    "verify_results",
                    status,
                    "failed".equals(status) ? firstNonBlank(failureCode, "ASSERTION_FAILED") : "");
        }
        String providerType = metadata == null ? "" : metadata.providerType();
        String providerId = metadata == null ? "" : metadata.target();
        return new EvidenceEntryDraft(
                entryTestCaseId,
                providerEvidenceType(evidenceRef, providerType),
                "provider",
                providerType,
                providerId,
                contentType(evidenceRef),
                evidenceRef.contains("cleanup") ? "provider_results.cleanup_status" : "provider_results.resolved_operation_result",
                status,
                failureCode);
    }

    private String providerEvidenceType(String evidenceRef, String providerType) {
        if (evidenceRef.contains("cleanup")) {
            return "jdbc".equals(providerType) ? "jdbc_cleanup" : "fixture_cleanup";
        }
        if ("jdbc".equals(providerType) && evidenceRef.contains("seed_")) {
            return "jdbc_seed";
        }
        if ("jdbc".equals(providerType) && evidenceRef.contains("query_")) {
            return "jdbc_query";
        }
        if ("nats".equals(providerType)) {
            return "nats_event";
        }
        if ("kafka".equals(providerType)) {
            return "kafka_event";
        }
        if ("ibm_mq".equals(providerType)) {
            return "ibm_mq_event";
        }
        if ("polling_observer".equals(providerType) || evidenceRef.startsWith("polling/")) {
            return "polling_observation";
        }
        if ("grpc_client".equals(providerType) || evidenceRef.startsWith("provider-evidence/grpc-client/")) {
            return "grpc_request_response";
        }
        if (evidenceRef.contains("request_journal")) {
            return "wiremock_request_journal";
        }
        if (evidenceRef.contains("server_log")) {
            return "wiremock_server_log";
        }
        if ("rest_client".equals(providerType) || evidenceRef.startsWith("provider-evidence/http/")) {
            return "http_request_response";
        }
        return "fixture_setup";
    }

    private String contentType(String ref) {
        if (ref.endsWith(".json")) {
            return "application/json";
        }
        if (ref.endsWith(".yaml") || ref.endsWith(".yml")) {
            return "application/yaml";
        }
        return "text/plain";
    }

    private List<String> providerEvidenceRefs(List<String> evidenceRefs) {
        return evidenceRefs.stream()
                .filter(ref -> ref != null && (ref.startsWith("provider-evidence/") || ref.startsWith("polling/")))
                .distinct()
                .toList();
    }

    private void writeExecutionLog(Path runDir, V03ExecutionPlan plan, String status) {
        write(runDir.resolve("logs/execution.log"), "suite_id=" + plan.suiteId() + "\nstatus=" + status + "\n");
    }

    private void writeBatch(Path runDir, V03ExecutionPlan plan, String testCaseId, String status) {
        write(runDir.resolve("batch/batch.yaml"), """
                suite_id: %s
                test_case_id: %s
                status: %s
                """.formatted(plan.suiteId(), testCaseId, status));
    }

    private boolean downstreamReleaseEvidence(String evidenceClassification) {
        return !List.of("framework_verification_only", "framework_provider_capability_only")
                .contains(evidenceClassification);
    }

    private String suiteLevelTestCaseId(String suiteId, List<V03CompiledTestCase> testCases) {
        if (testCases.size() == 1) {
            return testCases.get(0).testCaseId();
        }
        return suiteId + "-MULTI";
    }

    private List<Map<String, Object>> testResults(
            List<V03CompiledTestCase> testCases,
            Map<String, String> testStatuses,
            String profile) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (V03CompiledTestCase testCase : testCases) {
            String testCaseId = testCase.testCaseId();
            results.add(Map.of(
                    "test_case_id", testCaseId,
                    "status", testStatuses.getOrDefault(testCaseId, "passed"),
                    "profile", profile));
        }
        return List.copyOf(results);
    }

    private Map<String, String> initialTestStatuses(List<V03CompiledTestCase> testCases) {
        Map<String, String> statuses = new LinkedHashMap<>();
        for (V03CompiledTestCase testCase : testCases) {
            statuses.put(testCase.testCaseId(), "passed");
        }
        return statuses;
    }

    private boolean testTerminal(Map<String, String> testStatuses, String testCaseId) {
        return Set.of("failed", "blocked").contains(testStatuses.getOrDefault(testCaseId, "passed"));
    }

    private void markTestFailed(Map<String, String> testStatuses, String testCaseId) {
        testStatuses.put(testCaseId, "failed");
    }

    static void applyOutcomeStatus(Map<String, String> testStatuses, String testCaseId, String outcomeStatus) {
        String currentStatus = testStatuses.getOrDefault(testCaseId, "passed");
        if ("passed".equals(currentStatus) && !"passed".equals(outcomeStatus)) {
            testStatuses.put(testCaseId, outcomeStatus);
            return;
        }
        testStatuses.put(testCaseId, canonicalStatus(List.of(currentStatus, outcomeStatus)));
    }

    private static void applyCleanupOutcomeStatus(
            Map<String, String> testStatuses,
            String testCaseId,
            String outcomeStatus) {
        if ("passed".equals(testStatuses.getOrDefault(testCaseId, "passed"))) {
            applyOutcomeStatus(testStatuses, testCaseId, outcomeStatus);
        }
    }

    private String suiteStatus(Map<String, String> testStatuses) {
        return canonicalStatus(testStatuses.values());
    }

    static String canonicalStatus(Collection<String> statuses) {
        Set<String> allowedStatuses = Set.of("passed", "failed", "blocked", "skipped");
        if (statuses == null || statuses.isEmpty()
                || statuses.stream().anyMatch(status -> status == null || !allowedStatuses.contains(status))) {
            throw new IllegalArgumentException("Test status must be passed, failed, blocked, or skipped.");
        }
        if (statuses.stream().anyMatch("blocked"::equals)) {
            return "blocked";
        }
        if (statuses.stream().anyMatch("failed"::equals)) {
            return "failed";
        }
        if (statuses.stream().anyMatch("passed"::equals)) {
            return "passed";
        }
        return "skipped";
    }

    private Object readYaml(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML artifact `" + path + "`.", e);
        }
    }

    private String readText(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read text artifact `" + path + "`.", e);
        }
    }

    private Object readYamlText(String text) {
        return yaml.load(text);
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

    private List<String> listOfStrings(Object value) {
        return listValue(value).stream().map(this::stringValue).filter(text -> !text.isBlank()).toList();
    }

    private List<String> stringList(Object value) {
        return listOfStrings(value);
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(stringValue(value));
    }

    private List<String> distinct(List<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private void recordProducedOutputs(
            V03ExecutionPlan plan,
            V03ExecutionStep step,
            Map<String, Object> outputs,
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs,
            Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs) {
        V03ProviderContract contract = plan.providerContracts().get(step.providerContract());
        V03ProviderContract.V03OperationDefinition operation = contract == null ? null : contract.operations().get(step.operation());
        if (operation == null) return;
        for (Map.Entry<String, Object> output : outputs.entrySet()) {
            V03OutputDefinition definition = operation.outputDefinitions().get(output.getKey());
            if (definition == null) continue;
            V03ProducedOutput produced = new V03ProducedOutput(
                    step.testCaseId(), step.id(), step.target(), step.providerContract(), step.operation(), output.getKey(),
                    definition.valueType(), definition.sensitivity(), definition.bindable(), output.getValue());
            stepOutputs.put(new V03StepOutputKey(step.testCaseId(), step.id(), output.getKey()), produced);
            if (step.kind() == V03ExecutionStepKind.PROVIDER_OPERATION && definition.bindable()) {
                generatedOutputs.put(new V03GeneratedOutputKey(
                        step.testCaseId(), step.target(), step.id(), output.getKey()), produced);
            }
        }
    }

    /**
     * Provider adapters may write diagnostic text directly to the run directory. Runtime values remain
     * available in memory for bindings, but no referenced text artifact is indexed before masking.
     */
    private void sanitizeEvidence(Path runDir, List<String> evidenceRefs, java.util.Set<String> contractRedact) {
        Path normalizedRunDir = runDir.toAbsolutePath().normalize();
        for (String evidenceRef : distinct(evidenceRefs)) {
            Path evidence = normalizedRunDir.resolve(evidenceRef).normalize();
            if (!evidence.startsWith(normalizedRunDir) || Files.isSymbolicLink(evidence) || hasMultipleLinks(evidence)) {
                throw new IllegalArgumentException("invalid_evidence_ref: `" + evidenceRef
                        + "` escapes the v0.3 run directory.");
            }
            if (!Files.isRegularFile(evidence)) {
                throw new IllegalArgumentException("missing_evidence_file: `" + evidenceRef + "`.");
            }
            try {
                Path realRunDir = normalizedRunDir.toRealPath();
                Path realEvidence = evidence.toRealPath();
                if (!realEvidence.startsWith(realRunDir)) {
                    throw new IllegalArgumentException("invalid_evidence_ref: `" + evidenceRef
                            + "` escapes the v0.3 run directory.");
                }
                if (Files.size(evidence) > 10 * 1024 * 1024) {
                    throw new IllegalArgumentException("evidence_too_large_to_sanitize: `" + evidenceRef + "`.");
                }
                String original = Files.readString(evidence);
                String redacted = redactEvidenceDocument(evidenceRef, original, contractRedact);
                if (!original.equals(redacted)) {
                    Files.writeString(evidence, redacted);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to sanitize v0.3 evidence `" + evidence + "`.", e);
            }
        }
    }

    private String redactEvidenceDocument(String evidenceRef, String original, java.util.Set<String> contractRedact) throws IOException {
        String lower = evidenceRef.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".json")) {
                Object document = objectMapper.readValue(original, Object.class);
                return objectMapper.writeValueAsString(outputRedactor.redactEvidenceValue(document, contractRedact));
            }
            if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
                Object document = yaml.load(original);
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                return new Yaml(options).dump(outputRedactor.redactEvidenceValue(document, contractRedact));
            }
        } catch (RuntimeException malformedStructuredEvidence) {
            // Provider evidence with a YAML extension may be diagnostic text; retain the log sanitizer path.
        }
        String redacted = outputRedactor.redactMessage(original);
        for (String key : contractRedact) redacted = redacted.replaceAll("(?i)([\\\"']?" + java.util.regex.Pattern.quote(key)
                + "[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+", "$1" + V03OutputRedactor.MASKED);
        return redacted;
    }

    private boolean hasMultipleLinks(Path evidence) {
        try {
            Object links = Files.getAttribute(evidence, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return links instanceof Number number && number.longValue() > 1;
        } catch (UnsupportedOperationException | IOException ignored) {
            return true;
        }
    }

    private void rejectUnindexedEvidence(Path runDir, List<String> evidenceRefs) {
        java.util.Set<String> indexed = new java.util.LinkedHashSet<>(evidenceRefs);
        try (var paths = Files.walk(runDir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String ref = runDir.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                if (!indexed.contains(ref)) {
                    throw new IllegalArgumentException("unindexed_evidence_file: `" + ref + "`.");
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to inspect v0.3 evidence directory.", error);
        }
    }

    private void recreateDirectory(Path path) {
        try {
            if (Files.exists(path)) {
                try (var paths = Files.walk(path)) {
                    for (Path entry : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                        Files.deleteIfExists(entry);
                    }
                }
            }
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to recreate v0.3 run directory `" + path + "`.", e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write v0.3 runtime artifact `" + path + "`.", e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") + "\"";
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

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isBlank() ? "v03" : safe;
    }

    private record AssertionResult(
            String testCaseId,
            String id,
            String type,
            String status,
            String evidenceRef,
            String failureCode) {
        boolean passed() {
            return "passed".equals(status);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("test_case_id", testCaseId);
            map.put("id", id);
            map.put("type", type);
            map.put("status", status);
            map.put("evidence_ref", evidenceRef);
            if (!failureCode.isBlank()) {
                map.put("failure_code", failureCode);
            }
            return map;
        }
    }

    private record Failure(String code, String classification, String reason, String ownerAction) {
        static Failure none() {
            return new Failure(null, null, null, null);
        }

        boolean isNone() {
            return code == null || code.isBlank();
        }

        Map<String, Object> toMap(List<Map<String, Object>> cleanupFailures) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("classification", classification);
            map.put("reason", reason);
            map.put("owner_action", ownerAction);
            map.put("cleanup_failures", cleanupFailures);
            return map;
        }
    }

    private record EvidenceMetadata(
            String testCaseId,
            String target,
            String providerType,
            String status,
            String failureCode) {
    }

    private record EvidenceEntryDraft(
            String testCaseId,
            String evidenceType,
            String producedBy,
            String providerType,
            String providerId,
            String contentType,
            String linkedResultField,
            String status,
            String failureCode) {

        String evidenceId(int counter) {
            return "v03-evidence-" + counter;
        }
    }

    public record V03RuntimeRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            boolean providerRuntimeExecuted,
            List<String> targets,
            List<String> providerTypes,
            Path resultJson,
            Path evidenceDir,
            String evidenceClassification,
            List<ContractFinding> findings) {

        static V03RuntimeRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new V03RuntimeRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
                    profile,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    "framework_verification_only",
                    List.copyOf(findings));
        }
    }
}
