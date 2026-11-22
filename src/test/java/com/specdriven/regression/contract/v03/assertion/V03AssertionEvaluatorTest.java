package com.specdriven.regression.contract.v03.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class V03AssertionEvaluatorTest {

    private final V03AssertionEvaluator evaluator = new V03AssertionEvaluator();

    @Test
    void evaluatesEveryScalarOperatorWithoutEqualityFallback() {
        assertThat(evaluator.evaluate(V03AssertionKind.EQUALS, 10, 10).passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.NOT_EQUALS, "A", "B").passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.GT, 10, 2).passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.GTE, 10, 10).passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.LT, 2, 10).passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.LTE, 10, 10).passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.MATCHES, "ORD-100", "ORD-[0-9]+").passed()).isTrue();
    }

    @Test
    void distinguishesMissingFromBlankForExistsOperators() {
        assertThat(evaluator.evaluate(V03AssertionKind.EXISTS, "", null).passed()).isTrue();
        assertThat(evaluator.evaluate(V03AssertionKind.EXISTS, V03MissingValue.INSTANCE, null).passed()).isFalse();
        assertThat(evaluator.evaluate(V03AssertionKind.NOT_EXISTS, V03MissingValue.INSTANCE, null).passed()).isTrue();
    }

    @Test
    void rejectsInvalidNumericAndRegexOperands() {
        assertThatThrownBy(() -> evaluator.evaluate(V03AssertionKind.GTE, "not-a-number", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_numeric_assertion_operand");
        assertThatThrownBy(() -> evaluator.evaluate(V03AssertionKind.MATCHES, "value", "["))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid_assertion_regex");
    }
}
