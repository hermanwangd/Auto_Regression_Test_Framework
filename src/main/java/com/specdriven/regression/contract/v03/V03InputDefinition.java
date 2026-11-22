package com.specdriven.regression.contract.v03;

import java.util.Set;

public record V03InputDefinition(
        boolean required,
        V03ValueType valueType,
        Set<V03ReferenceKind> referenceKinds,
        V03Sensitivity sensitivity) {

    public V03InputDefinition {
        referenceKinds = Set.copyOf(referenceKinds);
    }

    static V03InputDefinition legacy(boolean required) {
        return new V03InputDefinition(required, V03ValueType.ANY, Set.of(
                V03ReferenceKind.LITERAL, V03ReferenceKind.ARTIFACT, V03ReferenceKind.STEP,
                V03ReferenceKind.GENERATED, V03ReferenceKind.ENV), V03Sensitivity.PUBLIC);
    }
}
