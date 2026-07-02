package com.specdriven.regression.provider.grpc;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.wiremock.grpc.GrpcExtensionFactory;
import org.yaml.snakeyaml.Yaml;

public class GrpcMockProviderRuntime implements ProviderRuntime {

    private static final String PROVIDER_TYPE = "grpc_mock";
    private final Yaml yaml = new Yaml();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private WireMockServer server;
    private String targetUri;
    private String adminBaseUrl;

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "load_grpc_stub" -> loadGrpcStub(context, request);
            case "grpc_request_received" -> grpcRequestReceived(context, request);
            case "reset_mock" -> resetMock(context);
            default -> ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported gRPC mock operation `" + request.operation() + "`.",
                            "Use an operation declared by the grpc_mock Provider Contract."));
        };
    }

    private ProviderOperationResult loadGrpcStub(ProviderExecutionContext context, ProviderOperationRequest request) {
        try {
            startServer(context);
            if (Boolean.parseBoolean(parameterValue(request, "mock.reset_before_load", "true"))) {
                server.resetAll();
            }
            String responseRef = parameterValue(request, "grpc.response_ref", "");
            Path responsePath = context.suiteRoot().resolve(responseRef).normalize();
            if (!Files.isRegularFile(responsePath)) {
                writeServerLog(context, "gRPC response fixture missing: " + responsePath);
                return ProviderOperationResult.failed(
                        Map.of("target_uri", targetUri),
                        evidence("server_log", "provider-evidence/grpc/server_log.txt"),
                        ProviderFailure.of(
                                "PROVIDER_STUB_MISSING",
                                "PROVIDER_STUB_MISSING",
                                "gRPC response fixture is missing: " + responsePath,
                                "Restore checked-in gRPC response fixture `" + responseRef + "`."));
            }
            String service = parameterValue(request, "grpc.service", stringValue(context.bindingValues().get("service_name")));
            String method = parameterValue(request, "grpc.method", "");
            String requestJson = requestJson(context.suiteRoot(), parameterValue(request, "grpc.request_json", ""));
            String responseJson = Files.readString(responsePath).strip();
            String status = parameterValue(request, "grpc.status_code", "OK");
            postJson(adminBaseUrl + "/__admin/mappings", stubJson(service, method, requestJson, responseJson, status));
            write(context.runDir().resolve("provider-evidence/grpc/injected_stubs.yaml"), """
                    evidence_type: fixture_setup
                    evidence_classification: framework_provider_capability_only
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: %s
                    service: %s
                    method: %s
                    response_ref: %s
                    loaded_stub_count: 1
                    target_uri: %s
                    status: passed
                    masking_applied: true
                    """.formatted(context.providerId(), PROVIDER_TYPE, service, method, responseRef, targetUri));
            writeServerLog(context, "started gRPC mock at " + targetUri + "\nloaded gRPC stub " + service + "/" + method + "\n");
            return ProviderOperationResult.passed(
                    Map.of(
                            "target_uri", targetUri,
                            "loaded_stub_count", 1,
                            "injected_stub", "provider-evidence/grpc/injected_stubs.yaml",
                            "server_log", "provider-evidence/grpc/server_log.txt"),
                    evidence(
                            "injected_stub", "provider-evidence/grpc/injected_stubs.yaml",
                            "server_log", "provider-evidence/grpc/server_log.txt"));
        } catch (Exception e) {
            writeFailureDetail(context, "gRPC mock unavailable: " + safeMessage(e));
            return ProviderOperationResult.failed(
                    Map.of("target_uri", targetUri == null ? "" : targetUri),
                    evidence("failure_detail", "provider-evidence/grpc/failure_detail.yaml"),
                    ProviderFailure.of(
                            "PROVIDER_UNAVAILABLE",
                            "PROVIDER_UNAVAILABLE",
                            "gRPC mock is unavailable: " + safeMessage(e),
                            "Use dynamic port strategy, verify descriptor_ref, or free the configured gRPC mock port."));
        }
    }

    private ProviderOperationResult grpcRequestReceived(ProviderExecutionContext context, ProviderOperationRequest request) {
        if (server == null || !server.isRunning()) {
            return ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "PROVIDER_UNAVAILABLE",
                            "PROVIDER_UNAVAILABLE",
                            "gRPC mock server is not running.",
                            "Run load_grpc_stub before observing gRPC requests."));
        }
        try {
            String journal = get(adminBaseUrl + "/__admin/requests");
            write(context.runDir().resolve("provider-evidence/grpc/request_journal.json"),
                    maskSensitiveText(journal) + "\n");
            String service = parameterValue(request, "grpc.service", stringValue(context.bindingValues().get("service_name")));
            String method = parameterValue(request, "grpc.method", "");
            int matchedCount = countRequests(journal, service, method);
            writeServerLog(context, "observed gRPC requests service=" + service + " method=" + method
                    + " matched_count=" + matchedCount + "\n");
            return ProviderOperationResult.passed(
                    Map.of(
                            "target_uri", targetUri,
                            "request_journal", "provider-evidence/grpc/request_journal.json",
                            "matched_count", matchedCount,
                            "server_log", "provider-evidence/grpc/server_log.txt"),
                    evidence(
                            "request_journal", "provider-evidence/grpc/request_journal.json",
                            "server_log", "provider-evidence/grpc/server_log.txt"));
        } catch (Exception e) {
            writeFailureDetail(context, "gRPC request journal unavailable: " + safeMessage(e));
            return ProviderOperationResult.failed(
                    Map.of("target_uri", targetUri == null ? "" : targetUri),
                    evidence("failure_detail", "provider-evidence/grpc/failure_detail.yaml"),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "gRPC request observation failed: " + safeMessage(e),
                            "Review gRPC mock request journal evidence."));
        }
    }

    private ProviderOperationResult resetMock(ProviderExecutionContext context) {
        try {
            if (server != null && server.isRunning()) {
                server.resetAll();
                server.stop();
            }
            write(context.runDir().resolve("provider-evidence/grpc/cleanup.yaml"), """
                    evidence_type: fixture_cleanup
                    evidence_classification: framework_provider_capability_only
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: %s
                    status: passed
                    masking_applied: true
                    """.formatted(context.providerId(), PROVIDER_TYPE));
            return ProviderOperationResult.passed(
                    Map.of("cleanup_log", "provider-evidence/grpc/cleanup.yaml"),
                    evidence("cleanup", "provider-evidence/grpc/cleanup.yaml"));
        } catch (Exception e) {
            writeFailureDetail(context, "gRPC mock cleanup failed: " + safeMessage(e));
            return ProviderOperationResult.failed(
                    Map.of(),
                    evidence("failure_detail", "provider-evidence/grpc/failure_detail.yaml"),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "gRPC mock cleanup failed: " + safeMessage(e),
                            "Review gRPC mock cleanup evidence."));
        }
    }

    private void startServer(ProviderExecutionContext context) {
        if (server != null && server.isRunning()) {
            return;
        }
        Path rootDir = context.runDir().resolve("wiremock-grpc-root");
        GrpcDescriptorMaterializer.materialize(
                context.suiteRoot(),
                rootDir.resolve("grpc"),
                stringValue(context.bindingValues().get("descriptor_ref")));
        String portStrategy = stringValue(context.bindingValues().get("port_strategy"));
        if ("fixed".equals(portStrategy)) {
            int port = intValue(context.bindingValues().getOrDefault("fixed_port", context.bindingValues().get("port")), 0);
            server = new WireMockServer(options()
                    .withRootDirectory(rootDir.toString())
                    .extensions(new GrpcExtensionFactory())
                    .port(port));
        } else {
            server = new WireMockServer(options()
                    .withRootDirectory(rootDir.toString())
                    .extensions(new GrpcExtensionFactory())
                    .dynamicPort());
        }
        server.start();
        targetUri = "localhost:" + server.port();
        adminBaseUrl = "http://localhost:" + server.port();
    }

    private String stubJson(
            String service,
            String method,
            String requestJson,
            String responseJson,
            String status) {
        return """
                {
                  "request": {
                    "method": "POST",
                    "urlPath": "/%s/%s",
                    "bodyPatterns": [
                      { "equalToJson": %s }
                    ]
                  },
                  "response": {
                    "status": 200,
                    "headers": {
                      "grpc-status-name": "%s"
                    },
                    "body": %s
                  }
                }
                """.formatted(
                        escapeJson(service),
                        escapeJson(method),
                        toJson(requestJson),
                        escapeJson(status),
                        toJson(responseJson));
    }

    private int countRequests(String journal, String service, String method) {
        String expectedPath = "/" + service + "/" + method;
        int count = 0;
        for (Object requestValue : listValue(readMapText(journal).get("requests"))) {
            Map<String, Object> request = mapValue(mapValue(requestValue).get("request"));
            if (expectedPath.equals(stringValue(request.get("url")))) {
                count++;
            }
        }
        return count;
    }

    private String requestJson(Path suiteRoot, String refOrValue) {
        if (refOrValue.isBlank()) {
            return "{}";
        }
        Path file = suiteRoot.resolve(refOrValue).normalize();
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read gRPC request ref `" + refOrValue + "`.", e);
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
        write(context.runDir().resolve("provider-evidence/grpc/server_log.txt"), maskSensitiveText(content));
    }

    private void writeFailureDetail(ProviderExecutionContext context, String content) {
        String safeContent = maskSensitiveText(content);
        write(context.runDir().resolve("provider-evidence/grpc/failure_detail.yaml"), """
                evidence_type: failure_detail
                evidence_classification: framework_provider_capability_only
                downstream_release_evidence: false
                provider_id: %s
                provider_type: %s
                status: failed
                failure_code: OPERATION_FAILED
                detail: %s
                masking_applied: true
                """.formatted(context.providerId(), PROVIDER_TYPE, safeContent));
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write gRPC mock evidence: " + path, e);
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String maskSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("(?i)(\"(?:password|token|api_key|authorization|secret|credential)\"\\s*:\\s*\")[^\"]*(\")", "$1***MASKED***$2")
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1***MASKED***")
                .replaceAll("(?i)(jdbc:[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)(mongodb(?:\\+srv)?://[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)(nats://[^\\s,}\\]]+)", "***MASKED***")
                .replaceAll("(?i)((?:password|token|secret|credential|api_key)\\s*[=:]\\s*)[^\\s,}\\]]+", "$1***MASKED***")
                .replaceAll("-----BEGIN [^-]+ PRIVATE KEY-----[\\s\\S]*?-----END [^-]+ PRIVATE KEY-----", "***MASKED***");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String toJson(String value) {
        return "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
