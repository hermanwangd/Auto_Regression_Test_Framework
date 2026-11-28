package com.specdriven.regression.contract.v03.assertion;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The v0.3 local JSON Schema subset. Objects and arrays are represented by maps and lists. */
public final class V03SchemaMatcher {
    private static final Set<String> TYPES =
            Set.of("null", "boolean", "object", "array", "number", "integer", "string");
    private static final int MAX_DEPTH = 64;
    private static final int MAX_POINTER_LENGTH = 2_048;
    private static final int MAX_WORK = 100_000;

    private V03SchemaMatcher() {}

    public static void validate(Object schema) {
        prepare(schema);
    }

    static void validateJsonValue(Object value) {
        new Budget("value").inspect(value, "", 0, null);
    }

    /** Invalid schemas throw. Invalid instances and mismatches return a value-free diagnostic. */
    public static String mismatch(Object schema, Object actual) {
        Map<List<?>, Integer> costs = prepare(schema);
        Budget instance = new Budget("instance");
        try {
            instance.inspect(actual, "", 0, null);
            match((Map<?, ?>) schema, actual, "", instance, costs);
            return "";
        } catch (SchemaProblem problem) {
            return problem.getMessage();
        }
    }

    private static Map<List<?>, Integer> prepare(Object schema) {
        var costs = new IdentityHashMap<List<?>, Integer>();
        Budget budget = new Budget("schema");
        budget.inspect(schema, "", 0, costs);
        checkSchema(schema, "", budget);
        return costs;
    }

    private static void checkSchema(Object value, String path, Budget budget) {
        if (!(value instanceof Map<?, ?> schema)) {
            throw budget.problem(path, "expected a schema object");
        }
        for (var entry : schema.entrySet()) {
            String keyword = (String) entry.getKey();
            String at = budget.child(path, keyword);
            Object argument = entry.getValue();
            switch (keyword) {
                case "type" -> {
                    if (argument instanceof List<?> list) {
                        if (list.isEmpty()) {
                            throw budget.problem(at, "expected at least one JSON type");
                        }
                        checkStrings(list, at, budget, true);
                    } else {
                        checkType(argument, at, budget);
                    }
                }
                case "required" -> {
                    if (!(argument instanceof List<?> list)) {
                        throw budget.problem(at, "expected an array of property names");
                    }
                    checkStrings(list, at, budget, false);
                }
                case "properties" -> {
                    if (!(argument instanceof Map<?, ?> properties)) {
                        throw budget.problem(at, "expected an object of property schemas");
                    }
                    for (var property : properties.entrySet()) {
                        checkSchema(property.getValue(), budget.child(at, (String) property.getKey()), budget);
                    }
                }
                case "items" -> checkSchema(argument, at, budget);
                case "enum" -> {
                    if (!(argument instanceof List<?>)) {
                        throw budget.problem(at, "expected an array of JSON values");
                    }
                }
                default -> throw budget.problem(at, "unsupported schema keyword");
            }
        }
    }

    private static void checkStrings(List<?> list, String path, Budget budget, boolean types) {
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (Object member : list) {
            String at = budget.child(path, Integer.toString(index++));
            if (types) checkType(member, at, budget);
            if (!(member instanceof String name)) throw budget.problem(at, "expected a property name");
            if (!seen.add(name)) throw budget.problem(at, "duplicate entry");
        }
    }

    private static void checkType(Object value, String path, Budget budget) {
        if (!(value instanceof String type) || !TYPES.contains(type)) {
            throw budget.problem(path, "unknown JSON type");
        }
    }

