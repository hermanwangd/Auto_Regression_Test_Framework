package com.specdriven.regression.contract.v03;

/** Runtime output selected through the same exact/longest-prefix semantics as contract lookup. */
public record V03ResolvedProducedOutput(V03ProducedOutput output, String remainingPath) {
    public boolean exact() {
        return remainingPath == null || remainingPath.isBlank();
    }
}
