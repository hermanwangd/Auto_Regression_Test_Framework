package com.specdriven.regression.provider.nats;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** One reviewed publish/echo on an owned subject of a disposable loopback broker. */
public final class ReviewedLoopbackNatsPolicy {
    public static final int MAX_PAYLOAD_BYTES = 65536;
    public static final String PAYLOAD_REF = "artifact://fixtures/payload.json";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ReviewedLoopbackNatsPolicy() { }

    public static URI origin(Object value) {
        require(value instanceof String text && text.matches("nats://127\\.0\\.0\\.1:[1-9][0-9]{0,4}"),
                "connection.uri must be exactly nats://127.0.0.1:<port>");
        URI uri = URI.create((String)value);
        require(uri.getPort() >= 1 && uri.getPort() <= 65535, "port must be 1..65535");
        return uri;
    }

    public static void validateBindings(Map<String,?> bindings) {
        require(bindings != null && bindings.keySet().equals(Set.of("connection.uri", "subject")),
                "bindings must contain exactly connection.uri and subject");
        origin(bindings.get("connection.uri"));
        require(bindings.get("subject") instanceof String subject && subject.matches("sit\\.[a-f0-9]{32}\\.echo"),
                "subject must be an owned sit.<32 lowercase hex>.echo without wildcards");
    }

    public static void validateInputs(Map<String,?> inputs) {
        require(inputs != null && inputs.containsKey("payload_ref")
                && Set.of("payload_ref", "timeout").containsAll(inputs.keySet()),
                "only the pinned payload_ref and optional timeout are supported");
        require(PAYLOAD_REF.equals(inputs.get("payload_ref")), "payload_ref must be the exact reviewed fixture");
        if (inputs.containsKey("timeout")) require(inputs.get("timeout") != null, "timeout must not be null");
        timeout(inputs.get("timeout"));
    }

    public static Duration timeout(Object raw) {
        if (raw == null) return Duration.ofSeconds(5);
        require(raw instanceof String text && text.length() <= 32, "timeout must be a bounded ISO duration");
        try {
            Duration timeout = Duration.parse((String)raw);
            require(timeout.toMillis() >= 1 && timeout.compareTo(Duration.ofSeconds(5)) <= 0,
                    "timeout must be 1ms..5s");
            return timeout;
        } catch (java.time.DateTimeException | ArithmeticException error) {
            throw new IllegalArgumentException("reviewed_nats: invalid timeout", error);
        }
    }

    public static void validateFixture(Path root) {
        try {
            // JVM temporary roots can use an OS alias (for example /var -> /private/var on macOS).
            // Inside that trusted snapshot, neither the fixture nor its parent may redirect elsewhere.
            Path file = root.toRealPath().resolve("fixtures/payload.json");
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
                    && file.toRealPath().equals(file), "payload must be a contained regular nonsymlink file");
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_PAYLOAD_BYTES + 1);
            }
            require(bytes.length > 0 && bytes.length <= MAX_PAYLOAD_BYTES, "payload exceeds 1..65536 bytes");
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            var document = JSON.readTree(text);
            require(document != null && document.isObject(), "payload must be a strict JSON object");
        } catch (IOException error) {
            throw new IllegalArgumentException("reviewed_nats: payload must be bounded strict UTF-8 JSON", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("reviewed_nats: " + message);
    }
}
