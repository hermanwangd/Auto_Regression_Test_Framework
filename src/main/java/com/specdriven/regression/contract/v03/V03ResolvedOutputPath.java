package com.specdriven.regression.contract.v03;

/** One contract-authoritative output lookup, including any nested path still to materialize. */
public record V03ResolvedOutputPath(
        String declaredPath,
        V03OutputDefinition definition,
        String remainingPath) {

    public boolean exact() {
        return remainingPath == null || remainingPath.isBlank();
    }

    public V03ValueType effectiveValueType() {
        return exact() ? definition.valueType() : V03ValueType.ANY;
    }
}
