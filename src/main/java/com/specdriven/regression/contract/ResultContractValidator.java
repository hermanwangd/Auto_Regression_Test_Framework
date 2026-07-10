package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
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
        boolean hasTestCount = result.containsKey("test_count");
        boolean hasTestResults = result.containsKey("test_results");
        List<Map<?, ?>> providerResults = listOfMaps(result.get("provider_results"));
        boolean standardSuiteResult = hasTestCount
                || hasTestResults
                || !providerResults.isEmpty()
                || result.containsKey("batch_id")
                || result.containsKey("run_id");
        if (!standardSuiteResult) {
            return findings;
        }
        String profile = stringValue(result.get("profile"));
        for (String field : STANDARD_RESULT_REQUIRED_FIELDS) {
            if (!result.containsKey(field) || isMissing(result.get(field))) {
                findings.add(finding(resultJson, field, "missing_required_field", profile,
                        "Add required standard result field `" + field + "`."));
            }
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
            if (!status.isBlank() && !List.of("passed", "failed", "blocked").contains(status)) {
                findings.add(finding(resultJson, "test_results[" + index + "].status", "invalid_status", profile,
                        "Use per-test status `passed`, `failed`, or `blocked`."));
            }
        }
        findings.addAll(validateMultiProviderRootFields(resultJson, result, rawEntries, providerResults, profile));
        findings.addAll(validateProviderEvidenceRefs(resultJson, result, profile));
        return List.copyOf(findings);
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
