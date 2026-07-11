package com.specdriven.regression.contract.v03;

public record V03OutputDefinition(
        V03ValueType valueType,
        V03Sensitivity sensitivity,
        boolean bindable,
        boolean evidenceIncluded) {

    static V03OutputDefinition legacy(boolean bindable) {
        return new V03OutputDefinition(V03ValueType.ANY, V03Sensitivity.PUBLIC, bindable, true);
    }
}
