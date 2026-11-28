package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.provider.wiremock.WireMockHttpMockProviderRuntime;
import java.util.Set;

public class HttpMockV03Adapter extends RunScopedMockV03Adapter<WireMockHttpMockProviderRuntime> {
    private static final Set<String> OPERATIONS = Set.of("load_stubs", "verify_requests", "reset_mock");

    public HttpMockV03Adapter() {
        super(WireMockHttpMockProviderRuntime::new);
    }

    @Override
    public String providerType() {
        return "http_mock";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "http_mock.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }
}
