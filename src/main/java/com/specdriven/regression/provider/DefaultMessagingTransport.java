package com.specdriven.regression.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

class DefaultMessagingTransport implements MessagingTransport {

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

    private String stripScheme(String value, String scheme) {
        String prefix = scheme + "://";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
