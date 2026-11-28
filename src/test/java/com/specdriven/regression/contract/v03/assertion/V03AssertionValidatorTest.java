package com.specdriven.regression.contract.v03.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class V03AssertionValidatorTest {

    private final V03AssertionValidator validator = new V03AssertionValidator();

    @Test
    void rejectsMissingVerifyTypeAndAssertDefinition() {
        assertThat(validator.validate(Map.of("id", "verify")))
                .extracting(V03AssertionValidator.Issue::code)
                .containsExactly("missing_verify_type");

        assertThat(validator.validate(Map.of("type", "assertion")))
                .extracting(V03AssertionValidator.Issue::code)
                .containsExactly("missing_assert_definition");
    }

    @Test
    void rejectsUnknownOperatorAndMissingOperands() {
        assertThat(validator.validate(Map.of(
                "type", "assertion",
                "assert", Map.of("operator", "equlas", "actual", 1, "expected", 1))))
                .extracting(V03AssertionValidator.Issue::code)
                .containsExactly("unsupported_assertion_operator");

        assertThat(validator.validate(Map.of(
                "type", "assertion",
                "assert", Map.of("operator", "gte"))))
                .extracting(V03AssertionValidator.Issue::code)
                .containsExactlyInAnyOrder("missing_assertion_actual", "missing_assertion_expected");
    }

    @Test
    void acceptsACompleteNumericAssertion() {
        assertThat(validator.validate(Map.of(
                "type", "assertion",
                "assert", Map.of("operator", "gte", "actual", 10, "expected", 2))))
                .isEmpty();
    }
    @Test void emptyWhitespaceFalseAndZeroArePresentLiteralOperands() {
        for (Object value : java.util.List.of("", " \t\n", false, 0))
            assertThat(validator.validate(Map.of("type", "assertion", "assert",
                    Map.of("operator", "equals", "actual", value, "expected", value)))).isEmpty();
    }

    @Test void emptyLiteralCannotHideAConflictingReference() {
        for (String side : java.util.List.of("actual", "expected")) {
            var operands = new java.util.LinkedHashMap<String,Object>();
            operands.put("operator", "equals"); operands.put("actual", ""); operands.put("expected", "");
            operands.put(side + "_ref", "artifact://expected/value.txt");
            assertThat(validator.validate(Map.of("type", "assertion", "assert", operands)))
                    .extracting(V03AssertionValidator.Issue::code).contains("conflicting_assertion_" + side);
        }
    }

    @Test void nullMissingAndBlankReferencesRemainAbsent() {
        for (String side : java.util.List.of("actual", "expected")) {
            for (String variant : java.util.List.of("missing", "null", "blank_reference")) {
                var operands = new java.util.LinkedHashMap<String,Object>();
                operands.put("operator", "equals"); operands.put("actual", 1); operands.put("expected", 1);
                operands.remove(side);
                if (variant.equals("null")) operands.put(side, null);
                if (variant.equals("blank_reference")) operands.put(side + "_ref", " ");
                assertThat(validator.validate(Map.of("type", "assertion", "assert", operands)))
                        .extracting(V03AssertionValidator.Issue::code).contains("missing_assertion_" + side);
            }
        }
    }

    @Test
    void strictEqualityAcceptsExplicitNullButRequiresBothOperands() {
        for (String operator : java.util.List.of("equals", "not_equals")) {
            var assertion = new java.util.LinkedHashMap<String, Object>();
            assertion.put("operator", operator);
            assertion.put("strict", true);
            assertion.put("actual", null);
            assertion.put("expected", null);
            var verify = Map.<String, Object>of("type", "assertion", "assert", assertion);
            assertThat(validator.validate(verify)).isEmpty();

            for (String side : java.util.List.of("actual", "expected")) {
                for (String reference : java.util.Arrays.asList(null, " ", "artifact://expected/value.json")) {
                    assertion.put(side + "_ref", reference);
                    assertThat(validator.validate(verify)).extracting(V03AssertionValidator.Issue::code)
                            .containsExactly("conflicting_assertion_" + side);
                }
                assertion.remove(side + "_ref");
            }
            assertion.remove("expected");
            assertThat(validator.validate(verify)).extracting(V03AssertionValidator.Issue::code)
                    .containsExactly("missing_assertion_expected");

            assertion.put("strict", false);
            assertThat(validator.validate(verify)).extracting(V03AssertionValidator.Issue::code)
                    .containsExactlyInAnyOrder("missing_assertion_actual", "missing_assertion_expected");
        }
    }

    @Test
    void strictRequiresABooleanAndAnEqualityOperator() {
        var assertion = new java.util.LinkedHashMap<String, Object>(Map.of("operator", "equals", "actual", 1, "expected", 1));
        var verify = Map.<String, Object>of("type", "assertion", "assert", assertion);
        for (Object invalid : java.util.Arrays.asList(null, 1, "true")) {
            assertion.put("strict", invalid);
            assertThat(validator.validate(verify)).extracting(V03AssertionValidator.Issue::code)
                    .containsExactly("invalid_assertion_strict");
        }
        assertion.put("operator", "gt");
        for (boolean value : java.util.List.of(true, false)) {
            assertion.put("strict", value);
            assertThat(validator.validate(verify)).extracting(V03AssertionValidator.Issue::code)
                    .containsExactly("unsupported_assertion_strict");
        }
    }

    @Test
    void strictLiteralValidationRejectsYamlDatesAndNumericObjectKeysBeforeExecution() {
        var assertion = Map.of("operator", "not_equals", "strict", true,
                "actual", new java.util.Date(0), "expected", Map.of(2, false));
        assertThat(validator.validate(Map.of("type", "assertion", "assert", assertion)))
                .extracting(V03AssertionValidator.Issue::fieldSuffix, V03AssertionValidator.Issue::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(".assert.actual", "invalid_strict_assertion_operand"),
                        org.assertj.core.groups.Tuple.tuple(".assert.expected", "invalid_strict_assertion_operand"));
    }

}
