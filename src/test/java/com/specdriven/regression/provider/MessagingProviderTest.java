package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesKafkaThroughNativeTransportAndWritesEvidence() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-001\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event",
                                        "serialization", "json",
                                        "requires_correlation", true,
                                        "correlation_id", "PAY-001"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.providerType()).isEqualTo("kafka");
        assertThat(transport.request.connectionRef()).isEqualTo("env://KAFKA_BOOTSTRAP_SERVERS");
        assertThat(transport.request.targetRef()).isEqualTo("payment.events");
        assertThat(transport.request.correlationId()).isEqualTo("PAY-001");
        assertThat(Files.readString(result.stdoutLog())).contains("published 1 native message");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("{\"eventId\":\"EVT-001\"}\n");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: kafka")
                .contains("connection_ref: env://KAFKA_BOOTSTRAP_SERVERS")
                .contains("topic_ref: payment.events")
                .contains("correlation_id: PAY-001")
                .contains("message_count: 1")
                .contains("payload_binding: payment_event");
    }

    @Test
    void publishesNatsThroughNativeTransportAndWritesSubjectEvidence() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-001\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event",
                                        "serialization", "json",
                                        "requires_correlation", true,
                                        "correlation_key", "paymentId"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.providerType()).isEqualTo("nats");
        assertThat(transport.request.connectionRef()).isEqualTo("nats://127.0.0.1:4222");
        assertThat(transport.request.targetRef()).isEqualTo("payment.events");
        assertThat(transport.request.correlationId()).isEqualTo("paymentId");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: nats")
                .contains("connection_ref: nats://127.0.0.1:4222")
                .contains("subject_ref: payment.events")
                .contains("correlation_id: paymentId")
                .contains("message_count: 1")
                .contains("payload_binding: payment_event");
    }

    @Test
    void requestsNatsReplyThroughNativeTransportAndWritesEvidence() throws Exception {
        writePayload("fixtures/requests/payment-authorization.json", "{\"paymentId\":\"PAY-REQ-001\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport(
                new MessagingTransportResult(
                        "requested 1 native reply from payment.authorization\n",
                        "{\"approved\":true,\"paymentId\":\"PAY-REQ-001\"}\n",
                        1));

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_authorization",
                Map.of(
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.authorization",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-authorization.json"),
                        "actions", Map.of(
                                "request_payment_authorization", Map.of(
                                        "mode", "request_reply",
                                        "payload_binding", "authorization_request",
                                        "serialization", "json",
                                        "correlation_id", "PAY-REQ-001"))),
                Map.of("steps", List.of(Map.of("action", "request_payment_authorization"))),
                List.of(new ResolvedBinding(
                        "authorization_request",
                        "message_event",
                        "fixtures/requests/payment-authorization.json")));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.providerType()).isEqualTo("nats");
        assertThat(transport.request.mode()).isEqualTo("request_reply");
        assertThat(transport.request.payloadBinding()).isEqualTo("authorization_request");
        assertThat(transport.request.payload()).isEqualTo("{\"paymentId\":\"PAY-REQ-001\"}\n");
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("requested 1 native reply from payment.authorization\n");
        assertThat(Files.readString(result.actualOutput()))
                .isEqualTo("{\"approved\":true,\"paymentId\":\"PAY-REQ-001\"}\n");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: nats")
                .contains("subject_ref: payment.authorization")
                .contains("action: request_payment_authorization")
                .contains("mode: request_reply")
                .contains("payload_binding: authorization_request")
                .contains("correlation_id: PAY-REQ-001")
                .contains("message_count: 1");
    }

    @Test
    void observesKafkaThroughNativeTransportWithoutPayloadBindingAndWritesEvidence() throws Exception {
        RecordingMessagingTransport transport = new RecordingMessagingTransport(
                new MessagingTransportResult(
                        "observed 2 native messages from payment.events\n",
                        "{\"eventId\":\"EVT-003\"}\n{\"eventId\":\"EVT-004\"}\n",
                        2));

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "observe_payment_event", Map.of(
                                        "mode", "observe",
                                        "serialization", "json",
                                        "min_count", 2,
                                        "requires_correlation", true,
                                        "correlation_id", "PAY-003"))),
                Map.of("steps", List.of(Map.of("action", "observe_payment_event"))),
                List.of());

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.providerType()).isEqualTo("kafka");
        assertThat(transport.request.mode()).isEqualTo("observe");
        assertThat(transport.request.payloadBinding()).isEmpty();
        assertThat(transport.request.payload()).isEmpty();
        assertThat(transport.request.correlationId()).isEqualTo("PAY-003");
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("observed 2 native messages from payment.events\n");
        assertThat(Files.readString(result.actualOutput()))
                .isEqualTo("{\"eventId\":\"EVT-003\"}\n{\"eventId\":\"EVT-004\"}\n");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: kafka")
                .contains("action: observe_payment_event")
                .contains("mode: observe")
                .contains("correlation_id: PAY-003")
                .contains("message_count: 2")
                .doesNotContain("payload_binding:");
    }

    @Test
    void observesWithDeclaredPayloadBindingWithoutRequiringPayloadResolution() throws Exception {
        RecordingMessagingTransport transport = new RecordingMessagingTransport(
                new MessagingTransportResult(
                        "observed 1 native message from payment.events\n",
                        "{\"eventId\":\"EVT-observed\"}\n",
                        1));

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "actions", Map.of(
                                "observe_payment_event", Map.of(
                                        "mode", "observe",
                                        "payload_binding", "optional_observe_filter"))),
                Map.of("steps", List.of(Map.of("action", "observe_payment_event"))),
                List.of());

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.mode()).isEqualTo("observe");
        assertThat(transport.request.payloadBinding()).isEqualTo("optional_observe_filter");
        assertThat(transport.request.payload()).isEmpty();
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("status: passed")
                .contains("mode: observe")
                .contains("payload_binding: optional_observe_filter")
                .contains("message_count: 1");
    }

    @Test
    void cleansKafkaThroughNativeTransportWithoutPayloadBindingAndWritesEvidence() throws Exception {
        RecordingMessagingTransport transport = new RecordingMessagingTransport(
                new MessagingTransportResult(
                        "cleaned 3 native messages from payment.events\n",
                        "",
                        3));

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "cleanup_payment_event", Map.of(
                                        "mode", "cleanup",
                                        "cleanup_strategy", "drain",
                                        "max_count", 25,
                                        "serialization", "json"))),
                Map.of("steps", List.of(Map.of("action", "cleanup_payment_event"))),
                List.of());

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.providerType()).isEqualTo("kafka");
        assertThat(transport.request.mode()).isEqualTo("cleanup");
        assertThat(transport.request.payloadBinding()).isEmpty();
        assertThat(transport.request.payload()).isEmpty();
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("cleaned 3 native messages from payment.events\n");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: kafka")
                .contains("action: cleanup_payment_event")
                .contains("mode: cleanup")
                .contains("cleanup_strategy: drain")
                .contains("message_count: 3")
                .doesNotContain("payload_binding:");
    }

    @Test
    void preservesObservationContextWhenTransportFails() throws Exception {
        MessagingTransport transport = new RecordingMessagingTransport() {
            @Override
            public MessagingTransportResult execute(MessagingTransportRequest request) throws java.io.IOException {
                throw new java.io.IOException("observed 0 messages before timeout");
            }
        };

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "observe_payment_event", Map.of(
                                        "mode", "observe",
                                        "serialization", "json",
                                        "min_count", 1,
                                        "requires_correlation", true,
                                        "correlation_id", "PAY-404"))),
                Map.of("steps", List.of(Map.of("action", "observe_payment_event"))),
                List.of());

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog())).contains("observed 0 messages before timeout");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: failed")
                .contains("action: observe_payment_event")
                .contains("mode: observe")
                .contains("correlation_id: PAY-404")
                .contains("message_count: 0")
                .doesNotContain("payload_binding:");
    }

    @Test
    void usesFirstMessageEventBindingWhenActionDoesNotDeclarePayloadBinding() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-002\"}\n");

        AdapterExecutionResult result = executeProvider(
                "payment_events",
                Map.of(
                        "provider_type", "local",
                        "subject_ref", "mock://payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of("mode", "publish"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog()))
                .contains("published 1 message to mock://payment.events");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("{\"eventId\":\"EVT-002\"}\n");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: local")
                .contains("subject_ref: mock://payment.events")
                .contains("payload_binding: payment_event")
                .contains("message_count: 1");
    }

    @Test
    void requestsMessageThroughNativeTransportWithPayloadAndFallbackTimeout() throws Exception {
        writePayload("fixtures/requests/payment.json", "{\"paymentId\":\"PAY-REQUEST-001\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport(
                new MessagingTransportResult(
                        "requested 1 native message from payment.requests\n",
                        "{\"accepted\":true}\n",
                        1));

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_requests",
                Map.of(
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.requests",
                        "timeout_seconds", "",
                        "actions", Map.of(
                                "request_payment", Map.of(
                                        "mode", "request",
                                        "payload_binding", "payment_request"))),
                Map.of("steps", List.of(Map.of("action", "request_payment"))),
                List.of(new ResolvedBinding("payment_request", "message_event", "fixtures/requests/payment.json")));

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.mode()).isEqualTo("request");
        assertThat(transport.request.timeoutSeconds()).isEqualTo(300);
        assertThat(transport.request.payload()).isEqualTo("{\"paymentId\":\"PAY-REQUEST-001\"}\n");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("mode: request")
                .contains("payload_binding: payment_request")
                .contains("message_count: 1");
    }

    @Test
    void usesEmptyActionWhenConfiguredActionValueIsNotAMap() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-FALLBACK\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "mock",
                        "endpoint_ref", "mock://payment.events",
                        "actions", Map.of("publish_payment_event", "invalid-action-shape")),
                Map.of("steps", List.of("not-a-map")),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.actionName()).isEmpty();
        assertThat(transport.request.mode()).isEqualTo("publish");
        assertThat(transport.request.payloadBinding()).isEqualTo("payment_event");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("endpoint_ref: mock://payment.events")
                .contains("action: ")
                .contains("mode: publish")
                .contains("payload_binding: payment_event");
    }

    @Test
    void usesEmptyActionWhenNamedActionValueIsNotAMap() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-NAMED-FALLBACK\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "mock",
                        "endpoint_ref", "mock://payment.events",
                        "actions", Map.of("publish_payment_event", "invalid-action-shape")),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.actionName()).isEqualTo("publish_payment_event");
        assertThat(transport.request.mode()).isEqualTo("publish");
        assertThat(transport.request.payloadBinding()).isEqualTo("payment_event");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("action: publish_payment_event")
                .contains("mode: publish")
                .contains("payload_binding: payment_event");
    }

    @Test
    void usesEmptyActionWhenActionMapIsEmpty() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-EMPTY-ACTION\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "mock",
                        "endpoint_ref", "mock://payment.events",
                        "actions", Map.of()),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.actionName()).isEqualTo("publish_payment_event");
        assertThat(transport.request.mode()).isEqualTo("publish");
        assertThat(transport.request.payloadBinding()).isEqualTo("payment_event");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("action: publish_payment_event")
                .contains("mode: publish")
                .contains("payload_binding: payment_event");
    }

    @Test
    void allowsObservationWithoutTargetRefAndOmitsTargetEvidenceField() throws Exception {
        RecordingMessagingTransport transport = new RecordingMessagingTransport(
                new MessagingTransportResult("observed local broker\n", "", 0));

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "broker_observer",
                Map.of(
                        "provider_type", "mock",
                        "connection_ref", "mock://broker",
                        "actions", Map.of(
                                "observe_broker", Map.of("mode", "observe"))),
                Map.of("steps", List.of()),
                List.of());

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.actionName()).isEmpty();
        assertThat(transport.request.targetRef()).isEmpty();
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("connection_ref: mock://broker")
                .contains("mode: observe")
                .doesNotContain("endpoint_ref:")
                .doesNotContain("topic_ref:")
                .doesNotContain("subject_ref:")
                .doesNotContain("stream_ref:");
    }

    @Test
    void failsUnsupportedMessagingProviderTypeBeforeTransportInvocation() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-unsupported\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "legacy_events",
                Map.of(
                        "provider_type", "rabbitmq",
                        "endpoint_ref", "amqp://legacy/events",
                        "actions", Map.of(
                                "publish_legacy_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event"))),
                Map.of("steps", List.of(Map.of("action", "publish_legacy_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(transport.request).isNull();
        assertThat(Files.readString(result.stderrLog()))
                .contains("Unsupported messaging provider_type `rabbitmq`");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("status: failed")
                .contains("provider_type: rabbitmq")
                .contains("endpoint_ref: amqp://legacy/events")
                .contains("payload_binding: payment_event")
                .contains("error: Unsupported messaging provider_type `rabbitmq`");
    }

    @Test
    void failsWhenRequiredPayloadBindingCannotBeResolved() throws Exception {
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "local",
                        "subject_ref", "mock://payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "missing_event"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(transport.request).isNull();
        assertThat(Files.readString(result.stderrLog()))
                .contains("Cannot resolve messaging payload binding `missing_event`");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("status: failed")
                .contains("payload_binding: missing_event")
                .contains("message_count: 0");
    }

    @Test
    void usesFirstConfiguredActionWhenTestCaseDoesNotNameActionAndParsesStringTimeout() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-stream\"}\n");
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_stream",
                Map.of(
                        "provider_type", "mock",
                        "connection_ref", "mock://broker",
                        "stream_ref", "payment.stream",
                        "timeout_seconds", "7",
                        "actions", Map.of(
                                "publish_stream_event", Map.of(
                                        "mode", "publish",
                                        "message_binding", "payment_event",
                                        "correlation_id_ref", "PAY-STREAM-001"))),
                Map.of(),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        assertThat(result.exitCode()).isZero();
        assertThat(transport.request.actionName()).isEmpty();
        assertThat(transport.request.targetField()).isEqualTo("stream_ref");
        assertThat(transport.request.targetRef()).isEqualTo("payment.stream");
        assertThat(transport.request.timeoutSeconds()).isEqualTo(7);
        assertThat(transport.request.payloadBinding()).isEqualTo("payment_event");
        assertThat(transport.request.correlationId()).isEqualTo("PAY-STREAM-001");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("stream_ref: payment.stream")
                .contains("payload_binding: payment_event")
                .contains("correlation_id: PAY-STREAM-001");
    }

    @Test
    void convertsPayloadReadFailureIntoFailedMessagingEvidence() throws Exception {
        AdapterExecutionResult result = executeProvider(
                "payment_events",
                Map.of(
                        "provider_type", "local",
                        "topic_ref", "payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "missing_event"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("missing_event", "message_event", "fixtures/events/missing.json")));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog())).contains("fixtures/events/missing.json");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("status: failed")
                .contains("payload_binding: missing_event")
                .contains("error:");
    }

    @Test
    void convertsTransportFailureWithoutMessageIntoDefaultFailedMessagingEvidence() throws Exception {
        MessagingTransport transport = new RecordingMessagingTransport() {
            @Override
            public MessagingTransportResult execute(MessagingTransportRequest request) throws IOException {
                throw new IOException();
            }
        };

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "mock",
                        "topic_ref", "payment.events",
                        "actions", Map.of(
                                "observe_payment_event", Map.of("mode", "observe"))),
                Map.of("steps", List.of(Map.of("action", "observe_payment_event"))),
                List.of());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog())).contains("Failed to execute messaging provider.");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("status: failed")
                .contains("error: Failed to execute messaging provider.");
    }

    @Test
    void failsWhenNoActionCanResolvePayloadBindingAndUsesEndpointFallback() throws Exception {
        RecordingMessagingTransport transport = new RecordingMessagingTransport();

        AdapterExecutionResult result = executeProvider(
                new MessagingProvider(transport),
                "payment_events",
                Map.of(
                        "provider_type", "mock",
                        "endpoint_ref", "mock://payment.events"),
                Map.of(),
                List.of(new ResolvedBinding("audit_payload", "audit_record", "fixtures/events/audit.json")));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(transport.request).isNull();
        assertThat(Files.readString(result.stderrLog()))
                .contains("Cannot resolve messaging payload binding ``.");
        assertThat(Files.readString(tempDir.resolve("run/messaging.yaml")))
                .contains("provider_type: mock")
                .contains("endpoint_ref: mock://payment.events")
                .contains("action: ")
                .contains("mode: publish")
                .contains("message_count: 0")
                .doesNotContain("payload_binding:");
    }

    @Test
    void reinterruptsThreadWhenMessagingTransportIsInterrupted() {
        MessagingTransport transport = new MessagingTransport() {
            @Override
            public MessagingTransportResult execute(MessagingTransportRequest request) throws InterruptedException {
                throw new InterruptedException("interrupted while polling topic");
            }

            @Override
            public MessagingTransportResult publish(MessagingTransportRequest request) {
                throw new AssertionError("execute should be used");
            }
        };

        try {
            assertThatThrownBy(() -> executeProvider(
                            new MessagingProvider(transport),
                            "payment_events",
                            Map.of(
                                    "provider_type", "mock",
                                    "topic_ref", "payment.events",
                                    "actions", Map.of(
                                            "observe_payment_event", Map.of("mode", "observe"))),
                            Map.of("steps", List.of(Map.of("action", "observe_payment_event"))),
                            List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Messaging provider interrupted.");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void throwsUncheckedIoWhenFailureEvidenceCannotBeWritten() throws Exception {
        Path blockedParent = tempDir.resolve("blocked-logs");
        Files.writeString(blockedParent, "not a directory");
        MessagingProvider provider = new MessagingProvider(new RecordingMessagingTransport() {
            @Override
            public MessagingTransportResult execute(MessagingTransportRequest request) throws IOException {
                throw new IOException("broker unavailable");
            }
        });

        assertThatThrownBy(() -> provider.execute(
                        "payment_events",
                        tempDir,
                        Map.of(
                                "provider_type", "mock",
                                "topic_ref", "payment.events",
                                "actions", Map.of(
                                        "observe_payment_event", Map.of("mode", "observe"))),
                        Map.of("steps", List.of(Map.of("action", "observe_payment_event"))),
                        List.of(),
                        tempDir.resolve("run-failure-write"),
                        blockedParent.resolve("stdout.log"),
                        tempDir.resolve("logs/stderr.log"),
                        tempDir.resolve("actual/message.json")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to execute messaging provider.");
    }

    private AdapterExecutionResult executeProvider(
            String providerName,
            Map<String, Object> contract,
            Map<String, Object> testCase,
            List<ResolvedBinding> bindings) {
        return executeProvider(new MessagingProvider(), providerName, contract, testCase, bindings);
    }

    private AdapterExecutionResult executeProvider(
            MessagingProvider provider,
            String providerName,
            Map<String, Object> contract,
            Map<String, Object> testCase,
            List<ResolvedBinding> bindings) {
        Path runDir = tempDir.resolve("run");
        return provider.execute(
                providerName,
                tempDir,
                contract,
                testCase,
                bindings,
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/message.json"));
    }

    private void writePayload(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static class RecordingMessagingTransport implements MessagingTransport {

        private final MessagingTransportResult result;
        private MessagingTransportRequest request;

        RecordingMessagingTransport() {
            this(new MessagingTransportResult("", "", 0));
        }

        RecordingMessagingTransport(MessagingTransportResult result) {
            this.result = result;
        }

        @Override
        public MessagingTransportResult execute(MessagingTransportRequest request) throws java.io.IOException {
            this.request = request;
            if (!result.stdout().isBlank() || !result.actualOutput().isBlank() || result.messageCount() > 0) {
                return result;
            }
            return new MessagingTransportResult(
                    "published 1 native message to " + request.targetRef() + "\n",
                    request.payload(),
                    1);
        }

        @Override
        public MessagingTransportResult publish(MessagingTransportRequest request) throws java.io.IOException {
            return execute(request);
        }
    }
}
