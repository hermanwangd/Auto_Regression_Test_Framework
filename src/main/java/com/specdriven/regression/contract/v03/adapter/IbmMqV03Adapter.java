package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.ibmmq.IbmMqRuntimeFactory;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import com.specdriven.regression.build.RunnerBuildVariant;
import java.util.Set;

public class IbmMqV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of(
            "mq_put",
            "mq_browse",
            "mq_message_exists",
            "mq_payload_match");

    private final ProviderRuntime runtime = IbmMqRuntimeFactory.create();

    @Override
    public String providerType() {
        return "ibm_mq";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return RunnerBuildVariant.ibmMqAvailable() && "ibm_mq.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }
}
