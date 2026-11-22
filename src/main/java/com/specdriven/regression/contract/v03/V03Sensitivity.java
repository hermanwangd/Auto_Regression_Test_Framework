package com.specdriven.regression.contract.v03;

public enum V03Sensitivity {
    PUBLIC,
    MASKED,
    SECRET;

    static V03Sensitivity parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_sensitivity: v0.3 typed metadata must declare sensitivity.");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid_sensitivity: `" + value + "`.");
        }
    }
}
