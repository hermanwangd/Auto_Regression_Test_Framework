package com.specdriven.regression.contract.v03;

public interface V03ProviderRuntimeAdapter {

    /** Releases this case's resources before another case in the same run can start. */
    default void endCase(V03ExecutionContext context, String testCaseId) {}

    /** Releases resources held by this adapter for one finished run. */
    default void endRun(V03ExecutionContext context) {}

    String providerType();

    boolean supports(String providerContract, String operation);

    V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context);
}
