package com.specdriven.regression.contract.v03.ref;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Evaluates v0.3 reference fragments against the actual JSON container at each token. */
public final class V03JsonPointer {
    private static final int MAX_DEPTH = 64;
    private static final Pattern ARRAY_TOKEN = Pattern.compile("-|0|[1-9][0-9]*");

    private V03JsonPointer() {}

    public static Object resolve(Object value, String pointer) {
        if (pointer == null || pointer.isEmpty()) return value;
        String[] tokens = pointer.substring(1).split("/", -1);
        if (tokens.length > MAX_DEPTH) {
            throw new IllegalArgumentException("json_pointer_too_deep: JSON Pointer exceeds " + MAX_DEPTH + " segments.");
        }
        Object current = value;
        for (String rawToken : tokens) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) throw new Missing("JSON Pointer segment `" + token + "` does not exist.");
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                requireArrayToken(token);
                if ("-".equals(token)) throw missingIndex(token);
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException outOfIntRange) {
                    throw missingIndex(token);
                }
                if (index >= list.size()) throw missingIndex(token);
                current = list.get(index);
            } else {
                throw new Missing("JSON Pointer cannot descend through a scalar value.");
            }
        }
        return current;
    }

    /** A provider can declare an array output without declaring its elements' types. */
    public static void validateArrayRoot(String pointer) {
        if (pointer.isEmpty()) return;
        int end = pointer.indexOf('/', 1);
        requireArrayToken(pointer.substring(1, end < 0 ? pointer.length() : end));
    }

    private static void requireArrayToken(String token) {
        if (!ARRAY_TOKEN.matcher(token).matches()) throw new InvalidArrayIndex(token);
    }

    private static Missing missingIndex(String token) {
        return new Missing("JSON Pointer array index `" + token + "` is out of range.");
    }

    public static final class InvalidArrayIndex extends IllegalArgumentException {
        private InvalidArrayIndex(String token) {
            super("invalid_json_pointer: array token `" + token + "` must be a canonical unsigned ASCII index or `-`.");
        }
    }

    public static final class Missing extends IllegalArgumentException {
        private Missing(String message) {
            super("json_pointer_missing: " + message);
        }
    }
}
