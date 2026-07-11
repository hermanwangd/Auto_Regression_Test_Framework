package com.specdriven.regression.contract.v03.assertion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Performs fail-closed shape validation for v0.3 verify steps. */
public final class V03AssertionValidator {

    public record Issue(String fieldSuffix, String code, String remediation) {
    }

    public List<Issue> validate(Map<String, Object> verify) {
        List<Issue> issues = new ArrayList<>();
        String type = stringValue(verify.get("type"));
        if (type.isBlank()) {
            issues.add(new Issue(".type", "missing_verify_type",
                    "Declare `type: assertion` or `type: provider_check`."));
            return List.copyOf(issues);
        }
        if (!Set.of("assertion", "provider_check").contains(type)) {
            issues.add(new Issue(".type", "unsupported_verify_type",
                    "Use `assertion` or `provider_check` for DSL v0.3 verify steps."));
            return List.copyOf(issues);
        }
        if ("provider_check".equals(type)) {
            if (verify.containsKey("expect")) {
                issues.add(new Issue(".expect", "prohibited_provider_check_expect",
                        "Put provider-specific expected input in contract-declared `with` fields and use a separate assertion for generic comparison."));
            }
            return List.copyOf(issues);
        }

        Object rawAssert = verify.get("assert");
        if (!(rawAssert instanceof Map<?, ?>)) {
            issues.add(new Issue(".assert", "missing_assert_definition",
                    "Add an `assert` map with operator and required operands."));
            return List.copyOf(issues);
        }
        Map<String, Object> assertion = mapValue(rawAssert);
        String operatorText = stringValue(assertion.get("operator"));
        if (operatorText.isBlank()) {
            issues.add(new Issue(".assert.operator", "missing_assertion_operator",
                    "Declare one supported v0.3 assertion operator."));
            return List.copyOf(issues);
        }

        V03AssertionKind kind;
        try {
            kind = V03AssertionKind.require(operatorText);
        } catch (IllegalArgumentException error) {
            issues.add(new Issue(".assert.operator", "unsupported_assertion_operator",
                    "Use one of: equals, not_equals, gt, gte, lt, lte, matches, exists, not_exists, json_match, schema_match, file_diff."));
            return List.copyOf(issues);
        }

        boolean hasActual = hasValue(assertion, "actual");
        boolean hasActualRef = hasValue(assertion, "actual_ref");
        boolean hasExpected = hasValue(assertion, "expected");
        boolean hasExpectedRef = hasValue(assertion, "expected_ref");
        boolean hasSchemaRef = hasValue(assertion, "schema_ref");

        if (hasActual && hasActualRef) {
            issues.add(new Issue(".assert", "conflicting_assertion_actual",
                    "Declare exactly one of `actual` or `actual_ref`."));
        }
        if (hasExpected && hasExpectedRef) {
            issues.add(new Issue(".assert", "conflicting_assertion_expected",
                    "Declare exactly one of `expected` or `expected_ref`."));
        }

        switch (kind) {
            case EQUALS, NOT_EQUALS, GT, GTE, LT, LTE, MATCHES -> {
                requireOne(issues, hasActual, hasActualRef, ".assert.actual",
                        "missing_assertion_actual", "Declare `actual` or `actual_ref`.");
                requireOne(issues, hasExpected, hasExpectedRef, ".assert.expected",
                        "missing_assertion_expected", "Declare `expected` or `expected_ref`.");
            }
            case EXISTS, NOT_EXISTS -> requireOne(issues, hasActual, hasActualRef, ".assert.actual",
                    "missing_assertion_actual", "Declare `actual` or `actual_ref`.");
            case JSON_MATCH -> {
                requireOne(issues, hasActual, hasActualRef, ".assert.actual",
                        "missing_assertion_actual", "Declare `actual` or `actual_ref`.");
                if (!hasExpectedRef) {
                    issues.add(new Issue(".assert.expected_ref", "missing_assertion_expected_ref",
                            "`json_match` requires `expected_ref`."));
                }
            }
            case SCHEMA_MATCH -> {
                requireOne(issues, hasActual, hasActualRef, ".assert.actual",
                        "missing_assertion_actual", "Declare `actual` or `actual_ref`.");
                if (!hasSchemaRef) {
                    issues.add(new Issue(".assert.schema_ref", "missing_assertion_schema_ref",
                            "`schema_match` requires `schema_ref`."));
                }
            }
            case FILE_DIFF -> {
                if (!hasActualRef) {
                    issues.add(new Issue(".assert.actual_ref", "missing_assertion_actual_ref",
                            "`file_diff` requires `actual_ref`."));
                }
                if (!hasExpectedRef) {
                    issues.add(new Issue(".assert.expected_ref", "missing_assertion_expected_ref",
                            "`file_diff` requires `expected_ref`."));
                }
            }
        }
        return List.copyOf(issues);
    }

    private void requireOne(
            List<Issue> issues,
            boolean hasLiteral,
            boolean hasRef,
            String field,
            String code,
            String remediation) {
        if (!hasLiteral && !hasRef) {
            issues.add(new Issue(field, code, remediation));
        }
    }

    private boolean hasValue(Map<String, Object> values, String key) {
        if (!values.containsKey(key)) {
            return false;
        }
        Object value = values.get(key);
        return !(value == null || value instanceof String text && text.isBlank());
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalized;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
