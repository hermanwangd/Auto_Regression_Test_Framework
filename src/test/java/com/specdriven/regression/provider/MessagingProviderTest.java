package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
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
