package com.specdriven.regression.mapping;

public record ReleaseUnitMapping(
        String ruId,
        String repo,
        String unitType,
        String executionMode,
        String environmentRef,
        String adapter) {
}
