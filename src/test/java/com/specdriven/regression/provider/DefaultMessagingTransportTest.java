package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class DefaultMessagingTransportTest {

    @Test
    void observesKafkaMessagesWithCorrelationThroughMockConsumer() throws Exception {
        TopicPartition partition = new TopicPartition("payment.events", 0);
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
        });
        consumer.schedulePollTask(() -> consumer.addRecord(new ConsumerRecord<>(
                "payment.events",
                0,
                0L,
                "PAY-003",
                "{\"eventId\":\"EVT-003\",\"paymentId\":\"PAY-003\"}")));
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.observe(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "PAY-003",
                "",
                2,
                Map.of(),
                Map.of("mode", "observe", "min_count", 1)));

        assertThat(result.stdout()).isEqualTo("observed 1 native messages from payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"EVT-003\",\"paymentId\":\"PAY-003\"}\n");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void observesNatsMessagesOverCoreProtocol() throws Exception {
        String payload = "{\"eventId\":\"EVT-NATS-001\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsObservation(server, payload));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.observe(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "consume_payment_event",
                    "consume",
                    "",
                    "EVT-NATS-001",
                    "",
                    2,
                    Map.of(),
                    Map.of("mode", "consume", "expected_count", 1)));

            assertThat(result.stdout()).isEqualTo("observed 1 native messages from payment.events\n");
            assertThat(result.actualOutput()).isEqualTo(payload + "\n");
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void observesLocalConfiguredPayload() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().observe(new MessagingTransportRequest(
                "payment_events",
                "local",
                "",
                "mock://payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "",
                "",
                1,
                Map.of(),
                Map.of("mode", "observe", "min_count", 2, "observed_payload", "{\"eventId\":\"LOCAL\"}\n")));

        assertThat(result.stdout()).isEqualTo("observed 2 native messages from mock://payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"LOCAL\"}\n");
        assertThat(result.messageCount()).isEqualTo(2);
    }

    @Test
    void cleansLocalConfiguredCountWithoutPayload() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().execute(new MessagingTransportRequest(
                "payment_events",
                "local",
                "",
                "mock://payment.events",
                "topic_ref",
                "cleanup_payment_event",
                "cleanup",
                "",
                "",
                "",
                1,
                Map.of(),
                Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 10, "cleanup_count", 2)));

        assertThat(result.stdout()).isEqualTo("cleaned 2 native messages from mock://payment.events\n");
        assertThat(result.actualOutput()).isEmpty();
        assertThat(result.messageCount()).isEqualTo(2);
    }

    @Test
    void cleansKafkaMessagesThroughMockConsumer() throws Exception {
        TopicPartition partition = new TopicPartition("payment.events", 0);
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
        });
        consumer.schedulePollTask(() -> {
            consumer.addRecord(new ConsumerRecord<>(
                    "payment.events",
                    0,
                    0L,
                    "PAY-101",
                    "{\"eventId\":\"EVT-101\"}"));
            consumer.addRecord(new ConsumerRecord<>(
                    "payment.events",
                    0,
                    1L,
                    "PAY-102",
                    "{\"eventId\":\"EVT-102\"}"));
        });
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.execute(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "cleanup_payment_event",
                "cleanup",
                "",
                "",
                "",
                2,
                Map.of(),
                Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 2)));

        assertThat(result.stdout()).isEqualTo("cleaned 2 native messages from payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"EVT-101\"}\n{\"eventId\":\"EVT-102\"}\n");
        assertThat(result.messageCount()).isEqualTo(2);
    }

    @Test
    void cleansNatsMessagesOverCoreProtocol() throws Exception {
        String payload = "{\"eventId\":\"EVT-NATS-CLEANUP-001\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsObservation(server, payload));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.execute(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "cleanup_payment_event",
                    "cleanup",
                    "",
                    "EVT-NATS-CLEANUP-001",
                    "",
                    2,
                    Map.of(),
                    Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 5)));

            assertThat(result.stdout()).isEqualTo("cleaned 1 native messages from payment.events\n");
            assertThat(result.actualOutput()).isEqualTo(payload + "\n");
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    private void serveNatsObservation(ServerSocket server, String payload) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            reader.readLine();
            reader.readLine();
            reader.readLine();
            output.write("PONG\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] message = payload.getBytes(StandardCharsets.UTF_8);
            output.write(("MSG payment.events 1 " + message.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(message);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
