package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.http.RestClientProviderRuntime;
import com.specdriven.regression.provider.http.ReviewedLoopbackHttpPolicy;
import com.specdriven.regression.sit.SitPolicyAwareProvider;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.sit.SitExecutionScope;
import java.util.Map;
import com.specdriven.regression.sit.SitOperationEffect;

public class RestClientV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter, SitPolicyAwareProvider {

    private final RestClientProviderRuntime runtime = new RestClientProviderRuntime();

    private final V03ReferenceParser parser = new V03ReferenceParser();

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
        var provider = providerContext(step, context);
        var target = context.targets().get(step.target());
        String approved = null;
        if (target != null && parser.parse(target.bindings().get("base_url")) instanceof V03Reference.Generated ref
                && ref.jsonPointer().isBlank()) {
            var producer = context.targets().get(ref.target());
            if (producer != null && "mock".equals(producer.runtimeMode())
                    && (("http_mock".equals(producer.providerType()) && "http_mock.v0.3".equals(producer.providerContract()) && "base_url".equals(ref.output()))
                    || ("soap_mock".equals(producer.providerType()) && "soap_mock.v0.3".equals(producer.providerContract()) && "endpoint_url".equals(ref.output())))) {
                // providerContext already resolved the binding against the compiled producer step.
                approved = stringValue(provider.bindingValues().get("base_url"));
            }
        }
        return stepResult(step, runtime.executeAtApprovedOrigin(provider, request(step, context), approved));
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
    Map<String,Object> parameter(String name, Object value, V03ExecutionStep step, V03ExecutionContext context) {
        if ("request.body_ref".equals(name) || "request.body".equals(name)) {
            V03Reference ref = parser.parse(value);
            if (ref instanceof V03Reference.Artifact asset && asset.jsonPointer().isBlank()) {
                return Map.of("name", name, "bind_as", "request.body_ref", "ref",
                        new RestClientProviderRuntime.BodyAsset(context.artifactRoots().get(asset.root()), asset.path()));
            }
            if (!(ref instanceof V03Reference.Literal)) name = "request.body";
        }
        return super.parameter(name, value, step, context);
    }
}
