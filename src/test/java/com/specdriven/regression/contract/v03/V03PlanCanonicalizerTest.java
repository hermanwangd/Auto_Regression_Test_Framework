package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V03PlanCanonicalizerTest {

    @Test
    void givesTheSameDigestForMapAndSetPermutations() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("bindings", Map.of("b", 2, "a", 1));
        first.put("modes", Set.of("mock", "external"));
        first.put("steps", List.of("setup", "execute"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("steps", List.of("setup", "execute"));
        second.put("modes", Set.of("external", "mock"));
        second.put("bindings", Map.of("a", 1, "b", 2));

        assertThat(new V03PlanCanonicalizer().digest(first))
                .isEqualTo(new V03PlanCanonicalizer().digest(second));
    }

    @Test
    void changesDigestWhenAuthoredStepOrderChanges() {
        V03PlanCanonicalizer canonicalizer = new V03PlanCanonicalizer();

        assertThat(canonicalizer.digest(Map.of("steps", List.of("setup", "execute"))))
                .isNotEqualTo(canonicalizer.digest(Map.of("steps", List.of("execute", "setup"))));
    }
}
