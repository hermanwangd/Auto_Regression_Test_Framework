package com.specdriven.regression.provider.ibmmq;

import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import com.ibm.mq.MQException;
import com.ibm.mq.MQGetMessageOptions;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.yaml.snakeyaml.Yaml;

public class IbmMqProviderRuntime implements ProviderRuntime {

    private final Map<String, List<MqMessage>> messagesByManagerQueue = new ConcurrentHashMap<>();
    private final IbmMqClientTransport mqClientTransport;
    private final Yaml yaml = new Yaml();

    public IbmMqProviderRuntime() {
        this(new DefaultIbmMqClientTransport());
    }

    IbmMqProviderRuntime(IbmMqClientTransport mqClientTransport) {
        this.mqClientTransport = mqClientTransport;
    }

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        ConnectionSelection connection = connection(context);
        if ("mq_get".equals(request.operation())) {
            return failed(context, request, Map.of(), failure(
                    "DESTRUCTIVE_OPERATION_UNSUPPORTED",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ destructive get is not supported by the browse-first provider baseline.",
                    "Use mq_browse, mq_message_exists, or mq_payload_match, or define a future destructive opt-in contract."),
                    Observation.empty(connection.queue()));
        }
        if (connection.failure() != null) {
            return failed(context, request, Map.of(), connection.failure(), Observation.empty());
        }
        return switch (request.operation()) {
            case "mq_put" -> put(context, request, connection);
            case "mq_browse", "mq_message_exists", "mq_payload_match" -> browse(context, request, connection);
            default -> failed(
                    context,
                    request,
                    Map.of(),
                    failure(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported IBM MQ operation `" + request.operation() + "`.",
                            "Use mq_put, mq_browse, mq_message_exists, or mq_payload_match."),
                    Observation.empty(connection.queue()));
        };
    }

    private ProviderOperationResult put(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection) {
        Instant startedAt = Instant.now();
        Payload payload = payload(context, request);
        if (payload.failure() != null) {
            return failed(context, request, Map.of("queue", connection.queue()), payload.failure(), Observation.empty(connection.queue()));
        }
        ParsedDuration timeout = duration(context, request, "timeout");
        if (timeout.failure() != null) {
            return failed(context, request, Map.of("queue", connection.queue()), timeout.failure(), Observation.empty(connection.queue()));
        }
        String messageId = "ID:" + UUID.randomUUID();
        String correlationId = firstNonBlank(parameterValue(request, "correlation_id", ""), "CORR-" + UUID.randomUUID());
        if ("native".equals(context.runtimeMode())) {
            return nativePut(context, request, connection, payload, messageId, correlationId, timeout, startedAt);
        }
        Instant putAt = Instant.now();
        messagesByManagerQueue.computeIfAbsent(connection.busKey() + "|" + connection.queue(), ignored -> new ArrayList<>())
                .add(new MqMessage(connection.queue(), messageId, correlationId, payload.value(), putAt));
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        String evidenceRef = writeEvidence(context, request, new Observation(
                connection.queue(),
                startedAt,
                Instant.now(),
                timeout.value(),
                Duration.ZERO,
                1,
                1,
                true,
                List.of(messageId),
                messageId,
                correlationId,
                List.of(),
                payload.value(),
                null,
                durationMs,
                null));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("queue", connection.queue());
        outputs.put("message_id", messageId);
        outputs.put("correlation_id", correlationId);
        outputs.put("put_at", putAt.toString());
        outputs.put("mq_evidence_ref", evidenceRef);
        return ProviderOperationResult.passed(
                outputs,
                List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)));
    }

    private ProviderOperationResult browse(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection) {
        Instant startedAt = Instant.now();
        ExpectedPayload expected = expectedPayload(context, request);
        if ("mq_payload_match".equals(request.operation()) && expected.failure() != null) {
            return failed(context, request, Map.of("queue", connection.queue()), expected.failure(), Observation.empty(connection.queue()));
        }
        ParsedDuration timeout = duration(context, request, "timeout");
        if (timeout.failure() != null) {
            return failed(context, request, Map.of("queue", connection.queue()), timeout.failure(), Observation.empty(connection.queue()));
        }
        ParsedDuration pollInterval = duration(context, request, "poll_interval");
        if (pollInterval.failure() != null) {
            return failed(context, request, Map.of("queue", connection.queue()), pollInterval.failure(), Observation.empty(connection.queue()));
        }
        String correlationId = parameterValue(request, "correlation_id", "");
        if ("native".equals(context.runtimeMode())) {
            return nativeBrowse(context, request, connection, expected, timeout, pollInterval, correlationId, startedAt);
        }
        Instant deadline = Instant.now().plus(timeout.value());
        int attempts = 0;
        List<MqMessage> observed = List.of();
        Match match = Match.notMatched();
        do {
            attempts++;
            observed = messages(connection.busKey(), connection.queue(), correlationId);
            match = match(request.operation(), observed, expected.value());
            if (match.matched() || "mq_browse".equals(request.operation()) && !observed.isEmpty()) {
                break;
            }
            sleepUntilNextPoll(deadline, pollInterval.value());
        } while (Instant.now().isBefore(deadline));

        MqMessage lastObserved = observed.isEmpty() ? null : observed.get(observed.size() - 1);
        List<String> messageIds = observed.stream().map(MqMessage::messageId).toList();
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        ProviderFailure failure = match.matched() || "mq_browse".equals(request.operation()) && !observed.isEmpty()
                ? null
                : failureForBrowse(request.operation(), timeout.value(), observed);
        String evidenceRef = writeEvidence(context, request, new Observation(
                connection.queue(),
                startedAt,
                Instant.now(),
                timeout.value(),
                pollInterval.value(),
                attempts,
                observed.size(),
                match.matched() || "mq_browse".equals(request.operation()) && !observed.isEmpty(),
                messageIds,
                lastObserved == null ? "" : lastObserved.messageId(),
                lastObserved == null ? "" : lastObserved.correlationId(),
                match.matchedFields(),
                match.matchedPayload(),
                lastObserved == null ? null : lastObserved.payload(),
                durationMs,
                failure));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("queue", connection.queue());
        outputs.put("browse_count", observed.size());
        outputs.put("message_ids", messageIds);
        outputs.put("last_observed_message_id", lastObserved == null ? "" : lastObserved.messageId());
        outputs.put("matched", match.matched());
        outputs.put("matched_fields", match.matchedFields());
        outputs.put("duration_ms", durationMs);
        outputs.put("mq_evidence_ref", evidenceRef);
        if ("mq_payload_match".equals(request.operation())) {
            outputs.put("assertion_evidence_ref", evidenceRef);
        }
        if (failure == null) {
            return ProviderOperationResult.passed(
                    outputs,
                    List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)));
        }
        return ProviderOperationResult.failed(
                outputs,
                List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)),
                failure);
    }

    private ProviderOperationResult nativePut(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection,
            Payload payload,
            String messageId,
            String correlationId,
            ParsedDuration timeout,
            Instant startedAt) {
        try {
            MqPutResult putResult = mqClientTransport.put(
                    connection.toMqConnection(),
                    payload.value(),
                    messageId,
                    correlationId,
                    timeout.value());
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeEvidence(context, request, new Observation(
                    connection.queue(),
                    startedAt,
                    Instant.now(),
                    timeout.value(),
                    Duration.ZERO,
                    1,
                    1,
                    true,
                    List.of(putResult.messageId()),
                    putResult.messageId(),
                    putResult.correlationId(),
                    List.of(),
                    payload.value(),
                    null,
                    durationMs,
                    null));
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("queue", connection.queue());
            outputs.put("message_id", putResult.messageId());
            outputs.put("correlation_id", putResult.correlationId());
            outputs.put("put_at", putResult.putAt().toString());
            outputs.put("mq_evidence_ref", evidenceRef);
            return ProviderOperationResult.passed(
                    outputs,
                    List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)));
        } catch (Exception e) {
            return failed(
                    context,
                    request,
                    Map.of("queue", connection.queue()),
                    mqConnectionFailure(e),
                    Observation.empty(connection.queue()));
        }
    }

    private ProviderOperationResult nativeBrowse(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection,
            ExpectedPayload expected,
            ParsedDuration timeout,
            ParsedDuration pollInterval,
            String correlationId,
            Instant startedAt) {
        try {
            MqBrowseResult browseResult = mqClientTransport.browse(
                    connection.toMqConnection(),
                    correlationId,
                    timeout.value(),
                    pollInterval.value());
            List<MqMessage> observed = browseResult.messages();
            Match match = match(request.operation(), observed, expected.value());
            MqMessage lastObserved = observed.isEmpty() ? null : observed.get(observed.size() - 1);
            List<String> messageIds = observed.stream().map(MqMessage::messageId).toList();
            ProviderFailure failure = match.matched() || "mq_browse".equals(request.operation()) && !observed.isEmpty()
                    ? null
                    : failureForBrowse(request.operation(), timeout.value(), observed);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeEvidence(context, request, new Observation(
                    connection.queue(),
                    startedAt,
                    Instant.now(),
                    timeout.value(),
                    pollInterval.value(),
                    browseResult.attempts(),
                    observed.size(),
                    match.matched() || "mq_browse".equals(request.operation()) && !observed.isEmpty(),
                    messageIds,
                    lastObserved == null ? "" : lastObserved.messageId(),
                    lastObserved == null ? "" : lastObserved.correlationId(),
                    match.matchedFields(),
                    match.matchedPayload(),
                    lastObserved == null ? null : lastObserved.payload(),
                    durationMs,
                    failure));
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("queue", connection.queue());
            outputs.put("browse_count", observed.size());
            outputs.put("message_ids", messageIds);
            outputs.put("last_observed_message_id", lastObserved == null ? "" : lastObserved.messageId());
            outputs.put("matched", match.matched());
            outputs.put("matched_fields", match.matchedFields());
            outputs.put("duration_ms", durationMs);
            outputs.put("mq_evidence_ref", evidenceRef);
            if ("mq_payload_match".equals(request.operation())) {
                outputs.put("assertion_evidence_ref", evidenceRef);
            }
            if (failure == null) {
                return ProviderOperationResult.passed(
                        outputs,
                        List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)));
            }
            return ProviderOperationResult.failed(
                    outputs,
                    List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)),
                    failure);
        } catch (Exception e) {
            return failed(
                    context,
                    request,
                    Map.of("queue", connection.queue()),
                    mqConnectionFailure(e),
                    Observation.empty(connection.queue()));
        }
    }

    private List<MqMessage> messages(String busKey, String queue, String correlationId) {
        return messagesByManagerQueue.getOrDefault(busKey + "|" + queue, List.of()).stream()
                .filter(message -> correlationId.isBlank() || correlationId.equals(message.correlationId()))
                .toList();
    }

    private Match match(String operation, List<MqMessage> observed, Object expectedPayload) {
        if (observed.isEmpty()) {
            return Match.notMatched();
        }
        if (!"mq_payload_match".equals(operation)) {
            return Match.matched(List.of(), observed.get(0).payload());
        }
        Map<String, Object> expected = mapValue(expectedPayload);
        if (expected.isEmpty()) {
            return Match.notMatched();
        }
        for (MqMessage message : observed) {
            Map<String, Object> actual = mapValue(message.payload());
            List<String> fields = matchedFields(expected, actual);
            if (fields.size() == expected.size()) {
                return Match.matched(fields, message.payload());
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

    private ProviderFailure failureForBrowse(String operation, Duration timeout, List<MqMessage> observed) {
        if (observed.isEmpty()) {
            return failure(
                    "MQ_TIMEOUT",
                    "MQ_TIMEOUT",
                    "No IBM MQ message was observed within " + timeout + ".",
                    "Review queue binding, correlation selector, put timing, and timeout.");
        }
        if ("mq_payload_match".equals(operation)) {
            return failure(
                    "PAYLOAD_MISMATCH",
                    "ASSERTION_FAILED",
                    "Observed IBM MQ message payload did not match expected JSON fields.",
                    "Review expected payload fields and MQ evidence.");
        }
        return failure(
                "MESSAGE_NOT_FOUND",
                "ASSERTION_FAILED",
                "Expected IBM MQ message was not found.",
                "Review queue binding, selector, and timeout.");
    }

    private ConnectionSelection connection(ProviderExecutionContext context) {
        String queueManager = bindingText(context, "queue_manager");
        String channel = bindingText(context, "channel");
        String connName = bindingText(context, "conn_name");
        String queue = bindingText(context, "queue");
        String credential = bindingText(context, "credential.secret_ref");
        if ("native".equals(context.runtimeMode())) {
            ResolvedBinding resolvedConnName = resolveEnvBinding(connName, "conn_name");
            if (resolvedConnName.failure() != null) {
                return new ConnectionSelection("", queueManager, channel, "", queue, credential, resolvedConnName.failure());
            }
            connName = resolvedConnName.value();
            ResolvedBinding resolvedCredential = resolveEnvBinding(credential, "credential.secret_ref");
            if (resolvedCredential.failure() != null) {
                return new ConnectionSelection("", queueManager, channel, connName, queue, "", resolvedCredential.failure());
            }
            credential = resolvedCredential.value();
        }
        if (queueManager.isBlank()) {
            return new ConnectionSelection("", queueManager, channel, connName, queue, credential, failure(
                    "QUEUE_MANAGER_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ queue_manager binding is missing.",
                    "Declare queue_manager in Env_Profile for this provider_id."));
        }
        if (channel.isBlank() || connName.isBlank()) {
            return new ConnectionSelection("", queueManager, channel, connName, queue, credential, failure(
                    "MQ_CONNECTION_FAILED",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ channel or conn_name binding is missing.",
                    "Declare channel and conn_name in Env_Profile for this provider_id."));
        }
        if (queue.isBlank()) {
            return new ConnectionSelection("", queueManager, channel, connName, "", credential, failure(
                    "QUEUE_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ queue binding is missing.",
                    "Declare queue in Env_Profile for this provider_id."));
        }
        if (credential.isBlank()) {
            return new ConnectionSelection("", queueManager, channel, connName, queue, credential, failure(
                    "CREDENTIAL_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ credential.secret_ref binding is missing.",
                    "Declare credential.secret_ref in Env_Profile for this provider_id."));
        }
        return new ConnectionSelection(
                context.runtimeMode() + ":" + queueManager + ":" + channel + ":" + connName,
                queueManager,
                channel,
                connName,
                queue,
                credential,
                null);
    }

    private ResolvedBinding resolveEnvBinding(String value, String bindingName) {
        if (!value.startsWith("env://")) {
            return new ResolvedBinding(value, null);
        }
        String envName = value.substring("env://".length());
        String resolved = firstNonBlank(System.getenv(envName), System.getProperty(envName));
        if (resolved.isBlank()) {
            return new ResolvedBinding("", failure(
                    "MQ_CONNECTION_FAILED",
                    "MQ_CONNECTION_FAILED",
                    "IBM MQ " + bindingName + " env ref `" + value + "` is not set.",
                    "Set environment variable `" + envName + "` before running this execution profile."));
        }
        return new ResolvedBinding(resolved, null);
    }

    private ProviderFailure mqConnectionFailure(Exception e) {
        return failure(
                "MQ_CONNECTION_FAILED",
                "MQ_CONNECTION_FAILED",
                "IBM MQ client operation failed. Cause type: " + e.getClass().getSimpleName() + ".",
                "Verify the execution profile supplies a reachable external queue manager and valid IBM MQ bindings.");
    }

    private Payload payload(ProviderExecutionContext context, ProviderOperationRequest request) {
        String payloadRef = parameterValue(request, "payload_ref", "");
        String payloadText = parameterValue(request, "payload", "");
        if (payloadRef.isBlank() && payloadText.isBlank()) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ put requires payload_ref or payload.",
                    "Add checked-in payload_ref input or inline payload input."));
        }
        try {
            String text = payloadRef.isBlank() ? payloadText : Files.readString(suiteFile(context.suiteRoot(), payloadRef));
            if (text.isBlank()) {
                return Payload.failed(failure(
                        "PAYLOAD_REF_MISSING",
                        "TARGET_RESOLUTION_FAILED",
                        "IBM MQ payload is empty.",
                        "Provide a non-empty JSON payload."));
            }
            Object value = yaml.load(text);
            return new Payload(value == null ? Map.of() : value, null);
        } catch (IOException e) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ payload ref is missing: " + payloadRef,
                    "Restore checked-in IBM MQ payload fixture."));
        } catch (OutsideSuiteRefException e) {
            return Payload.failed(failure(
                    "REF_OUTSIDE_SUITE_ROOT",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ payload_ref must stay inside the suite root.",
                    "Use a checked-in fixture path under the suite directory."));
        } catch (RuntimeException e) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ payload JSON is invalid.",
                    "Fix the payload fixture before running."));
        }
    }

    private ExpectedPayload expectedPayload(ProviderExecutionContext context, ProviderOperationRequest request) {
        String expectedRef = parameterValue(request, "expected_ref", "");
        if (expectedRef.isBlank()) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ payload match requires expected_ref.",
                    "Add expected result ref input `expected_ref`."));
        }
        try {
            Object value = resolveRef(context.suiteRoot(), expectedRef);
            if (isMissing(value)) {
                return new ExpectedPayload(Map.of(), failure(
                        "PAYLOAD_REF_MISSING",
                        "TARGET_RESOLUTION_FAILED",
                        "IBM MQ expected payload ref did not resolve to JSON fields.",
                        "Review expected_ref path and expected result fixture."));
            }
            return new ExpectedPayload(value, null);
        } catch (OutsideSuiteRefException e) {
            return new ExpectedPayload(Map.of(), failure(
                    "REF_OUTSIDE_SUITE_ROOT",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ expected_ref must stay inside the suite root.",
                    "Use a checked-in expected_results path under the suite directory."));
        } catch (RuntimeException e) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ expected payload ref did not resolve to JSON fields.",
                    "Review expected_ref path and expected result fixture."));
        }
    }

    private Object resolveRef(Path suiteRoot, String ref) {
        if (!ref.contains("#")) {
            return readObject(suiteFile(suiteRoot, ref));
        }
        String[] parts = ref.split("#", 2);
        Object current = readObject(suiteFile(suiteRoot, parts[0]));
        String pointer = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        for (String part : pointer.split("/")) {
            if (!part.isBlank()) {
                current = mapValue(current).get(part);
            }
        }
        return current;
    }

    private Object readObject(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read IBM MQ fixture: " + path, e);
        }
    }

    private Path suiteFile(Path suiteRoot, String ref) {
        Path root = suiteRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(ref.split("#", 2)[0]).normalize();
        if (!resolved.startsWith(root)) {
            throw new OutsideSuiteRefException();
        }
        return resolved;
    }

    private ParsedDuration duration(ProviderExecutionContext context, ProviderOperationRequest request, String bindAs) {
        String value = firstNonBlank(
                parameterValue(request, bindAs, ""),
                bindingText(context, bindAs),
                "timeout".equals(bindAs) ? "PT10S" : "PT1S");
        try {
            return new ParsedDuration(Duration.parse(value), null);
        } catch (RuntimeException e) {
            return new ParsedDuration(Duration.ZERO, failure(
                    "INVALID_DURATION",
                    "TARGET_RESOLUTION_FAILED",
                    "IBM MQ " + bindAs + " must be an ISO-8601 duration.",
                    "Fix DSL input or Env_Profile binding value `" + bindAs + "`."));
        }
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return firstNonBlank(stringValue(parameter.get("ref")), stringValue(parameter.get("value")));
            }
        }
        return fallback;
    }

    private String bindingText(ProviderExecutionContext context, String key) {
        Object value = valueAtPath(context.bindingValues(), key);
        if (value instanceof Map<?, ?> map) {
            return firstNonBlank(stringValue(map.get("local_ref")), stringValue(map.get("secret_ref")), stringValue(map.get("generated_ref")));
        }
        return stringValue(value);
    }

    private Object valueAtPath(Map<String, Object> map, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
    }

    private ProviderOperationResult failed(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            Map<String, Object> outputs,
            ProviderFailure failure,
            Observation observation) {
        String evidenceRef = writeEvidence(context, request, observation.withFailure(failure));
        Map<String, Object> resolvedOutputs = new LinkedHashMap<>(outputs);
        resolvedOutputs.putIfAbsent("mq_evidence_ref", evidenceRef);
        return ProviderOperationResult.failed(
                resolvedOutputs,
                List.of(new ProviderEvidence("mq_evidence", evidenceRef, true)),
                failure);
    }

    private String writeEvidence(ProviderExecutionContext context, ProviderOperationRequest request, Observation observation) {
        String operationId = safe(firstNonBlank(stringValue(request.outputs().get("_operation_id")), request.operation()));
        String ref = "provider-evidence/ibm_mq/" + operationId + ".yaml";
        Instant now = Instant.now();
        StringBuilder evidence = new StringBuilder();
        evidence.append("evidence_type: ibm_mq_event\n");
        evidence.append("evidence_classification: framework_provider_capability_only\n");
        evidence.append("downstream_release_evidence: false\n");
        evidence.append("provider_type: ").append(context.providerType()).append('\n');
        evidence.append("provider_id: ").append(context.providerId()).append('\n');
        evidence.append("profile: ").append(context.profile()).append('\n');
        evidence.append("runtime_mode: ").append(context.runtimeMode()).append('\n');
        evidence.append("operation: ").append(request.operation()).append('\n');
        evidence.append("queue: ").append(observation.queue()).append('\n');
        evidence.append("browse_only: true\n");
        evidence.append("observation_window:\n");
        evidence.append("  started_at: ").append(observation.startedAt() == null ? now : observation.startedAt()).append('\n');
        evidence.append("  finished_at: ").append(observation.finishedAt() == null ? now : observation.finishedAt()).append('\n');
        evidence.append("timeout: ").append(observation.timeout() == null ? "" : observation.timeout()).append('\n');
        evidence.append("poll_interval: ").append(observation.pollInterval() == null ? "" : observation.pollInterval()).append('\n');
        evidence.append("attempts: ").append(observation.attempts()).append('\n');
        evidence.append("browse_count: ").append(observation.browseCount()).append('\n');
        evidence.append("message_ids: ").append(observation.messageIds()).append('\n');
        evidence.append("last_observed_message_id: ").append(observation.lastObservedMessageId()).append('\n');
        evidence.append("correlation_id: ").append(observation.correlationId()).append('\n');
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
                masked.put(key, secretKey(key) ? "***" : maskPayload(entry.getValue()));
            }
            return masked;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::maskPayload).toList();
        }
        return value == null ? "" : value;
    }

    private boolean secretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("ccdt")
                || normalized.contains("tls");
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
            throw new UncheckedIOException("Failed to write IBM MQ provider evidence: " + path, e);
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

    interface IbmMqClientTransport {

        MqPutResult put(
                MqConnection connection,
                Object payload,
                String messageId,
                String correlationId,
                Duration timeout) throws Exception;

        MqBrowseResult browse(
                MqConnection connection,
                String correlationId,
                Duration timeout,
                Duration pollInterval) throws Exception;
    }

    record MqConnection(
            String queueManager,
            String channel,
            String connName,
            String queue,
            String credential) {
    }

    record MqPutResult(String messageId, String correlationId, Instant putAt) {
    }

    record MqBrowseResult(List<MqMessage> messages, int attempts) {
    }

    private static final class DefaultIbmMqClientTransport implements IbmMqClientTransport {

        @Override
        public MqPutResult put(
                MqConnection connection,
                Object payload,
                String messageId,
                String correlationId,
                Duration timeout) throws Exception {
            MQQueueManager queueManager = null;
            MQQueue queue = null;
            try {
                queueManager = new MQQueueManager(connection.queueManager(), connectionProperties(connection));
                queue = queueManager.accessQueue(connection.queue(),
                        MQConstants.MQOO_OUTPUT | MQConstants.MQOO_FAIL_IF_QUIESCING);
                MQMessage message = new MQMessage();
                message.format = MQConstants.MQFMT_STRING;
                if (!correlationId.isBlank()) {
                    message.correlationId = mqId(correlationId);
                }
                message.writeString(new Yaml().dump(payload));
                MQPutMessageOptions options = new MQPutMessageOptions();
                options.options = MQConstants.MQPMO_NO_SYNCPOINT
                        | MQConstants.MQPMO_NEW_MSG_ID
                        | MQConstants.MQPMO_FAIL_IF_QUIESCING;
                queue.put(message, options);
                return new MqPutResult(hex(message.messageId), correlationId, Instant.now());
            } finally {
                close(queue, queueManager);
            }
        }

        @Override
        public MqBrowseResult browse(
                MqConnection connection,
                String correlationId,
                Duration timeout,
                Duration pollInterval) throws Exception {
            MQQueueManager queueManager = null;
            MQQueue queue = null;
            Instant deadline = Instant.now().plus(timeout);
            int attempts = 0;
            List<MqMessage> observed = new ArrayList<>();
            try {
                queueManager = new MQQueueManager(connection.queueManager(), connectionProperties(connection));
                queue = queueManager.accessQueue(connection.queue(),
                        MQConstants.MQOO_BROWSE | MQConstants.MQOO_FAIL_IF_QUIESCING);
                do {
                    attempts++;
                    observed = browseAvailable(queue, connection.queue(), correlationId, pollInterval, deadline);
                    if (!observed.isEmpty()) {
                        break;
                    }
                } while (Instant.now().isBefore(deadline));
                return new MqBrowseResult(List.copyOf(observed), attempts);
            } finally {
                close(queue, queueManager);
            }
        }

        private List<MqMessage> browseAvailable(
                MQQueue queue,
                String queueName,
                String correlationId,
                Duration pollInterval,
                Instant deadline) throws Exception {
            List<MqMessage> observed = new ArrayList<>();
            int option = MQConstants.MQGMO_BROWSE_FIRST
                    | MQConstants.MQGMO_NO_SYNCPOINT
                    | MQConstants.MQGMO_WAIT
                    | MQConstants.MQGMO_CONVERT
                    | MQConstants.MQGMO_FAIL_IF_QUIESCING;
            while (Instant.now().isBefore(deadline)) {
                MQMessage message = new MQMessage();
                MQGetMessageOptions options = new MQGetMessageOptions();
                options.options = option;
                options.waitInterval = Math.toIntExact(Math.min(
                        Math.max(pollInterval.toMillis(), 1),
                        Math.max(Duration.between(Instant.now(), deadline).toMillis(), 1)));
                try {
                    queue.get(message, options);
                    if (correlationId.isBlank() || correlationId.equals(correlationId(message.correlationId))) {
                        observed.add(new MqMessage(
                                queueName,
                                hex(message.messageId),
                                correlationId(message.correlationId),
                                readPayload(message),
                                Instant.now()));
                    }
                    option = MQConstants.MQGMO_BROWSE_NEXT
                            | MQConstants.MQGMO_NO_SYNCPOINT
                            | MQConstants.MQGMO_NO_WAIT
                            | MQConstants.MQGMO_CONVERT
                            | MQConstants.MQGMO_FAIL_IF_QUIESCING;
                } catch (MQException e) {
                    if (e.reasonCode == MQException.MQRC_NO_MSG_AVAILABLE) {
                        break;
                    }
                    throw e;
                }
            }
            return observed;
        }

        private static Hashtable<String, Object> connectionProperties(MqConnection connection) {
            Hashtable<String, Object> properties = new Hashtable<>();
            HostPort hostPort = hostPort(connection.connName());
            properties.put(MQConstants.HOST_NAME_PROPERTY, hostPort.host());
            properties.put(MQConstants.PORT_PROPERTY, hostPort.port());
            properties.put(MQConstants.CHANNEL_PROPERTY, connection.channel());
            properties.put(MQConstants.TRANSPORT_PROPERTY, MQConstants.TRANSPORT_MQSERIES_CLIENT);
            Credential credential = credential(connection.credential());
            if (!credential.user().isBlank()) {
                properties.put(MQConstants.USER_ID_PROPERTY, credential.user());
            }
            if (!credential.password().isBlank()) {
                properties.put(MQConstants.PASSWORD_PROPERTY, credential.password());
            }
            return properties;
        }

        private static Object readPayload(MQMessage message) throws IOException {
            String text = message.readStringOfByteLength(message.getDataLength());
            Object value = new Yaml().load(text);
            return value == null ? Map.of() : value;
        }

        private static HostPort hostPort(String connName) {
            String first = connName.split(",", 2)[0].trim();
            int open = first.lastIndexOf('(');
            int close = first.lastIndexOf(')');
            if (open > 0 && close > open) {
                return new HostPort(first.substring(0, open), Integer.parseInt(first.substring(open + 1, close)));
            }
            String[] parts = first.split(":", 2);
            if (parts.length == 2) {
                return new HostPort(parts[0], Integer.parseInt(parts[1]));
            }
            return new HostPort(first, 1414);
        }

        private static Credential credential(String value) {
            String[] parts = value.split(":", 2);
            return parts.length == 2 ? new Credential(parts[0], parts[1]) : new Credential(value, "");
        }

        private static byte[] mqId(String value) {
            byte[] id = new byte[24];
            byte[] source = value.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(source, 0, id, 0, Math.min(source.length, id.length));
            return id;
        }

        private static String correlationId(byte[] value) {
            if (value == null || value.length == 0) {
                return "";
            }
            return new String(value, StandardCharsets.UTF_8).trim();
        }

        private static String hex(byte[] value) {
            if (value == null || value.length == 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder("ID:");
            for (byte b : value) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }

        private static void close(MQQueue queue, MQQueueManager queueManager) throws MQException {
            MQException failure = null;
            if (queue != null) {
                try {
                    queue.close();
                } catch (MQException e) {
                    failure = e;
                }
            }
            if (queueManager != null && queueManager.isConnected()) {
                try {
                    queueManager.disconnect();
                } catch (MQException e) {
                    if (failure == null) {
                        failure = e;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record ConnectionSelection(
            String busKey,
            String queueManager,
            String channel,
            String connName,
            String queue,
            String credential,
            ProviderFailure failure) {

        MqConnection toMqConnection() {
            return new MqConnection(queueManager, channel, connName, queue, credential);
        }
    }

    private record ResolvedBinding(String value, ProviderFailure failure) {
    }

    private record HostPort(String host, int port) {
    }

    private record Credential(String user, String password) {
    }

    private record Payload(Object value, ProviderFailure failure) {

        static Payload failed(ProviderFailure failure) {
            return new Payload(Map.of(), failure);
        }
    }

    private record ExpectedPayload(Object value, ProviderFailure failure) {
    }

    private record ParsedDuration(Duration value, ProviderFailure failure) {
    }

    record MqMessage(String queue, String messageId, String correlationId, Object payload, Instant observedAt) {
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
            String queue,
            Instant startedAt,
            Instant finishedAt,
            Duration timeout,
            Duration pollInterval,
            int attempts,
            int browseCount,
            boolean matched,
            List<String> messageIds,
            String lastObservedMessageId,
            String correlationId,
            List<String> matchedFields,
            Object matchedPayload,
            Object lastObservedPayload,
            long durationMs,
            ProviderFailure failure) {

        static Observation empty() {
            return empty("");
        }

        static Observation empty(String queue) {
            return new Observation(queue, null, null, null, null, 0, 0, false, List.of(), "", "", List.of(), Map.of(), null, 0, null);
        }

        Observation withFailure(ProviderFailure replacement) {
            return new Observation(
                    queue,
                    startedAt,
                    finishedAt,
                    timeout,
                    pollInterval,
                    attempts,
                    browseCount,
                    matched,
                    messageIds,
                    lastObservedMessageId,
                    correlationId,
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
