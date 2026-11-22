package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.cli.CliProcessProviderRuntime;
import com.specdriven.regression.provider.cli.ReviewedCliPolicy;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.sit.SitExecutionScope;
import com.specdriven.regression.sit.SitOperationEffect;
import com.specdriven.regression.sit.SitPolicyAwareProvider;
import java.util.ArrayList;
import java.util.Map;

/** Literal local process adaptation; shared-state ownership is never claimed. */
public final class CliProcessV03Adapter extends AbstractProviderRuntimeV03Adapter implements SitPolicyAwareProvider {
    private final CliProcessProviderRuntime runtime = new CliProcessProviderRuntime();

    @Override public String providerType() { return "cli_process"; }
    @Override public boolean supports(String contract, String operation) {
        return "cli_process.v0.3".equals(contract) && "run_command".equals(operation);
    }
    @Override public boolean simulated() { return false; }
    @Override public SitOperationEffect sitEffect(String contract, String operation) { return SitOperationEffect.UNKNOWN; }
    @Override public SitOperationEffect sitEffect(V03ExecutionStep step) {
        if (!supports(step.providerContract(), step.operation()) || !"execute".equals(step.phase())
                || !"native".equals(step.runtimeMode())) return SitOperationEffect.UNKNOWN;
        try { ReviewedCliPolicy.validateInputs(step.inputs()); return SitOperationEffect.LOCAL_PROCESS; }
        catch (IllegalArgumentException error) { return SitOperationEffect.UNKNOWN; }
    }
    @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        ReviewedCliPolicy.validateInputs(step.inputs());
        var target = context.targets().get(step.target());
        if (target == null) throw new IllegalArgumentException("reviewed_cli: target is required");
        ReviewedCliPolicy.validateBindings(target.bindings());
        ProviderExecutionContext providerContext = new ProviderExecutionContext(step.target(), step.providerType(),
                step.profile(), step.runtimeMode(), context.suiteRoot(), context.runDir(), Map.of(), Map.of(), target.bindings());
        var parameters = new ArrayList<Map<String,Object>>();
        // CLI values intentionally bypass the general reference resolver: every authoring input is literal.
        step.inputs().forEach((name,value) -> parameters.add(Map.of("name", name, "bind_as", name, "ref", value)));
        ProviderOperationRequest request = new ProviderOperationRequest(step.operation(), parameters,
                Map.of("_operation_id", step.id(), "_test_case_id", step.testCaseId()));
        return stepResult(step, runtime.execute(providerContext, request));
    }
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        throw new IllegalArgumentException("CLI local processes cannot claim shared-state ownership.");
    }
    @Override public V03StepResult executeReviewed(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
        if (scope != null || sitEffect(step) != SitOperationEffect.LOCAL_PROCESS)
            throw new IllegalArgumentException("Reviewed CLI requires literal native execution without shared ownership.");
        return execute(step, context);
    }
}
