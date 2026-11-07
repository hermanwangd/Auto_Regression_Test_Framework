package com.specdriven.regression.provider.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.sit.SitExecutionScope;
import com.specdriven.regression.sit.SitPolicyAwareProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliProcessV03AdapterTest {
    @Test
    void advertisesOnlyTheExplicitNativeLocalProcessContract() throws Exception {
        SitPolicyAwareProvider adapter = adapter();
        assertThat(adapter.providerType()).isEqualTo("cli_process");
        assertThat(adapter.simulated()).isFalse();
        assertThat(adapter.supports("cli_process.v0.3", "run_command")).isTrue();
        assertThat(adapter.supports("rest_client.v0.3", "run_command")).isFalse();
        assertThat(adapter.sitEffect(step("execute", Map.of("process.argv", List.of()))).name()).isEqualTo("LOCAL_PROCESS");
        assertThat(adapter.sitEffect(step("setup", Map.of("process.argv", List.of()))).name()).isEqualTo("UNKNOWN");
        assertThat(adapter.sitEffect(step("execute", Map.of("process.argv", List.of("env://PATH")))).name()).isEqualTo("UNKNOWN");
    }

    @Test
    void neverClaimsSharedStateOwnershipOrResolvesDynamicInputs() throws Exception {
        SitPolicyAwareProvider adapter = adapter();
        V03ExecutionStep step = step("execute", Map.of("process.argv", List.of()));
        assertThatThrownBy(() -> adapter.executeWithSitPolicy(step, null, new SitExecutionScope(null, "owned", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shared");
        assertThatThrownBy(() -> adapter.executeReviewed(step, null, new SitExecutionScope(null, "owned", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.execute(step("execute", Map.of("process.argv", List.of("env://PATH"))), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reviewed_cli:");
    }

    private SitPolicyAwareProvider adapter() throws Exception {
        try {
            return (SitPolicyAwareProvider) Class.forName("com.specdriven.regression.contract.v03.adapter.CliProcessV03Adapter")
                    .getConstructor().newInstance();
        } catch (ClassNotFoundException error) { throw new AssertionError("Missing cli_process v0.3 adapter", error); }
    }

    private V03ExecutionStep step(String phase, Map<String,Object> inputs) {
        return new V03ExecutionStep("case", phase, "command", "cli", "cli_process.v0.3", "cli_process",
                "local", "native", "run_command", inputs, "");
    }
}
