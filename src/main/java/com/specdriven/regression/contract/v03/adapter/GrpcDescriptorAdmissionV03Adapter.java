package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.*;
import com.specdriven.regression.provider.grpc.*;
import com.specdriven.regression.sit.*;

/** Explicit reviewed-only route; no fallback to the generic network client. */
public final class GrpcDescriptorAdmissionV03Adapter extends AbstractProviderRuntimeV03Adapter
        implements V03ProviderRuntimeAdapter, SitPolicyAwareProvider {
    @Override public String providerType(){return ReviewedGrpcDescriptorPolicy.TYPE;}
    @Override public boolean supports(String contract,String operation){return ReviewedGrpcDescriptorPolicy.CONTRACT.equals(contract)&&"unary_call".equals(operation);}
    @Override public boolean simulated(){return false;}
    @Override public SitOperationEffect sitEffect(String contract,String operation){return SitOperationEffect.UNKNOWN;}
    @Override public SitOperationEffect sitEffect(V03ExecutionStep step){
        if(!supports(step.providerContract(),step.operation())||!"execute".equals(step.phase())||!"native".equals(step.runtimeMode()))return SitOperationEffect.UNKNOWN;
        try{ReviewedGrpcDescriptorPolicy.validateInputs(step.inputs());return SitOperationEffect.READ_ONLY;}
        catch(IllegalArgumentException invalid){return SitOperationEffect.UNKNOWN;}
    }
    @Override public V03StepResult execute(V03ExecutionStep step,V03ExecutionContext context){throw new IllegalArgumentException("Descriptor admission requires a reviewed handoff");}
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step,V03ExecutionContext context,SitExecutionScope scope){throw new IllegalArgumentException("Descriptor admission cannot claim shared-state ownership");}
    @Override public V03StepResult executeReviewed(V03ExecutionStep step,V03ExecutionContext context,SitExecutionScope scope){
        if(scope!=null||sitEffect(step)!=SitOperationEffect.READ_ONLY)throw new IllegalArgumentException("Descriptor admission requires the finite offline profile");
        return stepResult(step,new GrpcDescriptorAdmissionRuntime().executeReviewed(providerContext(step,context),request(step,context)));
    }
}
