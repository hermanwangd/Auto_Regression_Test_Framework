package com.specdriven.regression.application;

import static com.specdriven.regression.application.SuiteDocuments.mapValue;
import static com.specdriven.regression.application.SuiteDocuments.objectList;
import static com.specdriven.regression.application.SuiteDocuments.readYamlMap;
import static com.specdriven.regression.application.SuiteDocuments.stringValue;

import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.DryRunResult;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.contract.v03.V03ExecutionPlan;
import com.specdriven.regression.contract.v03.V03ExecutionPlanBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** Validates suite graphs and compiles command-scoped v0.3 plans without entering a provider. */
public final class SuiteValidation {

    private final ContractBaselineService contractBaselineService = new ContractBaselineService();

    /** Validation and dry-run share the same command-scoped plan and findings. */
    public record Leaf(ValidationResult baseline, V03ExecutionPlan plan, List<ContractFinding> findings) {}

    public Leaf checkLeaf(Path manifest, String profile, String selectedTestCase) {
        ValidationResult baseline = contractBaselineService.validateSuite(manifest);
        List<ContractFinding> findings = new ArrayList<>(baseline.findings());
        findings.addAll(suiteProfileFindings(manifest, profile));
        V03ExecutionPlan plan = compileV03IfReady(manifest, profile, baseline, findings, selectedTestCase);
        return new Leaf(baseline, plan, List.copyOf(findings));
    }

    ValidationResult validateForExecution(Path manifest) {
        return contractBaselineService.validateForExecution(manifest);
    }

    DryRunResult compatibilityDryRun(Path manifest) {
        return contractBaselineService.dryRun(manifest);
    }

    public SuiteGroupValidation checkGroup(Path manifest, String profile) {
        SuiteGroupValidation group = validateSuiteGroupArtifacts(manifest, false);
        List<ContractFinding> findings = new ArrayList<>(group.findings());
        findings.addAll(suiteGroupProfileFindings(manifest, group.definition(), profile));
        return new SuiteGroupValidation(group.definition(), group.providerInstances(), group.providerTypes(), findings);
    }

