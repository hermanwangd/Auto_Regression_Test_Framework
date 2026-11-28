package com.specdriven.regression.contract.v03.assertion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Assigns the same ID to structurally equal JSON values within one json_match assertion. */
public final class V03JsonMatcher {
    private final boolean ignoreOrder;
    private final IgnoredPath ignored = new IgnoredPath();
    private final Map<String, Integer> strings = new HashMap<>();
    private final Map<BigDecimal, Integer> numbers = new TreeMap<>();
    // Container keys contain child IDs. Comparing them never retraverses the child trees.
    private final Map<int[], Integer> arrays = new TreeMap<>(Arrays::compare);
    private final Map<int[], Integer> objects = new TreeMap<>(Arrays::compare);
    private int nextId = 3; // null, false and true have fixed IDs 0, 1 and 2.

    public V03JsonMatcher(boolean ignoreOrder, List<String> ignorePaths) {
        this.ignoreOrder = ignoreOrder;
        for (String path : ignorePaths) {
            String[] parts = path.split("\\.");
            if (parts.length == 0) continue;
            IgnoredPath at = ignored;
            for (String part : parts) {
                at = at.children.computeIfAbsent(part, unused -> new IgnoredPath());
            }
            at.omit = true;
        }
    }

    public int valueId(Object value) {
        V03SchemaMatcher.validateJsonValue(value);
        return valueId(value, ignored);
    }

    private int valueId(Object value, IgnoredPath path) {
        if (value == null) return 0;
        if (value instanceof Boolean flag) return flag ? 2 : 1;
        if (value instanceof String text) return stringId(text);
        if (value instanceof Number number) {
            // TreeMap uses BigDecimal.compareTo, so 1, 1.0, and 1e0 share one ID.
            return numbers.computeIfAbsent(V03AssertionEvaluator.jsonNumber(number, "json_match"),
                    unused -> nextId++);
        }
        if (value instanceof List<?> array) {
            int[] members = new int[array.size()];
            int index = 0;
            for (Object element : array) members[index++] = valueId(element, null);
            if (ignoreOrder) Arrays.sort(members);
            return arrays.computeIfAbsent(members, unused -> nextId++);
        }
        Map<?, ?> object = (Map<?, ?>) value;
        var names = new ArrayList<String>(object.size());
        for (Object key : object.keySet()) {
            String name = (String) key;
            IgnoredPath child = path == null ? null : path.children.get(name);
            if (child == null || !child.omit) names.add(name);
        }
        names.sort(String::compareTo);
        int[] fields = new int[2 * names.size()];
        int index = 0;
        for (String name : names) {
            fields[index++] = stringId(name);
            fields[index++] = valueId(object.get(name), path == null ? null : path.children.get(name));
        }
        return objects.computeIfAbsent(fields, unused -> nextId++);
    }

    private int stringId(String value) {
        return strings.computeIfAbsent(value, unused -> nextId++);
    }

    private static final class IgnoredPath {
        final Map<String, IgnoredPath> children = new HashMap<>();
        boolean omit;
    }
}
