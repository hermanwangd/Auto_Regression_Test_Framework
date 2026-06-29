package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
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
    void publishesKafkaMessageThroughInjectedProducer() throws Exception {
        AtomicReference<Properties> capturedProperties = new AtomicReference<>();
        MockProducer<String, String> producer = new MockProducer<>(
                true,
                new StringSerializer(),
                new StringSerializer());
        DefaultMessagingTransport transport = new DefaultMessagingTransport(
                properties -> new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                properties -> {
                    capturedProperties.set(properties);
                    return producer;
                });
        String payload = "{\"eventId\":\"EVT-KAFKA-PUBLISH-001\"}";

        MessagingTransportResult result = transport.publish(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "kafka://payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                "EVT-KAFKA-PUBLISH-001",
                payload,
                1,
                Map.of(),
                Map.of("mode", "publish")));

        assertThat(result.stdout())
                .contains("published 1 native message to kafka://payment.events")
                .contains("partition=0")
                .contains("offset=0");
        assertThat(result.actualOutput()).isEqualTo(payload);
        assertThat(result.messageCount()).isEqualTo(1);
        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("payment.events");
            assertThat(record.key()).isEqualTo("EVT-KAFKA-PUBLISH-001");
            assertThat(record.value()).isEqualTo(payload);
        });
        assertThat(capturedProperties.get().getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo("127.0.0.1:9092");
    }

    @Test
    void publishesKafkaMessageWithNullCorrelationAsUnkeyedRecord() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(
                true,
                new StringSerializer(),
                new StringSerializer());
        DefaultMessagingTransport transport = new DefaultMessagingTransport(
                properties -> new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                properties -> producer);
        String payload = "{\"eventId\":\"EVT-KAFKA-PUBLISH-NULL-CORRELATION\"}";

        MessagingTransportResult result = transport.publish(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                null,
                payload,
                1,
                Map.of(),
                Map.of("mode", "publish")));

        assertThat(result.actualOutput()).isEqualTo(payload);
        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("payment.events");
            assertThat(record.key()).isNull();
            assertThat(record.value()).isEqualTo(payload);
        });
    }

    @Test
    void resolvesKafkaConnectionFromInjectedEnvironmentResolver() throws Exception {
        AtomicReference<Properties> capturedProperties = new AtomicReference<>();
        MockProducer<String, String> producer = new MockProducer<>(
                true,
                new StringSerializer(),
                new StringSerializer());
        DefaultMessagingTransport transport = new DefaultMessagingTransport(
                properties -> new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                properties -> {
                    capturedProperties.set(properties);
                    return producer;
                },
                name -> name.equals("SPEC_REGRESSION_KAFKA") ? "kafka://broker.internal:19092" : null);

        transport.publish(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "env://SPEC_REGRESSION_KAFKA",
                "payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                "",
                "{\"eventId\":\"EVT-KAFKA-ENV\"}",
                1,
                Map.of(),
                Map.of("mode", "publish")));

        assertThat(capturedProperties.get().getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .isEqualTo("broker.internal:19092");
    }

    @Test
    void rejectsBlankEnvironmentConnectionRefFromInjectedEnvironmentResolver() {
        DefaultMessagingTransport transport = new DefaultMessagingTransport(
                properties -> new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                properties -> new MockProducer<>(true, new StringSerializer(), new StringSerializer()),
                name -> "  ");

        assertThatThrownBy(() -> transport.publish(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "env://SPEC_REGRESSION_BLANK_KAFKA",
                "payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                "",
                "{\"eventId\":\"EVT-KAFKA-ENV-BLANK\"}",
                1,
                Map.of(),
                Map.of("mode", "publish"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Environment variable `SPEC_REGRESSION_BLANK_KAFKA` is not set for messaging connection.");
    }

    @Test
    void reportsKafkaPublishFailureFromInjectedProducer() {
        MockProducer<String, String> producer = new MockProducer<>(
                false,
                new StringSerializer(),
                new StringSerializer());
        DefaultMessagingTransport transport = new DefaultMessagingTransport(
                properties -> new MockConsumer<>(OffsetResetStrategy.EARLIEST),
                properties -> producer);
        producer.errorNext(new RuntimeException("broker unavailable"));

        assertThatThrownBy(() -> transport.publish(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "kafka://payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                "",
                "{\"eventId\":\"EVT-KAFKA-PUBLISH-FAIL\"}",
                1,
                Map.of(),
                Map.of("mode", "publish"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Kafka publish failed for `kafka://payment.events`.");
    }

    @Test
    void publishesLocalMessageWithPayload() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().publish(new MessagingTransportRequest(
                "payment_events",
                "mock",
                "",
                "mock://payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                "",
                "{\"eventId\":\"LOCAL-PUBLISH\"}",
                1,
                Map.of(),
                Map.of("mode", "publish")));

        assertThat(result.stdout()).isEqualTo("published 1 message to mock://payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"LOCAL-PUBLISH\"}");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void routesKafkaMockConnectionThroughLocalPublishWithoutProducer() throws Exception {
        DefaultMessagingTransport transport = new DefaultMessagingTransport(
                properties -> {
                    throw new AssertionError("Kafka consumer should not be created.");
                },
                properties -> {
                    throw new AssertionError("Kafka producer should not be created.");
                });

        MessagingTransportResult result = transport.publish(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "mock://kafka",
                "kafka://payment.events",
                "topic_ref",
                "publish_payment_event",
                "publish",
                "payment_event",
                "PAY-100",
                "{\"paymentId\":\"PAY-100\"}",
                1,
                Map.of(),
                Map.of("mode", "publish")));

        assertThat(result.stdout()).isEqualTo("published 1 message to kafka://payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"paymentId\":\"PAY-100\"}");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void routesNatsMockConnectionThroughLocalRequestReplyWithoutSocket() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().requestReply(new MessagingTransportRequest(
                "payment_requests",
                "nats",
                "mock://nats",
                "nats://payment.requests",
                "subject_ref",
                "request_payment",
                "request_reply",
                "payment_event",
                "PAY-101",
                "{\"paymentId\":\"PAY-101\"}",
                1,
                Map.of(),
                Map.of("mode", "request_reply", "reply_payload", "{\"reply\":\"accepted\"}")));

        assertThat(result.stdout()).isEqualTo("requested 1 native reply from nats://payment.requests\n");
        assertThat(result.actualOutput()).isEqualTo("{\"reply\":\"accepted\"}");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void observesKafkaMessageWhenCorrelationAppearsInPayload() throws Exception {
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
                "unrelated-key",
                "{\"eventId\":\"EVT-004\",\"paymentId\":\"PAY-004\"}")));
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.observe(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "127.0.0.1:9092",
                "kafka://payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "PAY-004",
                "",
                2,
                Map.of(),
                Map.of("mode", "observe", "expected_count", 1)));

        assertThat(result.stdout()).isEqualTo("observed 1 native messages from kafka://payment.events\n");
        assertThat(result.actualOutput()).contains("\"paymentId\":\"PAY-004\"");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void observesKafkaExpectedCountAfterSkippingUnmatchedMessages() throws Exception {
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
                    "unrelated-key",
                    "{\"paymentId\":\"OTHER\"}"));
            consumer.addRecord(new ConsumerRecord<>(
                    "payment.events",
                    0,
                    1L,
                    "PAY-MATCH",
                    "{\"eventId\":\"EVT-KAFKA-MATCH-001\"}"));
            consumer.addRecord(new ConsumerRecord<>(
                    "payment.events",
                    0,
                    2L,
                    "another-key",
                    "{\"eventId\":\"EVT-KAFKA-MATCH-002\",\"paymentId\":\"PAY-MATCH\"}"));
        });
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.observe(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "PAY-MATCH",
                "",
                2,
                Map.of(),
                Map.of("mode", "observe", "expected_count", 2)));

        assertThat(result.stdout()).isEqualTo("observed 2 native messages from payment.events\n");
        assertThat(result.actualOutput())
                .isEqualTo("{\"eventId\":\"EVT-KAFKA-MATCH-001\"}\n"
                        + "{\"eventId\":\"EVT-KAFKA-MATCH-002\",\"paymentId\":\"PAY-MATCH\"}\n");
        assertThat(result.messageCount()).isEqualTo(2);
    }

    @Test
    void observesKafkaMessagesWithConfiguredConsumerGroup() throws Exception {
        AtomicReference<Properties> capturedProperties = new AtomicReference<>();
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
                "PAY-GROUP",
                "{\"paymentId\":\"PAY-GROUP\"}")));
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> {
            capturedProperties.set(properties);
            return consumer;
        });

        MessagingTransportResult result = transport.observe(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "PAY-GROUP",
                "",
                1,
                Map.of(),
                Map.of("mode", "observe", "expected_count", 1, "group_id_ref", "rp-ci-group")));

        assertThat(result.messageCount()).isEqualTo(1);
        assertThat(capturedProperties.get().getProperty(ConsumerConfig.GROUP_ID_CONFIG))
                .isEqualTo("rp-ci-group");
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
    void observesNatsMessageAfterSkippingUnmatchedCorrelationMessage() throws Exception {
        String unmatched = "{\"eventId\":\"OTHER\"}";
        String matched = "{\"eventId\":\"EVT-NATS-MATCH\",\"paymentId\":\"PAY-NATS-MATCH\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsMessages(server, List.of(unmatched, matched)));
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
                    "PAY-NATS-MATCH",
                    "",
                    2,
                    Map.of(),
                    Map.of("mode", "consume", "expected_count", 1)));

            assertThat(result.stdout()).isEqualTo("observed 1 native messages from payment.events\n");
            assertThat(result.actualOutput()).isEqualTo(matched + "\n");
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestsNatsReplyOverCoreProtocol() throws Exception {
        String requestPayload = "{\"paymentId\":\"PAY-REQ-001\"}";
        String replyPayload = "{\"approved\":true,\"paymentId\":\"PAY-REQ-001\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsRequestReply(server, requestPayload, replyPayload));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.execute(new MessagingTransportRequest(
                    "payment_authorization",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.authorization",
                    "subject_ref",
                    "request_payment_authorization",
                    "request_reply",
                    "authorization_request",
                    "PAY-REQ-001",
                    requestPayload,
                    2,
                    Map.of(),
                    Map.of("mode", "request_reply")));

            assertThat(result.stdout()).isEqualTo("requested 1 native reply from payment.authorization\n");
            assertThat(result.actualOutput()).isEqualTo(replyPayload + "\n");
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestsNatsReplyWithConfiguredReplySubject() throws Exception {
        String requestPayload = "{\"paymentId\":\"PAY-REQ-002\"}";
        String replyPayload = "{\"approved\":false,\"paymentId\":\"PAY-REQ-002\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsRequestReplyWithConfiguredSubject(
                            server,
                            requestPayload,
                            replyPayload,
                            "_INBOX.custom.reply"));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.execute(new MessagingTransportRequest(
                    "payment_authorization",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.authorization",
                    "subject_ref",
                    "request_payment_authorization",
                    "request_reply",
                    "authorization_request",
                    "PAY-REQ-002",
                    requestPayload,
                    2,
                    Map.of(),
                    Map.of("mode", "request_reply", "reply_subject", "nats://_INBOX.custom.reply")));

            assertThat(result.stdout()).isEqualTo("requested 1 native reply from payment.authorization\n");
            assertThat(result.actualOutput()).isEqualTo(replyPayload + "\n");
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsRequestReplyProtocolError() throws Exception {
        String requestPayload = "{\"paymentId\":\"PAY-REQ-ERR\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsRequestReplyError(server, requestPayload, "-ERR no responders"));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.execute(new MessagingTransportRequest(
                    "payment_authorization",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.authorization",
                    "subject_ref",
                    "request_payment_authorization",
                    "request_reply",
                    "authorization_request",
                    "PAY-REQ-ERR",
                    requestPayload,
                    2,
                    Map.of(),
                    Map.of("mode", "request_reply"))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS request_reply failed: -ERR no responders");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsRequestReplyTimeoutWhenNoReplyArrives() throws Exception {
        String requestPayload = "{\"paymentId\":\"PAY-REQ-TIMEOUT\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsRequestReplyNoResponse(server, requestPayload));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.execute(new MessagingTransportRequest(
                    "payment_authorization",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.authorization",
                    "subject_ref",
                    "request_payment_authorization",
                    "request_reply",
                    "authorization_request",
                    "PAY-REQ-TIMEOUT",
                    requestPayload,
                    1,
                    Map.of(),
                    Map.of("mode", "request_reply"))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS request_reply expected a reply from `payment.authorization` but observed 0.");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishesNatsMessageOverCoreProtocolWithHostPortConnectionRef() throws Exception {
        String payload = "{\"eventId\":\"EVT-NATS-PUBLISH-001\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsPublish(server, payload, false));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.publish(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "127.0.0.1:" + server.getLocalPort(),
                    "nats://payment.events",
                    "subject_ref",
                    "publish_payment_event",
                    "publish",
                    "payment_event",
                    "EVT-NATS-PUBLISH-001",
                    payload,
                    2,
                    Map.of(),
                    Map.of("mode", "publish")));

            assertThat(result.stdout()).isEqualTo("published 1 native message to nats://payment.events\n");
            assertThat(result.actualOutput()).isEqualTo(payload);
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishesNatsMessageWhenServerSendsInterimStatusBeforePong() throws Exception {
        String payload = "{\"eventId\":\"EVT-NATS-PUBLISH-STATUS\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsPublishWithInterimStatus(server, payload));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.publish(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "publish_payment_event",
                    "publish",
                    "payment_event",
                    "EVT-NATS-PUBLISH-STATUS",
                    payload,
                    2,
                    Map.of(),
                    Map.of("mode", "publish")));

            assertThat(result.stdout()).isEqualTo("published 1 native message to payment.events\n");
            assertThat(result.actualOutput()).isEqualTo(payload);
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsPublishProtocolError() throws Exception {
        String payload = "{\"eventId\":\"EVT-NATS-PUBLISH-ERR\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsPublish(server, payload, true));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.publish(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "publish_payment_event",
                    "publish",
                    "payment_event",
                    "EVT-NATS-PUBLISH-ERR",
                    payload,
                    2,
                    Map.of(),
                    Map.of("mode", "publish"))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS publish failed: -ERR publish denied");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestsLocalReplyUsingConfiguredPayload() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().execute(new MessagingTransportRequest(
                "payment_authorization",
                "local",
                "",
                "mock://payment.authorization",
                "subject_ref",
                "request_payment_authorization",
                "request_reply",
                "authorization_request",
                "PAY-LOCAL-REQ-001",
                "{\"paymentId\":\"PAY-LOCAL-REQ-001\"}",
                1,
                Map.of(),
                Map.of("mode", "request_reply", "reply_payload", "{\"approved\":true}")));

        assertThat(result.stdout()).isEqualTo("requested 1 native reply from mock://payment.authorization\n");
        assertThat(result.actualOutput()).isEqualTo("{\"approved\":true}");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void rejectsUnsupportedProviderModesWithActionableErrors() {
        DefaultMessagingTransport transport = new DefaultMessagingTransport();

        assertThatThrownBy(() -> transport.publish(messageRequest("orbix", "publish")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Unsupported messaging provider_type `orbix` for execution.");
        assertThatThrownBy(() -> transport.observe(messageRequest("orbix", "observe")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Unsupported messaging provider_type `orbix` for observe execution.");
        assertThatThrownBy(() -> transport.requestReply(messageRequest("kafka", "request_reply")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Unsupported messaging request/reply mode for provider_type `kafka`.");
        assertThatThrownBy(() -> transport.cleanup(messageRequest("orbix", "cleanup")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Unsupported messaging provider_type `orbix` for cleanup execution.");
    }

    @Test
    void reportsKafkaObserveTimeoutWhenExpectedCountIsNotReached() {
        TopicPartition partition = new TopicPartition("payment.events", 0);
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
        });
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "PAY-MISSING",
                "",
                1,
                Map.of(),
                Map.of("mode", "observe", "expected_count", 1))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Kafka observe expected at least 1 messages from `payment.events` but observed 0.");
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
    void observesLocalDefaultSingleMessageWhenCountIsOmitted() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().observe(new MessagingTransportRequest(
                "payment_events",
                "mock",
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
                Map.of("mode", "observe", "sample_payload", "{\"eventId\":\"DEFAULT\"}")));

        assertThat(result.stdout()).isEqualTo("observed 1 native messages from mock://payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"DEFAULT\"}");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void reportsNatsObserveTimeoutWhenExpectedCountIsNotReached() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsNoMessages(server));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "observe_payment_event",
                    "observe",
                    "",
                    "",
                    "",
                    1,
                    Map.of(),
                    Map.of("mode", "observe", "expected_count", 1))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS observe expected at least 1 messages from `payment.events` but observed 0.");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsObserveProtocolError() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsSubscribeError(server, "-ERR observe denied"));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "observe_payment_event",
                    "observe",
                    "",
                    "",
                    "",
                    1,
                    Map.of(),
                    Map.of("mode", "observe", "expected_count", 1))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS observe failed: -ERR observe denied");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsObserveTimeoutWhenConnectionClosesAfterSubscribe() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsCloseAfterSubscribe(server));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "observe_payment_event",
                    "observe",
                    "",
                    "",
                    "",
                    1,
                    Map.of(),
                    Map.of("mode", "observe", "expected_count", 1))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS observe expected at least 1 messages from `payment.events` but observed 0.");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsInfoHandshakeFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsWithoutInfo(server));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.publish(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "publish_payment_event",
                    "publish",
                    "payment_event",
                    "",
                    "{}",
                    1,
                    Map.of(),
                    Map.of("mode", "publish"))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS server did not send INFO before command.");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    private MessagingTransportRequest messageRequest(String providerType, String mode) {
        return new MessagingTransportRequest(
                "payment_events",
                providerType,
                "",
                "mock://payment.events",
                "topic_ref",
                "payment_event",
                mode,
                "",
                "",
                "{\"eventId\":\"EVT\"}",
                1,
                Map.of(),
                Map.of("mode", mode, "expected_count", 1));
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
    void cleansLocalDefaultsToZeroWhenNoCleanupCountIsConfigured() throws Exception {
        MessagingTransportResult result = new DefaultMessagingTransport().cleanup(new MessagingTransportRequest(
                "payment_events",
                "mock",
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
                Map.of("mode", "cleanup")));

        assertThat(result.stdout()).isEqualTo("cleaned 0 native messages from mock://payment.events\n");
        assertThat(result.actualOutput()).isEmpty();
        assertThat(result.messageCount()).isZero();
    }

    @Test
    void rejectsInvalidMessagingCountsBeforeNetworkExecution() {
        DefaultMessagingTransport transport = new DefaultMessagingTransport();

        assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
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
                Map.of("mode", "observe", "expected_count", "many"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Messaging observe expected_count/min_count must be a positive integer.");
        assertThatThrownBy(() -> transport.cleanup(new MessagingTransportRequest(
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
                1,
                Map.of(),
                Map.of("mode", "cleanup", "max_count", "none"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Messaging cleanup max_count/drain_count must be a positive integer.");
        assertThatThrownBy(() -> transport.cleanup(new MessagingTransportRequest(
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
                Map.of("mode", "cleanup", "cleanup_count", "-1"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Messaging cleanup_count/cleaned_count must be a non-negative integer.");
    }

    @Test
    void rejectsNonPositiveMessagingCountsBeforeNetworkExecution() {
        DefaultMessagingTransport transport = new DefaultMessagingTransport();

        assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
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
                Map.of("mode", "observe", "min_count", 0))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Messaging observe expected_count/min_count must be a positive integer.");
        assertThatThrownBy(() -> transport.cleanup(new MessagingTransportRequest(
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
                1,
                Map.of(),
                Map.of("mode", "cleanup", "drain_count", 0))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Messaging cleanup max_count/drain_count must be a positive integer.");
        assertThatThrownBy(() -> transport.cleanup(new MessagingTransportRequest(
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
                Map.of("mode", "cleanup", "cleaned_count", "many"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Messaging cleanup_count/cleaned_count must be a non-negative integer.");
    }

    @Test
    void rejectsMissingEnvironmentConnectionRefBeforeKafkaNetworkExecution() {
        DefaultMessagingTransport transport = new DefaultMessagingTransport();

        assertThatThrownBy(() -> transport.observe(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "env://SPEC_REGRESSION_MISSING_KAFKA",
                "payment.events",
                "topic_ref",
                "observe_payment_event",
                "observe",
                "",
                "",
                "",
                1,
                Map.of(),
                Map.of("mode", "observe", "expected_count", 1))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Environment variable `SPEC_REGRESSION_MISSING_KAFKA` is not set for messaging connection.");
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
    void cleansKafkaMessagesMatchingCorrelationOnly() throws Exception {
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
                    "PAY-201",
                    "{\"eventId\":\"EVT-201\"}"));
            consumer.addRecord(new ConsumerRecord<>(
                    "payment.events",
                    0,
                    1L,
                    "other-key",
                    "{\"eventId\":\"EVT-202\",\"paymentId\":\"PAY-202\"}"));
        });
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.cleanup(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "cleanup_payment_event",
                "cleanup",
                "",
                "PAY-202",
                "",
                2,
                Map.of(),
                Map.of("mode", "cleanup", "cleanup_strategy", "drain", "drain_count", 2)));

        assertThat(result.stdout()).isEqualTo("cleaned 1 native messages from payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"EVT-202\",\"paymentId\":\"PAY-202\"}\n");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void cleansKafkaMessagesWithoutCorrelationFilterWhenCorrelationIsNull() throws Exception {
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
                "ANY-KEY",
                "{\"eventId\":\"EVT-NULL-CORRELATION-CLEANUP\"}")));
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.cleanup(new MessagingTransportRequest(
                "payment_events",
                "kafka",
                "kafka://127.0.0.1:9092",
                "payment.events",
                "topic_ref",
                "cleanup_payment_event",
                "cleanup",
                "",
                null,
                "",
                1,
                Map.of(),
                Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 1)));

        assertThat(result.stdout()).isEqualTo("cleaned 1 native messages from payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"EVT-NULL-CORRELATION-CLEANUP\"}\n");
        assertThat(result.messageCount()).isEqualTo(1);
    }

    @Test
    void cleansKafkaMessagesUsingDefaultDrainLimitWhenMaxCountIsOmitted() throws Exception {
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
                "PAY-DEFAULT-DRAIN",
                "{\"eventId\":\"EVT-DEFAULT-DRAIN\"}")));
        DefaultMessagingTransport transport = new DefaultMessagingTransport(properties -> consumer);

        MessagingTransportResult result = transport.cleanup(new MessagingTransportRequest(
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
                1,
                Map.of(),
                Map.of("mode", "cleanup", "cleanup_strategy", "drain")));

        assertThat(result.stdout()).isEqualTo("cleaned 1 native messages from payment.events\n");
        assertThat(result.actualOutput()).isEqualTo("{\"eventId\":\"EVT-DEFAULT-DRAIN\"}\n");
        assertThat(result.messageCount()).isEqualTo(1);
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

    @Test
    void cleansNatsMessageAfterSkippingUnmatchedCorrelationMessage() throws Exception {
        String unmatched = "{\"eventId\":\"OTHER\"}";
        String matched = "{\"eventId\":\"EVT-NATS-CLEANUP-MATCH\",\"paymentId\":\"PAY-NATS-CLEANUP\"}";
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsMessages(server, List.of(unmatched, matched)));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.cleanup(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "cleanup_payment_event",
                    "cleanup",
                    "",
                    "PAY-NATS-CLEANUP",
                    "",
                    2,
                    Map.of(),
                    Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 1)));

            assertThat(result.stdout()).isEqualTo("cleaned 1 native messages from payment.events\n");
            assertThat(result.actualOutput()).isEqualTo(matched + "\n");
            assertThat(result.messageCount()).isEqualTo(1);
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsNatsCleanupProtocolError() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(
                    () -> serveNatsSubscribeError(server, "-ERR cleanup denied"));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            assertThatThrownBy(() -> transport.cleanup(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "cleanup_payment_event",
                    "cleanup",
                    "",
                    "",
                    "",
                    1,
                    Map.of(),
                    Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 1))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("NATS cleanup failed: -ERR cleanup denied");
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void cleansNatsZeroMessagesWhenConnectionClosesAfterSubscribe() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsCloseAfterSubscribe(server));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.cleanup(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "cleanup_payment_event",
                    "cleanup",
                    "",
                    "",
                    "",
                    1,
                    Map.of(),
                    Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 1)));

            assertThat(result.stdout()).isEqualTo("cleaned 0 native messages from payment.events\n");
            assertThat(result.actualOutput()).isEmpty();
            assertThat(result.messageCount()).isZero();
            served.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void cleansNatsZeroMessagesWhenNoMessageArrivesBeforeTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> serveNatsNoMessages(server));
            DefaultMessagingTransport transport = new DefaultMessagingTransport();

            MessagingTransportResult result = transport.cleanup(new MessagingTransportRequest(
                    "payment_events",
                    "nats",
                    "nats://127.0.0.1:" + server.getLocalPort(),
                    "payment.events",
                    "subject_ref",
                    "cleanup_payment_event",
                    "cleanup",
                    "",
                    "",
                    "",
                    1,
                    Map.of(),
                    Map.of("mode", "cleanup", "cleanup_strategy", "drain", "max_count", 1)));

            assertThat(result.stdout()).isEqualTo("cleaned 0 native messages from payment.events\n");
            assertThat(result.actualOutput()).isEmpty();
            assertThat(result.messageCount()).isZero();
            served.get(5, TimeUnit.SECONDS);
        }
    }

    private void serveNatsObservation(ServerSocket server, String payload) {
        serveNatsMessages(server, List.of(payload));
    }

    private void serveNatsMessages(ServerSocket server, List<String> payloads) {
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
            for (String payload : payloads) {
                byte[] message = payload.getBytes(StandardCharsets.UTF_8);
                output.write(("MSG payment.events 1 " + message.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(message);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsPublish(ServerSocket server, String expectedPayload, boolean rejectPublish) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            assertThat(reader.readLine()).startsWith("CONNECT ");
            String publish = reader.readLine();
            String[] publishParts = publish.split(" ");
            assertThat(publishParts).containsExactly(
                    "PUB",
                    "payment.events",
                    String.valueOf(expectedPayload.getBytes(StandardCharsets.UTF_8).length));
            assertThat(reader.readLine()).isEqualTo(expectedPayload);
            assertThat(reader.readLine()).isEqualTo("PING");
            output.write((rejectPublish ? "-ERR publish denied\r\n" : "PONG\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsPublishWithInterimStatus(ServerSocket server, String expectedPayload) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            assertThat(reader.readLine()).startsWith("CONNECT ");
            String publish = reader.readLine();
            String[] publishParts = publish.split(" ");
            assertThat(publishParts).containsExactly(
                    "PUB",
                    "payment.events",
                    String.valueOf(expectedPayload.getBytes(StandardCharsets.UTF_8).length));
            assertThat(reader.readLine()).isEqualTo(expectedPayload);
            assertThat(reader.readLine()).isEqualTo("PING");
            output.write("+OK\r\nPONG\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsRequestReply(ServerSocket server, String requestPayload, String replyPayload) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            reader.readLine();
            String subscribe = reader.readLine();
            String publish = reader.readLine();
            String[] publishParts = publish.split(" ");
            assertThat(subscribe).startsWith("SUB _INBOX.spec-regression.");
            assertThat(publishParts).hasSize(4);
            assertThat(publishParts[0]).isEqualTo("PUB");
            assertThat(publishParts[1]).isEqualTo("payment.authorization");
            assertThat(publishParts[2]).startsWith("_INBOX.spec-regression.");
            assertThat(Integer.parseInt(publishParts[3])).isEqualTo(requestPayload.getBytes(StandardCharsets.UTF_8).length);
            assertThat(reader.readLine()).isEqualTo(requestPayload);
            assertThat(reader.readLine()).isEqualTo("PING");
            output.write("PONG\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] message = replyPayload.getBytes(StandardCharsets.UTF_8);
            output.write(("MSG " + publishParts[2] + " 1 " + message.length + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(message);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsRequestReplyWithConfiguredSubject(
            ServerSocket server,
            String requestPayload,
            String replyPayload,
            String replySubject) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            reader.readLine();
            assertThat(reader.readLine()).isEqualTo("SUB " + replySubject + " 1");
            String publish = reader.readLine();
            String[] publishParts = publish.split(" ");
            assertThat(publishParts).containsExactly(
                    "PUB",
                    "payment.authorization",
                    replySubject,
                    String.valueOf(requestPayload.getBytes(StandardCharsets.UTF_8).length));
            assertThat(reader.readLine()).isEqualTo(requestPayload);
            assertThat(reader.readLine()).isEqualTo("PING");
            byte[] message = replyPayload.getBytes(StandardCharsets.UTF_8);
            output.write(("MSG " + replySubject + " 1 " + message.length + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(message);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsRequestReplyError(ServerSocket server, String requestPayload, String errorLine) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            reader.readLine();
            reader.readLine();
            String publish = reader.readLine();
            String[] publishParts = publish.split(" ");
            assertThat(Integer.parseInt(publishParts[3]))
                    .isEqualTo(requestPayload.getBytes(StandardCharsets.UTF_8).length);
            assertThat(reader.readLine()).isEqualTo(requestPayload);
            assertThat(reader.readLine()).isEqualTo("PING");
            output.write((errorLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsRequestReplyNoResponse(ServerSocket server, String requestPayload) {
        try (Socket socket = server.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII));
                OutputStream output = socket.getOutputStream()) {
            output.write("INFO {}\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            reader.readLine();
            reader.readLine();
            String publish = reader.readLine();
            String[] publishParts = publish.split(" ");
            assertThat(Integer.parseInt(publishParts[3]))
                    .isEqualTo(requestPayload.getBytes(StandardCharsets.UTF_8).length);
            assertThat(reader.readLine()).isEqualTo(requestPayload);
            assertThat(reader.readLine()).isEqualTo("PING");
            output.write("PONG\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            Thread.sleep(1200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsSubscribeError(ServerSocket server, String errorLine) {
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
            output.write((errorLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsCloseAfterSubscribe(ServerSocket server) {
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
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsNoMessages(ServerSocket server) {
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
            Thread.sleep(1200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveNatsWithoutInfo(ServerSocket server) {
        try (Socket socket = server.accept();
                OutputStream output = socket.getOutputStream()) {
            output.write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
