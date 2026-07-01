package com.specdriven.regression.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DslTestCaseNormalizer {

    public Map<String, Object> normalize(Map<String, Object> testCase) {
        if (testCase == null || testCase.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>(testCase);
        Map<?, ?> traceability = map(testCase.get("traceability"));
        Map<?, ?> sourceRefs = map(testCase.get("source_refs"));
        Map<?, ?> labels = map(testCase.get("labels"));
        putIfMissing(normalized, "rp_id",
                firstNonBlank(firstText(traceability, "package_id", "rp_id"),
                        firstText(labels, "package", "rp_id")));
        putIfMissing(normalized, "ac_id",
                firstNonBlank(firstText(traceability, "acceptance_criteria_id", "ac_id"),
                        acIdFromSourceRef(firstText(sourceRefs, "acceptance_criteria"))));
        putIfMissing(normalized, "artifact_status", stringValue(testCase.get("status")));
        addSourceRefs(normalized, traceability);

        Map<?, ?> targets = map(testCase.get("targets"));
        Map<?, ?> setup = map(testCase.get("setup"));
        Map<?, ?> cleanup = map(testCase.get("cleanup"));
        Map<?, ?> data = map(testCase.get("data"));
        List<Map<?, ?>> execute = operationMaps(testCase, "execute");
        List<Map<?, ?>> verify = verifyMaps(testCase);
        addExecutionTarget(normalized, targets, execute, map(testCase.get("runtime")));
        addPackageInputs(normalized, setup, execute, cleanup);
        addFixture(normalized, setup, cleanup);
        addSteps(normalized, targets, execute);
        addExpected(normalized, map(testCase.get("expected_results")), data, verify);
        addOracles(normalized, map(testCase.get("expected_results")), data, verify);
        addAssertions(normalized, map(testCase.get("expected_results")), data, verify);
        addEvidenceRequired(normalized, map(testCase.get("evidence")));
        addPolicy(normalized, map(testCase.get("runtime")));
        return normalized;
    }

    private void addSourceRefs(Map<String, Object> normalized, Map<?, ?> traceability) {
        if (normalized.containsKey("source_refs")) {
            return;
        }
        String source = firstText(traceability, "source", "acceptance_criteria");
        if (!source.isBlank()) {
            normalized.put("source_refs", Map.of("acceptance_criteria", source));
        }
    }

    private void addExecutionTarget(
            Map<String, Object> normalized,
            Map<?, ?> targets,
            List<Map<?, ?>> execute,
            Map<?, ?> runtime) {
        if (normalized.containsKey("execution_target")) {
            return;
        }
        String targetId = primaryTargetId(targets, execute);
        Map<?, ?> target = target(targets, targetId);
        String ruId = firstText(target, "ru_id", "release_unit_id", "release_unit");
        if (ruId.isBlank()) {
            ruId = targetId;
        }
        String environment = firstText(target, "environment", "environment_ref");
        Map<String, Object> executionTarget = new LinkedHashMap<>();
        putIfNotBlank(executionTarget, "ru_id", ruId);
        putIfNotBlank(executionTarget, "provider", firstText(target, "provider", "runner"));
        putIfNotBlank(executionTarget, "execution_mode",
                firstNonBlank(firstText(target, "execution_mode"), firstText(runtime, "execution_mode"),
                        executionModeFromEnvironment(environment)));
        putIfNotBlank(executionTarget, "environment_ref", environment);
        if (!executionTarget.isEmpty()) {
            normalized.put("execution_target", executionTarget);
        }
    }

    private void addPackageInputs(
            Map<String, Object> normalized,
            Map<?, ?> setup,
            List<Map<?, ?>> execute,
            Map<?, ?> cleanup) {
        if (normalized.containsKey("package_inputs")) {
            return;
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        addFixtureInputs(inputs, setup.get("fixtures"));
        addOperationInputs(inputs, sectionOperations(setup));
        addExecuteInputs(inputs, execute);
        addOperationInputs(inputs, sectionOperations(cleanup));
        if (!inputs.isEmpty()) {
            normalized.put("package_inputs", Map.of("inputs", inputs));
        }
    }

    private void addFixtureInputs(Map<String, Object> inputs, Object fixturesValue) {
        for (NamedSection fixture : namedSections(fixturesValue)) {
            Map<String, Object> input = new LinkedHashMap<>();
            String bindingType = bindingType(firstText(fixture.value(), "bind_as", "binding_type", "type"));
            putIfNotBlank(input, "ref", firstText(fixture.value(), "ref", "data_ref", "fixture_ref", "payload_ref"));
            putIfNotBlank(input, "bind_as", bindingType);
            putIfNotBlank(input, "lifecycle",
                    firstNonBlank(firstText(fixture.value(), "lifecycle"), defaultLifecycle(bindingType)));
            if (!input.isEmpty()) {
                inputs.putIfAbsent(fixture.name(), input);
            }
        }
    }

    private void addExecuteInputs(Map<String, Object> inputs, List<Map<?, ?>> execute) {
        for (Map<?, ?> step : execute) {
            addLegacyWithInputs(inputs, step);
            addOperationInputs(inputs, List.of(step));
        }
    }

    private void addOperationInputs(Map<String, Object> inputs, List<Map<?, ?>> operations) {
        for (Map<?, ?> operation : operations) {
            Map<?, ?> operationInputs = map(operation.get("inputs"));
            String operationId = firstNonBlank(firstText(operation, "id"), "operation");
            for (Map.Entry<?, ?> entry : operationInputs.entrySet()) {
                String inputName = stringValue(entry.getKey());
                String name = safeName(operationId + "_" + inputName);
                if (inputName.isBlank() || inputs.containsKey(name)) {
                    continue;
                }
                Map<String, Object> input = new LinkedHashMap<>();
                Map<?, ?> inputMap = map(entry.getValue());
                putIfNotBlank(input, "ref", firstText(inputMap, "ref"));
                if (inputMap.containsKey("value")) {
                    input.put("value", inputMap.get("value"));
                }
                putIfNotBlank(input, "bind_as", inputName);
                putIfNotBlank(input, "lifecycle",
                        firstNonBlank(firstText(inputMap, "lifecycle"), defaultLifecycle(inputName)));
                if (!input.isEmpty()) {
                    inputs.put(name, input);
                }
            }
        }
    }

    private void addLegacyWithInputs(Map<String, Object> inputs, Map<?, ?> step) {
        Object with = step.get("with");
        if (!(with instanceof Map<?, ?> withMap)) {
            return;
        }
        for (Map.Entry<?, ?> entry : withMap.entrySet()) {
            String name = stringValue(entry.getKey());
            if (name.isBlank() || inputs.containsKey(name)) {
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> inputMap) {
                String bindingType = bindingType(firstText(inputMap, "bind_as", "binding_type", "type"));
                putIfNotBlank(input, "ref", firstText(inputMap, "ref", "data_ref", "payload_ref", "fixture_ref"));
                putIfNotBlank(input, "bind_as", bindingType);
                putIfNotBlank(input, "lifecycle",
                        firstNonBlank(firstText(inputMap, "lifecycle"), defaultLifecycle(bindingType)));
            } else {
                putIfNotBlank(input, "ref", stringValue(value));
            }
            if (!input.isEmpty()) {
                inputs.put(name, input);
            }
        }
    }

    private void addFixture(Map<String, Object> normalized, Map<?, ?> setup, Map<?, ?> cleanup) {
        if (normalized.containsKey("fixture")) {
            return;
        }
        List<Map<String, Object>> setupActions = new ArrayList<>();
        List<Map<String, Object>> cleanupActions = new ArrayList<>();
        for (NamedSection fixture : namedSections(setup.get("fixtures"))) {
            String provider = firstText(fixture.value(), "provider", "fixture_provider");
            String setupAction = firstText(fixture.value(), "setup_action", "action");
            String cleanupAction = firstText(fixture.value(), "cleanup_action");
            String lifecycle = normalizedLifecycle(firstText(fixture.value(), "lifecycle"),
                    bindingType(firstText(fixture.value(), "bind_as", "binding_type", "type")));
            if (!provider.isBlank() && !setupAction.isBlank()) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("id", "setup_" + fixture.name());
                action.put("provider", provider);
                action.put("action", setupAction);
                putIfNotBlank(action, "lifecycle", lifecycle);
                action.put("input", "${package_inputs.inputs." + fixture.name() + "}");
                setupActions.add(action);
            }
            if (!provider.isBlank() && !cleanupAction.isBlank()) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("id", "cleanup_" + fixture.name());
                action.put("provider", provider);
                action.put("action", cleanupAction);
                cleanupActions.add(action);
            }
        }
        addOperationFixtureActions(setupActions, setup.get("operations"), "setup");
        addOperationFixtureActions(cleanupActions, cleanup.get("operations"), "cleanup");
        if (!setupActions.isEmpty() || !cleanupActions.isEmpty()) {
            Map<String, Object> fixture = new LinkedHashMap<>();
            fixture.put("setup", setupActions);
            fixture.put("cleanup", cleanupActions);
            normalized.put("fixture", fixture);
        }
    }

    private void addOperationFixtureActions(List<Map<String, Object>> actions, Object operationsValue, String phase) {
        for (Map<?, ?> operation : mapList(operationsValue)) {
            String id = firstText(operation, "id");
            String operationName = firstText(operation, "operation", "action");
            if (id.isBlank() || operationName.isBlank()) {
                continue;
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("id", id);
            putIfNotBlank(action, "provider", firstText(operation, "target"));
            action.put("action", operationName);
            String firstInput = firstInputName(operation);
            if (!firstInput.isBlank()) {
                action.put("input", "${package_inputs.inputs." + safeName(id + "_" + firstInput) + "}");
            }
            action.put("phase", phase);
            actions.add(action);
        }
    }

    private void addSteps(
            Map<String, Object> normalized,
            Map<?, ?> targets,
            List<Map<?, ?>> execute) {
        if (normalized.containsKey("steps") || execute.isEmpty()) {
            return;
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        int index = 1;
        for (Map<?, ?> entry : execute) {
            String targetId = firstNonBlank(firstText(entry, "target", "target_ref"), primaryTargetId(targets, execute));
            Map<?, ?> target = target(targets, targetId);
            String ruId = firstText(target, "ru_id", "release_unit_id", "release_unit");
            if (ruId.isBlank()) {
                ruId = targetId;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", firstNonBlank(firstText(entry, "id"), "execute_" + index));
            step.put("action", firstText(entry, "operation", "action"));
            putIfNotBlank(step, "target_ru_id", ruId);
            String firstInput = firstInputName(entry);
            if (!firstInput.isBlank()) {
                String inputName = entry.get("inputs") instanceof Map<?, ?>
                        ? safeName(firstText(entry, "id") + "_" + firstInput)
                        : firstInput;
                step.put("input", "${package_inputs.inputs." + inputName + "}");
            }
            steps.add(step);
            index++;
        }
        normalized.put("steps", steps);
    }

    private void addExpected(
            Map<String, Object> normalized,
            Map<?, ?> expectedResults,
            Map<?, ?> data,
            List<Map<?, ?>> verify) {
        if (normalized.containsKey("expected") || expectedResults.isEmpty()) {
            if (!normalized.containsKey("expected") && expectedResults.isEmpty()) {
                String expectedRef = firstNonBlank(firstExpectedDataRef(data), firstVerifyExpectedArtifactRef(verify, data));
                if (!expectedRef.isBlank()) {
                    normalized.put("expected", Map.of("ref", expectedRef));
                }
            }
            return;
        }
        Map<?, ?> first = firstSection(expectedResults);
        String ref = firstText(first, "ref", "artifact_ref", "file", "path", "schema_ref", "contract_ref");
        if (!ref.isBlank()) {
            normalized.put("expected", Map.of("ref", ref));
        }
    }

    private void addOracles(
            Map<String, Object> normalized,
            Map<?, ?> expectedResults,
            Map<?, ?> data,
            List<Map<?, ?>> verify) {
        if (normalized.containsKey("oracles") || expectedResults.isEmpty()) {
            if (!normalized.containsKey("oracles") && expectedResults.isEmpty()) {
                Map<String, Object> dataOracles = dataOracles(data);
                for (Map.Entry<String, Object> entry : verifyOracles(verify, data).entrySet()) {
                    dataOracles.putIfAbsent(entry.getKey(), entry.getValue());
                }
                if (!dataOracles.isEmpty()) {
                    normalized.put("oracles", dataOracles);
                }
            }
            return;
        }
        Map<String, Object> oracles = new LinkedHashMap<>();
        for (NamedSection expected : namedSections(expectedResults)) {
            Map<String, Object> oracle = new LinkedHashMap<>();
            oracle.put("type", oracleType(firstText(expected.value(), "type")));
            copyIfPresent(expected.value(), oracle, "provider", "fixture_provider", "query", "query_name",
                    "expected_count", "row_count");
            String ref = firstText(expected.value(), "ref", "artifact_ref", "file", "path", "schema_ref",
                    "contract_ref", "sql_ref");
            putIfNotBlank(oracle, "ref", ref);
            oracles.put(expected.name(), oracle);
        }
        normalized.put("oracles", oracles);
    }

    private void addAssertions(
            Map<String, Object> normalized,
            Map<?, ?> expectedResults,
            Map<?, ?> data,
            List<Map<?, ?>> verify) {
        if (normalized.containsKey("assertions") || verify.isEmpty()) {
            return;
        }
        String fallbackOracle = expectedResults.isEmpty() ? "" : stringValue(expectedResults.keySet().iterator().next());
        List<Map<String, Object>> assertions = new ArrayList<>();
        int index = 1;
        for (Map<?, ?> verifyItem : verify) {
            Map<String, Object> assertion = new LinkedHashMap<>();
            String type = assertionType(firstText(verifyItem, "type", "assertion"));
            assertion.put("type", type);
            copyIfPresent(verifyItem, assertion, "path", "json_path", "actual", "expected_value", "expected_status",
                    "tolerance", "query", "provider");
            Object actual = verifyItem.get("actual");
            if (actual instanceof Map<?, ?> actualMap) {
                putIfNotBlank(assertion, "actual", firstText(actualMap, "ref"));
            }
            if (assertion.get("path") == null && assertion.get("json_path") == null) {
                putIfNotBlank(assertion, "path", firstText(verifyItem, "selector"));
            }
            addAssertionOptions(assertion, verifyItem);
            if (assertion.get("path") == null && sourceReference(verifyItem.get("actual")).startsWith("$.")) {
                assertion.put("path", sourceReference(verifyItem.get("actual")));
            }
            String expected = expectedReference(verifyItem);
            String oracleName = expectedResultName(expected, expectedResults);
            if (oracleName.isBlank() && expectedResults.isEmpty()
                    && !expectedArtifactReference(verifyItem, data).isBlank()) {
                oracleName = verifyOracleName(verifyItem, index);
            }
            if (oracleName.isBlank()) {
                oracleName = fallbackOracle;
            }
            if (!oracleName.isBlank()) {
                assertion.put("oracle", "${oracles." + oracleName + "}");
            } else if (!expected.isBlank() && assertion.get("expected_value") == null) {
                assertion.put("expected_value", expected);
            }
            assertions.add(assertion);
            index++;
        }
        normalized.put("assertions", assertions);
    }

    private void addAssertionOptions(Map<String, Object> assertion, Map<?, ?> verifyItem) {
        Map<?, ?> options = map(verifyItem.get("options"));
        if (assertion.get("tolerance") == null) {
            putIfNotBlank(assertion, "tolerance", firstText(options, "tolerance"));
        }
        if (assertion.get("epsilon") == null) {
            putIfNotBlank(assertion, "epsilon", firstText(options, "epsilon"));
        }
    }

    private void addEvidenceRequired(Map<String, Object> normalized, Map<?, ?> evidence) {
        if (normalized.containsKey("evidence_required")) {
            return;
        }
        Object required = evidence.get("required");
        if (required instanceof List<?> list) {
            normalized.put("evidence_required", List.copyOf(list));
        }
    }

    private void addPolicy(Map<String, Object> normalized, Map<?, ?> runtime) {
        if (normalized.containsKey("policy") || runtime.isEmpty()) {
            return;
        }
        normalized.put("policy", new LinkedHashMap<>(runtime));
    }

    private List<NamedSection> namedSections(Object value) {
        List<NamedSection> sections = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> section) {
                    sections.add(new NamedSection(stringValue(entry.getKey()), section));
                }
            }
            return sections;
        }
        if (value instanceof List<?> list) {
            int index = 1;
            for (Object item : list) {
                if (item instanceof Map<?, ?> section) {
                    String name = firstNonBlank(firstText(section, "name", "id"), "fixture_" + index);
                    sections.add(new NamedSection(name, section));
                    index++;
                }
            }
        }
        return sections;
    }

    private String primaryTargetId(Map<?, ?> targets, List<Map<?, ?>> execute) {
        for (Map<?, ?> step : execute) {
            String target = firstText(step, "target", "target_ref");
            if (!target.isBlank()) {
                return target;
            }
        }
        if (!targets.isEmpty()) {
            return stringValue(targets.keySet().iterator().next());
        }
        return "";
    }

    private String firstVerifyExpectedArtifactRef(List<Map<?, ?>> verify, Map<?, ?> data) {
        for (Map<?, ?> verifyItem : verify) {
            String expectedRef = expectedArtifactReference(verifyItem, data);
            if (!expectedRef.isBlank()) {
                return expectedRef;
            }
        }
        return "";
    }

    private Map<?, ?> target(Map<?, ?> targets, String targetId) {
        Object target = targets.get(targetId);
        if (target instanceof Map<?, ?> targetMap) {
            return targetMap;
        }
        if (!targets.isEmpty() && targets.values().iterator().next() instanceof Map<?, ?> fallback) {
            return fallback;
        }
        return Map.of();
    }

    private Map<?, ?> firstSection(Map<?, ?> sections) {
        if (!sections.isEmpty() && sections.values().iterator().next() instanceof Map<?, ?> section) {
            return section;
        }
        return Map.of();
    }

    private String firstInputName(Map<?, ?> operation) {
        Map<?, ?> inputs = map(operation.get("inputs"));
        if (!inputs.isEmpty()) {
            return stringValue(inputs.keySet().iterator().next());
        }
        Object with = operation.get("with");
        if (with instanceof Map<?, ?> withMap && !withMap.isEmpty()) {
            return stringValue(withMap.keySet().iterator().next());
        }
        return "";
    }

    private List<Map<?, ?>> operationMaps(Map<?, ?> container, String section) {
        Object value = container.get(section);
        if (value instanceof Map<?, ?> sectionMap && sectionMap.get("operations") instanceof List<?>) {
            return mapList(sectionMap.get("operations"));
        }
        return mapList(value);
    }

    private List<Map<?, ?>> sectionOperations(Map<?, ?> section) {
        return mapList(section.get("operations"));
    }

    private List<Map<?, ?>> verifyMaps(Map<?, ?> testCase) {
        Object verify = testCase.get("verify");
        if (verify instanceof Map<?, ?> verifyMap && verifyMap.get("checks") instanceof List<?>) {
            return mapList(verifyMap.get("checks"));
        }
        return mapList(verify);
    }

    private Map<String, Object> dataOracles(Map<?, ?> data) {
        Map<String, Object> oracles = new LinkedHashMap<>();
        for (NamedSection entry : namedSections(data)) {
            if (!entry.name().toLowerCase(java.util.Locale.ROOT).contains("expected")) {
                continue;
            }
            String ref = firstText(entry.value(), "ref");
            if (ref.isBlank()) {
                continue;
            }
            Map<String, Object> oracle = new LinkedHashMap<>();
            oracle.put("type", "expected_result_artifact");
            oracle.put("ref", ref);
            oracles.put(entry.name(), oracle);
        }
        return oracles;
    }

    private Map<String, Object> verifyOracles(List<Map<?, ?>> verify, Map<?, ?> data) {
        Map<String, Object> oracles = new LinkedHashMap<>();
        int index = 1;
        for (Map<?, ?> verifyItem : verify) {
            String expectedRef = expectedArtifactReference(verifyItem, data);
            if (!expectedRef.isBlank()) {
                Map<String, Object> oracle = new LinkedHashMap<>();
                oracle.put("type", oracleTypeForAssertion(assertionType(firstText(verifyItem, "type", "assertion"))));
                oracle.put("ref", expectedRef);
                oracles.putIfAbsent(verifyOracleName(verifyItem, index), oracle);
            }
            index++;
        }
        return oracles;
    }

    private String firstExpectedDataRef(Map<?, ?> data) {
        for (NamedSection entry : namedSections(data)) {
            if (!entry.name().toLowerCase(java.util.Locale.ROOT).contains("expected")) {
                continue;
            }
            String ref = firstText(entry.value(), "ref");
            if (!ref.isBlank()) {
                return ref;
            }
        }
        return "";
    }

    private String sourceReference(Object value) {
        if (value instanceof Map<?, ?> map) {
            return firstText(map, "ref");
        }
        return stringValue(value);
    }

    private String expectedReference(Map<?, ?> verifyItem) {
        String expectedRef = sourceReference(verifyItem.get("expected_ref"));
        if (!expectedRef.isBlank()) {
            return expectedRef;
        }
        String expected = sourceReference(verifyItem.get("expected"));
        if (!expected.isBlank()) {
            return expected;
        }
        return sourceReference(verifyItem.get("oracle"));
    }

    private String expectedArtifactReference(Map<?, ?> verifyItem, Map<?, ?> data) {
        String expected = expectedReference(verifyItem);
        String dataRef = dataReference(expected, data);
        if (!dataRef.isBlank()) {
            return dataRef;
        }
        if (isArtifactReference(expected)) {
            return expected;
        }
        return "";
    }

    private String dataReference(String value, Map<?, ?> data) {
        String prefix = "${data.";
        if (!value.startsWith(prefix) || !value.endsWith("}")) {
            return "";
        }
        String name = value.substring(prefix.length(), value.length() - 1);
        int dot = name.indexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        Object entry = data.get(name);
        if (entry instanceof Map<?, ?> dataMap) {
            return firstText(dataMap, "ref");
        }
        return "";
    }

    private boolean isArtifactReference(String value) {
        if (value.isBlank() || value.startsWith("${")) {
            return false;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("/")
                || lower.endsWith(".json")
                || lower.endsWith(".yaml")
                || lower.endsWith(".yml")
                || lower.endsWith(".txt")
                || lower.endsWith(".csv")
                || lower.endsWith(".sql")
                || lower.endsWith(".xml");
    }

    private String verifyOracleName(Map<?, ?> verifyItem, int index) {
        String id = safeName(firstText(verifyItem, "id"));
        if (!id.isBlank()) {
            return id + "_expected";
        }
        return "expected_" + index;
    }

    private String safeName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private String bindingType(String type) {
        return switch (type) {
            case "database_seed", "db_fixture", "db_seed" -> "db_seed";
            case "file", "file_input", "input_file" -> "input_file";
            case "request_payload", "request_body", "api_payload" -> "api_payload";
            case "event", "message", "kafka_event", "nats_message", "message_event" -> "message_event";
            case "data_set", "dataset" -> "dataset";
            default -> type;
        };
    }

    private String oracleType(String type) {
        return switch (type) {
            case "expected_result", "expected_result_artifact" -> "expected_result_artifact";
            case "file", "golden", "golden_file" -> "golden_file";
            case "db_state", "query", "query_result" -> "query_result";
            case "schema", "contract" -> type;
            default -> type.isBlank() ? "expected_result_artifact" : type;
        };
    }

    private String oracleTypeForAssertion(String assertionType) {
        return switch (assertionType) {
            case "schema_matches" -> "schema";
            case "contract_matches" -> "contract";
            case "db_row_matches" -> "query_result";
            default -> "expected_result_artifact";
        };
    }

    private String assertionType(String type) {
        return switch (type) {
            case "file_equals", "output_equals", "file_diff" -> "file_diff";
            case "schema_match" -> "schema_matches";
            case "contract_match" -> "contract_matches";
            default -> type;
        };
    }

    private String expectedResultName(String reference, Map<?, ?> expectedResults) {
        String prefix = "${expected_results.";
        if (reference.startsWith(prefix) && reference.endsWith("}")) {
            String inner = reference.substring(prefix.length(), reference.length() - 1);
            int dot = inner.indexOf('.');
            return dot < 0 ? inner : inner.substring(0, dot);
        }
        if (expectedResults.containsKey(reference)) {
            return reference;
        }
        return "";
    }

    private String defaultLifecycle(String bindingType) {
        if ("db_seed".equals(bindingType) || "message_event".equals(bindingType)) {
            return "state_mutating";
        }
        return bindingType.isBlank() ? "" : "read_only";
    }

    private String normalizedLifecycle(String lifecycle, String bindingType) {
        String resolved = firstNonBlank(lifecycle, defaultLifecycle(bindingType));
        if ("state_mutating".equals(resolved) || "mutating".equals(resolved)) {
            return "mutates_state";
        }
        return resolved;
    }

    private String executionModeFromEnvironment(String environment) {
        if (environment.startsWith("ci://")) {
            return "ci_ephemeral";
        }
        if (environment.startsWith("sit://")) {
            return "sit_deployed";
        }
        if (environment.startsWith("local://")) {
            return "local_fixture";
        }
        return "";
    }

    private String acIdFromSourceRef(String sourceRef) {
        if (sourceRef.isBlank()) {
            return "";
        }
        int fragment = sourceRef.indexOf('#');
        if (fragment >= 0 && fragment + 1 < sourceRef.length()) {
            return sourceRef.substring(fragment + 1);
        }
        return sourceRef.contains("-AC-") ? sourceRef : "";
    }

    private void putIfMissing(Map<String, Object> map, String key, String value) {
        if (!map.containsKey(key)) {
            putIfNotBlank(map, key, value);
        }
    }

    private void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (!value.isBlank()) {
            map.put(key, value);
        }
    }

    private void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String... fields) {
        for (String field : fields) {
            Object value = source.get(field);
            if (value != null && !stringValue(value).isBlank()) {
                target.put(field, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
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

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record NamedSection(String name, Map<?, ?> value) {
    }
}
