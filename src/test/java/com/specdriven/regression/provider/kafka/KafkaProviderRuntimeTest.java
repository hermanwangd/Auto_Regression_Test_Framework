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
        KafkaProviderRuntime runtime = new KafkaProviderRuntime();
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
        assertThat(result.outputs()).containsKey("event_evidence_ref");
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
}
