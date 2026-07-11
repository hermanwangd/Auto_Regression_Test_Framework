package com.specdriven.regression.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.specdriven.regression.contract.v03.V03ExecutionPlan;
import com.specdriven.regression.contract.v03.V03ExecutionPlanBuilder;
import com.specdriven.regression.contract.v03.assertion.V03AssertionValidator;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.report.LegacySuiteSummaryReportAdapter;
import com.specdriven.regression.summary.SuiteSummaryDocument;
import com.specdriven.regression.summary.SuiteSummaryValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
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
    private final LegacySuiteSummaryReportAdapter legacySummaryAdapter = new LegacySuiteSummaryReportAdapter();
    private final SuiteSummaryValidator suiteSummaryValidator = new SuiteSummaryValidator();
    private final V03AssertionValidator v03AssertionValidator = new V03AssertionValidator();
    private final V03ReferenceParser v03ReferenceParser = new V03ReferenceParser();
    private final ObjectMapper summaryMapper = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

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
    private static final Set<String> PROHIBITED_V03_TEST_FIELDS = Set.of(
            "uses",
            "targets",
            "provider_id",
            "provider_instance",
            "data_binding",
            "datasets",
            "fixtures",
            "expected_results",
            "db_seed",
            "db_cleanup",
            "mock_stubs",
            "parameters",
            "bind_as");
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
    private static final Map<String, Object> DEFAULT_ENV_PROFILE_DEPENDENCY_POLICY = Map.of(
            "require_readiness_evidence", false);
    private static final Map<String, Object> DEFAULT_ENV_PROFILE_DEPENDENCY_SUBSTITUTION_POLICY = Map.of(
            "mock_evidence_release_claim", "prohibited");
    private static final Map<String, Object> DEFAULT_ENV_PROFILE_DEPENDENCY_PROVISIONING_POLICY = Map.of(
            "allowed_provisioners", List.of());
    private static final Map<String, Object> DEFAULT_ENV_PROFILE_DATA_POLICY = Map.of(
            "approved_expected_results_required", true,
            "production_data_allowed", false,
            "generated_data_allowed", true,
            "secrets_must_use_refs", true);
    private static final Map<String, Object> DEFAULT_ENV_PROFILE_EVIDENCE_POLICY = Map.of(
            "evidence_classification", "framework_provider_capability_only",
            "downstream_release_evidence", false);
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
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^[A-Za-z]:.*$");
    private static final int MAX_JSON_POINTER_DEPTH = 32;
    private static final Object MISSING_JSON_POINTER_FRAGMENT = new Object();

    private final Yaml yaml = new Yaml();

    public ValidationResult validateSuite(Path suiteManifest) {
        ContractGraph graph = loadGraph(suiteManifest);
        List<ContractFinding> findings = new ArrayList<>(graph.loadFindings());
        validateProhibitedSuiteFields(graph, findings);
        if (graph.loadBlocked()) {
            return new ValidationResult(false, graph.suiteId(), List.of(), List.of(), List.of(), List.of(), List.of(), List.copyOf(findings));
        }
        if (graph.v03()) {
            validateV03RequiredFields(graph, findings);
            validateV03ProhibitedFields(graph, findings);
            validateRawSecrets(graph, findings);
            validateV03SuiteTargets(graph, findings);
            validateV03TargetRefsAndOperations(graph, findings);
            validateV03ReferenceSyntax(graph, findings);
            return validationResult(graph, findings);
        }
        validateRequiredFields(graph, findings);
        validateProhibitedFields(graph, findings);
        validateDataBindingCategories(graph, findings);
        validateRawSecrets(graph, findings);
        validateTargetProfileCompatibility(graph, findings);
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

    public V03ExecutionPlan buildV03ExecutionPlan(Path suiteManifest, String profile) {
        return new V03ExecutionPlanBuilder(this).build(suiteManifest, profile);
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
        boolean legacySummary = legacySummaryAdapter.supports(result);
        if (legacySummary) {
            try {
                result = legacySummaryAdapter.adapt(result);
            } catch (IllegalArgumentException error) {
                return new ReportResult(false, true, "", "", "", "", "", "", 0, 0, false, List.of(), List.of(
                        new ContractFinding(
                                resultJson.toString(), "result", "invalid_legacy_suite_summary", "", "", "", "",
                                "Repair the legacy summary or report from a canonical v0.3 result.json.")));
            }
        }
        List<ContractFinding> findings = new ArrayList<>();
        for (String field : RESULT_REQUIRED_FIELDS) {
            if ("failure".equals(field)
                    && !"v0.3".equals(stringValue(result.get("result_contract_version")))) {
                continue;
            }
            boolean missing = "failure".equals(field)
                    ? !result.containsKey(field)
                    : isMissingResultField(result.get(field));
            if (missing) {
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
        if (!legacySummary && "v0.3".equals(stringValue(result.get("result_contract_version")))) {
            findings.addAll(validateSuiteSummaryForReport(resultJson, result));
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

    private List<ContractFinding> validateSuiteSummaryForReport(Path resultJson, Map<String, Object> result) {
        String ref = stringValue(result.get("suite_summary_ref"));
        if (ref.isBlank()) return List.of();
        try {
            Path summaryPath = resultJson.toRealPath().getParent().resolve(ref).normalize().toRealPath();
            SuiteSummaryDocument summary = summaryMapper.readValue(summaryPath.toFile(), SuiteSummaryDocument.class);
            List<ContractFinding> findings = new ArrayList<>(suiteSummaryValidator.validate(summary, summaryPath.getParent()));
            for (String field : List.of("suite_id", "batch_id", "run_id")) {
                String resultValue = stringValue(result.get(field));
                String summaryValue = switch (field) {
                    case "suite_id" -> summary.suiteId();
                    case "batch_id" -> summary.batchId();
                    default -> summary.runId();
                };
                if (!resultValue.equals(summaryValue)) {
                    findings.add(new ContractFinding(
                            summaryPath.toString(), field, "result_summary_identity_mismatch", "", "",
                            stringValue(result.get("profile")), "",
                            "Regenerate result.json and suite_summary.json from the same suite run."));
                }
            }
            if (!stringValue(result.get("status")).equals(summary.status())) {
                findings.add(new ContractFinding(
                        summaryPath.toString(), "status", "result_summary_status_mismatch", "", "",
                        stringValue(result.get("profile")), "",
                        "Regenerate result.json and suite_summary.json from the same final status."));
            }
            return List.copyOf(findings);
        } catch (IOException | RuntimeException error) {
            return List.of(new ContractFinding(
                    resultJson.toString(), "suite_summary_ref", "invalid_suite_summary", "", "",
                    stringValue(result.get("profile")), "",
                    "Restore a schema-valid contained suite summary and rerun report."));
        }
    }

    private boolean looksLikeFileRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        if (ref.startsWith("evidence://")) {
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
            List<String> requiredContractFields = "v0.3".equals(contractVersion(contract))
                    ? List.of(
                            "contract_version", "provider_contract", "provider_type", "runtime_modes",
                            "binding_keys", "operations", "evidence", "failure_mapping")
                    : List.of(
                            "provider_contract_version", "provider_type", "runtime_modes", "binding_keys",
                            "operations", "evidence", "failure_mapping");
            for (String field : requiredContractFields) {
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
            for (String field : List.of("env_profile_id", "execution_mode", "providers")) {
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

    private void validateV03RequiredFields(ContractGraph graph, List<ContractFinding> findings) {
        require(graph.suitePath(), graph.suite(), "manifest_version", findings);
        require(graph.suitePath(), graph.suite(), "suite_id", findings);
        require(graph.suitePath(), graph.suite(), "targets", findings);
        require(graph.suitePath(), graph.suite(), "env_profiles", findings);
        require(graph.suitePath(), graph.suite(), "tests", findings);
        if (graph.usedProfiles().isEmpty()) {
            findings.add(finding(graph.suitePath(), "default_profile", "missing_selected_profile",
                    "Select a profile through `default_profile`, `profile`, or CLI --profile before execution."));
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            for (String field : List.of("dsl_version", "test_case_id", "title", "execute", "verify")) {
                require(entry.getKey(), testCase, field, findings);
            }
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.envProfiles().entrySet()) {
            Map<String, Object> profile = entry.getValue();
            for (String field : List.of("profile_id", "execution_mode", "targets")) {
                require(entry.getKey(), profile, field, findings);
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

    private void validateV03ProhibitedFields(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            validateV03ProhibitedKeys(entry.getKey(), "", entry.getValue(), findings);
            for (String field : GOVERNANCE_FIELDS) {
                if (entry.getValue().containsKey(field)) {
                    findings.add(finding(entry.getKey(), field, "prohibited_governance_field",
                            "Remove governance field `" + field + "` from framework runtime DSL."));
                }
            }
        }
    }

    private void validateV03ProhibitedKeys(
            Path testCasePath,
            String fieldPath,
            Object value,
            List<ContractFinding> findings) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                String childPath = fieldPath.isBlank() ? key : fieldPath + "." + key;
                if (PROHIBITED_V03_TEST_FIELDS.contains(key)) {
                    findings.add(finding(testCasePath, childPath, "prohibited_legacy_field",
                            "Remove `" + key + "` from DSL v0.3; use suite manifest targets, Env_Profile targets, `with`, and typed refs."));
                }
                validateV03ProhibitedKeys(testCasePath, childPath, entry.getValue(), findings);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                validateV03ProhibitedKeys(testCasePath, fieldPath + "[" + i + "]", list.get(i), findings);
            }
        }
    }

    private void validateV03SuiteTargets(ContractGraph graph, List<ContractFinding> findings) {
        Map<String, Object> suiteTargets = mapValue(graph.suite().get("targets"));
        for (Map.Entry<String, Object> targetEntry : suiteTargets.entrySet()) {
            Map<String, Object> target = mapValue(targetEntry.getValue());
            String providerContract = stringValue(target.get("provider_contract"));
            if (providerContract.isBlank()) {
                findings.add(finding(graph.suitePath(), "targets." + targetEntry.getKey() + ".provider_contract",
                        "missing_required_field",
                        "Declare provider_contract for suite target `" + targetEntry.getKey() + "`."));
                continue;
            }
            if (providerContract(graph, providerContract).isEmpty()) {
                findings.add(new ContractFinding(
                        graph.suitePath().toString(),
                        "targets." + targetEntry.getKey() + ".provider_contract",
                        "missing_provider_contract",
                        "",
                        "",
                        "",
                        "",
                        "Use a Provider Contract from the framework catalog or --contract-root. Missing `" + providerContract + "`."));
                continue;
            }
            Map<String, Object> contract = providerContract(graph, providerContract);
            if (!isExplicitV03ProviderContract(providerContract, contract)) {
                findings.add(new ContractFinding(
                        graph.suitePath().toString(),
                        "targets." + targetEntry.getKey() + ".provider_contract",
                        "invalid_provider_contract_version",
                        "",
                        stringValue(contract.get("provider_type")),
                        selectedProfile(graph, target),
                        "",
                        "Use an explicit DSL v0.3 Provider Contract id. Synthetic provider_type aliases are not valid v0.3 contracts."));
            }
        }
    }

    private void validateV03TargetRefsAndOperations(ContractGraph graph, List<ContractFinding> findings) {
        Map<String, Object> suiteTargets = mapValue(graph.suite().get("targets"));
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> testCase = entry.getValue();
            validateV03StepShape(entry.getKey(), testCase, findings);
            Set<String> priorSteps = new LinkedHashSet<>();
            for (V03OperationRef operationRef : v03OperationRefs(testCase)) {
                if ("assertion".equals(operationRef.kind())) {
                    validateV03AssertionRefs(entry.getKey(), operationRef, priorSteps, findings);
                    rememberV03PriorStep(priorSteps, operationRef);
                    continue;
                }
                String targetName = operationRef.target();
                if (targetName.isBlank() || !suiteTargets.containsKey(targetName)) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            operationRef.fieldPath() + ".target",
                            "invalid_target_ref",
                            "",
                            "",
                            "",
                            operationRef.operation(),
                            "Use a target declared in suite_manifest.targets. Unknown target `" + targetName + "`."));
                    rememberV03PriorStep(priorSteps, operationRef);
                    continue;
                }
                TargetResolution targetResolution = resolveV03Target(graph, targetName);
                if (!targetResolution.resolved()) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            operationRef.fieldPath() + ".target",
                            "missing_provider_contract",
                            "",
                            "",
                            selectedProfile(graph, Map.of()),
                            operationRef.operation(),
                            "Fix suite target `" + targetName + "` provider_contract before validating operations."));
                    rememberV03PriorStep(priorSteps, operationRef);
                    continue;
                }
                validateV03EnvProfileTarget(graph, entry.getKey(), operationRef, targetName, targetResolution, findings);
                Map<String, Object> operation = targetResolution.operation(operationRef.operation());
                if (operation.isEmpty()) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            operationRef.fieldPath() + ".op",
                            "unsupported_operation",
                            "",
                            targetResolution.providerType(),
                            targetResolution.profile(),
                            operationRef.operation(),
                            "Use an operation declared by Provider Contract `" + targetResolution.providerType() + "`."));
                    rememberV03PriorStep(priorSteps, operationRef);
                    continue;
                }
                validateV03Inputs(graph, entry.getKey(), operationRef, targetResolution, operation, findings);
                rememberV03PriorStep(priorSteps, operationRef);
            }
        }
    }

    private void validateV03ReferenceSyntax(ContractGraph graph, List<ContractFinding> findings) {
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            validateV03ReferenceSyntax(entry.getKey(), "", entry.getValue(), findings);
        }
    }

    private void validateV03ReferenceSyntax(
            Path path, String fieldPath, Object value, List<ContractFinding> findings) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                validateV03ReferenceSyntax(path, fieldPath.isBlank() ? key : fieldPath + "." + key,
                        entry.getValue(), findings);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                validateV03ReferenceSyntax(path, fieldPath + "[" + index + "]", list.get(index), findings);
            }
            return;
        }
        String reference = stringValue(value);
        if (!(reference.startsWith("artifact://")
                || reference.startsWith("step://")
                || reference.startsWith("generated://")
                || reference.startsWith("env://"))) {
            return;
        }
        try {
            v03ReferenceParser.parse(reference);
        } catch (IllegalArgumentException error) {
            String code = error.getMessage().split(":", 2)[0];
            findings.add(finding(path, fieldPath, code,
                    "Use the frozen DSL v0.3 typed-reference syntax."));
            return;
        }
        if (reference.startsWith("generated://") || reference.startsWith("env://")) {
            findings.add(finding(path, fieldPath, "invalid_reference_scope",
                    "Use generated:// and env:// values only in Env_Profile bindings, not in DSL test cases."));
        }
    }

    private void validateV03StepShape(Path testCasePath, Map<String, Object> testCase, List<ContractFinding> findings) {
        Set<String> seenIds = new LinkedHashSet<>();
        validateV03StepIds(testCasePath, "setup", listValue(testCase.get("setup")), seenIds, findings);
        validateV03StepIds(testCasePath, "execute", listValue(testCase.get("execute")), seenIds, findings);
        int verifyIndex = 0;
        for (Object value : listValue(testCase.get("verify"))) {
            Map<String, Object> verify = mapValue(value);
            String id = stringValue(verify.get("id"));
            String fieldPath = id.isBlank() ? "verify[" + verifyIndex + "]" : "verify." + id;
            validateV03StepId(testCasePath, fieldPath, id, seenIds, findings);
            for (V03AssertionValidator.Issue issue : v03AssertionValidator.validate(verify)) {
                findings.add(finding(
                        testCasePath,
                        fieldPath + issue.fieldSuffix(),
                        issue.code(),
                        issue.remediation()));
            }
            verifyIndex++;
        }
        validateV03StepIds(testCasePath, "cleanup", listValue(testCase.get("cleanup")), seenIds, findings);
    }

    private void validateV03StepIds(
            Path testCasePath,
            String phase,
            List<Object> steps,
            Set<String> seenIds,
            List<ContractFinding> findings) {
        int index = 0;
        for (Object value : steps) {
            Map<String, Object> step = mapValue(value);
            String id = stringValue(step.get("id"));
            String fieldPath = id.isBlank() ? phase + "[" + index + "]" : phase + "." + id;
            validateV03StepId(testCasePath, fieldPath, id, seenIds, findings);
            index++;
        }
    }

    private void validateV03StepId(
            Path testCasePath,
            String fieldPath,
            String id,
            Set<String> seenIds,
            List<ContractFinding> findings) {
        if (id.isBlank()) {
            return;
        }
        if (!seenIds.add(id)) {
            findings.add(finding(testCasePath, fieldPath + ".id", "duplicate_step_id",
                    "Use unique step ids within one DSL v0.3 test case."));
        }
    }

    private void rememberV03PriorStep(Set<String> priorSteps, V03OperationRef operationRef) {
        if (!operationRef.id().isBlank()) {
            priorSteps.add(operationRef.id());
        }
    }

    private void validateV03EnvProfileTarget(
            ContractGraph graph,
            Path testCasePath,
            V03OperationRef operationRef,
            String targetName,
            TargetResolution targetResolution,
            List<ContractFinding> findings) {
        for (String profile : graph.usedProfiles()) {
            EnvironmentBindingDoc bindingDoc = graph.environmentBindingsByProfile().get(profile);
            if (bindingDoc == null) {
                findings.add(new ContractFinding(
                        testCasePath.toString(),
                        "env_profiles." + profile,
                        "missing_env_profile",
                        "",
                        targetResolution.providerType(),
                        profile,
                        operationRef.operation(),
                        "Add Env_Profile for profile `" + profile + "`."));
                continue;
            }
            Map<String, Object> envTarget = mapValue(mapValue(bindingDoc.document().get("targets")).get(targetName));
            if (envTarget.isEmpty()) {
                findings.add(new ContractFinding(
                        bindingDoc.path().toString(),
                        "targets." + targetName,
                        "missing_env_profile_provider_binding",
                        "",
                        targetResolution.providerType(),
                        profile,
                        operationRef.operation(),
                        "Add Env_Profile target binding for suite target `" + targetName + "`."));
                continue;
            }
            String runtimeMode = stringValue(envTarget.get("runtime_mode"));
            validateRuntimeModeAllowed(bindingDoc.path(), "targets." + targetName + ".runtime_mode",
                    "", targetResolution.providerType(), profile, runtimeMode,
                    stringList(targetResolution.contract().get("runtime_modes")), "Provider Contract", findings);
            Map<String, Object> bindingValues = mapValue(envTarget.get("bindings"));
            Map<String, Object> contractBindingKeys = mapValue(targetResolution.contract().get("binding_keys"));
            for (String bindingKey : bindingValues.keySet()) {
                if (!contractBindingKeys.containsKey(bindingKey)) {
                    findings.add(new ContractFinding(
                            bindingDoc.path().toString(),
                            "targets." + targetName + ".bindings." + bindingKey,
                            "unknown_binding_key",
                            "",
                            targetResolution.providerType(),
                            profile,
                            operationRef.operation(),
                            "Use a binding key declared by Provider Contract `" + targetResolution.providerType()
                                    + "`. Unknown key `" + bindingKey + "`."));
                }
            }
            for (String requiredKey : requiredBindingKeys(targetResolution.contract())) {
                if (isMissing(valueAtPath(bindingValues, requiredKey))) {
                    findings.add(new ContractFinding(
                            bindingDoc.path().toString(),
                            "targets." + targetName + ".bindings." + requiredKey,
                            "missing_required_binding_key",
                            "",
                            targetResolution.providerType(),
                            profile,
                            operationRef.operation(),
                            "Supply binding key `" + requiredKey + "` for target `" + targetName + "`."));
                }
            }
        }
    }

    private void validateV03Inputs(
            ContractGraph graph,
            Path testCasePath,
            V03OperationRef operationRef,
            TargetResolution targetResolution,
            Map<String, Object> operation,
            List<ContractFinding> findings) {
        List<String> allowedInputs = operationInputs(operation, "allowed_inputs", "allowed_bind_as");
        for (String inputName : operationRef.inputs().keySet()) {
            if (!allowedBindAs(allowedInputs, inputName)) {
                findings.add(new ContractFinding(
                        testCasePath.toString(),
                        operationRef.fieldPath() + ".with." + inputName,
                        "unsupported_input",
                        "",
                        targetResolution.providerType(),
                        targetResolution.profile(),
                        operationRef.operation(),
                        "Use an input name allowed by Provider Contract `" + targetResolution.providerType()
                                + "` for operation `" + operationRef.operation() + "`. input: " + inputName));
                continue;
            }
            validateV03ArtifactRef(graph, testCasePath, operationRef, inputName, operationRef.inputs().get(inputName),
                    targetResolution, findings);
        }
        for (String requiredInput : operationInputs(operation, "required_inputs", "required_parameters")) {
            if (!providedInput(new ArrayList<>(operationRef.inputs().keySet()), requiredInput)) {
                findings.add(new ContractFinding(
                        testCasePath.toString(),
                        operationRef.fieldPath() + ".with",
                        "missing_required_input",
                        "",
                        targetResolution.providerType(),
                        targetResolution.profile(),
                        operationRef.operation(),
                        "Add required input `" + requiredInput + "` for operation `"
                                + operationRef.operation() + "`."));
            }
        }
    }

    private void validateV03ArtifactRef(
            ContractGraph graph,
            Path testCasePath,
            V03OperationRef operationRef,
            String inputName,
            Object value,
            TargetResolution targetResolution,
            List<ContractFinding> findings) {
        String ref = stringValue(value);
        if (!ref.startsWith("artifact://")) {
            return;
        }
        String refBody = ref.substring("artifact://".length());
        String[] refParts = refBody.split("#", 2);
        String filePart = refParts[0];
        if (refParts.length == 2 && !validJsonPointer(refParts[1])) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "invalid_json_pointer",
                    "Use RFC 6901 JSON pointer syntax after `#`, for example `#/status`.");
            return;
        }
        int separator = filePart.indexOf('/');
        if (separator < 1) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "invalid_artifact_ref",
                    "Use artifact refs as `artifact://<root>/<path>`.");
            return;
        }
        String rootName = filePart.substring(0, separator);
        String relativePath = filePart.substring(separator + 1);
        if (invalidV03ArtifactPath(relativePath)) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "invalid_artifact_ref",
                    "Use a relative artifact path under the declared artifact root; absolute, home, encoded traversal, and `..` paths are blocked.");
            return;
        }
        String rootDir = stringValue(mapValue(graph.suite().get("artifact_roots")).get(rootName));
        if (rootDir.isBlank()) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "unknown_artifact_root",
                    "Declare artifact root `" + rootName + "` in suite_manifest.artifact_roots.");
            return;
        }
        Path suiteRoot = graph.suitePath().toAbsolutePath().normalize().getParent();
        Path root = suiteRoot.resolve(rootDir).normalize();
        if (!root.startsWith(suiteRoot)) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "ref_outside_suite_root",
                    "Keep artifact refs under the declared suite artifact root.");
            return;
        }
        Path resolved;
        try {
            resolved = root.resolve(Path.of(relativePath)).normalize();
        } catch (InvalidPathException e) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "invalid_artifact_ref",
                    "Use a valid relative artifact path under the declared artifact root.");
            return;
        }
        if (!resolved.startsWith(root)) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "ref_outside_suite_root",
                    "Keep artifact refs under the declared suite artifact root.");
            return;
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "unresolved_artifact_ref",
                    "Restore runtime-critical artifact ref `" + ref + "`.");
            return;
        }
        try {
            Path realSuiteRoot = suiteRoot.toRealPath();
            Path realRoot = root.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (!realRoot.startsWith(realSuiteRoot) || !realResolved.startsWith(realRoot)) {
                addV03ArtifactFinding(
                        testCasePath,
                        operationRef,
                        inputName,
                        findings,
                        targetResolution,
                        "ref_outside_suite_root",
                        "Keep artifact refs under the declared suite artifact root; symlinks must not escape it.");
                return;
            }
            if (!Files.isRegularFile(realResolved)) {
                addV03ArtifactFinding(
                        testCasePath,
                        operationRef,
                        inputName,
                        findings,
                        targetResolution,
                        "unresolved_artifact_ref",
                        "Restore runtime-critical artifact ref `" + ref + "`.");
                return;
            }
            if (refParts.length == 2 && !jsonPointerFragmentExists(realResolved, refParts[1])) {
                addV03ArtifactFinding(
                        testCasePath,
                        operationRef,
                        inputName,
                        findings,
                        targetResolution,
                        "missing_json_pointer_fragment",
                        "Use a JSON Pointer fragment that exists in artifact ref `" + ref + "`.");
            }
        } catch (IOException e) {
            addV03ArtifactFinding(
                    testCasePath,
                    operationRef,
                    inputName,
                    findings,
                    targetResolution,
                    "unresolved_artifact_ref",
                    "Restore runtime-critical artifact ref `" + ref + "`.");
        }
    }

    private void addV03ArtifactFinding(
            Path testCasePath,
            V03OperationRef operationRef,
            String inputName,
            List<ContractFinding> findings,
            TargetResolution targetResolution,
            String reason,
            String ownerAction) {
        findings.add(new ContractFinding(
                testCasePath.toString(),
                operationRef.fieldPath() + ".with." + inputName,
                reason,
                "",
                targetResolution.providerType(),
                targetResolution.profile(),
                operationRef.operation(),
                ownerAction));
    }

    private boolean validJsonPointer(String pointer) {
        if (pointer.isEmpty()) {
            return true;
        }
        if (!pointer.startsWith("/")) {
            return false;
        }
        int depth = pointer.split("/", -1).length - 1;
        if (depth > MAX_JSON_POINTER_DEPTH) {
            return false;
        }
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '~') {
                if (i + 1 >= pointer.length()) {
                    return false;
                }
                char escaped = pointer.charAt(i + 1);
                if (escaped != '0' && escaped != '1') {
                    return false;
                }
                i++;
            }
        }
        return true;
    }

    private boolean jsonPointerFragmentExists(Path artifact, String pointer) {
        if (pointer.isEmpty()) {
            return true;
        }
        try {
            Object loaded = yaml.load(Files.readString(artifact));
            return resolveJsonPointerFragment(loaded, pointer) != MISSING_JSON_POINTER_FRAGMENT;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Object resolveJsonPointerFragment(Object value, String pointer) {
        Object current = value;
        String[] segments = pointer.substring(1).split("/", -1);
        for (String rawSegment : segments) {
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(segment)) {
                    return MISSING_JSON_POINTER_FRAGMENT;
                }
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                if (!isArrayIndex(segment)) {
                    return MISSING_JSON_POINTER_FRAGMENT;
                }
                try {
                    int index = Integer.parseInt(segment);
                    if (index < 0 || index >= list.size()) {
                        return MISSING_JSON_POINTER_FRAGMENT;
                    }
                    current = list.get(index);
                } catch (NumberFormatException e) {
                    return MISSING_JSON_POINTER_FRAGMENT;
                }
            } else {
                return MISSING_JSON_POINTER_FRAGMENT;
            }
        }
        return current;
    }

    private boolean isArrayIndex(String value) {
        if (value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean invalidV03ArtifactPath(String relativePath) {
        if (relativePath.isBlank()
                || relativePath.startsWith("~")
                || relativePath.contains("\\")
                || WINDOWS_DRIVE_PATH.matcher(relativePath).matches()
                || containsEncodedPathControl(relativePath)) {
            return true;
        }
        try {
            Path path = Path.of(relativePath);
            if (path.isAbsolute()) {
                return true;
            }
            for (Path segment : path) {
                if ("..".equals(segment.toString())) {
                    return true;
                }
            }
            return false;
        } catch (InvalidPathException e) {
            return true;
        }
    }

    private boolean containsEncodedPathControl(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c");
    }

    private void validateV03AssertionRefs(
            Path testCasePath,
            V03OperationRef operationRef,
            Set<String> priorSteps,
            List<ContractFinding> findings) {
        for (String operand : List.of("actual", "actual_ref", "expected", "expected_ref")) {
            String reference = stringValue(operationRef.inputs().get(operand));
            if (!reference.startsWith("step://")) {
                continue;
            }
            String refBody = reference.substring("step://".length());
            int slash = refBody.indexOf('/');
            if (slash < 1 || slash == refBody.length() - 1) {
                findings.add(finding(
                        testCasePath,
                        operationRef.fieldPath() + ".assert." + operand,
                        "invalid_step_ref",
                        "Use `step://<prior-step-id>/<output-path>` for v0.3 step references."));
                continue;
            }
            String stepId = refBody.substring(0, slash);
            if (!priorSteps.contains(stepId)) {
                findings.add(new ContractFinding(
                        testCasePath.toString(),
                        operationRef.fieldPath() + ".assert." + operand,
                        "invalid_step_ref",
                        "",
                        "",
                        "",
                        "",
                        "Reference only prior steps in the same v0.3 test case. Unknown or forward step `" + stepId + "`."));
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

    private void validateTargetProfileCompatibility(ContractGraph graph, List<ContractFinding> findings) {
        Set<String> selectedProfiles = suiteSelectedProfiles(graph.suite());
        if (selectedProfiles.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, Map<String, Object>> entry : graph.testCases().entrySet()) {
            Map<String, Object> targets = mapValue(entry.getValue().get("targets"));
            for (Map.Entry<String, Object> targetEntry : targets.entrySet()) {
                Map<String, Object> target = mapValue(targetEntry.getValue());
                String targetProfile = stringValue(target.get("profile"));
                if (targetProfile.isBlank() || selectedProfiles.contains(targetProfile)) {
                    continue;
                }
                findings.add(new ContractFinding(
                        entry.getKey().toString(),
                        "targets." + targetEntry.getKey() + ".profile",
                        "conflicting_profile_selection",
                        stringValue(target.get("provider_id")),
                        "",
                        targetProfile,
                        "",
                        "Remove deprecated target profile or align it with the suite/CLI selected profile: "
                                + selectedProfiles + "."));
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
        Map<String, Object> parameter = operationRef.parameters().get(parameterIndex);
        if (parameter.containsKey("value") && !parameter.containsKey("ref")) {
            return;
        }
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
        } else if (!Files.isRegularFile(resolved)) {
            findings.add(new ContractFinding(
                    testCasePath.toString(),
                    parameterFieldPath(operationRef, parameterIndex, operationRef.parameters().get(parameterIndex)),
                    "unresolved_artifact_ref",
                    targetResolution.providerId(),
                    targetResolution.providerType(),
                    targetResolution.profile(),
                    operationRef.operation(),
                    "Restore runtime-critical artifact ref `" + filePart + "` under the suite directory before execution."));
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
                            graph,
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
        Map<String, Object> bindingKeys = envProfileAuthoringBindings(provider);
        String bindingField = envProfileBindingField(provider);
        Map<String, Object> contractBindingKeys = mapValue(contract.get("binding_keys"));
        Set<String> allowedKeys = contractBindingKeys.keySet();
        for (Map.Entry<String, Object> entry : bindingKeys.entrySet()) {
            String rawKey = entry.getKey();
            List<String> valueKinds = bindingValueKinds(entry.getValue());
            if (valueKinds.size() > 1) {
                findings.add(new ContractFinding(
                        bindingPath.toString(),
                        "providers." + providerId + "." + bindingField + "." + rawKey,
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
                        "providers." + providerId + "." + bindingField + "." + rawKey,
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
                        "providers." + providerId + "." + bindingField + "." + rawKey + "." + kind,
                        "invalid_binding_key_value_kind",
                        providerId,
                        providerType,
                        profile,
                        "",
                        "Use one of " + allowedKinds + " for Provider Contract `" + providerType
                                + "` binding key `" + effectiveKey + "`."));
            }
            Object value = bindingValue(entry.getValue(), kind);
            validateBindingValueShape(
                    bindingPath, providerId, providerType, profile, bindingField, rawKey, kind, value, keySpec, findings);
            validateGeneratedRef(graph, bindingPath, providerId, providerType, profile, bindingField, rawKey, kind,
                    value, findings);
        }
    }

    private void validateBindingValueShape(
            Path bindingPath,
            String providerId,
            String providerType,
            String profile,
            String bindingField,
            String rawKey,
            String kind,
            Object value,
            Map<String, Object> keySpec,
            List<ContractFinding> findings) {
        if (!"value".equals(kind) || !"uri".equals(stringValue(keySpec.get("value_type")))) {
            return;
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".value",
                    uriInvalidReason(providerType, rawKey),
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Provide a non-empty " + rawKey + " URI value."));
            return;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".value",
                    uriInvalidReason(providerType, rawKey),
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Provide a valid URI for `" + rawKey + "`."));
            return;
        }
        Set<String> allowedSchemes = new LinkedHashSet<>(stringList(keySpec.get("allowed_schemes")));
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.isEmpty() && (!allowedSchemes.contains(scheme) || stringValue(uri.getHost()).isBlank())) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".value",
                    uriInvalidReason(providerType, rawKey),
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use one of " + allowedSchemes + " with a host for `" + rawKey + "`."));
        }
        Set<String> prohibitedParts = new LinkedHashSet<>(stringList(keySpec.get("prohibited_parts")));
        if (prohibitedParts.contains("userinfo") && uri.getRawUserInfo() != null) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".value",
                    uriSecretLeakReason(providerType, rawKey),
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Remove userinfo from `" + rawKey + "` and provide credentials through approved secret refs when a provider supports them."));
        }
        Set<String> prohibitedQueryKeys = new LinkedHashSet<>(stringList(keySpec.get("prohibited_query_keys"))).stream()
                .map(valueKey -> valueKey.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        String query = stringValue(uri.getRawQuery());
        if (!query.isBlank()) {
            for (String part : query.split("&")) {
                String queryKey = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (prohibitedQueryKeys.contains(queryKey)) {
                    findings.add(new ContractFinding(
                            bindingPath.toString(),
                            "providers." + providerId + "." + bindingField + "." + rawKey + ".value",
                            uriSecretLeakReason(providerType, rawKey),
                            providerId,
                            providerType,
                            profile,
                            "",
                            "Remove secret-like query parameter `" + queryKey + "` from `" + rawKey + "`."));
                    return;
                }
            }
        }
    }

    private String uriInvalidReason(String providerType, String rawKey) {
        return "invalid_binding_key_uri";
    }

    private String uriSecretLeakReason(String providerType, String rawKey) {
        return "raw_secret";
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
            ContractGraph graph,
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
        String localRef = stringValue(valueAtPath(bindingValues, "connection.local_ref"));
        if (secretRef.isBlank() && localRef.isBlank()) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "connection"),
                    "missing_required_binding_key",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Supply JDBC connection.secret_ref or approved connection.local_ref; raw DB URLs, usernames, and passwords are prohibited."));
        }
        if (secretRef.startsWith("generated://")
                && !secretRef.startsWith("generated://provider-capability/")
                && !declaredDependencyGeneratedOutput(graph, profile, secretRef)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "connection.secret_ref"),
                    "unresolved_generated_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Materialize generated ref `" + secretRef
                            + "` into standard Env_Profile/Environment Binding artifacts before running the framework."));
        }
        if (!localRef.isBlank() && !approvedJdbcLocalRef(localRef)) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    bindingValueFieldPath(envProfile, providerId, "connection.local_ref"),
                    "unsupported_local_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use approved JDBC local_ref `approved_local_h2_oracle` or `approved_local_h2_db2` for local/CI materialized samples."));
        }
    }

    private boolean approvedJdbcLocalRef(String localRef) {
        return "approved_local_h2_oracle".equals(localRef) || "approved_local_h2_db2".equals(localRef);
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
                ? "providers." + providerId + ".bindings." + bindingKey
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
                .map(value -> root.resolve(suiteTestRef(value)).normalize())
                .toList();
        Map<Path, Map<String, Object>> testCases = readAll(testPaths, findings);
        String manifestVersion = stringValue(suite.get("manifest_version"));
        String legacyContractVersion = stringValue(suite.get("contract_version"));
        boolean manifestV03 = "v0.3".equals(manifestVersion);
        boolean anyV03Test = testCases.values().stream()
                .anyMatch(testCase -> "v0.3".equals(stringValue(testCase.get("dsl_version"))));
        boolean anyNonV03Test = testCases.values().stream()
                .anyMatch(testCase -> !"v0.3".equals(stringValue(testCase.get("dsl_version"))));
        validateLeafVersionRouting(
                suiteManifest,
                manifestVersion,
                legacyContractVersion,
                testCases,
                findings);
        if ((manifestV03 && anyNonV03Test) || (!manifestV03 && anyV03Test)) {
            findings.add(finding(
                    suiteManifest,
                    "manifest_version",
                    "mixed_dsl_versions",
                    "A leaf suite must use one DSL version: align manifest_version and every referenced test case dsl_version."));
        }
        // Leaf routing is selected exclusively by the validated manifest; test case versions only validate it.
        boolean v03 = manifestV03;
        Map<Path, Map<String, Object>> frameworkContractsByPath =
                readDirectory(frameworkProviderContractsDirectory(root), findings);
        Map<Path, Map<String, Object>> suiteLocalContractsByPath =
                readSuiteLocalProviderContracts(root, suite, findings);
        Map<Path, Map<String, Object>> contractsByPath = new LinkedHashMap<>();
        contractsByPath.putAll(frameworkContractsByPath);
        contractsByPath.putAll(suiteLocalContractsByPath);
        Map<Path, Map<String, Object>> instancesByPath = v03
                ? Map.of()
                : readDirectory(root.resolve("provider_instances"), findings);
        Path envProfilesRoot = root.resolve("env_profiles");
        Map<Path, Map<String, Object>> envProfiles = withEnvProfileDefaults(readOptionalDirectory(envProfilesRoot, findings));
        boolean envProfileMode = Files.isDirectory(envProfilesRoot);
        Map<Path, Map<String, Object>> profiles = envProfileMode
                ? Map.of()
                : readDirectory(root.resolve("execution_profiles"), findings);
        Map<Path, Map<String, Object>> environmentBindings = envProfileMode
                ? Map.of()
                : readDirectory(root.resolve("environment_bindings"), findings);

        Map<String, Map<String, Object>> contracts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> contractsById = new LinkedHashMap<>();
        Map<String, Path> providerContractPathsById = new LinkedHashMap<>();
        for (Map.Entry<Path, Map<String, Object>> entry : contractsByPath.entrySet()) {
            Map<String, Object> contract = entry.getValue();
            String providerType = stringValue(contract.get("provider_type"));
            String contractVersion = contractVersion(contract);
            if (!providerType.isBlank()) {
                if (!contracts.containsKey(providerType) || "v0.2".equals(contractVersion)) {
                    contracts.put(providerType, contract);
                }
                if (!contractsById.containsKey(providerType) || "v0.2".equals(contractVersion)) {
                    contractsById.put(providerType, contract);
                }
                if ("v0.2".equals(contractVersion)) {
                    contractsById.put(providerType + ".v0.2", contract);
                }
            }
            String providerContract = stringValue(contract.get("provider_contract"));
            if (!providerContract.isBlank()) {
                Path existingPath = providerContractPathsById.putIfAbsent(providerContract, entry.getKey());
                if (existingPath != null) {
                    findings.add(new ContractFinding(
                            entry.getKey().toString(),
                            "provider_contract",
                            "duplicate_provider_contract",
                            "",
                            providerType,
                            "",
                            "",
                            "Provider Contract id `" + providerContract + "` is already declared by `"
                                    + existingPath + "`. Use a unique v0.3 provider_contract id."));
                    continue;
                }
                contractsById.put(providerContract, contract);
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
                String profile = firstNonBlank(
                        stringValue(entry.getValue().get("env_profile_id")),
                        stringValue(entry.getValue().get("profile_id")));
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
                contractsById,
                instancesByPath,
                instances,
                profiles,
                envProfiles,
                envProfileMode,
                environmentBindings,
                bindingsByProfile,
                new LinkedHashSet<>(usedProfiles),
                allowedProviderTypes(suite, frameworkContractsByPath),
                v03,
                List.copyOf(findings));
    }

    private void validateLeafVersionRouting(
            Path suiteManifest,
            String manifestVersion,
            String legacyContractVersion,
            Map<Path, Map<String, Object>> testCases,
            List<ContractFinding> findings) {
        if (testCases.isEmpty()) {
            return;
        }
        String expectedDslVersion;
        if ("v0.3".equals(manifestVersion)) {
            expectedDslVersion = "v0.3";
        } else if (manifestVersion.isBlank() && "v0.2".equals(legacyContractVersion)) {
            expectedDslVersion = "v0.2";
        } else if (manifestVersion.isBlank()) {
            findings.add(finding(
                    suiteManifest,
                    "manifest_version",
                    "missing_manifest_version",
                    "Set leaf suite manifest_version to `v0.3`, or retain explicit contract_version `v0.2` for compatibility suites."));
            return;
        } else {
            findings.add(finding(
                    suiteManifest,
                    "manifest_version",
                    "unsupported_manifest_version",
                    "Use supported leaf manifest_version `v0.3`, or use the explicit v0.2 compatibility manifest shape."));
            return;
        }
        for (Map.Entry<Path, Map<String, Object>> entry : testCases.entrySet()) {
            String dslVersion = stringValue(entry.getValue().get("dsl_version"));
            if (dslVersion.isBlank()) {
                findings.add(finding(
                        entry.getKey(),
                        "dsl_version",
                        "missing_required_field",
                        "Declare dsl_version matching the leaf suite manifest before execution."));
            } else if (!expectedDslVersion.equals(dslVersion)) {
                findings.add(finding(
                        entry.getKey(),
                        "dsl_version",
                        "mixed_dsl_versions",
                        "Set every test case dsl_version to `" + expectedDslVersion
                                + "` for this leaf suite; mixed DSL versions are not routed."));
            }
        }
    }

    private String suiteTestRef(Object value) {
        Map<String, Object> map = mapValue(value);
        if (!map.isEmpty() || value instanceof Map<?, ?>) {
            return stringValue(map.get("ref"));
        }
        return stringValue(value);
    }

    private Path frameworkProviderContractsDirectory(Path suiteRoot) {
        return FrameworkProviderContractCatalog.resolveDirectory(suiteRoot, FRAMEWORK_PROVIDER_CONTRACTS);
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
        Set<String> profiles = suiteSelectedProfiles(suite);
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

    private Set<String> suiteSelectedProfiles(Map<String, Object> suite) {
        Set<String> profiles = new LinkedHashSet<>();
        addIfPresent(profiles, stringValue(suite.get("default_profile")));
        addIfPresent(profiles, stringValue(suite.get("profile")));
        for (Object profile : listValue(suite.get("profiles"))) {
            addIfPresent(profiles, stringValue(profile));
        }
        addIfPresent(profiles, stringValue(mapValue(suite.get("selection")).get("profile")));
        return profiles;
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

    private Map<Path, Map<String, Object>> withEnvProfileDefaults(Map<Path, Map<String, Object>> envProfiles) {
        Map<Path, Map<String, Object>> normalized = new LinkedHashMap<>();
        for (Map.Entry<Path, Map<String, Object>> entry : envProfiles.entrySet()) {
            normalized.put(entry.getKey(), withEnvProfileDocumentDefaults(entry.getValue()));
        }
        return normalized;
    }

    private Map<String, Object> withEnvProfileDocumentDefaults(Map<String, Object> envProfile) {
        if (envProfile.isEmpty()) {
            return envProfile;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(envProfile);
        normalized.putIfAbsent("isolation_scope", "per_run");
        normalized.putIfAbsent("max_duration", "PT5M");
        mergeMapDefaults(normalized, "dependency_policy", DEFAULT_ENV_PROFILE_DEPENDENCY_POLICY);
        mergeMapDefaults(normalized, "dependency_substitution_policy", DEFAULT_ENV_PROFILE_DEPENDENCY_SUBSTITUTION_POLICY);
        mergeMapDefaults(normalized, "dependency_provisioning_policy", DEFAULT_ENV_PROFILE_DEPENDENCY_PROVISIONING_POLICY);
        mergeMapDefaults(normalized, "data_policy", DEFAULT_ENV_PROFILE_DATA_POLICY);
        mergeMapDefaults(normalized, "evidence_policy", DEFAULT_ENV_PROFILE_EVIDENCE_POLICY);
        return normalized;
    }

    private void mergeMapDefaults(Map<String, Object> document, String field, Map<String, Object> defaults) {
        if (document.containsKey(field) && !(document.get(field) instanceof Map<?, ?>)) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        merged.putAll(mapValue(document.get(field)));
        document.put(field, merged);
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
        if (("execute".equals(field) || "verify".equals(field)) && document.containsKey(field)) {
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
        if (map.isEmpty()) {
            return value;
        }
        if (map.containsKey(kind)) {
            return map.get(kind);
        }
        return "value".equals(kind) ? map : null;
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
            String bindingField,
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
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".generated_ref",
                    "invalid_generated_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use generated_ref format `generated://<provider_id>.<bindable_output>` from a Provider Contract bindable output."));
            return;
        }
        String target = generatedRef.substring(prefix.length());
        int separator = target.indexOf('.');
        if (separator < 1 || separator == target.length() - 1) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".generated_ref",
                    "invalid_generated_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Use generated_ref format `generated://<provider_id>.<bindable_output>` from a Provider Contract bindable output."));
            return;
        }
        String generatedProviderId = target.substring(0, separator);
        String output = target.substring(separator + 1);
        if (declaredDependencyGeneratedOutput(graph, profile, generatedRef)) {
            return;
        }
        Map<String, Object> generatedProvider = graph.providerInstances().get(generatedProviderId);
        if (generatedProvider == null) {
            findings.add(new ContractFinding(
                    bindingPath.toString(),
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".generated_ref",
                    "unresolved_generated_ref",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Materialize generated ref `" + generatedRef
                            + "` into standard Env_Profile/Environment Binding artifacts before running the framework."));
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
                    "providers." + providerId + "." + bindingField + "." + rawKey + ".generated_ref",
                    "unknown_bindable_output",
                    providerId,
                    providerType,
                    profile,
                    "",
                    "Reference a bindable output declared by Provider Contract `" + generatedProviderType
                            + "`. Unknown output `" + output + "`."));
        }
    }

    private boolean declaredDependencyGeneratedOutput(ContractGraph graph, String profile, String generatedRef) {
        EnvironmentBindingDoc bindingDoc = graph.environmentBindingsByProfile().get(profile);
        if (bindingDoc == null || !bindingDoc.envProfile()) {
            return false;
        }
        Map<String, Object> provisioningPolicy = mapValue(bindingDoc.document().get("dependency_provisioning_policy"));
        return generatedOutputMatches(provisioningPolicy.get("generated_outputs"), generatedRef)
                || generatedOutputMatches(provisioningPolicy.get("dependency_outputs"), generatedRef);
    }

    private boolean generatedOutputMatches(Object value, String generatedRef) {
        if (value == null) {
            return false;
        }
        String text = stringValue(value);
        if (generatedRef.equals(text)
                || (text.contains(".") && generatedRef.equals("generated://" + text))) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            if (generatedOutputMatches(map.get("generated_ref"), generatedRef)
                    || generatedOutputMatches(map.get("ref"), generatedRef)
                    || generatedOutputMatches(map.get("value"), generatedRef)) {
                return true;
            }
            String providerId = firstNonBlank(
                    stringValue(map.get("provider_id")),
                    stringValue(map.get("dependency_id")),
                    stringValue(map.get("id")));
            String output = firstNonBlank(
                    stringValue(map.get("output")),
                    stringValue(map.get("output_name")),
                    stringValue(map.get("name")));
            if (!providerId.isBlank() && !output.isBlank()
                    && generatedRef.equals("generated://" + providerId + "." + output)) {
                return true;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                if (generatedRef.equals(key)
                        || (key.contains(".") && generatedRef.equals("generated://" + key))) {
                    return true;
                }
                if (generatedOutputMatches(entry.getValue(), generatedRef)) {
                    return true;
                }
            }
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (generatedOutputMatches(item, generatedRef)) {
                    return true;
                }
            }
        }
        return false;
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
            normalized.put("binding_values", normalizeEnvProfileBindings(provider));
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

    private Map<String, Object> normalizeEnvProfileBindings(Map<String, Object> provider) {
        Map<String, Object> bindings = mapValue(provider.get("bindings"));
        if (!bindings.isEmpty() || provider.containsKey("bindings")) {
            return normalizeEnvProfileBindingValues(bindings);
        }
        return normalizeEnvProfileBindingKeys(mapValue(provider.get("binding_keys")));
    }

    private Map<String, Object> envProfileAuthoringBindings(Map<String, Object> provider) {
        Map<String, Object> bindings = mapValue(provider.get("bindings"));
        if (!bindings.isEmpty() || provider.containsKey("bindings")) {
            return bindings;
        }
        return mapValue(provider.get("binding_keys"));
    }

    private String envProfileBindingField(Map<String, Object> provider) {
        return provider.containsKey("bindings") ? "bindings" : "binding_keys";
    }

    private Map<String, Object> normalizeEnvProfileBindingValues(Map<String, Object> bindings) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            normalized.put(entry.getKey(), normalizeEnvProfileBindingValue(entry.getValue()));
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
        if (map.containsKey("local_ref") && map.size() == 1) {
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

    private List<V03OperationRef> v03OperationRefs(Map<String, Object> testCase) {
        List<V03OperationRef> operations = new ArrayList<>();
        addV03ProviderOperations(operations, "setup", listValue(testCase.get("setup")));
        addV03ProviderOperations(operations, "execute", listValue(testCase.get("execute")));
        int verifyIndex = 0;
        for (Object value : listValue(testCase.get("verify"))) {
            Map<String, Object> verify = mapValue(value);
            String id = stringValue(verify.get("id"));
            String path = id.isBlank() ? "verify[" + verifyIndex + "]" : "verify." + id;
            String type = stringValue(verify.get("type"));
            if ("assertion".equals(type)) {
                operations.add(new V03OperationRef(
                        path,
                        id,
                        "assertion",
                        "",
                        "",
                        mapValue(verify.get("assert"))));
            } else if ("provider_check".equals(type)) {
                operations.add(new V03OperationRef(
                        path,
                        id,
                        "provider_check",
                        stringValue(verify.get("target")),
                        stringValue(verify.get("op")),
                        mapValue(verify.get("with"))));
            }
            verifyIndex++;
        }
        addV03ProviderOperations(operations, "cleanup", listValue(testCase.get("cleanup")));
        return List.copyOf(operations);
    }

    private void addV03ProviderOperations(List<V03OperationRef> operations, String phase, List<Object> values) {
        int index = 0;
        for (Object value : values) {
            Map<String, Object> operation = mapValue(value);
            String id = stringValue(operation.get("id"));
            String path = id.isBlank() ? phase + "[" + index + "]" : phase + "." + id;
            operations.add(new V03OperationRef(
                    path,
                    id,
                    phase,
                    stringValue(operation.get("target")),
                    stringValue(operation.get("op")),
                    mapValue(operation.get("with"))));
            index++;
        }
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

    private TargetResolution resolveV03Target(ContractGraph graph, String targetName) {
        Map<String, Object> suiteTarget = mapValue(mapValue(graph.suite().get("targets")).get(targetName));
        String providerContract = stringValue(suiteTarget.get("provider_contract"));
        Map<String, Object> contract = providerContract(graph, providerContract);
        if (contract.isEmpty() || !isExplicitV03ProviderContract(providerContract, contract)) {
            return TargetResolution.unresolved("", selectedProfile(graph, Map.of()));
        }
        return new TargetResolution(targetName, selectedProfile(graph, Map.of()), stringValue(contract.get("provider_type")), contract);
    }

    private Map<String, Object> providerContract(ContractGraph graph, String providerContract) {
        Map<String, Object> contract = graph.providerContractsById().get(providerContract);
        return contract == null ? Map.of() : contract;
    }

    private boolean isExplicitV03ProviderContract(String requestedId, Map<String, Object> contract) {
        return "v0.3".equals(contractVersion(contract))
                && requestedId.equals(stringValue(contract.get("provider_contract")));
    }

    private String contractVersion(Map<String, Object> contract) {
        return firstNonBlank(
                stringValue(contract.get("contract_version")),
                stringValue(contract.get("provider_contract_version")));
    }

    private ValidationResult validationResult(ContractGraph graph, List<ContractFinding> findings) {
        List<ResolvedTarget> plan = findings.isEmpty() ? resolvedPlan(graph) : List.of();
        return new ValidationResult(
                findings.isEmpty(),
                graph.suiteId(),
                providerIds(graph),
                providerTypes(graph),
                targetNames(graph),
                providerContractIds(graph),
                plan,
                List.copyOf(findings));
    }

    private List<String> providerIds(ContractGraph graph) {
        if (graph.v03()) {
            return List.of();
        }
        Set<String> providerIds = new LinkedHashSet<>();
        for (Map<String, Object> testCase : graph.testCases().values()) {
            for (Object targetValue : mapValue(testCase.get("targets")).values()) {
                addIfPresent(providerIds, stringValue(mapValue(targetValue).get("provider_id")));
            }
        }
        return providerIds.stream().sorted().toList();
    }

    private List<String> providerTypes(ContractGraph graph) {
        Set<String> providerTypes = new LinkedHashSet<>();
        if (graph.v03()) {
            for (Object targetValue : mapValue(graph.suite().get("targets")).values()) {
                Map<String, Object> contract = providerContract(graph, stringValue(mapValue(targetValue).get("provider_contract")));
                addIfPresent(providerTypes, stringValue(contract.get("provider_type")));
            }
            return providerTypes.stream().sorted().toList();
        }
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

    private List<String> targetNames(ContractGraph graph) {
        if (graph.v03()) {
            return mapValue(graph.suite().get("targets")).keySet().stream().sorted().toList();
        }
        Set<String> targets = new LinkedHashSet<>();
        for (Map<String, Object> testCase : graph.testCases().values()) {
            targets.addAll(mapValue(testCase.get("targets")).keySet());
        }
        return targets.stream().sorted().toList();
    }

    private List<String> providerContractIds(ContractGraph graph) {
        if (!graph.v03()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object targetValue : mapValue(graph.suite().get("targets")).values()) {
            addIfPresent(ids, stringValue(mapValue(targetValue).get("provider_contract")));
        }
        return ids.stream().sorted().toList();
    }

    private List<ResolvedTarget> resolvedPlan(ContractGraph graph) {
        List<ResolvedTarget> plan = new ArrayList<>();
        if (graph.v03()) {
            for (Map<String, Object> testCase : graph.testCases().values()) {
                String testCaseId = stringValue(testCase.get("test_case_id"));
                Set<String> seenTargets = new LinkedHashSet<>();
                for (V03OperationRef operationRef : v03OperationRefs(testCase)) {
                    if ("assertion".equals(operationRef.kind()) || operationRef.target().isBlank()
                            || !seenTargets.add(operationRef.target())) {
                        continue;
                    }
                    TargetResolution targetResolution = resolveV03Target(graph, operationRef.target());
                    Map<String, Object> envTarget = v03EnvTarget(graph, targetResolution.profile(), operationRef.target());
                    String providerContract = stringValue(mapValue(mapValue(graph.suite().get("targets"))
                            .get(operationRef.target())).get("provider_contract"));
                    plan.add(new ResolvedTarget(
                            testCaseId,
                            operationRef.target(),
                            "",
                            targetResolution.providerType(),
                            targetResolution.profile(),
                            stringValue(envTarget.get("runtime_mode")),
                            providerContract));
                }
            }
            return List.copyOf(plan);
        }
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
                            stringValue(providerBinding.get("runtime_mode")),
                            ""));
                }
            }
        }
        return List.copyOf(plan);
    }

    private Map<String, Object> v03EnvTarget(ContractGraph graph, String profile, String targetName) {
        EnvironmentBindingDoc bindingDoc = graph.environmentBindingsByProfile().get(profile);
        if (bindingDoc == null) {
            return Map.of();
        }
        return mapValue(mapValue(bindingDoc.document().get("targets")).get(targetName));
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
                || value.startsWith("env://")
                || value.startsWith("generated://")
                || value.startsWith("local://")
                || value.startsWith("approved_local_")
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
            List<String> targetsUsed,
            List<String> providerContractsUsed,
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
            String runtimeMode,
            String providerContract) {
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
            if ("unresolved_generated_ref".equals(reason)) {
                return "CONFIGURATION_UNRESOLVED_GENERATED_REF";
            }
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
                        "profile_mismatch", "conflicting_profile_selection",
                        "invalid_generated_ref", "unresolved_generated_ref",
                        "invalid_binding_key_uri" -> "CONFIGURATION_ERROR";
                case "unknown_provider_type", "unsupported_operation", "unsupported_bind_as",
                        "unsupported_input",
                        "unsupported_dialect", "missing_output_ref", "missing_supported_dialect",
                        "missing_evidence_output_definition", "missing_required_parameter",
                        "missing_required_input",
                        "unsupported_suite_runtime", "unsupported_suite_shape",
                        "unsupported_provider_instance_field",
                        "unsupported_provider_instance_binding_key", "unknown_binding_key",
                        "invalid_binding_key_value_kind", "unknown_bindable_output",
                        "invalid_provider_contract_version", "duplicate_provider_contract" -> "CONTRACT_ERROR";
                case "invalid_result_json", "missing_result_json", "invalid_yaml",
                        "missing_required_file", "missing_required_directory",
                        "missing_required_field", "prohibited_legacy_field",
                        "prohibited_data_catalog_field",
                        "prohibited_governance_field", "prohibited_deprecated_field",
                        "prohibited_data_binding_category", "prohibited_source_ref", "invalid_source_refs",
                        "invalid_duration", "invalid_status", "invalid_test_count", "invalid_test_results",
                        "invalid_instant", "invalid_polling_config",
                        "invalid_artifact_ref", "invalid_json_pointer",
                        "missing_json_pointer_fragment", "ref_outside_suite_root" -> "VALIDATION_ERROR";
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
            Map<String, Map<String, Object>> providerContractsById,
            Map<Path, Map<String, Object>> providerInstancesByPath,
            Map<String, Map<String, Object>> providerInstances,
            Map<Path, Map<String, Object>> executionProfiles,
            Map<Path, Map<String, Object>> envProfiles,
            boolean envProfileMode,
            Map<Path, Map<String, Object>> environmentBindings,
            Map<String, EnvironmentBindingDoc> environmentBindingsByProfile,
            Set<String> usedProfiles,
            Set<String> allowedProviderTypes,
            boolean v03,
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
                    false,
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    false,
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

    private record V03OperationRef(
            String fieldPath,
            String id,
            String kind,
            String target,
            String operation,
            Map<String, Object> inputs) {
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
