package com.specdriven.regression.provider.soap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
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
import org.yaml.snakeyaml.Yaml;

public class SoapMockProviderRuntime implements ProviderRuntime {

    private static final String PROVIDER_TYPE = "soap_mock";
    private final Yaml yaml = new Yaml();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private WireMockServer server;
    private String endpointUrl;

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "load_soap_stub" -> loadSoapStub(context, request);
            case "soap_request_received" -> soapRequestReceived(context, request);
            case "reset_mock" -> resetMock(context);
            default -> ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported SOAP mock operation `" + request.operation() + "`.",
                            "Use an operation declared by the soap_mock Provider Contract."));
        };
    }

    private ProviderOperationResult loadSoapStub(ProviderExecutionContext context, ProviderOperationRequest request) {
        try {
            startServer(context);
            if (Boolean.parseBoolean(parameterValue(request, "mock.reset_before_load", "true"))) {
                server.resetAll();
            }
            String responseRef = parameterValue(request, "soap.response_ref", "");
            Path responsePath = context.suiteRoot().resolve(responseRef).normalize();
            if (!Files.isRegularFile(responsePath)) {
                writeServerLog(context, "SOAP response fixture missing: " + responsePath);
                return ProviderOperationResult.failed(
                        Map.of("endpoint_url", endpointUrl),
                        evidence("server_log", "provider-evidence/soap/server_log.txt"),
                        ProviderFailure.of(
                                "PROVIDER_STUB_MISSING",
                                "PROVIDER_STUB_MISSING",
                                "SOAP response fixture is missing: " + responsePath,
                                "Restore checked-in SOAP response fixture `" + responseRef + "`."));
            }

            String path = parameterValue(request, "soap.path", "/");
            String action = parameterValue(request, "soap.action", "");
            String requestXpath = parameterValue(request, "soap.request_xpath", "");
            int status = intValue(parameterValue(request, "soap.status", "200"), 200);
            String responseBody = Files.readString(responsePath);

            MappingBuilder mapping = post(urlEqualTo(path));
            if (!action.isBlank()) {
                mapping.withHeader("SOAPAction", equalTo(action));
            }
            if (!requestXpath.isBlank()) {
                mapping.withRequestBody(matchingXPath(requestXpath));
            }
            server.stubFor(mapping.willReturn(aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "text/xml; charset=utf-8")
                    .withBody(responseBody)));

            write(context.runDir().resolve("provider-evidence/soap/injected_stubs.yaml"), """
                    evidence_type: fixture_setup
                    evidence_classification: framework_provider_capability_only
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: %s
                    soap_path: %s
                    soap_action: %s
                    response_ref: %s
                    loaded_stub_count: 1
                    endpoint_url: %s
                    status: passed
                    masking_applied: true
                    """.formatted(context.providerId(), PROVIDER_TYPE, path, action, responseRef, endpointUrl));
            writeServerLog(context, "started SOAP mock at " + endpointUrl + "\nloaded SOAP stub " + responseRef + "\n");
            return ProviderOperationResult.passed(
                    Map.of(
                            "endpoint_url", endpointUrl,
                            "loaded_stub_count", 1,
                            "injected_stub", "provider-evidence/soap/injected_stubs.yaml",
                            "server_log", "provider-evidence/soap/server_log.txt"),
                    evidence(
                            "injected_stub", "provider-evidence/soap/injected_stubs.yaml",
                            "server_log", "provider-evidence/soap/server_log.txt"));
        } catch (Exception e) {
            writeFailureDetail(context, "SOAP mock unavailable: " + safeMessage(e));
            return ProviderOperationResult.failed(
                    Map.of("endpoint_url", endpointUrl == null ? "" : endpointUrl),
                    evidence("failure_detail", "provider-evidence/soap/failure_detail.yaml"),
                    ProviderFailure.of(
                            "PROVIDER_UNAVAILABLE",
                            "PROVIDER_UNAVAILABLE",
                            "SOAP mock is unavailable: " + safeMessage(e),
                            "Use dynamic port strategy or free the configured SOAP mock port."));
        }
    }

    private ProviderOperationResult soapRequestReceived(ProviderExecutionContext context, ProviderOperationRequest request) {
        if (server == null || !server.isRunning()) {
            return ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "PROVIDER_UNAVAILABLE",
                            "PROVIDER_UNAVAILABLE",
                            "SOAP mock server is not running.",
                            "Run load_soap_stub before observing SOAP requests."));
        }
        try {
            String journal = get(endpointUrl + "/__admin/requests");
            write(context.runDir().resolve("provider-evidence/soap/request_journal.json"),
                    maskSensitiveText(journal) + "\n");
            String path = parameterValue(request, "soap.path", "/");
            String action = parameterValue(request, "soap.action", "");
            int matchedCount = countRequests(journal, path, action);
            writeServerLog(context, "observed SOAP requests path=" + path + " action=" + action
                    + " matched_count=" + matchedCount + "\n");
            return ProviderOperationResult.passed(
                    Map.of(
                            "endpoint_url", endpointUrl,
                            "request_journal", "provider-evidence/soap/request_journal.json",
                            "matched_count", matchedCount,
                            "server_log", "provider-evidence/soap/server_log.txt"),
                    evidence(
                            "request_journal", "provider-evidence/soap/request_journal.json",
                            "server_log", "provider-evidence/soap/server_log.txt"));
        } catch (Exception e) {
            writeFailureDetail(context, "SOAP request journal unavailable: " + safeMessage(e));
            return ProviderOperationResult.failed(
                    Map.of("endpoint_url", endpointUrl == null ? "" : endpointUrl),
                    evidence("failure_detail", "provider-evidence/soap/failure_detail.yaml"),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "SOAP request observation failed: " + safeMessage(e),
                            "Review SOAP mock request journal evidence."));
        }
    }

    private ProviderOperationResult resetMock(ProviderExecutionContext context) {
        try {
            if (server != null && server.isRunning()) {
                server.resetAll();
                server.stop();
            }
            write(context.runDir().resolve("provider-evidence/soap/cleanup.yaml"), """
                    evidence_type: fixture_cleanup
                    evidence_classification: framework_provider_capability_only
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: %s
                    status: passed
                    masking_applied: true
                    """.formatted(context.providerId(), PROVIDER_TYPE));
            return ProviderOperationResult.passed(
                    Map.of("cleanup_log", "provider-evidence/soap/cleanup.yaml"),
                    evidence("cleanup", "provider-evidence/soap/cleanup.yaml"));
        } catch (Exception e) {
            writeFailureDetail(context, "SOAP mock cleanup failed: " + safeMessage(e));
            return ProviderOperationResult.failed(
                    Map.of(),
                    evidence("failure_detail", "provider-evidence/soap/failure_detail.yaml"),
                    ProviderFailure.of(
                            "OPERATION_FAILED",
                            "FRAMEWORK_ERROR",
                            "SOAP mock cleanup failed: " + safeMessage(e),
                            "Review SOAP mock cleanup evidence."));
        }
    }

    private void startServer(ProviderExecutionContext context) {
        if (server != null && server.isRunning()) {
            return;
        }
        String portStrategy = stringValue(context.bindingValues().get("port_strategy"));
        if ("fixed".equals(portStrategy)) {
            int port = intValue(context.bindingValues().getOrDefault("fixed_port", context.bindingValues().get("port")), 0);
            server = new WireMockServer(options().port(port));
        } else {
            server = new WireMockServer(options().dynamicPort());
        }
        server.start();
        endpointUrl = "http://localhost:" + server.port();
    }

    private int countRequests(String journal, String expectedPath, String expectedAction) {
        int count = 0;
        for (Object requestValue : listValue(readMapText(journal).get("requests"))) {
            Map<String, Object> request = mapValue(mapValue(requestValue).get("request"));
            if (!expectedPath.equals(stringValue(request.get("url")))) {
                continue;
            }
            if (!expectedAction.isBlank()
                    && !String.valueOf(request.get("headers")).contains(expectedAction)) {
                continue;
            }
            count++;
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
        write(context.runDir().resolve("provider-evidence/soap/server_log.txt"), maskSensitiveText(content));
    }

    private void writeFailureDetail(ProviderExecutionContext context, String content) {
        String safeContent = maskSensitiveText(content);
        write(context.runDir().resolve("provider-evidence/soap/failure_detail.yaml"), """
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
            throw new UncheckedIOException("Failed to write SOAP mock evidence: " + path, e);
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
}
