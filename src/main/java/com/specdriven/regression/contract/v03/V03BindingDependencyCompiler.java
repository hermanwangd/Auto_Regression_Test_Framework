package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
import com.specdriven.regression.contract.v03.assertion.V03AssertionKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles and validates dataflow dependencies before provider runtime dispatch. */
public final class V03BindingDependencyCompiler {
    private final V03ReferenceParser parser = new V03ReferenceParser();
    private final V03OutputDefinitionResolver outputResolver = new V03OutputDefinitionResolver();

    public List<V03BindingDependency> compile(
            List<V03ExecutionStep> steps,
            Map<String, V03ResolvedTarget> targets,
            Map<String, V03ProviderContract> contracts,
            Map<String, String> environment) {
        Map<String, V03ExecutionStep> priorStepOutputs = new LinkedHashMap<>();
        Map<String, V03ExecutionStep> priorGeneratedProducers = new LinkedHashMap<>();
        List<V03BindingDependency> dependencies = new ArrayList<>();
        for (V03ExecutionStep step : steps) {
            if (step.kind() == V03ExecutionStepKind.ASSERTION) {
                compileAssertion(step, priorStepOutputs, contracts, dependencies);
                continue;
            }
            if (step.kind() != V03ExecutionStepKind.PROVIDER_OPERATION && step.kind() != V03ExecutionStepKind.PROVIDER_CHECK) {
                continue;
            }
            V03ProviderContract.V03OperationDefinition operation = operation(step, contracts);
            compileTargetBindings(step, targets.get(step.target()), contracts, priorGeneratedProducers, environment, dependencies);
            for (Map.Entry<String, Object> input : step.inputs().entrySet()) {
                V03InputDefinition consumer = inputDefinition(operation, input.getKey());
                collect(input.getValue(), reference -> {
                    if (reference instanceof V03Reference.Step prior) {
                        V03ExecutionStep producer = priorStepOutputs.get(scopedStepKey(step.testCaseId(), prior.stepId()));
                        if (producer == null) {
                            throw new IllegalArgumentException("missing_or_forward_step_producer: step `" + step.id()
                                    + "` references `" + prior.stepId() + "`.");
                        }
                        if (producer.kind() == V03ExecutionStepKind.PROVIDER_CHECK) {
                            throw new IllegalArgumentException("provider_check_output_not_consumable: step `" + step.id()
                                    + "` cannot consume provider-check output `" + prior.stepId() + "`.");
                        }
                        V03OutputDefinition output = outputDefinition(producer, prior.outputPath(), contracts);
                        compatible(consumer, output, step, input.getKey());
                        dependencies.add(new V03BindingDependency(step.testCaseId(), step.id(), input.getKey(), V03ReferenceKind.STEP,
                                producer.testCaseId(), "", producer.id(), prior.outputPath()));
                    } else if (reference instanceof V03Reference.Generated generated) {
                        V03ResolvedTarget producerTarget = targets.get(generated.target());
                        if (producerTarget == null) {
                            throw new IllegalArgumentException("missing_generated_producer: step `" + step.id()
                                    + "` references target `" + generated.target() + "`.");
                        }
                        V03ExecutionStep producer = generatedProducer(generated, step.testCaseId(), priorGeneratedProducers, contracts);
                        if (producer == null) {
                            throw new IllegalArgumentException("unknown_bindable_output: target `" + generated.target()
                                    + "` output `" + generated.output() + "` is not bindable.");
                        }
                        V03OutputDefinition output = outputDefinition(producer, generated.output(), contracts);
                        compatible(consumer, output, step, input.getKey());
                        dependencies.add(new V03BindingDependency(step.testCaseId(), step.id(), input.getKey(), V03ReferenceKind.GENERATED,
                                producer.testCaseId(), generated.target(), producer.id(), generated.output()));
                    } else if (reference instanceof V03Reference.Environment env) {
                        String value = environment.get(env.name());
                        if (value == null || value.isBlank()) {
                            throw new IllegalArgumentException("missing_environment_value: input `" + input.getKey()
                                    + "` requires env://" + env.name() + ".");
                        }
                        dependencies.add(new V03BindingDependency(step.testCaseId(), step.id(), input.getKey(), V03ReferenceKind.ENV,
                                "", "", "", env.name()));
                    }
                });
            }
            priorStepOutputs.put(scopedStepKey(step.testCaseId(), step.id()), step);
            if (step.kind() == V03ExecutionStepKind.PROVIDER_OPERATION) {
                priorGeneratedProducers.put(scopedStepKey(step.testCaseId(), step.id()), step);
            }
        }
        return List.copyOf(dependencies);
    }

