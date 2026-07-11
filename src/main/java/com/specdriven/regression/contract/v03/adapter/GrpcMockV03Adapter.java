package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.grpc.GrpcMockProviderRuntime;
import java.util.Set;

public class GrpcMockV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of(
            "load_grpc_stub",
            "grpc_request_received",
            "reset_mock");

    private final GrpcMockProviderRuntime runtime = new GrpcMockProviderRuntime();

    @Override
    public String providerType() {
        return "grpc_mock";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "grpc_mock.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }
}
