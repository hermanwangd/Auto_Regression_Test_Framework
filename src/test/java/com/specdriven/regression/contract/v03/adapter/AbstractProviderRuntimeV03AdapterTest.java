package com.specdriven.regression.contract.v03.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AbstractProviderRuntimeV03AdapterTest {

    @Test
    void preservesBlockedProviderOperationStatus() {
        AbstractProviderRuntimeV03Adapter adapter = new AbstractProviderRuntimeV03Adapter() { };
        V03ExecutionStep step = new V03ExecutionStep(
                "TC-1", "exercise", "call-provider", "provider", "provider.v0.3", "provider",
                "local", "external", "call", Map.of(), "");
        ProviderOperationResult blocked = ProviderOperationResult.blocked(
                Map.of(), List.of(), ProviderFailure.of(
                        "PROVIDER_UNAVAILABLE", "ENVIRONMENT_BLOCKED", "Unavailable", "Start provider"));

        assertThat(adapter.stepResult(step, blocked).status()).isEqualTo("blocked");
    }

    @Test
    void rejectsStatusesOutsideFrozenProviderResultSet() {
        for (String status : new String[] {null, "", " ", "unknown", "timeout", "skipped"}) {
            assertThatThrownBy(() -> new ProviderOperationResult(status, Map.of(), List.of(), null))
                    .as("provider operation status %s", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("passed, failed, or blocked");

            assertThatThrownBy(() -> new V03StepResult("step", status, Map.of(), List.of(), "", ""))
                    .as("adapter result status %s", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("passed, failed, or blocked");
        }
    }
}
