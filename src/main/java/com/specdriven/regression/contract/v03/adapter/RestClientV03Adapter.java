package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.http.RestClientProviderRuntime;
import com.specdriven.regression.provider.http.ReviewedLoopbackHttpPolicy;
import com.specdriven.regression.sit.SitPolicyAwareProvider;
import com.specdriven.regression.sit.SitExecutionScope;
import com.specdriven.regression.sit.SitOperationEffect;

public class RestClientV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter, SitPolicyAwareProvider {

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
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }

    @Override public boolean simulated() { return false; }
    @Override public SitOperationEffect sitEffect(String contract, String operation) { return SitOperationEffect.UNKNOWN; }
    @Override public SitOperationEffect sitEffect(V03ExecutionStep step) {
        if (!supports(step.providerContract(), step.operation()) || !"execute".equals(step.phase())) return SitOperationEffect.UNKNOWN;
        try { ReviewedLoopbackHttpPolicy.validateInputs(step.inputs()); return SitOperationEffect.READ_ONLY; }
        catch (IllegalArgumentException error) { return SitOperationEffect.UNKNOWN; }
    }
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        throw new IllegalArgumentException("Native REST cannot claim fake shared-state ownership.");
    }
    @Override public V03StepResult executeReviewed(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        if (scope != null || sitEffect(step) != SitOperationEffect.READ_ONLY)
            throw new IllegalArgumentException("Reviewed REST permits only local reads without shared ownership.");
        return stepResult(step, runtime.executeReviewedRead(providerContext(step, context), request(step, context)));
    }

    @Override
    String mapBindAs(String inputName) {
        if ("request.body_ref".equals(inputName)) {
            return "request.body";
        }
        return inputName;
    }
}
