package com.specdriven.regression.contract.v03.assertion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Evaluates scalar v0.3 assertions. Structured artifact assertions remain runtime handlers. */
public final class V03AssertionEvaluator {

    public V03AssertionEvaluation evaluate(V03AssertionKind kind, Object actual, Object expected) {
        return evaluate(kind, actual, expected, false);
    }

    public V03AssertionEvaluation evaluate(V03AssertionKind kind, Object actual, Object expected, boolean strict) {
        Objects.requireNonNull(kind, "Assertion kind is required.");
        if (strict) {
            if (kind != V03AssertionKind.EQUALS && kind != V03AssertionKind.NOT_EQUALS) {
                throw new IllegalArgumentException("unsupported_assertion_strict: `strict` requires `equals` or `not_equals`.");
            }
            boolean equal = strictEquals(actual, expected);
            return new V03AssertionEvaluation(kind == V03AssertionKind.EQUALS ? equal : !equal, actual, expected);
        }
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

    /** Compares JSON values recursively without coercion between JSON types. */
    public static boolean strictEquals(Object actual, Object expected) {
        requirePresent(actual, "actual");
        requirePresent(expected, "expected");
        V03SchemaMatcher.validateJsonValue(actual);
        V03SchemaMatcher.validateJsonValue(expected);
        return equalJsonValues(actual, expected);
    }

    /** Both inputs have passed JSON type, depth and size validation. */
    static boolean equalJsonValues(Object actual, Object expected) {
        if (actual instanceof Number left && expected instanceof Number right) {
            return jsonNumber(left, "actual").compareTo(jsonNumber(right, "expected")) == 0;
        }
        if (actual instanceof Map<?, ?> left && expected instanceof Map<?, ?> right) {
            if (!left.keySet().equals(right.keySet())) {
                return false;
            }
            for (var entry : left.entrySet()) {
                if (!equalJsonValues(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (actual instanceof List<?> left && expected instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            var expectedElements = right.iterator();
            for (Object element : left) {
                if (!equalJsonValues(element, expectedElements.next())) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(actual, expected);
    }

    static BigDecimal jsonNumber(Number value, String operand) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        return decimal(value, operand);
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

    private static BigDecimal decimal(Object value, String operand) {
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "invalid_numeric_assertion_operand: `" + operand + "` must be numeric, but was `" + value + "`.",
                    error);
        }
    }

    private static void requirePresent(Object value, String operand) {
        if (value == V03MissingValue.INSTANCE) {
            throw new IllegalArgumentException(
                    "missing_assertion_operand: `" + operand + "` reference did not resolve.");
        }
    }
}