    private void compileAssertion(
            V03ExecutionStep assertion,
            Map<String, V03ExecutionStep> priorStepOutputs,
            Map<String, V03ProviderContract> contracts,
            List<V03BindingDependency> dependencies) {
        V03AssertionKind kind = V03AssertionKind.require(assertion.operation());
        for (String key : List.of("actual", "actual_ref", "expected", "expected_ref", "schema_ref")) {
            if (!assertion.inputs().containsKey(key)) {
                continue;
            }
            V03Reference reference = parser.parse(assertion.inputs().get(key));
            if (reference instanceof V03Reference.Generated) {
                throw new IllegalArgumentException("invalid_reference_scope: assertion `" + assertion.id()
                        + "` may not use generated://; use an Env_Profile target binding.");
            }
            if (!(reference instanceof V03Reference.Step prior)) {
                continue;
            }
            V03ExecutionStep producer = priorStepOutputs.get(scopedStepKey(assertion.testCaseId(), prior.stepId()));
            if (producer == null) {
                throw new IllegalArgumentException("missing_or_forward_step_producer: assertion `" + assertion.id()
                        + "` references `" + prior.stepId() + "`.");
            }
            V03OutputDefinition output = outputDefinition(producer, prior.outputPath(), contracts);
            validateAssertionOutputType(assertion, kind, key, output);
            dependencies.add(new V03BindingDependency(assertion.testCaseId(), assertion.id(), key, V03ReferenceKind.STEP,
                    producer.testCaseId(), producer.target(), producer.id(), prior.outputPath()));
        }
    }

    private void validateAssertionOutputType(
            V03ExecutionStep assertion,
            V03AssertionKind kind,
            String operand,
            V03OutputDefinition output) {
        if ((kind == V03AssertionKind.GT || kind == V03AssertionKind.GTE
                || kind == V03AssertionKind.LT || kind == V03AssertionKind.LTE)
                && output.valueType() != V03ValueType.NUMBER) {
            throw new IllegalArgumentException("incompatible_assertion_type: assertion `" + assertion.id()
                    + "` " + operand + " requires NUMBER output.");
        }
        if (kind == V03AssertionKind.MATCHES && output.valueType() != V03ValueType.STRING) {
            throw new IllegalArgumentException("incompatible_assertion_type: assertion `" + assertion.id()
                    + "` " + operand + " requires STRING output.");
        }
    }

