package com.specdriven.regression.provider.wiremock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.provider.ProviderEndpoints;
import com.specdriven.regression.provider.SuiteAssetReader;
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
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.yaml.snakeyaml.Yaml;

public class WireMockHttpMockProviderRuntime implements ProviderRuntime {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> MAPPING_FIELDS = Set.of("request", "response", "id", "uuid", "name",
            "priority", "metadata", "scenarioName", "requiredScenarioState", "newScenarioState");
    private static final Set<String> RESPONSE_FIELDS = Set.of("status", "statusMessage", "headers", "body",
            "jsonBody", "base64Body", "fixedDelayMilliseconds", "delayDistribution", "chunkedDribbleDelay", "fault");
    private final Yaml yaml = ProviderDataYaml.create();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private WireMockServer server;
    private URI origin;
    private Path managedFiles;
    private ArrayNode observedRequests = JSON.createArrayNode();

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        return switch (request.operation()) {
            case "connect_mock" -> connectMock(context);
            case "load_stubs" -> loadStubs(context, request);
            case "send_http_request" -> sendHttpRequest(context, request);
            case "reset_mock" -> resetMock(context);
            default -> ProviderOperationResult.failed(
                    Map.of(),
                    List.of(),
                    ProviderFailure.of(
                            "UNSUPPORTED_OPERATION",
                            "TARGET_RESOLUTION_FAILED",
                            "Unsupported WireMock operation.",
                            "Use an operation declared by the WireMock Provider Contract."));
        };
    }

    private ProviderOperationResult connectMock(ProviderExecutionContext context) {
        try {
            connectOrStartServer(context);
            writeServerLog(context, """
                    evidence_type: wiremock_server_log
                    evidence_classification: %s
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: wiremock_http_mock
                    base_url: %s
                    framework_started_wiremock: %s
                    """.formatted(
                    evidenceClassification(context),
                    context.providerId(),
                    origin.toString(),
                    frameworkStartedWireMock()));
            return ProviderOperationResult.passed(
                    Map.of(
                            "base_url", origin.toString(),
                            "server_log", "provider-evidence/wiremock/server_log.txt",
                            "framework_started_wiremock", frameworkStartedWireMock()),
                    evidence("server_log", "provider-evidence/wiremock/server_log.txt"));
        } catch (Exception e) {
            return failure(context, "PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "WireMock connection failed.",
                    "Check the configured mock mode, Admin API root and operator binding.");
        }
    }

    private ProviderOperationResult loadStubs(ProviderExecutionContext context, ProviderOperationRequest request) {
        String reference = parameterValue(request, "mock.mappings_ref", "");
        String stubJson;
        StubMapping mapping;
        try {
            stubJson = readAsset(context, reference);
            mapping = staticMapping(stubJson, "external".equals(context.runtimeMode()));
        } catch (NoSuchFileException missing) {
            return failure(context, "PROVIDER_STUB_MISSING", "PROVIDER_STUB_MISSING",
                    "WireMock stub mapping is missing.", "Restore the checked-in mapping under the suite root.");
        } catch (IOException | RuntimeException invalid) {
            return failure(context, "OPERATION_FAILED", "FRAMEWORK_ERROR", "WireMock stub mapping is invalid.",
                    "Use a readable static response mapping under the suite root.");
        }
        try {
            connectOrStartServer(context);
            if (Boolean.parseBoolean(parameterValue(request, "mock.reset_before_load", "true"))) {
                resetMappings();
            }
            if (frameworkStartedWireMock()) {
                server.addStubMapping(mapping);
            } else {
                postJson(origin.resolve("/__admin/mappings"), stubJson);
            }
            write(context.runDir().resolve("provider-evidence/wiremock/injected_stubs.yaml"), """
                    evidence_type: injected_stub
                    evidence_classification: %s
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: wiremock_http_mock
                    stub_ref: %s
                    loaded_stub_count: 1
                    base_url: %s
                    framework_started_wiremock: %s
                    """.formatted(
                            evidenceClassification(context),
                            context.providerId(),
                            JSON.writeValueAsString(reference),
                            origin.toString(),
                            frameworkStartedWireMock()));
            writeServerLog(context, """
                    evidence_type: wiremock_server_log
                    evidence_classification: %s
                    downstream_release_evidence: false
                    provider_id: %s
                    provider_type: wiremock_http_mock
                    base_url: %s
                    framework_started_wiremock: %s
                    injected_stub: %s
                    """.formatted(
                    evidenceClassification(context),
                    context.providerId(),
                    origin.toString(),
                    frameworkStartedWireMock(),
                    JSON.writeValueAsString(reference)));
            return ProviderOperationResult.passed(
                    Map.of(
                            "base_url", origin.toString(),
                            "loaded_stub_count", 1,
                            "injected_stub", "provider-evidence/wiremock/injected_stubs.yaml",
                            "server_log", "provider-evidence/wiremock/server_log.txt",
                            "framework_started_wiremock", frameworkStartedWireMock()),
                    evidence(
                            "injected_stub", "provider-evidence/wiremock/injected_stubs.yaml",
                            "server_log", "provider-evidence/wiremock/server_log.txt"));
        } catch (Exception e) {
            return failure(context, "PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "WireMock stub loading failed.",
                    "Check the configured mock port or the owner-provisioned Admin API.");
        }
    }

    private ProviderOperationResult sendHttpRequest(ProviderExecutionContext context, ProviderOperationRequest request) {
        observedRequests = JSON.createArrayNode();
        try {
            if (origin == null) {
                return ProviderOperationResult.failed(
                        Map.of(),
                        List.of(),
                        ProviderFailure.of(
                                "PROVIDER_UNAVAILABLE",
                                "PROVIDER_UNAVAILABLE",
                                "WireMock server is not running.",
                                "Run load_stubs before send_http_request."));
            }
            Map<String, Object> requestInput = readMapText(readAsset(context, parameterValue(request, "http.request_body", "")));
            Map<String, Object> expectedRequest = readMapText(readAsset(context, parameterValue(request, "mock.request_filter", "")));
            String method = parameterValue(request, "http.method", stringValue(requestInput.getOrDefault("method", "POST")));
            String path = parameterValue(request, "http.path", stringValue(requestInput.getOrDefault("path", "/")));
            String body = JSON.writeValueAsString(requestInput.get("body"));
            String requestId = UUID.randomUUID().toString();
            HttpRequest httpRequest = HttpRequest.newBuilder(requestUri(origin, path))
                    .timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .header(WireMockJournal.REQUEST_ID_HEADER, requestId)
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            JsonNode journal = getAdminList(origin.resolve("/__admin/requests"), "requests");
            observedRequests = WireMockJournal.matchedRequests(journal, method, httpRequest.uri(), requestId);
            write(context.runDir().resolve("provider-evidence/wiremock/request_journal.json"),
                    WireMockJournal.evidence(journal, method, httpRequest.uri(), requestId));
            writeServerLog(context, "called " + method + " -> " + response.statusCode() + "\n");
            int matchedCount = countExpectedRequests(expectedRequest);
            return ProviderOperationResult.passed(
                    Map.of(
                            "base_url", origin.toString(),
                            "http_status", response.statusCode(),
                            "response_body", response.body(),
                            "matched_count", matchedCount,
                            "request_journal", "provider-evidence/wiremock/request_journal.json",
                            "server_log", "provider-evidence/wiremock/server_log.txt"),
                    evidence(
                            "request_journal", "provider-evidence/wiremock/request_journal.json",
                            "server_log", "provider-evidence/wiremock/server_log.txt"));
        } catch (ConnectException e) {
            return failure(context, "PROVIDER_UNAVAILABLE", "PROVIDER_UNAVAILABLE", "WireMock is unavailable.",
                    "Check WireMock startup and configured port.");
        } catch (Exception e) {
            return failure(context, "OPERATION_FAILED", "FRAMEWORK_ERROR", "WireMock request failed.",
                    "Check the suite request body, filter and mock request path.");
        }
    }

    private ProviderOperationResult resetMock(ProviderExecutionContext context) {
        observedRequests = JSON.createArrayNode();
        try {
            if (server != null && server.isRunning()) {
                server.stop();
            }
            server = null;
            origin = null;
            removeManagedFiles();
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
                            "WireMock cleanup failed.",
                            "Review cleanup evidence."));
        }
    }

    private void startServer(ProviderExecutionContext context) throws IOException {
        if (frameworkStartedWireMock()) return;
        managedFiles = Files.createTempDirectory("regress-wiremock-");
        try {
            WireMockConfiguration config = managedConfiguration(managedFiles);
            if ("fixed".equals(context.bindingValues().get("port_strategy"))) {
                int port = intValue(firstNonNull(context.bindingValues().get("fixed_port"), context.bindingValues().get("port")), 0);
                config.port(port);
            } else {
                config.dynamicPort();
            }
            server = new WireMockServer(config);
            server.start();
            origin = URI.create("http://localhost:" + server.port());
        } catch (RuntimeException startupFailure) {
            if (server != null) server.stop();
            removeManagedFiles();
            throw startupFailure;
        }
    }

    static WireMockConfiguration managedConfiguration(Path filesRoot) {
        return ManagedWireMock.loopbackOptions()
                .usingFilesUnderDirectory(filesRoot.toString())
                .templatingEnabled(false)
                .adminAuthenticator(request -> RequestMethod.GET.equals(request.getMethod())
                        && "/__admin/requests".equals(request.getTypedAbsoluteUrl().getPath().toString()));
    }

    private void removeManagedFiles() throws IOException {
        if (managedFiles != null) {
            Files.deleteIfExists(managedFiles.resolve("__files"));
            Files.deleteIfExists(managedFiles.resolve("mappings"));
            Files.deleteIfExists(managedFiles);
            managedFiles = null;
        }
    }

    private void connectOrStartServer(ProviderExecutionContext context) throws IOException, InterruptedException, URISyntaxException {
        String baseUrl = stringValue(context.bindingValues().get("base_url"));
        if ("external".equals(context.runtimeMode())) {
            if (!"wiremock_http_mock".equals(context.providerType()) || frameworkStartedWireMock()) {
                throw new IllegalArgumentException("External WireMock mode requires a separate legacy target.");
            }
            origin = null;
            URI candidate = externalOrigin(baseUrl, System.getenv("REGRESS_WIREMOCK_BASE_URL"));
            getAdminList(candidate.resolve("/__admin/mappings"), "mappings");
            origin = candidate;
        } else if ("mock".equals(context.runtimeMode()) && baseUrl.isBlank()) {
            startServer(context);
        } else {
            throw new IllegalArgumentException("Invalid WireMock runtime mode or base URL binding.");
        }
    }

    static URI externalOrigin(String baseUrl, String operatorUrl) throws URISyntaxException {
        URI requested = httpRoot(baseUrl);
        if (ProviderEndpoints.localHost(requested.getHost()) || "localhost".equalsIgnoreCase(requested.getHost())) {
            return requested;
        }
        if (operatorUrl != null) {
            URI allowed = httpRoot(operatorUrl);
            if (allowed.equals(requested)) return allowed;
        }
        throw new IllegalArgumentException("The external WireMock origin needs an operator binding.");
    }

    private static URI httpRoot(String value) throws URISyntaxException {
        URI root = new URI(value);
        if (!("http".equals(root.getScheme()) || "https".equals(root.getScheme())) || root.getHost() == null
                || root.getRawUserInfo() != null || root.getRawQuery() != null || root.getRawFragment() != null
                || root.getPort() == 0 || root.getPort() > 65535 || root.getRawAuthority().endsWith(":")
                || !(root.getRawPath().isEmpty() || "/".equals(root.getRawPath()))) {
            throw new IllegalArgumentException("Expected a credential-free HTTP(S) origin.");
        }
        return new URI(root.getScheme(), null, root.getHost(), root.getPort(), null, null, null);
    }

    /** Resolves direct and paired client requests within the authorized mock application origin. */
    public static URI requestUri(URI origin, String path) throws URISyntaxException {
        URI reference = new URI(path);
        String decoded = reference.getPath();
        if (!path.startsWith("/") || path.startsWith("//") || reference.isAbsolute()
                || reference.getRawAuthority() != null || reference.getRawFragment() != null || decoded == null
                || decoded.contains("//") || decoded.contains("\\") || decoded.contains("%") || decoded.contains(";")
                || decoded.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(decoded.split("/")).anyMatch(part -> ".".equals(part) || "..".equals(part))
                || decoded.equals("/__admin") || decoded.startsWith("/__admin/")) {
            throw new IllegalArgumentException("Expected an application path on the configured WireMock origin.");
        }
        return origin.resolve(reference);
    }

    private void resetMappings() throws IOException, InterruptedException {
        if (frameworkStartedWireMock()) {
            server.resetAll();
        } else {
            postJson(origin.resolve("/__admin/reset"), "{}");
        }
    }

    static StubMapping staticMapping(String content, boolean external) throws JsonProcessingException {
        JsonNode mapping = JSON.readTree(content);
        if (!onlyFields(mapping, MAPPING_FIELDS) || !mapping.path("request").isObject()
                || mapping.path("request").has("customMatcher") || !staticResponse(mapping.path("response"))) {
            throw new IllegalArgumentException("Expected a static WireMock request and response.");
        }
        if (external) {
            // Serialize the parsed tree so JSON escapes are decoded in both field names and values.
            JsonNode base64Body = mapping.path("response").path("base64Body");
            if (mapping.toString().contains("{{") || (base64Body.isTextual()
                    && new String(Base64.getDecoder().decode(base64Body.textValue()), StandardCharsets.ISO_8859_1)
                            .contains("{{"))) {
                throw new IllegalArgumentException("External WireMock mappings must contain literal response data.");
            }
        }
        // Use WireMock's own decoder before resetting an existing mock.
        return Json.read(content, StubMapping.class);
    }

    private static boolean staticResponse(JsonNode response) {
        // WireMock 4 also accepts structured entity definitions in "body", including file references.
        return onlyFields(response, RESPONSE_FIELDS)
                && (!response.has("body") || response.path("body").isTextual())
                && (!response.has("base64Body") || response.path("base64Body").isTextual());
    }

    private static boolean onlyFields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()) return false;
        for (var names = object.fieldNames(); names.hasNext();) {
            if (!allowed.contains(names.next())) return false;
        }
        return true;
    }

    private String readAsset(ProviderExecutionContext context, String reference) throws IOException {
        return SuiteAssetReader.read(context.suiteRoot(), reference, "WireMock fixture must be inside the suite root.");
    }

    private boolean frameworkStartedWireMock() {
        return server != null && server.isRunning();
    }

    private int countExpectedRequests(Map<String, Object> expected) {
        int count = 0;
        for (JsonNode request : observedRequests) {
            if (matchesMethodAndPath(request, expected)) count++;
        }
        return count;
    }

    /** Compare the correlated request before cleanup. The evidence file omits bodies, headers and query values. */
    public boolean observedRequestBodyMatches(Path suiteRoot, String expectedRef) {
        try {
            String text = SuiteAssetReader.read(suiteRoot, expectedRef, "Expected request must be inside the suite root.");
            Map<String, Object> expected = readMapText(text);
            if (!expected.containsKey("body")) return false;
            JsonNode expectedBody = JSON.valueToTree(expected.get("body"));
            for (JsonNode request : observedRequests) {
                if (matchesMethodAndPath(request, expected) && request.path("body").isTextual()
                        && expectedBody.equals(JSON.readTree(request.path("body").textValue()))) return true;
            }
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
        return false;
    }

    private boolean matchesMethodAndPath(JsonNode request, Map<String, Object> expected) {
        return stringValue(expected.get("path")).equals(request.path("url").textValue())
                && stringValue(expected.get("method")).equals(request.path("method").textValue());
    }

    private String parameterValue(ProviderOperationRequest request, String bindAs, String fallback) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(parameter.get("bind_as"))) {
                return stringValue(parameter.get("ref"));
            }
        }
        return fallback;
    }

    private void postJson(URI url, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        requireAdminSuccess(response.statusCode());
    }

    private JsonNode getAdminList(URI url, String field) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireAdminSuccess(response.statusCode());
        JsonNode document = JSON.readTree(response.body());
        if (document == null || !document.path(field).isArray()) {
            throw new IOException("WireMock Admin API returned an invalid list.");
        }
        return document;
    }

    private void requireAdminSuccess(int status) throws IOException {
        if (status < 200 || status >= 300) throw new IOException("WireMock Admin API request failed.");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMapText(String text) {
        Object loaded = yaml.load(text);
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected a WireMock fixture object.");
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
            Files.writeString(log, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

    private ProviderOperationResult failure(ProviderExecutionContext context, String code, String classification,
            String detail, String ownerAction) {
        writeFailureDetail(context, detail);
        return ProviderOperationResult.failed(Map.of(),
                evidence("failure_detail", "provider-evidence/wiremock/failure_detail.yaml"),
                ProviderFailure.of(code, classification, detail, ownerAction));
    }
}
