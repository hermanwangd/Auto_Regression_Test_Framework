package com.specdriven.regression.sit;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SitAttemptMetadataTest {
    @ParameterizedTest @ValueSource(strings={"throw","timeout","malformed"})
    void retainsResolvedModeWheneverObservationWasInvoked(String outcome) {
        AtomicInteger calls = new AtomicInteger();
        SitReadinessAdapter adapter = new SitReadinessAdapter() {
            public String id() { return "probe"; }
            public String version() { return "test-1"; }
            public boolean simulated() { return true; }
            public SitReadinessObservation check(SitReadinessRequest request) throws Exception {
                calls.incrementAndGet();
                if (outcome.equals("throw")) throw new IllegalStateException("synthetic failure");
                if (outcome.equals("timeout")) Thread.sleep(5000);
                return null;
            }
        };
        var report = service(adapter).evaluate(config(200),true,Map.of("orders","simulated"));
        assertThat(report.ready()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(report.checks().get(0).adapterVersion()).isEqualTo("test-1");
        assertThat(report.checks().get(0).simulated()).isTrue();
    }

    @Test void invalidAdapterVersionCannotInvokeObservationAndDisappearFromEvidence() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = new SitReadinessAdapter() {
            public String id() { return "probe"; }
            public String version() { return ""; }
            public boolean simulated() { return true; }
            public SitReadinessObservation check(SitReadinessRequest request) {
                calls.incrementAndGet(); return ready();
            }
        };
        var report = service(adapter).evaluate(config(1000),true,Map.of("orders","simulated"));
        assertThat(report.ready()).isFalse(); assertThat(calls).hasValue(0);
        assertThat(report.checks().get(0).code()).isEqualTo("MALFORMED_ADAPTER_METADATA");
    }

    @Test void timeoutClosesAttemptGateBeforeSlowMetadataCanStartALateObservation() throws Exception {
        CountDownLatch metadataEntered = new CountDownLatch(1);
        CountDownLatch releaseMetadata = new CountDownLatch(1);
        CountDownLatch metadataCompleted = new CountDownLatch(1);
        CountDownLatch callback = new CountDownLatch(1);
        var adapter = new SitReadinessAdapter() {
            public String id() { return "probe"; }
            public String version() {
                metadataEntered.countDown();
                boolean released=false;
                while (!released) {
                    try { releaseMetadata.await(); released=true; }
                    catch (InterruptedException ignored) { /* Exercise trusted plugin's uncooperative metadata. */ }
                }
                return "test-1";
            }
            public boolean simulated() { metadataCompleted.countDown(); return true; }
            public SitReadinessObservation check(SitReadinessRequest request) { callback.countDown(); return ready(); }
        };
        try {
            var report=service(adapter).evaluate(config(200),true,Map.of("orders","simulated"));
            assertThat(metadataEntered.getCount()).isZero();
            assertThat(report.checks().get(0).code()).isEqualTo("TIMEOUT");
            releaseMetadata.countDown();
            assertThat(metadataCompleted.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(callback.await(500,TimeUnit.MILLISECONDS)).isFalse();
        } finally { releaseMetadata.countDown(); }
    }
    private SitReadinessService service(SitReadinessAdapter adapter) {
        return new SitReadinessService(new SitReadinessRegistry(List.of(adapter)));
    }
    private Map<String,Object> config(int timeout) {
        return Map.of("timeout_ms",timeout,"checks",List.of(Map.of("id","orders","kind","test_data",
                "adapter","probe","expected_version","v1","config",Map.of())));
    }
    private SitReadinessObservation ready() {
        return new SitReadinessObservation(SitReadinessState.READY,"v1",Instant.now(),Map.of("source","local"),"");
    }
}
