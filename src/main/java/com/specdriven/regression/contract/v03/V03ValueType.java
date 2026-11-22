package com.specdriven.regression.contract.v03;

public enum V03ValueType {
    ANY,
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY;

    static V03ValueType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_value_type: v0.3 typed metadata must declare value_type.");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid_value_type: `" + value + "`.");
        }
    }
}
