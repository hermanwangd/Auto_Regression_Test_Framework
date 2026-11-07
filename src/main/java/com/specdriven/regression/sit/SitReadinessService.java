package com.specdriven.regression.sit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Fail-closed orchestration; the bound covers adapter discovery, invocation and result validation. */
public final class SitReadinessService {
    // No unbounded queue or per-run thread leaks when an uncooperative trusted plugin ignores cancellation.
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "sit-readiness");
                thread.setDaemon(true);
                return thread;
            });
    private final Supplier<SitReadinessRegistry> registry;

    public SitReadinessService() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        registry = () -> SitReadinessRegistry.discover(loader);
    }

    public SitReadinessService(SitReadinessRegistry registry) { this.registry = () -> registry; }

    /** allowSimulated is true only for local, framework-verification-only runs. */
    public SitReadinessReport evaluate(Object rawConfig, boolean allowSimulated) {
        return evaluate(rawConfig, allowSimulated, null);
    }

    public SitReadinessReport evaluate(Object rawConfig, boolean allowSimulated, Map<String,String> expectedModes) {
        Instant started = Instant.now();
        long startedNanos = System.nanoTime();
        Configuration config;
        try {
            config = parse(rawConfig);
            if (expectedModes != null && (!expectedModes.keySet().equals(config.checks().stream()
                    .map(CheckConfig::id).collect(java.util.stream.Collectors.toSet()))
                    || expectedModes.values().stream().anyMatch(mode -> !Set.of("real","simulated").contains(mode))))
                throw new IllegalArgumentException("Dependency modes must exactly cover readiness check IDs with real or simulated.");
        }
        catch (IllegalArgumentException error) {
            return new SitReadinessReport(SitReadinessState.INVALID, started, Instant.now(), List.of(
                    gap(new CheckConfig("configuration", "configuration", "", "", Map.of()),
                            SitReadinessState.INVALID, "INVALID_CONFIGURATION", error.getMessage())));
        }
        long deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
        Instant deadline = started.plusMillis(config.timeoutMs());
        List<SitReadinessReport.Check> results = new ArrayList<>();
        boolean stop = false;
        for (CheckConfig check : config.checks()) {
            if (stop || System.nanoTime() >= deadlineNanos) {
                results.add(gap(check, SitReadinessState.UNKNOWN, "DEADLINE_EXHAUSTED",
                        "Refresh readiness after the previous check completes; review the total timeout."));
                continue;
            }
            Future<SitReadinessReport.Check> future = null;
            AttemptGate attempt = new AttemptGate(deadlineNanos);
            try {
                future = WORKERS.submit(() -> observe(check, deadline, allowSimulated,
                        expectedModes == null ? null : expectedModes.get(check.id()), attempt));
                var result = future.get(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (System.nanoTime() >= deadlineNanos) throw new TimeoutException();
                results.add(result);
            } catch (TimeoutException error) {
                results.add(gap(check, SitReadinessState.UNKNOWN, "TIMEOUT",
                        "Check adapter/client timeouts and dependency health; obtain a fresh observation.", attempt.closeSnapshot()));
                stop = true;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                results.add(gap(check, SitReadinessState.UNKNOWN, "INTERRUPTED", "Rerun readiness after cancellation.", attempt.closeSnapshot()));
                stop = true;
            } catch (RejectedExecutionException error) {
                results.add(gap(check, SitReadinessState.UNKNOWN, "ADAPTER_CAPACITY",
                        "Stop unresponsive adapters before retrying; readiness worker capacity is exhausted.", attempt.closeSnapshot()));
                stop = true;
            } catch (ExecutionException error) {
                results.add(gap(check, SitReadinessState.UNKNOWN, "ADAPTER_ERROR",
                        "Inspect the installed adapter and its read-only access; refresh readiness after repair.", attempt.closeSnapshot()));
            } finally {
                attempt.closeSnapshot();
                if (future != null && !future.isDone()) future.cancel(true);
            }
        }
        SitReadinessState state = SitReadinessState.READY;
        for (var candidate : List.of(SitReadinessState.BLOCKED, SitReadinessState.INVALID,
                SitReadinessState.MISSING, SitReadinessState.UNKNOWN)) {
            if (results.stream().anyMatch(check -> check.state() == candidate)) { state = candidate; break; }
        }
        return new SitReadinessReport(state, started, Instant.now(), results);
    }

    private SitReadinessReport.Check observe(CheckConfig check, Instant deadline, boolean allowSimulated,
            String expectedMode, AttemptGate attempt) throws Exception {
        SitReadinessAdapter adapter;
        try { adapter = registry.get().resolve(check.adapter()); }
        catch (IllegalArgumentException | java.util.ServiceConfigurationError error) {
            return gap(check, SitReadinessState.UNKNOWN, "ADAPTER_UNAVAILABLE",
                    "Install exactly one approved SPI adapter for this ID and check its service registration.");
        }
        String adapterVersion = adapter.version();
        if (!validText(adapterVersion)) return gap(check, SitReadinessState.UNKNOWN, "MALFORMED_ADAPTER_METADATA",
                "Fix the adapter to declare a bounded nonblank version before invoking an observation.");
        boolean simulated = adapter.simulated();
        if (expectedMode != null && !expectedMode.equals(simulated ? "simulated" : "real")) {
            return new SitReadinessReport.Check(check.id(), check.kind(), check.adapter(), adapterVersion, simulated,
                    SitReadinessState.BLOCKED, check.expectedVersion(), "", null, Map.of(), "MODE_MISMATCH",
                    "Use the reviewed dependency mode; no observation was invoked and no fallback is allowed.");
        }
        AttemptMetadata metadata = new AttemptMetadata(adapterVersion, simulated);
        if (!attempt.begin(metadata)) return gap(check, SitReadinessState.UNKNOWN, "DEADLINE_EXHAUSTED",
                "Refresh readiness; metadata resolved after the observation deadline or cancellation.");
        Instant checkStarted = Instant.now();
        SitReadinessObservation observation = adapter.check(new SitReadinessRequest(
                check.id(), check.kind(), check.expectedVersion(), check.config(), deadline));
        if (observation == null || observation.state() == null
                || observation.observedAt() == null || observation.observedAt().isBefore(checkStarted)
                || observation.observedAt().isAfter(Instant.now()) || observation.evidence() == null
                || observation.evidence().isEmpty() || !validFacts(observation.evidence())
                || observation.observedVersion() == null || observation.observedVersion().length() > 256
                || (observation.state() == SitReadinessState.READY && observation.observedVersion().isBlank())
                || observation.ownerAction() == null || observation.ownerAction().length() > 2048
                || (observation.state() != SitReadinessState.READY && observation.ownerAction().isBlank())) {
            return gap(check, SitReadinessState.UNKNOWN, "MALFORMED_OBSERVATION",
                    "Fix the adapter to return a fresh typed observation, version, bounded evidence facts and an owner action.", metadata);
        }
        SitReadinessState state = observation.state();
        String code = state == SitReadinessState.READY ? "" : "OBSERVED_" + state.name();
        String action = observation.ownerAction();
        if (state == SitReadinessState.READY && !check.expectedVersion().equals(observation.observedVersion())) {
            state = SitReadinessState.INVALID;
            code = "VERSION_MISMATCH";
            action = "Ask the dependency/data owner to provide the expected version, then refresh readiness.";
        }
        if (simulated && !allowSimulated) {
            state = SitReadinessState.BLOCKED;
            code = "SIMULATED_EVIDENCE_FORBIDDEN";
            action = "Replace the fixture with an approved read-only adapter; simulated facts cannot verify SIT or release readiness.";
        }
        return new SitReadinessReport.Check(check.id(), check.kind(), check.adapter(), adapterVersion, simulated,
                state, check.expectedVersion(), observation.observedVersion(), observation.observedAt(),
                observation.evidence(), code, action);
    }

    private SitReadinessReport.Check gap(CheckConfig check, SitReadinessState state, String code, String action) {
        return gap(check, state, code, action, null);
    }

    private SitReadinessReport.Check gap(CheckConfig check, SitReadinessState state, String code, String action,
            AttemptMetadata metadata) {
        return new SitReadinessReport.Check(check.id(), check.kind(), check.adapter(),
                metadata == null ? "" : metadata.version(), metadata != null && metadata.simulated(),
                state, check.expectedVersion(), "", null, Map.of(), code, action);
    }

    private record AttemptMetadata(String version, boolean simulated) { }

    /** The cutoff and published attempt are atomic; no plugin callback runs under this lock. */
    private static final class AttemptGate {
        private final long deadlineNanos;
        private boolean closed;
        private AttemptMetadata metadata;
        private AttemptGate(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }
        private synchronized boolean begin(AttemptMetadata value) {
            if (closed || System.nanoTime() >= deadlineNanos) return false;
            metadata = value;
            return true;
        }
        private synchronized AttemptMetadata closeSnapshot() {
            closed = true;
            return metadata;
        }
    }

    private Configuration parse(Object raw) {
        if (!(raw instanceof Map<?, ?> config) || !config.keySet().equals(Set.of("timeout_ms", "checks"))) {
            throw new IllegalArgumentException("Set sit_readiness to a map with exactly timeout_ms and a nonempty checks list.");
        }
        Object timeout = config.get("timeout_ms");
        if (!(timeout instanceof Integer || timeout instanceof Long) || ((Number) timeout).longValue() < 1
                || ((Number) timeout).longValue() > 60000) {
            throw new IllegalArgumentException("Set timeout_ms to an integer from 1 through 60000 (total preflight budget).");
        }
        if (!(config.get("checks") instanceof List<?> checks) || checks.isEmpty() || checks.size() > 32) {
            throw new IllegalArgumentException("Declare between 1 and 32 explicit readiness checks.");
        }
        Set<String> ids = new HashSet<>();
        List<CheckConfig> parsed = new ArrayList<>();
        for (Object value : checks) {
            if (!(value instanceof Map<?, ?> check) || !check.keySet().equals(
                    Set.of("id", "kind", "adapter", "expected_version", "config"))
                    || !identifier(check.get("id")) || !identifier(check.get("adapter"))
                    || !(check.get("kind") instanceof String kind)
                    || !Set.of("test_data", "external_system", "dependency").contains(kind)
                    || !validText(check.get("expected_version")) || !(check.get("config") instanceof Map<?, ?> options)
                    || !validFacts(options)) {
                throw new IllegalArgumentException("Each check needs id, kind (test_data/external_system/dependency), adapter, expected_version and a flat string config map.");
            }
            String id = (String) check.get("id");
            if (!ids.add(id)) throw new IllegalArgumentException("Give each readiness check a unique id.");
            Map<String, String> optionsCopy = new java.util.LinkedHashMap<>();
            options.forEach((key, option) -> optionsCopy.put((String) key, (String) option));
            parsed.add(new CheckConfig(id, kind, (String) check.get("adapter"),
                    (String) check.get("expected_version"), Map.copyOf(optionsCopy)));
        }
        return new Configuration(((Number) timeout).longValue(), List.copyOf(parsed));
    }

    private boolean identifier(Object value) {
        return value instanceof String text && text.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    }

    private boolean validText(Object value) { return value instanceof String text && !text.isBlank() && text.length() <= 256; }

    private boolean validFacts(Map<?, ?> facts) {
        return facts.size() <= 32 && facts.entrySet().stream().allMatch(entry -> validText(entry.getKey())
                && entry.getValue() instanceof String text && !text.isBlank() && text.length() <= 2048);
    }

    private record CheckConfig(String id, String kind, String adapter, String expectedVersion, Map<String, String> config) { }
    private record Configuration(long timeoutMs, List<CheckConfig> checks) { }
}
