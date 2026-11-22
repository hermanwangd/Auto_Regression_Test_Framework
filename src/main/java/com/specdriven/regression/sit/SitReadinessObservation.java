package com.specdriven.regression.sit;

import java.time.Instant;
import java.util.Map;

/** Evidence contains concise observed facts, not evidence paths or credentials. */
public record SitReadinessObservation(SitReadinessState state, String observedVersion,
        Instant observedAt, Map<String, String> evidence, String ownerAction) {
    public SitReadinessObservation {
        if (evidence != null) evidence = Map.copyOf(evidence);
    }
}
