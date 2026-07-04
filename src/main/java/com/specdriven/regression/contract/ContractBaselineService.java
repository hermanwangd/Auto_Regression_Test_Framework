package com.specdriven.regression.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public class ContractBaselineService {

    private static final Path FRAMEWORK_PROVIDER_CONTRACTS =
            Path.of("docs/02-architecture/contracts/provider-contracts");
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "rp_id",
            "ac_id",
            "execution_target",
            "package_inputs",
            "oracles",
            "steps",
            "assertions",
            "evidence_required");
    private static final Set<String> PROHIBITED_V02_DATA_FIELDS = Set.of(
            "data_binding",
            "datasets",
            "fixtures",
            "expected_results",
            "db_seed",
            "db_cleanup",
            "mock_stubs");
    private static final Set<String> PROHIBITED_SOURCE_REF_KEYS = Set.of(
            "expected_result",
            "expected_results",
            "fixture",
            "fixtures",
            "payload",
            "sql",
            "query",
            "mock_mapping",
            "mock_mappings",
            "data");
    private static final Set<String> GOVERNANCE_FIELDS = Set.of(
            "approval_status",
            "waiver",
            "release_gate",
            "risk_approval");
    private static final Set<String> DEPRECATED_DSL_FIELDS = Set.of(
            "scenario");
    private static final Set<String> PROHIBITED_SUITE_FIELDS = Set.of(
            "suite_type",
            "test_cases");
    private static final Set<String> BINDING_VALUE_KIND_FIELDS = Set.of(
            "value",
            "ref",
            "secret_ref",
            "generated_ref",
            "local_ref");
    private static final List<String> RESULT_REQUIRED_FIELDS = List.of(
            "framework_version",
            "dsl_version",
            "test_case_id",
            "status",
            "profile",
            "environment",
            "timestamps",
            "provider_results",
            "steps",
            "verify_results",
            "evidence_refs",
            "failure");
    private static final Pattern JDBC_URL = Pattern.compile("(?i)\\bjdbc:[a-z0-9_+.-]+:[^\\s\"']+");
    private static final Pattern NATS_CREDENTIAL = Pattern.compile("(?i)nats://[^\\s/@:]+:[^\\s/@]+@[^\\s\"']+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{8,}");
    private static final Pattern CONNECTION_URI =
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/@:]+:[^\\s/@]+@[^\\s\"']+");

    private final Yaml yaml = new Yaml();

    public ValidationResult validateSuite(Path suiteManifest) {
        ContractGraph graph = loadGraph(suiteManifest);
        List<ContractFinding> findings = new ArrayList<>(graph.loadFindings());
        validateProhibitedSuiteFields(graph, findings);
        if (graph.loadBlocked()) {
            return new ValidationResult(false, graph.suiteId(), List.of(), List.of(), List.of(), List.copyOf(findings));
        }
        validateRequiredFields(graph, findings);
        validateProhibitedFields(graph, findings);
        validateDataBindingCategories(graph, findings);
        validateRawSecrets(graph, findings);
        validateTargets(graph, findings);
        validateOperationTargets(graph, findings);
        validateProviderInstanceShapes(graph, findings);
        validateExecutionProfiles(graph, findings);
        validateJdbcContractsAndInstances(graph, findings);
        validateNatsContractsAndInstances(graph, findings);
        validateOperations(graph, findings);
        validateOutputRefs(graph, findings);
        validateRuntimeCriticalArtifactRefs(graph, findings);
        validateEnvironmentBindings(graph, findings);
        return validationResult(graph, findings);
    }

    public DryRunResult dryRun(Path suiteManifest) {
        ValidationResult validation = validateSuite(suiteManifest);
        return new DryRunResult(validation.valid(), validation, validation.valid() ? validation.plan() : List.of());
    }

    public ReportResult report(Path resultJson) {
        if (!Files.isRegularFile(resultJson)) {
            return new ReportResult(false, false, "", "", "", "", "", "", 0, 0, false, List.of(), List.of(
                    new ContractFinding(
                            resultJson.toString(),
                            "result",
                            "missing_result_json",
                            "",
                            "",
                            "",
                            "",
                            "Provide an existing standard result JSON file.")));
        }
        Map<String, Object> result;
        try {
            Object loaded = yaml.load(Files.readString(resultJson));
            result = mapValue(loaded);
        } catch (IOException | RuntimeException e) {
            return new ReportResult(false, false, "", "", "", "", "", "", 0, 0, false, List.of(), List.of(
                    new ContractFinding(
                            resultJson.toString(),
                            "result",
                            "invalid_result_json",
                            "",
                            "",
                            "",
                            "",
                            "Fix malformed result JSON before reporting.")));
        }
        if (result.isEmpty()) {
            return new ReportResult(false, false, "", "", "", "", "", "", 0, 0, false, List.of(), List.of(
                    new ContractFinding(
                            resultJson.toString(),
                            "result",
                            "invalid_result_json",
                            "",
                            "",
                            "",
                            "",
                            "Use a JSON object as the standard result.")));
        }
        List<ContractFinding> findings = new ArrayList<>();
        for (String field : RESULT_REQUIRED_FIELDS) {
            if (isMissingResultField(result.get(field))) {
                findings.add(new ContractFinding(
                        resultJson.toString(),
                        field,
                        "missing_required_field",
                        "",
                        "",
                        "",
                        "",
                            "Add required result field `" + field + "`."));
            }
        }
        String suiteId = stringValue(result.get("suite_id"));
        String batchId = stringValue(result.get("batch_id"));
        String runId = stringValue(result.get("run_id"));
        if (!suiteId.isBlank()) {
            for (String field : List.of("suite_id", "batch_id", "run_id", "start_time", "end_time", "duration_ms")) {
                if (isMissingResultField(result.get(field))) {
                    findings.add(new ContractFinding(
                            resultJson.toString(),
                            field,
                            "missing_required_field",
                            "",
                            "",
                            stringValue(result.get("profile")),
                            "",
                            "Add required generated result field `" + field + "`."));
                }
            }
            for (Object ref : listValue(result.get("evidence_refs"))) {
                String refText = stringValue(ref);
                if (!looksLikeFileRef(refText)) {
                    continue;
                }
                Path evidencePath = resultJson.getParent().resolve(stringValue(ref)).normalize();
                if (!Files.isRegularFile(evidencePath)) {
                    findings.add(new ContractFinding(
                            resultJson.toString(),
                            "evidence_refs",
                            "missing_evidence_ref",
                            "",
                            "",
                            stringValue(result.get("profile")),
                            "",
                            "Restore missing evidence ref `" + ref + "`."));
                }
            }
        }
        findings.addAll(ResultContractValidator.validate(resultJson, result));
        detectRawSecrets(resultJson.toString(), result, findings);
        List<Map<String, Object>> providerResults = mapList(result.get("provider_results"));
        List<String> failedVerifySummary = mapList(result.get("verify_results")).stream()
                .filter(verify -> !"passed".equals(stringValue(verify.get("status"))))
                .map(verify -> stringValue(verify.get("id")) + ":" + stringValue(verify.get("type")) + ":"
                        + stringValue(verify.get("status")))
                .toList();
        boolean releaseEvidenceEligible = providerResults.stream()
                .anyMatch(providerResult -> Boolean.TRUE.equals(providerResult.get("release_evidence_eligible")));
        Map<String, Object> labels = mapValue(result.get("labels"));
        boolean downstreamReleaseEvidence = Boolean.TRUE.equals(labels.get("downstream_release_evidence"));
        String evidenceClassification = stringValue(labels.get("evidence_classification"));
        boolean frameworkOnlyEvidence = "framework_provider_capability_only".equals(evidenceClassification)
                || "framework_verification_only".equals(evidenceClassification);
        if (findings.isEmpty() && releaseEvidenceEligible && (!downstreamReleaseEvidence || frameworkOnlyEvidence)) {
            findings.add(new ContractFinding(
                    resultJson.toString(),
                    "provider_results.release_evidence_eligible",
                    "mock_release_evidence_claim",
                    "",
                    "",
                    stringValue(result.get("profile")),
                    "",
                    "Mark contract-baseline provider results as framework evidence only."));
        }
        return new ReportResult(
                findings.isEmpty(),
                !findings.isEmpty(),
                suiteId,
                batchId,
                runId,
                stringValue(result.get("test_case_id")),
                stringValue(result.get("status")),
                stringValue(result.get("profile")),
                providerResults.size(),
                listValue(result.get("verify_results")).size(),
                releaseEvidenceEligible,
                failedVerifySummary,
                List.copyOf(findings));
    }

    private boolean looksLikeFileRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        return ref.contains("/") || ref.endsWith(".yaml") || ref.endsWith(".yml") || ref.endsWith(".json")
                || ref.endsWith(".txt") || ref.endsWith(".diff") || ref.endsWith(".log");
    }

    private void validateRequiredFields(ContractGraph graph, List<ContractFinding> findings) {
        require(graph.suitePath(), graph.suite(), "contract_version", findings);
        require(graph.suitePath(), graph.suite(), "suite_id", findings);
        require(graph.suitePath(), graph.suite(), "selection", findings);
        require(graph.suitePath(), graph.suite(), "tests", findings);
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            for (String field : List.of(
                    "dsl_version", "test_case_id", "status", "revision", "targets",
                    "execute", "verify", "evidence", "runtime")) {
                require(entry.getKey(), testCase, field, findings);
            }
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.providerContractsByPath().entrySet()) {
            Map<String, Object> contract = entry.getValue();
            for (String field : List.of(
                    "provider_contract_version", "provider_type", "runtime_modes", "binding_keys",
                    "operations", "evidence", "failure_mapping")) {
                require(entry.getKey(), contract, field, findings);
            }
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.providerInstancesByPath().entrySet()) {
            Map<String, Object> instance = entry.getValue();
            for (String field : List.of("provider_instance_version", "provider_id", "provider_type", "runtime_modes")) {
                require(entry.getKey(), instance, field, findings);
            }
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.executionProfiles().entrySet()) {
            Map<String, Object> profile = entry.getValue();
            for (String field : List.of(
                    "profile_id", "execution_mode", "environment_binding_ref", "isolation_scope",
                    "dependency_policy", "dependency_substitution_policy", "dependency_provisioning_policy",
                    "max_duration", "data_policy")) {
                require(entry.getKey(), profile, field, findings);
            }
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.envProfiles().entrySet()) {
            Map<String, Object> profile = entry.getValue();
            for (String field : List.of(
                    "env_profile_id", "execution_mode", "providers",
                    "dependency_policy", "dependency_substitution_policy",
                    "dependency_provisioning_policy", "data_policy")) {
                require(entry.getKey(), profile, field, findings);
            }
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.environmentBindings().entrySet()) {
            Map<String, Object> binding = entry.getValue();
            for (String field : List.of("environment_id", "profile", "provider_bindings")) {
                require(entry.getKey(), binding, field, findings);
            }
        }
    }

    private void validateProhibitedSuiteFields(ContractGraph graph, List<ContractFinding> findings) {
        for (String field : PROHIBITED_SUITE_FIELDS) {
            if (graph.suite().containsKey(field)) {
                findings.add(finding(graph.suitePath(), field, "prohibited_legacy_field",
                        "Remove legacy suite field `" + field + "`; use standard `tests[]` for multi-test execution or `child_suites[]` for compatibility aggregation."));
            }
        }
    }

    private void validateProhibitedFields(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            for (String field : LEGACY_FIELDS) {
                if (entry.getValue().containsKey(field)) {
                    findings.add(finding(entry.getKey(), field, "prohibited_legacy_field",
                            "Remove legacy field `" + field + "` from v0.2 DSL."));
                }
            }
            for (String field : GOVERNANCE_FIELDS) {
                if (entry.getValue().containsKey(field)) {
                    findings.add(finding(entry.getKey(), field, "prohibited_governance_field",
                            "Remove governance field `" + field + "` from framework runtime DSL."));
                }
            }
            for (String field : DEPRECATED_DSL_FIELDS) {
                if (entry.getValue().containsKey(field)) {
                    findings.add(finding(entry.getKey(), field, "prohibited_deprecated_field",
                            "Remove `scenario`; declare behavior through setup/execute/verify and provider capability through Provider Contract."));
                }
            }
            for (String field : PROHIBITED_V02_DATA_FIELDS) {
                if (entry.getValue().containsKey(field)) {
                    findings.add(finding(entry.getKey(), field, "prohibited_data_catalog_field",
                            "Use lifecycle-neutral `data` entries and bind them through operation `inputs`; `"
                                    + field + "` is not part of DSL v0.2."));
                }
            }
            Object sourceRefs = entry.getValue().get("source_refs");
            if (sourceRefs != null && !(sourceRefs instanceof Map<?, ?>)) {
                findings.add(finding(entry.getKey(), "source_refs", "invalid_source_refs",
                        "Declare `source_refs` as a map of traceability keys to source references."));
            } else {
                for (String field : mapValue(sourceRefs).keySet()) {
                    if (PROHIBITED_SOURCE_REF_KEYS.contains(field)) {
                        findings.add(finding(entry.getKey(), "source_refs." + field, "prohibited_source_ref",
                                "Keep execution artifacts in `data`, operation `inputs`, or verify expected refs; `source_refs` is traceability metadata only."));
                    }
                }
            }
        }
    }

    private void validateDataBindingCategories(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            for (String category : mapValue(entry.getValue().get("data_binding")).keySet()) {
                findings.add(finding(entry.getKey(), "data_binding." + category,
                        "prohibited_data_binding_category",
                        "Move checked-in artifacts to `data.<name>.ref` and reference them from operation `inputs`."));
            }
        }
    }

    private void validateRawSecrets(ContractGraph graph, List<ContractFinding> findings) {
        scanAll(graph.suitePath(), graph.suite(), findings);
        graph.testCases().forEach((path, document) -> scanAll(path, document, findings));
        graph.providerContractsByPath().forEach((path, document) -> scanAll(path, document, findings));
        graph.providerInstancesByPath().forEach((path, document) -> scanAll(path, document, findings));
        graph.executionProfiles().forEach((path, document) -> scanAll(path, document, findings));
        graph.envProfiles().forEach((path, document) -> scanAll(path, document, findings));
        graph.environmentBindings().forEach((path, document) -> scanAll(path, document, findings));
    }

    private void scanAll(Path path, Map<String, Object> document, List<ContractFinding> findings) {
        detectRawSecrets(path.toString(), document, findings);
    }

    private void validateTargets(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> targets = mapValue(entry.getValue().get("targets"));
            for (Map.Entry<String, Object> targetEntry : targets.entrySet()) {
                Map<String, Object> target = mapValue(targetEntry.getValue());
                String providerId = stringValue(target.get("provider_id"));
                String profile = selectedProfile(graph, target);
                if (providerId.isBlank()) {
                    findings.add(finding(entry.getKey(), "targets." + targetEntry.getKey() + ".provider_id",
                            "missing_required_field", "Add provider_id to DSL target `" + targetEntry.getKey() + "`."));
                    continue;
                }
                Map<String, Object> instance = graph.providerInstances().get(providerId);
                if (instance == null) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            "targets." + targetEntry.getKey() + ".provider_id",
                            "missing_provider_instance",
                            providerId,
                            "",
                            profile,
                            "",
                            "Add Provider Instance for provider_id `" + providerId + "`."));
                    continue;
                }
                String providerType = stringValue(instance.get("provider_type"));
                if (!graph.allowedProviderTypes().contains(providerType) || !graph.providerContracts().containsKey(providerType)) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            "targets." + targetEntry.getKey() + ".provider_type",
                            "unknown_provider_type",
                            providerId,
                            providerType,
                            profile,
                            "",
                            "Use a framework Provider Contract provider_type or declare an explicit custom contract for `"
                                    + providerType + "`."));
                }
                if (graph.usedProfiles().isEmpty()) {
                    findings.add(finding(entry.getKey(), "targets." + targetEntry.getKey(),
                            "missing_selected_profile",
                            "Select a profile in the suite manifest or CLI before execution."));
                    continue;
                }
                for (String usedProfile : graph.usedProfiles()) {
                    if (!graph.environmentBindingsByProfile().containsKey(usedProfile)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                "profiles." + usedProfile,
                                "missing_environment_binding",
                                providerId,
                                providerType,
                                usedProfile,
                                "",
                                "Add Environment Binding for profile `" + usedProfile + "`."));
                    } else if (!environmentBindingForProvider(graph, usedProfile, providerId)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                "targets." + targetEntry.getKey() + ".provider_id",
                                "missing_environment_binding",
                                providerId,
                                providerType,
                                usedProfile,
                                "",
                                "Add provider binding for `" + providerId + "` in profile `" + usedProfile + "`."));
                    }
                }
            }
        }
    }

    private void validateProviderInstanceShapes(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.providerInstancesByPath().entrySet()) {
            Map<String, Object> instance = entry.getValue();
            String providerId = stringValue(instance.get("provider_id"));
            String providerType = stringValue(instance.get("provider_type"));
            Map<String, Object> contract = graph.providerContracts().get(providerType);
            if (contract == null) {
                continue;
            }
            Map<String, Object> shape = mapValue(contract.get("valid_provider_instance_shape"));
            if (shape.isEmpty()) {
                continue;
            }
            for (String requiredField : stringList(shape.get("required_fields"))) {
                if (isMissing(instance.get(requiredField))) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            requiredField,
                            "missing_required_field",
                            providerId,
                            providerType,
                            "",
                            "",
                            "Add Provider Instance field `" + requiredField + "` required by Provider Contract `"
                                    + providerType + "`."));
                }
            }
            Set<String> allowedFields = new LinkedHashSet<>(stringList(shape.get("allowed_fields")));
            if (!allowedFields.isEmpty()) {
                for (String field : instance.keySet()) {
                    if (!allowedFields.contains(field)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                field,
                                "unsupported_provider_instance_field",
                                providerId,
                                providerType,
                                "",
                                "",
                                "Remove Provider Instance field `" + field + "` or add it to Provider Contract `"
                                        + providerType + "` valid_provider_instance_shape.allowed_fields."));
                    }
                }
            }
            Set<String> allowedBindingKeys = new LinkedHashSet<>(stringList(shape.get("binding_keys")));
            if (!allowedBindingKeys.isEmpty()) {
                for (String bindingKey : mapValue(instance.get("binding_keys")).keySet()) {
                    if (!allowedBindingKeys.contains(bindingKey)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                "binding_keys." + bindingKey,
                                "unsupported_provider_instance_binding_key",
                                providerId,
                                providerType,
                                "",
                                "",
                                "Use a Provider Instance binding key allowed by Provider Contract `" + providerType
                                        + "`: " + allowedBindingKeys + "."));
                    }
                }
            }
        }
    }

    private void validateOperationTargets(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            Set<String> targetNames = mapValue(testCase.get("targets")).keySet();
            for (OperationRef operationRef : operationRefs(testCase)) {
                String target = operationRef.target();
                if (target.isBlank() || targetNames.contains(target)) {
                    continue;
                }
                findings.add(new ContractFinding(
                        entry.getKey().toString(),
                        operationRef.fieldPath() + ".target",
                        "invalid_target_ref",
                        "",
                        "",
                        "",
                        operationRef.operation(),
                        "Use a target declared in DSL targets before runtime execution. Unknown target `" + target + "`."));
            }
        }
    }

    private void validateExecutionProfiles(ContractGraph graph, List<ContractFinding> findings) {
        if (graph.envProfileMode()) {
            for (String profile : graph.usedProfiles()) {
                boolean profileExists = graph.envProfiles().values().stream()
                        .anyMatch(document -> profile.equals(stringValue(document.get("env_profile_id"))));
                if (!profileExists) {
                    findings.add(new ContractFinding(
                            graph.suitePath().toString(),
                            "profiles." + profile,
                            "missing_env_profile",
                            "",
                            "",
                            profile,
                            "",
                            "Add Env_Profile for env_profile_id `" + profile + "`."));
                }
            }
            return;
        }
        for (String profile : graph.usedProfiles()) {
            boolean profileExists = graph.executionProfiles().values().stream()
                    .anyMatch(document -> profile.equals(stringValue(document.get("profile_id"))));
            if (!profileExists) {
                findings.add(new ContractFinding(
                        graph.suitePath().toString(),
                        "profiles." + profile,
                        "missing_execution_profile",
                        "",
                        "",
                        profile,
                        "",
                        "Add Execution Profile for profile `" + profile + "`."));
            }
        }
    }

    private void validateOperations(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            for (OperationRef operationRef : operationRefs(testCase)) {
                TargetResolution targetResolution = resolveTarget(graph, testCase, operationRef.target());
                if (!targetResolution.resolved()) {
                    continue;
                }
                Map<String, Object> operation = targetResolution.operation(operationRef.operation());
                if (operation.isEmpty()) {
                    if (operationRef.parameters().isEmpty() && !"common_verify".equals(targetResolution.providerType())) {
                        continue;
                    }
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            operationRef.fieldPath() + ".operation",
                            "unsupported_operation",
                            targetResolution.providerId(),
                            targetResolution.providerType(),
                            targetResolution.profile(),
                            operationRef.operation(),
                            "Use an operation declared by Provider Contract `" + targetResolution.providerType() + "`."));
                    continue;
                }
                List<String> allowedInputs = operationInputs(operation, "allowed_inputs", "allowed_bind_as");
                List<String> providedInputs = new ArrayList<>();
                for (int parameterIndex = 0; parameterIndex < operationRef.parameters().size(); parameterIndex++) {
                    Map<String, Object> parameter = operationRef.parameters().get(parameterIndex);
                    String inputName = parameterInputName(parameter);
                    providedInputs.add(inputName);
                    if (inputName.isBlank()) {
                        findings.add(finding(entry.getKey(), parameterFieldPath(operationRef, parameterIndex, parameter),
                                "missing_required_field", "Add an input name declared by the Provider Contract operation."));
                    } else if (!allowedBindAs(allowedInputs, inputName)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                parameterFieldPath(operationRef, parameterIndex, parameter),
                                operationRef.modernInputs() ? "unsupported_input" : "unsupported_bind_as",
                                targetResolution.providerId(),
                                targetResolution.providerType(),
                                targetResolution.profile(),
                                operationRef.operation(),
                                "Use an input name allowed by Provider Contract `" + targetResolution.providerType()
                                        + "` for operation `" + operationRef.operation() + "`. input: " + inputName));
                    }
                    String source = parameterSource(parameter);
                    if (source.isBlank()) {
                        findings.add(finding(entry.getKey(), parameterFieldPath(operationRef, parameterIndex, parameter),
                                "missing_required_field", "Add ref or value for operation input."));
                    } else {
                        String ref = source;
                        validateParameterRefWithinSuite(graph, entry.getKey(), testCase, operationRef,
                                parameterIndex, inputName, ref, targetResolution, findings);
                        validateParameterValueSyntax(entry.getKey(), operationRef, parameterIndex,
                                inputName, ref, targetResolution, findings);
                    }
                }
                if (!operationRef.parameters().isEmpty() || "common_verify".equals(targetResolution.providerType())) {
                    for (String requiredInput : operationInputs(operation, "required_inputs", "required_parameters")) {
                        if (!providedInput(providedInputs, requiredInput)) {
                            findings.add(new ContractFinding(
                                    entry.getKey().toString(),
                                    operationRef.fieldPath() + (operationRef.modernInputs() ? ".inputs" : ".parameters"),
                                    operationRef.modernInputs() ? "missing_required_input" : "missing_required_parameter",
                                    targetResolution.providerId(),
                                    targetResolution.providerType(),
                                    targetResolution.profile(),
                                    operationRef.operation(),
                                    "Add required input `" + requiredInput + "` for operation `"
                                            + operationRef.operation() + "`."));
                        }
                    }
                }
            }
        }
    }

    private void validateOutputRefs(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            for (OperationRef operationRef : operationRefs(testCase)) {
                TargetResolution targetResolution = resolveTarget(graph, testCase, operationRef.target());
                if (!targetResolution.resolved()) {
                    continue;
                }
                Map<String, Object> operation = targetResolution.operation(operationRef.operation());
                if (operation.isEmpty()) {
                    continue;
                }
                List<String> outputRefs = stringList(operation.get("output_refs"));
                for (String output : operationRef.outputs().keySet()) {
                    if (!outputRefs.contains(output)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                operationRef.fieldPath() + ".outputs." + output,
                                "missing_output_ref",
                                targetResolution.providerId(),
                                targetResolution.providerType(),
                                targetResolution.profile(),
                                operationRef.operation(),
                                "Declare output ref `" + output + "` in Provider Contract operation `"
                                        + operationRef.operation() + "`."));
                    }
                }
            }
        }
    }

    private void validateRuntimeCriticalArtifactRefs(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Path testCasePath = entry.getKey();
            Map<String, Object> testCase = entry.getValue();
            Path suiteRoot = graph.suitePath().toAbsolutePath().normalize().getParent();
            for (Map.Entry<String, Object> expected : mapValue(testCase.get("expected_results")).entrySet()) {
                validateRuntimeCriticalRef(
                        suiteRoot,
                        testCasePath,
                        "expected_results." + expected.getKey() + ".ref",
                        stringValue(mapValue(expected.getValue()).get("ref")),
                        findings);
            }
            for (Map.Entry<String, Object> dataEntry : mapValue(testCase.get("data")).entrySet()) {
                validateRuntimeCriticalRef(
                        suiteRoot,
                        testCasePath,
                        "data." + dataEntry.getKey() + ".ref",
                        stringValue(mapValue(dataEntry.getValue()).get("ref")),
                        findings);
            }
            int verifyIndex = 0;
            for (Object verifyValue : verifyChecks(testCase)) {
                Map<String, Object> verify = mapValue(verifyValue);
                String verifyPath = verify.get("id") == null
                        ? "verify[" + verifyIndex + "]"
                        : "verify." + stringValue(verify.get("id"));
                validateRuntimeCriticalRef(suiteRoot, testCasePath, verifyPath + ".expected_ref",
                        stringValue(verify.get("expected_ref")), findings);
                validateRuntimeCriticalExpectedRef(
                        suiteRoot,
                        testCasePath,
                        testCase,
                        verifyPath + ".expected",
                        verify.get("expected"),
                        findings);
                validateRuntimeCriticalRef(suiteRoot, testCasePath, verifyPath + ".actual_ref",
                        stringValue(verify.get("actual_ref")), findings);
                validateRuntimeCriticalRef(suiteRoot, testCasePath, verifyPath + ".query.ref",
                        stringValue(mapValue(verify.get("query")).get("ref")), findings);
                validateRuntimeCriticalRef(suiteRoot, testCasePath, verifyPath + ".event.subject_ref",
                        stringValue(mapValue(verify.get("event")).get("subject_ref")), findings);
                validateRuntimeCriticalRef(suiteRoot, testCasePath, verifyPath + ".event.payload_ref",
                        stringValue(mapValue(verify.get("event")).get("payload_ref")), findings);
                verifyIndex++;
            }
        }
    }

    private void validateRuntimeCriticalExpectedRef(
            Path suiteRoot,
            Path testCasePath,
            Map<String, Object> testCase,
            String fieldPath,
            Object expectedValue,
            List<ContractFinding> findings) {
        String ref = resolveDataRef(testCase, referenceText(expectedValue));
        if (!looksLikeExpectedArtifactRef(ref)) {
            return;
        }
        validateRuntimeCriticalRef(suiteRoot, testCasePath, fieldPath, ref, findings);
    }

    private void validateRuntimeCriticalRef(
            Path suiteRoot,
            Path testCasePath,
            String fieldPath,
            String ref,
            List<ContractFinding> findings) {
        String filePart = refFilePart(ref);
        if (filePart.isBlank()
                || ref.startsWith("${")
                || ref.contains("://")
                || "test_start_time".equals(ref)
                || "earliest".equals(ref)) {
            return;
        }
        Path resolved = suiteRoot.resolve(filePart).normalize();
        if (!resolved.startsWith(suiteRoot) || !Files.isRegularFile(resolved)) {
            findings.add(new ContractFinding(
                    testCasePath.toString(),
                    fieldPath,
                    "unresolved_artifact_ref",
                    "",
                    "",
                    "",
                    "",
                    "Restore runtime-critical artifact ref `" + filePart + "` under the suite directory before execution."));
        }
    }

    private void validateParameterRefWithinSuite(
            ContractGraph graph,
            Path testCasePath,
            Map<String, Object> testCase,
            OperationRef operationRef,
            int parameterIndex,
            String bindAs,
            String ref,
            TargetResolution targetResolution,
            List<ContractFinding> findings) {
        ref = resolveDataRef(testCase, ref);
        String filePart = refFilePart(ref);
        if (!looksLikeLocalFileRef(bindAs, ref, filePart)) {
            return;
        }
        Path suiteRoot = graph.suitePath().toAbsolutePath().normalize().getParent();
        Path resolved = suiteRoot.resolve(filePart).normalize();
        if (!resolved.startsWith(suiteRoot)) {
            findings.add(new ContractFinding(
                    testCasePath.toString(),
                    parameterFieldPath(operationRef, parameterIndex, operationRef.parameters().get(parameterIndex)),
                    "ref_outside_suite_root",
                    targetResolution.providerId(),
                    targetResolution.providerType(),
                    targetResolution.profile(),
                    operationRef.operation(),
                    "Keep parameter refs under the suite directory; use checked-in fixtures or expected_results."));
        }
    }

    private String resolveDataRef(Map<String, Object> testCase, String ref) {
        if (!ref.startsWith("${data.") || !ref.endsWith("}")) {
            return ref;
        }
        String path = ref.substring("${data.".length(), ref.length() - 1);
        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            String resolved = stringValue(mapValue(mapValue(testCase.get("data")).get(parts[0])).get("ref"));
            return resolved.isBlank() ? ref : resolved;
        }
        if (parts.length != 2) {
            return ref;
        }
        String resolved = stringValue(mapValue(mapValue(mapValue(testCase.get("data_binding")).get(parts[0]))
                .get(parts[1])).get("ref"));
        return resolved.isBlank() ? ref : resolved;
    }

    private void validateParameterValueSyntax(
            Path testCasePath,
            OperationRef operationRef,
            int parameterIndex,
            String bindAs,
            String ref,
            TargetResolution targetResolution,
            List<ContractFinding> findings) {
        if (!literalParameterValue(ref)) {
            return;
        }
        if (List.of("timeout", "poll_interval").contains(bindAs)) {
            try {
                Duration.parse(ref);
            } catch (RuntimeException e) {
                findings.add(new ContractFinding(
                        testCasePath.toString(),
                        parameterFieldPath(operationRef, parameterIndex, operationRef.parameters().get(parameterIndex)),
                        "invalid_duration",
                        targetResolution.providerId(),
                        targetResolution.providerType(),
                        targetResolution.profile(),
                        operationRef.operation(),
                        "Use an ISO-8601 duration for input `" + bindAs + "`, for example `PT5S`."));
            }
        }
        if ("consume_from".equals(bindAs)
                && !List.of("test_start_time", "earliest").contains(ref)) {
            try {
                Instant.parse(ref);
            } catch (RuntimeException e) {
                findings.add(new ContractFinding(
                        testCasePath.toString(),
                        parameterFieldPath(operationRef, parameterIndex, operationRef.parameters().get(parameterIndex)),
                        "invalid_instant",
                        targetResolution.providerId(),
                        targetResolution.providerType(),
                        targetResolution.profile(),
                        operationRef.operation(),
                        "Use `test_start_time`, `earliest`, or an ISO-8601 instant for input `consume_from`."));
            }
        }
    }

    private String refFilePart(String ref) {
        return ref.split("#", 2)[0];
    }

    private boolean looksLikeLocalFileRef(String bindAs, String ref, String filePart) {
        if (filePart.isBlank() || ref.startsWith("${") || ref.contains("://")) {
            return false;
        }
        if (ref.contains("#") || fileReferenceBindAs(bindAs)) {
            return true;
        }
        return filePart.startsWith(".")
                || !filePart.startsWith("/") && (filePart.contains("/") || filePart.contains("\\"));
    }

    private boolean looksLikeExpectedArtifactRef(String ref) {
        String filePart = refFilePart(ref);
        if (filePart.isBlank() || ref.startsWith("${") || ref.contains("://")) {
            return false;
        }
        String lower = filePart.toLowerCase(Locale.ROOT);
        return ref.contains("#")
                || filePart.startsWith(".")
                || filePart.contains("/")
                || filePart.contains("\\")
                || lower.endsWith(".json")
                || lower.endsWith(".yaml")
                || lower.endsWith(".yml")
                || lower.endsWith(".txt")
                || lower.endsWith(".csv")
                || lower.endsWith(".sql")
                || lower.endsWith(".xml");
    }

    private String referenceText(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stringValue(map.get("ref"));
        }
        return stringValue(value);
    }

    private boolean fileReferenceBindAs(String bindAs) {
        String normalized = bindAs.toLowerCase();
        return normalized.endsWith("_ref")
                || normalized.endsWith(".ref")
                || normalized.contains("file")
                || normalized.contains("fixture")
                || normalized.contains("mapping")
                || normalized.contains("sql")
                || normalized.contains("query");
    }

    private boolean literalParameterValue(String ref) {
        return !ref.isBlank()
                && !ref.startsWith("${")
                && !ref.contains("://")
                && !ref.contains("#")
                && !ref.startsWith(".")
                && !ref.contains("/")
                && !ref.contains("\\");
    }

    private void validateEnvironmentBindings(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<String, Map<String, Object>> entry : graph.providerInstances().entrySet()) {
            String providerId = entry.getKey();
            Map<String, Object> providerInstance = entry.getValue();
            String providerType = stringValue(providerInstance.get("provider_type"));
            Map<String, Object> contract = graph.providerContracts().get(providerType);
            if (contract == null) {
                continue;
            }
            Set<String> requiredBindingKeys = requiredBindingKeys(contract);
            for (String profile : graph.usedProfiles()) {
                if (!providerUsedInProfile(graph, providerId, profile)) {
                    continue;
                }
                Map<String, Object> providerBinding = providerBinding(graph, profile, providerId);
                if (providerBinding.isEmpty()) {
                    continue;
                }
                EnvironmentBindingDoc bindingDoc = graph.environmentBindingsByProfile().get(profile);
                Path bindingPath = bindingDoc.path();
                if (bindingDoc.envProfile()) {
                    validateEnvProfileProviderBinding(
                            graph,
                            bindingPath,
                            providerId,
                            providerType,
                            profile,
                            mapValue(mapValue(bindingDoc.document().get("providers")).get(providerId)),
                            contract,
                            findings);
                }
                validateRuntimeMode(bindingPath, bindingDoc.envProfile(), providerId, providerType, profile, providerBinding,
                        providerInstance, contract, graph, findings);
                Map<String, Object> bindingValues = mapValue(providerBinding.get("binding_values"));
                for (String requiredKey : requiredBindingKeys) {
                    if (isMissing(valueAtPath(bindingValues, requiredKey))) {
                        findings.add(new ContractFinding(
                                bindingPath.toString(),
                                bindingValueFieldPath(bindingDoc.envProfile(), providerId, requiredKey),
                                "missing_required_binding_key",
                                providerId,
                                providerType,
                                profile,
                                "",
                                "Supply binding key `" + requiredKey + "` for provider `" + providerId + "`."));
                    }
                }
                if ("jdbc".equals(providerType)) {
                    validateJdbcEnvironmentBinding(
                            bindingPath,
                            bindingDoc.envProfile(),
                            providerId,
                            providerType,
                            profile,
                            contract,
                            bindingValues,
                            findings);
                } else if ("nats".equals(providerType)) {
                    validateNatsEnvironmentBinding(
                            bindingPath,
                            bindingDoc.envProfile(),
                            providerId,
                            providerType,
                            profile,
                            bindingValues,
                            findings);
                }
            }
        }
    }

    private void validateEnvProfileProviderBinding(
            ContractGraph graph,
            Path bindingPath,
            String providerId,
            String providerType,
            String profile,
            Map<String, Object> provider,
            Map<String, Object> contract,
            List<ContractFinding> findings) {
        Map<String, Object> bindingKeys = mapValue(provider.get("binding_keys"));
        Map<String, Object> contractBindingKeys = mapValue(contract.get("binding_keys"));
        Set<String> allowedKeys = contractBindingKeys.keySet();
        for (Map.Entry<String, Object> entry : bindingKeys.entrySet()) {
            String rawKey = entry.getKey();
            List<String> valueKinds = bindingValueKinds(entry.getValue());
            if (valueKinds.size() > 1) {
                findings.add(new ContractFinding(
                        bindingPath.toString(),
                        "providers." + providerId + ".binding_keys." + rawKey,
                        "invalid_binding_key_value_kind",
                        providerId,
                        providerType,
                        profile,
                        "",
                        "Use exactly one binding value kind for Env_Profile binding key `" + rawKey + "`."));
                continue;
            }
            String kind = valueKinds.isEmpty() ? "value" : valueKinds.get(0);
            String effectiveKey = effectiveContractBindingKey(rawKey, kind, allowedKeys);
            Map<String, Object> keySpec = mapValue(contractBindingKeys.get(effectiveKey));
            if (keySpec.isEmpty() && !contractBindingKeys.containsKey(effectiveKey)) {
                findings.add(new ContractFinding(
                        bindingPath.toString(),
                        "providers." + providerId + ".binding_keys." + rawKey,
                        "unknown_binding_key",
                        providerId,
                        providerType,
                        profile,
                        "",
                        "Use a binding key declared by Provider Contract `" + providerType + "`. Unknown key `" + rawKey + "`."));
                continue;
            }
            Set<String> allowedKinds = allowedValueKinds(keySpec);
            if (!allowedKinds.isEmpty() && !allowedKinds.contains(kind)) {
                findings.add(new ContractFinding(
                        bindingPath.toString(),
                        "providers." + providerId + ".binding_keys." + rawKey + "." + kind,
                        "invalid_binding_key_value_kind",
                        providerId,
                        providerType,
                        profile,
                        "",
                        "Use one of " + allowedKinds + " for Provider Contract `" + providerType
                                + "` binding key `" + effectiveKey + "`."));
            }
            validateGeneratedRef(graph, bindingPath, providerId, providerType, profile, rawKey, kind,
                    bindingValue(entry.getValue(), kind), findings);
        }
    }

    private void validateRuntimeMode(
            Path bindingPath,
            boolean envProfile,
            String providerId,
            String providerType,
            String profile,
            Map<String, Object> providerBinding,
            Map<String, Object> providerInstance,
            Map<String, Object> contract,
            ContractGraph graph,
            List<ContractFinding> findings) {
        String runtimeMode = stringValue(providerBinding.get("runtime_mode"));
        String fieldPath = envProfile
                ? "providers." + providerId + ".runtime_mode"
                : "provider_bindings." + providerId + ".runtime_mode";
        if (runtimeMode.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    fieldPath,
                    "missing_required_field",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Set runtime_mode for provider `" + providerId + "`."));
            return;
        }
        validateRuntimeModeAllowed(bindingPath, fieldPath, providerId, providerType, profile, runtimeMode,
                stringList(contract.get("runtime_modes")), "Provider Contract", findings);
        validateRuntimeModeAllowed(bindingPath, fieldPath, providerId, providerType, profile, runtimeMode,
                stringList(contract.get("executable_runtime_modes")), "Provider Contract executable runtime support", findings);
        validateRuntimeModeAllowed(bindingPath, fieldPath, providerId, providerType, profile, runtimeMode,
                stringList(providerInstance.get("runtime_modes")), "Provider Instance", findings);
        validateRuntimeModeAllowed(bindingPath, fieldPath, providerId, providerType, profile, runtimeMode,
                allowedRuntimeModes(graph, profile), "Execution Profile", findings);
    }

    private void validateRuntimeModeAllowed(
            Path bindingPath,
            String fieldPath,
            String providerId,
            String providerType,
            String profile,
            String runtimeMode,
            List<String> allowedModes,
            String owner,
            List<ContractFinding> findings) {
        if (allowedModes.isEmpty() || allowedModes.contains(runtimeMode)) {
            return;
        }
        findings.add(new ContractFinding(
                bindingPath.toString(),
                fieldPath,
                "unsupported_runtime_mode",
                providerId,
                providerType,
                profile,
                "",
                "Use runtime_mode allowed by " + owner + " for provider `" + providerId
                        + "`: " + allowedModes + ". Unsupported runtime_mode: " + runtimeMode));
    }

    private List<String> allowedRuntimeModes(ContractGraph graph, String profile) {
        Map<String, Object> executionProfile = executionProfile(graph, profile);
        Map<String, Object> policy = mapValue(executionProfile.get("dependency_substitution_policy"));
        List<String> allowed = stringList(policy.get("allowed_runtime_modes"));
        if (!allowed.isEmpty()) {
            return allowed;
        }
        String executionMode = stringValue(executionProfile.get("execution_mode"));
        return executionMode.isBlank() ? List.of() : stringList(policy.get(executionMode + "_allowed_runtime_modes"));
    }

    private Map<String, Object> executionProfile(ContractGraph graph, String profile) {
        for (Map<String, Object> document : graph.executionProfiles().values()) {
            if (profile.equals(stringValue(document.get("profile_id")))) {
                return document;
            }
        }
        return Map.of();
    }

    private void validateJdbcContractsAndInstances(ContractGraph graph, List<ContractFinding> findings) {
        Map<String, Object> contract = graph.providerContracts().get("jdbc");
        if (contract != null) {
            List<String> supportedDialects = jdbcSupportedDialects(contract);
            for (String requiredDialect : List.of("oracle", "db2")) {
                if (!supportedDialects.contains(requiredDialect)) {
                    findings.add(new ContractFinding(
                            graph.suitePath().toString(),
                            "provider_contracts.jdbc.dialects.supported",
                            "missing_supported_dialect",
                            "",
                            "jdbc",
                            "",
                            "",
                            "Declare JDBC dialect `" + requiredDialect + "` in Provider Contract."));
                }
            }
            for (String requiredOperation : List.of("db_seed", "db_cleanup", "db_query", "db_record_exists")) {
                if (mapValue(contract.get("operations")).get(requiredOperation) == null) {
                    findings.add(new ContractFinding(
                            graph.suitePath().toString(),
                            "provider_contracts.jdbc.operations." + requiredOperation,
                            "unsupported_operation",
                            "",
                            "jdbc",
                            "",
                            requiredOperation,
                            "Declare JDBC operation `" + requiredOperation + "` in Provider Contract."));
                }
            }
            if (listValue(mapValue(contract.get("evidence")).get("outputs")).isEmpty()) {
                findings.add(new ContractFinding(
                        graph.suitePath().toString(),
                        "provider_contracts.jdbc.evidence.outputs",
                        "missing_evidence_output_definition",
                        "",
                        "jdbc",
                        "",
                        "",
                        "Define JDBC seed, query, and cleanup evidence outputs."));
            }
        }
    }

    private void validateNatsContractsAndInstances(ContractGraph graph, List<ContractFinding> findings) {
        Map<String, Object> contract = graph.providerContracts().get("nats");
        if (contract != null) {
            for (String requiredOperation : List.of("nats_publish", "nats_observe", "event_published", "event_payload_match")) {
                if (mapValue(contract.get("operations")).get(requiredOperation) == null) {
                    findings.add(new ContractFinding(
                            graph.suitePath().toString(),
                            "provider_contracts.nats.operations." + requiredOperation,
                            "unsupported_operation",
                            "",
                            "nats",
                            "",
                            requiredOperation,
                            "Declare NATS operation `" + requiredOperation + "` in Provider Contract."));
                }
            }
            List<String> failureCodes = stringList(mapValue(contract.get("failure_mapping")).get("allowed_codes"));
            for (String requiredCode : List.of(
                    "SUBJECT_MISSING",
                    "PAYLOAD_REF_MISSING",
                    "PAYLOAD_PARAM_MISSING",
                    "NATS_CONNECTION_FAILED",
                    "NATS_PUBLISH_FAILED",
                    "NATS_OBSERVE_FAILED",
                    "EVENT_NOT_FOUND",
                    "PAYLOAD_MISMATCH",
                    "NATS_TIMEOUT",
                    "INVALID_DURATION",
                    "INVALID_INSTANT",
                    "REF_OUTSIDE_SUITE_ROOT")) {
                if (!failureCodes.contains(requiredCode)) {
                    findings.add(new ContractFinding(
                            graph.suitePath().toString(),
                            "provider_contracts.nats.failure_mapping.allowed_codes",
                            "missing_failure_code",
                            "",
                            "nats",
                            "",
                            "",
                            "Declare NATS failure code `" + requiredCode + "` in Provider Contract."));
                }
            }
            if (listValue(mapValue(contract.get("evidence")).get("outputs")).isEmpty()) {
                findings.add(new ContractFinding(
                        graph.suitePath().toString(),
                        "provider_contracts.nats.evidence.outputs",
                        "missing_evidence_output_definition",
                        "",
                        "nats",
                        "",
                        "",
                        "Define NATS indexed event evidence outputs."));
            }
        }
    }

    private void validateJdbcEnvironmentBinding(
            Path bindingPath,
            boolean envProfile,
            String providerId,
            String providerType,
            String profile,
            Map<String, Object> contract,
            Map<String, Object> bindingValues,
            List<ContractFinding> findings) {
        String dialect = stringValue(valueAtPath(bindingValues, "dialect"));
        if (dialect.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "dialect"),
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply JDBC dialect `oracle` or `db2` in Environment Binding."));
        } else if (!jdbcSupportedDialects(contract).contains(dialect)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "dialect"),
                    "unsupported_dialect",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use supported JDBC dialect `oracle` or `db2`; unsupported dialect `" + dialect
                            + "` is outside PR-004."));
        }
        String secretRef = stringValue(valueAtPath(bindingValues, "connection.secret_ref"));
        if (secretRef.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "connection.secret_ref"),
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply JDBC connection.secret_ref; raw DB URLs, usernames, and passwords are prohibited."));
        }
    }

    private void validateNatsEnvironmentBinding(
            Path bindingPath,
            boolean envProfile,
            String providerId,
            String providerType,
            String profile,
            Map<String, Object> bindingValues,
            List<ContractFinding> findings) {
        Map<String, Object> connection = mapValue(bindingValues.get("connection"));
        String secretRef = stringValue(connection.get("secret_ref"));
        String localRef = stringValue(connection.get("local_ref"));
        if (secretRef.isBlank() && localRef.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "connection"),
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply NATS connection.secret_ref or approved connection.local_ref."));
        }
        if (!localRef.isBlank() && !approvedLocalNatsRef(localRef)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "connection.local_ref"),
                    "unsupported_local_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use an approved local_ref for local or CI NATS capability verification."));
        }
        String subject = stringValue(bindingValues.get("subject"));
        if (subject.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "subject"),
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply explicit NATS subject in Environment Binding; subjects are never inferred."));
        }
    }

    private boolean approvedLocalNatsRef(String localRef) {
        return localRef.startsWith("approved_local_nats")
                || localRef.startsWith("generated://provider-capability/nats/");
    }

    private String bindingValueFieldPath(boolean envProfile, String providerId, String bindingKey) {
        return envProfile
                ? "providers." + providerId + ".binding_keys." + bindingKey
                : "provider_bindings." + providerId + ".binding_values." + bindingKey;
    }

    private ContractGraph loadGraph(Path suiteManifest) {
        List<ContractFinding> findings = new ArrayList<>();
        Map<String, Object> suite = readYamlMap(suiteManifest, findings);
        if (suite.isEmpty()) {
            return ContractGraph.blocked(suiteManifest, suite, findings);
        }
        Path root = suiteManifest.toAbsolutePath().normalize().getParent();
        List<Path> testPaths = listValue(suite.get("tests")).stream()
                .map(value -> root.resolve(stringValue(value)).normalize())
                .toList();
        Map<Path, Map<String, Object>> testCases = readAll(testPaths, findings);
        Map<Path, Map<String, Object>> frameworkContractsByPath =
                readDirectory(frameworkProviderContractsDirectory(root), findings);
        Map<Path, Map<String, Object>> suiteLocalContractsByPath =
                readSuiteLocalProviderContracts(root, suite, findings);
        Map<Path, Map<String, Object>> contractsByPath = new LinkedHashMap<>();
        contractsByPath.putAll(frameworkContractsByPath);
        contractsByPath.putAll(suiteLocalContractsByPath);
        Map<Path, Map<String, Object>> instancesByPath = readDirectory(root.resolve("provider_instances"), findings);
        Path envProfilesRoot = root.resolve("env_profiles");
        Map<Path, Map<String, Object>> envProfiles = readOptionalDirectory(envProfilesRoot, findings);
        boolean envProfileMode = Files.isDirectory(envProfilesRoot);
        Map<Path, Map<String, Object>> profiles = envProfileMode
                ? Map.of()
                : readDirectory(root.resolve("execution_profiles"), findings);
        Map<Path, Map<String, Object>> environmentBindings = envProfileMode
                ? Map.of()
                : readDirectory(root.resolve("environment_bindings"), findings);

        Map<String, Map<String, Object>> contracts = new LinkedHashMap<>();
        for (Map<String, Object> contract : contractsByPath.values()) {
            String providerType = stringValue(contract.get("provider_type"));
            if (!providerType.isBlank()) {
                contracts.put(providerType, contract);
            }
        }
        Map<String, Map<String, Object>> instances = new LinkedHashMap<>();
        for (Map<String, Object> instance : instancesByPath.values()) {
            String providerId = stringValue(instance.get("provider_id"));
            if (!providerId.isBlank()) {
                instances.put(providerId, instance);
            }
        }
        Map<String, EnvironmentBindingDoc> bindingsByProfile = new LinkedHashMap<>();
        if (envProfileMode) {
            for (Map.Entry<Path, Map<String, Object>> entry : envProfiles.entrySet()) {
                String profile = stringValue(entry.getValue().get("env_profile_id"));
                if (!profile.isBlank()) {
                    bindingsByProfile.put(profile, new EnvironmentBindingDoc(entry.getKey(), entry.getValue(), true));
                }
            }
        } else {
            for (Map.Entry<Path, Map<String, Object>> entry : environmentBindings.entrySet()) {
                String profile = stringValue(entry.getValue().get("profile"));
                if (!profile.isBlank()) {
                    bindingsByProfile.put(profile, new EnvironmentBindingDoc(entry.getKey(), entry.getValue(), false));
                }
            }
        }
        Set<String> usedProfiles = usedProfiles(suite, testCases);
        return new ContractGraph(
                suiteManifest,
                suite,
                stringValue(suite.get("suite_id")),
                testCases,
                contractsByPath,
                contracts,
                instancesByPath,
                instances,
                profiles,
                envProfiles,
                envProfileMode,
                environmentBindings,
                bindingsByProfile,
                new LinkedHashSet<>(usedProfiles),
                allowedProviderTypes(suite, frameworkContractsByPath),
                List.copyOf(findings));
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

    private Map<Path, Map<String, Object>> readSuiteLocalProviderContracts(
            Path root,
            Map<String, Object> suite,
            List<ContractFinding> findings) {
        Map<String, Object> resolution = mapValue(suite.get("provider_contract_resolution"));
        String mode = stringValue(resolution.get("mode"));
        if (mode.isBlank() || "framework_builtin".equals(mode)) {
            return Map.of();
        }
        if (!List.of("suite_override", "snapshot").contains(mode)) {
            findings.add(finding(
                    root.resolve("suite_manifest.yaml"),
                    "provider_contract_resolution.mode",
                    "unsupported_contract_resolution_mode",
                    "Use `framework_builtin`, `suite_override`, or `snapshot` for provider_contract_resolution.mode."));
            return Map.of();
        }
        String directory = stringValue(resolution.get("custom_provider_contracts"));
        if (directory.isBlank()) {
            directory = "custom-provider-contracts";
        }
        return readDirectory(root.resolve(directory), findings);
    }

    private Set<String> allowedProviderTypes(
            Map<String, Object> suite,
            Map<Path, Map<String, Object>> frameworkContractsByPath) {
        Set<String> allowed = new LinkedHashSet<>();
        for (Map<String, Object> contract : frameworkContractsByPath.values()) {
            addIfPresent(allowed, stringValue(contract.get("provider_type")));
        }
        Map<String, Object> resolution = mapValue(suite.get("provider_contract_resolution"));
        String mode = stringValue(resolution.get("mode"));
        if ("suite_override".equals(mode) || "snapshot".equals(mode)) {
            for (String providerType : stringList(resolution.get("allowed_provider_types"))) {
                addIfPresent(allowed, providerType);
            }
        }
        return allowed;
    }

    private Set<String> usedProfiles(Map<String, Object> suite, Map<Path, Map<String, Object>> testCases) {
        Set<String> profiles = new LinkedHashSet<>();
        addIfPresent(profiles, stringValue(suite.get("profile")));
        for (Object profile : listValue(suite.get("profiles"))) {
            addIfPresent(profiles, stringValue(profile));
        }
        addIfPresent(profiles, stringValue(mapValue(suite.get("selection")).get("profile")));
        if (!profiles.isEmpty()) {
            return new LinkedHashSet<>(profiles);
        }
        for (Map<String, Object> testCase : testCases.values()) {
            for (Object targetValue : mapValue(testCase.get("targets")).values()) {
                addIfPresent(profiles, stringValue(mapValue(targetValue).get("profile")));
            }
        }
        return new LinkedHashSet<>(profiles);
    }

    private void addIfPresent(Set<String> values, String value) {
        if (!value.isBlank()) {
            values.add(value);
        }
    }

    private Map<Path, Map<String, Object>> readAll(List<Path> paths, List<ContractFinding> findings) {
        Map<Path, Map<String, Object>> documents = new LinkedHashMap<>();
        for (Path path : paths) {
            documents.put(path, readYamlMap(path, findings));
        }
        return documents;
    }

    private Map<Path, Map<String, Object>> readDirectory(Path directory, List<ContractFinding> findings) {
        if (!Files.isDirectory(directory)) {
            findings.add(finding(directory, directory.getFileName().toString(), "missing_required_directory",
                    "Create required contract artifact directory `" + directory + "`."));
            return Map.of();
        }
        try (var paths = Files.list(directory)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            return readAll(files, findings);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read contract directory: " + directory, e);
        }
    }

    private Map<Path, Map<String, Object>> readOptionalDirectory(Path directory, List<ContractFinding> findings) {
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        return readDirectory(directory, findings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path, List<ContractFinding> findings) {
        if (!Files.isRegularFile(path)) {
            findings.add(finding(path, path.toString(), "missing_required_file",
                    "Create required contract artifact `" + path + "`."));
            return Map.of();
        }
        try {
            Object loaded = yaml.load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            findings.add(finding(path, path.toString(), "invalid_yaml",
                    "Use a YAML mapping document for `" + path + "`."));
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML: " + path, e);
        } catch (RuntimeException e) {
            findings.add(finding(path, path.toString(), "invalid_yaml",
                    "Fix malformed YAML before validation."));
            return Map.of();
        }
    }

    private void require(Path path, Map<String, Object> document, String field, List<ContractFinding> findings) {
        if ("execute".equals(field) && document.containsKey(field)) {
            return;
        }
        if (isMissingRequiredField(field, document.get(field))) {
            findings.add(finding(path, field, "missing_required_field", "Add required field `" + field + "`."));
        }
    }

    private boolean isMissingRequiredField(String field, Object value) {
        if ("binding_keys".equals(field) && value instanceof Map<?, ?>) {
            return false;
        }
        return isMissing(value);
    }

    private boolean environmentBindingForProvider(ContractGraph graph, String profile, String providerId) {
        return !providerBinding(graph, profile, providerId).isEmpty();
    }

    private List<String> bindingValueKinds(Object value) {
        Map<String, Object> map = mapValue(value);
        if (map.isEmpty()) {
            return List.of();
        }
        List<String> kinds = new ArrayList<>();
        for (String kind : BINDING_VALUE_KIND_FIELDS) {
            if (map.containsKey(kind)) {
                kinds.add(kind);
            }
        }
        return List.copyOf(kinds);
    }

    private Object bindingValue(Object value, String kind) {
        Map<String, Object> map = mapValue(value);
        return map.isEmpty() ? value : map.get(kind);
    }

    private String effectiveContractBindingKey(String rawKey, String valueKind, Set<String> contractKeys) {
        if (contractKeys.contains(rawKey)) {
            return rawKey;
        }
        String nestedKey = rawKey + "." + valueKind;
        if (contractKeys.contains(nestedKey)) {
            return nestedKey;
        }
        return rawKey;
    }

    private Set<String> allowedValueKinds(Map<String, Object> keySpec) {
        Set<String> explicit = new LinkedHashSet<>(stringList(keySpec.get("allowed_value_kinds")));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String source = stringValue(keySpec.get("source")).toLowerCase(Locale.ROOT);
        if (source.contains("secret_ref_or_approved_local_ref")) {
            return Set.of("secret_ref", "local_ref");
        }
        if (source.contains("secret_ref")) {
            return Set.of("secret_ref", "generated_ref");
        }
        if (source.contains("env_profile") || source.contains("environment_binding")) {
            return Set.of("value", "ref", "generated_ref", "secret_ref", "local_ref");
        }
        return Set.of();
    }

    private void validateGeneratedRef(
            ContractGraph graph,
            Path bindingPath,
            String providerId,
            String providerType,
            String profile,
            String rawKey,
            String kind,
            Object value,
            List<ContractFinding> findings) {
        if (!"generated_ref".equals(kind)) {
            return;
        }
        String generatedRef = stringValue(value);
        String prefix = "generated://";
        if (!generatedRef.startsWith(prefix)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + ".binding_keys." + rawKey + ".generated_ref",
                    "invalid_generated_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use generated_ref format `generated://<provider_id>.<bindable_output>` or an approved provisioner-generated ref."));
            return;
        }
        String target = generatedRef.substring(prefix.length());
        int separator = target.indexOf('.');
        if (separator < 1 || separator == target.length() - 1) {
            return;
        }
        String generatedProviderId = target.substring(0, separator);
        String output = target.substring(separator + 1);
        Map<String, Object> generatedProvider = graph.providerInstances().get(generatedProviderId);
        if (generatedProvider == null) {
            return;
        }
        String generatedProviderType = stringValue(generatedProvider.get("provider_type"));
        Map<String, Object> generatedContract = graph.providerContracts().get(generatedProviderType);
        if (generatedContract == null) {
            return;
        }
        if (!mapValue(generatedContract.get("bindable_outputs")).containsKey(output)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + ".binding_keys." + rawKey + ".generated_ref",
                    "unknown_bindable_output",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Reference a bindable output declared by Provider Contract `" + generatedProviderType
                            + "`. Unknown output `" + output + "`."));
        }
    }

    private Map<String, Object> providerBinding(ContractGraph graph, String profile, String providerId) {
        EnvironmentBindingDoc bindingDoc = graph.environmentBindingsByProfile().get(profile);
        if (bindingDoc == null) {
            return Map.of();
        }
        if (bindingDoc.envProfile()) {
            Map<String, Object> provider = mapValue(mapValue(bindingDoc.document().get("providers")).get(providerId));
            if (provider.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("provider_id", providerId);
            normalized.put("runtime_mode", provider.get("runtime_mode"));
            normalized.put("binding_values", normalizeEnvProfileBindingKeys(mapValue(provider.get("binding_keys"))));
            return normalized;
        }
        for (Object value : listValue(bindingDoc.document().get("provider_bindings"))) {
            Map<String, Object> providerBinding = mapValue(value);
            if (providerId.equals(stringValue(providerBinding.get("provider_id")))) {
                return providerBinding;
            }
        }
        return Map.of();
    }

    private Map<String, Object> normalizeEnvProfileBindingKeys(Map<String, Object> bindingKeys) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : bindingKeys.entrySet()) {
            putPath(normalized, entry.getKey(), normalizeEnvProfileBindingValue(entry.getValue()));
        }
        return normalized;
    }

    private Object normalizeEnvProfileBindingValue(Object value) {
        Map<String, Object> map = mapValue(value);
        if (map.isEmpty()) {
            return value;
        }
        if (map.containsKey("value")) {
            return map.get("value");
        }
        if (map.containsKey("ref")) {
            return map.get("ref");
        }
        if (map.containsKey("generated_ref")) {
            return map.get("generated_ref");
        }
        if (map.containsKey("secret_ref") && map.size() == 1) {
            return map;
        }
        return map;
    }

    private void putPath(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object existing = cursor.get(parts[i]);
            if (existing instanceof Map<?, ?>) {
                cursor = writableMap(existing);
            } else {
                Map<String, Object> nested = new LinkedHashMap<>();
                cursor.put(parts[i], nested);
                cursor = nested;
            }
        }
        cursor.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> writableMap(Object value) {
        return (Map<String, Object>) value;
    }

    private boolean providerUsedInProfile(ContractGraph graph, String providerId, String profile) {
        for (Map<String, Object> testCase : graph.testCases().values()) {
            for (Object value : mapValue(testCase.get("targets")).values()) {
                Map<String, Object> target = mapValue(value);
                if (providerId.equals(stringValue(target.get("provider_id")))
                        && graph.usedProfiles().contains(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> requiredBindingKeys(Map<String, Object> contract) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : mapValue(contract.get("binding_keys")).entrySet()) {
            Map<String, Object> keySpec = mapValue(entry.getValue());
            if (Boolean.TRUE.equals(keySpec.get("required"))) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    private List<String> jdbcSupportedDialects(Map<String, Object> contract) {
        Map<String, Object> dialects = mapValue(contract.get("dialects"));
        List<String> supported = stringList(dialects.get("supported"));
        if (supported.isEmpty()) {
            supported = stringList(dialects.get("allowed"));
        }
        return supported;
    }

    private List<OperationRef> operationRefs(Map<String, Object> testCase) {
        List<OperationRef> operations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("setup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            operations.add(new OperationRef(
                    "setup.fixtures." + entry.getKey(),
                    stringValue(fixture.get("target")),
                    stringValue(fixture.get("operation")),
                    operationInputs(fixture, "setup.fixtures." + entry.getKey()),
                    Map.of(),
                    fixture.containsKey("inputs")));
        }
        for (Object value : listValue(mapValue(testCase.get("setup")).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            String id = stringValue(operation.get("id"));
            String path = id.isBlank() ? "setup.operations[]" : "setup." + id;
            operations.add(new OperationRef(
                    path,
                    stringValue(operation.get("target")),
                    stringValue(operation.get("operation")),
                    operationInputs(operation, path),
                    Map.of(),
                    operation.containsKey("inputs")));
        }
        for (Object value : executeOperations(testCase)) {
            Map<String, Object> step = mapValue(value);
            String id = stringValue(step.get("id"));
            String path = id.isBlank() ? "execute[]" : "execute." + id;
            operations.add(new OperationRef(
                    path,
                    stringValue(step.get("target")),
                    stringValue(step.get("operation")),
                    operationInputs(step, path),
                    mapValue(step.get("outputs")),
                    step.containsKey("inputs")));
        }
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("cleanup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            operations.add(new OperationRef(
                    "cleanup.fixtures." + entry.getKey(),
                    stringValue(fixture.get("target")),
                    stringValue(fixture.get("operation")),
                    operationInputs(fixture, "cleanup.fixtures." + entry.getKey()),
                    Map.of(),
                    fixture.containsKey("inputs")));
        }
        for (Object value : listValue(mapValue(testCase.get("cleanup")).get("operations"))) {
            Map<String, Object> operation = mapValue(value);
            String id = stringValue(operation.get("id"));
            String path = id.isBlank() ? "cleanup.operations[]" : "cleanup." + id;
            operations.add(new OperationRef(
                    path,
                    stringValue(operation.get("target")),
                    stringValue(operation.get("operation")),
                    operationInputs(operation, path),
                    Map.of(),
                    operation.containsKey("inputs")));
        }
        for (Object value : verifyChecks(testCase)) {
            Map<String, Object> verify = mapValue(value);
            String id = stringValue(verify.get("id"));
            String path = id.isBlank() ? "verify[]" : "verify." + id;
            List<Map<String, Object>> parameters = operationInputs(verify, path);
            if (!parameters.isEmpty() || !stringValue(verify.get("target")).isBlank()) {
                operations.add(new OperationRef(
                        path,
                        stringValue(verify.get("target")),
                        stringValue(verify.get("type")),
                        parameters,
                        Map.of(),
                        verify.containsKey("inputs")));
            }
        }
        return operations;
    }

    private List<Object> executeOperations(Map<String, Object> testCase) {
        Object execute = testCase.get("execute");
        if (execute instanceof Map<?, ?> executeMap) {
            return listValue(executeMap.get("operations"));
        }
        return listValue(execute);
    }

    private List<Object> verifyChecks(Map<String, Object> testCase) {
        Object verify = testCase.get("verify");
        if (verify instanceof Map<?, ?> verifyMap) {
            return listValue(verifyMap.get("checks"));
        }
        return listValue(verify);
    }

    private List<Map<String, Object>> operationInputs(Map<String, Object> operation, String fieldPath) {
        if (operation.get("inputs") instanceof Map<?, ?> inputs) {
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Map.Entry<?, ?> entry : inputs.entrySet()) {
                String inputName = stringValue(entry.getKey());
                Map<String, Object> source = new LinkedHashMap<>(mapValue(entry.getValue()));
                source.put("__input_name", inputName);
                source.put("__field_path", fieldPath + ".inputs." + inputName);
                source.putIfAbsent("bind_as", inputName);
                normalized.add(source);
            }
            return List.copyOf(normalized);
        }
        return mapList(operation.get("parameters"));
    }

    private TargetResolution resolveTarget(ContractGraph graph, Map<String, Object> testCase, String targetName) {
        Map<String, Object> target = mapValue(mapValue(testCase.get("targets")).get(targetName));
        String providerId = stringValue(target.get("provider_id"));
        String profile = selectedProfile(graph, target);
        Map<String, Object> instance = graph.providerInstances().get(providerId);
        if (instance == null) {
            return TargetResolution.unresolved(providerId, profile);
        }
        String providerType = stringValue(instance.get("provider_type"));
        Map<String, Object> contract = graph.providerContracts().get(providerType);
        if (contract == null) {
            return TargetResolution.unresolved(providerId, profile, providerType);
        }
        return new TargetResolution(providerId, profile, providerType, contract);
    }

    private ValidationResult validationResult(ContractGraph graph, List<ContractFinding> findings) {
        List<ResolvedTarget> plan = findings.isEmpty() ? resolvedPlan(graph) : List.of();
        return new ValidationResult(
                findings.isEmpty(),
                graph.suiteId(),
                providerIds(graph),
                providerTypes(graph),
                plan,
                List.copyOf(findings));
    }

    private List<String> providerIds(ContractGraph graph) {
        return graph.providerInstances().keySet().stream().sorted().toList();
    }

    private List<String> providerTypes(ContractGraph graph) {
        Set<String> providerTypes = new LinkedHashSet<>();
        for (Map<String, Object> testCase : graph.testCases().values()) {
            for (Object targetValue : mapValue(testCase.get("targets")).values()) {
                String providerId = stringValue(mapValue(targetValue).get("provider_id"));
                Map<String, Object> instance = graph.providerInstances().get(providerId);
                if (instance != null) {
                    addIfPresent(providerTypes, stringValue(instance.get("provider_type")));
                }
            }
        }
        return providerTypes.stream().sorted().toList();
    }

    private List<ResolvedTarget> resolvedPlan(ContractGraph graph) {
        List<ResolvedTarget> plan = new ArrayList<>();
        for (Map<String, Object> testCase : graph.testCases().values()) {
            String testCaseId = stringValue(testCase.get("test_case_id"));
            for (Map.Entry<String, Object> entry : mapValue(testCase.get("targets")).entrySet()) {
                Map<String, Object> target = mapValue(entry.getValue());
                String providerId = stringValue(target.get("provider_id"));
                Map<String, Object> instance = graph.providerInstances().get(providerId);
                if (instance == null) {
                    continue;
                }
                String providerType = stringValue(instance.get("provider_type"));
                for (String profile : graph.usedProfiles()) {
                    Map<String, Object> providerBinding = providerBinding(graph, profile, providerId);
                    plan.add(new ResolvedTarget(
                            testCaseId,
                            entry.getKey(),
                            providerId,
                            providerType,
                            profile,
                            stringValue(providerBinding.get("runtime_mode"))));
                }
            }
        }
        return List.copyOf(plan);
    }

    private String selectedProfile(ContractGraph graph, Map<String, Object> target) {
        if (!graph.usedProfiles().isEmpty()) {
            return graph.usedProfiles().iterator().next();
        }
        return stringValue(target.get("profile"));
    }

    private boolean allowedBindAs(List<String> allowedBindAs, String bindAs) {
        for (String allowed : allowedBindAs) {
            if (allowed.endsWith(".*") && bindAs.startsWith(allowed.substring(0, allowed.length() - 1))) {
                return true;
            }
            if (allowed.equals(bindAs)) {
                return true;
            }
        }
        return false;
    }

    private List<String> operationInputs(Map<String, Object> operation, String preferredField, String legacyField) {
        List<String> inputs = stringList(operation.get(preferredField));
        return inputs.isEmpty() ? stringList(operation.get(legacyField)) : inputs;
    }

    private boolean providedInput(List<String> providedInputs, String requiredInput) {
        for (String provided : providedInputs) {
            if (allowedBindAs(List.of(requiredInput), provided)) {
                return true;
            }
        }
        return false;
    }

    private String parameterInputName(Map<String, Object> parameter) {
        return firstNonBlank(
                stringValue(parameter.get("__input_name")),
                stringValue(parameter.get("input")),
                stringValue(parameter.get("name")),
                stringValue(parameter.get("bind_as")));
    }

    private String parameterSource(Map<String, Object> parameter) {
        return firstNonBlank(stringValue(parameter.get("ref")), stringValue(parameter.get("value")));
    }

    private String parameterFieldPath(OperationRef operationRef, int parameterIndex, Map<String, Object> parameter) {
        String fieldPath = stringValue(parameter.get("__field_path"));
        if (!fieldPath.isBlank()) {
            return fieldPath;
        }
        return operationRef.fieldPath() + ".parameters[" + parameterIndex + "]";
    }

    private void detectRawSecrets(String filePath, Object value, List<ContractFinding> findings) {
        detectRawSecrets(filePath, "", value, findings, false);
    }

    private void detectRawSecrets(
            String filePath,
            String fieldPath,
            Object value,
            List<ContractFinding> findings,
            boolean secretContext) {
        if (value instanceof Map<?, ?> map) {
            if (Boolean.TRUE.equals(map.get("sensitive")) && map.containsKey("value")) {
                findings.add(new ContractFinding(
                        filePath,
                        fieldPath + ".value",
                        "raw_secret",
                        "",
                        "",
                        "",
                        "",
                        "Replace sensitive raw value with `secret_ref`."));
            }
            if (secretContext && map.containsKey("value")) {
                findings.add(new ContractFinding(
                        filePath,
                        fieldPath + ".value",
                        "raw_secret",
                        "",
                        "",
                        "",
                        "",
                        "Replace raw secret value with `secret_ref`."));
            }
            if (secretContext && !map.containsKey("secret_ref") && !map.containsKey("value")
                    && !map.containsKey("required") && !map.containsKey("source")) {
                for (Object nestedValue : map.values()) {
                    if (nestedValue instanceof Map<?, ?> || nestedValue instanceof Collection<?>) {
                        continue;
                    }
                    String text = stringValue(nestedValue);
                    if (!text.isBlank() && !safeSecretReference(text)) {
                        findings.add(new ContractFinding(
                                filePath,
                                fieldPath,
                                "raw_secret",
                                "",
                                "",
                                "",
                                "",
                                "Replace raw secret value with `secret_ref`."));
                        break;
                    }
                }
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                String childPath = fieldPath.isBlank() ? key : fieldPath + "." + key;
                boolean childSecretContext = secretContext || secretKey(key);
                if (!"secret_ref".equals(key)) {
                    detectRawSecrets(filePath, childPath, entry.getValue(), findings, childSecretContext);
                }
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                detectRawSecrets(filePath, fieldPath + "[" + index++ + "]", item, findings, secretContext);
            }
            return;
        }
        if (value instanceof String text && rawSecretTextDetected(text)) {
            findings.add(new ContractFinding(
                    filePath,
                    fieldPath,
                    "raw_secret",
                    "",
                    "",
                    "",
                    "",
                    "Replace raw secret or connection string with `secret_ref`."));
            return;
        }
        if (secretContext && value != null && !stringValue(value).isBlank() && !(value instanceof Boolean)
                && !safeSecretReference(stringValue(value))) {
            findings.add(new ContractFinding(
                    filePath,
                    fieldPath,
                    "raw_secret",
                    "",
                    "",
                    "",
                    "",
                    "Replace raw secret value with `secret_ref`."));
        }
    }

    private boolean secretKey(String key) {
        String normalized = key.toLowerCase();
        return normalized.equals("password")
                || normalized.equals("token")
                || normalized.equals("credential")
                || normalized.equals("credentials")
                || normalized.equals("secret")
                || normalized.endsWith(".secret_ref")
                || normalized.endsWith("_secret_ref")
                || normalized.equals("connection")
                || normalized.equals("api_key")
                || normalized.equals("authorization");
    }

    private boolean rawSecretTextDetected(String value) {
        if (safeSecretReference(value) || value.startsWith("secret://") || value.startsWith("vault://")) {
            return false;
        }
        return JDBC_URL.matcher(value).find()
                || NATS_CREDENTIAL.matcher(value).find()
                || BEARER_TOKEN.matcher(value).find()
                || CONNECTION_URI.matcher(value).find();
    }

    private boolean safeSecretReference(String value) {
        return value.startsWith("vault://")
                || value.startsWith("generated://")
                || value.startsWith("${")
                || value.endsWith("_ref")
                || value.endsWith(".password");
    }

    private ContractFinding finding(Path file, String fieldPath, String reason, String ownerAction) {
        return new ContractFinding(file.toString(), fieldPath, reason, "", "", "", "", ownerAction);
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

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : listValue(value)) {
            maps.add(mapValue(item));
        }
        return List.copyOf(maps);
    }

    private List<String> stringList(Object value) {
        List<String> strings = new ArrayList<>();
        for (Object item : listValue(value)) {
            strings.add(stringValue(item));
        }
        return List.copyOf(strings);
    }

    private boolean isMissing(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private boolean isMissingResultField(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private Object valueAtPath(Map<String, Object> map, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
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

    public record ValidationResult(
            boolean valid,
            String suiteId,
            List<String> providerInstancesUsed,
            List<String> providerTypesUsed,
            List<ResolvedTarget> plan,
            List<ContractFinding> findings) {
    }

    public record DryRunResult(
            boolean ready,
            ValidationResult validation,
            List<ResolvedTarget> plan) {
    }

    public record ReportResult(
            boolean valid,
            boolean invalid,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            String status,
            String profile,
            int providerResultsCount,
            int verifyResultsCount,
            boolean releaseEvidenceEligible,
            List<String> failedVerifySummary,
            List<ContractFinding> findings) {
    }

    public record ResolvedTarget(
            String testCaseId,
            String target,
            String providerId,
            String providerType,
            String profile,
            String runtimeMode) {
    }

    public record ContractFinding(
            String filePath,
            String fieldPath,
            String reason,
            String providerId,
            String providerType,
            String profile,
            String operation,
            String ownerAction,
            String failureCode,
            String category,
            String originalCause) {

        public ContractFinding(
                String filePath,
                String fieldPath,
                String reason,
                String providerId,
                String providerType,
                String profile,
                String operation,
                String ownerAction) {
            this(
                    filePath,
                    fieldPath,
                    reason,
                    providerId,
                    providerType,
                    profile,
                    operation,
                    ownerAction,
                    failureCodeFor(reason),
                    categoryFor(reason),
                    "");
        }

        private static String failureCodeFor(String reason) {
            String normalized = reason == null || reason.isBlank()
                    ? "UNKNOWN"
                    : reason.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_|_$)", "");
            return categoryFor(reason).replace("_ERROR", "") + "_" + normalized;
        }

        private static String categoryFor(String reason) {
            return switch (reason == null ? "" : reason) {
                case "raw_secret" -> "SECRET_GUARDRAIL_ERROR";
                case "missing_evidence_index", "missing_evidence_file", "unknown_evidence_ref",
                        "missing_required_evidence", "missing_failure_evidence",
                        "missing_polling_last_observed_evidence", "invalid_evidence_path",
                        "missing_required_evidence_field", "unsupported_evidence_type",
                        "duplicate_evidence_id", "masking_not_applied",
                        "missing_provider_metadata", "missing_failure_code",
                        "unreadable_evidence_file", "invalid_evidence_index" -> "EVIDENCE_ERROR";
                case "cleanup_failure_not_indexed", "cleanup_failure_hides_original_failure" -> "CLEANUP_ERROR";
                case "missing_provider_instance", "missing_environment_binding", "missing_execution_profile",
                        "missing_env_profile", "missing_env_profile_provider_binding",
                        "missing_required_binding_key", "unsupported_runtime_mode",
                        "invalid_target_ref", "unresolved_artifact_ref",
                        "unsupported_local_ref", "missing_jdbc_target", "missing_nats_target",
                        "profile_mismatch", "invalid_generated_ref" -> "CONFIGURATION_ERROR";
                case "unknown_provider_type", "unsupported_operation", "unsupported_bind_as",
                        "unsupported_input",
                        "unsupported_dialect", "missing_output_ref", "missing_supported_dialect",
                        "missing_evidence_output_definition", "missing_required_parameter",
                        "missing_required_input",
                        "unsupported_suite_runtime", "unsupported_suite_shape",
                        "unsupported_provider_instance_field",
                        "unsupported_provider_instance_binding_key", "unknown_binding_key",
                        "invalid_binding_key_value_kind", "unknown_bindable_output" -> "CONTRACT_ERROR";
                case "invalid_result_json", "missing_result_json", "invalid_yaml",
                        "missing_required_file", "missing_required_directory",
                        "missing_required_field", "prohibited_legacy_field",
                        "prohibited_data_catalog_field",
                        "prohibited_governance_field", "prohibited_deprecated_field",
                        "prohibited_data_binding_category", "prohibited_source_ref", "invalid_source_refs",
                        "invalid_duration", "invalid_status", "invalid_test_count", "invalid_test_results",
                        "invalid_instant", "invalid_polling_config" -> "VALIDATION_ERROR";
                default -> "VALIDATION_ERROR";
            };
        }
    }

    private record ContractGraph(
            Path suitePath,
            Map<String, Object> suite,
            String suiteId,
            Map<Path, Map<String, Object>> testCases,
            Map<Path, Map<String, Object>> providerContractsByPath,
            Map<String, Map<String, Object>> providerContracts,
            Map<Path, Map<String, Object>> providerInstancesByPath,
            Map<String, Map<String, Object>> providerInstances,
            Map<Path, Map<String, Object>> executionProfiles,
            Map<Path, Map<String, Object>> envProfiles,
            boolean envProfileMode,
            Map<Path, Map<String, Object>> environmentBindings,
            Map<String, EnvironmentBindingDoc> environmentBindingsByProfile,
            Set<String> usedProfiles,
            Set<String> allowedProviderTypes,
            List<ContractFinding> loadFindings) {

        static ContractGraph blocked(Path suitePath, Map<String, Object> suite, List<ContractFinding> findings) {
            return new ContractGraph(
                    suitePath,
                    suite,
                    "",
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    false,
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    List.copyOf(findings));
        }

        boolean loadBlocked() {
            return !loadFindings.isEmpty();
        }
    }

    private record EnvironmentBindingDoc(Path path, Map<String, Object> document, boolean envProfile) {
    }

    private record OperationRef(
            String fieldPath,
            String target,
            String operation,
            List<Map<String, Object>> parameters,
            Map<String, Object> outputs,
            boolean modernInputs) {
    }

    private record TargetResolution(
            String providerId,
            String profile,
            String providerType,
            Map<String, Object> contract) {

        static TargetResolution unresolved(String providerId, String profile) {
            return new TargetResolution(providerId, profile, "", Map.of());
        }

        static TargetResolution unresolved(String providerId, String profile, String providerType) {
            return new TargetResolution(providerId, profile, providerType, Map.of());
        }

        boolean resolved() {
            return !providerId.isBlank() && !providerType.isBlank() && !contract.isEmpty();
        }

        Map<String, Object> operation(String operation) {
            return mapValueStatic(mapValueStatic(contract.get("operations")).get(operation));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> mapValueStatic(Object value) {
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        }
    }
}
