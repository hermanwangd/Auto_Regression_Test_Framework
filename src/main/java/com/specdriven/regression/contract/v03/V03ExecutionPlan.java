package com.specdriven.regression.contract.v03;

import java.util.List;

public record V03ExecutionPlan(
        String suiteId,
        String profile,
        List<V03ResolvedTarget> targets,
        List<V03ExecutionStep> steps,
        String planDigest) {

    public static V03ExecutionPlan from(V03CompiledSuite compiled) {
        List<V03ExecutionStep> steps = new java.util.ArrayList<>();
        for (V03CompiledTestCase test : compiled.tests()) {
            steps.addAll(test.setup());
            steps.addAll(test.execute());
            steps.addAll(test.verify());
            steps.addAll(test.cleanup());
        }
        return new V03ExecutionPlan(compiled.suiteId(), compiled.profile(),
                List.copyOf(compiled.targets().values()), List.copyOf(steps), compiled.planDigest());
    }
}
