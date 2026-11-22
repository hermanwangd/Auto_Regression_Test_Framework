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

}
