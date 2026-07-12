package com.specdriven.regression.contract.v03;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Map;

/** Deterministic canonical serialization for v0.3 execution-plan audit identity. */
public final class V03PlanCanonicalizer {

    public String digest(Object value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalValue(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : bytes) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required to compile a v0.3 execution plan.", error);
        }
    }

    public String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> Map.entry(String.valueOf(entry.getKey()), canonicalValue(entry.getValue())))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> quote(entry.getKey()) + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof java.util.Set<?> set) {
            return set.stream().map(this::canonicalValue).sorted()
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayList<String> values = new ArrayList<>();
            iterable.forEach(item -> values.add(canonicalValue(item)));
            return String.join(",", values).replaceFirst("^", "[").concat("]");
        }
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return quote(String.valueOf(value));
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
