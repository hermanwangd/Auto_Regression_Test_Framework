package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.kafka.KafkaProviderRuntime;
import java.util.Set;

public class KafkaV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter, com.specdriven.regression.sit.SitPolicyAwareProvider {

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
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }
    @Override public boolean simulated(){return false;}
    @Override public com.specdriven.regression.sit.SitOperationEffect sitEffect(String contract,String op){return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
    @Override public com.specdriven.regression.sit.SitOperationEffect sitEffect(V03ExecutionStep step){
        if(!"kafka.v0.3".equals(step.providerContract())||!"kafka_publish".equals(step.operation())||!"execute".equals(step.phase())||!"native".equals(step.runtimeMode()))return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;
        try{com.specdriven.regression.provider.kafka.ReviewedLoopbackKafkaPolicy.validateInputs(step.inputs());return com.specdriven.regression.sit.SitOperationEffect.OWNED_WRITE;}catch(IllegalArgumentException e){return com.specdriven.regression.sit.SitOperationEffect.UNKNOWN;}
    }
    @Override public V03StepResult executeWithSitPolicy(V03ExecutionStep step,V03ExecutionContext context,com.specdriven.regression.sit.SitExecutionScope scope){throw new IllegalArgumentException("reviewed_kafka: shared_ownership_unsupported");}
    @Override public V03StepResult executeReviewed(V03ExecutionStep step,V03ExecutionContext context,com.specdriven.regression.sit.SitExecutionScope scope){
        if(scope!=null||sitEffect(step)!=com.specdriven.regression.sit.SitOperationEffect.OWNED_WRITE)throw new IllegalArgumentException("reviewed_kafka: owned_publish_required");
        com.specdriven.regression.provider.kafka.ReviewedLoopbackKafkaPolicy.validateBindings(context.targets().get(step.target()).bindings());
        return stepResult(step,runtime.executeReviewedPublish(providerContext(step,context),request(step,context)));
    }
}
