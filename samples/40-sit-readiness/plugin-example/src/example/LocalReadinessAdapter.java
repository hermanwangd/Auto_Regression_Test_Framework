package example;

import com.specdriven.regression.sit.SitReadinessAdapter;
import com.specdriven.regression.sit.SitReadinessObservation;
import com.specdriven.regression.sit.SitReadinessRequest;
import com.specdriven.regression.sit.SitReadinessState;
import java.time.Instant;
import java.util.Map;

/** Example external JAR, deliberately synthetic and local-only. */
public final class LocalReadinessAdapter implements SitReadinessAdapter {
    public String id() { return "example-local"; }
    public String version() { return "example-1"; }
    public boolean simulated() { return true; }

    public SitReadinessObservation check(SitReadinessRequest request) {
        SitReadinessState state = SitReadinessState.valueOf(request.config().getOrDefault("state", "UNKNOWN"));
        return new SitReadinessObservation(state, request.config().getOrDefault("observed_version", ""),
                Instant.now(), Map.of("source", "external local example JAR", "check", request.id()),
                state == SitReadinessState.READY ? "" : "Correct the local example input; no SIT was queried.");
    }
}
