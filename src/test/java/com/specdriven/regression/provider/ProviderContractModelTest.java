package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderContractModelTest {

    @Test
    void resolvedProviderContractConvenienceConstructorDefaultsRuntimeMetadata() {
        ResolvedProviderContract contract = new ResolvedProviderContract(
                "binding",
                "api_payload",
                "ru",
                "request_response",
                "RU-api",
                "api_payload",
                "release_units[0].provider_contracts.bindings.api_payload");

        assertThat(contract.providerType()).isEmpty();
        assertThat(contract.registryStatus()).isEqualTo("supported");
        assertThat(contract.runtimeStatus()).isEqualTo("supported");
        assertThat(contract.affectedRu()).isEqualTo("RU-api");
        assertThat(contract.contractPath())
                .isEqualTo("release_units[0].provider_contracts.bindings.api_payload");
    }

    @Test
    void minimalResolvedProviderContractDefaultsCapabilityToProviderName() {
        ResolvedProviderContract contract = new ResolvedProviderContract("provider", "spring_boot_cli", "ru");

        assertThat(contract.providerFamily()).isEmpty();
        assertThat(contract.providerType()).isEmpty();
        assertThat(contract.registryStatus()).isEqualTo("supported");
        assertThat(contract.runtimeStatus()).isEqualTo("supported");
        assertThat(contract.affectedRu()).isEmpty();
        assertThat(contract.capability()).isEqualTo("spring_boot_cli");
        assertThat(contract.contractPath()).isEmpty();
    }

    @Test
    void providerContractGapConvenienceConstructorDefaultsToMissingAndBlocked() {
        ProviderContractGap gap = new ProviderContractGap(
                "release_units[0].provider_contracts.providers.spring_boot_cli",
                "provider",
                "spring_boot_cli",
                "Add provider contract before execution.");

        assertThat(gap.providerFamily()).isEmpty();
        assertThat(gap.providerType()).isEmpty();
        assertThat(gap.registryStatus()).isEqualTo("missing");
        assertThat(gap.runtimeStatus()).isEqualTo("blocked");
        assertThat(gap.affectedRu()).isEmpty();
        assertThat(gap.capability()).isEqualTo("spring_boot_cli");
    }
}
