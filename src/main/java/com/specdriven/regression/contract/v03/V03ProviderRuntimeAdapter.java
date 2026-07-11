package com.specdriven.regression.contract.v03;

public interface V03ProviderRuntimeAdapter {

    String providerType();

    boolean supports(String providerContract, String operation);

    V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context);
}
