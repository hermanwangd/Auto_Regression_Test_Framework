package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V03GeneratedBindingDagTest {
    private final V03GeneratedBindingDag dag = new V03GeneratedBindingDag();

    @Test
    void ordersGeneratedBindingProducerBeforeConsumer() {
        Map<String, V03ResolvedTarget> targets = Map.of(
                "client", target("client", Map.of("base_url", "generated://mock/base_url")),
                "mock", target("mock", Map.of()));

        assertThat(dag.producerFirstOrder(targets)).containsExactly("mock", "client");
    }

    @Test
    void rejectsMissingProducerAndCycles() {
        assertThatThrownBy(() -> dag.producerFirstOrder(Map.of(
                "client", target("client", Map.of("base_url", "generated://missing/base_url")))))
                .hasMessageContaining("missing_generated_producer");
        assertThatThrownBy(() -> dag.producerFirstOrder(Map.of(
                "first", target("first", Map.of("ref", "generated://second/value")),
                "second", target("second", Map.of("ref", "generated://first/value")))))
                .hasMessageContaining("generated_binding_cycle");
    }

    @Test
    void rejectsGeneratedOutputNotDeclaredBindableByProducerContract() {
        Map<String, V03ResolvedTarget> targets = Map.of(
                "client", target("client", Map.of("base_url", "generated://mock/hidden_url")),
                "mock", new V03ResolvedTarget("mock", "http_mock.v0.3", "http_mock", "local", "mock", Map.of()));
        Map<String, V03ProviderContract> contracts = Map.of("http_mock.v0.3", new V03ProviderContract(
                "http_mock.v0.3", "http_mock", Set.of("mock"), Map.of(), Map.of(), Set.of("base_url"), Set.of(), Set.of()));

        assertThatThrownBy(() -> dag.producerFirstOrder(targets, contracts))
                .hasMessageContaining("unknown_bindable_output");
    }

    private V03ResolvedTarget target(String name, Map<String, Object> bindings) {
        return new V03ResolvedTarget(name, "sample.v0.3", "sample", "local", "mock", bindings);
    }
}
