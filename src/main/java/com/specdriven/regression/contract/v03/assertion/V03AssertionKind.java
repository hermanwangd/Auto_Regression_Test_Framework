package com.specdriven.regression.contract.v03.assertion;

import java.util.Arrays;

public enum V03AssertionKind {
    EQUALS("equals"),
    NOT_EQUALS("not_equals"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    MATCHES("matches"),
    EXISTS("exists"),
    NOT_EXISTS("not_exists"),
    JSON_MATCH("json_match"),
    SCHEMA_MATCH("schema_match"),
    FILE_DIFF("file_diff");

    private final String value;

    V03AssertionKind(String value) {
        this.value = value;
    }

    public String dslValue() {
        return value;
    }

    public static V03AssertionKind require(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported_assertion_kind: use a v0.3 assertion kind, not `" + value + "`."));
    }
}
