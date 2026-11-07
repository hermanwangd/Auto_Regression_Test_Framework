package com.specdriven.regression.provider.http;

import java.net.URI;
import java.time.Duration;
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
        String path = (String)inputs.get("request.path");
        require(path.length() <= 2048 && path.matches("/[A-Za-z0-9/_.-]*") && !path.startsWith("//")
                && URI.create(path).normalize().toString().equals(path), "path must be a static absolute local path");
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("reviewed_http: " + message);
    }
}