    public boolean isSuiteGroupManifest(Path suiteManifest) {
        if (!Files.isRegularFile(suiteManifest)) {
            return false;
        }
        try {
            Object loaded = new Yaml().load(Files.readString(suiteManifest));
            return loaded instanceof Map<?, ?> map
                    && (map.containsKey("child_suites")
                            || ("suite_group".equals(stringValue(map.get("suite_type")))
                                    && map.containsKey("test_cases")));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private List<ContractFinding> suiteGroupProfileFindings(
            Path suiteManifest,
            SuiteGroupDefinition definition,
            String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return List.of();
        }
        if (!definition.profile().isBlank() && !definition.profile().equals(requestedProfile)) {
            return List.of(finding(
                    suiteManifest,
                    "profile",
                    "profile_mismatch",
                    "Run with an allowed profile: [" + definition.profile() + "]."));
        }
        return List.of();
    }

    private List<ContractFinding> suiteProfileFindings(Path suiteManifest, String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank() || !Files.isRegularFile(suiteManifest)) {
            return List.of();
        }
        Map<String, Object> suite;
        try {
            suite = readYamlMap(suiteManifest);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ContractFinding> findings = new ArrayList<>();
        List<String> selectedProfiles = selectedProfiles(suite);
        if (!selectedProfiles.isEmpty() && !selectedProfiles.contains(requestedProfile)) {
            findings.add(finding(
                    suiteManifest,
                    "profile",
                    "profile_mismatch",
                    "Run with an allowed profile: " + selectedProfiles + "."));
        }
        Path suiteRoot = suiteDirectory(suiteManifest);
        for (Object testRef : objectList(suite.get("tests"))) {
            Path testCasePath = suiteRoot.resolve(stringValue(testRef)).normalize();
            if (!Files.isRegularFile(testCasePath)) {
                continue;
            }
            Map<String, Object> testCase;
            try {
                testCase = readYamlMap(testCasePath);
            } catch (RuntimeException e) {
                continue;
            }
            List<String> compatibleProfiles = stringListValue(testCase.get("compatible_profiles"));
            if (!compatibleProfiles.isEmpty() && !compatibleProfiles.contains(requestedProfile)) {
                findings.add(finding(
                        testCasePath,
                        "compatible_profiles",
                        "profile_mismatch",
                        "Run with an allowed profile: " + compatibleProfiles + "."));
            }
            for (Map.Entry<String, Object> entry : mapValue(testCase.get("targets")).entrySet()) {
                Map<String, Object> target = mapValue(entry.getValue());
                String targetProfile = stringValue(target.get("profile"));
                if (targetProfile.isBlank() || targetProfile.equals(requestedProfile)) {
                    continue;
                }
                findings.add(finding(
                        testCasePath,
                        "targets." + entry.getKey() + ".profile",
                        "conflicting_profile_selection",
                        "Remove deprecated target profile or run with profile `" + targetProfile + "`."));
            }
        }
        return List.copyOf(findings);
    }

    private List<String> selectedProfiles(Map<String, Object> suite) {
        List<String> profiles = new ArrayList<>();
        addProfile(profiles, stringValue(suite.get("profile")));
        profiles.addAll(stringListValue(suite.get("profiles")));
        Object selection = suite.get("selection");
        if (selection instanceof Map<?, ?> map) {
            addProfile(profiles, stringValue(map.get("profile")));
        }
        return profiles.stream().distinct().toList();
    }

    private void addProfile(List<String> profiles, String profile) {
        if (!profile.isBlank()) {
            profiles.add(profile);
        }
    }

    private List<String> stringListValue(Object value) {
        return objectList(value).stream().map(SuiteDocuments::stringValue).filter(text -> !text.isBlank()).toList();
    }

    SuiteGroupValidation validateSuiteGroupArtifacts(Path suiteManifest, boolean forExecution) {
        Map<String, Object> manifest = readYamlMap(suiteManifest);
        List<ContractFinding> findings = new ArrayList<>();
        SuiteGroupDefinition definition = suiteGroupDefinition(suiteManifest, manifest, findings);
        List<String> providerInstances = new ArrayList<>();
        List<String> providerTypes = new ArrayList<>();
        for (SuiteGroupChild child : definition.children()) {
            if (!Files.isRegularFile(child.suiteManifest())) {
                findings.add(finding(
                        child.suiteManifest(),
                        child.fieldPath() + ".ref",
                        "missing_required_file",
                        "Create or correct the child suite manifest referenced by the suite group."));
                continue;
            }
            ValidationResult childValidation = forExecution
                    ? contractBaselineService.validateForExecution(child.suiteManifest())
                    : contractBaselineService.validateSuite(child.suiteManifest());
            addUnique(providerInstances, childValidation.providerInstancesUsed());
            addUnique(providerTypes, childValidation.providerTypesUsed());
            if (!childValidation.valid()) {
                findings.addAll(childValidation.findings());
            }
        }
        return new SuiteGroupValidation(definition, providerInstances, providerTypes, findings);
    }

    private SuiteGroupDefinition suiteGroupDefinition(
            Path suiteManifest,
            Map<String, Object> manifest,
            List<ContractFinding> findings) {
        String suiteId = stringValue(manifest.get("suite_id"));
        if (suiteId.isBlank()) {
            findings.add(finding(suiteManifest, "suite_id", "missing_required_field", "Set suite_id on the suite group manifest."));
        }
        String profile = stringValue(manifest.get("profile"));
        if (profile.isBlank()) {
            findings.add(finding(suiteManifest, "profile", "missing_required_field", "Set the default suite group profile."));
        }
        String childrenField = manifest.containsKey("child_suites") ? "child_suites" : "test_cases";
        Object childrenValue = manifest.get(childrenField);
        if (!(childrenValue instanceof List<?> entries) || entries.isEmpty()) {
            findings.add(finding(
                    suiteManifest,
                    childrenField,
                    "missing_required_field",
                    "List at least one child suite manifest."));
            return new SuiteGroupDefinition(suiteId, profile, List.of());
        }
        Path suiteRoot = suiteDirectory(suiteManifest);
        List<SuiteGroupChild> children = new ArrayList<>();
        List<String> seenChildIds = new ArrayList<>();
        int index = 0;
        for (Object entry : entries) {
            String fieldPath = childrenField + "[" + index + "]";
            if (!(entry instanceof Map<?, ?> map)) {
                findings.add(finding(suiteManifest, fieldPath, "invalid_field_type", "Use a map for each child suite entry."));
                index++;
                continue;
            }
            String id = stringValue(map.get("id"));
            String ref = stringValue(map.get("ref"));
            String childProfile = stringValue(map.get("profile"));
            String expectedStatus = stringValue(map.get("expected_status"));
            if (expectedStatus.isBlank()) {
                expectedStatus = "passed";
            }
            if (id.isBlank()) {
                findings.add(finding(suiteManifest, fieldPath + ".id", "missing_required_field", "Set a stable child test id."));
            } else if (seenChildIds.contains(id)) {
                findings.add(finding(
                        suiteManifest,
                        fieldPath + ".id",
                        "duplicate_child_id",
                        "Use a unique child test id so suite summaries and Allure results cannot overwrite each other."));
            } else {
                seenChildIds.add(id);
            }
            if (ref.isBlank()) {
                findings.add(finding(suiteManifest, fieldPath + ".ref", "missing_required_field", "Set child suite manifest ref."));
            }
            if (childProfile.isBlank()) {
                findings.add(finding(suiteManifest, fieldPath + ".profile", "missing_required_field", "Set child execution profile."));
            }
            if (!List.of("passed", "failed").contains(expectedStatus)) {
                findings.add(finding(
                        suiteManifest,
                        fieldPath + ".expected_status",
                        "unsupported_expected_status",
                        "Use expected_status passed or failed."));
            }
            if (ref.isBlank()) {
                index++;
                continue;
            }
            Path childSuite = suiteRoot.resolve(ref).normalize();
            if (!childSuite.startsWith(suiteRoot)) {
                findings.add(finding(
                        suiteManifest,
                        fieldPath + ".ref",
                        "invalid_child_suite_ref",
                        "Keep child suite refs under the aggregation manifest directory."));
                index++;
                continue;
            }
            children.add(new SuiteGroupChild(id, ref, childSuite, childProfile, expectedStatus, fieldPath));
            index++;
        }
        return new SuiteGroupDefinition(suiteId, profile, List.copyOf(children));
    }

    private void addUnique(List<String> target, List<String> values) {
        for (String value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private Path suiteDirectory(Path suiteManifest) {
        Path normalized = suiteManifest.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    static ContractFinding finding(Path file, String fieldPath, String reason, String ownerAction) {
        return new ContractFinding(file.toString(), fieldPath, reason, "", "", "", "", ownerAction);
    }

    public static boolean isV03Validation(ValidationResult validation) {
        return validation.providerContractsUsed().stream().anyMatch(contract -> contract.endsWith(".v0.3"));
    }

    static ContractFinding unsupportedCaseSelection(Path manifest) {
        return finding(manifest, "test_case_id", "unsupported_test_case_selection",
                "Use --test-case with a v0.3 leaf suite manifest.");
    }

    private V03ExecutionPlan compileV03IfReady(
            Path suiteManifest,
            String profile,
            ValidationResult validation,
            List<ContractFinding> findings, String selectedTestCase) {
        if (selectedTestCase != null && validation.valid() && !isV03Validation(validation)) {
            findings.add(unsupportedCaseSelection(suiteManifest));
        }
        if (!validation.valid() || !isV03Validation(validation) || !findings.isEmpty()) {
            return null;
        }
        try {
            return new V03ExecutionPlanBuilder(contractBaselineService).build(suiteManifest, profile, validation, selectedTestCase);
        } catch (IllegalArgumentException | IllegalStateException error) {
            findings.add(finding(
                    suiteManifest,
                    "execution_plan",
                    "v03_plan_compilation_failed",
                    "Correct the v0.3 target bindings or Provider Contract metadata: " + error.getMessage()));
            return null;
        }
    }

    public record SuiteGroupDefinition(String suiteId, String profile, List<SuiteGroupChild> children) {
    }

    public record SuiteGroupValidation(
            SuiteGroupDefinition definition,
            List<String> providerInstances,
            List<String> providerTypes,
            List<ContractFinding> findings) {

        public boolean valid() {
            return findings.isEmpty();
        }
    }

    public record SuiteGroupChild(
            String id,
            String ref,
            Path suiteManifest,
            String profile,
            String expectedStatus,
            String fieldPath) {
    }
}
