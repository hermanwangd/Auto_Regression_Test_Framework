package com.specdriven.regression.provider.wiremock;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class WireMockHttpMockProviderRuntime implements ProviderRuntime {

    private final Yaml yaml = new Yaml();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WireMockServer server;
    private String baseUrl;

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "load_stubs" -> loadStubs(context, request);
            case "send_http_request" -> sendHttpRequest(context, request);
            case "reset_mock" -> resetMock(context);
            default -> ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported WireMock operation `" + request.operation() + "`.",
                            "Use an operation declared by the WireMock Provider Contract."));
        };
    }

    private ProviderOperationResult loadStubs(ProviderExecutionContext context, ProviderOperationRequest request) {
        try {
            startServer(context);
            if (Boolean.parseBoolean(parameterValue(request, "mock.reset_before_load", "true"))) {
                server.resetAll();
            }
            Path stub = context.suiteRoot().resolve(parameterValue(request, "mock.mappings_ref", "")).normalize();
            if (!Files.isRegularFile(stub)) {
                writeServerLog(context, "stub mapping missing: " + stub);
                return ProviderOperationResult.failed(
                        Map.of("base_url", baseUrl),
                        evidence("server_log", "provider-evidence/wiremock/server_log.txt"),
                        ProviderFailure.of(
                                "PROVIDER_STUB_MISSING",
                                "PROVIDER_STUB_MISSING",
                                "WireMock stub mapping is missing: " + stub,
                                "Restore checked-in WireMock stub mapping `" + stub + "`."));
            }
            String stubJson = Files.readString(stub);
            postJson(baseUrl + "/__admin/mappings", stubJson);
            write(context.runDir().resolve("provider-evidence/wiremock/injected_stubs.yaml"), """
                    evidence_type: injected_stub
                    evidence_classification: %s
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: wiremock_http_mock
                    stub_ref: %s
                    loaded_stub_count: 1
                    base_url: %s
                    """.formatted(evidenceClassification(context), context.providerId(), stub, baseUrl));
            writeServerLog(context, "started WireMock at " + baseUrl + "\ninjected stub " + stub + "\n");
            return ProviderOperationResult.passed(
                    Map.of(
                            "base_url", baseUrl,
                            "loaded_stub_count", 1,
                            "injected_stub", "provider-evidence/wiremock/injected_stubs.yaml",
                            "server_log", "provider-evidence/wiremock/server_log.txt"),
                    evidence(
                            "injected_stub", "provider-evidence/wiremock/injected_stubs.yaml",
                            "server_log", "provider-evidence/wiremock/server_log.txt"));
        } catch (Exception e) {
            writeFailureDetail(context, "provider unavailable: " + e.getMessage());
            return ProviderOperationResult.failed(
                    Map.of(),
                    evidence("failure_detail", "provider-evidence/wiremock/failure_detail.yaml"),
                    ProviderFailure.of(
                            "PROVIDER_UNAVAILABLE",
                            "PROVIDER_UNAVAILABLE",
                            "WireMock is unavailable: " + e.getMessage(),
                            "Use dynamic port strategy or free the configured WireMock port."));
        }
    }

    private ProviderOperationResult sendHttpRequest(ProviderExecutionContext context, ProviderOperationRequest request) {
        try {
            if (server == null || !server.isRunning()) {
                return ProviderOperationResult.failed(
                        Map.of(),
                        List.of(),
                        ProviderFailure.of(
                                "PROVIDER_UNAVAILABLE",
                                "PROVIDER_UNAVAILABLE",
                                "WireMock server is not running.",
                                "Run load_stubs before send_http_request."));
            }
            Path requestInputPath = context.suiteRoot().resolve(parameterValue(request, "http.request_body", "")).normalize();
            Path expectedRequestPath = context.suiteRoot().resolve(parameterValue(request, "mock.request_filter", "")).normalize();
            Map<String, Object> requestInput = readMap(requestInputPath);
            Map<String, Object> expectedRequest = readMap(expectedRequestPath);
            String method = parameterValue(request, "http.method", stringValue(requestInput.getOrDefault("method", "POST")));
            String path = parameterValue(request, "http.path", stringValue(requestInput.getOrDefault("path", "/")));
            String body = toJson(requestInput.get("body"));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            String journal = get(baseUrl + "/__admin/requests");
            write(context.runDir().resolve("provider-evidence/wiremock/request_journal.json"), journal + "\n");
            writeServerLog(context, "called " + method + " " + path + " -> " + response.statusCode() + "\n");
            int matchedCount = countExpectedRequests(journal, expectedRequest);
            return ProviderOperationResult.passed(
                    Map.of(
                            "base_url", baseUrl,
                            "http_status", response.statusCode(),
                            "response_body", response.body(),
                            "matched_count", matchedCount,
                            "request_journal", "provider-evidence/wiremock/request_journal.json",
                            "server_log", "provider-evidence/wiremock/server_log.txt"),
                    evidence(
                            "request_journal", "provider-evidence/wiremock/request_journal.json",
                            "server_log", "provider-evidence/wiremock/server_log.txt"));
        } catch (ConnectException e) {
            writeFailureDetail(context, "provider unavailable: " + e.getMessage());
            return ProviderOperationResult.failed(
                    Map.of(),
                    evidence("failure_detail", "provider-evidence/wiremock/failure_detail.yaml"),
                    ProviderFailure.of(
                            "PROVIDER_UNAVAILABLE",
                            "PROVIDER_UNAVAILABLE",
                            "WireMock is unavailable: " + e.getMessage(),
                            "Check WireMock startup and configured port."));
        } catch (Exception e) {
            writeFailureDetail(context, "operation failed: " + e.getMessage());
            return ProviderOperationResult.failed(
                    Map.of("base_url", baseUrl == null ? "" : baseUrl),
                    evidence("failure_detail", "provider-evidence/wiremock/failure_detail.yaml"),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "WireMock operation failed: " + e.getMessage(),
                            "Review WireMock provider evidence."));
        }
    }

    private ProviderOperationResult resetMock(ProviderExecutionContext context) {
        try {
            if (server != null && server.isRunning()) {
                server.resetAll();
                server.stop();
            }
            write(context.runDir().resolve("provider-evidence/wiremock/cleanup.yaml"), """
                    evidence_type: cleanup
                    evidence_classification: %s
                    downstream_release_evidence: false
                    provider_id: %s
                    status: passed
                    """.formatted(evidenceClassification(context), context.providerId()));
            return ProviderOperationResult.passed(
                    Map.of("cleanup_log", "provider-evidence/wiremock/cleanup.yaml"),
                    evidence("cleanup", "provider-evidence/wiremock/cleanup.yaml"));
        } catch (Exception e) {
            return ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "WireMock cleanup failed: " + e.getMessage(),
                            "Review cleanup evidence."));
        }
    }

    private void startServer(ProviderExecutionContext context) {
        if (server != null && server.isRunning()) {
            return;
        }
        String portStrategy = stringValue(context.bindingValues().get("port_strategy"));
        if ("fixed".equals(portStrategy)) {
            int port = intValue(firstNonNull(context.bindingValues().get("fixed_port"), context.bindingValues().get("port")), 0);
            server = new WireMockServer(options().port(port));
        } else {
            server = new WireMockServer(options().dynamicPort());
        }
        server.start();
        baseUrl = "http://localhost:" + server.port();
    }

    private int countExpectedRequests(String journal, Map<String, Object> expectedRequest) {
        String expectedPath = stringValue(expectedRequest.get("path"));
        String expectedMethod = stringValue(expectedRequest.get("method"));
        int count = 0;
        for (Object requestValue : listValue(readMapText(journal).get("requests"))) {
            Map<String, Object> request = mapValue(mapValue(requestValue).get("request"));
            if (expectedPath.equals(stringValue(request.get("url")))
                    && expectedMethod.equals(stringValue(request.get("method")))) {
                count++;
            }
        }
        return count;
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return stringValue(parameter.get("ref"));
            }
        }
        return fallback;
    }

    private void postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("WireMock admin returned " + response.statusCode() + ": " + response.body());
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Path path) {
        try {
            Object loaded = yaml.load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read WireMock artifact: " + path, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMapText(String text) {
        Object loaded = yaml.load(text);
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    private List<ProviderEvidence> evidence(String evidenceType, String ref) {
        return List.of(new ProviderEvidence(evidenceType, ref, true));
    }

    private List<ProviderEvidence> evidence(String type1, String ref1, String type2, String ref2) {
        List<ProviderEvidence> evidence = new ArrayList<>();
        evidence.add(new ProviderEvidence(type1, ref1, true));
        evidence.add(new ProviderEvidence(type2, ref2, true));
        return List.copyOf(evidence);
    }

    private void writeServerLog(ProviderExecutionContext context, String content) {
        Path log = context.runDir().resolve("provider-evidence/wiremock/server_log.txt");
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write WireMock server log", e);
        }
    }

    private void writeFailureDetail(ProviderExecutionContext context, String content) {
        write(context.runDir().resolve("provider-evidence/wiremock/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: %s
                downstream_release_evidence: false
                provider_id: %s
                provider_type: wiremock_http_mock
                detail: %s
                """.formatted(evidenceClassification(context), context.providerId(), content));
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write WireMock evidence: " + path, e);
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String evidenceClassification(ProviderExecutionContext context) {
        String classification = stringValue(context.bindingValues().get("_evidence_classification"));
        return classification.isBlank() ? "framework_provider_capability_only" : classification;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(":").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }
}
