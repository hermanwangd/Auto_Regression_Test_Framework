package com.specdriven.regression.sit;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SitModePreflightTest {
    @Test void declaredRealModeRejectsSimulatedAdapterBeforeObservation() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = new SitReadinessAdapter() {
            public String id() { return "probe"; }
            public String version() { return "1"; }
            public boolean simulated() { return true; }
            public SitReadinessObservation check(SitReadinessRequest request) {
                calls.incrementAndGet();
                return new SitReadinessObservation(SitReadinessState.READY,"v1",Instant.now(),Map.of("kind","fake"),"");
            }
        };
        var configuration = Map.of("timeout_ms",1000,"checks", List.of(Map.of("id","dataset","kind","test_data",
                "adapter","probe","expected_version","v1","config",Map.of())));
        var result = new SitReadinessService(new SitReadinessRegistry(List.of(adapter)))
                .evaluate(configuration,true,Map.of("dataset","real"));
        assertThat(result.ready()).isFalse();
        assertThat(result.toMap().toString()).contains("MODE_MISMATCH");
        assertThat(calls).hasValue(0);
    }
}
