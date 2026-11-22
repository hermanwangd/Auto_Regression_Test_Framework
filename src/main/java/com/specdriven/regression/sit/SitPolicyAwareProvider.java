package com.specdriven.regression.sit;

import com.specdriven.regression.contract.v03.*;

/** Trusted providers opt in; the framework cannot sandbox arbitrary Java code. */
public interface SitPolicyAwareProvider extends V03ProviderRuntimeAdapter {
    boolean simulated();
    SitOperationEffect sitEffect(String providerContract, String operation);
    V03StepResult executeWithSitPolicy(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope);
    default SitOperationEffect sitEffect(V03ExecutionStep step) {
        return sitEffect(step.providerContract(), step.operation());
    }
    default V03StepResult executeReviewed(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        return scope == null ? execute(step, context) : executeWithSitPolicy(step, context, scope);
    }
}
