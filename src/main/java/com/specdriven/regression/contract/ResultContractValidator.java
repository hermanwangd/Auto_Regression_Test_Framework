package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ResultContractValidator {

    private static final List<String> STANDARD_RESULT_REQUIRED_FIELDS = List.of(
            "framework_version",
            "dsl_version",
            "suite_id",
            "batch_id",
            "run_id",
            "test_case_id",
            "test_count",
            "status",
            "profile",
            "environment",
            "start_time",
            "end_time",
            "duration_ms",
            "timestamps",
            "test_results",
            "provider_results",
            "steps",
            "verify_results",
            "evidence_refs",
            "failure");

    private ResultContractValidator() {
    }

    public static List<ContractFinding> validate(Path resultJson, Map<String, Object> result) {
        List<ContractFinding> findings = new ArrayList<>();
        String resultContractVersion = stringValue(result.get("result_contract_version"));
        boolean v03Result = "v0.3".equals(resultContractVersion);
        if (!resultContractVersion.isBlank() && !v03Result) {
            findings.add(finding(resultJson, "result_contract_version", "unsupported_result_contract_version",
                    stringValue(result.get("profile")),
                    "Publish result_contract_version `v0.3`, or omit it only for a legacy v0.2 result."));
        }
        boolean hasTestCount = result.containsKey("test_count");
        boolean hasTestResults = result.containsKey("test_results");
        List<Map<?, ?>> providerResults = listOfMaps(result.get("provider_results"));
        boolean standardSuiteResult = v03Result || hasTestCount || hasTestResults || !providerResults.isEmpty();
        if (!standardSuiteResult) {
            return findings;
        }
        String profile = stringValue(result.get("profile"));
        for (String field : STANDARD_RESULT_REQUIRED_FIELDS) {
            boolean missing = !result.containsKey(field)
                    || (!"failure".equals(field) && isMissing(result.get(field)));
            if (missing) {
                findings.add(finding(resultJson, field, "missing_required_field", profile,
                        "Add required standard result field `" + field + "`."));
            }
        }
        if (v03Result) {
            for (String field : List.of("completion_status", "termination_reason", "suite_summary_ref")) {
                boolean missing = !result.containsKey(field)
                        || (!"termination_reason".equals(field) && isMissing(result.get(field)));
                if (missing) {
                    findings.add(finding(resultJson, field, "missing_required_field", profile,
                            "Add required v0.3 result field `" + field + "`."));
                }
            }
            findings.addAll(validateV03Completion(resultJson, result, profile));
            findings.addAll(validateSuiteSummaryRef(resultJson, result, profile));
        }
        if (!hasTestResults) {
            findings.addAll(validateMultiProviderRootFields(resultJson, result, List.of(), providerResults, profile));
            return findings;
        }
        Object rawTestResults = result.get("test_results");
        if (!(rawTestResults instanceof Collection<?>)) {
            findings.add(finding(resultJson, "test_results", "invalid_test_results", profile,
                    "Publish `test_results` as an array of per-test result objects."));
            return findings;
        }

        List<Object> rawEntries = new ArrayList<>((Collection<?>) rawTestResults);
        for (int index = 0; index < rawEntries.size(); index++) {
            if (!(rawEntries.get(index) instanceof Map<?, ?>)) {
                findings.add(finding(resultJson, "test_results[" + index + "]", "invalid_test_results", profile,
                        "Publish each `test_results[]` entry as an object with test_case_id, status, and profile."));
            }
        }
        if (v03Result && isAggregationResult(result, rawEntries)) {
            findings.addAll(validateAggregationTestResultIdentities(resultJson, rawEntries, profile));
        }
        if (hasTestCount) {
            Integer declaredTestCount = integerValue(result.get("test_count"));
            if (declaredTestCount == null || declaredTestCount <= 0) {
                findings.add(finding(resultJson, "test_count", "invalid_test_count", profile,
                        "Set `test_count` to a positive JSON integer value matching `test_results.length`; do not quote it."));
            } else if (declaredTestCount != rawEntries.size()) {
                findings.add(finding(resultJson, "test_count", "invalid_test_count", profile,
                        "Set `test_count` to match the number of entries in `test_results`."));
            }
        }

        for (int index = 0; index < rawEntries.size(); index++) {
            Object rawEntry = rawEntries.get(index);
            if (!(rawEntry instanceof Map<?, ?> rawMap)) {
                continue;
            }
            for (String field : List.of("test_case_id", "status", "profile")) {
                if (isMissing(rawMap.get(field))) {
                    findings.add(finding(resultJson, "test_results[" + index + "]." + field,
                            "missing_required_field", profile,
                            "Add required per-test result field `" + field + "`."));
                }
            }
            String status = stringValue(rawMap.get("status"));
            List<String> allowedStatuses = v03Result
                    ? List.of("passed", "failed", "blocked", "skipped")
                    : List.of("passed", "failed", "blocked");
            if (!status.isBlank() && !allowedStatuses.contains(status)) {
                findings.add(finding(resultJson, "test_results[" + index + "].status", "invalid_status", profile,
                        v03Result
                                ? "Use v0.3 per-test status `passed`, `failed`, `blocked`, or `skipped`."
                                : "Use per-test status `passed`, `failed`, or `blocked`."));
            }
        }
        findings.addAll(validateMultiProviderRootFields(resultJson, result, rawEntries, providerResults, profile));
        findings.addAll(validateV03ProviderResults(resultJson, result, providerResults, profile));
        findings.addAll(validateProviderEvidenceRefs(resultJson, result, profile));
        return List.copyOf(findings);
    }

    private static List<ContractFinding> validateV03Completion(
            Path resultJson,
            Map<String, Object> result,
            String profile) {
        List<ContractFinding> findings = new ArrayList<>();
        String completionStatus = stringValue(result.get("completion_status"));
        String status = stringValue(result.get("status"));
        Object terminationReason = result.get("termination_reason");
        if (!completionStatus.isBlank() && !List.of("complete", "partial").contains(completionStatus)) {
            findings.add(finding(resultJson, "completion_status", "invalid_completion_status", profile,
                    "Use v0.3 completion_status `complete` or `partial`."));
            return findings;
        }
        if (!status.isBlank() && !List.of("passed", "failed", "blocked", "skipped").contains(status)) {
            findings.add(finding(resultJson, "status", "invalid_status", profile,
                    "Use v0.3 result status `passed`, `failed`, `blocked`, or `skipped`."));
        }
        if ("complete".equals(completionStatus) && terminationReason != null) {
            findings.add(finding(resultJson, "termination_reason", "invalid_termination_reason", profile,
                    "Set termination_reason to null when completion_status is `complete`."));
        }
        if ("partial".equals(completionStatus)) {
            if (!"blocked".equals(status)) {
                findings.add(finding(resultJson, "status", "invalid_completion_status_tuple", profile,
                        "Set status to `blocked` when completion_status is `partial`."));
            }
            String reason = stringValue(terminationReason);
            if (!List.of("timeout", "cancelled", "framework_error", "aggregation_error").contains(reason)) {
                findings.add(finding(resultJson, "termination_reason", "invalid_termination_reason", profile,
                        "For a partial result use termination_reason `timeout`, `cancelled`, `framework_error`, or `aggregation_error`."));
            }
        }
        return findings;
    }

    private static List<ContractFinding> validateSuiteSummaryRef(
            Path resultJson,
            Map<String, Object> result,
            String profile) {
        String ref = stringValue(result.get("suite_summary_ref"));
        if (ref.isBlank()) {
            return List.of();
        }
        Path refPath = Path.of(ref);
        if (refPath.isAbsolute()) {
            return List.of(invalidSummaryRef(resultJson, profile,
                    "Use a relative suite_summary_ref; absolute paths are not allowed."));
        }
        if (ref.startsWith("~")) {
            return List.of(invalidSummaryRef(resultJson, profile,
                    "Use a literal relative suite_summary_ref; home expansion with `~` is not allowed."));
        }
        for (Path segment : refPath) {
            if ("..".equals(segment.toString())) {
                String action = refPath.normalize().equals(refPath)
                        ? "Remove parent traversal `..` segments from suite_summary_ref."
                        : "Use a normalized relative suite_summary_ref without `.` or `..` segments.";
                return List.of(invalidSummaryRef(resultJson, profile, action));
            }
        }
        if (!refPath.normalize().equals(refPath) || !ref.equals(refPath.toString().replace('\\', '/'))) {
            return List.of(invalidSummaryRef(resultJson, profile,
                    "Use a normalized relative suite_summary_ref without redundant path segments."));
        }
        Path resultParent = resultJson == null ? null : resultJson.toAbsolutePath().normalize().getParent();
        if (resultParent == null || !Files.isDirectory(resultParent)) {
            return List.of();
        }
        Path referencedSummary = resultParent.resolve(refPath);
        if (!Files.exists(referencedSummary)) {
            return List.of();
        }
        try {
            Path realParent = resultParent.toRealPath();
            if (!referencedSummary.toRealPath().startsWith(realParent)) {
                return List.of(invalidSummaryRef(resultJson, profile,
                        "Point suite_summary_ref to a file whose real path is inside the result directory real path."));
            }
        } catch (IOException e) {
            return List.of(invalidSummaryRef(resultJson, profile,
                    "Make suite_summary_ref resolvable inside the result directory: " + e.getMessage()));
        }
        return List.of();
    }

    private static ContractFinding invalidSummaryRef(Path resultJson, String profile, String ownerAction) {
        return finding(resultJson, "suite_summary_ref", "invalid_suite_summary_ref", profile, ownerAction);
    }

    private static List<ContractFinding> validateV03ProviderResults(
            Path resultJson,
            Map<String, Object> result,
            List<Map<?, ?>> providerResults,
            String profile) {
        if (!"v0.3".equals(stringValue(result.get("result_contract_version")))) {
            return List.of();
        }
        List<ContractFinding> findings = new ArrayList<>();
        for (int index = 0; index < providerResults.size(); index++) {
            Map<?, ?> providerResult = providerResults.get(index);
            String fieldPrefix = "provider_results[" + index + "]";
            for (String field : List.of(
                    "target",
                    "provider_contract",
                    "provider_type",
                    "profile",
                    "runtime_mode",
                    "operation",
                    "status",
                    "evidence_refs")) {
                if (isMissing(providerResult.get(field))) {
                    findings.add(finding(resultJson, fieldPrefix + "." + field, "missing_required_field", profile,
                            "Add v0.3 provider result field `" + field + "` without Provider Instance refs."));
                }
            }
            String status = stringValue(providerResult.get("status"));
            if (!status.isBlank() && !List.of("passed", "failed", "blocked").contains(status)) {
                findings.add(finding(resultJson, fieldPrefix + ".status", "invalid_status", profile,
                        "Use provider result status `passed`, `failed`, or `blocked`."));
            }
            if ("failed".equals(status) && isMissing(providerResult.get("failure_code"))) {
                findings.add(finding(resultJson, fieldPrefix + ".failure_code", "missing_failure_code", profile,
                        "Publish a v0.3 provider result failure_code when status is failed."));
            }
            if (!(providerResult.get("evidence_refs") instanceof Collection<?>)) {
                findings.add(finding(resultJson, fieldPrefix + ".evidence_refs", "invalid_evidence_refs", profile,
                        "Publish v0.3 provider result `evidence_refs` as an array."));
            }
            if (providerResult.containsKey("provider_instance")
                    || providerResult.containsKey("provider_instance_ref")) {
                findings.add(finding(resultJson, fieldPrefix, "prohibited_provider_instance_ref", profile,
                        "Remove Provider Instance refs from v0.3 result JSON; use target and provider_contract."));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isAggregationResult(Map<String, Object> result, List<Object> testResults) {
        return stringValue(result.get("test_case_id")).endsWith("-AGGREGATE")
                || testResults.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .anyMatch(entry -> entry.containsKey("test_result_id") || entry.containsKey("suite_path"));
    }

    private static List<ContractFinding> validateAggregationTestResultIdentities(
            Path resultJson,
            List<Object> testResults,
            String profile) {
        List<ContractFinding> findings = new ArrayList<>();
        Set<String> testResultIds = new LinkedHashSet<>();
        for (int index = 0; index < testResults.size(); index++) {
            if (!(testResults.get(index) instanceof Map<?, ?> entry)) {
                continue;
            }
            String suitePath = stringValue(entry.get("suite_path"));
            if (suitePath.isBlank()) {
                findings.add(finding(resultJson, "test_results[" + index + "].suite_path",
                        "missing_required_field", profile,
                        "Add a non-empty aggregation `suite_path` identifying the immediate child suite."));
            }
            String testResultId = stringValue(entry.get("test_result_id"));
            if (testResultId.isBlank()) {
                findings.add(finding(resultJson, "test_results[" + index + "].test_result_id",
                        "missing_required_field", profile,
                        "Add a non-empty globally unique aggregation `test_result_id`."));
            } else if (!testResultIds.add(testResultId)) {
                findings.add(finding(resultJson, "test_results[" + index + "].test_result_id",
                        "duplicate_test_result_id", profile,
                        "Use a globally unique `test_result_id` formed from suite_path and test_case_id."));
            }
        }
        return findings;
    }

    private static List<ContractFinding> validateProviderEvidenceRefs(
            Path resultJson,
            Map<String, Object> result,
            String profile) {
        Object refs = result.get("provider_evidence_refs");
        if (refs == null) {
            return List.of();
        }
        if (!(refs instanceof Collection<?> collection)) {
            return List.of(finding(resultJson, "provider_evidence_refs", "invalid_provider_evidence_refs", profile,
                    "Publish `provider_evidence_refs` as an array of provider-produced evidence refs."));
        }
        List<ContractFinding> findings = new ArrayList<>();
        int index = 0;
        for (Object ref : collection) {
            String fieldPath = "provider_evidence_refs[" + index + "]";
            if (!(ref instanceof String text) || text.isBlank()) {
                findings.add(finding(resultJson, fieldPath, "invalid_provider_evidence_ref", profile,
                        "Use non-empty string refs in `provider_evidence_refs`."));
            } else if (!ProviderCapabilityResultWriter.providerEvidenceRefs(List.of(text)).contains(text)) {
                findings.add(finding(resultJson, fieldPath, "invalid_provider_evidence_ref", profile,
                        "Keep framework logs, batch summaries, assertion diffs, and expected artifacts in `evidence_refs`; reserve `provider_evidence_refs` for provider-produced evidence."));
            }
            index++;
        }
        return findings;
    }

    private static List<ContractFinding> validateMultiProviderRootFields(
            Path resultJson,
            Map<String, Object> result,
            List<Object> rawTestResults,
            List<Map<?, ?>> providerResults,
            String profile) {
        List<ContractFinding> findings = new ArrayList<>();
        Set<String> providerKeys = new LinkedHashSet<>();
        rawTestResults.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .forEach(testResult -> addProviderKey(providerKeys, testResult));
        providerResults.forEach(providerResult -> addProviderKey(providerKeys, providerResult));
        if (providerKeys.size() <= 1) {
            return findings;
        }
        Object providerSummary = result.get("provider_summary");
        if (providerSummary == null || providerSummary instanceof Collection<?> collection && collection.isEmpty()) {
            findings.add(finding(resultJson, "provider_summary", "missing_required_field", profile,
                    "Add `provider_summary[]` for multi-provider result JSON so suite-level reporting does not imply one provider describes the whole suite."));
        } else if (!(providerSummary instanceof Collection<?>)) {
            findings.add(finding(resultJson, "provider_summary", "invalid_provider_summary", profile,
                    "Publish `provider_summary` as an array of provider summary objects."));
        }
        for (String field : List.of("provider_id", "provider_type", "topic", "queue")) {
            if (result.containsKey(field)) {
                findings.add(finding(resultJson, field, "invalid_provider_summary", profile,
                        "Remove top-level `" + field
                                + "` from multi-provider result JSON; use `provider_summary[]`, `test_results[]`, and `provider_results[]`."));
            }
        }
        return findings;
    }

    private static void addProviderKey(Set<String> providerKeys, Map<?, ?> providerLike) {
        String providerKey = stringValue(providerLike.get("provider_type")) + "\n"
                + firstNonBlank(stringValue(providerLike.get("provider_id")), stringValue(providerLike.get("target")));
        if (!providerKey.trim().isBlank()) {
            providerKeys.add(providerKey);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<Map<?, ?>> listOfMaps(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>();
        for (Object item : collection) {
            if (item instanceof Map<?, ?> map) {
                maps.add(map);
            }
        }
        return maps;
    }

    private static ContractFinding finding(
            Path resultJson,
            String fieldPath,
            String reason,
            String profile,
            String ownerAction) {
        return new ContractFinding(
                resultJson == null ? "" : resultJson.toString(),
                fieldPath,
                reason,
                "",
                "",
                profile,
                "",
                ownerAction);
    }

    private static boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.isBlank();
        }
        return false;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            int intValue = number.intValue();
            return Double.isFinite(doubleValue) && doubleValue == intValue ? intValue : null;
        }
        return null;
    }
}
