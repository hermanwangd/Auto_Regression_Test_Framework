package com.specdriven.regression.contract.v03.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V03JsonMatcherTest {
    @Test
    void unorderedArraysCompareEveryMemberRecursivelyAndCountDuplicatesByType() {
        var unordered = new V03JsonMatcher(true, List.of());
        var ordered = new V03JsonMatcher(false, List.of());
        var first = Map.of("nested", Arrays.asList(1, "1", 1, null, List.of("true", true)));
        var permuted = Map.of("nested", Arrays.asList(List.of(true, "true"), "1", null, 1.0, 1L));
        var different = Map.of("nested", Arrays.asList(List.of(true, "true"), "1", null, "1", 1));

        assertThat(unordered.valueId(first)).isEqualTo(unordered.valueId(permuted));
        assertThat(unordered.valueId(first)).isNotEqualTo(unordered.valueId(different));
        assertThat(ordered.valueId(first)).isNotEqualTo(ordered.valueId(permuted));
        assertThat(unordered.valueId(List.of())).isNotEqualTo(unordered.valueId(Map.of()));
        assertThat(unordered.valueId(null)).isNotEqualTo(unordered.valueId("null"));
    }

    @Test
    void numericComparisonUsesMathematicalValueAcrossScalesAndIntegerTypes() {
        var matcher = new V03JsonMatcher(false, List.of());
        var exact = new BigDecimal("9007199254740993123.1234567890123456789");
        assertThat(matcher.valueId(exact)).isEqualTo(matcher.valueId(exact.setScale(24)));
        assertThat(matcher.valueId(exact)).isNotEqualTo(matcher.valueId(exact.add(exact.ulp())));
        assertThat(matcher.valueId(0L)).isEqualTo(matcher.valueId(new BigDecimal("-0.000")));
    }

    @Test
    void ignorePathsAffectOnlyTheSelectedObjectProperties() {
        var matcher = new V03JsonMatcher(false, List.of("meta.stamp", "absent", "rows.stamp"));
        var left = Map.of("meta", Map.of("stamp", 1, "id", "stable"), "rows", List.of(Map.of("stamp", 1)));
        var right = Map.of("rows", left.get("rows"), "meta", Map.of("id", "stable"));
        var changedRow = Map.of("meta", left.get("meta"), "rows", List.of(Map.of("stamp", 2)));

        assertThat(matcher.valueId(left)).isEqualTo(matcher.valueId(right));
        assertThat(matcher.valueId(left)).isNotEqualTo(matcher.valueId(changedRow));
        assertThat(matcher.valueId(Map.of("meta.stamp", 1))).isNotEqualTo(matcher.valueId(Map.of("meta.stamp", 2)));
    }

    @Test
    void rejectsNonJsonValuesAndUsesTheTypedAssertionWorkLimit() {
        var matcher = new V03JsonMatcher(true, List.of());
        assertThatThrownBy(() -> matcher.valueId(new byte[]{1, 2})).hasMessageContaining("expected a JSON value");
        assertThatThrownBy(() -> matcher.valueId(Map.of(1, "a"))).hasMessageContaining("string property names");
        assertThatThrownBy(() -> matcher.valueId(Collections.nCopies(100_001, 1))).hasMessageContaining("work limit");
    }
}
