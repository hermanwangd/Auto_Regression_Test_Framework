package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.http.RestClientProviderRuntime;

public class RestClientV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private final RestClientProviderRuntime runtime = new RestClientProviderRuntime();

    @Override
    public String providerType() {
        return "rest_client";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "rest_client.v0.3".equals(providerContract) && "http_request".equals(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step)));
    }

    @Override
    String mapBindAs(String inputName) {
        if ("request.body_ref".equals(inputName)) {
            return "request.body";
        }
        return inputName;
    }
}
