package com.specdriven.regression.provider.wiremock;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.common.NetworkAddressRules;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/** Network policy for the framework's HTTP, SOAP and gRPC mock listeners. */
public final class ManagedWireMock {
    private static final NetworkAddressRules NO_PROXY_TARGETS = new NetworkAddressRules() {
        @Override public boolean isAllowed(String address) { return false; }
        @Override public boolean isAllowedAll() { return false; }
    };

    private ManagedWireMock() { }

    public static WireMockConfiguration loopbackOptions() {
        return options().bindAddress("127.0.0.1").enableBrowserProxying(false)
                .proxyPassThrough(false).limitProxyTargets(NO_PROXY_TARGETS);
    }
}
