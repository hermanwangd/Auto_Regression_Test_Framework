package com.specdriven.regression.sit;

import java.time.Instant;
import java.util.Map;

/** Config is an immutable flat map of non-secret adapter-specific string options. */
public record SitReadinessRequest(String id, String kind, String expectedVersion,
        Map<String, String> config, Instant deadline) {
    public SitReadinessRequest { config = Map.copyOf(config); }
}
