package com.specdriven.regression.contract.v03;

public record V03BindingDependency(
        String consumerStepId,
        String consumerInput,
        V03ReferenceKind referenceKind,
        String producerTarget,
        String producerStepId,
        String producerOutput) {
}