    private static void match(Map<?, ?> schema, Object value, String path, Budget budget, Map<List<?>, Integer> costs) {
        budget.spend(1, path);
        Object type = schema.get("type");
        if (type != null) {
            List<?> types = type instanceof List<?> list ? list : List.of(type);
            if (types.stream().noneMatch(t -> hasType((String) t, value))) {
                throw budget.problem(path, "type mismatch (expected " + type + ")");
            }
        }
        if (schema.get("enum") instanceof List<?> choices) {
            // Charge the size of the candidates before recursive JSON equality, including repeated item matches.
            budget.spend(costs.get(choices), path);
            if (choices.stream().noneMatch(choice -> V03AssertionEvaluator.equalJsonValues(choice, value))) {
                throw budget.problem(path, "enum mismatch");
            }
        }
        if (value instanceof Map<?, ?> object) {
            if (schema.get("required") instanceof List<?> names) {
                budget.spend(names.size(), path);
                for (Object name : names) {
                    if (!object.containsKey(name)) {
                        throw budget.problem(budget.child(path, (String) name), "required property missing");
                    }
                }
            }
            if (schema.get("properties") instanceof Map<?, ?> properties) {
                budget.spend(properties.size(), path);
                for (var property : properties.entrySet()) {
                    String name = (String) property.getKey();
                    if (object.containsKey(name)) {
                        match((Map<?, ?>) property.getValue(), object.get(name), budget.child(path, name), budget, costs);
                    }
                }
            }
        }
        if (value instanceof List<?> array && schema.get("items") instanceof Map<?, ?> items) {
            int index = 0;
            for (Object element : array) {
                match(items, element, budget.child(path, Integer.toString(index++)), budget, costs);
            }
        }
    }

    private static boolean hasType(String type, Object value) {
        return switch (type) {
            case "null" -> value == null;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && isInteger(n);
            default -> throw new IllegalStateException("Unvalidated schema type");
        };
    }

    private static boolean isInteger(Number value) {
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            BigDecimal decimal = value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
            return decimal.scale() <= 0 || decimal.stripTrailingZeros().scale() <= 0;
        }
        return true;
    }

    private static final class SchemaProblem extends IllegalArgumentException {
        SchemaProblem(String message) {
            super(message);
        }
    }

    private static final class Budget {
        private final String subject;
        private int remaining = MAX_WORK;

        Budget(String subject) {
            this.subject = subject;
        }

        SchemaProblem problem(String path, String reason) {
            String pointer = path.isEmpty() ? "<root>" : new String(JsonStringEncoder.getInstance().quoteAsString(path));
            return new SchemaProblem(subject + " at " + pointer + ": " + reason);
        }

        String child(String path, String token) {
            if (token.length() > MAX_POINTER_LENGTH) throw problem(path, "JSON Pointer length limit exceeded");
            String next = path + "/" + token.replace("~", "~0").replace("/", "~1");
            if (next.length() > MAX_POINTER_LENGTH) throw problem(path, "JSON Pointer length limit exceeded");
            return next;
        }

        void spend(int cost, String path) {
            if (cost > remaining) throw problem(path, "work limit exceeded");
            remaining -= cost;
        }

        void inspect(Object value, String path, int depth, Map<List<?>, Integer> listCosts) {
            int start = remaining;
            spend(1, path);
            if (depth > MAX_DEPTH) throw problem(path, "nesting limit exceeded");
            if (value instanceof List<?> array) {
                int index = 0;
                for (Object element : array) {
                    inspect(element, child(path, Integer.toString(index++)), depth + 1, listCosts);
                }
                if (listCosts != null) listCosts.put(array, start - remaining);
            } else if (value instanceof Map<?, ?> object) {
                for (var entry : object.entrySet()) {
                    if (!(entry.getKey() instanceof String name)) {
                        throw problem(path, "expected JSON string property names");
                    }
                    spend(1 + name.length() / 64, path);
                    inspect(entry.getValue(), child(path, name), depth + 1, listCosts);
                }
            } else if (value instanceof String text) {
                spend(text.length() / 64, path);
            } else if (value instanceof Number number) {
                inspectNumber(number, path);
            } else if (value != null && !(value instanceof Boolean)) {
                throw problem(path, "expected a JSON value");
            }
        }

        private void inspectNumber(Number number, String path) {
            if (number instanceof BigInteger || number instanceof BigDecimal) {
                BigInteger unscaled = number instanceof BigDecimal d ? d.unscaledValue() : (BigInteger) number;
                if (unscaled.bitLength() > 16_384) throw problem(path, "number size limit exceeded");
                spend(unscaled.bitLength() / 64, path);
            } else if (number instanceof Float || number instanceof Double) {
                if (!Double.isFinite(number.doubleValue())) throw problem(path, "expected a finite JSON number");
            } else if (!(number instanceof Byte || number instanceof Short
                    || number instanceof Integer || number instanceof Long)) {
                throw problem(path, "expected a JSON number");
            }
        }
    }
}
