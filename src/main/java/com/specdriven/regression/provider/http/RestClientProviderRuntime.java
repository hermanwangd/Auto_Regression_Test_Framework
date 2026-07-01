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
import java.util.Map;

public class RestClientProviderRuntime implements ProviderRuntime {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "http_request" -> httpRequest(context, request);
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

    private ProviderOperationResult httpRequest(ProviderExecutionContext context, ProviderOperationRequest request) {
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
        String body = requestBody(context.suiteRoot(), parameterValue(request, "request.body", ""));
        Map<String, String> headers = requestHeaders(request);
        headers.putIfAbsent("Content-Type", "application/json");

        long started = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(baseUrl, path))
                    .timeout(requestTimeout(request));
            headers.forEach(builder::header);
            HttpRequest.BodyPublisher publisher = body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpResponse<String> response = httpClient.send(
                    builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
            long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("response.status", response.statusCode());
            outputs.put("response.headers", response.headers().map());
            outputs.put("response.body", response.body());
            outputs.put("response.duration_ms", durationMs);
            writeRequestEvidence(context, method, path, headers, body);
            writeResponseEvidence(context, response.statusCode(), response.body(), response.headers().map(), durationMs, "passed", "");
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
            return failed(context, "OPERATION_FAILED", "FRAMEWORK_ERROR", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(context, "OPERATION_FAILED", "FRAMEWORK_ERROR", "REST request was interrupted.");
        }
    }

    private ProviderOperationResult failed(
            ProviderExecutionContext context,
            String code,
            String classification,
            String reason) {
        writeResponseEvidence(context, 0, "", Map.of(), 0, "failed", reason);
        return ProviderOperationResult.failed(
                Map.of(),
                evidence("http_request_response", "provider-evidence/http/response.json"),
                ProviderFailure.of(
                        code,
                        classification,
                        "rest_client operation failed: " + reason,
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

    private String requestBody(Path suiteRoot, String refOrValue) {
        if (refOrValue.isBlank()) {
            return "";
        }
        Path file = suiteRoot.resolve(refOrValue).normalize();
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read request body ref `" + refOrValue + "`.", e);
            }
        }
        return refOrValue;
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return stringValue(parameter.get("ref"));
            }
        }
        return fallback;
    }

    private void writeRequestEvidence(
            ProviderExecutionContext context,
            String method,
            String path,
            Map<String, String> headers,
            String body) {
        write(context.runDir().resolve("provider-evidence/http/request.json"), """
                {
                  "evidence_type": "http_request_response",
                  "provider_type": "rest_client",
                  "provider_id": "%s",
                  "method": "%s",
                  "path": "%s",
                  "headers": %s,
                  "body": %s,
                  "masking_applied": true
                }
                """.formatted(
                        escape(context.providerId()),
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
