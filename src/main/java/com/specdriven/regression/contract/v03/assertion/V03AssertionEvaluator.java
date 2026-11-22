package com.specdriven.regression.contract.v03.assertion;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates scalar v0.3 assertions. Structured artifact assertions remain runtime handlers. */
public final class V03AssertionEvaluator {

    public V03AssertionEvaluation evaluate(V03AssertionKind kind, Object actual, Object expected) {
        Objects.requireNonNull(kind, "Assertion kind is required.");
        boolean passed = switch (kind) {
            case EQUALS -> equalsValue(actual, expected);
            case NOT_EQUALS -> !equalsValue(actual, expected);
            case GT -> compareNumbers(actual, expected) > 0;
            case GTE -> compareNumbers(actual, expected) >= 0;
            case LT -> compareNumbers(actual, expected) < 0;
            case LTE -> compareNumbers(actual, expected) <= 0;
            case MATCHES -> matches(actual, expected);
            case EXISTS -> exists(actual);
            case NOT_EXISTS -> !exists(actual);
            case JSON_MATCH, SCHEMA_MATCH, FILE_DIFF -> throw new IllegalArgumentException(
                    "structured_assertion_requires_runtime_handler: `" + kind.dslValue() + "`.");
        };
        return new V03AssertionEvaluation(passed, actual, expected);
    }

    private boolean exists(Object actual) {
        return actual != null && actual != V03MissingValue.INSTANCE;
    }

    private boolean equalsValue(Object actual, Object expected) {
        requirePresent(actual, "actual");
        requirePresent(expected, "expected");
        if (actual instanceof Number || expected instanceof Number) {
            try {
                return decimal(actual, "actual").compareTo(decimal(expected, "expected")) == 0;
            } catch (IllegalArgumentException ignored) {
                // Preserve compatibility for non-numeric provider values while typed contracts are introduced.
            }
        }
        return Objects.equals(actual, expected)
                || String.valueOf(actual).equals(String.valueOf(expected));
    }

    private int compareNumbers(Object actual, Object expected) {
        requirePresent(actual, "actual");
        requirePresent(expected, "expected");
        return decimal(actual, "actual").compareTo(decimal(expected, "expected"));
    }

    private boolean matches(Object actual, Object expected) {
        requirePresent(actual, "actual");
        requirePresent(expected, "expected");
        try {
            return Pattern.compile(String.valueOf(expected)).matcher(String.valueOf(actual)).matches();
        } catch (PatternSyntaxException error) {
            throw new IllegalArgumentException("invalid_assertion_regex: " + error.getMessage(), error);
        }
    }

    private BigDecimal decimal(Object value, String operand) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "invalid_numeric_assertion_operand: `" + operand + "` must be numeric, but was `" + value + "`.",
                    error);
        }
    }

    private void requirePresent(Object value, String operand) {
        if (value == V03MissingValue.INSTANCE) {
            throw new IllegalArgumentException(
                    "missing_assertion_operand: `" + operand + "` reference did not resolve.");
        }
    }
}
