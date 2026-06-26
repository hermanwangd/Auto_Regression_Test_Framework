package com.specdriven.regression.mapping;

import java.util.List;

public record RpRuMappingValidationReport(
        boolean valid,
        boolean executionBlocked,
        List<ReleaseUnitMapping> releaseUnits,
        List<RpRuMappingGap> gaps,
        boolean membershipInferred) {
}
