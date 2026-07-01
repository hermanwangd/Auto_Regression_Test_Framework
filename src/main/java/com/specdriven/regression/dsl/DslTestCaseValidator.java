package com.specdriven.regression.dsl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

@Service
public class DslTestCaseValidator {

    private static final Set<String> EXECUTION_FOCUSED_SECTIONS = Set.of(
            "traceability",
            "targets",
            "data",
            "setup",
            "execute",
            "verify",
            "cleanup",
            "evidence",
            "runtime");
    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "draft_skeleton",
            "draft_executable",
            "active",
            "needs_update",
            "retired");
    private static final Set<String> LEGACY_FIELDS = Set.of(
            "rp_id",
            "ac_id",
            "artifact_status",
            "execution_target",
            "fixture",
            "package_inputs",
            "expected",
            "oracles",
            "steps",
            "assertions",
            "evidence_required",
            "policy");
    private static final Set<String> PROHIBITED_V02_DATA_FIELDS = Set.of(
            "data_binding",
            "datasets",
            "fixtures",
            "expected_results",
            "db_seed",
            "db_cleanup",
            "mock_stubs");
    private static final Set<String> GOVERNANCE_FIELDS = Set.of(
            "approval_status",
            "approved_by",
            "approval_required",
            "waiver",
            "release_gate",
            "risk_approval");
    private static final Set<String> DEPRECATED_DSL_FIELDS = Set.of(
            "scenario");
    private static final Set<String> SUPPORTED_OPERATIONS = Set.of(
            "run_batch",
            "execute_command",
            "call_api",
            "execute_sql",
            "publish_message",
            "consume_message",
            "request_reply_message",
            "run_application",
            "setup_fixture",
            "execute_sample",
            "cleanup_fixture",
            "load_stubs",
            "reset_stubs",
            "http_request",
            "db_seed",
            "db_execute",
            "db_query",
            "db_cleanup",
            "nats_publish",
            "nats_observe",
            "event_published",
            "event_payload_match",
            "json_match",
            "schema_match",
            "file_diff");
    private static final Set<String> VERIFY_TYPES_REQUIRING_ACTUAL_AND_EXPECTED = Set.of(
            "equals",
            "not_equals",
            "contains",
            "regex_match",
            "list_size_equals",
            "unordered_list_equals",
            "numeric_tolerance",
            "file_diff",
            "schema_match",
            "schema_matches",
            "contract_match",
            "contract_matches",
            "json_path_equals",
            "json_match",
            "value_equals");
    private static final Set<String> VERIFY_TYPES_REQUIRING_ACTUAL_ONLY = Set.of(
            "exists",
            "not_exists",
            "file_exists",
            "file_not_empty",
            "json_path_absent");
    private static final Set<String> VERIFY_TYPES_REQUIRING_SELECTOR = Set.of(
            "json_path_equals",
            "json_path_absent",
            "numeric_tolerance");
    private static final Set<String> STATE_VERIFY_TYPES = Set.of("db_record_exists", "db_row_matches");
    private static final Set<String> EVENT_VERIFY_TYPES = Set.of("event_published");
    private static final Set<String> STATE_MUTATING_FIXTURE_TYPES = Set.of(
            "database_seed",
            "db_seed",
            "db_fixture",
            "message_event",
            "kafka_event",
            "nats_message",
            "queue_seed",
            "initial_state");

    private final DslTestCaseNormalizer normalizer = new DslTestCaseNormalizer();

    public DslValidationReport validate(Path testCasePath) {
        try {
            return validate(Files.readString(testCasePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read DSL test case: " + testCasePath, e);
        }
    }

    public DslValidationReport validate(String yaml) {
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);
        } catch (YAMLException e) {
            return new DslValidationReport(false, "", "", List.of(gap("", "", "syntax", "dsl", "",
                    "Fix YAML syntax before DSL validation can continue.")));
        }
        if (!(loaded instanceof Map<?, ?> document)) {
            return new DslValidationReport(false, "", "", List.of(gap("", "", "syntax", "dsl", "",
                    "Provide a YAML mapping for the DSL test case.")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typedDocument = (Map<String, Object>) document;
        Map<String, Object> normalized = normalizer.normalize(typedDocument);
        String testCaseId = stringValue(normalized.get("test_case_id"));
        String acId = stringValue(normalized.get("ac_id"));
        if (!isExecutionFocused(document)) {
            return new DslValidationReport(true, testCaseId, acId, List.of());
        }

        List<DslValidationGap> gaps = new ArrayList<>();
        validateIdentity(document, testCaseId, acId, gaps);
        validateLegacyAndGovernanceFields(document, testCaseId, acId, gaps);
        validateDataBinding(document, testCaseId, acId, gaps);
        validateData(document, testCaseId, acId, gaps);
        validateTargets(document, testCaseId, acId, gaps);
        validateSetup(document, testCaseId, acId, gaps);
        validateExecute(document, testCaseId, acId, gaps);
        validateExpectedResults(document, testCaseId, acId, gaps);
        validateVerify(document, testCaseId, acId, gaps);
        validateEvidence(document, testCaseId, acId, gaps);
        validateRuntime(document, testCaseId, acId, gaps);
        return new DslValidationReport(gaps.isEmpty(), testCaseId, acId, List.copyOf(gaps));
    }

    private boolean isExecutionFocused(Map<?, ?> document) {
        for (String section : EXECUTION_FOCUSED_SECTIONS) {
            if (document.containsKey(section)) {
                return true;
            }
        }
        return ALLOWED_STATUSES.contains(stringValue(document.get("status")));
    }

    private void validateIdentity(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        requireText(document, "dsl_version", "identity", testCaseId, acId, gaps,
                "Declare dsl_version v0.2 before execution.");
        String dslVersion = stringValue(document.get("dsl_version"));
        if (!isMissing(document.get("dsl_version"))
                && !Set.of("v0.2", "v1").contains(dslVersion)) {
            gaps.add(gap(testCaseId, acId, "identity", "dsl_version", "",
                    "Use supported DSL version `v0.2`; `v1` is legacy compatibility only."));
        }
        requireText(document, "test_case_id", "identity", testCaseId, acId, gaps,
                "Declare stable test_case_id before execution.");
        String status = stringValue(document.get("status"));
        if (status.isBlank()) {
            gaps.add(gap(testCaseId, acId, "identity", "status", "",
                    "Declare DSL execution status draft_skeleton, draft_executable, active, needs_update, or retired."));
        } else if (!ALLOWED_STATUSES.contains(status)) {
            gaps.add(gap(testCaseId, acId, "identity", "status", "",
                    "Use allowed DSL execution status draft_skeleton, draft_executable, active, needs_update, or retired."));
        }
        if (isMissing(document.get("revision"))) {
            gaps.add(gap(testCaseId, acId, "identity", "revision", "",
                    "Declare revision before execution."));
        }
        if ("v1".equals(dslVersion)) {
            validateLegacyTraceability(document, testCaseId, acId, gaps);
        } else {
            validateSourceRefs(document, testCaseId, acId, gaps);
        }
    }

    private void validateLegacyTraceability(
            Map<?, ?> document,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        Map<?, ?> traceability = map(document.get("traceability"));
        if (traceability.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "traceability", "traceability", "",
                    "Declare traceability.package_id, acceptance_criteria_id, and source."));
            return;
        }
        requireText(traceability, "package_id", "traceability", testCaseId, acId, gaps,
                "Declare traceability.package_id for RP evidence.", "traceability.package_id");
        requireText(traceability, "acceptance_criteria_id", "traceability", testCaseId, acId, gaps,
                "Declare traceability.acceptance_criteria_id for AC evidence.",
                "traceability.acceptance_criteria_id");
        requireText(traceability, "source", "traceability", testCaseId, acId, gaps,
                "Declare traceability.source for AC/source linkage.", "traceability.source");
    }

    private void validateSourceRefs(
            Map<?, ?> document,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        Map<?, ?> sourceRefs = map(document.get("source_refs"));
        if (sourceRefs.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "source_refs", "source_refs", "",
                    "Declare source_refs.acceptance_criteria for AC/source linkage."));
            return;
        }
        requireText(sourceRefs, "acceptance_criteria", "source_refs", testCaseId, acId, gaps,
                "Declare source_refs.acceptance_criteria for AC/source linkage.",
                "source_refs.acceptance_criteria");
    }

    private void validateLegacyAndGovernanceFields(
            Map<?, ?> document,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        for (String field : LEGACY_FIELDS) {
            if (document.containsKey(field)) {
                gaps.add(gap(testCaseId, acId, "compatibility", field, "",
                        "Use execution-focused DSL v0.2 fields instead of legacy field `" + field + "`."));
            }
        }
        if ("v0.2".equals(stringValue(document.get("dsl_version"))) && document.containsKey("traceability")) {
            gaps.add(gap(testCaseId, acId, "compatibility", "traceability", "",
                    "Use source_refs and labels instead of old traceability fields in DSL v0.2."));
        }
        if ("v0.2".equals(stringValue(document.get("dsl_version")))) {
            for (String field : PROHIBITED_V02_DATA_FIELDS) {
                if (document.containsKey(field)) {
                    gaps.add(gap(testCaseId, acId, "compatibility", field, "",
                            "Use lifecycle-neutral `data` entries and bind them through operation `inputs`; `"
                                    + field + "` is not part of DSL v0.2."));
                }
            }
        }
        for (String field : DEPRECATED_DSL_FIELDS) {
            if (document.containsKey(field)) {
                gaps.add(gap(testCaseId, acId, "compatibility", field, "",
                        "Remove `scenario`; declare behavior through setup/execute/verify and provider capability through Provider Contract."));
            }
        }
        collectGovernanceFields(document, "", testCaseId, acId, gaps);
    }

    private void validateDataBinding(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        if (!document.containsKey("data_binding")) {
            return;
        }
        if ("v0.2".equals(stringValue(document.get("dsl_version")))) {
            gaps.add(gap(testCaseId, acId, "data_binding", "data_binding", "",
                    "Use lifecycle-neutral `data` entries and bind them through operation `inputs`; `data_binding` is not part of DSL v0.2."));
        }
        Map<?, ?> dataBinding = map(document.get("data_binding"));
        for (Map.Entry<?, ?> entry : dataBinding.entrySet()) {
            String category = stringValue(entry.getKey());
            if (!DataBindingCategories.allowed(category)) {
                gaps.add(gap(testCaseId, acId, "data_binding", "data_binding." + category, "",
                        "Move checked-in artifacts to `data.<name>.ref` and reference them from operation `inputs`."));
            }
        }
    }

    private void validateData(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        if (!document.containsKey("data")) {
            return;
        }
        Map<?, ?> data = map(document.get("data"));
        if (data.isEmpty() && !(document.get("data") instanceof Map<?, ?>)) {
            gaps.add(gap(testCaseId, acId, "data", "data", "",
                    "Declare data as a map of reusable data names to ref or value sources."));
            return;
        }
        for (Map.Entry<?, ?> entry : data.entrySet()) {
            String dataName = stringValue(entry.getKey());
            validateInputSource(entry.getValue(), "data." + dataName, "data", testCaseId, acId, gaps);
        }
    }

    private void collectGovernanceFields(
            Object value,
            String path,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                String fieldPath = path.isBlank() ? key : path + "." + key;
                if (GOVERNANCE_FIELDS.contains(key)) {
                    gaps.add(gap(testCaseId, acId, "governance", fieldPath, "",
                            "Move governance or release approval field `" + key
                                    + "` out of the execution DSL."));
                }
                collectGovernanceFields(entry.getValue(), fieldPath, testCaseId, acId, gaps);
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectGovernanceFields(list.get(index), path + "[" + index + "]", testCaseId, acId, gaps);
            }
        }
    }

    private void validateTargets(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        Map<?, ?> targets = map(document.get("targets"));
        if (targets.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "targets", "targets", "",
                    "Declare at least one named target with type, provider, and environment."));
            return;
        }
        boolean v02 = "v0.2".equals(stringValue(document.get("dsl_version")));
        for (Map.Entry<?, ?> entry : targets.entrySet()) {
            String targetName = stringValue(entry.getKey());
            Map<?, ?> target = map(entry.getValue());
            if (v02) {
                requireText(target, "provider_id", "targets", testCaseId, acId, gaps,
                        "Declare targets." + targetName + ".provider_id.", "targets." + targetName + ".provider_id");
            } else if (!isMissing(target.get("provider_id"))) {
                requireText(target, "provider_id", "targets", testCaseId, acId, gaps,
                        "Declare targets." + targetName + ".provider_id.", "targets." + targetName + ".provider_id");
            } else {
                requireText(target, "type", "targets", testCaseId, acId, gaps,
                        "Declare targets." + targetName + ".type.", "targets." + targetName + ".type");
                requireAnyText(target, List.of("provider", "runner"), "targets", testCaseId, acId, gaps,
                        "Declare targets." + targetName + ".provider.", "targets." + targetName + ".provider");
                requireText(target, "environment", "targets", testCaseId, acId, gaps,
                        "Declare targets." + targetName + ".environment.", "targets." + targetName + ".environment");
            }
        }
    }

    private void validateSetup(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        Map<?, ?> setup = map(document.get("setup"));
        if (setup.isEmpty() && !document.containsKey("setup")) {
            gaps.add(gap(testCaseId, acId, "setup", "setup", "",
                    "Declare setup.operations, even when no setup is required."));
            return;
        }
        if (setup.containsKey("operations")) {
            List<OperationSection> operations = operationSections(document, "setup");
            for (OperationSection operation : operations) {
                validateOperationShape(operation, map(document.get("targets")), "setup", testCaseId, acId, gaps, false);
            }
            return;
        }
        if (!(setup.get("fixtures") instanceof Map<?, ?>)) {
            gaps.add(gap(testCaseId, acId, "setup", "setup.fixtures", "",
                    "Declare setup.fixtures as a map, even when empty."));
            return;
        }
        Map<?, ?> fixtures = map(setup.get("fixtures"));
        for (Map.Entry<?, ?> entry : fixtures.entrySet()) {
            String fixtureName = stringValue(entry.getKey());
            Map<?, ?> fixture = map(entry.getValue());
            if (isStateMutatingFixture(fixture)
                    && isMissing(fixture.get("cleanup_ref"))
                    && isMissing(fixture.get("cleanup_action"))) {
                gaps.add(gap(testCaseId, acId, "setup",
                        "setup.fixtures." + fixtureName + ".cleanup_ref", "",
                        "Declare cleanup_ref for state-mutating fixture `" + fixtureName + "`."));
            }
        }
    }

    private boolean isStateMutatingFixture(Map<?, ?> fixture) {
        String type = stringValue(fixture.get("type"));
        String lifecycle = stringValue(fixture.get("lifecycle"));
        return STATE_MUTATING_FIXTURE_TYPES.contains(type)
                || "state_mutating".equals(lifecycle)
                || "mutates_state".equals(lifecycle)
                || "mutating".equals(lifecycle);
    }

    private void validateExecute(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        List<OperationSection> execute = operationSections(document, "execute");
        if (execute.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "execute", "execute", "",
                    "Declare at least one execute operation before execution."));
            return;
        }
        if (document.get("execute") instanceof List<?> && execute.size() > 1) {
            gaps.add(gap(testCaseId, acId, "execute", "execute", "",
                    "Use exactly one execute step per M1 test case; split additional operations into separate approved tests in the same batch."));
        }
        Map<?, ?> targets = map(document.get("targets"));
        for (int index = 0; index < execute.size(); index++) {
            OperationSection step = execute.get(index);
            validateOperationShape(step, targets, "execute", testCaseId, acId, gaps, true);
            if (!(step.value().get("outputs") instanceof Map<?, ?> outputs) || outputs.isEmpty()) {
                gaps.add(gap(testCaseId, acId, "execute", step.path() + ".outputs", "",
                        "Declare execute outputs used by verify or evidence."));
            }
        }
    }

    private void validateOperationShape(
            OperationSection operationSection,
            Map<?, ?> targets,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            boolean outputsRequired) {
        Map<?, ?> operationMap = operationSection.value();
        String prefix = operationSection.path();
        requireText(operationMap, "id", section, testCaseId, acId, gaps,
                "Declare " + section + " operation id.", prefix + ".id");
        String target = stringValue(operationMap.get("target"));
        if (target.isBlank()) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".target", "",
                    "Declare " + section + " target that matches a named target."));
        } else if (!targets.containsKey(target)) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".target", "",
                    "Reference an existing target in " + prefix + ".target."));
        }
        if (operationMap.containsKey("target_ru_id")) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".target_ru_id", "",
                    "Use " + section + ".operations[].target instead of legacy target_ru_id."));
        }
        String operation = stringValue(operationMap.get("operation"));
        if (operation.isBlank()) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".operation", "",
                    "Declare " + section + " operation before provider dispatch."));
        } else if ("call_ru".equals(operation)) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".operation", "",
                    "Use a provider contract operation instead of legacy `call_ru`."));
        } else if (!SUPPORTED_OPERATIONS.contains(operation)) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".operation", "",
                    "Use an operation declared by the selected Provider Contract."));
        }
        if (operationMap.containsKey("parameters")) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".parameters", "",
                    "Use " + section + ".operations[].inputs instead of legacy parameters/bind_as."));
        }
        validateOperationInputs(operationMap.get("inputs"), prefix + ".inputs", section, testCaseId, acId, gaps);
        if (outputsRequired && !(operationMap.get("outputs") instanceof Map<?, ?>)) {
            gaps.add(gap(testCaseId, acId, section, prefix + ".outputs", "",
                    "Declare execute outputs used by verify or evidence."));
        }
    }

    private void validateOperationInputs(
            Object inputsValue,
            String fieldPath,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        if (inputsValue == null) {
            return;
        }
        if (!(inputsValue instanceof Map<?, ?> inputs)) {
            gaps.add(gap(testCaseId, acId, section, fieldPath, "",
                    "Declare operation inputs as a map keyed by Provider Contract input name."));
            return;
        }
        for (Map.Entry<?, ?> entry : inputs.entrySet()) {
            String inputName = stringValue(entry.getKey());
            validateInputSource(entry.getValue(), fieldPath + "." + inputName, section, testCaseId, acId, gaps);
        }
    }

    private void validateInputSource(
            Object sourceValue,
            String fieldPath,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        if (!(sourceValue instanceof Map<?, ?> source)) {
            gaps.add(gap(testCaseId, acId, section, fieldPath, "",
                    "Declare a data/input source as a map with exactly one of `ref` or `value`."));
            return;
        }
        boolean hasRef = !isMissing(source.get("ref"));
        boolean hasValue = source.containsKey("value") && !isMissing(source.get("value"));
        if (hasRef == hasValue) {
            gaps.add(gap(testCaseId, acId, section, fieldPath, "",
                    "Declare exactly one source: `ref` for checked-in artifact references or `value` for safe literals."));
        }
    }

    private boolean usesModernDataOrOperationShape(Map<?, ?> document) {
        return document.containsKey("data")
                || hasOperationsMap(document, "setup")
                || hasOperationsMap(document, "execute")
                || hasOperationsMap(document, "cleanup")
                || map(document.get("verify")).containsKey("checks");
    }

    private boolean hasOperationsMap(Map<?, ?> document, String section) {
        return map(document.get(section)).containsKey("operations");
    }

    private List<OperationSection> operationSections(Map<?, ?> document, String section) {
        Object value = document.get(section);
        if (value instanceof Map<?, ?> sectionMap && sectionMap.get("operations") instanceof List<?> operations) {
            List<Map<?, ?>> maps = mapList(operations);
            List<OperationSection> sections = new ArrayList<>();
            for (int index = 0; index < maps.size(); index++) {
                sections.add(new OperationSection(maps.get(index), section + ".operations[" + index + "]"));
            }
            return List.copyOf(sections);
        }
        List<Map<?, ?>> legacy = mapList(value);
        List<OperationSection> sections = new ArrayList<>();
        for (int index = 0; index < legacy.size(); index++) {
            sections.add(new OperationSection(legacy.get(index), section + "[" + index + "]"));
        }
        return List.copyOf(sections);
    }

    private List<OperationSection> verifySections(Map<?, ?> document) {
        Object value = document.get("verify");
        if (value instanceof Map<?, ?> verifyMap && verifyMap.get("checks") instanceof List<?> checks) {
            List<Map<?, ?>> maps = mapList(checks);
            List<OperationSection> sections = new ArrayList<>();
            for (int index = 0; index < maps.size(); index++) {
                sections.add(new OperationSection(maps.get(index), "verify.checks[" + index + "]"));
            }
            return List.copyOf(sections);
        }
        List<Map<?, ?>> legacy = mapList(value);
        List<OperationSection> sections = new ArrayList<>();
        for (int index = 0; index < legacy.size(); index++) {
            sections.add(new OperationSection(legacy.get(index), "verify[" + index + "]"));
        }
        return List.copyOf(sections);
    }

    private void validateExpectedResults(
            Map<?, ?> document,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        if ("v0.2".equals(stringValue(document.get("dsl_version")))) {
            return;
        }
        if (!document.containsKey("expected_results")) {
            if (!usesModernDataOrOperationShape(document)) {
                gaps.add(gap(testCaseId, acId, "expected_results", "expected_results", "",
                        "Declare expected_results as a map, even when deterministic verify rules do not use artifacts."));
            }
            return;
        }
        Map<?, ?> expectedResults = map(document.get("expected_results"));
        for (Map.Entry<?, ?> entry : expectedResults.entrySet()) {
            String name = stringValue(entry.getKey());
            Map<?, ?> expected = map(entry.getValue());
            requireText(expected, "type", "expected_results", testCaseId, acId, gaps,
                    "Declare expected_results." + name + ".type.",
                    "expected_results." + name + ".type");
            if (requiresExpectedRef(stringValue(expected.get("type"))) && isMissing(expected.get("ref"))) {
                gaps.add(gap(testCaseId, acId, "expected_results", "expected_results." + name + ".ref", "",
                        "Declare expected result ref for `" + name + "`."));
            }
        }
    }

    private boolean requiresExpectedRef(String type) {
        return type.isBlank()
                || List.of("expected_result_artifact", "golden_file", "schema", "contract").contains(type);
    }

    private void validateVerify(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        List<OperationSection> verify = verifySections(document);
        if (verify.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "verify", "verify", "",
                    "Declare at least one verify rule before execution."));
            return;
        }
        Map<?, ?> targets = map(document.get("targets"));
        Set<String> expectedNames = map(document.get("expected_results")).keySet().stream()
                .map(this::stringValue)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Set<String>> executeOutputs = executeOutputs(document);
        boolean requestResponseMetadataAvailable = hasRequestResponseExecuteTarget(document);
        for (int index = 0; index < verify.size(); index++) {
            Map<?, ?> rule = verify.get(index).value();
            String prefix = verify.get(index).path();
            String verifyId = stringValue(rule.get("id"));
            requireText(rule, "id", "verify", testCaseId, acId, gaps,
                    "Declare verify rule id.", prefix + ".id");
            String type = stringValue(rule.get("type"));
            if (type.isBlank()) {
                gaps.add(gap(testCaseId, acId, "verify", prefix + ".type", verifyId,
                        "Declare verify type."));
                continue;
            }
            validateSelectorWhenRequired(rule, type, prefix, verifyId, testCaseId, acId, gaps);
            validateToleranceWhenRequired(rule, type, prefix, verifyId, testCaseId, acId, gaps);
            if (VERIFY_TYPES_REQUIRING_ACTUAL_AND_EXPECTED.contains(type)) {
                requireText(rule, "actual", "verify", testCaseId, acId, gaps,
                        "Declare actual source for verify rule `" + verifyId + "`.", prefix + ".actual", verifyId);
                if (!hasExpected(rule)) {
                    gaps.add(gap(testCaseId, acId, "verify", prefix + ".expected", verifyId,
                            "Declare expected source or value for verify rule `" + verifyId + "`."));
                }
                validateReference(rule.get("actual"), prefix + ".actual", "verify", verifyId, expectedNames,
                        executeOutputs, Set.of(), testCaseId, acId, gaps);
                validateExpectedReference(firstNonMissing(rule.get("expected"), rule.get("expected_ref")),
                        prefix + ".expected", verifyId, expectedNames,
                        testCaseId, acId, gaps);
            } else if ("response_status_equals".equals(type)) {
                requirePresent(rule, "expected", "verify", testCaseId, acId, gaps,
                        "Declare expected status for verify rule `" + verifyId + "`.", prefix + ".expected", verifyId);
                if (!isMissing(rule.get("actual"))) {
                    requireSelector(rule, prefix, verifyId, testCaseId, acId, gaps,
                            "Declare selector when response_status_equals reads status from a captured actual output.");
                    validateReference(rule.get("actual"), prefix + ".actual", "verify", verifyId, expectedNames,
                            executeOutputs, Set.of(), testCaseId, acId, gaps);
                } else if (!requestResponseMetadataAvailable) {
                    gaps.add(gap(testCaseId, acId, "verify", prefix + ".actual", verifyId,
                            "Declare actual plus selector, or execute through a request_response target that supplies provider HTTP status metadata."));
                }
                validateExpectedReference(rule.get("expected"), prefix + ".expected", verifyId, expectedNames,
                        testCaseId, acId, gaps);
            } else if (VERIFY_TYPES_REQUIRING_ACTUAL_ONLY.contains(type)) {
                requireText(rule, "actual", "verify", testCaseId, acId, gaps,
                        "Declare actual source for verify rule `" + verifyId + "`.", prefix + ".actual", verifyId);
                validateReference(rule.get("actual"), prefix + ".actual", "verify", verifyId, expectedNames,
                        executeOutputs, Set.of(), testCaseId, acId, gaps);
            } else if (STATE_VERIFY_TYPES.contains(type)) {
                requireText(rule, "target", "verify", testCaseId, acId, gaps,
                        "Declare target for state verification rule `" + verifyId + "`.", prefix + ".target", verifyId);
                validateTargetReference(rule.get("target"), targets, prefix + ".target", verifyId, testCaseId, acId, gaps);
                if (!(rule.get("query") instanceof Map<?, ?> query) || isMissing(query.get("ref"))) {
                    gaps.add(gap(testCaseId, acId, "verify", prefix + ".query.ref", verifyId,
                            "Declare query.ref for state verification rule `" + verifyId + "`."));
                }
                requirePresent(rule, "expected", "verify", testCaseId, acId, gaps,
                        "Declare expected state for verify rule `" + verifyId + "`.", prefix + ".expected", verifyId);
            } else if (EVENT_VERIFY_TYPES.contains(type)) {
                requireText(rule, "target", "verify", testCaseId, acId, gaps,
                        "Declare target for event verification rule `" + verifyId + "`.", prefix + ".target", verifyId);
                validateTargetReference(rule.get("target"), targets, prefix + ".target", verifyId, testCaseId, acId, gaps);
                if (!(rule.get("event") instanceof Map<?, ?>)) {
                    gaps.add(gap(testCaseId, acId, "verify", prefix + ".event", verifyId,
                            "Declare event reference for event verification rule `" + verifyId + "`."));
                }
                requirePresent(rule, "expected", "verify", testCaseId, acId, gaps,
                        "Declare expected event match for verify rule `" + verifyId + "`.", prefix + ".expected", verifyId);
            } else {
                gaps.add(gap(testCaseId, acId, "verify", prefix + ".type", verifyId,
                        "Use a supported v0.2 verify type before assertion evaluation."));
            }
        }
    }

    private boolean hasRequestResponseExecuteTarget(Map<?, ?> document) {
        Map<?, ?> targets = map(document.get("targets"));
        for (OperationSection stepSection : operationSections(document, "execute")) {
            Map<?, ?> step = stepSection.value();
            Map<?, ?> target = map(targets.get(stringValue(step.get("target"))));
            if ("request_response".equals(firstText(target, "provider", "runner"))) {
                return true;
            }
        }
        return false;
    }

    private void validateSelectorWhenRequired(
            Map<?, ?> rule,
            String type,
            String prefix,
            String verifyId,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        if (VERIFY_TYPES_REQUIRING_SELECTOR.contains(type)
                && isMissing(rule.get("selector"))
                && isMissing(rule.get("path"))
                && isMissing(rule.get("json_path"))) {
            requireSelector(rule, prefix, verifyId, testCaseId, acId, gaps,
                    "Declare selector for JSON path verify rule `" + verifyId + "`.");
        }
    }

    private void requireSelector(
            Map<?, ?> rule,
            String prefix,
            String verifyId,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            String ownerAction) {
        if (isMissing(rule.get("selector"))
                && isMissing(rule.get("path"))
                && isMissing(rule.get("json_path"))) {
            gaps.add(gap(testCaseId, acId, "verify", prefix + ".selector", verifyId, ownerAction));
        }
    }

    private void validateToleranceWhenRequired(
            Map<?, ?> rule,
            String type,
            String prefix,
            String verifyId,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        if ("numeric_tolerance".equals(type)
                && isMissing(rule.get("tolerance"))
                && isMissing(rule.get("epsilon"))
                && isMissing(map(rule.get("options")).get("tolerance"))
                && isMissing(map(rule.get("options")).get("epsilon"))) {
            gaps.add(gap(testCaseId, acId, "verify", prefix + ".options.tolerance", verifyId,
                    "Declare tolerance for numeric_tolerance verify rule `" + verifyId + "`."));
        }
    }

    private void validateEvidence(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        Map<?, ?> evidence = map(document.get("evidence"));
        if (evidence.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "evidence", "evidence", "",
                    "Declare evidence.required before execution."));
            return;
        }
        Object required = evidence.get("required");
        if (!(required instanceof List<?> list) || list.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "evidence", "evidence.required", "",
                    "Declare concrete execute or verify evidence references."));
            return;
        }
        Map<String, Set<String>> executeOutputs = executeOutputs(document);
        Set<String> verifyIds = verifyIds(document);
        for (int index = 0; index < list.size(); index++) {
            String value = stringValue(list.get(index));
            if (!value.startsWith("${execute.") && !value.startsWith("${verify.")) {
                gaps.add(gap(testCaseId, acId, "evidence", "evidence.required[" + index + "]", "",
                        "Reference concrete execute or verify outputs in evidence.required."));
                continue;
            }
            validateReference(value, "evidence.required[" + index + "]", "evidence", "", Set.of(),
                    executeOutputs, verifyIds, testCaseId, acId, gaps);
        }
    }

    private void validateReference(
            Object value,
            String fieldPath,
            String section,
            String verifyId,
            Set<String> expectedNames,
            Map<String, Set<String>> executeOutputs,
            Set<String> verifyIds,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        String reference = referenceText(value);
        if (reference.startsWith("${execute.")) {
            String[] parts = expressionParts(reference);
            if (parts.length < 4 || !"outputs".equals(parts[2])
                    || !executeOutputs.getOrDefault(parts[1], Set.of()).contains(parts[3])) {
                gaps.add(gap(testCaseId, acId, section, fieldPath, verifyId,
                        "Reference a declared execute output before execution."));
            }
        } else if (reference.startsWith("${verify.")) {
            String[] parts = expressionParts(reference);
            if (parts.length < 3 || !"result".equals(parts[2]) || !verifyIds.contains(parts[1])) {
                gaps.add(gap(testCaseId, acId, section, fieldPath, verifyId,
                        "Reference a declared verify result before evidence collection."));
            }
        } else if (reference.startsWith("${expected_results.")) {
            String expectedName = expectedResultName(reference);
            if (!expectedNames.contains(expectedName)) {
                gaps.add(gap(testCaseId, acId, section, fieldPath, verifyId,
                        "Reference a declared expected_results entry before assertion evaluation."));
            }
        }
    }

    private void validateExpectedReference(
            Object value,
            String fieldPath,
            String verifyId,
            Set<String> expectedNames,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        String reference = referenceText(value);
        if (reference.startsWith("${expected_results.")) {
            String expectedName = expectedResultName(reference);
            if (!expectedNames.contains(expectedName)) {
                gaps.add(gap(testCaseId, acId, "verify", fieldPath, verifyId,
                        "Reference a declared expected_results entry before assertion evaluation."));
            }
        }
    }

    private void validateTargetReference(
            Object value,
            Map<?, ?> targets,
            String fieldPath,
            String verifyId,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps) {
        String target = stringValue(value);
        if (!target.isBlank() && !targets.containsKey(target)) {
            gaps.add(gap(testCaseId, acId, "verify", fieldPath, verifyId,
                    "Reference an existing target for verification."));
        }
    }

    private Map<String, Set<String>> executeOutputs(Map<?, ?> document) {
        Map<String, Set<String>> outputsByStep = new java.util.LinkedHashMap<>();
        for (OperationSection stepSection : operationSections(document, "execute")) {
            Map<?, ?> step = stepSection.value();
            String stepId = stringValue(step.get("id"));
            if (!stepId.isBlank()) {
                outputsByStep.put(stepId, map(step.get("outputs")).keySet().stream()
                        .map(this::stringValue)
                        .collect(java.util.stream.Collectors.toSet()));
            }
        }
        return outputsByStep;
    }

    private Set<String> verifyIds(Map<?, ?> document) {
        return verifySections(document).stream()
                .map(rule -> stringValue(rule.value().get("id")))
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }

    private String[] expressionParts(String reference) {
        if (!reference.startsWith("${") || !reference.endsWith("}")) {
            return new String[0];
        }
        return reference.substring(2, reference.length() - 1).split("\\.");
    }

    private String expectedResultName(String reference) {
        String[] parts = expressionParts(reference);
        return parts.length >= 2 ? parts[1] : "";
    }

    private void validateRuntime(Map<?, ?> document, String testCaseId, String acId, List<DslValidationGap> gaps) {
        Map<?, ?> runtime = map(document.get("runtime"));
        if (runtime.isEmpty()) {
            gaps.add(gap(testCaseId, acId, "runtime", "runtime", "",
                    "Declare runtime timeout and retry policy before execution."));
            return;
        }
        requireText(runtime, "timeout", "runtime", testCaseId, acId, gaps,
                "Declare bounded runtime.timeout before execution.", "runtime.timeout");
        Map<?, ?> retry = map(runtime.get("retry"));
        if (retry.isEmpty() || isMissing(retry.get("max_attempts"))) {
            gaps.add(gap(testCaseId, acId, "runtime", "runtime.retry.max_attempts", "",
                    "Declare bounded runtime.retry.max_attempts before execution."));
        } else if (integerValue(retry.get("max_attempts")) < 0) {
            gaps.add(gap(testCaseId, acId, "runtime", "runtime.retry.max_attempts", "",
                    "Declare runtime.retry.max_attempts as a non-negative integer."));
        }
    }

    private int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void requireText(
            Map<?, ?> map,
            String field,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            String ownerAction) {
        requireText(map, field, section, testCaseId, acId, gaps, ownerAction, field, "");
    }

    private void requireText(
            Map<?, ?> map,
            String field,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            String ownerAction,
            String fieldPath) {
        requireText(map, field, section, testCaseId, acId, gaps, ownerAction, fieldPath, "");
    }

    private void requireText(
            Map<?, ?> map,
            String field,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            String ownerAction,
            String fieldPath,
            String verifyId) {
        if (isMissing(map.get(field))) {
            gaps.add(gap(testCaseId, acId, section, fieldPath, verifyId, ownerAction));
        }
    }

    private void requireAnyText(
            Map<?, ?> map,
            List<String> fields,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            String ownerAction,
            String fieldPath) {
        if (fields.stream().allMatch(field -> isMissing(map.get(field)))) {
            gaps.add(gap(testCaseId, acId, section, fieldPath, "", ownerAction));
        }
    }

    private void requirePresent(
            Map<?, ?> map,
            String field,
            String section,
            String testCaseId,
            String acId,
            List<DslValidationGap> gaps,
            String ownerAction,
            String fieldPath,
            String verifyId) {
        if (!map.containsKey(field) || map.get(field) == null || stringValue(map.get(field)).isBlank()) {
            gaps.add(gap(testCaseId, acId, section, fieldPath, verifyId, ownerAction));
        }
    }

    private DslValidationGap gap(
            String testCaseId,
            String acId,
            String section,
            String fieldPath,
            String verifyId,
            String ownerAction) {
        return new DslValidationGap(testCaseId, acId, section, fieldPath, verifyId, ownerAction);
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private List<Map<?, ?>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                maps.add(map);
            }
        }
        return List.copyOf(maps);
    }

    private boolean isMissing(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private boolean hasExpected(Map<?, ?> rule) {
        return !isMissing(rule.get("expected")) || !isMissing(rule.get("expected_ref"));
    }

    private Object firstNonMissing(Object... values) {
        for (Object value : values) {
            if (!isMissing(value)) {
                return value;
            }
        }
        return null;
    }

    private String referenceText(Object value) {
        if (value instanceof Map<?, ?> map && map.containsKey("ref")) {
            return stringValue(map.get("ref"));
        }
        return stringValue(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record OperationSection(Map<?, ?> value, String path) {
    }
}
