package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles and validates dataflow dependencies before provider runtime dispatch. */
public final class V03BindingDependencyCompiler {
    private final V03ReferenceParser parser = new V03ReferenceParser();

    public List<V03BindingDependency> compile(
            List<V03ExecutionStep> steps,
            Map<String, V03ResolvedTarget> targets,
            Map<String, V03ProviderContract> contracts,
            Map<String, String> environment) {
        Map<String, V03ExecutionStep> priorSteps = new LinkedHashMap<>();
        List<V03BindingDependency> dependencies = new ArrayList<>();
        for (V03ExecutionStep step : steps) {
            if (step.kind() != V03ExecutionStepKind.PROVIDER_OPERATION) {
                priorSteps.put(step.id(), step);
                continue;
            }
            V03ProviderContract.V03OperationDefinition operation = operation(step, contracts);
            for (Map.Entry<String, Object> input : step.inputs().entrySet()) {
                V03InputDefinition consumer = inputDefinition(operation, input.getKey());
                collect(input.getValue(), reference -> {
                    if (reference instanceof V03Reference.Step prior) {
                        V03ExecutionStep producer = priorSteps.get(prior.stepId());
                        if (producer == null) {
                            throw new IllegalArgumentException("missing_or_forward_step_producer: step `" + step.id()
                                    + "` references `" + prior.stepId() + "`.");
                        }
                        V03OutputDefinition output = outputDefinition(producer, prior.outputPath(), contracts);
                        compatible(consumer, output, step, input.getKey());
                        dependencies.add(new V03BindingDependency(step.id(), input.getKey(), V03ReferenceKind.STEP,
                                "", producer.id(), prior.outputPath()));
                    } else if (reference instanceof V03Reference.Generated generated) {
                        V03ResolvedTarget producerTarget = targets.get(generated.target());
                        if (producerTarget == null) {
                            throw new IllegalArgumentException("missing_generated_producer: step `" + step.id()
                                    + "` references target `" + generated.target() + "`.");
                        }
                        V03OutputDefinition output = outputDefinition(producerTarget, generated.output(), contracts);
                        if (!output.bindable()) {
                            throw new IllegalArgumentException("unknown_bindable_output: target `" + generated.target()
                                    + "` output `" + generated.output() + "` is not bindable.");
                        }
                        compatible(consumer, output, step, input.getKey());
                        dependencies.add(new V03BindingDependency(step.id(), input.getKey(), V03ReferenceKind.GENERATED,
                                generated.target(), "", generated.output()));
                    } else if (reference instanceof V03Reference.Environment env) {
                        String value = environment.get(env.name());
                        if (value == null || value.isBlank()) {
                            throw new IllegalArgumentException("missing_environment_value: input `" + input.getKey()
                                    + "` requires env://" + env.name() + ".");
                        }
                        dependencies.add(new V03BindingDependency(step.id(), input.getKey(), V03ReferenceKind.ENV,
                                "", "", env.name()));
                    }
                });
            }
            priorSteps.put(step.id(), step);
        }
        return List.copyOf(dependencies);
    }

    private V03ProviderContract.V03OperationDefinition operation(
            V03ExecutionStep step, Map<String, V03ProviderContract> contracts) {
        V03ProviderContract contract = contracts.get(step.providerContract());
        if (contract == null || !contract.operations().containsKey(step.operation())) {
            throw new IllegalArgumentException("missing_provider_contract_operation: step `" + step.id() + "`.");
        }
        return contract.operations().get(step.operation());
    }

    private V03InputDefinition inputDefinition(V03ProviderContract.V03OperationDefinition operation, String input) {
        V03InputDefinition exact = operation.inputDefinitions().get(input);
        if (exact != null) return exact;
        return operation.inputDefinitions().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".*")
                        && input.startsWith(entry.getKey().substring(0, entry.getKey().length() - 1)))
                .map(Map.Entry::getValue).findFirst().orElse(V03InputDefinition.legacy(false));
    }

    private V03OutputDefinition outputDefinition(
            V03ExecutionStep producer, String output, Map<String, V03ProviderContract> contracts) {
        V03ProviderContract contract = contracts.get(producer.providerContract());
        if (contract == null) {
            throw new IllegalArgumentException("missing_provider_contract_output: `" + output + "`.");
        }
        V03ProviderContract.V03OperationDefinition operation = contract.operations().get(producer.operation());
        if (operation == null) {
            throw new IllegalArgumentException("missing_provider_contract_operation: step `" + producer.id() + "`.");
        }
        V03OutputDefinition definition = operation.outputDefinitions().get(output);
        if (definition == null) {
            throw new IllegalArgumentException("missing_provider_output: step `" + producer.id()
                    + "` operation `" + producer.operation() + "` does not declare `" + output + "`.");
        }
        return definition;
    }

    private V03OutputDefinition outputDefinition(
            V03ResolvedTarget producer, String output, Map<String, V03ProviderContract> contracts) {
        return outputDefinition(contracts.get(producer.providerContract()), output);
    }

    private V03OutputDefinition outputDefinition(V03ProviderContract contract, String output) {
        if (contract == null) throw new IllegalArgumentException("missing_provider_contract_output: `" + output + "`.");
        return contract.operations().values().stream()
                .map(operation -> operation.outputDefinitions().get(output))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing_provider_output: `" + output + "` is not declared."));
    }

    private void compatible(
            V03InputDefinition consumer, V03OutputDefinition producer, V03ExecutionStep step, String input) {
        if (consumer.valueType() != V03ValueType.ANY && producer.valueType() != V03ValueType.ANY
                && consumer.valueType() != producer.valueType()) {
            throw new IllegalArgumentException("incompatible_binding_type: step `" + step.id() + "` input `"
                    + input + "` cannot consume `" + producer.valueType() + "` as `" + consumer.valueType() + "`.");
        }
        if (producer.sensitivity() == V03Sensitivity.SECRET && consumer.sensitivity() == V03Sensitivity.PUBLIC) {
            throw new IllegalArgumentException("unsafe_sensitivity_flow: step `" + step.id() + "` input `"
                    + input + "` accepts secret output as public.");
        }
    }

    private void collect(Object value, java.util.function.Consumer<V03Reference> consumer) {
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collect(item, consumer));
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collect(item, consumer));
        } else {
            consumer.accept(parser.parse(value));
        }
    }
}
