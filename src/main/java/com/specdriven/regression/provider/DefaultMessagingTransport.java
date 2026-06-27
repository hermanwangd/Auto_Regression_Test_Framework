package com.specdriven.regression.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

class DefaultMessagingTransport implements MessagingTransport {

    private final Function<Properties, Consumer<String, String>> kafkaConsumerFactory;

    DefaultMessagingTransport() {
        this(KafkaConsumer::new);
    }

    DefaultMessagingTransport(Function<Properties, Consumer<String, String>> kafkaConsumerFactory) {
        this.kafkaConsumerFactory = kafkaConsumerFactory;
    }

    @Override
    public MessagingTransportResult publish(MessagingTransportRequest request)
            throws IOException, InterruptedException {
        return switch (request.providerType().toLowerCase(Locale.ROOT)) {
            case "local", "mock" -> localPublish(request);
            case "kafka" -> kafkaPublish(request);
            case "nats" -> natsPublish(request);
            default -> throw new IOException("Unsupported messaging provider_type `"
                    + request.providerType() + "` for execution.");
        };
    }

    private MessagingTransportResult localPublish(MessagingTransportRequest request) {
        return new MessagingTransportResult(
                "published 1 message to " + request.targetRef() + "\n",
                request.payload(),
                1);
    }

    @Override
    public MessagingTransportResult observe(MessagingTransportRequest request)
            throws IOException {
        return switch (request.providerType().toLowerCase(Locale.ROOT)) {
            case "local", "mock" -> localObserve(request);
            case "kafka" -> kafkaObserve(request);
            case "nats" -> natsObserve(request);
            default -> throw new IOException("Unsupported messaging provider_type `"
                    + request.providerType() + "` for observe execution.");
        };
    }

    private MessagingTransportResult localObserve(MessagingTransportRequest request) throws IOException {
        int expectedCount = expectedCount(request);
        String payload = firstText(request.action(), "observed_payload", "expected_payload", "sample_payload");
        return new MessagingTransportResult(
                observedStdout(request, expectedCount),
                payload,
                expectedCount);
    }

    @Override
    public MessagingTransportResult cleanup(MessagingTransportRequest request)
            throws IOException {
        return switch (request.providerType().toLowerCase(Locale.ROOT)) {
            case "local", "mock" -> localCleanup(request);
            case "kafka" -> kafkaCleanup(request);
            case "nats" -> natsCleanup(request);
            default -> throw new IOException("Unsupported messaging provider_type `"
                    + request.providerType() + "` for cleanup execution.");
        };
    }

    private MessagingTransportResult localCleanup(MessagingTransportRequest request) throws IOException {
        int cleanedCount = optionalNonNegativeCount(request, "cleanup_count", "cleaned_count");
        return new MessagingTransportResult(
                cleanupStdout(request, cleanedCount),
                "",
                cleanedCount);
    }