    private void compileTargetBindings(
            V03ExecutionStep consumerStep,
            V03ResolvedTarget target,
            Map<String, V03ProviderContract> contracts,
            Map<String, V03ExecutionStep> priorGeneratedProducers,
            Map<String, String> environment,
            List<V03BindingDependency> dependencies) {
        if (target == null) return;
        V03ProviderContract contract = contracts.get(target.providerContract());
        if (contract == null) {
            throw new IllegalArgumentException("missing_provider_contract: target `" + target.target() + "`.");
        }
        for (Map.Entry<String, Object> binding : target.bindings().entrySet()) {
            V03ProviderContract.V03BindingDefinition definition = contract.bindings().get(binding.getKey());
            if (definition == null) continue;
            if (definition.source().contains("secret_ref")) {
                continue;
            }
            V03InputDefinition consumer = new V03InputDefinition(
                    definition.required(), definition.valueType(), definition.referenceKinds(), definition.sensitivity());
            collect(binding.getValue(), reference -> {
                if (reference instanceof V03Reference.Generated generated) {
                    V03ExecutionStep producer = generatedProducer(
                            generated, consumerStep.testCaseId(), priorGeneratedProducers, contracts);
                    if (producer == null) {
                        throw new IllegalArgumentException("missing_generated_binding_producer: target `" + target.target()
                                + "` binding `" + binding.getKey() + "` requires prior `" + generated.target()
                                + "/" + generated.output() + "`.");
                    }
                    V03OutputDefinition output = outputDefinition(producer, generated.output(), contracts);
                    compatible(consumer, output, consumerStep, "binding." + binding.getKey());
                    dependencies.add(new V03BindingDependency(consumerStep.testCaseId(), consumerStep.id(), "binding." + binding.getKey(),
                            V03ReferenceKind.GENERATED, producer.testCaseId(), generated.target(), producer.id(), generated.output()));
                } else if (reference instanceof V03Reference.Environment env) {
                    String value = environment.get(env.name());
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException("missing_environment_value: target `" + target.target()
                                + "` binding `" + binding.getKey() + "` requires env://" + env.name() + ".");
                    }
                    dependencies.add(new V03BindingDependency(consumerStep.testCaseId(), consumerStep.id(), "binding." + binding.getKey(),
                            V03ReferenceKind.ENV, "", "", "", env.name()));
                }
            });
        }
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
        try {
            return outputResolver.resolve(operation, output);
        } catch (IllegalArgumentException error) {
            if (error.getMessage().startsWith("invalid_output_subpath:")) throw error;
            throw new IllegalArgumentException("missing_provider_output: step `" + producer.id()
                    + "` operation `" + producer.operation() + "` does not declare `" + output + "`.", error);
        }
    }

    private V03OutputDefinition outputDefinition(
            V03ResolvedTarget producer, String output, Map<String, V03ProviderContract> contracts) {
        return outputDefinition(contracts.get(producer.providerContract()), output);
    }

    private V03ExecutionStep generatedProducer(
            V03Reference.Generated generated,
            String consumerTestCaseId,
            Map<String, V03ExecutionStep> priorGeneratedProducers,
            Map<String, V03ProviderContract> contracts) {
        List<V03ExecutionStep> producers = priorGeneratedProducers.values().stream()
                .filter(step -> consumerTestCaseId.equals(step.testCaseId()))
                .filter(step -> generated.target().equals(step.target()))
                .filter(step -> {
                    try {
                        return outputDefinition(step, generated.output(), contracts).bindable();
                    } catch (IllegalArgumentException ignored) {
                        return false;
                    }
                }).toList();
        if (producers.size() > 1) {
            throw new IllegalArgumentException("ambiguous_generated_producer: target `" + generated.target()
                    + "` output `" + generated.output() + "` has multiple prior producer operations.");
        }
        return producers.isEmpty() ? null : producers.get(0);
    }

    private V03OutputDefinition outputDefinition(V03ProviderContract contract, String output) {
        if (contract == null) throw new IllegalArgumentException("missing_provider_contract_output: `" + output + "`.");
        V03OutputDefinition exact = contract.operations().values().stream()
                .map(operation -> outputResolver.find(operation, output))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (exact != null) return exact;
        throw new IllegalArgumentException("missing_provider_output: `" + output + "` is not declared.");
    }

    private void compatible(
            V03InputDefinition consumer, V03OutputDefinition producer, V03ExecutionStep step, String input) {
        if (consumer.valueType() != V03ValueType.ANY && producer.valueType() == V03ValueType.ANY) {
            throw new IllegalArgumentException("incompatible_binding_type: step `" + step.id() + "` input `"
                    + input + "` requires a declared typed producer output.");
        }
        if (consumer.valueType() != V03ValueType.ANY && consumer.valueType() != producer.valueType()) {
            throw new IllegalArgumentException("incompatible_binding_type: step `" + step.id() + "` input `"
                    + input + "` cannot consume `" + producer.valueType() + "` as `" + consumer.valueType() + "`.");
        }
        if (producer.sensitivity().ordinal() > consumer.sensitivity().ordinal()) {
            throw new IllegalArgumentException("unsafe_sensitivity_flow: step `" + step.id() + "` input `"
                    + input + "` accepts `" + producer.sensitivity() + "` output as `" + consumer.sensitivity() + "`.");
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

    private String scopedStepKey(String testCaseId, String stepId) {
        return testCaseId + "\n" + stepId;
    }

}
