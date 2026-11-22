package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.grpc.GrpcClientProviderRuntime;

public class GrpcClientV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter, com.specdriven.regression.sit.SitPolicyAwareProvider {

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
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }

    @Override public boolean simulated() { return false; }
    @Override public com.specdriven.regression.sit.SitOperationEffect sitEffect(String contract,String operation) {return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
    @Override public com.specdriven.regression.sit.SitOperationEffect sitEffect(V03ExecutionStep step) {
        if(!supports(step.providerContract(),step.operation()) || !"execute".equals(step.phase()) || !"native".equals(step.runtimeMode())) return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;
        try {com.specdriven.regression.provider.grpc.ReviewedLoopbackGrpcPolicy.validateInputs(step.inputs());return com.specdriven.regression.sit.SitOperationEffect.READ_ONLY;}
        catch(IllegalArgumentException e) {return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
    }
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step,V03ExecutionContext context,com.specdriven.regression.sit.SitExecutionScope scope) {
        throw new IllegalArgumentException("Native gRPC cannot claim fake shared-state ownership.");
    }
    @Override public V03StepResult executeReviewed(V03ExecutionStep step,V03ExecutionContext context,com.specdriven.regression.sit.SitExecutionScope scope) {
        if(scope!=null || sitEffect(step)!=com.specdriven.regression.sit.SitOperationEffect.READ_ONLY) throw new IllegalArgumentException("Reviewed gRPC requires finite local unary without shared ownership.");
        com.specdriven.regression.provider.grpc.ReviewedLoopbackGrpcPolicy.fixture(context.suiteRoot(),context.targets().get(step.target()).bindings(),true);
        return stepResult(step,runtime.executeReviewedUnary(providerContext(step,context),request(step,context)));
    }
}
