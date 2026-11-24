package com.specdriven.regression.provider.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RestDestinationPolicyTest {
    @Test
    void usesOwnerEndpointOnlyWhenThereIsNoExplicitPolicy() {
        String base = "https://api.example.test/v1";
        assertThat(new RestDestinationPolicy(null, base).destination(base, "ready", null).toString())
                .isEqualTo(base + "/ready");
        assertThatThrownBy(() -> new RestDestinationPolicy("", base).destination(base, "ready", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorizesExactOriginAndKeepsBasePathAndQuery() {
        var policy = new RestDestinationPolicy("https://api.example.test, http://127.0.0.1:8100", null);
        assertThat(policy.destination("https://API.example.test:443/v1/", "/status?q=a%20b", null).toString())
                .isEqualTo("https://API.example.test:443/v1/status?q=a%20b");
        assertThatThrownBy(() -> policy.destination("https://api.example.test:8443", "/status", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.destination("https://api.example.test", "https://other.example.test", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestDestinationPolicy("https://api.example.test/v1", null).destination("https://api.example.test", "/", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
