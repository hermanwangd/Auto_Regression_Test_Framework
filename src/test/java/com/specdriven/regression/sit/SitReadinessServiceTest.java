package com.specdriven.regression.sit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SitReadinessServiceTest {
    @Test
    void readyRequiresEvidenceAndMatchingVersion() {
        var report = service(observation(SitReadinessState.READY, "v1")).evaluate(config("custom", 500), true);
        assertThat(report.state()).isEqualTo(SitReadinessState.READY);
        assertThat(report.checks().get(0).adapterVersion()).isEqualTo("test-1");
        assertThat(report.checks().get(0).observedVersion()).isEqualTo("v1");
    }

    @Test
    void preservesMissingUnhealthyAndBlockedWithActionableGaps() {
        for (var state : List.of(SitReadinessState.MISSING, SitReadinessState.INVALID, SitReadinessState.BLOCKED)) {
            var report = service(observation(state, "v1")).evaluate(config("custom", 500), true);
            assertThat(report.state()).isEqualTo(state);
            assertThat(report.checks().get(0).ownerAction()).isEqualTo("Restore the isolated dataset.");
        }
    }

    @Test
    void rejectsVersionMismatchEvenWhenAdapterClaimsReady() {
        var report = service(observation(SitReadinessState.READY, "v0")).evaluate(config("custom", 500), true);
        assertThat(report.state()).isEqualTo(SitReadinessState.INVALID);
        assertThat(report.checks().get(0).code()).isEqualTo("VERSION_MISMATCH");
    }

    @Test
    void missingOrAmbiguousPluginNeverPasses() {
        assertThat(new SitReadinessService(new SitReadinessRegistry(List.of()))
                .evaluate(config("missing", 500), true).state()).isEqualTo(SitReadinessState.UNKNOWN);
        var adapter = adapter(observation(SitReadinessState.READY, "v1"));
        assertThat(new SitReadinessService(new SitReadinessRegistry(List.of(adapter, adapter)))
                .evaluate(config("custom", 500), true).state()).isEqualTo(SitReadinessState.UNKNOWN);
    }

    @Test
    void malformedOrStaleObservationsNeverPass() {
        for (var observation : java.util.Arrays.asList(null,
                new SitReadinessObservation(null, "v1", Instant.now(), Map.of("proof", "found"), ""),
                new SitReadinessObservation(SitReadinessState.READY, "v1", Instant.now(), Map.of(), ""),
                new SitReadinessObservation(SitReadinessState.READY, "v1", Instant.EPOCH, Map.of("proof", "old"), ""))) {
            assertThat(service(observation).evaluate(config("custom", 500), true).state())
                    .isEqualTo(SitReadinessState.UNKNOWN);
        }
    }

    @Test
    void malformedConfigurationFailsClosed() {
        var service = service(observation(SitReadinessState.READY, "v1"));
        for (Object config : List.of("wrong", Map.of(), Map.of("timeout_ms", 0, "checks", List.of()),
                Map.of("timeout_ms", 60001, "checks", List.of()))) {
            assertThat(service.evaluate(config, true).state()).isEqualTo(SitReadinessState.INVALID);
        }
    }

    @Test
    void exceptionIsUnknownAndDoesNotLeakExceptionText() {
        var adapter = new TestAdapter() {
            @Override public SitReadinessObservation check(SitReadinessRequest request) {
                throw new IllegalStateException("password=do-not-publish");
            }
        };
        var report = new SitReadinessService(new SitReadinessRegistry(List.of(adapter)))
                .evaluate(config("custom", 500), true);
        assertThat(report.state()).isEqualTo(SitReadinessState.UNKNOWN);
        assertThat(report.toMap().toString()).doesNotContain("do-not-publish");
    }

    @Test
    void totalDeadlineBoundsWaitAndCancelsCheck() throws Exception {
        var interrupted = new AtomicBoolean();
        var calls = new AtomicInteger();
        var adapter = new TestAdapter() {
            @Override public SitReadinessObservation check(SitReadinessRequest request) throws Exception {
                calls.incrementAndGet();
                try { Thread.sleep(5000); } catch (InterruptedException e) { interrupted.set(true); throw e; }
                return observation(SitReadinessState.READY, "v1");
            }
        };
        var checks = new java.util.ArrayList<Map<String, Object>>();
        for (String id : List.of("first", "second", "third")) checks.add(Map.of(
                "id", id, "kind", "external_system", "adapter", "custom", "expected_version", "v1", "config", Map.of()));
        long start = System.nanoTime();
        var report = new SitReadinessService(new SitReadinessRegistry(List.of(adapter)))
                .evaluate(Map.of("timeout_ms", 50, "checks", checks), true);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1500);
        assertThat(report.state()).isEqualTo(SitReadinessState.UNKNOWN);
        assertThat(report.checks().get(0).code()).isEqualTo("TIMEOUT");
        assertThat(report.checks()).extracting(SitReadinessReport.Check::code)
                .containsExactly("TIMEOUT", "DEADLINE_EXHAUSTED", "DEADLINE_EXHAUSTED");
        assertThat(calls).hasValue(1);
        for (int i = 0; i < 50 && !interrupted.get(); i++) Thread.sleep(10);
        assertThat(interrupted).isTrue();
    }

    @Test
    void fixtureCannotQualifyAsReleaseEvidence() {
        var config = Map.of("timeout_ms", 500, "checks", List.of(Map.of(
                "id", "orders", "kind", "test_data", "adapter", "fixture", "expected_version", "v1",
                "config", Map.of("state", "READY", "observed_version", "v1"))));
        assertThat(new SitReadinessService().evaluate(config, true).state()).isEqualTo(SitReadinessState.READY);
        assertThat(new SitReadinessService().evaluate(config, false).state()).isEqualTo(SitReadinessState.BLOCKED);
    }

    @Test
    void readyWithoutObservedVersionNeverPasses() {
        assertThat(service(observation(SitReadinessState.READY, ""))
                .evaluate(config("custom", 500), true).state()).isEqualTo(SitReadinessState.UNKNOWN);
    }

    private SitReadinessService service(SitReadinessObservation observation) {
        return new SitReadinessService(new SitReadinessRegistry(List.of(adapter(observation))));
    }

    private TestAdapter adapter(SitReadinessObservation observation) {
        return new TestAdapter() {
            @Override public SitReadinessObservation check(SitReadinessRequest request) {
                return observation == null ? null : new SitReadinessObservation(observation.state(),
                        observation.observedVersion(), observation.observedAt().equals(Instant.EPOCH)
                                ? Instant.EPOCH : Instant.now(), observation.evidence(), observation.ownerAction());
            }
        };
    }

    private SitReadinessObservation observation(SitReadinessState state, String version) {
        return new SitReadinessObservation(state, version, Instant.now(), Map.of("dataset", "orders-namespace-17"),
                "Restore the isolated dataset.");
    }

    static Map<String, Object> config(String adapter, long timeout) {
        return Map.of("timeout_ms", timeout, "checks", List.of(Map.of(
                "id", "orders", "kind", "test_data", "adapter", adapter, "expected_version", "v1", "config", Map.of())));
    }

    abstract static class TestAdapter implements SitReadinessAdapter {
        @Override public String id() { return "custom"; }
        @Override public String version() { return "test-1"; }
    }
}
