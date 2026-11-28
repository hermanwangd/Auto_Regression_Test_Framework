package com.specdriven.regression.contract.v03.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    void strictEqualityRejectsCrossTypeCoercionWhileTheDefaultStillAcceptsIt() {
        for (Object actual : List.of(1, true)) {
            String expected = actual.toString();
            assertThat(evaluator.evaluate(V03AssertionKind.EQUALS, actual, expected).passed()).isTrue();
            assertThat(evaluator.evaluate(V03AssertionKind.EQUALS, actual, expected, false).passed()).isTrue();
            assertThat(evaluator.evaluate(V03AssertionKind.EQUALS, actual, expected, true).passed()).isFalse();
            assertThat(evaluator.evaluate(V03AssertionKind.NOT_EQUALS, actual, expected, true).passed()).isTrue();
        }
        assertThat(V03AssertionEvaluator.strictEquals(null, null)).isTrue();
        assertThat(V03AssertionEvaluator.strictEquals(null, "null")).isFalse();
        assertThat(V03AssertionEvaluator.strictEquals(true, 1)).isFalse();
        assertThat(V03AssertionEvaluator.strictEquals("A", "a")).isFalse();
        assertThat(V03AssertionEvaluator.strictEquals(Map.of(), List.of())).isFalse();
    }

    @Test
    void strictEqualityComparesObjectsByKeysAndArraysInOrderAtEveryDepth() {
        var actual = new LinkedHashMap<String, Object>();
        actual.put("values", Arrays.asList(1, null, Map.of("fraction", new BigDecimal("0.0200"))));
        actual.put("flag", true);
        var expected = new LinkedHashMap<String, Object>();
        expected.put("flag", true);
        expected.put("values", Arrays.asList(1.0, null, Map.of("fraction", 0.02)));
        assertThat(evaluator.evaluate(V03AssertionKind.NOT_EQUALS, actual, expected, true).passed()).isFalse();

        expected.put("values", Arrays.asList(null, 1, Map.of("fraction", 0.02)));
        assertThat(V03AssertionEvaluator.strictEquals(actual, expected)).isFalse();
        expected.put("values", Arrays.asList(1, null, Map.of("fraction", "0.02")));
        assertThat(V03AssertionEvaluator.strictEquals(actual, expected)).isFalse();
        assertThat(V03AssertionEvaluator.strictEquals(List.of(1), List.of(1, 2))).isFalse();
        assertThat(V03AssertionEvaluator.strictEquals(actual, Map.of("values", actual.get("values")))).isFalse();
        assertThat(V03AssertionEvaluator.strictEquals(new BigDecimal("9007199254740993"), 9007199254740992L))
                .isFalse();
        var extreme = new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE);
        assertThat(V03AssertionEvaluator.strictEquals(extreme, extreme)).isTrue();
    }

    @Test
    void missingOperandsFailBothStrictOperators() {
        for (var kind : List.of(V03AssertionKind.EQUALS, V03AssertionKind.NOT_EQUALS)) {
            assertThatThrownBy(() -> evaluator.evaluate(kind, V03MissingValue.INSTANCE, null, true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing_assertion_operand");
            assertThatThrownBy(() -> evaluator.evaluate(kind, null, V03MissingValue.INSTANCE, true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing_assertion_operand");
        }
    }

    @Test
    void invalidJsonFailsBothStrictOperatorsEvenAfterAnEarlierMismatch() {
        for (Object value : List.of(new java.util.Date(0), Map.of(1, "x"), Double.NaN, Float.POSITIVE_INFINITY)) {
            for (var kind : List.of(V03AssertionKind.EQUALS, V03AssertionKind.NOT_EQUALS)) {
                assertThatThrownBy(() -> evaluator.evaluate(kind, List.of(1, value), List.of(2), true))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> evaluator.evaluate(kind, value, value, true))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        Object nested = null;
        for (int i = 0; i < 66; i++) {
            nested = java.util.Collections.singletonList(nested);
        }
        Object deep = nested;
        assertThatThrownBy(() -> V03AssertionEvaluator.strictEquals(null, deep))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nesting limit");
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
