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
        return producerFirstOrder(targets, Map.of());
    }

    public List<String> producerFirstOrder(
            Map<String, V03ResolvedTarget> targets,
            Map<String, V03ProviderContract> contracts) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, V03ResolvedTarget> entry : targets.entrySet()) {
            Set<V03Reference.Generated> generated = new LinkedHashSet<>();
            collectGeneratedReferences(entry.getValue().bindings(), generated);
            Set<String> producers = new LinkedHashSet<>();
            for (V03Reference.Generated reference : generated) {
                String producer = reference.target();
                if (!targets.containsKey(producer)) {
                    throw invalid("missing_generated_producer", entry.getKey(), producer);
                }
                if (producer.equals(entry.getKey())) {
                    throw invalid("generated_binding_self_reference", entry.getKey(), producer);
                }
                if (!contracts.isEmpty()) {
                    V03ResolvedTarget producerTarget = targets.get(producer);
                    V03ProviderContract contract = contracts.get(producerTarget.providerContract());
                    if (contract == null || !contract.bindableOutputs().contains(reference.output())) {
                        throw new IllegalArgumentException("unknown_bindable_output: target `" + entry.getKey()
                                + "` references `" + producer + "/" + reference.output() + "`.");
                    }
                }
                producers.add(producer);
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

    private void collectGeneratedReferences(Object value, Set<V03Reference.Generated> references) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectGeneratedReferences(item, references));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectGeneratedReferences(item, references));
            return;
        }
        V03Reference reference = parser.parse(value);
        if (reference instanceof V03Reference.Generated generated) {
            references.add(generated);
        }
    }

    private IllegalArgumentException invalid(String code, String target, String producer) {
        return new IllegalArgumentException(code + ": target `" + target + "` references `" + producer + "`.");
    }
}
