package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V03OutputRedactorTest {
    @Test
    void masksContractSensitiveOutputsAndNestedSecretLikeValues() {
        V03ProviderContract.V03OperationDefinition operation = new V03ProviderContract.V03OperationDefinition(
                Set.of(), Set.of(), Set.of("session", "response"), Map.of(), Map.of(
                        "session", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.SECRET, false, false),
                        "response", new V03OutputDefinition(V03ValueType.OBJECT, V03Sensitivity.PUBLIC, false, true)), Set.of());
        V03ProviderContract contract = new V03ProviderContract(
                "test.v0.3", "test", Set.of("native"), Map.of(), Map.of("run", operation), Set.of(), Set.of(), Set.of());
        V03ExecutionStep step = new V03ExecutionStep("TC-1", V03ExecutionStepKind.PROVIDER_OPERATION,
                "execute", "run", "target", "test.v0.3", "test", "local", "native", "run", Map.of(), "");
        V03ExecutionPlan plan = new V03ExecutionPlan("S", "local", Path.of("."),
                new V03SuiteMetadata("v0.3", "S", "local"),
                new V03EnvironmentProfile("local", "local", "per_run", "framework_verification_only", false),
                Map.of(), Map.of("test.v0.3", contract), Map.of(), java.util.List.of(), java.util.List.of(step),
                java.util.List.of(), "digest");

        Map<String, Object> redacted = new V03OutputRedactor().redact(plan, step, Map.of(
                "session", "Bearer actual-token",
                "response", Map.of("authorization", "Bearer actual-token", "status", "OK")));

        assertThat(redacted).containsEntry("session", V03OutputRedactor.MASKED);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) redacted.get("response");
        assertThat(response)
                .containsEntry("authorization", V03OutputRedactor.MASKED)
                .containsEntry("status", "OK");
        assertThat(new V03OutputRedactor().redactMessage("jdbc:oracle:thin:@secret-host password=actual"))
                .doesNotContain("secret-host")
                .doesNotContain("actual");
    }
}
