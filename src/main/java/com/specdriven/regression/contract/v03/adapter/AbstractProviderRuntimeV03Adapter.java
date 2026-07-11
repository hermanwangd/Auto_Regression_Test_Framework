package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class AbstractProviderRuntimeV03Adapter {

    private final V03ReferenceParser referenceParser = new V03ReferenceParser();

    ProviderExecutionContext providerContext(V03ExecutionStep step, V03ExecutionContext context) {
        V03ResolvedTarget target = context.targets().get(step.target());
        Map<String, Object> bindingValues = new LinkedHashMap<>();
        if (target != null) {
            for (Map.Entry<String, Object> entry : target.bindings().entrySet()) {
                Object resolved = isSecretReferenceBinding(entry.getKey())
                        ? entry.getValue()
                        : resolveProviderValue(entry.getValue(), step, context);
                bindingValues.put(entry.getKey(), resolved);
                putDotted(bindingValues, entry.getKey(), resolved);
            }
        }
        return new ProviderExecutionContext(
                step.target(),
                step.providerType(),
                step.profile(),
                step.runtimeMode(),
                context.suiteRoot(),
                context.runDir(),
                Map.of(),
                Map.of(),
                bindingValues);
    }

    ProviderOperationRequest request(V03ExecutionStep step, V03ExecutionContext context) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Map.Entry<String, Object> entry : step.inputs().entrySet()) {
            String bindAs = mapBindAs(entry.getKey());
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", bindAs);
            parameter.put("bind_as", bindAs);
            parameter.put("ref", resolveProviderValue(entry.getValue(), step, context));
            parameters.add(parameter);
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("_operation_id", step.id());
        outputs.put("_test_case_id", step.testCaseId());
        return new ProviderOperationRequest(step.operation(), List.copyOf(parameters), Map.copyOf(outputs));
    }

    V03StepResult stepResult(V03ExecutionStep step, ProviderOperationResult result) {
        return new V03StepResult(
                step.id(),
                result.status(),
                result.outputs(),
                result.evidence().stream().map(ProviderEvidence::ref).filter(ref -> ref != null && !ref.isBlank()).toList(),
                result.failure() == null ? "" : result.failure().code(),
                result.failure() == null ? "" : result.failure().reason());
    }

    Object resolveProviderValue(Object value, V03ExecutionStep step, V03ExecutionContext context) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                resolved.put(String.valueOf(entry.getKey()),
                        resolveProviderValue(entry.getValue(), step, context));
            }
            return Map.copyOf(resolved);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> resolveProviderValue(item, step, context)).toList();
        }
        V03Reference reference = referenceParser.parse(value);
        return context.referenceResolver().resolveProviderValue(reference, context.referenceContext(step.testCaseId()));
    }

    Path artifactPath(Object value, V03ExecutionStep step, V03ExecutionContext context) {
        V03Reference reference = referenceParser.parse(value);
        if (!(reference instanceof V03Reference.Artifact artifact)) {
            throw new IllegalArgumentException("artifact_ref_required: `" + value + "`.");
        }
        return context.referenceResolver().artifactPath(artifact, context.referenceContext(step.testCaseId()));
    }

    @SuppressWarnings("unchecked")
    private void putDotted(Map<String, Object> values, String key, Object value) {
        if (!key.contains(".")) {
            return;
        }
        String[] parts = key.split("\\.");
        Map<String, Object> current = values;
        for (int index = 0; index < parts.length - 1; index++) {
            Object nested = current.get(parts[index]);
            if (!(nested instanceof Map<?, ?>)) {
                nested = new LinkedHashMap<String, Object>();
                current.put(parts[index], nested);
            }
            current = (Map<String, Object>) nested;
        }
        current.put(parts[parts.length - 1], value);
    }

    private boolean isSecretReferenceBinding(String bindingName) {
        return bindingName.endsWith(".secret_ref") || "secret_ref".equals(bindingName);
    }

    String mapBindAs(String inputName) {
        return inputName;
    }

    String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
