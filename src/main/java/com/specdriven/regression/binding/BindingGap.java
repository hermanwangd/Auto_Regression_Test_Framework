package com.specdriven.regression.binding;

public record BindingGap(
        String testCaseId,
        String acId,
        String fieldPath,
        String bindingName,
        String bindingType,
        String ownerAction) {
}
