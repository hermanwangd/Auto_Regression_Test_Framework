package com.specdriven.regression.provider.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RestClientProviderRuntimeJsonEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RestClientProviderRuntime RUNTIME = new RestClientProviderRuntime();

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final AtomicReference<String> receivedBody = new AtomicReference<>("");
    private final AtomicReference<String> receivedMethod = new AtomicReference<>("");

    @BeforeEach
    void startOwnedLoopbackFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        server.createContext("/evidence", exchange -> {
            try {
                requestCount.incrementAndGet();
                receivedMethod.set(exchange.getRequestMethod());
                receivedBody.set(new String(exchange.getRequestBody().readNBytes(257), StandardCharsets.UTF_8));
                byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopOwnedLoopbackFixture() {
        if (server != null) {
            server.stop(0);
        }
    }

    static Stream<Arguments> responseBodies() {
        List<Arguments> cases = new ArrayList<>(List.of(
                Arguments.of("ascii", "plain response", "plain response"),
                Arguments.of("crlf", "left\r\nright", "left\r\nright"),
                Arguments.of("quote_slash", "quoted \"value\" with \\ slash", "quoted \"value\" with \\ slash"),
                Arguments.of("unicode", "繁體中文 café 😀", "繁體中文 café 😀"),
                Arguments.of("empty", "", ""),
                Arguments.of("masked", "{\"token\":\"fixture-placeholder\",\"note\":\"ok\"}",
                        "{\"token\":\"***MASKED***\",\"note\":\"ok\"}")));
        for (int c = 0; c < 32; c++) {
            String body = "left" + (char) c + "right";
            cases.add(Arguments.of(String.format("c0_%02x", c), body, body));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}: response evidence is strict JSON")
    @MethodSource("responseBodies")
    void preservesResponseBodiesInStrictJsonEvidence(String name, String input, String expected) throws Exception {
        responseBody.set(input);

        ProviderOperationResult result = RUNTIME.execute(context(), request("GET", ""));

        assertThat(result.passed()).as(name + " operation").isTrue();
        assertThat(result.outputs().get("response.body")).isEqualTo(expected);
        assertThat(result.evidence()).hasSize(2);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedBody.get()).isEmpty();
        assertEvidenceBodies("", expected, "GET");
    }

    @Test
    void preservesTabInRequestBodyEvidence() throws Exception {
        responseBody.set("request accepted");

        ProviderOperationResult result = RUNTIME.execute(context(), request("POST", "left\tright"));

        assertThat(result.passed()).isTrue();
        assertThat(result.outputs().get("response.body")).isEqualTo("request accepted");
        assertThat(result.evidence()).hasSize(2);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(receivedMethod.get()).isEqualTo("POST");
        assertThat(receivedBody.get()).isEqualTo("left\tright");
        assertEvidenceBodies("left\tright", "request accepted", "POST");
    }

    @Test
    void preservesTabInReviewedReadEvidence() throws Exception {
        responseBody.set("left\tright");

        ProviderOperationResult result = RUNTIME.executeReviewedRead(context(), request("GET", ""));

        assertThat(result.passed()).isTrue();
        assertThat(result.outputs().get("response.body")).isEqualTo("left\tright");
        assertThat(result.evidence()).hasSize(2);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedBody.get()).isEmpty();
        assertEvidenceBodies("", "left\tright", "GET");
    }

    @Test
    void rejectsUnsupportedOperationWithoutHttpOrEvidence() {
        ProviderOperationResult result = RUNTIME.execute(context(),
                new ProviderOperationRequest("not_supported", request("GET", "").parameters(), Map.of()));

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.failure().code()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(result.evidence()).isEmpty();
        assertThat(requestCount.get()).isZero();
        assertThat(receivedMethod.get()).isEmpty();
        assertThat(runDir().resolve("provider-evidence")).doesNotExist();
    }

    @Test
    void rejectsReviewedPostWithoutHttpOrEvidence() {
        assertThatThrownBy(() -> RUNTIME.executeReviewedRead(context(), request("POST", "")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(requestCount.get()).isZero();
        assertThat(receivedMethod.get()).isEmpty();
        assertThat(runDir().resolve("provider-evidence")).doesNotExist();
    }

    private ProviderExecutionContext context() {
        return new ProviderExecutionContext("rest-fixture", "rest_client", "default", "native",
                tempDir, runDir(), Map.of(), Map.of(),
                Map.of("base_url", "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private Path runDir() {
        return tempDir.resolve("run");
    }

    private ProviderOperationRequest request(String method, String body) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        parameters.add(Map.of("bind_as", "request.method", "ref", method));
        parameters.add(Map.of("bind_as", "request.path", "ref", "/evidence"));
        parameters.add(Map.of("bind_as", "request.timeout", "ref", "PT2S"));
        if (!body.isEmpty()) {
            parameters.add(Map.of("bind_as", "request.body", "ref", body));
        }
        return new ProviderOperationRequest("http_request", parameters, Map.of());
    }

    private void assertEvidenceBodies(String requestBody, String responseBody, String method) throws Exception {
        // Jackson's default parser rejects literal JSON control characters.
        // Do not enable ALLOW_UNESCAPED_CONTROL_CHARS: that would hide the defect.
        Path evidence = runDir().resolve("provider-evidence/http");
        JsonNode request = JSON.readTree(Files.readString(evidence.resolve("request.json")));
        JsonNode response = JSON.readTree(Files.readString(evidence.resolve("response.json")));
        assertThat(request.path("body").isTextual()).isTrue();
        assertThat(request.path("body").asText()).isEqualTo(requestBody);
        assertThat(request.path("method").asText()).isEqualTo(method);
        assertThat(request.path("path").asText()).isEqualTo("/evidence");
        assertThat(request.path("provider_type").asText()).isEqualTo("rest_client");
        assertThat(request.path("masking_applied").asBoolean()).isTrue();
        assertThat(response.path("body").isTextual()).isTrue();
        assertThat(response.path("body").asText()).isEqualTo(responseBody);
        assertThat(response.path("status").asInt()).isEqualTo(200);
        assertThat(response.path("operation_status").asText()).isEqualTo("passed");
        assertThat(response.path("provider_type").asText()).isEqualTo("rest_client");
        assertThat(response.path("masking_applied").asBoolean()).isTrue();
    }
}
