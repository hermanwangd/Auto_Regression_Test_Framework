package com.specdriven.regression.provider.nats;

import java.util.Objects;
import com.specdriven.regression.provider.SuiteAssetReader;
import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.contract.v03.V03OutputRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.specdriven.regression.contract.SecurityFieldNames;
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
import com.specdriven.regression.provider.ProviderEndpoints;
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Yaml yaml = ProviderDataYaml.create();

    public ProviderOperationResult executeReviewedPublish(ProviderExecutionContext context, ProviderOperationRequest request) {
        if (!"native".equals(context.runtimeMode()) || !"nats_publish".equals(request.operation()))
            throw new IllegalArgumentException("reviewed_nats: only native nats_publish is supported");
        Map<String,Object> bindings = new LinkedHashMap<>(context.bindingValues());
        // The generic adapter also materializes a nested copy of dotted binding names.
        bindings.remove("connection");
        ReviewedLoopbackNatsPolicy.validateBindings(bindings);
        Map<String,Object> inputs = new LinkedHashMap<>();
        for (var parameter : request.parameters()) {
            String name = String.valueOf(parameter.get("bind_as"));
            if (inputs.putIfAbsent(name, parameter.get("ref")) != null)
                throw new IllegalArgumentException("reviewed_nats: duplicate input");
        }
        if (!"fixtures/payload.json".equals(inputs.get("payload_ref")))
            throw new IllegalArgumentException("reviewed_nats: resolved payload differs from reviewed fixture");
        inputs.put("payload_ref", ReviewedLoopbackNatsPolicy.PAYLOAD_REF);
        ReviewedLoopbackNatsPolicy.validateInputs(inputs);
        ReviewedLoopbackNatsPolicy.validateFixture(context.suiteRoot());
        return execute(context, request);
    }

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
            if (!payload.text().equals(externalObservation.payloadText())) {
                return failed(context, request, Map.of("subject", subject), failure("NATS_PUBLISH_FAILED", "NATS_PUBLISH_FAILED",
                        "NATS did not return the exact published payload on the selected subscription.",
                        "Verify publish permissions and the native broker echo evidence."), Observation.empty(subject));
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
        List<ObservedEvent> observed;
        Match match;
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
        String publicUri = firstNonBlank(stringValue(connection.get("uri")), stringValue(context.bindingValues().get("connection.uri")));
        if (!localRef.isBlank() && !secretRef.isBlank()) {
            return new ConnectionSelection("", null, failure("NATS_CONNECTION_FAILED", "NATS_CONNECTION_FAILED",
                    "NATS connection has conflicting bindings.", "Select one connection binding."));
        }
        if (!publicUri.isBlank()) {
            try {
                URI uri = ReviewedLoopbackNatsPolicy.origin(publicUri);
                if (!localRef.isBlank() || !secretRef.isBlank()) throw new IllegalArgumentException("ambiguous connection");
                return new ConnectionSelection("loopback:" + uri.getPort(), uri, null);
            } catch (IllegalArgumentException error) {
                return new ConnectionSelection("", null, failure("NATS_CONNECTION_FAILED", "NATS_CONNECTION_FAILED",
                        "NATS public connection requires one exact loopback URI.", "Use the reviewed loopback connection binding."));
            }
        }
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
            if (secretRef.matches("env://[A-Z_][A-Z0-9_]*")) {
                String envName = secretRef.substring("env://".length());
                String value = firstNonBlank(System.getenv(envName), System.getProperty(envName));
                if (value.isBlank()) {
                    return new ConnectionSelection("", null, failure(
                            "NATS_CONNECTION_FAILED",
                            "NATS_CONNECTION_FAILED",
                            "NATS env secret ref `" + secretRef + "` is not set.",
                            "Set environment variable `" + envName + "` to a NATS connection URI before running."));
                }
                URI uri = natsUri(value, secretRef);
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

    private URI natsUri(String value, String reference) {
        try {
            return ProviderEndpoints.nats(value, reference, false);
        } catch (IllegalArgumentException error) {
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
            String text = payloadRef.isBlank() ? payloadText : readAsset(context.suiteRoot(), payloadRef);
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
            return readObject(suiteRoot, ref);
        }
        String[] parts = ref.split("#", 2);
        Object current = readObject(suiteRoot, parts[0]);
        String pointer = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        for (String part : pointer.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            current = mapValue(current).get(part);
        }
        return current;
    }

    private Object readObject(Path root, String ref) {
        try {
            return yaml.load(readAsset(root, ref));
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read NATS fixture.", error);
        }
    }

    private String readAsset(Path root, String ref) throws IOException {
        try {
            return SuiteAssetReader.read(root, ref.split("#", 2)[0], "NATS asset must be inside the suite.");
        } catch (IllegalArgumentException outside) {
            throw new OutsideSuiteRefException();
        }
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
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidence_type", "nats_event");
        evidence.put("evidence_classification", evidenceClassification(context));
        evidence.put("downstream_release_evidence", false);
        evidence.put("provider_type", context.providerType());
        evidence.put("provider_id", context.providerId());
        evidence.put("profile", context.profile());
        evidence.put("runtime_mode", context.runtimeMode());
        evidence.put("operation", request.operation());
        evidence.put("subject", observation.subject());
        evidence.put("consume_from", observation.consumeFrom());
        evidence.put("observation_window", Map.of("started_at", (observation.startedAt() == null ? now : observation.startedAt()).toString(),
                "finished_at", (observation.finishedAt() == null ? now : observation.finishedAt()).toString()));
        evidence.put("timeout", Objects.toString(observation.timeout(), ""));
        evidence.put("poll_interval", Objects.toString(observation.pollInterval(), ""));
        evidence.put("attempts", observation.attempts());
        evidence.put("observed_count", observation.observedCount());
        evidence.put("matched", observation.matched());
        evidence.put("matched_fields", observation.matchedFields());
        evidence.put("masked_observed_payload", maskPayload(observation.matchedPayload()));
        if (observation.failure() != null && observation.lastObservedPayload() != null) {
            evidence.put("last_observed_payload", maskPayload(observation.lastObservedPayload()));
        }
        evidence.put("duration_ms", observation.durationMs());
        evidence.put("status", observation.failure() == null ? "passed" : "failed");
        evidence.put("failure_code", observation.failure() == null ? "" : observation.failure().code());
        evidence.put("masking", Map.of("raw_secret_found", false));
        try {
            ProviderDataYaml.writeEvidence(context.runDir().resolve(ref), evidence);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to write NATS evidence.", error);
        }
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
        return value instanceof String text ? V03OutputRedactor.redactProviderDiagnostic(text) : value;
    }

    private String evidenceClassification(ProviderExecutionContext context) {
        return firstNonBlank(
                stringValue(context.bindingValues().get("_evidence_classification")),
                "framework_provider_capability_only");
    }

    private boolean secretKey(String key) {
        String normalized = SecurityFieldNames.canonical(key);
        return SecurityFieldNames.isCredential(key)
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

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid NATS payload.", error);
        }
    }


    private record ConnectionSelection(String busKey, URI externalUri, ProviderFailure failure) {
    }

    private static final class ExternalNatsClient {
        private static final int MAX_LINE_BYTES = 8192;
        private static final int MAX_PAYLOAD_BYTES = ReviewedLoopbackNatsPolicy.MAX_PAYLOAD_BYTES;
        private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();

        private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
            var executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "nats-socket-deadline"); thread.setDaemon(true); return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }

        static ExternalObservation publishAndObserve(URI uri, String subject, String payload, Duration timeout) {
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            if (payloadBytes.length == 0 || payloadBytes.length > MAX_PAYLOAD_BYTES || !validSubject(subject))
                return ExternalObservation.failed(connectionFailure());
            try (TimedSocket connection = new TimedSocket(uri, timeout)) {
                Socket socket = connection.socket;
                var input = new BufferedInputStream(socket.getInputStream());
                var output = new BufferedOutputStream(socket.getOutputStream());
                readServerInfo(input, connection.deadline);
                writeAscii(output, connectCommand(uri));
                writeAscii(output, "SUB " + subject + " 1\r\n");
                writeAscii(output, "PUB " + subject + " " + payloadBytes.length + "\r\n");
                output.write(payloadBytes);
                writeAscii(output, "\r\nPING\r\n"); output.flush();
                return readObservation(input, output, connection.deadline, subject, true);
            } catch (IOException | RuntimeException error) {
                return ExternalObservation.failed(connectionFailure());
            }
        }

        static ExternalObservation observe(URI uri, String subject, Duration timeout) {
            if (!validSubscription(subject)) return ExternalObservation.failed(connectionFailure());
            try (TimedSocket connection = new TimedSocket(uri, timeout)) {
                var input = new BufferedInputStream(connection.socket.getInputStream());
                var output = new BufferedOutputStream(connection.socket.getOutputStream());
                readServerInfo(input, connection.deadline);
                writeAscii(output, connectCommand(uri));
                writeAscii(output, "SUB " + subject + " 1\r\nPING\r\n"); output.flush();
                return readObservation(input, output, connection.deadline, subject, false);
            } catch (IOException | RuntimeException error) {
                return ExternalObservation.failed(connectionFailure());
            }
        }

        private static boolean validSubject(String subject) {
            return subject != null && subject.length() <= 1024 && subject.matches("[!-~]+")
                    && subject.indexOf('*') < 0 && subject.indexOf('>') < 0;
        }

        private static boolean validSubscription(String subject) {
            return subject != null && subject.length() <= 1024 && subject.matches("[!-~]+");
        }

        private static boolean matchesSubscription(String subscription, String subject) {
            if (!validSubject(subject)) return false;
            String[] filter = subscription.split("\\.", -1), actual = subject.split("\\.", -1);
            for (int i = 0; i < filter.length; i++) {
                if (filter[i].equals(">")) return i == filter.length - 1 && i < actual.length;
                if (i >= actual.length || !filter[i].equals("*") && !filter[i].equals(actual[i])) return false;
            }
            return filter.length == actual.length;
        }

        private static void readServerInfo(BufferedInputStream input, long deadline) throws IOException {
            if (!readLine(input, deadline).startsWith("INFO ")) throw new IOException("missing INFO");
        }

        private static ExternalObservation readObservation(BufferedInputStream input, BufferedOutputStream output,
                long deadline, String subject, boolean exactSubject) throws IOException {
            while (System.nanoTime() < deadline) {
                String line = readLine(input, deadline);
                // An empty observation is legitimate for polling; publish requires a nonempty exact echo.
                if (line.equals("PONG")) return new ExternalObservation("", null);
                if (line.startsWith("INFO ") || line.equals("+OK")) continue;
                if (line.equals("PING")) { writeAscii(output, "PONG\r\n"); output.flush(); continue; }
                if (!line.startsWith("MSG ")) throw new IOException("unexpected NATS frame");
                String[] parts = line.split(" ", -1);
                if ((parts.length != 4 && parts.length != 5) || !(exactSubject ? parts[1].equals(subject) : matchesSubscription(subject, parts[1])) || !parts[2].equals("1"))
                    throw new IOException("unexpected MSG identity");
                String rawLength = parts[parts.length - 1];
                if (!rawLength.matches("[0-9]{1,5}")) throw new IOException("invalid MSG length");
                int length = Integer.parseInt(rawLength);
                if (length < 0 || length > MAX_PAYLOAD_BYTES) throw new IOException("MSG exceeds payload bound");
                byte[] payload = new byte[length];
                for (int offset = 0; offset < length;) {
                    checkDeadline(deadline);
                    int read = input.read(payload, offset, length - offset);
                    if (read < 0) throw new java.io.EOFException("truncated MSG");
                    offset += read;
                }
                if (readByte(input, deadline) != '\r' || readByte(input, deadline) != '\n')
                    throw new IOException("invalid MSG terminator");
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(payload)).toString();
                return new ExternalObservation(text, null);
            }
            throw new java.net.SocketTimeoutException("NATS deadline exceeded");
        }

        private static String readLine(BufferedInputStream input, long deadline) throws IOException {
            var bytes = new java.io.ByteArrayOutputStream();
            while (bytes.size() <= MAX_LINE_BYTES) {
                int next = readByte(input, deadline);
                if (next == '\r') {
                    if (readByte(input, deadline) != '\n') throw new IOException("invalid NATS line ending");
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
                if (next < 32 || next > 126) throw new IOException("invalid NATS protocol line");
                bytes.write(next);
            }
            throw new IOException("NATS protocol line exceeds bound");
        }

        private static int readByte(BufferedInputStream input, long deadline) throws IOException {
            checkDeadline(deadline);
            int next = input.read();
            if (next < 0) throw new java.io.EOFException("NATS peer closed connection");
            return next;
        }

        private static void checkDeadline(long deadline) throws IOException {
            if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("NATS deadline exceeded");
        }

        private static final class TimedSocket implements AutoCloseable {
            final Socket socket = new Socket();
            final long deadline;
            final java.util.concurrent.ScheduledFuture<?> closer;
            TimedSocket(URI uri, Duration timeout) throws IOException {
                long budget = Math.max(1, Math.min(timeout.toMillis(), Integer.MAX_VALUE));
                deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budget);
                closer = DEADLINES.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } },
                        budget, java.util.concurrent.TimeUnit.MILLISECONDS);
                try {
                    socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), (int)Math.min(2000, budget));
                    socket.setSoTimeout((int)budget);
                } catch (IOException | RuntimeException error) { close(); throw error; }
            }
            @Override public void close() throws IOException { closer.cancel(false); socket.close(); }
        }

        private static void writeAscii(BufferedOutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String connectCommand(URI uri) {
            StringBuilder json = new StringBuilder("{\"verbose\":false,\"pedantic\":false,\"lang\":\"java\",\"version\":\"0.3.2\"");
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
