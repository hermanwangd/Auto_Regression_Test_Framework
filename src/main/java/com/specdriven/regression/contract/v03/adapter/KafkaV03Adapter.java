package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.kafka.KafkaProviderRuntime;
import java.util.Set;

public class KafkaV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of(
            "kafka_publish",
            "kafka_observe",
            "kafka_payload_match");

    private final KafkaProviderRuntime runtime = new KafkaProviderRuntime();

    @Override
    public String providerType() {
        return "kafka";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "kafka.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return stepResult(step, runtime.execute(providerContext(step, context), request(step)));
    }
}
