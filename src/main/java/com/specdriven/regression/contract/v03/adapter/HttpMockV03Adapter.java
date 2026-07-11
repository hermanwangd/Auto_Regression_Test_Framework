package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.wiremock.WireMockHttpMockProviderRuntime;
import java.util.Set;

public class HttpMockV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of("load_stubs", "verify_requests", "reset_mock");

    private final WireMockHttpMockProviderRuntime runtime = new WireMockHttpMockProviderRuntime();

    @Override
    public String providerType() {
        return "http_mock";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "http_mock.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step)));
    }
}
