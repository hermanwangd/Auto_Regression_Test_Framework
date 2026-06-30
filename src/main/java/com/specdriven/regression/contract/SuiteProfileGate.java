package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SuiteProfileGate {

    private SuiteProfileGate() {
    }

    static List<ContractFinding> validate(
            Path suitePath,
            Map<String, Object> suite,
            List<TestCaseDocument> testCases,
            String requestedProfile,
            String providerType) {
        List<ContractFinding> findings = new ArrayList<>();
        List<String> selectedProfiles = selectedProfiles(suite);
        if (!selectedProfiles.isEmpty() && !selectedProfiles.contains(requestedProfile)) {
            findings.add(new ContractFinding(
                    suitePath.toString(),
                    "profile",
                    "profile_mismatch",
                    "",
                    providerType,
                    requestedProfile,
                    "",
                    "Run with a profile selected by the suite manifest: " + selectedProfiles + "."));
        }
        for (TestCaseDocument testCase : testCases) {
            List<String> compatibleProfiles = stringList(testCase.document().get("compatible_profiles"));
            if (!compatibleProfiles.isEmpty() && !compatibleProfiles.contains(requestedProfile)) {
                findings.add(new ContractFinding(
                        testCase.path().toString(),
                        "compatible_profiles",
                        "profile_mismatch",
                        "",
                        providerType,
                        requestedProfile,
                        "",
                        "Run with a profile allowed by compatible_profiles: " + compatibleProfiles + "."));
            }
        }
        return List.copyOf(findings);
    }

    private static List<String> selectedProfiles(Map<String, Object> suite) {
        List<String> profiles = new ArrayList<>();
        addIfPresent(profiles, stringValue(suite.get("profile")));
        profiles.addAll(stringList(suite.get("profiles")));
        addIfPresent(profiles, stringValue(mapValue(suite.get("selection")).get("profile")));
        return profiles.stream().distinct().toList();
    }

    private static void addIfPresent(List<String> values, String value) {
        if (!value.isBlank()) {
            values.add(value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static List<String> stringList(Object value) {
        return listValue(value).stream().map(SuiteProfileGate::stringValue).filter(text -> !text.isBlank()).toList();
    }

    private static List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    record TestCaseDocument(Path path, Map<String, Object> document) {
    }
}
