package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.ContractBaselineService.ResolvedTarget;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.contract.v03.assertion.V03AssertionKind;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class V03ExecutionPlanBuilder {

    private final ContractBaselineService contractBaselineService;
    private final Yaml yaml = new Yaml();
    private final V03ReferenceParser referenceParser = new V03ReferenceParser();
    private final V03ReferenceResolver referenceResolver = new V03ReferenceResolver(this::readDocument);
    private final V03GeneratedBindingDag generatedBindingDag = new V03GeneratedBindingDag();
    private final V03ProviderContractCatalog providerContractCatalog = new V03ProviderContractCatalog();

    public V03ExecutionPlanBuilder() {
        this(new ContractBaselineService());
    }

    public V03ExecutionPlanBuilder(ContractBaselineService contractBaselineService) {
        this.contractBaselineService = contractBaselineService;
    }

    public V03ExecutionPlan build(Path suiteManifest, String requestedProfile) {
        V03CompiledSuite compiled = compile(suiteManifest, requestedProfile);
        return V03ExecutionPlan.from(compiled);
    }

    public V03CompiledSuite compile(Path suiteManifest, String requestedProfile) {
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        return compile(suiteManifest, requestedProfile, validation);
    }

    /** Compiles from a command-scoped validation result to avoid duplicate baseline validation. */
    public V03CompiledSuite compile(
            Path suiteManifest,
            String requestedProfile,
            ValidationResult validation) {
        if (!validation.valid()) {
            throw new IllegalArgumentException("v0.3 suite is not valid: " + validation.findings());
        }

        Path suiteRoot = suiteManifest.toAbsolutePath().normalize().getParent();
        Map<String, Object> suite = readMap(suiteManifest);
        String profile = effectiveProfile(requestedProfile, suite, validation);
        Map<String, Object> envProfile = readEnvProfile(suiteRoot, suite, profile);
        Map<String, V03ProviderContract> contracts = providerContractCatalog.load(suiteRoot);
        Map<String, V03ResolvedTarget> targets = orderedTargets(
                resolvedTargets(validation, suite, profile, envProfile), contracts);
        List<Map<String, Object>> testDocuments = testDocuments(suiteRoot, suite);
        Map<String, Path> artifactRoots = artifactRoots(suiteRoot, suite);
        testDocuments.forEach(document -> validateReferences(document, artifactRoots));
        List<V03ExecutionStep> steps = executionSteps(testDocuments, profile, targets);
        validateTypedContracts(targets, steps, contracts);
        V03SuiteMetadata metadata = new V03SuiteMetadata(
                stringValue(suite.get("manifest_version")),
                stringValue(suite.get("suite_id")),
                stringValue(suite.get("default_profile")));
        V03EnvironmentProfile environmentProfile = compiledEnvironmentProfile(profile, envProfile);
        List<V03CompiledTestCase> compiledTests = compiledTestCases(steps, testDocuments);
        return new V03CompiledSuite(
                stringValue(suite.get("suite_id")),
                profile,
                suiteRoot,
                metadata,
                environmentProfile,
                Collections.unmodifiableMap(new LinkedHashMap<>(targets)),
                artifactRoots,
                compiledTests,
                planDigest(metadata, environmentProfile, targets, compiledTests));
    }

    private void validateReferences(Object value, Map<String, Path> artifactRoots) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> validateReferences(item, artifactRoots));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> validateReferences(item, artifactRoots));
            return;
        }
        V03Reference reference = referenceParser.parse(value);
        if (reference instanceof V03Reference.Artifact artifact) {
            referenceResolver.artifactPath(artifact, artifactRoots);
        }
    }

    private List<V03CompiledTestCase> compiledTestCases(List<V03ExecutionStep> steps, List<Map<String, Object>> documents) {
        Map<String, List<V03ExecutionStep>> byTestCase = new LinkedHashMap<>();
        for (V03ExecutionStep step : steps) {
            byTestCase.computeIfAbsent(step.testCaseId(), ignored -> new ArrayList<>()).add(step);
        }
        List<V03CompiledTestCase> tests = new ArrayList<>();
        for (Map.Entry<String, List<V03ExecutionStep>> entry : byTestCase.entrySet()) {
            List<V03ExecutionStep> setup = new ArrayList<>();
            List<V03ExecutionStep> execute = new ArrayList<>();
            List<V03ExecutionStep> verify = new ArrayList<>();
            List<V03ExecutionStep> cleanup = new ArrayList<>();
            for (V03ExecutionStep step : entry.getValue()) {
                switch (step.phase()) {
                    case "setup" -> setup.add(step);
                    case "execute" -> execute.add(step);
                    case "verify" -> verify.add(step);
                    case "cleanup" -> cleanup.add(step);
                    default -> throw new IllegalArgumentException("Unsupported v0.3 step phase `" + step.phase() + "`.");
                }
            }
            Map<String, Object> document = documents.stream()
                    .filter(candidate -> entry.getKey().equals(stringValue(candidate.get("test_case_id"))))
                    .findFirst().orElse(Map.of());
            tests.add(new V03CompiledTestCase(
                    entry.getKey(),
                    stringValue(document.get("dsl_version")),
                    stringValue(document.get("title")),
                    stringMap(document.get("source_refs")),
                    List.copyOf(setup), List.copyOf(execute), List.copyOf(verify), List.copyOf(cleanup)));
        }
        return List.copyOf(tests);
    }

    private V03EnvironmentProfile compiledEnvironmentProfile(String profile, Map<String, Object> envProfile) {
        return new V03EnvironmentProfile(
                firstNonBlank(stringValue(envProfile.get("profile_id")), profile),
                stringValue(envProfile.get("execution_mode")),
                stringValue(envProfile.get("isolation")),
                firstNonBlank(stringValue(envProfile.get("evidence_classification")), "framework_verification_only"),
                Boolean.parseBoolean(stringValue(envProfile.get("downstream_release_evidence"))));
    }

    private Map<String, Path> artifactRoots(Path suiteRoot, Map<String, Object> suite) {
        Map<String, Path> roots = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapValue(suite.get("artifact_roots")).entrySet()) {
            roots.put(entry.getKey(), suiteRoot.resolve(stringValue(entry.getValue())).normalize());
        }
        return Collections.unmodifiableMap(roots);
    }

    private Map<String, V03ResolvedTarget> resolvedTargets(
            ValidationResult validation,
            Map<String, Object> suite,
            String profile,
            Map<String, Object> envProfile) {
        Map<String, V03ResolvedTarget> targets = new LinkedHashMap<>();
        for (ResolvedTarget target : validation.plan()) {
            if (targets.containsKey(target.target())) {
                continue;
            }
            Map<String, Object> envTarget = mapValue(mapValue(envProfile.get("targets")).get(target.target()));
            targets.put(target.target(), new V03ResolvedTarget(
                    target.target(),
                    target.providerContract(),
                    target.providerType(),
                    target.profile(),
                    target.runtimeMode(),
                    immutableMap(mapValue(envTarget.get("bindings")))));
        }
        for (Map.Entry<String, Object> entry : mapValue(suite.get("targets")).entrySet()) {
            if (targets.containsKey(entry.getKey())) {
                continue;
            }
            Map<String, Object> declaration = mapValue(entry.getValue());
            Map<String, Object> envTarget = mapValue(mapValue(envProfile.get("targets")).get(entry.getKey()));
            String providerContract = stringValue(declaration.get("provider_contract"));
            targets.put(entry.getKey(), new V03ResolvedTarget(
                    entry.getKey(),
                    providerContract,
                    providerType(providerContract),
                    profile,
                    stringValue(envTarget.get("runtime_mode")),
                    immutableMap(mapValue(envTarget.get("bindings")))));
        }
        return targets;
    }

    private Map<String, V03ResolvedTarget> orderedTargets(
            Map<String, V03ResolvedTarget> targets,
            Map<String, V03ProviderContract> contracts) {
        Map<String, V03ResolvedTarget> ordered = new LinkedHashMap<>();
        for (String target : generatedBindingDag.producerFirstOrder(targets, contracts)) {
            ordered.put(target, targets.get(target));
        }
        return Collections.unmodifiableMap(ordered);
    }

    private void validateTypedContracts(
            Map<String, V03ResolvedTarget> targets,
            List<V03ExecutionStep> steps,
            Map<String, V03ProviderContract> contracts) {
        for (V03ResolvedTarget target : targets.values()) {
            V03ProviderContract contract = contractFor(target, contracts);
            if (!contract.runtimeModes().contains(target.runtimeMode())) {
                throw new IllegalArgumentException("unsupported_runtime_mode: target `" + target.target()
                        + "` uses `" + target.runtimeMode() + "` outside Provider Contract `" + contract.id() + "`.");
            }
            for (String binding : target.bindings().keySet()) {
                if (!contract.bindings().containsKey(binding)) {
                    throw new IllegalArgumentException("unknown_binding_key: target `" + target.target()
                            + "` supplies `" + binding + "` outside Provider Contract `" + contract.id() + "`.");
                }
            }
            for (Map.Entry<String, V03ProviderContract.V03BindingDefinition> binding : contract.bindings().entrySet()) {
                if (binding.getValue().required() && !target.bindings().containsKey(binding.getKey())) {
                    throw new IllegalArgumentException("missing_required_binding_key: target `" + target.target()
                            + "` requires `" + binding.getKey() + "` from Provider Contract `" + contract.id() + "`.");
                }
            }
        }
        for (V03ExecutionStep step : steps) {
            if (step.target().isBlank()) {
                continue;
            }
            V03ProviderContract contract = contractFor(targets.get(step.target()), contracts);
            V03ProviderContract.V03OperationDefinition operation = contract.operations().get(step.operation());
            if (operation == null) {
                throw new IllegalArgumentException("unsupported_operation: step `" + step.id() + "` uses `"
                        + step.operation() + "` outside Provider Contract `" + contract.id() + "`.");
            }
            for (String input : step.inputs().keySet()) {
                if (!matchesContractName(operation.allowedInputs(), input)) {
                    throw new IllegalArgumentException("unsupported_input: step `" + step.id() + "` uses `" + input
                            + "` outside Provider Contract `" + contract.id() + "` operation `" + step.operation() + "`.");
                }
            }
            for (String required : operation.requiredInputs()) {
                if (!hasContractName(step.inputs().keySet(), required)) {
                    throw new IllegalArgumentException("missing_required_input: step `" + step.id() + "` requires `" + required
                            + "` from Provider Contract `" + contract.id() + "`.");
                }
            }
        }
    }

    private V03ProviderContract contractFor(
            V03ResolvedTarget target, Map<String, V03ProviderContract> contracts) {
        if (target == null || !contracts.containsKey(target.providerContract())) {
            throw new IllegalArgumentException("missing_provider_contract: target `"
                    + (target == null ? "" : target.target()) + "` has no typed Provider Contract.");
        }
        return contracts.get(target.providerContract());
    }

    private boolean matchesContractName(Iterable<String> allowed, String actual) {
        for (String candidate : allowed) {
            if (candidate.equals(actual) || (candidate.endsWith(".*")
                    && actual.startsWith(candidate.substring(0, candidate.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasContractName(Iterable<String> provided, String required) {
        for (String candidate : provided) {
            if (candidate.equals(required) || (required.endsWith(".*")
                    && candidate.startsWith(required.substring(0, required.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private String providerType(String providerContract) {
        int versionDelimiter = providerContract.lastIndexOf(".v");
        return versionDelimiter > 0 ? providerContract.substring(0, versionDelimiter) : providerContract;
    }

    private List<Map<String, Object>> testDocuments(Path suiteRoot, Map<String, Object> suite) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object testRef : listValue(suite.get("tests"))) {
            documents.add(readMap(suiteRoot.resolve(suiteTestRef(testRef)).normalize()));
        }
        return List.copyOf(documents);
    }

    private List<V03ExecutionStep> executionSteps(
            List<Map<String, Object>> testDocuments,
            String profile,
            Map<String, V03ResolvedTarget> targets) {
        List<V03ExecutionStep> steps = new ArrayList<>();
        for (Map<String, Object> testCase : testDocuments) {
            String testCaseId = stringValue(testCase.get("test_case_id"));
            addProviderSteps(steps, testCaseId, "setup", listValue(testCase.get("setup")), profile, targets);
            addProviderSteps(steps, testCaseId, "execute", listValue(testCase.get("execute")), profile, targets);
            addVerifySteps(steps, testCaseId, listValue(testCase.get("verify")), profile, targets);
            addProviderSteps(steps, testCaseId, "cleanup", listValue(testCase.get("cleanup")), profile, targets);
        }
        return steps;
    }

    private void addProviderSteps(
            List<V03ExecutionStep> steps,
            String testCaseId,
            String phase,
            List<Object> values,
            String profile,
            Map<String, V03ResolvedTarget> targets) {
        int index = 0;
        for (Object value : values) {
            Map<String, Object> step = mapValue(value);
            String targetName = stringValue(step.get("target"));
            String id = firstNonBlank(stringValue(step.get("id")), phase + "[" + index + "]");
            steps.add(executionStep(
                    testCaseId,
                    phase,
                    id,
                    targetName,
                    stringValue(step.get("op")),
                    mapValue(step.get("with")),
                    profile,
                    targets.get(targetName)));
            index++;
        }
    }

    private void addVerifySteps(
            List<V03ExecutionStep> steps,
            String testCaseId,
            List<Object> values,
            String profile,
            Map<String, V03ResolvedTarget> targets) {
        int index = 0;
        for (Object value : values) {
            Map<String, Object> verify = mapValue(value);
            String id = firstNonBlank(stringValue(verify.get("id")), "verify[" + index + "]");
            String type = stringValue(verify.get("type"));
            if ("provider_check".equals(type)) {
                String targetName = stringValue(verify.get("target"));
                steps.add(executionStep(
                        testCaseId,
                        "verify",
                        id,
                        targetName,
                        stringValue(verify.get("op")),
                        mapValue(verify.get("with")),
                        profile,
                        targets.get(targetName)));
            } else if ("assertion".equals(type)) {
                Map<String, Object> assertion = mapValue(verify.get("assert"));
                String operator = stringValue(assertion.get("operator"));
                V03AssertionKind.require(operator);
                steps.add(new V03ExecutionStep(
                        testCaseId,
                        "verify",
                        id,
                        "",
                        "",
                        "",
                        profile,
                        "",
                        operator,
                        immutableMap(assertion),
                        ""));
            } else {
                throw new IllegalArgumentException(
                        "missing_or_unsupported_verify_type: verify step `" + id
                                + "` must declare `type: assertion` or `type: provider_check`.");
            }
            index++;
        }
    }

    private V03ExecutionStep executionStep(
            String testCaseId,
            String phase,
            String id,
            String targetName,
            String operation,
            Map<String, Object> inputs,
            String profile,
            V03ResolvedTarget target) {
        if (target == null) {
            return new V03ExecutionStep(
                    testCaseId,
                    phase,
                    id,
                    targetName,
                    "",
                    "",
                    profile,
                    "",
                    operation,
                    immutableMap(inputs),
                    "");
        }
        return new V03ExecutionStep(
                testCaseId,
                phase,
                id,
                targetName,
                target.providerContract(),
                target.providerType(),
                target.profile(),
                target.runtimeMode(),
                operation,
                immutableMap(inputs),
                "");
    }

    private Map<String, Object> readEnvProfile(Path suiteRoot, Map<String, Object> suite, String profile) {
        Map<String, Object> profileRef = mapValue(mapValue(suite.get("env_profiles")).get(profile));
        String ref = stringValue(profileRef.get("ref"));
        if (ref.isBlank()) {
            return Map.of();
        }
        return readMap(suiteRoot.resolve(ref).normalize());
    }

    private String effectiveProfile(String requestedProfile, Map<String, Object> suite, ValidationResult validation) {
        if (requestedProfile != null && !requestedProfile.isBlank()) {
            return requestedProfile;
        }
        String defaultProfile = stringValue(suite.get("default_profile"));
        if (!defaultProfile.isBlank()) {
            return defaultProfile;
        }
        if (!validation.plan().isEmpty()) {
            return validation.plan().get(0).profile();
        }
        return "";
    }

    private String suiteTestRef(Object value) {
        Map<String, Object> map = mapValue(value);
        if (!map.isEmpty() || value instanceof Map<?, ?>) {
            return stringValue(map.get("ref"));
        }
        return stringValue(value);
    }

    private Object readDocument(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            return yaml.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML `" + path + "`.", e);
        }
    }

    private Map<String, Object> readMap(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            return mapValue(yaml.load(reader));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML `" + path + "`.", e);
        }
    }

    private Map<String, Object> immutableMap(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private Map<String, String> stringMap(Object value) {
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapValue(value).entrySet()) {
            strings.put(entry.getKey(), stringValue(entry.getValue()));
        }
        return Map.copyOf(strings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String planDigest(
            V03SuiteMetadata metadata,
            V03EnvironmentProfile environmentProfile,
            Map<String, V03ResolvedTarget> targets,
            List<V03CompiledTestCase> tests) {
        StringBuilder canonical = new StringBuilder()
                .append(metadata.manifestVersion()).append('|')
                .append(metadata.suiteId()).append('|')
                .append(environmentProfile.profileId()).append('\n');
        targets.values().forEach(target -> canonical.append(target.target()).append('|')
                .append(target.providerContract()).append('|')
                .append(target.runtimeMode()).append('|')
                .append(target.bindings()).append('\n'));
        tests.forEach(test -> {
            canonical.append(test.testCaseId()).append('|').append(test.dslVersion()).append('\n');
            appendSteps(canonical, test.setup());
            appendSteps(canonical, test.execute());
            appendSteps(canonical, test.verify());
            appendSteps(canonical, test.cleanup());
        });
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required to compile a v0.3 execution plan.", error);
        }
    }

    private void appendSteps(StringBuilder canonical, List<V03ExecutionStep> steps) {
        steps.forEach(step -> canonical.append(step.phase()).append('|')
                .append(step.id()).append('|').append(step.target()).append('|')
                .append(step.operation()).append('|').append(step.inputs()).append('\n'));
    }
}
