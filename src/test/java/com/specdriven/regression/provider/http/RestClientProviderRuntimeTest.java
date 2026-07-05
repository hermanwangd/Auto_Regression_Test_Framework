package com.specdriven.regression.provider.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestClientProviderRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void masksSecretBearingHttpOutputsAndEvidence() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            byte[] body = """
                    {"status":"accepted","access_token":"response-token-123456"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Set-Cookie", "session=server-cookie-123456");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RestClientProviderRuntime runtime = new RestClientProviderRuntime();
            ProviderOperationResult result = runtime.execute(context(server), new ProviderOperationRequest(
                    "http_request",
                    List.of(
                            Map.of("bind_as", "request.method", "ref", "POST"),
                            Map.of("bind_as", "request.path", "ref", "/payments?token=query-token-123456"),
                            Map.of("bind_as", "request.headers.Authorization", "ref", "Bearer request-token-123456"),
                            Map.of("bind_as", "request.body", "ref", "{\"password\":\"body-secret-123456\"}")),
                    Map.of()));

            assertThat(result.passed()).isTrue();
            String outputs = result.outputs().toString();
            String requestEvidence = Files.readString(tempDir.resolve("run/provider-evidence/http/request.json"));
            String responseEvidence = Files.readString(tempDir.resolve("run/provider-evidence/http/response.json"));

            assertThat(outputs)
                    .doesNotContain("query-token-123456")
                    .doesNotContain("request-token-123456")
                    .doesNotContain("body-secret-123456")
                    .doesNotContain("response-token-123456")
                    .doesNotContain("server-cookie-123456")
                    .contains("***MASKED***");
            assertThat(requestEvidence)
                    .doesNotContain("query-token-123456")
                    .doesNotContain("request-token-123456")
                    .doesNotContain("body-secret-123456")
                    .contains("***MASKED***")
                    .contains("\"masking_applied\": true");
            assertThat(responseEvidence)
                    .doesNotContain("response-token-123456")
                    .doesNotContain("server-cookie-123456")
                    .contains("***MASKED***")
                    .contains("\"masking_applied\": true");
        } finally {
            server.stop(0);
        }
    }

    private ProviderExecutionContext context(HttpServer server) {
        return new ProviderExecutionContext(
                "payment-api-client",
                "rest_client",
                "local_test",
                "native",
                tempDir.resolve("suite"),
                tempDir.resolve("run"),
                Map.of(),
                Map.of(),
                Map.of("base_url", "http://127.0.0.1:" + server.getAddress().getPort()));
    }
}
