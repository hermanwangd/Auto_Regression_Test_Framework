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
}
