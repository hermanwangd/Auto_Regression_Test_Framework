package com.specdriven.regression.contract.v03;

public enum V03ReferenceKind {
    LITERAL,
    ARTIFACT,
    STEP,
    GENERATED,
    ENV;

    static V03ReferenceKind parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid_reference_kind: `" + value + "`.");
        }
    }
}
