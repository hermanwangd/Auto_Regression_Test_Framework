package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import com.specdriven.regression.provider.SampleFakeProvider;
import com.specdriven.regression.provider.SampleFakeProvider.CleanupResult;
import com.specdriven.regression.provider.SampleFakeProvider.ExecutionResult;
import com.specdriven.regression.provider.SampleFakeProvider.SetupResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path testCasePath = suiteRoot.resolve(stringValue(listValue(suite.get("tests")).get(0))).normalize();
        Map<String, Object> testCase = readMap(testCasePath);
        if (!profileAllowed(suite, testCase, requestedProfile)) {
            return GoldenRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, List.of(new ContractFinding(
                    testCasePath.toString(),
                    "profile",
                    "profile_mismatch",
                    "",
                    "sample_fake_provider",
                    requestedProfile,
                    "",
                    "Run with a profile selected by the suite manifest and allowed by compatible_profiles.")));
        }

        Path runDir = outputBase.resolve(safe(stringValue(suite.get("suite_id")))).resolve(BATCH_ID).resolve(RUN_ID);
        recreateDirectory(runDir);

        String generatedAt = resolveGeneratedAt(suiteRoot, requestedProfile);
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
        ExecutionResult execution = null;
        CleanupResult cleanup;
        try {
            if (!setup.passed()) {
                status = "failed";
                failureClassification = "FRAMEWORK_ERROR";
                failureReason = "Fixture setup failed.";
                ownerAction = "Check setup fixture and input refs.";
            } else {
                execution = fakeProvider.execute(inputFile, generatedAt, runDir);
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

        writeBatch(runDir, suite, testCase, status);
        writeEvidenceIndex(runDir, suite, testCase, requestedProfile);
        Path resultJson = writeResult(
                runDir,
                suite,
                testCase,
                requestedProfile,
                status,
                stepResults,
                verifyResults,
                failureClassification,
                failureReason,
                ownerAction);
        return new GoldenRunResult(
                "passed".equals(status),
                status,
                stringValue(suite.get("suite_id")),
                BATCH_ID,
                RUN_ID,
                stringValue(testCase.get("test_case_id")),
                requestedProfile,
                resultJson,
                runDir,
                fakeProviderExecuted,
                List.of());
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

    private void writeBatch(Path runDir, Map<String, Object> suite, Map<String, Object> testCase, String status) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: framework_verification_only
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                status: %s
                """.formatted(suite.get("suite_id"), BATCH_ID, RUN_ID, testCase.get("test_case_id"), status));
    }

    private void writeEvidenceIndex(Path runDir, Map<String, Object> suite, Map<String, Object> testCase, String profile) {
        write(runDir.resolve("evidence_index.yaml"), EvidenceIndexFormatter.format(
                new EvidenceIndexFormatter.Context(
                        stringValue(suite.get("suite_id")),
                        BATCH_ID,
                        RUN_ID,
                        stringValue(testCase.get("test_case_id")),
                        profile,
                        "sample_fake_provider",
                        "sample-fake-provider"),
                runDir,
                EVIDENCE_REFS));
    }

    private Path writeResult(
            Path runDir,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            String status,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            String failureClassification,
            String failureReason,
            String ownerAction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework_version", "v0.2");
        result.put("dsl_version", testCase.get("dsl_version"));
        result.put("suite_id", suite.get("suite_id"));
        result.put("batch_id", BATCH_ID);
        result.put("run_id", RUN_ID);
        result.put("test_case_id", testCase.get("test_case_id"));
        result.put("profile", profile);
        result.put("environment", "local_golden");
        result.put("status", status);
        result.put("start_time", "2026-06-29T00:00:00Z");
        result.put("end_time", "2026-06-29T00:00:01Z");
        result.put("duration_ms", 1000);
        result.put("timestamps", Map.of(
                "started_at", "2026-06-29T00:00:00Z",
                "finished_at", "2026-06-29T00:00:01Z"));
        result.put("labels", testCase.get("labels"));
        result.put("source_refs", testCase.get("source_refs"));
        result.put("step_results", stepResults);
        result.put("steps", stepResults);
        result.put("provider_results", List.of(providerResult(profile, status)));
        result.put("verify_results", verifyResults);
        result.put("evidence_refs", EVIDENCE_REFS);
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("classification", failureClassification);
        failure.put("reason", failureReason);
        failure.put("owner_action", ownerAction);
        result.put("failure", failure);
        Path resultJson = runDir.resolve("result.json");
        write(resultJson, toJson(result) + "\n");
        return resultJson;
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
                    profile,
                    null,
                    null,
                    false,
                    List.copyOf(findings));
        }
    }

    private record VerificationOutcome(boolean passed, List<Map<String, Object>> verifyResults) {
    }
}
