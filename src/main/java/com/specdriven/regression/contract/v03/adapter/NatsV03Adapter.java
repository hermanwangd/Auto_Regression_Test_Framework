package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.nats.NatsProviderRuntime;
import com.specdriven.regression.provider.nats.ReviewedLoopbackNatsPolicy;
import com.specdriven.regression.sit.SitPolicyAwareProvider;
import com.specdriven.regression.sit.SitExecutionScope;
import com.specdriven.regression.sit.SitOperationEffect;
import java.util.Set;

public class NatsV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter, SitPolicyAwareProvider {

    private static final Set<String> OPERATIONS = Set.of(
            "nats_publish",
            "event_published",
            "event_payload_match");

    private final NatsProviderRuntime runtime = new NatsProviderRuntime();

    @Override
    public String providerType() {
        return "nats";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "nats.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }

    @Override public boolean simulated() { return false; }
    @Override public SitOperationEffect sitEffect(String contract, String operation) { return SitOperationEffect.UNKNOWN; }
    @Override public SitOperationEffect sitEffect(V03ExecutionStep step) {
        if (!"nats.v0.3".equals(step.providerContract()) || !"nats_publish".equals(step.operation())
                || !"execute".equals(step.phase()) || !"native".equals(step.runtimeMode())) return SitOperationEffect.UNKNOWN;
        try { ReviewedLoopbackNatsPolicy.validateInputs(step.inputs()); return SitOperationEffect.OWNED_WRITE; }
        catch (IllegalArgumentException error) { return SitOperationEffect.UNKNOWN; }
    }
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        throw new IllegalArgumentException("Native NATS cannot claim fake shared-state ownership.");
    }
    @Override public V03StepResult executeReviewed(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        if (scope != null || sitEffect(step) != SitOperationEffect.OWNED_WRITE)
            throw new IllegalArgumentException("Reviewed NATS requires one owned loopback publish without shared ownership.");
        ReviewedLoopbackNatsPolicy.validateBindings(context.targets().get(step.target()).bindings());
        ReviewedLoopbackNatsPolicy.validateFixture(context.suiteRoot());
        return stepResult(step, runtime.executeReviewedPublish(providerContext(step, context), request(step, context)));
    }
}
