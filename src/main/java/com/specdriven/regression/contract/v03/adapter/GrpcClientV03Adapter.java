package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.grpc.GrpcClientProviderRuntime;

public class GrpcClientV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private final GrpcClientProviderRuntime runtime = new GrpcClientProviderRuntime();

    @Override
    public String providerType() {
        return "grpc_client";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "grpc_client.v0.3".equals(providerContract) && "unary_call".equals(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step)));
    }
}
