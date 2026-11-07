package com.specdriven.regression.sit;

import com.specdriven.regression.contract.v03.*;
import com.specdriven.regression.producttest.ProductTestPolicy;
import com.specdriven.regression.producttest.ReviewedNativeCapability;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Prechecks every provider phase; effects remain enforced by the opted-in provider. */
public final class SitExecutionGuard {
    private final V03ProviderRuntimeRegistry registry;
    private final LocalFakeSitOwnership store;
    public SitExecutionGuard(V03ProviderRuntimeRegistry registry, LocalFakeSitOwnership store) {
        this.registry = registry; this.store = store;
    }
    public Prepared prepare(V03ExecutionPlan plan, ProductTestPolicy policy) {
        if (!policy.providerModes().keySet().equals(plan.targets().keySet()))
            throw new IllegalArgumentException("provider mode coverage must exactly match the reviewed targets");
        if (!"local".equals(plan.environmentProfile().executionMode())
                || !"framework_verification_only".equals(plan.environmentProfile().evidenceClassification())
                || plan.environmentProfile().downstreamReleaseEvidence())
            throw new IllegalArgumentException("reviewed providers require local framework_verification_only evidence without release eligibility");
        if (policy.nativeExecution() && (plan.targets().size() != 1 || plan.environmentProfile().sitReadiness() != null
                || plan.steps().stream().filter(step -> step.kind() != V03ExecutionStepKind.ASSERTION).count() != 1))
            throw new IllegalArgumentException("reviewed native execution requires one operation and no readiness/shared-state extension");
        var shared = policy.sharedState();
        if (shared != null && "read_only".equals(shared.dataAccess()) && !"none".equals(shared.cleanup()))
            throw new IllegalArgumentException("read_only requires cleanup none");
        Map<String, String> modes = new LinkedHashMap<>();
        for (var target : plan.targets().values()) {
            V03ProviderRuntimeAdapter raw = registry.resolve(target.providerType());
            if (!(raw instanceof SitPolicyAwareProvider adapter))
                throw new IllegalArgumentException("provider must explicitly declare its reviewed mode and shared-state effects");
            String observed = adapter.simulated() ? "simulated" : "real";
            String expected = policy.nativeExecution() ? "real" : "simulated";
            if (!observed.equals(policy.providerModes().get(target.target())) || !expected.equals(observed))
                throw new IllegalArgumentException("provider mode mismatch for the reviewed capability");
            if (policy.nativeExecution()) {
                var capability = ReviewedNativeCapability.fromContract(target.providerContract());
                if (!capability.providerType().equals(target.providerType()) || !"native".equals(target.runtimeMode()))
                    throw new IllegalArgumentException("reviewed native provider metadata differs from selected contract");
                capability.validateBindings(target.bindings());
            }
            modes.put(target.target(), observed);
        }
        Map<String, SitOperationEffect> effects = new LinkedHashMap<>();
        for (var step : plan.steps()) {
            if (step.kind() == V03ExecutionStepKind.ASSERTION) continue;
            SitPolicyAwareProvider adapter = (SitPolicyAwareProvider) registry.resolve(step.providerType());
            if (!adapter.supports(step.providerContract(), step.operation()))
                throw new IllegalArgumentException("provider does not support the reviewed operation");
            SitOperationEffect effect = adapter.sitEffect(step);
            if (effect == null || effect == SitOperationEffect.UNKNOWN)
                throw new IllegalArgumentException("unknown provider effect blocks reviewed execution");
            if (policy.nativeExecution()) {
                var capability = ReviewedNativeCapability.fromContract(step.providerContract());
                if (!"execute".equals(step.phase()) || !capability.operation().equals(step.operation()))
                    throw new IllegalArgumentException("reviewed native operation differs from selected contract");
                capability.validateInputs(step.inputs());
            }
            if (effect == SitOperationEffect.LOCAL_PROCESS && (shared != null || !policy.nativeExecution()))
                throw new IllegalArgumentException("local processes cannot claim shared-state ownership");
            if (shared != null) {
                if ("read_only".equals(shared.dataAccess()) && effect != SitOperationEffect.READ_ONLY)
                    throw new IllegalArgumentException("read_only cannot execute write or cleanup phases");
                if (effect == SitOperationEffect.OWNED_CLEANUP && (!"owned_only".equals(shared.cleanup())
                        || !"cleanup".equals(step.phase())))
                    throw new IllegalArgumentException("owned cleanup requires cleanup phase and owned_only policy");
            }
            effects.put(key(step), effect);
        }
        return new Prepared(registry, store, policy, Map.copyOf(effects), Map.copyOf(modes));
    }
    private static String key(V03ExecutionStep step) { return step.testCaseId() + "/" + step.id(); }
    public static final class Prepared {
        private final V03ProviderRuntimeRegistry registry;
        private final LocalFakeSitOwnership store;
        private final ProductTestPolicy policy;
        private final Map<String,SitOperationEffect> effects;
        private final Map<String,String> modes;
        private Prepared(V03ProviderRuntimeRegistry registry, LocalFakeSitOwnership store, ProductTestPolicy policy,
                Map<String,SitOperationEffect> effects, Map<String,String> modes) {
            this.registry=registry; this.store=store; this.policy=policy; this.effects=effects; this.modes=modes;
        }
        public SitExecutionScope open(String runId) {
            var shared = policy.sharedState();
            if (shared == null) return null;
            var token = "owned".equals(shared.dataAccess())
                    ? store.claim(shared.environmentId(), shared.namespace(), shared.owner(), runId) : null;
            return new SitExecutionScope(shared, runId, store, token);
        }
        public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context, SitExecutionScope scope) {
            var adapter = (SitPolicyAwareProvider) registry.resolve(step.providerType());
            if (!(adapter.simulated() ? "simulated" : "real").equals(modes.get(step.target()))
                    || !Objects.equals(adapter.sitEffect(step), effects.get(key(step))))
                throw new IllegalStateException("provider mode or effect changed after policy preparation");
            if (policy.nativeExecution()) {
                var target = context.targets().get(step.target());
                ReviewedNativeCapability.fromContract(target.providerContract()).validateBindings(target.bindings());
            }
            return adapter.executeReviewed(step, context, scope);
        }
        public Map<String,String> observedProviderModes() { return modes; }
    }
}
