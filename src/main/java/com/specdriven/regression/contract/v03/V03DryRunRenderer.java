package com.specdriven.regression.contract.v03;

import java.io.PrintStream;

public final class V03DryRunRenderer {

    public void render(PrintStream out, V03ExecutionPlan plan) {
        out.println("suite_id: " + plan.suiteId());
        out.println("plan_digest: " + plan.planDigest());
        out.println("resolved_execution_plan:");
        for (V03CompiledTestCase testCase : plan.tests()) {
            render(out, testCase.setup());
            render(out, testCase.execute());
            render(out, testCase.verify());
            render(out, testCase.cleanup());
        }
    }

    private void render(PrintStream out, Iterable<V03ExecutionStep> steps) {
        for (V03ExecutionStep step : steps) {
            out.println("  - test_case_id: " + step.testCaseId());
            out.println("    kind: " + step.kind().name().toLowerCase(java.util.Locale.ROOT));
            out.println("    phase: " + step.phase());
            out.println("    id: " + step.id());
            if (!step.target().isBlank()) {
                out.println("    target: " + step.target());
                out.println("    provider_contract: " + step.providerContract());
                out.println("    provider_type: " + step.providerType());
                out.println("    runtime_mode: " + step.runtimeMode());
            }
            out.println("    profile: " + step.profile());
            out.println("    operation: " + step.operation());
        }
    }
}
