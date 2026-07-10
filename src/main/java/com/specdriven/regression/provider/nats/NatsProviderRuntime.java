package com.specdriven.regression.provider.nats;

import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

public class NatsProviderRuntime implements ProviderRuntime {

    private final Map<String, List<ObservedEvent>> eventsByBusSubject = new ConcurrentHashMap<>();
    private final Yaml yaml = new Yaml();

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        ConnectionSelection connection = connection(context);
        if (connection.failure() != null) {
            return failed(context, request, Map.of(), connection.failure(), Observation.empty());
        }
        return switch (request.operation()) {
            case "nats_publish" -> publish(context, request, connection);
            case "nats_observe", "event_published", "event_payload_match" -> observe(context, request, connection);
            default -> failed(
                    context,
                    request,
                    Map.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported NATS operation `" + request.operation() + "`.",
                            "Use nats_publish, nats_observe, event_published, or event_payload_match."),
                    Observation.empty());
        };
    }

    private ProviderOperationResult publish(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection) {
        Instant startedAt = Instant.now();
        String subject = subject(context, request);
        if (subject.isBlank()) {
            return failed(context, request, Map.of(), failure("SUBJECT_MISSING", "TARGET_RESOLUTION_FAILED",
                    "NATS subject is missing.", "Declare subject in DSL parameters or Environment Binding."),
                    Observation.empty());
        }
        Payload payload = payload(context, request);
        if (payload.failure() != null) {
            return failed(context, request, Map.of("subject", subject), payload.failure(), Observation.empty());
        }
        ParsedDuration timeout = duration(context, request, "timeout");
        if (timeout.failure() != null) {
            return failed(context, request, Map.of("subject", subject), timeout.failure(), Observation.empty(subject));
        }
        ParsedDuration pollInterval = duration(context, request, "poll_interval");
        if (pollInterval.failure() != null) {
            return failed(context, request, Map.of("subject", subject), pollInterval.failure(), Observation.empty(subject));
        }
        Instant publishedAt = Instant.now();
        int observedCount = 1;
        if (connection.externalUri() == null) {
            eventsByBusSubject.computeIfAbsent(connection.busKey() + "|" + subject, ignored -> new ArrayList<>())
                    .add(new ObservedEvent(subject, payload.value(), payload.text(), publishedAt));
        } else {
            ExternalObservation externalObservation =
                    ExternalNatsClient.publishAndObserve(connection.externalUri(), subject, payload.text(), timeout.value());
            if (externalObservation.failure() != null) {
                return failed(context, request, Map.of("subject", subject), externalObservation.failure(), Observation.empty(subject));
            }
            if (externalObservation.payloadText().isBlank()) {
                observedCount = 0;
            } else {
                Object observedPayload = parsePayload(externalObservation.payloadText());
                eventsByBusSubject.computeIfAbsent(connection.busKey() + "|" + subject, ignored -> new ArrayList<>())
                        .add(new ObservedEvent(subject, observedPayload, externalObservation.payloadText(), Instant.now()));
            }
        }
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        String evidenceRef = writeEvidence(
                context,
                request,
                new Observation(
                        subject,
                        "publish",
                        startedAt,
                        Instant.now(),
                        timeout.value(),
                        pollInterval.value(),
                        1,
                        observedCount,
                        true,
                        List.of(),
                        payload.value(),
                        null,
                        durationMs,
                        null));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("subject", subject);
        outputs.put("published", true);
        outputs.put("published_at", publishedAt.toString());
        outputs.put("duration_ms", durationMs);
        outputs.put("event_evidence_ref", evidenceRef);
        return ProviderOperationResult.passed(
                outputs,
                List.of(new ProviderEvidence("event_evidence", evidenceRef, true)));
    }

    private ProviderOperationResult observe(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection) {
        Instant startedAt = Instant.now();
        String subject = subject(context, request);
        if (subject.isBlank()) {
            return failed(context, request, Map.of(), failure("SUBJECT_MISSING", "TARGET_RESOLUTION_FAILED",
                    "NATS subject is missing.", "Declare subject in DSL parameters or Environment Binding."),
                    Observation.empty());
        }
        ExpectedPayload expected = expectedPayload(context, request);
        if ("event_payload_match".equals(request.operation()) && expected.failure() != null) {
            return failed(context, request, Map.of("subject", subject), expected.failure(), Observation.empty());
        }
        ParsedDuration timeout = duration(context, request, "timeout");
        if (timeout.failure() != null) {
            return failed(context, request, Map.of("subject", subject), timeout.failure(), Observation.empty(subject));
        }
        ParsedDuration pollInterval = duration(context, request, "poll_interval");
        if (pollInterval.failure() != null) {
            return failed(context, request, Map.of("subject", subject), pollInterval.failure(), Observation.empty(subject));
        }
        ParsedInstant consumeFrom = consumeFrom(request, startedAt);
        if (consumeFrom.failure() != null) {
            return failed(context, request, Map.of("subject", subject), consumeFrom.failure(), Observation.empty(subject));
        }
        Instant deadline = Instant.now().plus(timeout.value());
        int attempts = 0;
        List<ObservedEvent> observed = List.of();
        Match match = Match.notMatched();
        do {
            attempts++;
            observed = eventsAfter(connection.busKey(), subject, consumeFrom.value());
            if (observed.isEmpty() && connection.externalUri() != null) {
                ExternalObservation externalObservation =
                        ExternalNatsClient.observe(connection.externalUri(), subject, pollInterval.value());
                if (externalObservation.failure() != null) {
                    return failed(context, request, Map.of("subject", subject), externalObservation.failure(),
                            Observation.empty(subject));
                }
                if (!externalObservation.payloadText().isBlank()) {
                    Object observedPayload = parsePayload(externalObservation.payloadText());
                    eventsByBusSubject.computeIfAbsent(connection.busKey() + "|" + subject, ignored -> new ArrayList<>())
                            .add(new ObservedEvent(subject, observedPayload, externalObservation.payloadText(), Instant.now()));
                    observed = eventsAfter(connection.busKey(), subject, consumeFrom.value());
                }
            }
            match = match(request.operation(), observed, expected.value());
            if (match.matched()) {
                break;
            }
            sleepUntilNextPoll(deadline, pollInterval.value());
        } while (Instant.now().isBefore(deadline));

        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        ObservedEvent lastObserved = observed.isEmpty() ? null : observed.get(observed.size() - 1);
        ProviderFailure failure = match.matched()
                ? null
                : failureForObserve(request.operation(), timeout.value(), observed);
        Observation observation = new Observation(
                subject,
                consumeFrom.label(),
                consumeFrom.value(),
                Instant.now(),
                timeout.value(),
                pollInterval.value(),
                attempts,
                observed.size(),
                match.matched(),
                match.matchedFields(),
                match.matchedPayload(),
                lastObserved == null ? null : lastObserved.payload(),
                durationMs,
                failure);
        String evidenceRef = writeEvidence(context, request, observation);
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("subject", subject);
        outputs.put("observed_count", observed.size());
        outputs.put("matched", match.matched());
        outputs.put("matched_fields", match.matchedFields());
        outputs.put("duration_ms", durationMs);
        outputs.put("event_evidence_ref", evidenceRef);
        if (match.matched()) {
            return ProviderOperationResult.passed(
                    outputs,
                    List.of(new ProviderEvidence("event_evidence", evidenceRef, true)));
        }
        return ProviderOperationResult.failed(
                outputs,
                List.of(new ProviderEvidence("event_evidence", evidenceRef, true)),
                failure);
    }

    private List<ObservedEvent> eventsAfter(String busKey, String subject, Instant consumeFrom) {
        return eventsByBusSubject.getOrDefault(busKey + "|" + subject, List.of()).stream()
                .filter(event -> !event.observedAt().isBefore(consumeFrom))
                .toList();
    }

    private Match match(String operation, List<ObservedEvent> observed, Object expectedPayload) {
        if (observed.isEmpty()) {
            return Match.notMatched();
        }
        if (!"event_payload_match".equals(operation)) {
            return Match.matched(List.of(), observed.get(0).payload());
        }
        Map<String, Object> expected = mapValue(expectedPayload);
        if (expected.isEmpty()) {
            return Match.notMatched();
        }
        for (ObservedEvent event : observed) {
            Map<String, Object> actual = mapValue(event.payload());
            List<String> fields = matchedFields(expected, actual);
            if (fields.size() == expected.size()) {
                return Match.matched(fields, event.payload());
            }
        }
        return Match.notMatched();
    }

    private List<String> matchedFields(Map<String, Object> expected, Map<String, Object> actual) {
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            if (String.valueOf(entry.getValue()).equals(String.valueOf(actual.get(entry.getKey())))) {
                fields.add(entry.getKey());
            }
        }
        return List.copyOf(fields);
    }

    private ProviderFailure failureForObserve(String operation, Duration timeout, List<ObservedEvent> observed) {
        if (observed.isEmpty()) {
            if ("nats_observe".equals(operation)) {
                return failure("NATS_TIMEOUT", "NATS_TIMEOUT",
                        "No NATS event was observed before timeout " + timeout + ".",
                        "Increase timeout or verify event publication.");
            }
            return failure("EVENT_NOT_FOUND", "ASSERTION_FAILED",
                    "No NATS event was observed within " + timeout + ".",
                    "Review subject, consume_from, publish timing, and timeout.");
        }
        if ("event_payload_match".equals(operation)) {
            return failure("PAYLOAD_MISMATCH", "ASSERTION_FAILED",
                    "Observed NATS event payload did not match expected JSON fields.",
                    "Review expected payload fields and event evidence.");
        }
        return failure("NATS_TIMEOUT", "NATS_TIMEOUT",
                "NATS observation timed out.", "Increase timeout or verify event publication.");
    }

    private ConnectionSelection connection(ProviderExecutionContext context) {
        Map<String, Object> connection = mapValue(context.bindingValues().get("connection"));
        String localRef = stringValue(connection.get("local_ref"));
        String secretRef = stringValue(connection.get("secret_ref"));
        if (!localRef.isBlank()) {
            if (approvedLocalRef(localRef)) {
                return new ConnectionSelection("local:" + localRef, null, null);
            }
            return new ConnectionSelection("", null, failure(
                    "NATS_CONNECTION_FAILED",
                    "NATS_CONNECTION_FAILED",
                    "NATS local_ref is not approved for framework-managed local execution.",
                    "Use an approved local_ref or a secret_ref resolved by the execution environment."));
        }
        if (!secretRef.isBlank()) {
            if (secretRef.startsWith("env://")) {
                String envName = secretRef.substring("env://".length());
                String value = firstNonBlank(System.getenv(envName), System.getProperty(envName));
                if (value.isBlank()) {
                    return new ConnectionSelection("", null, failure(
                            "NATS_CONNECTION_FAILED",
                            "NATS_CONNECTION_FAILED",
                            "NATS env secret ref `" + secretRef + "` is not set.",
                            "Set environment variable `" + envName + "` to a NATS connection URI before running."));
                }
                URI uri = natsUri(value);
                if (uri == null) {
                    return new ConnectionSelection("", null, failure(
                            "NATS_CONNECTION_FAILED",
                            "NATS_CONNECTION_FAILED",
                            "NATS env secret ref `" + secretRef + "` did not resolve to a supported NATS URI.",
                            "Set `" + envName + "` to `nats://host:port` for this execution profile."));
                }
                return new ConnectionSelection("env:" + envName, uri, null);
            }
            return new ConnectionSelection("", null, failure(
                    "NATS_CONNECTION_FAILED",
                    "NATS_CONNECTION_FAILED",
                    "NATS secret_ref must use an environment-backed reference for framework runtime resolution.",
                    "Use `env://VARIABLE_NAME`; do not put raw NATS connection strings in artifacts."));
        }
        return new ConnectionSelection("", null, failure(
                "NATS_CONNECTION_FAILED",
                "NATS_CONNECTION_FAILED",
                "NATS connection requires connection.secret_ref or approved connection.local_ref.",
                "Supply connection.secret_ref or approved connection.local_ref in Environment Binding."));
    }

    private URI natsUri(String value) {
        try {
            URI uri = new URI(value);
            if (!"nats".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private boolean approvedLocalRef(String localRef) {
        return localRef.startsWith("approved_local_nats")
                || localRef.startsWith("generated://provider-capability/nats/");
    }

    private String subject(ProviderExecutionContext context, ProviderOperationRequest request) {
        return firstNonBlank(parameterValue(request, "subject", ""), stringValue(context.bindingValues().get("subject")));
    }

    private Payload payload(ProviderExecutionContext context, ProviderOperationRequest request) {
        String payloadRef = parameterValue(request, "payload_ref", "");
        String payloadText = parameterValue(request, "payload", "");
        if (payloadRef.isBlank() && payloadText.isBlank()) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS publish requires payload_ref or payload.",
                    "Add checked-in payload ref input `payload_ref` or inline input `payload`."));
        }
        try {
            String text = payloadRef.isBlank() ? payloadText : Files.readString(suiteFile(context.suiteRoot(), payloadRef));
            if (text.isBlank()) {
                return Payload.failed(failure(
                        "PAYLOAD_PARAM_MISSING",
                        "TARGET_RESOLUTION_FAILED",
                        "NATS payload is empty.",
                        "Provide a non-empty JSON payload."));
            }
            Object value = yaml.load(text);
            return new Payload(value == null ? Map.of() : value, toJson(value), null);
        } catch (IOException e) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS payload ref is missing: " + payloadRef,
                    "Restore checked-in NATS payload fixture."));
        } catch (OutsideSuiteRefException e) {
            return Payload.failed(failure(
                    "REF_OUTSIDE_SUITE_ROOT",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS payload_ref must stay inside the suite root.",
                    "Use a checked-in fixture path under the suite directory."));
        } catch (RuntimeException e) {
            return Payload.failed(failure(
                    "PAYLOAD_PARAM_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS payload JSON is invalid.",
                    "Fix the payload fixture before running."));
        }
    }

    private Object parsePayload(String text) {
        try {
            Object value = yaml.load(text);
            return value == null ? Map.of() : value;
        } catch (RuntimeException e) {
            return text;
        }
    }

    private ExpectedPayload expectedPayload(ProviderExecutionContext context, ProviderOperationRequest request) {
        String expectedRef = parameterValue(request, "expected_ref", "");
        if (expectedRef.isBlank()) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS payload match requires expected_ref.",
                    "Add expected result ref input `expected_ref`."));
        }
        Object value;
        try {
            value = resolveRef(context.suiteRoot(), expectedRef);
        } catch (OutsideSuiteRefException e) {
            return new ExpectedPayload(Map.of(), failure(
                    "REF_OUTSIDE_SUITE_ROOT",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS expected_ref must stay inside the suite root.",
                    "Use a checked-in expected_results path under the suite directory."));
        } catch (RuntimeException e) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_PARAM_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS expected payload ref did not resolve to JSON fields.",
                    "Review expected_ref path and expected result fixture."));
        }
        if (isMissing(value)) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_PARAM_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS expected payload ref did not resolve to JSON fields.",
                    "Review expected_ref path and expected result fixture."));
        }
        return new ExpectedPayload(value, null);
    }

    private Object resolveRef(Path suiteRoot, String ref) {
        if (!ref.contains("#")) {
            return readObject(suiteFile(suiteRoot, ref));
        }
        String[] parts = ref.split("#", 2);
        Object current = readObject(suiteFile(suiteRoot, parts[0]));
        String pointer = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        for (String part : pointer.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            current = mapValue(current).get(part);
        }
        return current;
    }

    private Object readObject(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read NATS fixture: " + path, e);
        }
    }

    private Path suiteFile(Path suiteRoot, String ref) {
        Path root = suiteRoot.toAbsolutePath().normalize();
        String filePart = ref.split("#", 2)[0];
        Path resolved = root.resolve(filePart).normalize();
        if (!resolved.startsWith(root)) {
            throw new OutsideSuiteRefException();
        }
        return resolved;
    }

    private ParsedInstant consumeFrom(ProviderOperationRequest request, Instant startedAt) {
        String value = parameterValue(request, "consume_from", "test_start_time");
        try {
            if ("test_start_time".equals(value)) {
                return new ParsedInstant(
                        Instant.parse(stringValue(request.outputs().getOrDefault("_test_start_time", startedAt.toString()))),
                        "test_start_time",
                        null);
            }
            if ("earliest".equals(value)) {
                return new ParsedInstant(Instant.EPOCH, "earliest", null);
            }
            return new ParsedInstant(Instant.parse(value), value, null);
        } catch (RuntimeException e) {
            return new ParsedInstant(Instant.EPOCH, value, failure(
                    "INVALID_INSTANT",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS consume_from must be `test_start_time`, `earliest`, or an ISO-8601 instant.",
                    "Fix DSL input `consume_from`."));
        }
    }

    private ParsedDuration duration(ProviderExecutionContext context, ProviderOperationRequest request, String bindAs) {
        String value = firstNonBlank(
                parameterValue(request, bindAs, ""),
                stringValue(context.bindingValues().get(bindAs)),
                "timeout".equals(bindAs) ? "PT5S" : "PT0.05S");
        try {
            return new ParsedDuration(Duration.parse(value), null);
        } catch (RuntimeException e) {
            return new ParsedDuration(Duration.ZERO, failure(
                    "INVALID_DURATION",
                    "TARGET_RESOLUTION_FAILED",
                    "NATS " + bindAs + " must be an ISO-8601 duration.",
                    "Fix DSL input or Environment Binding value `" + bindAs + "`."));
        }
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return stringValue(parameter.get("ref"));
            }
        }
        return fallback;
    }

    private ProviderOperationResult failed(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            Map<String, Object> outputs,
            ProviderFailure failure,
            Observation observation) {
        String evidenceRef = writeEvidence(context, request, observation.withFailure(failure));
        Map<String, Object> resolvedOutputs = new LinkedHashMap<>(outputs);
        resolvedOutputs.putIfAbsent("event_evidence_ref", evidenceRef);
        return ProviderOperationResult.failed(
                resolvedOutputs,
                List.of(new ProviderEvidence("event_evidence", evidenceRef, true)),
                failure);
    }

    private String writeEvidence(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            Observation observation) {
        String operationId = safe(firstNonBlank(stringValue(request.outputs().get("_operation_id")), request.operation()));
        String ref = "provider-evidence/nats/" + operationId + ".yaml";
        Instant now = Instant.now();
        StringBuilder evidence = new StringBuilder();
        evidence.append("evidence_type: nats_event\n");
        evidence.append("evidence_classification: ").append(evidenceClassification(context)).append('\n');
        evidence.append("downstream_release_evidence: false\n");
        evidence.append("provider_type: ").append(context.providerType()).append('\n');
        evidence.append("provider_id: ").append(context.providerId()).append('\n');
        evidence.append("profile: ").append(context.profile()).append('\n');
        evidence.append("runtime_mode: ").append(context.runtimeMode()).append('\n');
        evidence.append("operation: ").append(request.operation()).append('\n');
        evidence.append("subject: ").append(observation.subject()).append('\n');
        evidence.append("consume_from: ").append(observation.consumeFrom()).append('\n');
        evidence.append("observation_window:\n");
        evidence.append("  started_at: ").append(observation.startedAt() == null ? now : observation.startedAt()).append('\n');
        evidence.append("  finished_at: ").append(observation.finishedAt() == null ? now : observation.finishedAt()).append('\n');
        evidence.append("timeout: ").append(observation.timeout() == null ? "" : observation.timeout()).append('\n');
        evidence.append("poll_interval: ").append(observation.pollInterval() == null ? "" : observation.pollInterval()).append('\n');
        evidence.append("attempts: ").append(observation.attempts()).append('\n');
        evidence.append("observed_count: ").append(observation.observedCount()).append('\n');
        evidence.append("matched: ").append(observation.matched()).append('\n');
        evidence.append("matched_fields: ").append(observation.matchedFields()).append('\n');
        evidence.append("masked_observed_payload:\n");
        appendYamlValue(evidence, maskPayload(observation.matchedPayload()), 2);
        if (observation.failure() != null && observation.lastObservedPayload() != null) {
            evidence.append("last_observed_payload:\n");
            appendYamlValue(evidence, maskPayload(observation.lastObservedPayload()), 2);
        }
        evidence.append("duration_ms: ").append(observation.durationMs()).append('\n');
        evidence.append("status: ").append(observation.failure() == null ? "passed" : "failed").append('\n');
        evidence.append("failure_code: ").append(observation.failure() == null ? "" : observation.failure().code()).append('\n');
        evidence.append("masking:\n  raw_secret_found: false\n");
        write(context.runDir().resolve(ref), evidence.toString());
        return ref;
    }

    private Object maskPayload(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = stringValue(entry.getKey());
                if (secretKey(key)) {
                    masked.put(key, "***");
                } else {
                    masked.put(key, maskPayload(entry.getValue()));
                }
            }
            return masked;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::maskPayload).toList();
        }
        return value == null ? "" : value;
    }

    private String evidenceClassification(ProviderExecutionContext context) {
        return firstNonBlank(
                stringValue(context.bindingValues().get("_evidence_classification")),
                "framework_provider_capability_only");
    }

    private boolean secretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("connection");
    }

    private void sleepUntilNextPoll(Instant deadline, Duration pollInterval) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            return;
        }
        long sleepMs = Math.min(Math.max(pollInterval.toMillis(), 1), remaining);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ProviderFailure failure(String code, String classification, String reason, String ownerAction) {
        return ProviderFailure.of(code, classification, reason, ownerAction);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isMissing(Object value) {
        return value == null
                || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write NATS provider evidence: " + path, e);
        }
    }

    private void appendYamlValue(StringBuilder builder, Object value, int indent) {
        String prefix = " ".repeat(indent);
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                builder.append(prefix).append(entry.getKey()).append(": ");
                Object nested = entry.getValue();
                if (nested instanceof Map<?, ?> || nested instanceof Collection<?>) {
                    builder.append('\n');
                    appendYamlValue(builder, nested, indent + 2);
                } else {
                    builder.append(nested).append('\n');
                }
            }
        } else if (value instanceof Collection<?> collection && !collection.isEmpty()) {
            for (Object item : collection) {
                builder.append(prefix).append("- ");
                if (item instanceof Map<?, ?> || item instanceof Collection<?>) {
                    builder.append('\n');
                    appendYamlValue(builder, item, indent + 2);
                } else {
                    builder.append(item).append('\n');
                }
            }
        } else {
            builder.append(prefix).append("{}\n");
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    private record ConnectionSelection(String busKey, URI externalUri, ProviderFailure failure) {
    }

    private static final class ExternalNatsClient {

        private ExternalNatsClient() {
        }

        static ExternalObservation publishAndObserve(URI uri, String subject, String payload, Duration timeout) {
            long deadline = System.currentTimeMillis() + Math.max(timeout.toMillis(), 1);
            try (Socket socket = connectedSocket(uri)) {
                socket.setSoTimeout(250);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                if (!readServerInfo(input, deadline)) {
                    return ExternalObservation.failed(connectionFailure());
                }
                writeAscii(output, connectCommand(uri));
                writeAscii(output, "SUB " + subject + " 1\r\n");
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                writeAscii(output, "PUB " + subject + " " + payloadBytes.length + "\r\n");
                output.write(payloadBytes);
                writeAscii(output, "\r\nPING\r\n");
                output.flush();
                return readObservation(input, deadline);
            } catch (IOException | RuntimeException e) {
                return ExternalObservation.failed(connectionFailure());
            }
        }

        static ExternalObservation observe(URI uri, String subject, Duration timeout) {
            long deadline = System.currentTimeMillis() + Math.max(timeout.toMillis(), 1);
            try (Socket socket = connectedSocket(uri)) {
                socket.setSoTimeout(250);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                if (!readServerInfo(input, deadline)) {
                    return ExternalObservation.failed(connectionFailure());
                }
                writeAscii(output, connectCommand(uri));
                writeAscii(output, "SUB " + subject + " 1\r\nPING\r\n");
                output.flush();
                return readObservation(input, deadline);
            } catch (IOException | RuntimeException e) {
                return ExternalObservation.failed(connectionFailure());
            }
        }

        private static boolean readServerInfo(BufferedInputStream input, long deadline) throws IOException {
            String line = readLine(input, deadline);
            return line != null && line.startsWith("INFO");
        }

        private static ExternalObservation readObservation(BufferedInputStream input, long deadline) throws IOException {
            while (System.currentTimeMillis() <= deadline) {
                String line = readLine(input, deadline);
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (line.startsWith("PONG")) {
                    return new ExternalObservation("", null);
                }
                if (line.startsWith("INFO") || line.startsWith("+OK")) {
                    continue;
                }
                if (line.startsWith("-ERR")) {
                    return ExternalObservation.failed(connectionFailure());
                }
                if (line.startsWith("MSG ")) {
                    String[] parts = line.split("\\s+");
                    int length = Integer.parseInt(parts[parts.length - 1]);
                    byte[] payload = input.readNBytes(length);
                    readLine(input, deadline);
                    return new ExternalObservation(new String(payload, StandardCharsets.UTF_8), null);
                }
                return ExternalObservation.failed(connectionFailure());
            }
            return ExternalObservation.failed(connectionFailure());
        }

        private static String readLine(BufferedInputStream input, long deadline) throws IOException {
            StringBuilder line = new StringBuilder();
            while (System.currentTimeMillis() <= deadline) {
                int next;
                try {
                    next = input.read();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (next < 0) {
                    return line.length() == 0 ? null : line.toString();
                }
                if (next == '\n') {
                    return line.toString().strip();
                }
                if (next != '\r') {
                    line.append((char) next);
                }
            }
            return line.length() == 0 ? null : line.toString();
        }

        private static Socket connectedSocket(URI uri) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 2_000);
            return socket;
        }

        private static void writeAscii(BufferedOutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String connectCommand(URI uri) {
            StringBuilder json = new StringBuilder("{\"verbose\":false,\"pedantic\":false,\"lang\":\"java\",\"version\":\"0.2.7\"");
            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                String[] parts = userInfo.split(":", 2);
                json.append(",\"user\":\"").append(escape(parts[0])).append('"');
                if (parts.length > 1) {
                    json.append(",\"pass\":\"").append(escape(parts[1])).append('"');
                }
            }
            return "CONNECT " + json.append("}\r\n");
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static ProviderFailure connectionFailure() {
            return ProviderFailure.of(
                    "NATS_CONNECTION_FAILED",
                    "NATS_CONNECTION_FAILED",
                    "NATS connection could not be opened or did not return a valid protocol response.",
                    "Verify the execution profile starts NATS and that env://NATS_CONNECTION points to it.");
        }
    }

    private record ExternalObservation(String payloadText, ProviderFailure failure) {

        static ExternalObservation failed(ProviderFailure failure) {
            return new ExternalObservation("", failure);
        }
    }

    private record Payload(Object value, String text, ProviderFailure failure) {

        static Payload failed(ProviderFailure failure) {
            return new Payload(Map.of(), "", failure);
        }
    }

    private record ExpectedPayload(Object value, ProviderFailure failure) {
    }

    private record ParsedDuration(Duration value, ProviderFailure failure) {
    }

    private record ParsedInstant(Instant value, String label, ProviderFailure failure) {
    }

    private record ObservedEvent(String subject, Object payload, String payloadText, Instant observedAt) {
    }

    private record Match(boolean matched, List<String> matchedFields, Object matchedPayload) {

        static Match matched(List<String> fields, Object payload) {
            return new Match(true, List.copyOf(fields), payload);
        }

        static Match notMatched() {
            return new Match(false, List.of(), Map.of());
        }
    }

    private record Observation(
            String subject,
            String consumeFrom,
            Instant startedAt,
            Instant finishedAt,
            Duration timeout,
            Duration pollInterval,
            int attempts,
            int observedCount,
            boolean matched,
            List<String> matchedFields,
            Object matchedPayload,
            Object lastObservedPayload,
            long durationMs,
            ProviderFailure failure) {

        static Observation empty() {
            return new Observation("", "", null, null, null, null, 0, 0, false, List.of(), Map.of(), null, 0, null);
        }

        static Observation empty(String subject) {
            return new Observation(subject, "", null, null, null, null, 0, 0, false, List.of(), Map.of(), null, 0, null);
        }

        Observation withFailure(ProviderFailure replacement) {
            return new Observation(
                    subject,
                    consumeFrom,
                    startedAt,
                    finishedAt,
                    timeout,
                    pollInterval,
                    attempts,
                    observedCount,
                    matched,
                    matchedFields,
                    matchedPayload,
                    lastObservedPayload,
                    durationMs,
                    replacement);
        }
    }

    private static final class OutsideSuiteRefException extends RuntimeException {
    }
}
