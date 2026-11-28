package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Each target owns its mock for the lifetime of one case in one run. */
abstract class RunScopedMockV03Adapter<T extends ProviderRuntime & AutoCloseable>
        extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {
    private record Target(String testCaseId, String name) {}
    private final Map<V03ExecutionContext, Map<Target, T>> runs = new ConcurrentHashMap<>();
    private final Supplier<T> factory;

    RunScopedMockV03Adapter(Supplier<T> factory) {
        this.factory = factory;
    }

    @Override
    public final V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        var targets = runs.computeIfAbsent(context, ignored -> new ConcurrentHashMap<>());
        T runtime = targets.computeIfAbsent(new Target(step.testCaseId(), step.target()), ignored -> factory.get());
        return stepResult(step, runtime.execute(providerContext(step, context), request(step, context)));
    }

    @Override
    public final void endCase(V03ExecutionContext context, String testCaseId) {
        closeTargets(runs.get(context), target -> target.testCaseId().equals(testCaseId));
    }

    @Override
    public final void endRun(V03ExecutionContext context) {
        closeTargets(runs.remove(context), target -> true);
    }

    private void closeTargets(Map<Target, T> targets, Predicate<Target> owned) {
        if (targets == null) return;
        IllegalStateException failure = null;
        for (var entry : targets.entrySet()) {
            if (!owned.test(entry.getKey())) continue;
            try {
                entry.getValue().close();
                targets.remove(entry.getKey(), entry.getValue());
            } catch (Exception error) {
                if (failure == null) failure = new IllegalStateException("Could not release run-owned mock resources.");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
