package com.specdriven.regression.provider.kafka;

import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.yaml.snakeyaml.Yaml;

public class KafkaProviderRuntime implements ProviderRuntime {

    private final Map<String, List<KafkaMessage>> messagesByBusTopic = new ConcurrentHashMap<>();
    private final AtomicLong offsetSequence = new AtomicLong();
    private final KafkaClientTransport kafkaClientTransport;
    private final Yaml yaml = new Yaml();

    public KafkaProviderRuntime() {
        this(new DefaultKafkaClientTransport());
    }

    KafkaProviderRuntime(KafkaClientTransport kafkaClientTransport) {
        this.kafkaClientTransport = kafkaClientTransport;
    }

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        ConnectionSelection connection = connection(context);
        if (connection.failure() != null) {
            return failed(context, request, Map.of(), connection.failure(), Observation.empty());
        }
        return switch (request.operation()) {
            case "kafka_publish" -> publish(context, request, connection);
            case "kafka_observe", "kafka_payload_match" -> observe(context, request, connection);
            default -> failed(
                    context,
                    request,
                    Map.of(),
                    failure(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported Kafka operation `" + request.operation() + "`.",
                            "Use kafka_publish, kafka_observe, or kafka_payload_match."),
                    Observation.empty(connection.topic()));
        };
    }

    private ProviderOperationResult publish(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection) {
        Instant startedAt = Instant.now();
        Payload payload = payload(context, request);
        if (payload.failure() != null) {
            return failed(context, request, Map.of("topic", connection.topic()), payload.failure(), Observation.empty(connection.topic()));
        }
        ParsedDuration timeout = duration(context, request, "timeout");
        if (timeout.failure() != null) {
            return failed(context, request, Map.of("topic", connection.topic()), timeout.failure(), Observation.empty(connection.topic()));
        }
        String key = parameterValue(request, "key", "");
        if ("native".equals(context.runtimeMode())) {
            return nativePublish(context, request, connection, payload, timeout, key, startedAt);
        }
        long offset = offsetSequence.incrementAndGet() - 1;
        Instant publishedAt = Instant.now();
        messagesByBusTopic.computeIfAbsent(connection.busKey() + "|" + connection.topic(), ignored -> new ArrayList<>())
                .add(new KafkaMessage(connection.topic(), key, 0, offset, payload.value(), publishedAt));
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        String evidenceRef = writeEvidence(context, request, new Observation(
                connection.topic(),
                connection.consumerGroup(),
                "publish",
                startedAt,
                Instant.now(),
                timeout.value(),
                Duration.ZERO,
                1,
                1,
                true,
                List.of(),
                payload.value(),
                null,
                offset,
                durationMs,
                null));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("topic", connection.topic());
        outputs.put("partition", 0);
        outputs.put("offset", offset);
        outputs.put("key", key);
        outputs.put("published_at", publishedAt.toString());
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
        ExpectedPayload expected = expectedPayload(context, request);
        if ("kafka_payload_match".equals(request.operation()) && expected.failure() != null) {
            return failed(context, request, Map.of("topic", connection.topic()), expected.failure(), Observation.empty(connection.topic()));
        }
        ParsedDuration timeout = duration(context, request, "timeout");
        if (timeout.failure() != null) {
            return failed(context, request, Map.of("topic", connection.topic()), timeout.failure(), Observation.empty(connection.topic()));
        }
        ParsedDuration pollInterval = duration(context, request, "poll_interval");
        if (pollInterval.failure() != null) {
            return failed(context, request, Map.of("topic", connection.topic()), pollInterval.failure(), Observation.empty(connection.topic()));
        }
        ParsedInstant consumeFrom = consumeFrom(request, startedAt);
        if (consumeFrom.failure() != null) {
            return failed(context, request, Map.of("topic", connection.topic()), consumeFrom.failure(), Observation.empty(connection.topic()));
        }
        String key = parameterValue(request, "key", "");
        if ("native".equals(context.runtimeMode())) {
            return nativeObserve(context, request, connection, expected, timeout, pollInterval, consumeFrom, key, startedAt);
        }
        Instant deadline = Instant.now().plus(timeout.value());
        int attempts = 0;
        List<KafkaMessage> observed = List.of();
        Match match = Match.notMatched();
        do {
            attempts++;
            observed = messagesAfter(connection.busKey(), connection.topic(), consumeFrom.value(), key);
            match = match(request.operation(), observed, expected.value());
            if (match.matched()) {
                break;
            }
            sleepUntilNextPoll(deadline, pollInterval.value());
        } while (Instant.now().isBefore(deadline));

        KafkaMessage lastObserved = observed.isEmpty() ? null : observed.get(observed.size() - 1);
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        ProviderFailure failure = match.matched()
                ? null
                : failureForObserve(request.operation(), timeout.value(), observed);
        String evidenceRef = writeEvidence(context, request, new Observation(
                connection.topic(),
                connection.consumerGroup(),
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
                lastObserved == null ? -1 : lastObserved.offset(),
                durationMs,
                failure));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("topic", connection.topic());
        outputs.put("observed_count", observed.size());
        outputs.put("last_observed_offset", lastObserved == null ? -1 : lastObserved.offset());
        outputs.put("matched", match.matched());
        outputs.put("matched_fields", match.matchedFields());
        outputs.put("duration_ms", durationMs);
        outputs.put("event_evidence_ref", evidenceRef);
        if ("kafka_payload_match".equals(request.operation())) {
            outputs.put("assertion_evidence_ref", evidenceRef);
        }
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

    private ProviderOperationResult nativePublish(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection,
            Payload payload,
            ParsedDuration timeout,
            String key,
            Instant startedAt) {
        try {
            KafkaPublishResult publishResult = kafkaClientTransport.publish(
                    connection.toKafkaConnection(),
                    key,
                    payload.value(),
                    timeout.value());
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeEvidence(context, request, new Observation(
                    connection.topic(),
                    connection.consumerGroup(),
                    "publish",
                    startedAt,
                    Instant.now(),
                    timeout.value(),
                    Duration.ZERO,
                    1,
                    1,
                    true,
                    List.of(),
                    payload.value(),
                    null,
                    publishResult.offset(),
                    durationMs,
                    null));
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("topic", connection.topic());
            outputs.put("partition", publishResult.partition());
            outputs.put("offset", publishResult.offset());
            outputs.put("key", key);
            outputs.put("published_at", publishResult.publishedAt().toString());
            outputs.put("event_evidence_ref", evidenceRef);
            return ProviderOperationResult.passed(
                    outputs,
                    List.of(new ProviderEvidence("event_evidence", evidenceRef, true)));
        } catch (Exception e) {
            return failed(
                    context,
                    request,
                    Map.of("topic", connection.topic()),
                    kafkaConnectionFailure(e),
                    Observation.empty(connection.topic()));
        }
    }

    private ProviderOperationResult nativeObserve(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ConnectionSelection connection,
            ExpectedPayload expected,
            ParsedDuration timeout,
            ParsedDuration pollInterval,
            ParsedInstant consumeFrom,
            String key,
            Instant startedAt) {
        try {
            KafkaObserveResult observedResult = kafkaClientTransport.observe(
                    connection.toKafkaConnection(),
                    key,
                    consumeFrom.value(),
                    timeout.value(),
                    pollInterval.value());
            List<KafkaMessage> observed = observedResult.messages();
            Match match = match(request.operation(), observed, expected.value());
            KafkaMessage lastObserved = observed.isEmpty() ? null : observed.get(observed.size() - 1);
            ProviderFailure failure = match.matched()
                    ? null
                    : failureForObserve(request.operation(), timeout.value(), observed);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeEvidence(context, request, new Observation(
                    connection.topic(),
                    connection.consumerGroup(),
                    consumeFrom.label(),
                    consumeFrom.value(),
                    Instant.now(),
                    timeout.value(),
                    pollInterval.value(),
                    observedResult.attempts(),
                    observed.size(),
                    match.matched(),
                    match.matchedFields(),
                    match.matchedPayload(),
                    lastObserved == null ? null : lastObserved.payload(),
                    lastObserved == null ? -1 : lastObserved.offset(),
                    durationMs,
                    failure));
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("topic", connection.topic());
            outputs.put("observed_count", observed.size());
            outputs.put("last_observed_offset", lastObserved == null ? -1 : lastObserved.offset());
            outputs.put("matched", match.matched());
            outputs.put("matched_fields", match.matchedFields());
            outputs.put("duration_ms", durationMs);
            outputs.put("event_evidence_ref", evidenceRef);
            if ("kafka_payload_match".equals(request.operation())) {
                outputs.put("assertion_evidence_ref", evidenceRef);
            }
            if (failure == null) {
                return ProviderOperationResult.passed(
                        outputs,
                        List.of(new ProviderEvidence("event_evidence", evidenceRef, true)));
            }
            return ProviderOperationResult.failed(
                    outputs,
                    List.of(new ProviderEvidence("event_evidence", evidenceRef, true)),
                    failure);
        } catch (Exception e) {
            return failed(
                    context,
                    request,
                    Map.of("topic", connection.topic()),
                    kafkaConnectionFailure(e),
                    Observation.empty(connection.topic()));
        }
    }

    private List<KafkaMessage> messagesAfter(String busKey, String topic, Instant consumeFrom, String key) {
        return messagesByBusTopic.getOrDefault(busKey + "|" + topic, List.of()).stream()
                .filter(message -> !message.observedAt().isBefore(consumeFrom))
                .filter(message -> key.isBlank() || key.equals(message.key()))
                .toList();
    }

    private Match match(String operation, List<KafkaMessage> observed, Object expectedPayload) {
        if (observed.isEmpty()) {
            return Match.notMatched();
        }
        if (!"kafka_payload_match".equals(operation)) {
            return Match.matched(List.of(), observed.get(0).payload());
        }
        Map<String, Object> expected = mapValue(expectedPayload);
        if (expected.isEmpty()) {
            return Match.notMatched();
        }
        for (KafkaMessage message : observed) {
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

    private ProviderFailure failureForObserve(String operation, Duration timeout, List<KafkaMessage> observed) {
        if (observed.isEmpty()) {
            return failure(
                    "kafka_observe".equals(operation) ? "KAFKA_TIMEOUT" : "EVENT_NOT_FOUND",
                    "KAFKA_TIMEOUT",
                    "No Kafka event was observed within " + timeout + ".",
                    "Review topic binding, consumer group, consume_from, publish timing, and timeout.");
        }
        return failure(
                "PAYLOAD_MISMATCH",
                "ASSERTION_FAILED",
                "Observed Kafka payload did not match expected JSON fields.",
                "Review expected payload fields and Kafka event evidence.");
    }

    private ConnectionSelection connection(ProviderExecutionContext context) {
        String bootstrapServers = bindingText(context, "bootstrap_servers");
        String topic = bindingText(context, "topic");
        String consumerGroup = bindingText(context, "consumer_group");
        ResolvedBinding resolvedBootstrap = resolveEnvBinding(bootstrapServers, "bootstrap_servers");
        if (resolvedBootstrap.failure() != null) {
            return new ConnectionSelection("", "", topic, consumerGroup, resolvedBootstrap.failure());
        }
        bootstrapServers = resolvedBootstrap.value();
        if (bootstrapServers.isBlank()) {
            return new ConnectionSelection("", "", topic, consumerGroup, failure(
                    "KAFKA_CONNECTION_FAILED",
                    "KAFKA_CONNECTION_FAILED",
                    "Kafka bootstrap_servers binding is missing.",
                    "Declare bootstrap_servers in Env_Profile for this provider_id."));
        }
        if (topic.isBlank()) {
            return new ConnectionSelection("", "", "", consumerGroup, failure(
                    "TOPIC_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka topic binding is missing.",
                    "Declare topic in Env_Profile for this provider_id."));
        }
        if (consumerGroup.isBlank()) {
            return new ConnectionSelection("", "", topic, "", failure(
                    "KAFKA_CONNECTION_FAILED",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka consumer_group binding is missing.",
                    "Declare consumer_group in Env_Profile for this provider_id."));
        }
        return new ConnectionSelection(
                bootstrapServers,
                context.runtimeMode() + ":" + bootstrapServers + ":" + consumerGroup,
                topic,
                consumerGroup,
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
                    "KAFKA_CONNECTION_FAILED",
                    "KAFKA_CONNECTION_FAILED",
                    "Kafka " + bindingName + " env ref `" + value + "` is not set.",
                    "Set environment variable `" + envName + "` before running this execution profile."));
        }
        return new ResolvedBinding(resolved, null);
    }

    private ProviderFailure kafkaConnectionFailure(Exception e) {
        return failure(
                "KAFKA_CONNECTION_FAILED",
                "KAFKA_CONNECTION_FAILED",
                "Kafka client operation failed. Cause type: " + e.getClass().getSimpleName() + ".",
                "Verify the execution profile supplies a reachable external broker and valid Kafka bindings.");
    }

    private Payload payload(ProviderExecutionContext context, ProviderOperationRequest request) {
        String payloadRef = parameterValue(request, "payload_ref", "");
        String payloadText = parameterValue(request, "payload", "");
        if (payloadRef.isBlank() && payloadText.isBlank()) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka publish requires payload_ref or payload.",
                    "Add checked-in payload_ref input or inline payload input."));
        }
        try {
            String text = payloadRef.isBlank() ? payloadText : Files.readString(suiteFile(context.suiteRoot(), payloadRef));
            if (text.isBlank()) {
                return Payload.failed(failure(
                        "PAYLOAD_REF_MISSING",
                        "TARGET_RESOLUTION_FAILED",
                        "Kafka payload is empty.",
                        "Provide a non-empty JSON payload."));
            }
            Object value = yaml.load(text);
            return new Payload(value == null ? Map.of() : value, null);
        } catch (IOException e) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka payload ref is missing: " + payloadRef,
                    "Restore checked-in Kafka payload fixture."));
        } catch (OutsideSuiteRefException e) {
            return Payload.failed(failure(
                    "REF_OUTSIDE_SUITE_ROOT",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka payload_ref must stay inside the suite root.",
                    "Use a checked-in fixture path under the suite directory."));
        } catch (RuntimeException e) {
            return Payload.failed(failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka payload JSON is invalid.",
                    "Fix the payload fixture before running."));
        }
    }

    private ExpectedPayload expectedPayload(ProviderExecutionContext context, ProviderOperationRequest request) {
        String expectedRef = parameterValue(request, "expected_ref", "");
        if (expectedRef.isBlank()) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka payload match requires expected_ref.",
                    "Add expected result ref input `expected_ref`."));
        }
        try {
            Object value = resolveRef(context.suiteRoot(), expectedRef);
            if (isMissing(value)) {
                return new ExpectedPayload(Map.of(), failure(
                        "PAYLOAD_REF_MISSING",
                        "TARGET_RESOLUTION_FAILED",
                        "Kafka expected payload ref did not resolve to JSON fields.",
                        "Review expected_ref path and expected result fixture."));
            }
            return new ExpectedPayload(value, null);
        } catch (OutsideSuiteRefException e) {
            return new ExpectedPayload(Map.of(), failure(
                    "REF_OUTSIDE_SUITE_ROOT",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka expected_ref must stay inside the suite root.",
                    "Use a checked-in expected_results path under the suite directory."));
        } catch (RuntimeException e) {
            return new ExpectedPayload(Map.of(), failure(
                    "PAYLOAD_REF_MISSING",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka expected payload ref did not resolve to JSON fields.",
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
            throw new UncheckedIOException("Failed to read Kafka fixture: " + path, e);
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
                    "Kafka consume_from must be `test_start_time`, `earliest`, or an ISO-8601 instant.",
                    "Fix DSL input `consume_from`."));
        }
    }

    private ParsedDuration duration(ProviderExecutionContext context, ProviderOperationRequest request, String bindAs) {
        String value = firstNonBlank(
                parameterValue(request, bindAs, ""),
                bindingText(context, bindAs),
                "timeout".equals(bindAs) ? "PT10S" : "PT0.5S");
        try {
            return new ParsedDuration(Duration.parse(value), null);
        } catch (RuntimeException e) {
            return new ParsedDuration(Duration.ZERO, failure(
                    "INVALID_DURATION",
                    "TARGET_RESOLUTION_FAILED",
                    "Kafka " + bindAs + " must be an ISO-8601 duration.",
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
        resolvedOutputs.putIfAbsent("event_evidence_ref", evidenceRef);
        return ProviderOperationResult.failed(
                resolvedOutputs,
                List.of(new ProviderEvidence("event_evidence", evidenceRef, true)),
                failure);
    }

    private String writeEvidence(ProviderExecutionContext context, ProviderOperationRequest request, Observation observation) {
        String operationId = safe(firstNonBlank(stringValue(request.outputs().get("_operation_id")), request.operation()));
        String ref = "provider-evidence/kafka/" + operationId + ".yaml";
        Instant now = Instant.now();
        StringBuilder evidence = new StringBuilder();
        evidence.append("evidence_type: kafka_event\n");
        evidence.append("evidence_classification: framework_provider_capability_only\n");
        evidence.append("downstream_release_evidence: false\n");
        evidence.append("provider_type: ").append(context.providerType()).append('\n');
        evidence.append("provider_id: ").append(context.providerId()).append('\n');
        evidence.append("profile: ").append(context.profile()).append('\n');
        evidence.append("runtime_mode: ").append(context.runtimeMode()).append('\n');
        evidence.append("operation: ").append(request.operation()).append('\n');
        evidence.append("topic: ").append(observation.topic()).append('\n');
        evidence.append("consumer_group: ").append(observation.consumerGroup()).append('\n');
        evidence.append("consume_from: ").append(observation.consumeFrom()).append('\n');
        evidence.append("observation_window:\n");
        evidence.append("  started_at: ").append(observation.startedAt() == null ? now : observation.startedAt()).append('\n');
        evidence.append("  finished_at: ").append(observation.finishedAt() == null ? now : observation.finishedAt()).append('\n');
        evidence.append("timeout: ").append(observation.timeout() == null ? "" : observation.timeout()).append('\n');
        evidence.append("poll_interval: ").append(observation.pollInterval() == null ? "" : observation.pollInterval()).append('\n');
        evidence.append("attempts: ").append(observation.attempts()).append('\n');
        evidence.append("observed_count: ").append(observation.observedCount()).append('\n');
        evidence.append("last_observed_offset: ").append(observation.lastObservedOffset()).append('\n');
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
                || normalized.contains("sasl")
                || normalized.contains("ssl");
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
            throw new UncheckedIOException("Failed to write Kafka provider evidence: " + path, e);
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

    interface KafkaClientTransport {

        KafkaPublishResult publish(KafkaConnection connection, String key, Object payload, Duration timeout)
                throws Exception;

        KafkaObserveResult observe(
                KafkaConnection connection,
                String key,
                Instant consumeFrom,
                Duration timeout,
                Duration pollInterval)
                throws Exception;
    }

    record KafkaConnection(String bootstrapServers, String topic, String consumerGroup) {
    }

    record KafkaPublishResult(int partition, long offset, Instant publishedAt) {
    }

    record KafkaObserveResult(List<KafkaMessage> messages, int attempts) {
    }

    record KafkaMessage(String topic, String key, int partition, long offset, Object payload, Instant observedAt) {
    }

    private static final class DefaultKafkaClientTransport implements KafkaClientTransport {
        private final Yaml yaml = new Yaml();

        @Override
        public KafkaPublishResult publish(KafkaConnection connection, String key, Object payload, Duration timeout)
                throws Exception {
            Properties properties = producerProperties(connection);
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        connection.topic(),
                        key,
                        serialize(payload));
                RecordMetadata metadata = producer.send(record).get(Math.max(timeout.toMillis(), 1), TimeUnit.MILLISECONDS);
                return new KafkaPublishResult(metadata.partition(), metadata.offset(), Instant.now());
            }
        }

        @Override
        public KafkaObserveResult observe(
                KafkaConnection connection,
                String key,
                Instant consumeFrom,
                Duration timeout,
                Duration pollInterval) {
            Properties properties = consumerProperties(connection);
            List<KafkaMessage> messages = new ArrayList<>();
            int attempts = 0;
            Instant deadline = Instant.now().plus(timeout);
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
                consumer.subscribe(List.of(connection.topic()));
                do {
                    attempts++;
                    for (ConsumerRecord<String, String> record : consumer.poll(pollInterval)) {
                        if (!key.isBlank() && !key.equals(record.key())) {
                            continue;
                        }
                        Instant observedAt = Instant.ofEpochMilli(record.timestamp());
                        if (observedAt.isBefore(consumeFrom)) {
                            continue;
                        }
                        messages.add(new KafkaMessage(
                                record.topic(),
                                record.key(),
                                record.partition(),
                                record.offset(),
                                parsePayload(record.value()),
                                observedAt));
                    }
                } while (Instant.now().isBefore(deadline));
            }
            return new KafkaObserveResult(List.copyOf(messages), attempts);
        }

        private Properties producerProperties(KafkaConnection connection) {
            Properties properties = new Properties();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers());
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            properties.put(ProducerConfig.ACKS_CONFIG, "all");
            return properties;
        }

        private Properties consumerProperties(KafkaConnection connection) {
            Properties properties = new Properties();
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connection.bootstrapServers());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, connection.consumerGroup());
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            return properties;
        }

        private String serialize(Object payload) {
            if (payload instanceof String text) {
                return text;
            }
            return yaml.dump(payload);
        }

        private Object parsePayload(String payload) {
            Object parsed = yaml.load(payload);
            return parsed == null ? Map.of() : parsed;
        }
    }

    private record ConnectionSelection(
            String bootstrapServers,
            String busKey,
            String topic,
            String consumerGroup,
            ProviderFailure failure) {

        KafkaConnection toKafkaConnection() {
            return new KafkaConnection(bootstrapServers, topic, consumerGroup);
        }
    }

    private record ResolvedBinding(String value, ProviderFailure failure) {
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

    private record ParsedInstant(Instant value, String label, ProviderFailure failure) {
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
            String topic,
            String consumerGroup,
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
            long lastObservedOffset,
            long durationMs,
            ProviderFailure failure) {

        static Observation empty() {
            return empty("");
        }

        static Observation empty(String topic) {
            return new Observation(topic, "", "", null, null, null, null, 0, 0, false, List.of(), Map.of(), null, -1, 0, null);
        }

        Observation withFailure(ProviderFailure replacement) {
            return new Observation(
                    topic,
                    consumerGroup,
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
                    lastObservedOffset,
                    durationMs,
                    replacement);
        }
    }

    private static final class OutsideSuiteRefException extends RuntimeException {
    }
}
