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
        Set<String> evidenceRedact,
        Set<String> failureCodes) {

    public V03ProviderContract {
        runtimeModes = Set.copyOf(runtimeModes);
        bindings = Map.copyOf(bindings);
        operations = Map.copyOf(operations);
        bindableOutputs = Set.copyOf(bindableOutputs);
        evidenceOutputs = Set.copyOf(evidenceOutputs);
        evidenceRedact = Set.copyOf(evidenceRedact);
        failureCodes = Set.copyOf(failureCodes);
    }

    public V03ProviderContract(String id, String providerType, Set<String> runtimeModes,
            Map<String, V03BindingDefinition> bindings, Map<String, V03OperationDefinition> operations,
            Set<String> bindableOutputs, Set<String> evidenceOutputs, Set<String> failureCodes) {
        this(id, providerType, runtimeModes, bindings, operations, bindableOutputs, evidenceOutputs, Set.of(), failureCodes);
    }

    public record V03BindingDefinition(boolean required, String source, V03ValueType valueType,
            Set<V03ReferenceKind> referenceKinds, V03Sensitivity sensitivity) {
        public V03BindingDefinition { referenceKinds = Set.copyOf(referenceKinds); }
        public V03BindingDefinition(boolean required, String source) {
            this(required, source, V03ValueType.ANY, Set.of(V03ReferenceKind.LITERAL, V03ReferenceKind.GENERATED,
                    V03ReferenceKind.ENV), V03Sensitivity.PUBLIC);
        }
    }

    public record V03OperationDefinition(
            Set<String> allowedInputs,
            Set<String> requiredInputs,
            Set<String> outputRefs,
            Map<String, V03InputDefinition> inputDefinitions,
            Map<String, V03OutputDefinition> outputDefinitions,
            Set<String> runtimeModes,
            Set<String> allowedPhases) {

        public V03OperationDefinition {
            if (!inputDefinitions.isEmpty()) {
                allowedInputs = inputDefinitions.keySet();
                requiredInputs = inputDefinitions.entrySet().stream()
                        .filter(entry -> entry.getValue().required())
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
            if (!outputDefinitions.isEmpty()) outputRefs = outputDefinitions.keySet();
            allowedInputs = Set.copyOf(allowedInputs);
            requiredInputs = Set.copyOf(requiredInputs);
            outputRefs = Set.copyOf(outputRefs);
            inputDefinitions = Map.copyOf(inputDefinitions);
            outputDefinitions = Map.copyOf(outputDefinitions);
            runtimeModes = Set.copyOf(runtimeModes);
            allowedPhases = Set.copyOf(allowedPhases);
        }

        public V03OperationDefinition(
                Set<String> allowedInputs,
                Set<String> requiredInputs,
                Set<String> outputRefs,
                Map<String, V03InputDefinition> inputDefinitions,
                Map<String, V03OutputDefinition> outputDefinitions,
                Set<String> runtimeModes) {
            this(allowedInputs, requiredInputs, outputRefs, inputDefinitions, outputDefinitions,
                    runtimeModes, Set.of("setup", "execute", "verify", "cleanup"));
        }

        public V03OperationDefinition(Set<String> allowedInputs, Set<String> requiredInputs, Set<String> outputRefs) {
            this(allowedInputs, requiredInputs, outputRefs,
                    legacyInputs(allowedInputs, requiredInputs), legacyOutputs(outputRefs), Set.of(),
                    Set.of("setup", "execute", "verify", "cleanup"));
        }

        private static Map<String, V03InputDefinition> legacyInputs(
                Set<String> allowedInputs, Set<String> requiredInputs) {
            Map<String, V03InputDefinition> result = new java.util.LinkedHashMap<>();
            for (String input : allowedInputs) {
                result.put(input, V03InputDefinition.legacy(requiredInputs.contains(input)));
            }
            return result;
        }

        private static Map<String, V03OutputDefinition> legacyOutputs(Set<String> outputRefs) {
            Map<String, V03OutputDefinition> result = new java.util.LinkedHashMap<>();
            for (String output : outputRefs) result.put(output, V03OutputDefinition.legacy(false));
            return result;
        }
    }
}
