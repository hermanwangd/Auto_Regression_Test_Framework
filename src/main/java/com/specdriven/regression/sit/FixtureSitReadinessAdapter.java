package com.specdriven.regression.sit;

import java.time.Instant;
import java.util.Map;

/** Local deterministic demonstration only. Makes no network or filesystem calls. */
public final class FixtureSitReadinessAdapter implements SitReadinessAdapter {
    @Override public String id() { return "fixture"; }
    @Override public String version() { return "1"; }
    @Override public boolean simulated() { return true; }

    @Override public SitReadinessObservation check(SitReadinessRequest request) {
        var state = SitReadinessState.valueOf(request.config().getOrDefault("state", "UNKNOWN"));
        return new SitReadinessObservation(state, request.config().getOrDefault("observed_version", ""),
                Instant.now(), Map.of("source", "local fixture; no SIT observation", "check", request.id(),
                        "simulated_state", state.name()),
                state == SitReadinessState.READY ? "" : "Correct the local fixture or use an approved read-only SIT adapter.");
    }
}
