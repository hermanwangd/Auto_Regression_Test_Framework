package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProviderEndpointsTest {
    @Test
    void recordsTheBindingNameWithoutSerializingSensitiveEndpointComponents() {
        assertThat(ProviderEndpoints.httpEvidenceRef("env://VM_HEALTH_URL")).isEqualTo("env://VM_HEALTH_URL");
        assertThat(ProviderEndpoints.httpEvidenceRef("https://example.invalid/health?name=fixture"))
                .isEqualTo("[MASKED]");
    }

    @Test
    void acceptsLocalBrokerAndOperatorBoundRemoteBroker() {
        assertThat(ProviderEndpoints.nats("127.0.0.1:4222", "", true).getPort()).isEqualTo(4222);
        String broker = "nats://broker.example:4222";
        assertThat(ProviderEndpoints.nats(broker, "env://NATS_CONNECTION", false).getHost()).isEqualTo("broker.example");
        assertThatThrownBy(() -> ProviderEndpoints.nats(broker, "env://OTHER", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderEndpoints.nats(broker, broker, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void limitsPlaintextAuthenticationToOperatorBoundNumericLoopback() {
        String binding = "env://NATS_CONNECTION";
        assertThat(ProviderEndpoints.nats("nats://fixture@127.0.0.1:4222", binding, false).getHost())
                .isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> ProviderEndpoints.nats("nats://fixture@example.invalid:4222", binding, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProviderEndpoints.nats("nats://fixture%09name@127.0.0.1:4222", binding, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
