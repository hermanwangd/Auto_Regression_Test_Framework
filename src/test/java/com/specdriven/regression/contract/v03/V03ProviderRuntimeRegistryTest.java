package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class V03ProviderRuntimeRegistryTest {

    @Test
    void resolvesRuntimeAdapterByProviderType() {
        V03ProviderRuntimeAdapter adapter = new FakeAdapter("http_mock");
        V03ProviderRuntimeRegistry registry = new V03ProviderRuntimeRegistry(List.of(adapter));

        assertThat(registry.resolve("http_mock")).isSameAs(adapter);
    }

    @Test
    void rejectsUnknownProviderTypeWithOwnerActionableMessage() {
        V03ProviderRuntimeRegistry registry = new V03ProviderRuntimeRegistry(List.of(new FakeAdapter("http_mock")));

        assertThatThrownBy(() -> registry.resolve("jdbc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No v0.3 runtime adapter for provider_type `jdbc`");
    }

    @Test
    void rejectsDuplicateProviderTypeAdapters() {
        V03ProviderRuntimeAdapter first = new FakeAdapter("http_mock");
        V03ProviderRuntimeAdapter second = new FakeAdapter("http_mock");

        assertThatThrownBy(() -> new V03ProviderRuntimeRegistry(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate v0.3 runtime adapter for provider_type `http_mock`");
    }

    private static final class FakeAdapter implements V03ProviderRuntimeAdapter {
        private final String providerType;

        private FakeAdapter(String providerType) {
            this.providerType = providerType;
        }

        @Override
        public String providerType() {
            return providerType;
        }

        @Override
        public boolean supports(String providerContract, String operation) {
            return true;
        }

        @Override
        public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
            return new V03StepResult(step.id(), "passed", Map.of(), List.of(), "", "");
        }
    }
}
