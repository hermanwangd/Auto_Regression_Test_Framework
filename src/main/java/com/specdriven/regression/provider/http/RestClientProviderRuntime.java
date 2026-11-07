package com.specdriven.regression.provider.http;

import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RestClientProviderRuntime implements ProviderRuntime {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final String MASK = "***MASKED***";

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "http_request" -> httpRequest(context, request, false);
            default -> ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported REST operation `" + request.operation() + "`.",
                            "Use an operation declared by the rest_client Provider Contract."));
        };
    }

    /** Used by the reviewed native adapter; preserves the ordinary REST interface and behavior. */
    public ProviderOperationResult executeReviewedRead(ProviderExecutionContext context, ProviderOperationRequest request) {
        if (!"http_request".equals(request.operation()) || !"native".equals(context.runtimeMode())
                || !context.bindingValues().keySet().equals(java.util.Set.of("base_url")))
            throw new IllegalArgumentException("Reviewed HTTP requires the native REST operation and exact base_url binding.");
        ReviewedLoopbackHttpPolicy.validateOrigin(context.bindingValues().get("base_url"));
        Map<String,Object> inputs = new LinkedHashMap<>();
        for (var parameter : request.parameters()) {
            String key = stringValue(parameter.get("bind_as"));
            if (inputs.putIfAbsent(key, parameter.get("ref")) != null)
                throw new IllegalArgumentException("Reviewed HTTP inputs must be unique.");
        }
        ReviewedLoopbackHttpPolicy.validateInputs(inputs);
        return httpRequest(context, request, true);
    }

    private ProviderOperationResult httpRequest(ProviderExecutionContext context, ProviderOperationRequest request, boolean reviewed) {
        String baseUrl = stringValue(context.bindingValues().get("base_url"));
        if (baseUrl.isBlank() || baseUrl.startsWith("generated://")) {
            return ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "MISSING_BINDING_KEY",
                            "TARGET_RESOLUTION_FAILED",
                            "rest_client base_url was not resolved.",
                            "Resolve generated base_url from the upstream mock provider before executing http_request."));
        }

        String method = parameterValue(request, "request.method", "GET");
        String path = parameterValue(request, "request.path", "/");
        String body = requestBody(context.suiteRoot(), parameterValueObject(request, "request.body", ""));
        Map<String, String> headers = requestHeaders(request);
        headers.putIfAbsent("Content-Type", "application/json");

        long started = System.nanoTime();
        try {
            URI requestUri = uri(baseUrl, path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri)
                    .timeout(requestTimeout(request));
            headers.forEach(builder::header);
            HttpRequest.BodyPublisher publisher = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest httpRequest = builder.method(method, publisher).build();
            HttpResponse<String> response = reviewed ? sendReviewed(httpRequest)
                    : httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            String maskedRequestUrl = maskUrl(requestUri.toString());
            Map<String, String> maskedRequestHeaders = maskHeaders(headers);
            Map<String, List<String>> maskedResponseHeaders = maskHeaderLists(response.headers().map());
            String maskedRequestBody = maskText(body);
            String maskedResponseBody = maskText(response.body());
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("request_url", maskedRequestUrl);
            outputs.put("response.status", response.statusCode());
            outputs.put("response.headers", maskedResponseHeaders);
            outputs.put("response.body", maskedResponseBody);
            outputs.put("response.duration_ms", durationMs);
            writeRequestEvidence(context, maskedRequestUrl, method, maskUrl(path), maskedRequestHeaders, maskedRequestBody);
            boolean redirect = reviewed && response.statusCode() >= 300 && response.statusCode() < 400;
            writeResponseEvidence(context, response.statusCode(), maskedResponseBody, maskedResponseHeaders, durationMs,
                    redirect ? "failed" : "passed", redirect ? "Reviewed HTTP redirects are prohibited and were not followed." : "");
            if (redirect) return ProviderOperationResult.failed(outputs,
                    evidence("http_request", "provider-evidence/http/request.json", "http_request_response", "provider-evidence/http/response.json"),
                    ProviderFailure.of("HTTP_REQUEST_FAILED", "VERIFICATION_FAILED", "Reviewed HTTP redirect was rejected.",
                            "Use the reviewed direct loopback endpoint; redirects are not followed."));
            return ProviderOperationResult.passed(
                    outputs,
                    evidence(
                            "http_request", "provider-evidence/http/request.json",
                            "http_request_response", "provider-evidence/http/response.json"));
        } catch (HttpTimeoutException e) {
            return failed(context, "PROVIDER_TIMEOUT", "PROVIDER_TIMEOUT", e.getMessage());
        } catch (ConnectException e) {
            return failed(context, "PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE", e.getMessage());
        } catch (IOException e) {
            return failed(context, reviewed ? "HTTP_REQUEST_FAILED" : "OPERATION_FAILED", "FRAMEWORK_ERROR", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(context, reviewed ? "HTTP_REQUEST_FAILED" : "OPERATION_FAILED", "FRAMEWORK_ERROR", "REST request was interrupted.");
        }
    }

    /** HttpRequest.timeout alone stops protecting the body after headers on JDK 17. */
    private HttpResponse<String> sendReviewed(HttpRequest request) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + request.timeout().orElse(Duration.ofSeconds(10)).toNanos();
        BoundedResponseSubscriber subscriber = new BoundedResponseSubscriber();
        var pending = httpClient.sendAsync(request, ignored -> subscriber);
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException();
            HttpResponse<String> response = pending.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (System.nanoTime() >= deadline) throw new java.util.concurrent.TimeoutException();
            return response;
        } catch (java.util.concurrent.TimeoutException error) {
            subscriber.cancel(new IOException("Reviewed HTTP whole-response deadline exceeded."));
            pending.cancel(true);
            throw new HttpTimeoutException("Reviewed HTTP whole-response deadline exceeded.");
        } catch (InterruptedException error) {
            subscriber.cancel(new IOException("Reviewed HTTP request was interrupted."));
            pending.cancel(true);
            throw error;
        } catch (java.util.concurrent.ExecutionException error) {
            subscriber.cancel(new IOException("Reviewed HTTP response did not complete."));
            pending.cancel(true);
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Reviewed HTTP response failed.", cause);
        }
    }

    /** Cancels before copying a chunk that would exceed the reviewed response budget. */
    private static final class BoundedResponseSubscriber implements HttpResponse.BodySubscriber<String> {
        private final java.util.concurrent.CompletableFuture<String> body = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private volatile java.util.concurrent.Flow.Subscription subscription;
        private volatile boolean closed;
        @Override public java.util.concurrent.CompletionStage<String> getBody() { return body; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            if (closed) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<java.nio.ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (closed || body.isDone()) return;
                if (chunk.remaining() > ReviewedLoopbackHttpPolicy.MAX_RESPONSE_BYTES - bytes.size()) {
                    cancel(new IOException("Reviewed HTTP response exceeds 1 MiB."));
                    return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            if (!closed && !body.isDone()) subscription.request(1);
        }
        @Override public void onError(Throwable error) { closed = true; body.completeExceptionally(error); }
        @Override public void onComplete() {
            if (!closed && !body.isDone()) body.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
        private void cancel(IOException reason) {
            closed = true;
            body.completeExceptionally(reason);
            var known = subscription;
            if (known != null) known.cancel();
        }
    }

    private ProviderOperationResult failed(
            ProviderExecutionContext context,
            String code,
            String classification,
            String reason) {
        String safeReason = maskText(stringValue(reason));
        writeResponseEvidence(context, 0, "", Map.of(), 0, "failed", safeReason);
        return ProviderOperationResult.failed(
                Map.of(),
                evidence("http_request_response", "provider-evidence/http/response.json"),
                ProviderFailure.of(
                        code,
                        classification,
                        "rest_client operation failed: " + safeReason,
                        "Review provider-evidence/http/response.json and Environment Binding base_url."));
    }

    private URI uri(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return URI.create(baseUrl + path.substring(1));
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return URI.create(baseUrl + "/" + path);
        }
        return URI.create(baseUrl + path);
    }

    private Duration requestTimeout(ProviderOperationRequest request) {
        String timeout = parameterValue(request, "request.timeout", "");
        if (timeout.isBlank()) {
            return Duration.ofSeconds(10);
        }
        try {
            return Duration.parse(timeout);
        } catch (RuntimeException e) {
            return Duration.ofSeconds(10);
        }
    }

    private Map<String, String> requestHeaders(ProviderOperationRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map<String, Object> parameter : request.parameters()) {
            String bindAs = stringValue(parameter.get("bind_as"));
            if (bindAs.startsWith("request.headers.")) {
                String header = bindAs.substring("request.headers.".length());
                if (!header.isBlank()) {
                    headers.put(headerName(header), stringValue(parameter.get("ref")));
                }
            }
        }
        return headers;
    }

    private String headerName(String header) {
        if ("content-type".equalsIgnoreCase(header)) {
            return "Content-Type";
        }
        return header;
    }

    private String requestBody(Path suiteRoot, Object refOrValue) {
        if (refOrValue == null || (refOrValue instanceof String text && text.isBlank())) {
            return "";
        }
        if (refOrValue instanceof String text) {
            Path file = suiteRoot.resolve(text).normalize();
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readString(file).strip();
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to read request body ref `" + text + "`.", e);
                }
            }
            return text;
        }
        return toJson(refOrValue);
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        return stringValue(parameterValueObject(request, bindAs, fallback));
    }

    private Object parameterValueObject(ProviderOperationRequest request, String bindAs, Object fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return parameter.get("ref");
            }
        }
        return fallback;
    }

    private void writeRequestEvidence(
            ProviderExecutionContext context,
            String requestUrl,
            String method,
            String path,
            Map<String, String> headers,
            String body) {
        write(context.runDir().resolve("provider-evidence/http/request.json"), """
                {
                  "evidence_type": "http_request_response",
                  "provider_type": "rest_client",
                  "provider_id": "%s",
                  "request_url": "%s",
                  "method": "%s",
                  "path": "%s",
                  "headers": %s,
                  "body": %s,
                  "masking_applied": true
                }
                """.formatted(
                        escape(context.providerId()),
                        escape(requestUrl),
                        escape(method),
                        escape(path),
                        toJson(headers),
                        toJson(body)));
    }

    private void writeResponseEvidence(
            ProviderExecutionContext context,
            int status,
            String body,
            Map<String, List<String>> headers,
            long durationMs,
            String operationStatus,
            String error) {
        write(context.runDir().resolve("provider-evidence/http/response.json"), """
                {
                  "evidence_type": "http_request_response",
                  "provider_type": "rest_client",
                  "provider_id": "%s",
                  "status": %s,
                  "body": %s,
                  "headers": %s,
                  "duration_ms": %s,
                  "operation_status": "%s",
                  "error": %s,
                  "masking_applied": true
                }
                """.formatted(
                        escape(context.providerId()),
                        status,
                        toJson(body),
                        toJson(headers),
                        durationMs,
                        escape(operationStatus),
                        toJson(error)));
    }

    private List<ProviderEvidence> evidence(String type, String ref) {
        return List.of(new ProviderEvidence(type, ref, true));
    }

    private List<ProviderEvidence> evidence(String type1, String ref1, String type2, String ref2) {
        List<ProviderEvidence> evidence = new ArrayList<>();
        evidence.add(new ProviderEvidence(type1, ref1, true));
        evidence.add(new ProviderEvidence(type2, ref2, true));
        return List.copyOf(evidence);
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write REST provider evidence: " + path, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String maskUrl(String value) {
        return value.replaceAll("(?i)([?&][^=]*(?:password|token|secret|credential|api[_-]?key|authorization|session)[^=]*=)[^&#]*", "$1" + MASK);
    }

    private Map<String, String> maskHeaders(Map<String, String> headers) {
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            masked.put(entry.getKey(), sensitiveName(entry.getKey()) ? MASK : maskText(entry.getValue()));
        }
        return masked;
    }

    private Map<String, List<String>> maskHeaderLists(Map<String, List<String>> headers) {
        Map<String, List<String>> masked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (sensitiveName(entry.getKey())) {
                masked.put(entry.getKey(), List.of(MASK));
            } else {
                masked.put(entry.getKey(), entry.getValue().stream().map(this::maskText).toList());
            }
        }
        return masked;
    }

    private boolean sensitiveName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("api-key")
                || normalized.contains("api_key");
    }

    private String maskText(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String masked = value
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1" + MASK)
                .replaceAll("(?i)(session=)[^;\\s]+", "$1" + MASK);
        masked = masked.replaceAll(
                "(?i)(\"(?:access_)?token\"\\s*:\\s*\")[^\"]*(\")",
                "$1" + MASK + "$2");
        masked = masked.replaceAll(
                "(?i)(\"(?:password|secret|credential|api[_-]?key|authorization|session)\"\\s*:\\s*\")[^\"]*(\")",
                "$1" + MASK + "$2");
        return masked;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escape(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
