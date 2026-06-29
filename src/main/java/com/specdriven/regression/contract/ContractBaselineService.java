package com.specdriven.regression.contract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public class ContractBaselineService {

    private static final Set<String> LEGACY_FIELDS = Set.of(
            "rp_id",
            "ac_id",
            "execution_target",
            "package_inputs",
            "oracles",
            "steps",
            "assertions",
            "evidence_required");
    private static final Set<String> GOVERNANCE_FIELDS = Set.of(
            "approval_status",
            "waiver",
            "release_gate",
            "risk_approval");
    private static final Set<String> ALLOWED_PROVIDER_TYPES = Set.of(
            "wiremock_http_mock",
            "jdbc",
            "jdbc_database",
            "nats_messaging",
            "sample_fake_provider");
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

    private final Yaml yaml = new Yaml();

    public ValidationResult validateSuite(Path suiteManifest) {
        ContractGraph graph = loadGraph(suiteManifest);
        List<ContractFinding> findings = new ArrayList<>(graph.loadFindings());
        if (graph.loadBlocked()) {
            return new ValidationResult(false, graph.suiteId(), List.of(), List.of(), List.of(), List.copyOf(findings));
        }
        validateRequiredFields(graph, findings);
        validateProhibitedFields(graph, findings);
        validateRawSecrets(graph, findings);
        validateTargets(graph, findings);
        validateExecutionProfiles(graph, findings);
        validateJdbcContractsAndInstances(graph, findings);
        validateOperations(graph, findings);
        validateOutputRefs(graph, findings);
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
            if (isMissing(result.get(field))) {
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
                if (isMissing(result.get(field))) {
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
        detectRawSecrets(resultJson.toString(), result, findings);
        List<Map<String, Object>> providerResults = mapList(result.get("provider_results"));
        List<String> failedVerifySummary = mapList(result.get("verify_results")).stream()
                .filter(verify -> !"passed".equals(stringValue(verify.get("status"))))
                .map(verify -> stringValue(verify.get("id")) + ":" + stringValue(verify.get("type")) + ":"
                        + stringValue(verify.get("status")))
                .toList();
        boolean releaseEvidenceEligible = providerResults.stream()
                .anyMatch(providerResult -> Boolean.TRUE.equals(providerResult.get("release_evidence_eligible")));
        if (findings.isEmpty() && releaseEvidenceEligible) {
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

    private void validateRequiredFields(ContractGraph graph, List<ContractFinding> findings) {
        require(graph.suitePath(), graph.suite(), "contract_version", findings);
        require(graph.suitePath(), graph.suite(), "suite_id", findings);
        require(graph.suitePath(), graph.suite(), "selection", findings);
        require(graph.suitePath(), graph.suite(), "tests", findings);
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            for (String field : List.of(
                    "dsl_version", "test_case_id", "status", "revision", "source_refs", "targets",
                    "scenario", "execute", "verify", "evidence", "runtime")) {
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
            for (String field : List.of("provider_instance_version", "provider_id", "provider_type", "runtime_modes", "binding_keys")) {
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
        for (Map.Entry<Path, Map<String, Object>> entry : graph.environmentBindings().entrySet()) {
            Map<String, Object> binding = entry.getValue();
            for (String field : List.of("environment_id", "profile", "provider_bindings")) {
                require(entry.getKey(), binding, field, findings);
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
        }
    }

    private void validateRawSecrets(ContractGraph graph, List<ContractFinding> findings) {
        scanAll(graph.suitePath(), graph.suite(), findings);
        graph.testCases().forEach((path, document) -> scanAll(path, document, findings));
        graph.providerContractsByPath().forEach((path, document) -> scanAll(path, document, findings));
        graph.providerInstancesByPath().forEach((path, document) -> scanAll(path, document, findings));
        graph.executionProfiles().forEach((path, document) -> scanAll(path, document, findings));
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
                String profile = stringValue(target.get("profile"));
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
                if (!ALLOWED_PROVIDER_TYPES.contains(providerType) || !graph.providerContracts().containsKey(providerType)) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            "targets." + targetEntry.getKey() + ".provider_type",
                            "unknown_provider_type",
                            providerId,
                            providerType,
                            profile,
                            "",
                            "Add Provider Contract for provider_type `" + providerType + "` or use supported v0.2 type."));
                }
                if (profile.isBlank()) {
                    findings.add(finding(entry.getKey(), "targets." + targetEntry.getKey() + ".profile",
                            "missing_required_field", "Add profile to DSL target `" + targetEntry.getKey() + "`."));
                } else if (!graph.environmentBindingsByProfile().containsKey(profile)) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            "targets." + targetEntry.getKey() + ".profile",
                            "missing_environment_binding",
                            providerId,
                            providerType,
                            profile,
                            "",
                            "Add Environment Binding for profile `" + profile + "`."));
                } else if (!environmentBindingForProvider(graph, profile, providerId)) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            "targets." + targetEntry.getKey() + ".provider_id",
                            "missing_environment_binding",
                            providerId,
                            providerType,
                            profile,
                            "",
                            "Add provider binding for `" + providerId + "` in profile `" + profile + "`."));
                }
            }
        }
    }

    private void validateExecutionProfiles(ContractGraph graph, List<ContractFinding> findings) {
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
                List<String> allowedBindAs = stringList(operation.get("allowed_bind_as"));
                List<String> providedBindAs = new ArrayList<>();
                for (Map<String, Object> parameter : operationRef.parameters()) {
                    String bindAs = stringValue(parameter.get("bind_as"));
                    providedBindAs.add(bindAs);
                    if (bindAs.isBlank()) {
                        findings.add(finding(entry.getKey(), operationRef.fieldPath() + ".parameters.bind_as",
                                "missing_required_field", "Add bind_as for operation parameter."));
                    } else if (!allowedBindAs(allowedBindAs, bindAs)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                operationRef.fieldPath() + ".parameters.bind_as",
                                "unsupported_bind_as",
                                targetResolution.providerId(),
                                targetResolution.providerType(),
                                targetResolution.profile(),
                                operationRef.operation(),
                                "Use a bind_as value allowed by Provider Contract `" + targetResolution.providerType()
                                        + "` for operation `" + operationRef.operation() + "`. bind_as: " + bindAs));
                    }
                    if (isMissing(parameter.get("ref"))) {
                        findings.add(finding(entry.getKey(), operationRef.fieldPath() + ".parameters.ref",
                                "missing_required_field", "Add ref for operation parameter."));
                    }
                }
                for (String requiredParameter : stringList(operation.get("required_parameters"))) {
                    if (!providedBindAs.contains(requiredParameter)) {
                        findings.add(new ContractFinding(
                                entry.getKey().toString(),
                                operationRef.fieldPath() + ".parameters",
                                "missing_required_parameter",
                                targetResolution.providerId(),
                                targetResolution.providerType(),
                                targetResolution.profile(),
                                operationRef.operation(),
                                "Add required parameter bind_as `" + requiredParameter + "` for operation `"
                                        + operationRef.operation() + "`."));
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

    private void validateEnvironmentBindings(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<String, Map<String, Object>> entry : graph.providerInstances().entrySet()) {
            String providerId = entry.getKey();
            String providerType = stringValue(entry.getValue().get("provider_type"));
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
                Map<String, Object> bindingValues = mapValue(providerBinding.get("binding_values"));
                for (String requiredKey : requiredBindingKeys) {
                    if (isMissing(valueAtPath(bindingValues, requiredKey))) {
                        findings.add(new ContractFinding(
                                graph.environmentBindingsByProfile().get(profile).path().toString(),
                                "provider_bindings." + providerId + ".binding_values." + requiredKey,
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
                            graph.environmentBindingsByProfile().get(profile).path(),
                            providerId,
                            providerType,
                            profile,
                            contract,
                            bindingValues,
                            findings);
                }
            }
        }
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
        for (Map.Entry<Path, Map<String, Object>> entry : graph.providerInstancesByPath().entrySet()) {
            Map<String, Object> instance = entry.getValue();
            if (!"jdbc".equals(stringValue(instance.get("provider_type")))) {
                continue;
            }
            String providerId = stringValue(instance.get("provider_id"));
            String dialect = stringValue(instance.get("dialect"));
            if (dialect.isBlank()) {
                findings.add(new ContractFinding(
                        entry.getKey().toString(),
                        "dialect",
                        "missing_required_field",
                        providerId,
                        "jdbc",
                        stringValue(instance.get("profile")),
                        "",
                        "Set JDBC Provider Instance dialect to `oracle` or `db2`."));
            } else if (contract != null && !jdbcSupportedDialects(contract).contains(dialect)) {
                findings.add(new ContractFinding(
                        entry.getKey().toString(),
                        "dialect",
                        "unsupported_dialect",
                        providerId,
                        "jdbc",
                        stringValue(instance.get("profile")),
                        "",
                        "Use supported JDBC dialect `oracle` or `db2`; unsupported dialect `" + dialect
                                + "` is outside PR-004."));
            }
            if (isMissing(valueAtPath(instance, "connection.secret_ref"))) {
                findings.add(new ContractFinding(
                        entry.getKey().toString(),
                        "connection.secret_ref",
                        "missing_required_binding_key",
                        providerId,
                        "jdbc",
                        stringValue(instance.get("profile")),
                        "",
                        "Declare connection.secret_ref on the JDBC Provider Instance shape."));
            }
        }
    }

    private void validateJdbcEnvironmentBinding(
            Path bindingPath,
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
                    "provider_bindings." + providerId + ".binding_values.dialect",
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply JDBC dialect `oracle` or `db2` in Environment Binding."));
        } else if (!jdbcSupportedDialects(contract).contains(dialect)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "provider_bindings." + providerId + ".binding_values.dialect",
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
                    "provider_bindings." + providerId + ".binding_values.connection.secret_ref",
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply JDBC connection.secret_ref; raw DB URLs, usernames, and passwords are prohibited."));
        }
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
        Map<Path, Map<String, Object>> contractsByPath = readDirectory(root.resolve("provider_contracts"), findings);
        Map<Path, Map<String, Object>> instancesByPath = readDirectory(root.resolve("provider_instances"), findings);
        Map<Path, Map<String, Object>> profiles = readDirectory(root.resolve("execution_profiles"), findings);
        Map<Path, Map<String, Object>> environmentBindings = readDirectory(root.resolve("environment_bindings"), findings);

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
        for (Map.Entry<Path, Map<String, Object>> entry : environmentBindings.entrySet()) {
            String profile = stringValue(entry.getValue().get("profile"));
            if (!profile.isBlank()) {
                bindingsByProfile.put(profile, new EnvironmentBindingDoc(entry.getKey(), entry.getValue()));
            }
        }
        Set<String> usedProfiles = new LinkedHashSet<>();
        for (Map<String, Object> testCase : testCases.values()) {
            for (Object targetValue : mapValue(testCase.get("targets")).values()) {
                String profile = stringValue(mapValue(targetValue).get("profile"));
                if (!profile.isBlank()) {
                    usedProfiles.add(profile);
                }
            }
        }
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
                environmentBindings,
                bindingsByProfile,
                Set.copyOf(usedProfiles),
                List.copyOf(findings));
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
        if (isMissing(document.get(field))) {
            findings.add(finding(path, field, "missing_required_field", "Add required field `" + field + "`."));
        }
    }

    private boolean environmentBindingForProvider(ContractGraph graph, String profile, String providerId) {
        return !providerBinding(graph, profile, providerId).isEmpty();
    }

    private Map<String, Object> providerBinding(ContractGraph graph, String profile, String providerId) {
        EnvironmentBindingDoc bindingDoc = graph.environmentBindingsByProfile().get(profile);
        if (bindingDoc == null) {
            return Map.of();
        }
        for (Object value : listValue(bindingDoc.document().get("provider_bindings"))) {
            Map<String, Object> providerBinding = mapValue(value);
            if (providerId.equals(stringValue(providerBinding.get("provider_id")))) {
                return providerBinding;
            }
        }
        return Map.of();
    }

    private boolean providerUsedInProfile(ContractGraph graph, String providerId, String profile) {
        for (Map<String, Object> testCase : graph.testCases().values()) {
            for (Object value : mapValue(testCase.get("targets")).values()) {
                Map<String, Object> target = mapValue(value);
                if (providerId.equals(stringValue(target.get("provider_id")))
                        && profile.equals(stringValue(target.get("profile")))) {
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
                    mapList(fixture.get("parameters")),
                    Map.of()));
        }
        for (Object value : listValue(testCase.get("execute"))) {
            Map<String, Object> step = mapValue(value);
            String id = stringValue(step.get("id"));
            operations.add(new OperationRef(
                    id.isBlank() ? "execute[]" : "execute." + id,
                    stringValue(step.get("target")),
                    stringValue(step.get("operation")),
                    mapList(step.get("parameters")),
                    mapValue(step.get("outputs"))));
        }
        for (Map.Entry<String, Object> entry : mapValue(mapValue(testCase.get("cleanup")).get("fixtures")).entrySet()) {
            Map<String, Object> fixture = mapValue(entry.getValue());
            operations.add(new OperationRef(
                    "cleanup.fixtures." + entry.getKey(),
                    stringValue(fixture.get("target")),
                    stringValue(fixture.get("operation")),
                    mapList(fixture.get("parameters")),
                    Map.of()));
        }
        return operations;
    }

    private TargetResolution resolveTarget(ContractGraph graph, Map<String, Object> testCase, String targetName) {
        Map<String, Object> target = mapValue(mapValue(testCase.get("targets")).get(targetName));
        String providerId = stringValue(target.get("provider_id"));
        String profile = stringValue(target.get("profile"));
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
        return graph.providerContracts().keySet().stream().sorted().toList();
    }

    private List<ResolvedTarget> resolvedPlan(ContractGraph graph) {
        List<ResolvedTarget> plan = new ArrayList<>();
        for (Map<String, Object> testCase : graph.testCases().values()) {
            String testCaseId = stringValue(testCase.get("test_case_id"));
            for (Map.Entry<String, Object> entry : mapValue(testCase.get("targets")).entrySet()) {
                Map<String, Object> target = mapValue(entry.getValue());
                String providerId = stringValue(target.get("provider_id"));
                String profile = stringValue(target.get("profile"));
                Map<String, Object> instance = graph.providerInstances().get(providerId);
                if (instance == null) {
                    continue;
                }
                String providerType = stringValue(instance.get("provider_type"));
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
        return List.copyOf(plan);
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
                || normalized.equals("connection")
                || normalized.equals("api_key")
                || normalized.equals("authorization");
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
            String ownerAction) {
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
            Map<Path, Map<String, Object>> environmentBindings,
            Map<String, EnvironmentBindingDoc> environmentBindingsByProfile,
            Set<String> usedProfiles,
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
                    Map.of(),
                    Set.of(),
                    List.copyOf(findings));
        }

        boolean loadBlocked() {
            return !loadFindings.isEmpty();
        }
    }

    private record EnvironmentBindingDoc(Path path, Map<String, Object> document) {
    }

    private record OperationRef(
            String fieldPath,
            String target,
            String operation,
            List<Map<String, Object>> parameters,
            Map<String, Object> outputs) {
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
