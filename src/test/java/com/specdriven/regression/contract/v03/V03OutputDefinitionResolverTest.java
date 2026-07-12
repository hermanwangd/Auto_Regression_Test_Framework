package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V03OutputDefinitionResolverTest {

    private final V03OutputDefinitionResolver resolver = new V03OutputDefinitionResolver();

    @Test
    void resolvesTheLongestDeclaredDottedObjectPrefix() {
        V03ProviderContract.V03OperationDefinition operation = operation(Map.of(
                "response", new V03OutputDefinition(V03ValueType.OBJECT, V03Sensitivity.PUBLIC, true, false),
                "response.body", new V03OutputDefinition(V03ValueType.OBJECT, V03Sensitivity.SECRET, true, false)));

        V03ResolvedOutputPath resolved = resolver.resolvePath(operation, "response.body.id");

        assertThat(resolved.declaredPath()).isEqualTo("response.body");
        assertThat(resolved.remainingPath()).isEqualTo("id");
        assertThat(resolved.definition().sensitivity()).isEqualTo(V03Sensitivity.SECRET);
    }

    @Test
    void rejectsNestedPathBelowScalarOutput() {
        V03ProviderContract.V03OperationDefinition operation = operation(Map.of(
                "response.status", new V03OutputDefinition(V03ValueType.NUMBER, V03Sensitivity.PUBLIC, true, false)));

        assertThatThrownBy(() -> resolver.resolvePath(operation, "response.status.code"))
                .hasMessageContaining("invalid_output_subpath");
    }

    private V03ProviderContract.V03OperationDefinition operation(Map<String, V03OutputDefinition> outputs) {
        return new V03ProviderContract.V03OperationDefinition(
                Set.of(), Set.of(), outputs.keySet(), Map.of(), outputs, Set.of("native"), Set.of("execute"));
    }
}
