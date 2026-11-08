package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.jdbc.JdbcProviderRuntime;
import java.util.Set;

public class JdbcV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter,com.specdriven.regression.sit.SitPolicyAwareProvider {

    private static final Set<String> OPERATIONS = Set.of(
            "db_seed",
            "db_query",
            "db_record_exists",
            "db_cleanup");

    private final JdbcProviderRuntime runtime;
    private final com.specdriven.regression.provider.jdbc.ReviewedJdbcInvocation invocation;
    public JdbcV03Adapter(){this.runtime=new JdbcProviderRuntime();this.invocation=null;}
    public JdbcV03Adapter(com.specdriven.regression.provider.jdbc.ReviewedJdbcInvocation invocation) {
        this.invocation=java.util.Objects.requireNonNull(invocation);this.runtime=new JdbcProviderRuntime(com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery.missing(java.nio.file.Path.of(".")));
    }

    @Override
    public String providerType() {
        return "jdbc";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "jdbc.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }
    @Override public boolean simulated(){return false;}
    @Override public com.specdriven.regression.sit.SitOperationEffect sitEffect(String contract,String operation){return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
    @Override public com.specdriven.regression.sit.SitOperationEffect sitEffect(V03ExecutionStep step) {
        if(!"jdbc.v0.3".equals(step.providerContract())||!"db_query".equals(step.operation())||!"native".equals(step.runtimeMode())||!"execute".equals(step.phase()))return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;
        try{com.specdriven.regression.provider.jdbc.ReviewedOwnedMariaDbPolicy.validateInputs(step.inputs());return com.specdriven.regression.sit.SitOperationEffect.READ_ONLY;}
        catch(IllegalArgumentException e){return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
    }
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step,V03ExecutionContext context,com.specdriven.regression.sit.SitExecutionScope scope){throw new IllegalArgumentException("Reviewed JDBC cannot claim shared-state ownership");}
    @Override public V03StepResult executeReviewed(V03ExecutionStep step,V03ExecutionContext context,com.specdriven.regression.sit.SitExecutionScope scope) {
        if(scope!=null||invocation==null||sitEffect(step)!=com.specdriven.regression.sit.SitOperationEffect.READ_ONLY)throw new IllegalArgumentException("Reviewed JDBC requires finite local read and protected RAM carrier");
        return stepResult(step,runtime.executeReviewedQuery(providerContext(step,context),request(step,context),invocation));
    }
}
