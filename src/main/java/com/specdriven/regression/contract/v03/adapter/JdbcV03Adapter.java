package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.jdbc.JdbcProviderRuntime;
import java.util.Set;

public class JdbcV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of(
            "db_seed",
            "db_query",
            "db_record_exists",
            "db_cleanup");

    private final JdbcProviderRuntime runtime = new JdbcProviderRuntime();

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
}
