package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes producer-before-consumer target order for v0.3 generated bindings. */
public final class V03GeneratedBindingDag {
    private final V03ReferenceParser parser = new V03ReferenceParser();

    public List<String> producerFirstOrder(Map<String, V03ResolvedTarget> targets) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, V03ResolvedTarget> entry : targets.entrySet()) {
            Set<String> producers = new LinkedHashSet<>();
            collectGeneratedProducers(entry.getValue().bindings(), producers);
            for (String producer : producers) {
                if (!targets.containsKey(producer)) {
                    throw invalid("missing_generated_producer", entry.getKey(), producer);
                }
                if (producer.equals(entry.getKey())) {
                    throw invalid("generated_binding_self_reference", entry.getKey(), producer);
                }
            }
            dependencies.put(entry.getKey(), producers);
        }
        List<String> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String target : dependencies.keySet()) {
            visit(target, dependencies, visiting, visited, ordered);
        }
        return List.copyOf(ordered);
    }

    private void visit(
            String target,
            Map<String, Set<String>> dependencies,
            Set<String> visiting,
            Set<String> visited,
            List<String> ordered) {
        if (visited.contains(target)) {
            return;
        }
        if (!visiting.add(target)) {
            throw new IllegalArgumentException("generated_binding_cycle: target `" + target + "` is part of a generated binding cycle.");
        }
        for (String producer : dependencies.getOrDefault(target, Set.of())) {
            visit(producer, dependencies, visiting, visited, ordered);
        }
        visiting.remove(target);
        visited.add(target);
        ordered.add(target);
    }

    private void collectGeneratedProducers(Object value, Set<String> producers) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectGeneratedProducers(item, producers));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectGeneratedProducers(item, producers));
            return;
        }
        V03Reference reference = parser.parse(value);
        if (reference instanceof V03Reference.Generated generated) {
            producers.add(generated.target());
        }
    }

    private IllegalArgumentException invalid(String code, String target, String producer) {
        return new IllegalArgumentException(code + ": target `" + target + "` references `" + producer + "`.");
    }
}
