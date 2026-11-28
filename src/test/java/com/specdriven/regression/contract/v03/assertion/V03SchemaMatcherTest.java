package com.specdriven.regression.contract.v03.assertion;

import static com.specdriven.regression.contract.v03.assertion.V03SchemaMatcher.mismatch;
import static com.specdriven.regression.contract.v03.assertion.V03SchemaMatcher.validate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V03SchemaMatcherTest {
    @Test
    void checksNestedRequiredPropertiesAndEveryArrayElementWithEscapedPointers() {
        var customer = Map.of("type", "object", "required", List.of("a/b~"), "properties",
                Map.of("a/b~", Map.of("type", List.of("integer", "null"))));
        var schema = Map.of("properties", Map.of("orders", Map.of("type", "array", "items", customer)));
        var first = Collections.singletonMap("a/b~", null);

        assertThat(mismatch(schema, Map.of("orders", List.of(first, Map.of("a/b~", 22.0))))).isEmpty();
        assertThat(mismatch(schema, Map.of("orders", List.of(first, Map.of()))))
                .contains("/orders/1/a~1b~0", "required property missing");
        assertThat(mismatch(schema, Map.of("orders", List.of(first, Map.of("a/b~", "private-customer-22")))))
                .contains("/orders/1/a~1b~0", "type mismatch").doesNotContain("private-customer");
    }

    @Test
    void recognizesExactlyTheSevenTypesIncludingArbitraryPrecisionIntegers() {
        assertThat(mismatch(Map.of("type", "null"), null)).isEmpty();
        assertThat(mismatch(Map.of("type", "boolean"), false)).isEmpty();
        assertThat(mismatch(Map.of("type", "object"), Map.of())).isEmpty();
        assertThat(mismatch(Map.of("type", "array"), List.of())).isEmpty();
        assertThat(mismatch(Map.of("type", "string"), "")).isEmpty();
        assertThat(mismatch(Map.of("type", "number"), new BigDecimal("0.123"))).isEmpty();
        assertThat(mismatch(Map.of("type", "integer"), new BigInteger("900719925474099300000000000"))).isEmpty();
        assertThat(mismatch(Map.of("type", "integer"), new BigDecimal("9007199254740993.0000000001")))
                .contains("<root>", "type mismatch");
        assertThat(mismatch(Map.of("type", List.of("integer", "null")), "1")).contains("type mismatch");
        assertThat(mismatch(Map.of("type", "number"), Double.NaN)).contains("finite");
    }

    @Test
    void comparesEnumValuesUsingOrderedArraysTypedScalarsAndNumericEquivalence() {
        var schema = Map.of("enum", Arrays.asList(null, List.of(Map.of("id", 1), false), "ok"));
        assertThat(mismatch(schema, null)).isEmpty();
        assertThat(mismatch(schema, "ok")).isEmpty();
        assertThat(mismatch(schema, List.of(Map.of("id", new BigDecimal("1.00")), false))).isEmpty();
        assertThat(mismatch(schema, List.of(false, Map.of("id", 1)))).contains("<root>", "enum mismatch");
        assertThat(mismatch(schema, List.of(Map.of("id", "1"), false))).contains("enum mismatch");
        assertThat(mismatch(schema, "private-actual")).contains("enum mismatch").doesNotContain("private-actual", "ok");
    }

    @Test
    void recognizesEmptySchemasOptionalPropertiesAndKeywordApplicability() {
        assertThat(mismatch(Map.of(), "any")).isEmpty();
        assertThat(mismatch(Map.of("properties", Map.of("order", Map.of("type", "string"))), Map.of("extra", 2)))
                .isEmpty();
        assertThat(mismatch(Map.of("required", List.of("x"), "items", Map.of("type", "object")), 1)).isEmpty();
        assertThat(mismatch(Map.of("properties", Map.of("", Map.of("type", "boolean"))), Map.of("", "secret")))
                .contains("instance at /: type mismatch");
    }

    @Test
    void rejectsUnknownKeywordsEvenWhenTheirInstancePropertyIsAbsent() {
        var schema = Map.of("properties", Map.of("x~/", Map.of("$ref", "https://private.invalid/secret")));
        assertThatThrownBy(() -> mismatch(schema, Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema at /properties/x~0~1/$ref").hasMessageNotContaining("private.invalid");
        for (String keyword : List.of("additionalProperties", "anyOf", "pattern", "minimum")) {
            assertThatThrownBy(() -> validate(Map.of("items", Map.of(keyword, true))))
                    .hasMessageContaining("/items/" + keyword).hasMessageContaining("unsupported");
        }
    }

    @Test
    void identifiesMalformedKeywordArgumentsWithoutRenderingTheirValues() {
        assertInvalid(false, "<root>");
        assertInvalid(Map.of("type", List.of()), "/type");
        assertInvalid(Map.of("type", "private-unsupported"), "/type");
        assertInvalid(Map.of("type", List.of("integer", "integer")), "/type/1");
        assertInvalid(Map.of("required", "private-string"), "/required");
        assertInvalid(Map.of("required", List.of("x", 1)), "/required/1");
        assertInvalid(Map.of("required", List.of("x", "x")), "/required/1");
        assertInvalid(Map.of("properties", List.of()), "/properties");
        assertInvalid(Map.of("properties", Map.of("a/b", "private-value")), "/properties/a~1b");
        assertInvalid(Map.of("items", List.of(Map.of())), "/items");
        assertInvalid(Map.of("enum", "private-choice"), "/enum");
        assertInvalid(Map.of("enum", List.of()), "/enum");
        assertInvalid(Map.of("enum", List.of(Map.of(42, 10))), "/enum/0");
        assertInvalid(Map.of("enum", List.of(Double.POSITIVE_INFINITY)), "/enum/0");
    }

    @Test
    void stopsCyclesExcessiveWorkAndOversizedDocumentPaths() {
        var cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThat(mismatch(Map.of(), cycle)).contains("instance at /0", "nesting limit");
        assertThatThrownBy(() -> validate(Map.of("enum", cycle))).hasMessageContaining("nesting limit");
        assertThat(mismatch(Map.of(), Collections.nCopies(105_000, 1))).contains("work limit");
        assertThat(mismatch(Map.of(), Map.of("x".repeat(3000), false))).contains("<root>", "Pointer length");
        var choices = Collections.nCopies(1_000, 1);
        assertThat(mismatch(Map.of("items", Map.of("enum", choices)), Collections.nCopies(150, 1)))
                .contains("instance at /", "work limit");
    }

    @Test
    void escapesControlCharactersInDiagnostics() {
        var schema = Map.of("properties", Map.of("a\nz", Map.of("type", "object")));
        assertThat(mismatch(schema, Map.of("a\nz", false))).contains("/a\\nz").doesNotContain("\n");
    }

    private static void assertInvalid(Object schema, String path) {
        assertThatThrownBy(() -> validate(schema)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema at " + path).hasMessageNotContaining("private-");
    }
}
