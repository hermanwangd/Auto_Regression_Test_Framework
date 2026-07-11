package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.SampleFakeProvider;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SampleFakeProviderV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of("setup_fixture", "execute_sample", "cleanup_fixture");
    private final SampleFakeProvider provider = new SampleFakeProvider();

    @Override public String providerType() { return "sample_fake_provider"; }
    @Override public boolean supports(String contract, String operation) {
        return "sample_fake_provider.v0.3".equals(contract) && OPERATIONS.contains(operation);
    }

    @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        return switch (step.operation()) {
            case "setup_fixture" -> setup(step, context);
            case "execute_sample" -> run(step, context);
            case "cleanup_fixture" -> cleanup(step, context);
            default -> throw new IllegalArgumentException("Unsupported sample fake operation `" + step.operation() + "`.");
        };
    }

    private V03StepResult setup(V03ExecutionStep step, V03ExecutionContext context) {
        SampleFakeProvider.SetupResult result = provider.setup(artifactPath(step.inputs().get("fixture.setup_ref"), step, context),
                artifactPath(step.inputs().get("fixture.input_ref"), step, context), context.runDir(), step.target());
        return new V03StepResult(step.id(), result.passed() ? "passed" : "failed", Map.of(), List.of(relative(context, result.evidenceRef())), "", "");
    }

    private V03StepResult run(V03ExecutionStep step, V03ExecutionContext context) {
        SampleFakeProvider.ExecutionResult result = provider.execute(artifactPath(step.inputs().get("sample.input_ref"), step, context),
                context.testStartTime().toString(), context.runDir());
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("actual_json.status", result.actual().get("status"));
        outputs.put("actual_json", result.actual());
        outputs.put("actual_text", relative(context, result.actualText()));
        outputs.put("execution_log", relative(context, result.executionLog()));
        return new V03StepResult(step.id(), "passed", outputs,
                List.of(relative(context, result.actualJson()), relative(context, result.actualText()), relative(context, result.executionLog())), "", "");
    }

    private V03StepResult cleanup(V03ExecutionStep step, V03ExecutionContext context) {
        SampleFakeProvider.CleanupResult result = provider.cleanup(artifactPath(step.inputs().get("fixture.cleanup_ref"), step, context), context.runDir(), step.target());
        return new V03StepResult(step.id(), result.passed() ? "passed" : "failed", Map.of(), List.of(relative(context, result.evidenceRef())), "", "");
    }

    private String relative(V03ExecutionContext context, Path path) { return context.runDir().relativize(path).toString(); }
}
