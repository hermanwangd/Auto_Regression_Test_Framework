package com.specdriven.regression.contract.v03.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class V03AssertionKindTest {

    @Test
    void acceptsTheVersionedV03AssertionCatalog() {
        assertThat(V03AssertionKind.require("json_match")).isEqualTo(V03AssertionKind.JSON_MATCH);
        assertThat(V03AssertionKind.require("equals")).isEqualTo(V03AssertionKind.EQUALS);
    }

    @Test
    void rejectsUnknownAssertionKind() {
        assertThatThrownBy(() -> V03AssertionKind.require("business_oracle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported_assertion_kind");
    }
}
