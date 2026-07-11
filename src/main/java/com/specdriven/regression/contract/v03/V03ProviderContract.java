package com.specdriven.regression.contract.v03;

import java.util.Map;
import java.util.Set;

/** Typed framework-owned Provider Contract used by the v0.3 compiler. */
public record V03ProviderContract(
        String id,
        String providerType,
        Set<String> runtimeModes,
        Map<String, V03BindingDefinition> bindings,
        Map<String, V03OperationDefinition> operations,
        Set<String> bindableOutputs,
        Set<String> evidenceOutputs,
        Set<String> failureCodes) {

    public V03ProviderContract {
        runtimeModes = Set.copyOf(runtimeModes);
        bindings = Map.copyOf(bindings);
        operations = Map.copyOf(operations);
        bindableOutputs = Set.copyOf(bindableOutputs);
        evidenceOutputs = Set.copyOf(evidenceOutputs);
        failureCodes = Set.copyOf(failureCodes);
    }

    public record V03BindingDefinition(boolean required, String source) {
    }

    public record V03OperationDefinition(
            Set<String> allowedInputs,
            Set<String> requiredInputs,
            Set<String> outputRefs) {

        public V03OperationDefinition {
            allowedInputs = Set.copyOf(allowedInputs);
            requiredInputs = Set.copyOf(requiredInputs);
            outputRefs = Set.copyOf(outputRefs);
        }
    }
}
