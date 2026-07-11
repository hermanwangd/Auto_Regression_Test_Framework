package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ExecutionStep;
import com.specdriven.regression.contract.v03.V03ProviderRuntimeAdapter;
import com.specdriven.regression.contract.v03.V03StepResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public class PollingObserverV03Adapter extends AbstractProviderRuntimeV03Adapter implements V03ProviderRuntimeAdapter {

    private static final Set<String> OPERATIONS = Set.of(
            "observe_condition",
            "event_published",
            "db_record_exists");

    private final Yaml yaml = new Yaml();

    @Override
    public String providerType() {
        return "polling_observer";
    }

    @Override
    public boolean supports(String providerContract, String operation) {
        return "polling_observer.v0.3".equals(providerContract) && OPERATIONS.contains(operation);
    }

    @Override
    public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
        Instant start = Instant.now();
        String evidenceRef = "polling/" + safe(step.id()) + ".yaml";
        Map<String, Object> expected;
        try {
            expected = mapValue(readYaml(context.suiteRoot().resolve(artifactRef(step.inputs().get("expected_ref"))).normalize()));
        } catch (UncheckedIOException e) {
            writePollingEvidence(context.runDir().resolve(evidenceRef), step, start, Instant.now(), 1,
                    Map.of(), "failed", "POLLING_ARTIFACT_MISSING");
            return new V03StepResult(
                    step.id(),
                    "failed",
                    Map.of("matched", false, "attempts", 1, "last_observed_ref", evidenceRef),
                    List.of(evidenceRef),
                    "EXPECTED_ARTIFACT_MISSING",
                    e.getMessage());
        }

        Duration timeout = duration(step.inputs().get("timeout"), Duration.ofSeconds(1));
        Duration pollInterval = duration(step.inputs().get("poll_interval"), Duration.ZERO);
        if (timeout.isNegative() || pollInterval.isNegative()) {
            writePollingEvidence(context.runDir().resolve(evidenceRef), step, start, Instant.now(), 1,
                    Map.of(), "failed", "INVALID_POLLING_CONFIG");
            return new V03StepResult(
                    step.id(),
                    "failed",
                    Map.of("matched", false, "attempts", 1, "last_observed_ref", evidenceRef),
                    List.of(evidenceRef),
                    "INVALID_POLLING_CONFIG",
                    "Polling timeout and poll_interval must be non-negative ISO-8601 durations.");
        }

        Map<String, Object> actual = Map.of();
        boolean matched = false;
        int attempts = 0;
        Instant end = start;
        Instant deadline = start.plus(timeout);
        while (true) {
            attempts++;
            try {
                actual = mapValue(readYaml(context.suiteRoot().resolve(artifactRef(step.inputs().get("actual_ref"))).normalize()));
            } catch (UncheckedIOException e) {
                writePollingEvidence(context.runDir().resolve(evidenceRef), step, start, Instant.now(), attempts,
                        actual, "failed", "POLLING_ARTIFACT_MISSING");
                return new V03StepResult(
                        step.id(),
                        "failed",
                        Map.of("matched", false, "attempts", attempts, "last_observed_ref", evidenceRef),
                        List.of(evidenceRef),
                        "ACTUAL_ARTIFACT_MISSING",
                        e.getMessage());
            }
            matched = containsExpected(actual, expected);
            end = Instant.now();
            if (matched || !end.isBefore(deadline)) {
                break;
            }
            try {
                sleepBeforeNextAttempt(pollInterval, deadline);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                end = Instant.now();
                writePollingEvidence(context.runDir().resolve(evidenceRef), step, start, end, attempts,
                        actual, "failed", "POLLING_INTERRUPTED");
                return new V03StepResult(
                        step.id(),
                        "failed",
                        Map.of("matched", false, "attempts", attempts, "last_observed_ref", evidenceRef),
                        List.of(evidenceRef),
                        "POLLING_INTERRUPTED",
                        "Polling was interrupted before expected state was observed.");
            }
        }
        String status = matched ? "passed" : "failed";
        String failureCode = matched ? "" : "POLLING_TIMEOUT";
        writePollingEvidence(context.runDir().resolve(evidenceRef), step, start, end, attempts, actual, status, failureCode);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("matched", matched);
        outputs.put("attempts", attempts);
        outputs.put("last_observed_ref", evidenceRef);
        outputs.put("polling_evidence_ref", evidenceRef);
        outputs.put("observation_start", start.toString());
        outputs.put("observation_end", end.toString());
        outputs.put("timeout", timeout.toString());
        outputs.put("poll_interval", pollInterval.toString());
        return new V03StepResult(
                step.id(),
                status,
                outputs,
                List.of(evidenceRef),
                failureCode,
                matched ? "Polling observation matched expected state." : "Polling timed out before expected state was observed.");
    }

    private void sleepBeforeNextAttempt(Duration pollInterval, Instant deadline) throws InterruptedException {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0) {
            return;
        }
        long requestedMillis = pollInterval.toMillis();
        long sleepMillis = requestedMillis <= 0 ? 1 : Math.min(requestedMillis, remainingMillis);
        Thread.sleep(Math.max(1, sleepMillis));
    }

    private void writePollingEvidence(
            Path path,
            V03ExecutionStep step,
            Instant start,
            Instant end,
            int attempts,
            Map<String, Object> lastObserved,
            String status,
            String failureCode) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("verifier_type: ").append(step.operation()).append('\n');
        evidence.append("timeout: ").append(stringValue(step.inputs().get("timeout"))).append('\n');
        evidence.append("poll_interval: ").append(stringValue(step.inputs().get("poll_interval"))).append('\n');
        evidence.append("attempts: ").append(attempts).append('\n');
        evidence.append("observation_start: \"").append(start).append("\"\n");
        evidence.append("observation_end: \"").append(end).append("\"\n");
        evidence.append("last_observed_value:\n");
        for (Map.Entry<String, Object> entry : lastObserved.entrySet()) {
            evidence.append("  ").append(entry.getKey()).append(": ").append(stringValue(entry.getValue())).append('\n');
        }
        evidence.append("final_status: ").append(status).append('\n');
        if (!failureCode.isBlank()) {
            evidence.append("failure_code: ").append(failureCode).append('\n');
        }
        write(path, evidence.toString());
    }

    private boolean containsExpected(Object actual, Object expected) {
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
                if (!actualMap.containsKey(entry.getKey())) {
                    return false;
                }
                if (!containsExpected(actualMap.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof Collection<?> expectedCollection && actual instanceof Collection<?> actualCollection) {
            return actualCollection.containsAll(expectedCollection);
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    private Object readYaml(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read polling artifact `" + path + "`.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return Map.of();
    }

    private Path artifactRef(Object value) {
        String text = stringValue(value);
        return Path.of(text.startsWith("artifact://") ? text.substring("artifact://".length()) : text);
    }

    private Duration duration(Object value, Duration defaultValue) {
        String text = stringValue(value);
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Duration.parse(text);
        } catch (RuntimeException e) {
            return Duration.ofMillis(-1);
        }
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write polling evidence `" + path + "`.", e);
        }
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
