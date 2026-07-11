package com.specdriven.regression.contract;

import com.specdriven.regression.summary.SuiteExecutionContext;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import com.specdriven.regression.provider.SampleFakeProvider;
import com.specdriven.regression.provider.SampleFakeProvider.CleanupResult;
import com.specdriven.regression.provider.SampleFakeProvider.ExecutionResult;
import com.specdriven.regression.provider.SampleFakeProvider.SetupResult;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class GoldenE2eService {

    private static final String BATCH_ID = "BATCH-GOLDEN-E2E-001";
    private static final String RUN_ID = "RUN-GOLDEN-E2E-001";
    private static final String FRAMEWORK_EVIDENCE = "framework_verification_only";
    private static final List<String> EVIDENCE_REFS = List.of(
            "evidence_index.yaml",
            "logs/execution.log",
            "fixture/setup.yaml",
            "fixture/cleanup.yaml",
            "actual/actual_output.json",
            "actual/actual_output.txt",
            "expected/expected_output.ref",
            "assertions/status_is_ok.yaml",
            "assertions/output_matches_expected_json.yaml",
            "diffs/output_matches_expected_json.diff",
            "batch/batch.yaml");

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final RuntimeBindingResolver runtimeBindingResolver = new RuntimeBindingResolver();
    private final SampleFakeProvider fakeProvider = new SampleFakeProvider();
    private final Yaml yaml = new Yaml();

    public GoldenRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        return run(suiteManifest, requestedProfile,
                SuiteExecutionContext.standaloneWithBatchId(requestedProfile, outputBase, BATCH_ID));
    }

    public GoldenRunResult run(Path suiteManifest, String requestedProfile, SuiteExecutionContext executionContext) {
        requireMatchingProfile(requestedProfile, executionContext);
        Instant startedAt = Instant.now();
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return GoldenRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return GoldenRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    "",
                    "",
                    "",
                    "Provide --profile for executable suite run.")));
        }
        if (!validation.providerTypesUsed().equals(List.of("sample_fake_provider"))) {
            return GoldenRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "Executable suite mode supports framework-owned sample_fake_provider only.")));
        }

        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<TestCaseDocument> testCases = new ArrayList<>();
        List<ContractFinding> profileFindings = new ArrayList<>();
        for (Object testRef : listValue(suite.get("tests"))) {
            Path testCasePath = suiteRoot.resolve(suiteTestRef(testRef)).normalize();
            Map<String, Object> testCase = readMap(testCasePath);
            testCases.add(new TestCaseDocument(testCasePath, testCase));
            if (!profileAllowed(suite, testCase, requestedProfile)) {
                profileFindings.add(new ContractFinding(
                        testCasePath.toString(),
                        "profile",
                        "profile_mismatch",
                        "",
                        "sample_fake_provider",
                        requestedProfile,
                        "",
                        "Run with a profile selected by the suite manifest and allowed by compatible_profiles."));
            }
        }
        if (!profileFindings.isEmpty()) {
            return GoldenRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, profileFindings);
        }

        String batchId = executionContext.parentBatchId();
        String runId = executionContext.standaloneInvocation()
                ? RUN_ID
                : executionContext.newRunId("GOLDEN");
        Path runDir = executionContext.childRunRoot(stringValue(suite.get("suite_id")), runId);
        recreateDirectory(runDir);

        String generatedAt = resolveGeneratedAt(suiteRoot, requestedProfile);
        String status = "passed";
        String failureClassification = null;
        String failureReason = null;
        String ownerAction = null;
        boolean fakeProviderExecuted = false;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> testResults = new ArrayList<>();
        List<Map<String, Object>> providerResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();
        boolean multiTestSuite = testCases.size() > 1;

        for (TestCaseDocument document : testCases) {
            Path testRunDir = multiTestSuite
                    ? runDir.resolve("tests").resolve(safe(stringValue(document.testCase().get("test_case_id"))))
                    : runDir;
            GoldenTestExecution execution = executeTestCase(suiteRoot, suite, document.testCase(), requestedProfile, testRunDir, generatedAt);
            fakeProviderExecuted = fakeProviderExecuted || execution.fakeProviderExecuted();
            stepResults.addAll(execution.stepResults());
            verifyResults.addAll(execution.verifyResults());
            testResults.add(execution.testResult());
            providerResults.add(execution.providerResult());
            evidenceRefs.addAll(prefixEvidenceRefs(document.testCase(), execution.evidenceRefs(), multiTestSuite));
            if (!execution.passed() && "passed".equals(status)) {
                status = "failed";
                failureClassification = execution.failureClassification();
                failureReason = execution.failureReason();
                ownerAction = execution.ownerAction();
            }
        }

        TestCaseDocument firstTestCase = testCases.get(0);
        String topLevelTestCaseId = topLevelTestCaseId(suite, firstTestCase.testCase(), testCases.size());
        if (multiTestSuite) {
            writeAggregateExecutionLog(
                    runDir,
                    suite,
                    topLevelTestCaseId,
                    requestedProfile,
                    status,
                    verifyResults.size(),
                    fakeProviderExecuted);
            evidenceRefs.add("logs/execution.log");
        }
        evidenceRefs.add("batch/batch.yaml");
        writeBatch(runDir, suite, batchId, runId, topLevelTestCaseId, status, testCases.size());
        writeEvidenceIndex(runDir, suite, batchId, runId, topLevelTestCaseId, requestedProfile, providerResults, distinctWithIndex(evidenceRefs));
        Path resultJson = writeResult(
                runDir,
                suite,
                firstTestCase.testCase(),
                batchId,
                runId,
                requestedProfile,
                topLevelTestCaseId,
                testCases.size(),
                status,
                stepResults,
                verifyResults,
                testResults,
                providerResults,
                distinctWithIndex(evidenceRefs),
                failureClassification,
                failureReason,
                ownerAction,
                startedAt,
                Instant.now());
        return new GoldenRunResult(
                "passed".equals(status),
                status,
                stringValue(suite.get("suite_id")),
                batchId,
                runId,
                topLevelTestCaseId,
                testCases.size(),
                requestedProfile,
                resultJson,
                runDir,
                fakeProviderExecuted,
                List.of());
    }

    private void requireMatchingProfile(String profile, SuiteExecutionContext context) {
        if (!context.matchesRequestedProfile(profile)) {
            throw new IllegalArgumentException("Execution context profile does not match requested profile: " + profile);
        }
    }

    private GoldenTestExecution executeTestCase(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String requestedProfile,
            Path runDir,
            String generatedAt) {
        if (isV03(testCase)) {
            return executeV03TestCase(suiteRoot, suite, testCase, requestedProfile, runDir);
        }
        createDirectories(runDir);
        Path inputFile = suiteRoot.resolve(resolveDataRef(testCase, "${data.input}")).normalize();
        Path setupFixture = suiteRoot.resolve(resolveDataRef(testCase, "${data.setup_fixture}")).normalize();
        Path cleanupFixture = suiteRoot.resolve(resolveDataRef(testCase, "${data.cleanup_fixture}")).normalize();
        Path expectedResult = suiteRoot.resolve(resolveExpectedRef(testCase)).normalize();

        String status = "passed";
        String failureClassification = null;
        String failureReason = null;
        String ownerAction = null;
        boolean fakeProviderExecuted = false;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();

        SetupResult setup = fakeProvider.setup(setupFixture, inputFile, runDir);
        stepResults.add(step("prepare_sample_workspace", "setup_fixture", setup.passed() ? "passed" : "failed"));
        CleanupResult cleanup;
        try {
            if (!setup.passed()) {
                status = "failed";
                failureClassification = "FRAMEWORK_ERROR";
                failureReason = "Fixture setup failed.";
                ownerAction = "Check setup fixture and input refs.";
            } else {
                ExecutionResult execution = fakeProvider.execute(inputFile, generatedAt, runDir);
                fakeProviderExecuted = true;
                stepResults.add(executeStep(execution));
                writeExpectedRef(runDir, expectedResult);
                if (!Files.isRegularFile(expectedResult)) {
                    status = "failed";
                    failureClassification = "EXPECTED_RESULT_MISSING";
                    failureReason = "Expected result is missing.";
                    ownerAction = "Restore expected result artifact `" + expectedResult + "`.";
                    verifyResults.add(assertion("status_is_ok", "value_equals", "blocked", null));
                    verifyResults.add(assertion("output_matches_expected_json", "json_match", "blocked",
                            "diffs/output_matches_expected_json.diff"));
                    writeAssertionEvidence(runDir, verifyResults);
                    writeDiff(runDir, "expected result missing: " + expectedResult);
                } else {
                    VerificationOutcome outcome = verify(testCase, execution.actual(), expectedResult, runDir);
                    verifyResults.addAll(outcome.verifyResults());
                    if (!outcome.passed()) {
                        status = "failed";
                        failureClassification = "ASSERTION_FAILED";
                        failureReason = "One or more golden verification checks failed.";
                        ownerAction = "Review assertion evidence and diff artifact.";
                    }
                }
            }
        } finally {
            cleanup = fakeProvider.cleanup(cleanupFixture, runDir);
        }
        stepResults.add(step("cleanup_sample_workspace", "cleanup_fixture", cleanup.passed() ? "passed" : "failed"));
        if (!cleanup.passed()) {
            status = "failed";
            failureClassification = "FRAMEWORK_ERROR";
            failureReason = "Fixture cleanup failed.";
            ownerAction = "Check cleanup fixture and cleanup evidence.";
        }
        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", testCase.get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("provider_id", "sample-fake-runtime");
        testResult.put("provider_type", "sample_fake_provider");
        testResult.put("status", status);
        testResult.put("step_count", stepResults.size());
        testResult.put("verify_count", verifyResults.size());
        if (failureClassification != null) {
            testResult.put("failure_classification", failureClassification);
        }
        return new GoldenTestExecution(
                "passed".equals(status),
                status,
                fakeProviderExecuted,
                stepResults,
                verifyResults,
                testResult,
                providerResult(requestedProfile, status),
                EVIDENCE_REFS,
                failureClassification,
                failureReason,
                ownerAction);
    }

    private GoldenTestExecution executeV03TestCase(
            Path suiteRoot,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String requestedProfile,
            Path runDir) {
        createDirectories(runDir);
        String target = firstV03Target(testCase);
        String providerContract = stringValue(mapValue(mapValue(suite.get("targets")).get(target)).get("provider_contract"));
        String generatedAt = resolveV03GeneratedAt(suiteRoot, suite, requestedProfile, target);
        Map<String, Object> setupOperation = v03Operation(testCase, "setup", "setup_fixture");
        Map<String, Object> executeOperation = v03Operation(testCase, "execute", "execute_sample");
        Map<String, Object> cleanupOperation = v03Operation(testCase, "cleanup", "cleanup_fixture");
        Path inputFile = artifactPath(suiteRoot, suite, stringValue(mapValue(executeOperation.get("with")).get("sample.input_ref")));
        Path setupFixture = artifactPath(suiteRoot, suite, stringValue(mapValue(setupOperation.get("with")).get("fixture.setup_ref")));
        Path cleanupFixture = artifactPath(suiteRoot, suite, stringValue(mapValue(cleanupOperation.get("with")).get("fixture.cleanup_ref")));
        Path expectedResult = artifactPath(suiteRoot, suite, stringValue(mapValue(executeOperation.get("with")).get("sample.expected_ref")));

        String status = "passed";
        String failureClassification = null;
        String failureReason = null;
        String ownerAction = null;
        boolean fakeProviderExecuted = false;
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>();

        SetupResult setup = fakeProvider.setup(setupFixture, inputFile, runDir, target);
        stepResults.add(step(stringValue(setupOperation.get("id")), "setup_fixture", setup.passed() ? "passed" : "failed"));
        evidenceRefs.add("fixture/setup.yaml");
        CleanupResult cleanup;
        try {
            if (!setup.passed()) {
                status = "failed";
                failureClassification = "FRAMEWORK_ERROR";
                failureReason = "Fixture setup failed.";
                ownerAction = "Check setup fixture and input refs.";
            } else {
                ExecutionResult execution = fakeProvider.execute(inputFile, generatedAt, runDir);
                fakeProviderExecuted = true;
                stepResults.add(v03ExecuteStep(executeOperation, execution));
                evidenceRefs.add("actual/actual_output.json");
                evidenceRefs.add("actual/actual_output.txt");
                evidenceRefs.add("logs/execution.log");
                writeExpectedRef(runDir, expectedResult);
                evidenceRefs.add("expected/expected_output.ref");
                VerificationOutcome outcome = verifyV03Assertions(testCase, execution.actual(), runDir);
                verifyResults.addAll(outcome.verifyResults());
                evidenceRefs.addAll(outcome.verifyResults().stream()
                        .map(result -> "assertions/" + safe(stringValue(result.get("id"))) + ".yaml")
                        .toList());
                if (!outcome.passed()) {
                    status = "failed";
                    failureClassification = "ASSERTION_FAILED";
                    failureReason = "One or more v0.3 verification checks failed.";
                    ownerAction = "Review assertion evidence and operation outputs.";
                }
            }
        } finally {
            cleanup = fakeProvider.cleanup(cleanupFixture, runDir, target);
        }
        stepResults.add(step(stringValue(cleanupOperation.get("id")), "cleanup_fixture", cleanup.passed() ? "passed" : "failed"));
        evidenceRefs.add("fixture/cleanup.yaml");
        if (!cleanup.passed()) {
            status = "failed";
            failureClassification = "FRAMEWORK_ERROR";
            failureReason = "Fixture cleanup failed.";
            ownerAction = "Check cleanup fixture and cleanup evidence.";
        }

        Map<String, Object> testResult = new LinkedHashMap<>();
        testResult.put("test_case_id", testCase.get("test_case_id"));
        testResult.put("profile", requestedProfile);
        testResult.put("target", target);
        testResult.put("provider_contract", providerContract);
        testResult.put("provider_type", "sample_fake_provider");
        testResult.put("status", status);
        testResult.put("step_count", stepResults.size());
        testResult.put("verify_count", verifyResults.size());
        if (failureClassification != null) {
            testResult.put("failure_classification", failureClassification);
        }
        return new GoldenTestExecution(
                "passed".equals(status),
                status,
                fakeProviderExecuted,
                stepResults,
                verifyResults,
                testResult,
                providerResultV03(requestedProfile, target, providerContract, status),
                List.copyOf(evidenceRefs),
                failureClassification,
                failureReason,
                ownerAction);
    }

    private VerificationOutcome verify(
            Map<String, Object> testCase,
            Map<String, Object> actual,
            Path expectedResult,
            Path runDir) {
        Map<String, Object> expected = readMap(expectedResult);
        List<Map<String, Object>> results = new ArrayList<>();
        boolean passed = true;
        for (Object verifyValue : verifyChecks(testCase)) {
            Map<String, Object> verify = mapValue(verifyValue);
            String id = stringValue(verify.get("id"));
            String type = stringValue(verify.get("type"));
            if ("value_equals".equals(type)) {
                boolean checkPassed = stringValue(actual.get("status")).equals(stringValue(verify.get("expected")));
                results.add(assertion(id, type, checkPassed ? "passed" : "failed", null));
                passed = passed && checkPassed;
            } else if ("json_match".equals(type)) {
                Map<String, Object> comparableActual = withoutGeneratedAt(actual);
                Map<String, Object> comparableExpected = withoutGeneratedAt(expected);
                boolean checkPassed = comparableActual.equals(comparableExpected);
                results.add(assertion(id, type, checkPassed ? "passed" : "failed", "diffs/" + id + ".diff"));
                passed = passed && checkPassed;
                writeDiff(runDir, checkPassed
                        ? "no differences\n"
                        : "expected: " + toJson(comparableExpected) + "\nactual: " + toJson(comparableActual) + "\n");
            }
        }
        writeAssertionEvidence(runDir, results);
        return new VerificationOutcome(passed, List.copyOf(results));
    }

    private Map<String, Object> withoutGeneratedAt(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove("generatedAt");
        return copy;
    }

    private Map<String, Object> step(String id, String operation, String status) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("operation", operation);
        step.put("status", status);
        return step;
    }

    private Map<String, Object> executeStep(ExecutionResult execution) {
        Map<String, Object> step = step("produce_sample_output", "execute_sample", "passed");
        step.put("outputs", Map.of(
                "actual_json", "actual/actual_output.json",
                "actual_text", "actual/actual_output.txt",
                "execution_log", "logs/execution.log"));
        return step;
    }

    private Map<String, Object> assertion(String id, String type, String status, String diffRef) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("id", id);
        assertion.put("type", type);
        assertion.put("status", status);
        if (diffRef != null) {
            assertion.put("diff_ref", diffRef);
        }
        return assertion;
    }

    private void writeAssertionEvidence(Path runDir, List<Map<String, Object>> verifyResults) {
        for (Map<String, Object> result : verifyResults) {
            String id = stringValue(result.get("id"));
            write(runDir.resolve("assertions/" + id + ".yaml"), """
                    evidence_type: assertion_result
                    evidence_classification: framework_verification_only
                    downstream_release_evidence: false
                    assertion_id: %s
                    type: %s
                    status: %s
                    """.formatted(id, result.get("type"), result.get("status")));
        }
    }

    private void writeExpectedRef(Path runDir, Path expectedResult) {
        write(runDir.resolve("expected/expected_output.ref"), """
                evidence_type: expected_result_ref
                evidence_classification: framework_verification_only
                downstream_release_evidence: false
                ref: %s
                exists: %s
                """.formatted(expectedResult, Files.isRegularFile(expectedResult)));
    }

    private void writeDiff(Path runDir, String content) {
        write(runDir.resolve("diffs/output_matches_expected_json.diff"), content);
    }

    private void writeAggregateExecutionLog(
            Path runDir,
            Map<String, Object> suite,
            String testCaseId,
            String profile,
            String status,
            int verifyCount,
            boolean fakeProviderExecuted) {
        write(runDir.resolve("logs/execution.log"), """
                suite_id: %s
                test_case_id: %s
                profile: %s
                fake_provider_executed: %s
                verifier_count: %d
                status: %s
                """.formatted(suite.get("suite_id"), testCaseId, profile, fakeProviderExecuted, verifyCount, status));
    }

    private void writeBatch(Path runDir, Map<String, Object> suite, String batchId, String runId,
            String testCaseId, String status, int testCount) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_verification_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                test_count: %s
                status: %s
                """.formatted(suite.get("suite_id"), batchId, runId, testCaseId, testCount, status));
    }

    private void writeEvidenceIndex(
            Path runDir,
            Map<String, Object> suite,
            String batchId,
            String runId,
            String testCaseId,
            String profile,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs) {
        Map<String, Object> provider = providerResults.isEmpty() ? Map.of() : providerResults.get(0);
        write(runDir.resolve("evidence_index.yaml"), EvidenceIndexFormatter.format(
                new EvidenceIndexFormatter.Context(
                        stringValue(suite.get("suite_id")),
                        batchId,
                        runId,
                        testCaseId,
                        profile,
                        firstNonBlank(stringValue(provider.get("provider_type")), "sample_fake_provider"),
                        firstNonBlank(stringValue(provider.get("provider_id")), stringValue(provider.get("target")))),
                runDir,
                evidenceRefs));
    }

    private Path writeResult(
            Path runDir,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String batchId,
            String runId,
            String profile,
            String testCaseId,
            int testCount,
            String status,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<Map<String, Object>> testResults,
            List<Map<String, Object>> providerResults,
            List<String> evidenceRefs,
            String failureClassification,
            String failureReason,
            String ownerAction,
            Instant startedAt,
            Instant finishedAt) {
        ProviderFailure failure = failureClassification == null
                ? null
                : ProviderFailure.of(failureClassification, failureClassification, failureReason, ownerAction);
        return ProviderCapabilityResultWriter.write(runDir, new ProviderCapabilityResultWriter.ResultDocument(
                testCase.get("dsl_version"),
                suite.get("suite_id"),
                batchId,
                runId,
                testCaseId,
                testCount,
                profile,
                "local_golden",
                status,
                startedAt,
                finishedAt,
                testCase.get("labels"),
                testCase.get("source_refs"),
                stepResults,
                verifyResults,
                testResults,
                providerResults,
                evidenceRefs,
                evidenceRefs,
                ProviderCapabilityResultWriter.singleProviderRootFields(providerResults),
                failure,
                null,
                true));
    }

    private Map<String, Object> providerResult(String profile, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", "sample-fake-runtime");
        result.put("provider_type", "sample_fake_provider");
        result.put("profile", profile);
        result.put("runtime_mode", "stub");
        result.put("resolved_operation_result", Map.of(
                "operation", "execute_sample",
                "status", status,
                        "outputs", Map.of(
                        "actual_json", "actual/actual_output.json",
                        "actual_text", "actual/actual_output.txt",
                        "execution_log", "logs/execution.log")));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private Map<String, Object> providerResultV03(String profile, String target, String providerContract, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("provider_contract", providerContract);
        result.put("provider_type", "sample_fake_provider");
        result.put("profile", profile);
        result.put("runtime_mode", "stub");
        result.put("operation", "execute_sample");
        result.put("status", status);
        result.put("evidence_refs", List.of(
                "fixture/setup.yaml",
                "actual/actual_output.json",
                "actual/actual_output.txt",
                "fixture/cleanup.yaml"));
        result.put("resolved_operation_result", Map.of(
                "operation", "execute_sample",
                "status", status,
                "outputs", Map.of(
                        "actual_json", "actual/actual_output.json",
                        "actual_text", "actual/actual_output.txt",
                        "execution_log", "logs/execution.log")));
        result.put("resolved_operation_results", List.of(result.get("resolved_operation_result")));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private Map<String, Object> v03ExecuteStep(Map<String, Object> operation, ExecutionResult execution) {
        Map<String, Object> step = step(stringValue(operation.get("id")), "execute_sample", "passed");
        step.put("outputs", Map.of(
                "actual_json", "actual/actual_output.json",
                "actual_text", "actual/actual_output.txt",
                "execution_log", "logs/execution.log"));
        return step;
    }

    private VerificationOutcome verifyV03Assertions(
            Map<String, Object> testCase,
            Map<String, Object> actual,
            Path runDir) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean passed = true;
        for (Object verifyValue : listValue(testCase.get("verify"))) {
            Map<String, Object> verify = mapValue(verifyValue);
            if (!"assertion".equals(stringValue(verify.get("type")))) {
                continue;
            }
            Map<String, Object> assertion = mapValue(verify.get("assert"));
            Object actualValue = resolveV03Actual(actual, stringValue(assertion.get("actual")));
            Object expected = assertion.get("expected");
            boolean checkPassed = "equals".equals(stringValue(assertion.get("operator")))
                    && String.valueOf(expected).equals(String.valueOf(actualValue));
            results.add(assertion(stringValue(verify.get("id")), "assertion", checkPassed ? "passed" : "failed", null));
            passed = passed && checkPassed;
        }
        writeAssertionEvidence(runDir, results);
        return new VerificationOutcome(passed, List.copyOf(results));
    }

    private Object resolveV03Actual(Map<String, Object> actual, String ref) {
        String prefix = "step://";
        String marker = "/actual_json.";
        if (!ref.startsWith(prefix) || !ref.contains(marker)) {
            return "";
        }
        return actual.getOrDefault(ref.substring(ref.indexOf(marker) + marker.length()), "");
    }

    private String suiteTestRef(Object testRef) {
        Map<String, Object> map = mapValue(testRef);
        if (!map.isEmpty() || testRef instanceof Map<?, ?>) {
            return stringValue(map.get("ref"));
        }
        return stringValue(testRef);
    }

    private boolean isV03(Map<String, Object> testCase) {
        return "v0.3".equals(stringValue(testCase.get("dsl_version")));
    }

    private Map<String, Object> v03Operation(Map<String, Object> testCase, String phase, String operationName) {
        for (Object value : listValue(testCase.get(phase))) {
            Map<String, Object> operation = mapValue(value);
            if (operationName.equals(stringValue(operation.get("op")))) {
                return operation;
            }
        }
        return Map.of();
    }

    private String firstV03Target(Map<String, Object> testCase) {
        for (String phase : List.of("setup", "execute", "cleanup")) {
            for (Object value : listValue(testCase.get(phase))) {
                String target = stringValue(mapValue(value).get("target"));
                if (!target.isBlank()) {
                    return target;
                }
            }
        }
        return "";
    }

    private Path artifactPath(Path suiteRoot, Map<String, Object> suite, String ref) {
        if (!ref.startsWith("artifact://")) {
            return suiteRoot.resolve(ref).normalize();
        }
        String body = ref.substring("artifact://".length());
        String filePart = body.split("#", 2)[0];
        int separator = filePart.indexOf('/');
        if (separator < 1) {
            return suiteRoot.resolve(filePart).normalize();
        }
        String rootName = filePart.substring(0, separator);
        String relativePath = filePart.substring(separator + 1);
        String rootDir = stringValue(mapValue(suite.get("artifact_roots")).get(rootName));
        Path root = suiteRoot.resolve(rootDir).normalize();
        Path resolved = root.resolve(relativePath).normalize();
        try {
            Path realSuiteRoot = suiteRoot.toRealPath();
            Path realRoot = root.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (realRoot.startsWith(realSuiteRoot)
                    && realResolved.startsWith(realRoot)
                    && Files.isRegularFile(realResolved)) {
                return realResolved;
            }
        } catch (IOException e) {
            // Validation reports missing or unsafe artifact refs before runtime execution.
        }
        return suiteRoot.resolve(".blocked-artifact-ref").resolve(Integer.toHexString(ref.hashCode())).normalize();
    }

    private String resolveV03GeneratedAt(Path suiteRoot, Map<String, Object> suite, String profile, String target) {
        String envProfileRef = stringValue(mapValue(mapValue(suite.get("env_profiles")).get(profile)).get("ref"));
        if (envProfileRef.isBlank()) {
            return "2026-06-29T00:00:00Z";
        }
        Map<String, Object> envProfile = readMap(suiteRoot.resolve(envProfileRef).normalize());
        String clockRef = stringValue(mapValue(mapValue(mapValue(envProfile.get("targets")).get(target)).get("bindings"))
                .get("clock_ref"));
        if (clockRef.startsWith("fixed://")) {
            return clockRef.substring("fixed://".length());
        }
        return clockRef.isBlank() ? "2026-06-29T00:00:00Z" : clockRef;
    }

    private List<String> prefixEvidenceRefs(Map<String, Object> testCase, List<String> refs, boolean multiTestSuite) {
        if (!multiTestSuite) {
            return refs;
        }
        String prefix = "tests/" + safe(stringValue(testCase.get("test_case_id"))) + "/";
        return refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .filter(ref -> !"evidence_index.yaml".equals(ref) && !"batch/batch.yaml".equals(ref))
                .map(ref -> prefix + ref)
                .toList();
    }

    private List<String> distinctWithIndex(List<String> refs) {
        List<String> values = new ArrayList<>();
        values.add("evidence_index.yaml");
        for (String ref : refs) {
            if (ref != null && !ref.isBlank() && !"evidence_index.yaml".equals(ref) && !values.contains(ref)) {
                values.add(ref);
            }
        }
        return List.copyOf(values);
    }

    private String topLevelTestCaseId(Map<String, Object> suite, Map<String, Object> firstTestCase, int testCount) {
        if (testCount == 1) {
            return stringValue(firstTestCase.get("test_case_id"));
        }
        return stringValue(suite.get("suite_id")) + "-MULTI";
    }

    private String resolveGeneratedAt(Path suiteRoot, String profile) {
        Map<String, Object> providerBinding =
                runtimeBindingResolver.providerBinding(suiteRoot, profile, "sample-fake-runtime");
        String clockRef = stringValue(mapValue(providerBinding.get("binding_values")).get("clock_ref"));
        if (!clockRef.isBlank()) {
            return clockRef.startsWith("fixed://") ? clockRef.substring("fixed://".length()) : clockRef;
        }
        return "2026-06-29T00:00:00Z";
    }

    private String resolveDataRef(Map<String, Object> testCase, String expression) {
        String path = expression.substring("${data.".length(), expression.length() - 1);
        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            String resolved = stringValue(mapValue(mapValue(testCase.get("data")).get(parts[0])).get("ref"));
            return resolved.isBlank() ? "" : resolved;
        }
        if (parts.length != 2) {
            return "";
        }
        return stringValue(mapValue(mapValue(mapValue(testCase.get("data_binding")).get(parts[0])).get(parts[1])).get("ref"));
    }

    private String resolveExpectedRef(Map<String, Object> testCase) {
        String dataExpected = stringValue(mapValue(mapValue(testCase.get("data")).get("expected_output")).get("ref"));
        if (!dataExpected.isBlank()) {
            return dataExpected;
        }
        Map<String, Object> expectedResults = mapValue(testCase.get("expected_results"));
        return stringValue(mapValue(expectedResults.get("expected_output")).get("ref"));
    }

    private List<Object> verifyChecks(Map<String, Object> testCase) {
        Object verify = testCase.get("verify");
        if (verify instanceof Map<?, ?> verifyMap) {
            return listValue(verifyMap.get("checks"));
        }
        return listValue(verify);
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
            throw new UncheckedIOException("Failed to read golden artifact: " + path, e);
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

    private boolean profileAllowed(Map<String, Object> suite, Map<String, Object> testCase, String requestedProfile) {
        List<String> selectedProfiles = selectedProfiles(suite);
        if (!selectedProfiles.isEmpty() && !selectedProfiles.contains(requestedProfile)) {
            return false;
        }
        List<String> compatibleProfiles = stringList(testCase.get("compatible_profiles"));
        return compatibleProfiles.isEmpty() || compatibleProfiles.contains(requestedProfile);
    }

    private List<String> selectedProfiles(Map<String, Object> suite) {
        List<String> profiles = new ArrayList<>();
        String profile = stringValue(suite.get("profile"));
        if (!profile.isBlank()) {
            profiles.add(profile);
        }
        profiles.addAll(stringList(suite.get("profiles")));
        String selectionProfile = stringValue(mapValue(suite.get("selection")).get("profile"));
        if (!selectionProfile.isBlank()) {
            profiles.add(selectionProfile);
        }
        return profiles.stream().distinct().toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
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
                throw new UncheckedIOException("Failed to clean generated golden output: " + directory, e);
            }
        }
        createDirectories(directory);
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated golden output: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write golden output: " + path, e);
        }
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
                json.append(toJson(String.valueOf(entry.getKey()))).append(": ").append(toJson(entry.getValue()));
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
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record GoldenRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            Path resultJson,
            Path evidenceDir,
            boolean fakeProviderExecuted,
            List<ContractFinding> findings) {

        static GoldenRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new GoldenRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
                    profile,
                    null,
                    null,
                    false,
                    List.copyOf(findings));
        }
    }

    private record TestCaseDocument(Path path, Map<String, Object> testCase) {
    }

    private record GoldenTestExecution(
            boolean passed,
            String status,
            boolean fakeProviderExecuted,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            Map<String, Object> testResult,
            Map<String, Object> providerResult,
            List<String> evidenceRefs,
            String failureClassification,
            String failureReason,
            String ownerAction) {
    }

    private record VerificationOutcome(boolean passed, List<Map<String, Object>> verifyResults) {
    }
}