    private MessagingTransportResult kafkaPublish(MessagingTransportRequest request)
            throws IOException, InterruptedException {
        Properties properties = new Properties();
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers(request.connectionRef()));
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeoutMs + 1000);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeoutMs);

        String topic = stripScheme(request.targetRef(), "kafka");
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topic,
                blankToNull(request.correlationId()),
                request.payload());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            RecordMetadata metadata = producer.send(record).get(request.timeoutSeconds(), TimeUnit.SECONDS);
            String stdout = "published 1 native message to " + request.targetRef()
                    + " partition=" + metadata.partition()
                    + " offset=" + metadata.offset() + "\n";
            return new MessagingTransportResult(stdout, request.payload(), 1);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Kafka publish failed for `" + request.targetRef() + "`.", e);
        }
    }

    private MessagingTransportResult kafkaObserve(MessagingTransportRequest request) throws IOException {
        Properties properties = new Properties();
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers(request.connectionRef()));
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup(request));
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);

        int expectedCount = expectedCount(request);
        List<String> observed = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, request.timeoutSeconds()));
        try (Consumer<String, String> consumer = kafkaConsumerFactory.apply(properties)) {
            consumer.subscribe(Collections.singletonList(stripScheme(request.targetRef(), "kafka")));
            while (observed.size() < expectedCount && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    if (matchesCorrelation(record.key(), record.value(), request.correlationId())) {
                        observed.add(record.value());
                        if (observed.size() >= expectedCount) {
                            break;
                        }
                    }
                }
            }
        }
        if (observed.size() < expectedCount) {
            throw new IOException("Kafka " + request.mode() + " expected at least " + expectedCount
                    + " messages from `" + request.targetRef() + "` but observed " + observed.size() + ".");
        }
        return new MessagingTransportResult(
                observedStdout(request, observed.size()),
                observedPayload(observed),
                observed.size());
    }

    private MessagingTransportResult kafkaCleanup(MessagingTransportRequest request) throws IOException {
        Properties properties = new Properties();
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers(request.connectionRef()));
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup(request));
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);

        int maxCount = cleanupLimit(request);
        List<String> cleaned = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, request.timeoutSeconds()));
        try (Consumer<String, String> consumer = kafkaConsumerFactory.apply(properties)) {
            consumer.subscribe(Collections.singletonList(stripScheme(request.targetRef(), "kafka")));
            while (cleaned.size() < maxCount && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) {
                    continue;
                }
                for (ConsumerRecord<String, String> record : records) {
                    if (matchesCorrelation(record.key(), record.value(), request.correlationId())) {
                        cleaned.add(record.value());
                        if (cleaned.size() >= maxCount) {
                            break;
                        }
                    }
                }
            }
            consumer.commitSync();
        }
        return new MessagingTransportResult(
                cleanupStdout(request, cleaned.size()),
                observedPayload(cleaned),
                cleaned.size());
    }

    private String kafkaBootstrapServers(String connectionRef) throws IOException {
        String resolved = resolveRef(connectionRef);
        if (resolved.startsWith("kafka://")) {
            return resolved.substring("kafka://".length());
        }
        return resolved;
    }

    private MessagingTransportResult natsPublish(MessagingTransportRequest request)
            throws IOException {
        URI uri = natsUri(resolveRef(request.connectionRef()));
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        byte[] payload = request.payload().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), natsPort(uri)), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            readNatsInfo(input);
            writeAscii(output, "CONNECT {\"verbose\":false,\"pedantic\":false}\r\n");
            writeAscii(output, "PUB " + stripScheme(request.targetRef(), "nats") + " " + payload.length + "\r\n");
            output.write(payload);
            writeAscii(output, "\r\nPING\r\n");
            output.flush();
            awaitNatsPong(input);
        }
        return new MessagingTransportResult(
                "published 1 native message to " + request.targetRef() + "\n",
                request.payload(),
                1);
    }

    private MessagingTransportResult natsObserve(MessagingTransportRequest request)
            throws IOException {
        URI uri = natsUri(resolveRef(request.connectionRef()));
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        int expectedCount = expectedCount(request);
        List<String> observed = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), natsPort(uri)), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            readNatsInfo(input);
            writeAscii(output, "CONNECT {\"verbose\":false,\"pedantic\":false}\r\n");
            writeAscii(output, "SUB " + stripScheme(request.targetRef(), "nats") + " 1\r\nPING\r\n");
            output.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, request.timeoutSeconds()));
            while (observed.size() < expectedCount && System.nanoTime() < deadline) {
                String line;
                try {
                    line = readLine(input);
                } catch (SocketTimeoutException e) {
                    break;
                } catch (IOException e) {
                    if (e.getMessage() != null
                            && e.getMessage().startsWith("Connection closed while reading messaging protocol")) {
                        break;
                    }
                    throw e;
                }
                if (line.startsWith("MSG ")) {
                    String payload = readNatsPayload(input, line);
                    if (matchesCorrelation("", payload, request.correlationId())) {
                        observed.add(payload);
                    }
                } else if (line.startsWith("-ERR")) {
                    throw new IOException("NATS " + request.mode() + " failed: " + line);
                }
            }
        }
        if (observed.size() < expectedCount) {
            throw new IOException("NATS " + request.mode() + " expected at least " + expectedCount
                    + " messages from `" + request.targetRef() + "` but observed " + observed.size() + ".");
        }
        return new MessagingTransportResult(
                observedStdout(request, observed.size()),
                observedPayload(observed),
                observed.size());
    }

    private MessagingTransportResult natsCleanup(MessagingTransportRequest request)
            throws IOException {
        URI uri = natsUri(resolveRef(request.connectionRef()));
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        int maxCount = cleanupLimit(request);
        List<String> cleaned = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), natsPort(uri)), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            readNatsInfo(input);
            writeAscii(output, "CONNECT {\"verbose\":false,\"pedantic\":false}\r\n");
            writeAscii(output, "SUB " + stripScheme(request.targetRef(), "nats") + " 1\r\nPING\r\n");
            output.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, request.timeoutSeconds()));
            while (cleaned.size() < maxCount && System.nanoTime() < deadline) {
                String line;
                try {
                    line = readLine(input);
                } catch (SocketTimeoutException e) {
                    break;
                } catch (IOException e) {
                    if (e.getMessage() != null
                            && e.getMessage().startsWith("Connection closed while reading messaging protocol")) {
                        break;
                    }
                    throw e;
                }
                if (line.startsWith("MSG ")) {
                    String payload = readNatsPayload(input, line);
                    if (matchesCorrelation("", payload, request.correlationId())) {
                        cleaned.add(payload);
                    }
                } else if (line.startsWith("-ERR")) {
                    throw new IOException("NATS " + request.mode() + " failed: " + line);
                }
            }
        }
        return new MessagingTransportResult(
                cleanupStdout(request, cleaned.size()),
                observedPayload(cleaned),
                cleaned.size());
    }

    private URI natsUri(String connectionRef) {
        URI uri = URI.create(connectionRef);
        if (uri.getScheme() == null) {
            return URI.create("nats://" + connectionRef);
        }
        return uri;
    }

    private int natsPort(URI uri) {
        return uri.getPort() > 0 ? uri.getPort() : 4222;
    }

    private void readNatsInfo(InputStream input) throws IOException {
        String line = readLine(input);
        if (!line.startsWith("INFO")) {
            throw new IOException("NATS server did not send INFO before publish.");
        }
    }

    private void awaitNatsPong(InputStream input) throws IOException {
        while (true) {
            String line = readLine(input);
            if (line.equals("PONG")) {
                return;
            }
            if (line.startsWith("-ERR")) {
                throw new IOException("NATS publish failed: " + line);
            }
        }
    }

    private String readNatsPayload(InputStream input, String header) throws IOException {
        String[] parts = header.split(" ");
        int length = Integer.parseInt(parts[parts.length - 1]);
        byte[] payload = input.readNBytes(length);
        readLine(input);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = input.read()) != -1) {
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                buffer.write(read);
            }
        }
        if (read == -1 && buffer.size() == 0) {
            throw new IOException("Connection closed while reading messaging protocol response.");
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void writeAscii(OutputStream output, String command) throws IOException {
        output.write(command.getBytes(StandardCharsets.US_ASCII));
    }

    private String resolveRef(String ref) throws IOException {
        if (ref.startsWith("env://")) {
            String envName = ref.substring("env://".length());
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new IOException("Environment variable `" + envName + "` is not set for messaging connection.");
            }
            return value;
        }
        return ref;
    }

    private int expectedCount(MessagingTransportRequest request) throws IOException {
        String expected = firstText(request.action(), "expected_count", "min_count");
        if (expected.isBlank()) {
            return 1;
        }
        try {
            int count = Integer.parseInt(expected);
            if (count > 0) {
                return count;
            }
        } catch (NumberFormatException e) {
            throw new IOException("Messaging " + request.mode()
                    + " expected_count/min_count must be a positive integer.", e);
        }
        throw new IOException("Messaging " + request.mode()
                + " expected_count/min_count must be a positive integer.");
    }

    private int cleanupLimit(MessagingTransportRequest request) throws IOException {
        String limit = firstText(request.action(), "max_count", "drain_count");
        if (limit.isBlank()) {
            return 100;
        }
        try {
            int count = Integer.parseInt(limit);
            if (count > 0) {
                return count;
            }
        } catch (NumberFormatException e) {
            throw new IOException("Messaging cleanup max_count/drain_count must be a positive integer.", e);
        }
        throw new IOException("Messaging cleanup max_count/drain_count must be a positive integer.");
    }

    private int optionalNonNegativeCount(MessagingTransportRequest request, String... fields) throws IOException {
        String countText = firstText(request.action(), fields);
        if (countText.isBlank()) {
            return 0;
        }
        try {
            int count = Integer.parseInt(countText);
            if (count >= 0) {
                return count;
            }
        } catch (NumberFormatException e) {
            throw new IOException("Messaging cleanup_count/cleaned_count must be a non-negative integer.", e);
        }
        throw new IOException("Messaging cleanup_count/cleaned_count must be a non-negative integer.");
    }

    private String consumerGroup(MessagingTransportRequest request) {
        String group = firstText(request.action(), "group_id", "group_id_ref", "consumer_group_ref");
        if (!group.isBlank()) {
            return group;
        }
        return "spec-regression-" + request.providerName() + "-" + request.actionName();
    }

    private boolean matchesCorrelation(String key, String payload, String correlationId) {
        return correlationId == null
                || correlationId.isBlank()
                || correlationId.equals(key)
                || (payload != null && payload.contains(correlationId));
    }

    private String observedPayload(List<String> observed) {
        if (observed.isEmpty()) {
            return "";
        }
        return String.join("\n", observed) + "\n";
    }

    private String observedStdout(MessagingTransportRequest request, int observedCount) {
        return "observed " + observedCount + " native messages from " + request.targetRef() + "\n";
    }

    private String cleanupStdout(MessagingTransportRequest request, int cleanedCount) {
        return "cleaned " + cleanedCount + " native messages from " + request.targetRef() + "\n";
    }

    private String stripScheme(String value, String scheme) {
        String prefix = scheme + "://";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
