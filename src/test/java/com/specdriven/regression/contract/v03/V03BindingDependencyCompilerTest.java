package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V03BindingDependencyCompilerTest {
    private final V03BindingDependencyCompiler compiler = new V03BindingDependencyCompiler();

    @Test
    void rejectsUndeclaredOutputIncompatibleTypesAndUnsafeSensitivityFlow() {
        V03ExecutionStep producer = step("produce", "producer", "producer.v0.3", "produce", Map.of());
        V03ExecutionStep consumer = step("consume", "consumer", "consumer.v0.3", "consume",
                Map.of("value", "step://produce/value"));
        Map<String, V03ProviderContract> contracts = Map.of(
                "producer.v0.3", contract("producer.v0.3", "produce", Map.of(), Map.of(
                        "value", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.SECRET, true, false))),
                "consumer.v0.3", contract("consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.NUMBER, Set.of(V03ReferenceKind.STEP),
                                V03Sensitivity.PUBLIC)), Map.of()));

        assertThatThrownBy(() -> compiler.compile(List.of(producer, consumer), Map.of(), contracts, Map.of()))
                .hasMessageContaining("incompatible_binding_type");

        Map<String, V03ProviderContract> unsafeContracts = Map.of(
                "producer.v0.3", contracts.get("producer.v0.3"),
                "consumer.v0.3", contract("consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.STRING, Set.of(V03ReferenceKind.STEP),
                                V03Sensitivity.PUBLIC)), Map.of()));
        assertThatThrownBy(() -> compiler.compile(List.of(producer, consumer), Map.of(), unsafeContracts, Map.of()))
                .hasMessageContaining("unsafe_sensitivity_flow");
    }

    @Test
    void rejectsMissingEnvironmentValueBeforeProviderRuntime() {
        V03ExecutionStep consumer = step("consume", "consumer", "consumer.v0.3", "consume",
                Map.of("value", "env://REQUIRED_VALUE"));
        Map<String, V03ProviderContract> contracts = Map.of("consumer.v0.3", contract(
                "consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.STRING, Set.of(V03ReferenceKind.ENV),
                                V03Sensitivity.MASKED)), Map.of()));

        assertThatThrownBy(() -> compiler.compile(List.of(consumer), Map.of(), contracts, Map.of()))
                .hasMessageContaining("missing_environment_value");
    }

    @Test
    void rejectsOutputNotDeclaredByTheReferencedProducerOperation() {
        V03ExecutionStep producer = step("produce", "producer", "producer.v0.3", "produce", Map.of());
        V03ExecutionStep consumer = step("consume", "consumer", "consumer.v0.3", "consume",
                Map.of("value", "step://produce/other_value"));
        Map<String, V03ProviderContract> contracts = Map.of(
                "producer.v0.3", contract("producer.v0.3", "produce", Map.of(), Map.of(
                        "value", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.PUBLIC, true, false))),
                "consumer.v0.3", contract("consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.STRING, Set.of(V03ReferenceKind.STEP),
                                V03Sensitivity.PUBLIC)), Map.of()));

        assertThatThrownBy(() -> compiler.compile(List.of(producer, consumer), Map.of(), contracts, Map.of()))
                .hasMessageContaining("missing_provider_output");
    }

    @Test
    void acceptsDottedSubpathsOfDeclaredObjectOutputs() {
        V03ExecutionStep producer = step("produce", "producer", "producer.v0.3", "produce", Map.of());
        V03ExecutionStep consumer = step("consume", "consumer", "consumer.v0.3", "consume",
                Map.of("value", "step://produce/response.status"));
        Map<String, V03ProviderContract> contracts = Map.of(
                "producer.v0.3", contract("producer.v0.3", "produce", Map.of(), Map.of(
                        "response", new V03OutputDefinition(V03ValueType.OBJECT, V03Sensitivity.PUBLIC, true, false))),
                "consumer.v0.3", contract("consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.STRING, Set.of(V03ReferenceKind.STEP),
                                V03Sensitivity.PUBLIC)), Map.of()));

        assertThat(compiler.compile(List.of(producer, consumer), Map.of(), contracts, Map.of())).hasSize(1);
    }

    @Test
    void rejectsProducerStepsFromAnotherTestCase() {
        V03ExecutionStep producer = step("TC-1", "produce", "producer", "producer.v0.3", "produce", Map.of());
        V03ExecutionStep consumer = step("TC-2", "consume", "consumer", "consumer.v0.3", "consume",
                Map.of("value", "step://produce/value"));
        Map<String, V03ProviderContract> contracts = Map.of(
                "producer.v0.3", contract("producer.v0.3", "produce", Map.of(), Map.of(
                        "value", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.PUBLIC, true, false))),
                "consumer.v0.3", contract("consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.STRING, Set.of(V03ReferenceKind.STEP),
                                V03Sensitivity.PUBLIC)), Map.of()));

        assertThatThrownBy(() -> compiler.compile(List.of(producer, consumer), Map.of(), contracts, Map.of()))
                .hasMessageContaining("missing_or_forward_step_producer");
    }

    @Test
    void rejectsNestedSubpathsOfScalarOutputs() {
        V03ExecutionStep producer = step("produce", "producer", "producer.v0.3", "produce", Map.of());
        V03ExecutionStep consumer = step("consume", "consumer", "consumer.v0.3", "consume",
                Map.of("value", "step://produce/value.child"));
        Map<String, V03ProviderContract> contracts = Map.of(
                "producer.v0.3", contract("producer.v0.3", "produce", Map.of(), Map.of(
                        "value", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.PUBLIC, true, false))),
                "consumer.v0.3", contract("consumer.v0.3", "consume", Map.of(
                        "value", new V03InputDefinition(true, V03ValueType.ANY, Set.of(V03ReferenceKind.STEP),
                                V03Sensitivity.PUBLIC)), Map.of()));

        assertThatThrownBy(() -> compiler.compile(List.of(producer, consumer), Map.of(), contracts, Map.of()))
                .hasMessageContaining("invalid_output_subpath");
    }

    private V03ExecutionStep step(
            String id, String target, String contract, String operation, Map<String, Object> inputs) {
        return step("TC-1", id, target, contract, operation, inputs);
    }

    private V03ExecutionStep step(
            String testCaseId, String id, String target, String contract, String operation, Map<String, Object> inputs) {
        return new V03ExecutionStep(testCaseId, V03ExecutionStepKind.PROVIDER_OPERATION,
                "execute", id, target, contract, contract.substring(0, contract.indexOf('.')),
                "local", "native", operation, inputs, "");
    }

    private V03ProviderContract contract(
            String id,
            String operationName,
            Map<String, V03InputDefinition> inputs,
            Map<String, V03OutputDefinition> outputs) {
        return new V03ProviderContract(id, id.substring(0, id.indexOf('.')), Set.of("native"), Map.of(), Map.of(
                operationName, new V03ProviderContract.V03OperationDefinition(
                        inputs.keySet(), inputs.keySet(), outputs.keySet(), inputs, outputs, Set.of("native"),
                        Set.of("execute"))), Set.of(), Set.of(), Set.of());
    }
}
