package com.specdriven.regression.provider.http;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finite reviewed local HTTP reads; ordinary REST provider behavior is separate. */
public final class ReviewedLoopbackHttpPolicy {
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private ReviewedLoopbackHttpPolicy() { }

    public static void validateOrigin(Object value) {
        require(value instanceof String, "origin must be a literal string");
        String origin = (String)value;
        require(origin.matches("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}"),
                "origin must be exactly http://127.0.0.1:<port>");
        int port = URI.create(origin).getPort();
        require(port >= 1 && port <= 65535, "origin port is outside 1..65535");
    }

    public static void validateInputs(Map<String,?> inputs) {
        require(inputs.keySet().containsAll(Set.of("request.method", "request.path"))
                && Set.of("request.method", "request.path", "request.timeout").containsAll(inputs.keySet()),
                "only literal method, path and optional timeout are supported");
        require(inputs.get("request.method") instanceof String method && Set.of("GET", "HEAD").contains(method),
                "only GET and HEAD are supported");
        require(inputs.get("request.path") instanceof String, "path must be a literal string");
        validateTarget((String)inputs.get("request.path"));
        if (inputs.containsKey("request.timeout")) {
            Object raw = inputs.get("request.timeout");
            require(raw instanceof String && ((String)raw).length() <= 32, "timeout must be a bounded ISO duration");
            try {
                Duration timeout = Duration.parse((String)raw);
                require(!timeout.isNegative() && !timeout.isZero() && timeout.toMillis() >= 1
                        && timeout.compareTo(Duration.ofSeconds(10)) <= 0, "timeout must be 1ms..10s");
            } catch (java.time.DateTimeException | ArithmeticException error) {
                throw new IllegalArgumentException("reviewed_http: timeout must be a bounded ISO duration", error);
            }
        }
    }

    private static void validateTarget(String target) {
        require(target.length() <= 2048 && target.chars().allMatch(c -> c >= 33 && c <= 126)
                && target.indexOf('#') < 0, "target must be bounded ASCII without a fragment");
        int separator = target.indexOf('?');
        String path = separator < 0 ? target : target.substring(0, separator);
        require(path.matches("/[A-Za-z0-9/_.-]*") && !path.startsWith("//")
                && URI.create(path).normalize().toString().equals(path), "path must be a static absolute local path");
        if (separator < 0) return;
        String[] pairs = target.substring(separator + 1).split("&", -1);
        require(pairs.length <= 32, "query exceeds 32 pairs");
        Set<String> keys = new HashSet<>();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            require(equals > 0 && equals == pair.lastIndexOf('='), "query requires key=value pairs");
            String key = decodeQueryPart(pair.substring(0, equals));
            String value = decodeQueryPart(pair.substring(equals + 1));
            require(key.length() <= 128 && key.matches("[A-Za-z0-9_.:-]+"), "unsupported query key");
            require(value.length() <= 512 && value.matches("[A-Za-z0-9 _.,:+~-]*"), "unsupported query value");
            require(keys.add(key), "duplicate decoded query key");
            String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[._:-]", "");
            require(!Set.of("auth", "pwd").contains(normalized)
                    && !normalized.matches(".*(?:password|passwd|token|secret|authorization|credential|apikey|session|cookie|connection).*"),
                    "sensitive query keys are unsupported");
        }
    }

    /** Decode only for validation; callers transmit the original reviewed target. */
    private static String decodeQueryPart(String raw) {
        byte[] bytes = new byte[raw.length()];
        int length = 0;
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (current == '%') {
                require(i + 2 < raw.length(), "incomplete query escape");
                int high = Character.digit(raw.charAt(++i), 16);
                int low = Character.digit(raw.charAt(++i), 16);
                require(high >= 0 && low >= 0, "invalid query escape");
                bytes[length++] = (byte)((high << 4) | low);
            } else {
                bytes[length++] = (byte)(current == '+' ? ' ' : current);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("reviewed_http: invalid UTF-8 query escape", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("reviewed_http: " + message);
    }
}
