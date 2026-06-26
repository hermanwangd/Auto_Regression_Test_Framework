package com.specdriven.regression.mapping;

public record RpRuMappingGap(
        String fieldPath,
        String severity,
        boolean blocksExecution,
        String ownerAction) {
}
