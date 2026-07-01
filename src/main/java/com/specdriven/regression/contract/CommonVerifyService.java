package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.evidence.EvidenceIndexFormatter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public class CommonVerifyService {

    private static final String PROVIDER_TYPE = "common_verify";
    private static final String EVIDENCE_CLASSIFICATION = "framework_provider_capability_only";
    private static final Object MISSING_FRAGMENT = new Object();

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final Yaml yaml = new Yaml();

    public CommonVerifyRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return CommonVerifyRunResult.blocked(validation.suiteId(), requestedProfile, validation.findings());
        }
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return CommonVerifyRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "missing_required_field",
                    "",
                    "",
                    "",
                    "",
                    "Provide --profile for common verifier suite run.")));
        }
        if (!validation.providerTypesUsed().equals(List.of(PROVIDER_TYPE))) {
            return CommonVerifyRunResult.blocked(validation.suiteId(), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_type",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", validation.providerTypesUsed()),
                    requestedProfile,
                    "",
                    "Common verifier suite mode supports provider_type `common_verify` only.")));
        }

        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        List<Object> tests = listValue(suite.get("tests"));
        if (tests.size() != 1) {
            return CommonVerifyRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "tests",
                    "unsupported_suite_shape",
                    "",
                    PROVIDER_TYPE,
                    requestedProfile,
                    "",
                    "Common verifier run currently supports exactly one test case per suite; split suites or add batch result support before adding more than one test case.")));
        }
        Path testCasePath = suiteRoot.resolve(stringValue(tests.get(0))).normalize();
        Map<String, Object> testCase = readMap(testCasePath);
        List<ContractFinding> profileFindings = SuiteProfileGate.validate(
                suiteManifest,
                suite,
                List.of(new SuiteProfileGate.TestCaseDocument(testCasePath, testCase)),
                requestedProfile,
                PROVIDER_TYPE);
        if (!profileFindings.isEmpty()) {
            return CommonVerifyRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, profileFindings);
        }
        TargetSelection selection = selectTarget(testCase, requestedProfile);
        if (selection.providerId().isBlank()) {
            return CommonVerifyRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, List.of(new ContractFinding(
                    testCasePath.toString(),
                    "targets",
                    "missing_provider_instance",
                    "",
                    PROVIDER_TYPE,
                    requestedProfile,
                    "",
                    "Declare a DSL target with provider_id for the common verifier runtime.")));
        }
        List<ContractFinding> configFindings = pollingConfigFindings(testCasePath, testCase, requestedProfile);
        if (!configFindings.isEmpty()) {
            return CommonVerifyRunResult.blocked(stringValue(suite.get("suite_id")), requestedProfile, configFindings);
        }

        RunIds ids = runIds("COMMON");
        Path runDir = outputBase.resolve(safe(stringValue(suite.get("suite_id")))).resolve(ids.batchId()).resolve(ids.runId());
        recreateDirectory(runDir);
        Instant startedAt = Instant.now();
        List<Map<String, Object>> verifyResults = new ArrayList<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();
        List<String> evidenceRefs = new ArrayList<>(List.of("evidence_index.yaml", "logs/execution.log", "batch/batch.yaml"));
        String status = "passed";
        Failure failure = null;

        for (Object value : verifyValues(testCase)) {
            Map<String, Object> verify = mapValue(value);
            VerifyOutcome outcome = evaluateVerifier(suiteRoot, runDir, verify);
            verifyResults.add(outcome.result());
            evidenceRefs.addAll(outcome.evidenceRefs());
            if (!outcome.passed() && "passed".equals(status)) {
                status = "failed";
                failure = new Failure(
                        outcome.failureCode(),
                        outcome.polling() ? "POLLING_TIMEOUT" : "ASSERTION_FAILED",
                        outcome.reason(),
                        outcome.ownerAction());
            }
        }
        Instant finishedAt = Instant.now();
        writeExecutionLog(runDir, suite, testCase, requestedProfile, status, verifyResults);
        writeBatch(runDir, suite, testCase, ids, requestedProfile, status);
        writeEvidenceIndex(runDir, suite, testCase, ids, requestedProfile, selection, evidenceRefs);
        Path resultJson = writeResult(
                runDir,
                suite,
                testCase,
                ids,
                requestedProfile,
                selection,
                status,
                stepResults,
                verifyResults,
                distinct(evidenceRefs),
                failure,
                startedAt,
                finishedAt);
        return new CommonVerifyRunResult(
                "passed".equals(status),
                status,
                stringValue(suite.get("suite_id")),
                ids.batchId(),
                ids.runId(),
                stringValue(testCase.get("test_case_id")),
                requestedProfile,
                resultJson,
                runDir,
                false,
                PROVIDER_TYPE,
                selection.providerId(),
                List.of());
    }

    private VerifyOutcome evaluateVerifier(Path suiteRoot, Path runDir, Map<String, Object> verify) {
        String type = stringValue(verify.get("type"));
        if ("json_match".equals(type)) {
            return evaluateJsonMatch(suiteRoot, runDir, verify);
        }
        if ("schema_match".equals(type)) {
            return evaluateSchemaMatch(suiteRoot, runDir, verify);
        }
        if ("file_diff".equals(type)) {
            return evaluateFileDiff(suiteRoot, runDir, verify);
        }
        if (List.of("event_published", "db_record_exists").contains(type)) {
            return evaluatePolling(suiteRoot, runDir, verify);
        }
        String id = verifyId(verify);
        return assertionOutcome(
                runDir,
                id,
                type,
                false,
                "ASSERTION_MISMATCH",
                "Unsupported common verifier type `" + type + "`.",
                "Use a P0 verifier type: json_match, schema_match, file_diff, event_published, or db_record_exists.",
                "",
                Map.of(),
                "");
    }

    private VerifyOutcome evaluateJsonMatch(Path suiteRoot, Path runDir, Map<String, Object> verify) {
        String id = verifyId(verify);
        String expectedRef = firstText(verify, "expected_ref", "expected");
        String actualRef = firstText(verify, "actual_ref", "actual_ref");
        Map<String, Object> options = mapValue(verify.get("options"));
        ArtifactValue expected = readJsonArtifact(suiteRoot, expectedRef, "EXPECTED_ARTIFACT_MISSING");
        ArtifactValue actual = readJsonArtifact(suiteRoot, actualRef, "ACTUAL_ARTIFACT_MISSING");
        if (!expected.available()) {
            return missingArtifact(runDir, id, "json_match", expectedRef, actualRef, expected.failureCode(), "Restore expected artifact `" + expectedRef + "`.");
        }
        if (!actual.available()) {
            return missingArtifact(runDir, id, "json_match", expectedRef, actualRef, actual.failureCode(), "Restore actual artifact `" + actualRef + "`.");
        }
        List<String> ignorePaths = stringList(options.get("ignore_paths"));
        String normalize = firstText(options, "normalize");
        boolean ignoreOrder = boolValue(options.get("ignore_order"));
        Object expectedValue = comparableJson(expected.value(), ignorePaths, normalize, ignoreOrder);
        Object actualValue = comparableJson(actual.value(), ignorePaths, normalize, ignoreOrder);
        boolean passed = Objects.equals(expectedValue, actualValue);
        String diffRef = "diffs/" + id + ".diff";
        MaskedValue maskedExpected = maskValue(expectedValue);
        MaskedValue maskedActual = maskValue(actualValue);
        boolean rawSecretFound = maskedExpected.rawSecretFound() || maskedActual.rawSecretFound();
        writeDiff(runDir, diffRef, passed
                ? "no differences\n"
                : "expected: " + toJson(maskedExpected.value()) + "\nactual: " + toJson(maskedActual.value()) + "\n");
        return assertionOutcome(
                runDir,
                id,
                "json_match",
                passed,
                "ASSERTION_MISMATCH",
                "JSON artifacts did not match.",
                "Review assertion diff evidence and update the expected artifact only through owner review.",
                diffRef,
                Map.of(
                        "expected_ref", expectedRef,
                        "actual_ref", actualRef,
                        "ignore_paths", ignorePaths,
                        "normalize", normalize,
                        "ignore_order", ignoreOrder,
                        "masked_actual_sample", maskedSample(maskedActual.value()),
                        "raw_secret_found", rawSecretFound),
                passed ? "" : diffRef);
    }

    private VerifyOutcome evaluateSchemaMatch(Path suiteRoot, Path runDir, Map<String, Object> verify) {
        String id = verifyId(verify);
        String schemaRef = firstText(verify, "schema_ref", "expected_ref");
        String actualRef = firstText(verify, "actual_ref", "actual_ref");
        ArtifactValue schema = readJsonArtifact(suiteRoot, schemaRef, "EXPECTED_ARTIFACT_MISSING");
        ArtifactValue actual = readJsonArtifact(suiteRoot, actualRef, "ACTUAL_ARTIFACT_MISSING");
        if (!schema.available()) {
            return missingArtifact(runDir, id, "schema_match", schemaRef, actualRef, schema.failureCode(), "Restore expected artifact `" + schemaRef + "`.");
        }
        if (!actual.available()) {
            return missingArtifact(runDir, id, "schema_match", schemaRef, actualRef, actual.failureCode(), "Restore actual artifact `" + actualRef + "`.");
        }
        List<String> mismatches = new ArrayList<>();
        validateSchema("$", schema.value(), actual.value(), mismatches);
        boolean passed = mismatches.isEmpty();
        String diffRef = "diffs/" + id + ".diff";
        writeDiff(runDir, diffRef, passed ? "no differences\n" : String.join("\n", mismatches) + "\n");
        MaskedValue maskedActual = maskValue(actual.value());
        return assertionOutcome(
                runDir,
                id,
                "schema_match",
                passed,
                "SCHEMA_MISMATCH",
                "JSON artifact did not satisfy expected schema.",
                "Review schema mismatch and correct the actual artifact or owner-approved schema.",
                diffRef,
                Map.of(
                        "expected_ref", schemaRef,
                        "actual_ref", actualRef,
                        "ignore_paths", List.of(),
                        "normalize", firstText(mapValue(verify.get("options")), "normalize"),
                        "ignore_order", false,
                        "masked_actual_sample", maskedSample(maskedActual.value()),
                        "raw_secret_found", maskedActual.rawSecretFound()),
                passed ? "" : diffRef);
    }

    private VerifyOutcome evaluateFileDiff(Path suiteRoot, Path runDir, Map<String, Object> verify) {
        String id = verifyId(verify);
        String expectedRef = firstText(verify, "expected_ref", "expected");
        String actualRef = firstText(verify, "actual_ref", "actual_ref");
        Map<String, Object> options = mapValue(verify.get("options"));
        ArtifactText expected = readTextArtifact(suiteRoot, expectedRef, "EXPECTED_ARTIFACT_MISSING");
        ArtifactText actual = readTextArtifact(suiteRoot, actualRef, "ACTUAL_ARTIFACT_MISSING");
        if (!expected.available()) {
            return missingArtifact(runDir, id, "file_diff", expectedRef, actualRef, expected.failureCode(), "Restore expected artifact `" + expectedRef + "`.");
        }
        if (!actual.available()) {
            return missingArtifact(runDir, id, "file_diff", expectedRef, actualRef, actual.failureCode(), "Restore actual artifact `" + actualRef + "`.");
        }
        String normalize = firstText(options, "normalize");
        boolean ignoreOrder = boolValue(options.get("ignore_order"));
        String expectedText = normalizeText(expected.text(), normalize, ignoreOrder);
        String actualText = normalizeText(actual.text(), normalize, ignoreOrder);
        boolean passed = expectedText.equals(actualText);
        String diffRef = "diffs/" + id + ".diff";
        MaskedText maskedExpected = maskText(expectedText);
        MaskedText maskedActual = maskText(actualText);
        boolean rawSecretFound = maskedExpected.rawSecretFound() || maskedActual.rawSecretFound();
        writeDiff(runDir, diffRef, passed
                ? "no differences\n"
                : "expected:\n" + maskedExpected.text() + "\nactual:\n" + maskedActual.text() + "\n");
        return assertionOutcome(
                runDir,
                id,
                "file_diff",
                passed,
                "ASSERTION_MISMATCH",
                "Files did not match.",
                "Review file diff evidence and correct actual output or expected artifact through owner review.",
                diffRef,
                Map.of(
                        "expected_ref", expectedRef,
                        "actual_ref", actualRef,
                        "ignore_paths", List.of(),
                        "normalize", normalize,
                        "ignore_order", ignoreOrder,
                        "masked_actual_sample", maskedSample(maskedActual.text()),
                        "raw_secret_found", rawSecretFound),
                passed ? "" : diffRef);
    }

    private VerifyOutcome evaluatePolling(Path suiteRoot, Path runDir, Map<String, Object> verify) {
        String id = verifyId(verify);
        String type = stringValue(verify.get("type"));
        String expectedRef = firstText(verify, "expected_ref", "expected");
        String actualRef = firstText(verify, "actual_ref", "actual_ref");
        Map<String, Object> options = mapValue(verify.get("options"));
        Duration timeout = parseDuration(firstText(options, "timeout"), Duration.ofSeconds(30));
        Duration pollInterval = parseDuration(firstText(options, "poll_interval"), Duration.ZERO);
        String consumeFrom = firstText(options, "consume_from");
        ArtifactValue expected = readJsonArtifact(suiteRoot, expectedRef, "EXPECTED_ARTIFACT_MISSING");
        if (!expected.available()) {
            return pollingArtifactFailure(runDir, id, type, expectedRef, actualRef, expected.failureCode(), "Restore expected artifact `" + expectedRef + "`.");
        }
        Instant started = Instant.now();
        Instant deadline = started.plus(timeout);
        int attempts = 0;
        Object lastObserved = Map.of("status", "not_observed");
        boolean matched = false;
        do {
            attempts++;
            ArtifactValue actual = readJsonArtifact(suiteRoot, actualRef, "ACTUAL_ARTIFACT_MISSING");
            lastObserved = actual.available() ? actual.value() : Map.of("artifact_status", "missing", "ref", actualRef);
            matched = actual.available() && observationMatches(expected.value(), actual.value(), type);
            if (matched || pollInterval.isZero() || pollInterval.isNegative()) {
                break;
            }
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                break;
            }
            sleep(pollInterval.compareTo(remaining) > 0 ? remaining : pollInterval);
        } while (Instant.now().isBefore(deadline));
        Instant finished = Instant.now();
        String evidenceRef = "polling/" + id + ".yaml";
        String status = matched ? "passed" : "failed";
        String failureCode = matched ? "" : "POLLING_TIMEOUT";
        writePollingEvidence(
                runDir,
                evidenceRef,
                id,
                type,
                expectedRef,
                actualRef,
                timeout,
                pollInterval,
                attempts,
                started,
                finished,
                lastObserved,
                status,
                failureCode,
                consumeFrom);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("status", status);
        result.put("expected_ref", expectedRef);
        result.put("actual_ref", actualRef);
        result.put("polling", Map.of(
                "timeout", timeout.toString(),
                "poll_interval", pollInterval.toString(),
                "attempts", attempts,
                "last_observed_ref", evidenceRef,
                "failure_code", failureCode));
        if (!matched) {
            result.put("failure_code", failureCode);
            result.put("reason", "Observation did not satisfy expected value before timeout.");
            result.put("owner_action", "Review polling evidence and upstream observation readiness.");
        }
        return new VerifyOutcome(
                matched,
                result,
                List.of(evidenceRef),
                failureCode,
                "Observation did not satisfy expected value before timeout.",
                "Review polling evidence and upstream observation readiness.",
                true);
    }

    private VerifyOutcome pollingArtifactFailure(
            Path runDir,
            String id,
            String type,
            String expectedRef,
            String actualRef,
            String failureCode,
            String ownerAction) {
        String evidenceRef = "polling/" + id + ".yaml";
        Instant now = Instant.now();
        writePollingEvidence(
                runDir,
                evidenceRef,
                id,
                type,
                expectedRef,
                actualRef,
                Duration.ZERO,
                Duration.ZERO,
                1,
                now,
                now,
                Map.of("artifact_status", "missing"),
                "failed",
                failureCode,
                "");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("status", "failed");
        result.put("expected_ref", expectedRef);
        result.put("actual_ref", actualRef);
        result.put("polling", Map.of("attempts", 1, "last_observed_ref", evidenceRef, "failure_code", failureCode));
        result.put("failure_code", failureCode);
        result.put("owner_action", ownerAction);
        return new VerifyOutcome(false, result, List.of(evidenceRef), failureCode, ownerAction, ownerAction, true);
    }

    private List<ContractFinding> pollingConfigFindings(Path testCasePath, Map<String, Object> testCase, String profile) {
        List<ContractFinding> findings = new ArrayList<>();
        for (Object value : verifyValues(testCase)) {
            Map<String, Object> verify = mapValue(value);
            if (!List.of("event_published", "db_record_exists").contains(stringValue(verify.get("type")))) {
                continue;
            }
            Map<String, Object> options = mapValue(verify.get("options"));
            for (String field : List.of("timeout", "poll_interval")) {
                String valueText = firstText(options, field);
                if (valueText.isBlank()) {
                    continue;
                }
                try {
                    Duration parsed = Duration.parse(valueText);
                    if (parsed.isNegative()) {
                        throw new DateTimeParseException("negative duration", valueText, 0);
                    }
                } catch (RuntimeException e) {
                    findings.add(new ContractFinding(
                            testCasePath.toString(),
                            "verify." + verifyId(verify) + ".options." + field,
                            "invalid_polling_config",
                            "",
                            PROVIDER_TYPE,
                            profile,
                            stringValue(verify.get("type")),
                            "Use ISO-8601 duration values for polling " + field + ", for example `PT1S`."));
                }
            }
            String consumeFrom = firstText(options, "consume_from");
            if (!consumeFrom.isBlank() && !"test_start_time".equals(consumeFrom) && !"earliest".equals(consumeFrom)) {
                try {
                    Instant.parse(consumeFrom);
                } catch (RuntimeException e) {
                    findings.add(new ContractFinding(
                            testCasePath.toString(),
                            "verify." + verifyId(verify) + ".options.consume_from",
                            "invalid_polling_config",
                            "",
                            PROVIDER_TYPE,
                            profile,
                            stringValue(verify.get("type")),
                            "Use `test_start_time`, `earliest`, or an ISO-8601 instant for consume_from."));
                }
            }
        }
        return List.copyOf(findings);
    }

    private List<Object> verifyValues(Map<String, Object> testCase) {
        Object verify = testCase.get("verify");
        if (verify instanceof Map<?, ?> verifyMap) {
            return listValue(verifyMap.get("checks"));
        }
        return listValue(verify);
    }

    private VerifyOutcome missingArtifact(
            Path runDir,
            String id,
            String type,
            String expectedRef,
            String actualRef,
            String failureCode,
            String ownerAction) {
        return assertionOutcome(
                runDir,
                id,
                type,
                false,
                failureCode,
                ownerAction,
                ownerAction,
                "",
                Map.of(
                        "expected_ref", expectedRef,
                        "actual_ref", actualRef,
                        "ignore_paths", List.of(),
                        "normalize", "",
                        "ignore_order", false,
                        "masked_actual_sample", "",
                        "raw_secret_found", false),
                "");
    }

    private VerifyOutcome assertionOutcome(
            Path runDir,
            String id,
            String type,
            boolean passed,
            String failureCode,
            String reason,
            String ownerAction,
            String diffRef,
            Map<String, Object> evidenceFields,
            String resultDiffRef) {
        String evidenceRef = "assertions/" + id + ".yaml";
        writeAssertionEvidence(runDir, evidenceRef, id, type, passed, failureCode, reason, ownerAction, diffRef, evidenceFields);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("status", passed ? "passed" : "failed");
        result.put("expected_ref", evidenceFields.getOrDefault("expected_ref", ""));
        result.put("actual_ref", evidenceFields.getOrDefault("actual_ref", ""));
        result.put("ignore_paths", evidenceFields.getOrDefault("ignore_paths", List.of()));
        result.put("normalize", evidenceFields.getOrDefault("normalize", ""));
        result.put("ignore_order", evidenceFields.getOrDefault("ignore_order", false));
        result.put("assertion_evidence_ref", evidenceRef);
        if (!resultDiffRef.isBlank()) {
            result.put("diff_ref", resultDiffRef);
        }
        if (!passed) {
            result.put("failure_code", failureCode);
            result.put("reason", reason);
            result.put("owner_action", ownerAction);
            result.put("summary", id + ":" + type + ":failed");
        }
        List<String> refs = new ArrayList<>(List.of(evidenceRef));
        if (!diffRef.isBlank()) {
            refs.add(diffRef);
        }
        return new VerifyOutcome(passed, result, refs, failureCode, reason, ownerAction, false);
    }

    private Object comparableJson(Object value, List<String> ignorePaths, String normalize, boolean ignoreOrder) {
        Object copy = deepCopy(value);
        for (String ignorePath : ignorePaths) {
            removePath(copy, ignorePath);
        }
        if ("canonical_json".equals(normalize) || ignoreOrder) {
            return canonical(copy, ignoreOrder);
        }
        return copy;
    }

    private Object canonical(Object value, boolean ignoreOrder) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> sorted.put(String.valueOf(entry.getKey()), canonical(entry.getValue(), ignoreOrder)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> normalized = collection.stream()
                    .map(item -> canonical(item, ignoreOrder))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            if (ignoreOrder) {
                normalized.sort(Comparator.comparing(this::toJson));
            }
            return normalized;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : collection) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }

    private void removePath(Object value, String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.isBlank()) {
            return;
        }
        if (path.startsWith("$.")) {
            path = path.substring(2);
        }
        List<String> parts = path.startsWith("/")
                ? List.of(path.substring(1).split("/"))
                : List.of(path.split("\\."));
        Object current = value;
        for (int index = 0; index < parts.size() - 1; index++) {
            String part = parts.get(index);
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list && numeric(part)) {
                int listIndex = Integer.parseInt(part);
                if (listIndex < 0 || listIndex >= list.size()) {
                    return;
                }
                current = list.get(listIndex);
            } else {
                return;
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        String leaf = parts.get(parts.size() - 1);
        if (current instanceof Map<?, ?> map) {
            map.remove(leaf);
        } else if (current instanceof List<?> list && numeric(leaf)) {
            int listIndex = Integer.parseInt(leaf);
            if (listIndex >= 0 && listIndex < list.size()) {
                list.remove(listIndex);
            }
        }
    }

    private boolean numeric(String value) {
        return value != null && value.matches("\\d+");
    }

    private void validateSchema(String path, Object schemaValue, Object actualValue, List<String> mismatches) {
        Map<String, Object> schema = mapValue(schemaValue);
        if (schema.isEmpty()) {
            mismatches.add(path + ": schema must be an object");
            return;
        }
        String type = stringValue(schema.get("type"));
        if (!type.isBlank() && !matchesType(type, actualValue)) {
            mismatches.add(path + ": expected type " + type + " but was " + actualType(actualValue));
            return;
        }
        if ("object".equals(type)) {
            Map<String, Object> actual = mapValue(actualValue);
            for (String required : stringList(schema.get("required"))) {
                if (!actual.containsKey(required)) {
                    mismatches.add(path + "." + required + ": required field missing");
                }
            }
            Map<String, Object> properties = mapValue(schema.get("properties"));
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (actual.containsKey(entry.getKey())) {
                    validateSchema(path + "." + entry.getKey(), entry.getValue(), actual.get(entry.getKey()), mismatches);
                }
            }
        }
        if ("array".equals(type) && schema.containsKey("items") && actualValue instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                validateSchema(path + "[" + index++ + "]", schema.get("items"), item, mismatches);
            }
        }
    }

    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Collection<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private String actualType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof Collection<?>) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return value.getClass().getSimpleName();
    }

    private String normalizeText(String text, String normalize, boolean ignoreOrder) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if ("trim_lines".equals(normalize)) {
            lines.replaceAll(String::trim);
        }
        if (ignoreOrder) {
            Collections.sort(lines);
        }
        return String.join("\n", lines);
    }

    private boolean observationMatches(Object expectedValue, Object actualValue, String type) {
        Map<String, Object> expected = mapValue(expectedValue);
        Map<String, Object> actual = mapValue(actualValue);
        if ("event_published".equals(type) && !Boolean.TRUE.equals(actual.get("published"))) {
            return false;
        }
        if ("db_record_exists".equals(type) && !Boolean.TRUE.equals(actual.get("exists"))) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            if (!Objects.equals(entry.getValue(), actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private ArtifactValue readJsonArtifact(Path suiteRoot, String ref, String failureCode) {
        Path path = artifactPath(suiteRoot, ref);
        if (path == null || !Files.isRegularFile(path)) {
            return new ArtifactValue(false, null, failureCode);
        }
        try {
            Object loaded = yaml.load(Files.readString(path));
            Object value = resolveFragment(loaded, ref);
            if (value == MISSING_FRAGMENT) {
                return new ArtifactValue(false, null, failureCode);
            }
            return new ArtifactValue(true, value, "");
        } catch (IOException | RuntimeException e) {
            return new ArtifactValue(false, null, failureCode);
        }
    }

    private ArtifactText readTextArtifact(Path suiteRoot, String ref, String failureCode) {
        Path path = artifactPath(suiteRoot, ref);
        if (path == null || !Files.isRegularFile(path)) {
            return new ArtifactText(false, "", failureCode);
        }
        try {
            return new ArtifactText(true, Files.readString(path), "");
        } catch (IOException e) {
            return new ArtifactText(false, "", failureCode);
        }
    }

    private Path artifactPath(Path suiteRoot, String ref) {
        String fileRef = ref == null ? "" : ref.split("#", 2)[0];
        if (fileRef.isBlank() || fileRef.contains("://") || fileRef.startsWith("${")) {
            return null;
        }
        Path resolved = suiteRoot.resolve(fileRef).normalize();
        return resolved.startsWith(suiteRoot) ? resolved : null;
    }

    private Object resolveFragment(Object loaded, String ref) {
        if (ref == null || !ref.contains("#")) {
            return loaded;
        }
        String fragment = ref.substring(ref.indexOf('#') + 1);
        if (fragment.startsWith("/")) {
            fragment = fragment.substring(1);
        }
        Object current = loaded;
        for (String rawPart : fragment.split("/")) {
            String part = rawPart.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(part)) {
                    return MISSING_FRAGMENT;
                }
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                if (!numeric(part)) {
                    return MISSING_FRAGMENT;
                }
                int index = Integer.parseInt(part);
                if (index < 0 || index >= list.size()) {
                    return MISSING_FRAGMENT;
                }
                current = list.get(index);
            } else {
                return MISSING_FRAGMENT;
            }
        }
        return current;
    }

    private TargetSelection selectTarget(Map<String, Object> testCase, String profile) {
        for (Map.Entry<String, Object> entry : mapValue(testCase.get("targets")).entrySet()) {
            Map<String, Object> target = mapValue(entry.getValue());
            return new TargetSelection(entry.getKey(), stringValue(target.get("provider_id")), "local");
        }
        return new TargetSelection("", "", "");
    }

    private void writeAssertionEvidence(
            Path runDir,
            String evidenceRef,
            String id,
            String type,
            boolean passed,
            String failureCode,
            String reason,
            String ownerAction,
            String diffRef,
            Map<String, Object> evidenceFields) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("evidence_type: assertion_result\n");
        evidence.append("evidence_classification: ").append(EVIDENCE_CLASSIFICATION).append('\n');
        evidence.append("downstream_release_evidence: false\n");
        evidence.append("assertion_id: ").append(id).append('\n');
        evidence.append("verifier_type: ").append(type).append('\n');
        evidence.append("expected_ref: ").append(evidenceFields.getOrDefault("expected_ref", "")).append('\n');
        evidence.append("actual_ref: ").append(evidenceFields.getOrDefault("actual_ref", "")).append('\n');
        evidence.append("ignore_paths:\n");
        for (String path : stringList(evidenceFields.get("ignore_paths"))) {
            evidence.append("  - ").append(path).append('\n');
        }
        evidence.append("normalize: ").append(evidenceFields.getOrDefault("normalize", "")).append('\n');
        evidence.append("ignore_order: ").append(evidenceFields.getOrDefault("ignore_order", false)).append('\n');
        evidence.append("comparison_status: ").append(passed ? "passed" : "failed").append('\n');
        evidence.append("diff_ref: ").append(diffRef).append('\n');
        if (!passed) {
            evidence.append("failure_code: ").append(failureCode).append('\n');
            evidence.append("reason: ").append(reason).append('\n');
            evidence.append("owner_action: ").append(ownerAction).append('\n');
        }
        evidence.append("masked_actual_sample: |\n");
        for (String line : stringValue(evidenceFields.get("masked_actual_sample")).split("\\R", -1)) {
            evidence.append("  ").append(line).append('\n');
        }
        evidence.append("masking:\n  raw_secret_found: ")
                .append(evidenceFields.getOrDefault("raw_secret_found", false))
                .append('\n');
        write(runDir.resolve(evidenceRef), evidence.toString());
    }

    private void writePollingEvidence(
            Path runDir,
            String evidenceRef,
            String id,
            String type,
            String expectedRef,
            String actualRef,
            Duration timeout,
            Duration pollInterval,
            int attempts,
            Instant started,
            Instant finished,
            Object lastObserved,
            String status,
            String failureCode,
            String consumeFrom) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("evidence_type: polling_result\n");
        evidence.append("evidence_classification: ").append(EVIDENCE_CLASSIFICATION).append('\n');
        evidence.append("downstream_release_evidence: false\n");
        evidence.append("assertion_id: ").append(id).append('\n');
        evidence.append("verifier_type: ").append(type).append('\n');
        evidence.append("expected_ref: ").append(expectedRef).append('\n');
        evidence.append("actual_ref: ").append(actualRef).append('\n');
        evidence.append("timeout: ").append(timeout).append('\n');
        evidence.append("poll_interval: ").append(pollInterval).append('\n');
        evidence.append("consume_from: ").append(consumeFrom).append('\n');
        evidence.append("attempts: ").append(attempts).append('\n');
        evidence.append("observation_started_at: ").append(started).append('\n');
        evidence.append("observation_finished_at: ").append(finished).append('\n');
        evidence.append("last_observed_value:\n");
        appendYamlValue(evidence, lastObserved, 2);
        evidence.append("final_status: ").append(status).append('\n');
        evidence.append("failure_code: ").append(failureCode).append('\n');
        evidence.append("masking:\n  raw_secret_found: false\n");
        write(runDir.resolve(evidenceRef), evidence.toString());
    }

    private void writeDiff(Path runDir, String diffRef, String content) {
        write(runDir.resolve(diffRef), content);
    }

    private void writeExecutionLog(
            Path runDir,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            String profile,
            String status,
            List<Map<String, Object>> verifyResults) {
        write(runDir.resolve("logs/execution.log"), """
                suite_id: %s
                test_case_id: %s
                profile: %s
                provider_runtime_executed: false
                verifier_count: %d
                status: %s
                """.formatted(
                suite.get("suite_id"),
                testCase.get("test_case_id"),
                profile,
                verifyResults.size(),
                status));
    }

    private void writeBatch(Path runDir, Map<String, Object> suite, Map<String, Object> testCase, RunIds ids, String profile, String status) {
        write(runDir.resolve("batch/batch.yaml"), """
                evidence_type: batch_summary
                evidence_classification: %s
                downstream_release_evidence: false
                suite_id: %s
                batch_id: %s
                run_id: %s
                test_case_id: %s
                profile: %s
                status: %s
                """.formatted(
                EVIDENCE_CLASSIFICATION,
                suite.get("suite_id"),
                ids.batchId(),
                ids.runId(),
                testCase.get("test_case_id"),
                profile,
                status));
    }

    private void writeEvidenceIndex(
            Path runDir,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            RunIds ids,
            String profile,
            TargetSelection selection,
            List<String> evidenceRefs) {
        write(runDir.resolve("evidence_index.yaml"), EvidenceIndexFormatter.format(
                new EvidenceIndexFormatter.Context(
                        stringValue(suite.get("suite_id")),
                        ids.batchId(),
                        ids.runId(),
                        stringValue(testCase.get("test_case_id")),
                        profile,
                        PROVIDER_TYPE,
                        selection.providerId()),
                runDir,
                evidenceRefs));
    }

    private Path writeResult(
            Path runDir,
            Map<String, Object> suite,
            Map<String, Object> testCase,
            RunIds ids,
            String profile,
            TargetSelection selection,
            String status,
            List<Map<String, Object>> stepResults,
            List<Map<String, Object>> verifyResults,
            List<String> evidenceRefs,
            Failure failure,
            Instant startedAt,
            Instant finishedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework_version", "v0.2");
        result.put("dsl_version", testCase.get("dsl_version"));
        result.put("suite_id", suite.get("suite_id"));
        result.put("batch_id", ids.batchId());
        result.put("run_id", ids.runId());
        result.put("test_case_id", testCase.get("test_case_id"));
        result.put("profile", profile);
        result.put("environment", profile);
        result.put("provider_type", PROVIDER_TYPE);
        result.put("provider_id", selection.providerId());
        result.put("runtime_mode", selection.runtimeMode());
        result.put("status", status);
        result.put("start_time", startedAt.toString());
        result.put("end_time", finishedAt.toString());
        result.put("duration_ms", Duration.between(startedAt, finishedAt).toMillis());
        result.put("timestamps", Map.of("started_at", startedAt.toString(), "finished_at", finishedAt.toString()));
        result.put("labels", testCase.get("labels"));
        result.put("source_refs", testCase.get("source_refs"));
        result.put("step_results", stepResults);
        result.put("steps", stepResults);
        result.put("verify_results", verifyResults);
        result.put("provider_results", List.of(providerResult(selection, profile, status, verifyResults)));
        result.put("evidence_refs", evidenceRefs);
        Map<String, Object> failureObject = new LinkedHashMap<>();
        failureObject.put("code", failure == null ? null : failure.code());
        failureObject.put("classification", failure == null ? null : failure.classification());
        failureObject.put("reason", failure == null ? null : failure.reason());
        failureObject.put("owner_action", failure == null ? null : failure.ownerAction());
        result.put("failure", failureObject);
        Path resultJson = runDir.resolve("result.json");
        write(resultJson, toJson(result) + "\n");
        return resultJson;
    }

    private Map<String, Object> providerResult(
            TargetSelection selection,
            String profile,
            String status,
            List<Map<String, Object>> verifyResults) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider_id", selection.providerId());
        result.put("provider_type", PROVIDER_TYPE);
        result.put("profile", profile);
        result.put("runtime_mode", selection.runtimeMode());
        result.put("resolved_operation_result", Map.of(
                "operation", "common_verifier_flow",
                "status", status,
                "outputs", Map.of("verify_results_count", verifyResults.size())));
        result.put("release_evidence_eligible", false);
        return result;
    }

    private void appendYamlValue(StringBuilder builder, Object value, int indent) {
        String prefix = " ".repeat(indent);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                builder.append(prefix).append(entry.getKey()).append(":");
                if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof Collection<?>) {
                    builder.append('\n');
                    appendYamlValue(builder, entry.getValue(), indent + 2);
                } else {
                    builder.append(' ').append(entry.getValue()).append('\n');
                }
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                builder.append(prefix).append("- ");
                if (item instanceof Map<?, ?> || item instanceof Collection<?>) {
                    builder.append('\n');
                    appendYamlValue(builder, item, indent + 2);
                } else {
                    builder.append(item).append('\n');
                }
            }
            return;
        }
        builder.append(prefix).append(value).append('\n');
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
            throw new UncheckedIOException("Failed to read common verifier artifact: " + path, e);
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
        List<String> strings = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                strings.add(stringValue(item));
            }
        }
        return List.copyOf(strings);
    }

    private String firstText(Map<String, Object> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String verifyId(Map<String, Object> verify) {
        String id = stringValue(verify.get("id"));
        return id.isBlank() ? "verify_" + Math.abs(verify.hashCode()) : safe(id);
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(stringValue(value));
    }

    private Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Duration.parse(value);
    }

    private void sleep(Duration pollInterval) {
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            return;
        }
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String maskedSample(Object value) {
        String text = value instanceof String string ? string : toJson(value);
        return text.length() <= 300 ? text : text.substring(0, 300);
    }

    private MaskedValue maskValue(Object value) {
        return maskValue(value, false);
    }

    private MaskedValue maskValue(Object value, boolean secretContext) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            boolean rawSecretFound = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                boolean childSecretContext = secretContext || secretKey(key);
                MaskedValue child = maskValue(entry.getValue(), childSecretContext);
                masked.put(key, child.value());
                rawSecretFound = rawSecretFound || child.rawSecretFound();
            }
            return new MaskedValue(masked, rawSecretFound);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> masked = new ArrayList<>();
            boolean rawSecretFound = false;
            for (Object item : collection) {
                MaskedValue child = maskValue(item, secretContext);
                masked.add(child.value());
                rawSecretFound = rawSecretFound || child.rawSecretFound();
            }
            return new MaskedValue(masked, rawSecretFound);
        }
        if (secretContext && value != null && !stringValue(value).isBlank() && !safeSecretReference(stringValue(value))) {
            return new MaskedValue("***MASKED***", true);
        }
        if (value instanceof String text) {
            MaskedText masked = maskText(text);
            return new MaskedValue(masked.text(), masked.rawSecretFound());
        }
        return new MaskedValue(value, false);
    }

    private MaskedText maskText(String value) {
        String masked = value
                .replaceAll("(?i)(\"(?:password|token|api_key|authorization|secret)\"\\s*:\\s*\")[^\"]*(\")", "$1***MASKED***$2")
                .replaceAll("(?i)(password\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("(?i)(token\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("(?i)(api_key\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("(?i)(authorization\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("(?i)(secret\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***");
        return new MaskedText(masked, !masked.equals(value));
    }

    private boolean secretKey(String key) {
        String normalized = key.toLowerCase();
        return normalized.equals("password")
                || normalized.equals("token")
                || normalized.equals("credential")
                || normalized.equals("credentials")
                || normalized.equals("secret")
                || normalized.equals("connection")
                || normalized.equals("api_key")
                || normalized.equals("authorization");
    }

    private boolean safeSecretReference(String value) {
        return value.startsWith("secret://")
                || value.startsWith("vault://")
                || value.startsWith("generated://")
                || value.startsWith("${");
    }

    private List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private RunIds runIds(String prefix) {
        String suffix = String.valueOf(System.currentTimeMillis());
        return new RunIds("BATCH-" + prefix + "-" + suffix, "RUN-" + prefix + "-" + suffix);
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
                throw new UncheckedIOException("Failed to clean generated common verifier output: " + directory, e);
            }
        }
        createDirectories(directory);
    }

    private void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create generated common verifier output: " + path, e);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write common verifier output: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    public record CommonVerifyRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            String profile,
            Path resultJson,
            Path evidenceDir,
            boolean providerRuntimeExecuted,
            String providerType,
            String providerId,
            List<ContractFinding> findings) {

        static CommonVerifyRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new CommonVerifyRunResult(
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
                    PROVIDER_TYPE,
                    "",
                    List.copyOf(findings));
        }
    }

    private record TargetSelection(String targetName, String providerId, String runtimeMode) {
    }

    private record RunIds(String batchId, String runId) {
    }

    private record Failure(String code, String classification, String reason, String ownerAction) {
    }

    private record VerifyOutcome(
            boolean passed,
            Map<String, Object> result,
            List<String> evidenceRefs,
            String failureCode,
            String reason,
            String ownerAction,
            boolean polling) {
    }

    private record ArtifactValue(boolean available, Object value, String failureCode) {
    }

    private record ArtifactText(boolean available, String text, String failureCode) {
    }

    private record MaskedValue(Object value, boolean rawSecretFound) {
    }

    private record MaskedText(String text, boolean rawSecretFound) {
    }
}
