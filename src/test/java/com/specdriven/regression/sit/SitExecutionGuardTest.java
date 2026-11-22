package com.specdriven.regression.sit;

import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.contract.v03.*;
import com.specdriven.regression.producttest.ProductTestPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SitExecutionGuardTest {
    private final V03ExecutionPlan plan = new V03ExecutionPlanBuilder().build(
            Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"), "local_v03");
    private ProductTestPolicy policy(String access, String cleanup, String mode) {
        return new ProductTestPolicy("component", "regression", "local", "not_used",
                Map.of("sample_runtime", mode), Map.of(),
                new ProductTestPolicy.SharedState("fake", "namespace", "team", access, cleanup), "handoff.json");
    }
    private SitExecutionGuard guard(Spy adapter) {
        return new SitExecutionGuard(new V03ProviderRuntimeRegistry(List.of(adapter)), new LocalFakeSitOwnership());
    }
    @Test void readOnlyPrechecksSetupAndCleanupBeforeEveryProviderCall() {
        Spy spy = new Spy();
        assertThatThrownBy(() -> guard(spy).prepare(plan, policy("read_only", "none", "simulated")))
                .hasMessageContaining("read_only");
        assertThat(spy.calls).isZero();
    }
    @Test void cleanupNoneCannotSilentlySkipPlannedCleanup() {
        Spy spy = new Spy();
        assertThatThrownBy(() -> guard(spy).prepare(plan, policy("owned", "none", "simulated")))
                .hasMessageContaining("cleanup");
        assertThat(spy.calls).isZero();
    }
    @Test void modeMismatchAndUnknownEffectsBlockWithoutInvocation() {
        Spy spy = new Spy();
        assertThatThrownBy(() -> guard(spy).prepare(plan, policy("owned", "owned_only", "real")))
                .hasMessageContaining("mode");
        spy.override = SitOperationEffect.UNKNOWN;
        assertThatThrownBy(() -> guard(spy).prepare(plan, policy("owned", "owned_only", "simulated")))
                .hasMessageContaining("effect");
        assertThat(spy.calls).isZero();
    }
    @Test void changedEffectCannotBypassThePreparedPolicy() {
        Spy spy = new Spy();
        var prepared = guard(spy).prepare(plan, policy("owned", "owned_only", "simulated"));
        var scope = prepared.open("framework-run-id");
        spy.override = SitOperationEffect.READ_ONLY;
        assertThatThrownBy(() -> prepared.execute(plan.steps().get(0), null, scope))
                .hasMessageContaining("changed");
        assertThat(spy.calls).isZero();
    }
    static class Spy implements SitPolicyAwareProvider {
        int calls;
        SitOperationEffect override;
        public String providerType() { return "sample_fake_provider"; }
        public boolean supports(String c,String o) { return true; }
        public boolean simulated() { return true; }
        public SitOperationEffect sitEffect(String c,String operation) {
            if (override != null) return override;
            return switch (operation) {
                case "setup_fixture" -> SitOperationEffect.OWNED_WRITE;
                case "cleanup_fixture" -> SitOperationEffect.OWNED_CLEANUP;
                default -> SitOperationEffect.READ_ONLY;
            };
        }
        public V03StepResult execute(V03ExecutionStep s,V03ExecutionContext c) { calls++; return null; }
        public V03StepResult executeWithSitPolicy(V03ExecutionStep s,V03ExecutionContext c,SitExecutionScope scope) {
            calls++; return null;
        }
    }
}
