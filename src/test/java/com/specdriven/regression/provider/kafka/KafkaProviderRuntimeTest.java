package com.specdriven.regression.provider.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KafkaProviderRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void kafkaPayloadMatchUsesEnvProfileTopicAndWritesEvidence() throws Exception {
        Path suiteRoot = tempDir.resolve("suite");
        Files.createDirectories(suiteRoot.resolve("fixtures"));
        Files.createDirectories(suiteRoot.resolve("expected_results"));
        Files.writeString(suiteRoot.resolve("fixtures/order_event.json"), """
                {
                  "orderId": "ORD-K-001",
                  "status": "CREATED"
                }
                """);
        Files.writeString(suiteRoot.resolve("expected_results/order_event.json"), """
                {
                  "orderId": "ORD-K-001",
                  "status": "CREATED"
                }
                """);
        KafkaProviderRuntime runtime = new KafkaProviderRuntime();
        ProviderExecutionContext context = context(suiteRoot);

        ProviderOperationResult published = runtime.execute(context, new ProviderOperationRequest(
                "kafka_publish",
                List.of(
                        Map.of("bind_as", "key", "ref", "ORD-K-001"),
                        Map.of("bind_as", "payload_ref", "ref", "fixtures/order_event.json")),
                Map.of("_operation_id", "publish_order")));
        ProviderOperationResult matched = runtime.execute(context, new ProviderOperationRequest(
                "kafka_payload_match",
                List.of(
                        Map.of("bind_as", "expected_ref", "ref", "expected_results/order_event.json"),
                        Map.of("bind_as", "consume_from", "ref", "test_start_time")),
                Map.of("_operation_id", "match_order", "_test_start_time", Instant.EPOCH.toString())));

        assertThat(published.passed()).isTrue();
        assertThat(matched.passed()).isTrue();
        assertThat(matched.outputs())
                .containsEntry("topic", "orders.created")
                .containsEntry("matched", true)
                .containsEntry("observed_count", 1);
        Path evidence = tempDir.resolve("run/provider-evidence/kafka/match_order.yaml");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("evidence_type: kafka_event")
                .contains("provider_type: kafka")
                .contains("provider_id: order-events")
                .contains("topic: orders.created")
                .contains("status: passed")
                .contains("failure_code: ")
                .contains("masking:")
                .contains("raw_secret_found: false");
    }

    @Test
    void kafkaObserveTimesOutWithLastObservedEvidenceShape() throws Exception {
        KafkaProviderRuntime runtime = new KafkaProviderRuntime();

        ProviderOperationResult observed = runtime.execute(context(tempDir.resolve("suite")), new ProviderOperationRequest(
                "kafka_observe",
                List.of(
                        Map.of("bind_as", "consume_from", "ref", "test_start_time"),
                        Map.of("bind_as", "timeout", "ref", "PT0.02S"),
                        Map.of("bind_as", "poll_interval", "ref", "PT0.01S")),
                Map.of("_operation_id", "timeout_observe", "_test_start_time", Instant.now().toString())));

        assertThat(observed.passed()).isFalse();
        assertThat(observed.failure()).isNotNull();
        assertThat(observed.failure().code()).isEqualTo("KAFKA_TIMEOUT");
        assertThat(observed.outputs())
                .containsEntry("topic", "orders.created")
                .containsEntry("matched", false)
                .containsEntry("observed_count", 0);
        Path evidence = tempDir.resolve("run/provider-evidence/kafka/timeout_observe.yaml");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("status: failed")
                .contains("failure_code: KAFKA_TIMEOUT")
                .contains("attempts:")
                .contains("observed_count: 0")
                .contains("masking:")
                .contains("raw_secret_found: false");
    }

    @Test
    void kafkaNativeModeFailsFastWithoutPretendingBrokerRuntimeExists() {
        KafkaProviderRuntime runtime = new KafkaProviderRuntime(new FailingKafkaClientTransport("broker unavailable"));
        ProviderExecutionContext context = new ProviderExecutionContext(
                "order-events",
                "kafka",
                "sit_kafka",
                "native",
                tempDir.resolve("suite"),
                tempDir.resolve("run"),
                Map.of("provider_type", "kafka"),
                Map.of("provider_id", "order-events", "provider_type", "kafka"),
                Map.of(
                        "bootstrap_servers", "broker:9092",
                        "topic", "orders.created",
                        "consumer_group", "artf-sit"));

        ProviderOperationResult result = runtime.execute(context, new ProviderOperationRequest(
                "kafka_publish",
                List.of(Map.of("bind_as", "payload", "value", "{\"orderId\":\"ORD-K-001\"}")),
                Map.of("_operation_id", "native_publish")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("KAFKA_CONNECTION_FAILED");
        assertThat(result.failure().reason())
                .contains("Kafka client operation failed")
                .doesNotContain("broker unavailable");
        assertThat(result.outputs()).containsKey("event_evidence_ref");
    }

    @Test
    void kafkaNativeModeUsesExternalClientTransportWithoutStartingBroker() throws Exception {
        Path suiteRoot = tempDir.resolve("suite");
        Files.createDirectories(suiteRoot.resolve("expected_results"));
        Files.writeString(suiteRoot.resolve("expected_results/order_event.json"), """
                {
                  "orderId": "ORD-K-001",
                  "status": "CREATED"
                }
                """);
        System.setProperty("KAFKA_BOOTSTRAP_SERVERS", "127.0.0.1:9092");
        try {
            RecordingKafkaClientTransport transport = new RecordingKafkaClientTransport();
            KafkaProviderRuntime runtime = new KafkaProviderRuntime(transport);
            ProviderExecutionContext context = new ProviderExecutionContext(
                    "order-events",
                    "kafka",
                    "ci_kafka_external",
                    "native",
                    suiteRoot,
                    tempDir.resolve("run"),
                    Map.of("provider_type", "kafka"),
                    Map.of("provider_id", "order-events", "provider_type", "kafka"),
                    Map.of(
                            "bootstrap_servers", Map.of("secret_ref", "env://KAFKA_BOOTSTRAP_SERVERS"),
                            "topic", "orders.created",
                            "consumer_group", "artf-ci"));

            ProviderOperationResult published = runtime.execute(context, new ProviderOperationRequest(
                    "kafka_publish",
                    List.of(
                            Map.of("bind_as", "key", "ref", "ORD-K-001"),
                            Map.of("bind_as", "payload", "value", "{\"orderId\":\"ORD-K-001\",\"status\":\"CREATED\"}")),
                    Map.of("_operation_id", "native_publish")));
            ProviderOperationResult observed = runtime.execute(context, new ProviderOperationRequest(
                    "kafka_payload_match",
                    List.of(
                            Map.of("bind_as", "expected_ref", "ref", "expected_results/order_event.json"),
                            Map.of("bind_as", "consume_from", "ref", "earliest")),
                    Map.of("_operation_id", "native_match", "_test_start_time", Instant.EPOCH.toString())));

            assertThat(published.passed()).isTrue();
            assertThat(observed.passed()).isTrue();
            assertThat(transport.bootstrapServers()).isEqualTo("127.0.0.1:9092");
            assertThat(transport.topic()).isEqualTo("orders.created");
            assertThat(transport.consumerGroup()).isEqualTo("artf-ci");
            assertThat(observed.outputs())
                    .containsEntry("topic", "orders.created")
                    .containsEntry("matched", true)
                    .containsEntry("observed_count", 1);
            assertThat(Files.readString(tempDir.resolve("run/provider-evidence/kafka/native_match.yaml")))
                    .contains("runtime_mode: native")
                    .contains("provider_type: kafka")
                    .contains("provider_id: order-events")
                    .contains("topic: orders.created")
                    .contains("status: passed")
                    .contains("raw_secret_found: false");
        } finally {
            System.clearProperty("KAFKA_BOOTSTRAP_SERVERS");
        }
    }

    private ProviderExecutionContext context(Path suiteRoot) {
        return new ProviderExecutionContext(
                "order-events",
                "kafka",
                "local_kafka",
                "mock",
                suiteRoot,
                tempDir.resolve("run"),
                Map.of(
                        "provider_type", "kafka",
                        "binding_keys", Map.of(
                                "bootstrap_servers", Map.of("required", true),
                                "topic", Map.of("required", true),
                                "consumer_group", Map.of("required", true)),
                        "operations", Map.of(
                                "kafka_publish", Map.of(
                                        "allowed_inputs", List.of("key", "payload", "payload_ref"),
                                        "required_inputs", List.of("payload_ref")),
                                "kafka_payload_match", Map.of(
                                        "allowed_inputs", List.of("expected_ref", "consume_from", "timeout", "poll_interval"),
                                        "required_inputs", List.of("expected_ref")))),
                Map.of("provider_id", "order-events", "provider_type", "kafka"),
                Map.of(
                        "bootstrap_servers", "approved_local_kafka_ref",
                        "topic", "orders.created",
                        "consumer_group", "artf-local"));
    }

    private static final class RecordingKafkaClientTransport implements KafkaProviderRuntime.KafkaClientTransport {
        private String bootstrapServers = "";
        private String topic = "";
        private String consumerGroup = "";
        private Object payload = Map.of();

        @Override
        public KafkaProviderRuntime.KafkaPublishResult publish(
                KafkaProviderRuntime.KafkaConnection connection,
                String key,
                Object payload,
                java.time.Duration timeout) {
            this.bootstrapServers = connection.bootstrapServers();
            this.topic = connection.topic();
            this.consumerGroup = connection.consumerGroup();
            this.payload = payload;
            return new KafkaProviderRuntime.KafkaPublishResult(0, 0, Instant.parse("2026-07-05T00:00:00Z"));
        }

        @Override
        public KafkaProviderRuntime.KafkaObserveResult observe(
                KafkaProviderRuntime.KafkaConnection connection,
                String key,
                Instant consumeFrom,
                java.time.Duration timeout,
                java.time.Duration pollInterval) {
            this.bootstrapServers = connection.bootstrapServers();
            this.topic = connection.topic();
            this.consumerGroup = connection.consumerGroup();
            return new KafkaProviderRuntime.KafkaObserveResult(List.of(new KafkaProviderRuntime.KafkaMessage(
                    connection.topic(),
                    key,
                    0,
                    0,
                    payload,
                    Instant.parse("2026-07-05T00:00:01Z"))), 1);
        }

        String bootstrapServers() {
            return bootstrapServers;
        }

        String topic() {
            return topic;
        }

        String consumerGroup() {
            return consumerGroup;
        }
    }

    private static final class FailingKafkaClientTransport implements KafkaProviderRuntime.KafkaClientTransport {
        private final String message;

        private FailingKafkaClientTransport(String message) {
            this.message = message;
        }

        @Override
        public KafkaProviderRuntime.KafkaPublishResult publish(
                KafkaProviderRuntime.KafkaConnection connection,
                String key,
                Object payload,
                java.time.Duration timeout) throws Exception {
            throw new Exception(message);
        }

        @Override
        public KafkaProviderRuntime.KafkaObserveResult observe(
                KafkaProviderRuntime.KafkaConnection connection,
                String key,
                Instant consumeFrom,
                java.time.Duration timeout,
                java.time.Duration pollInterval) throws Exception {
            throw new Exception(message);
        }
    }
}
