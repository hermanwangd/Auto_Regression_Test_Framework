package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestResponseProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void writesNonSuccessHttpResponseAsFailedAdapterResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            byte[] response = "{\"error\":\"duplicate\"}".getBytes();
            exchange.sendResponseHeaders(409, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            writePayload("fixtures/payment.json", "{\"paymentId\":\"P-001\"}");
            AdapterExecutionResult result = executeProvider(
                    Map.of(
                            "endpoint_ref", endpoint(server),
                            "timeout_seconds", 5,
                            "actions", Map.of(
                                    "submit_payment", Map.of(
                                            "path", "/payments",
                                            "request_binding", "payment_payload"))),
                    List.of(new ResolvedBinding("payment_payload", "api_payload", "fixtures/payment.json")),
                    Map.of("steps", List.of(Map.of("action", "submit_payment"))));

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(result.timeout()).isFalse();
            assertThat(Files.readString(result.stdoutLog())).isEqualTo("{\"error\":\"duplicate\"}");
            assertThat(Files.readString(result.actualOutput())).isEqualTo("{\"error\":\"duplicate\"}");
            assertThat(Files.readString(result.stderrLog())).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writesFailureEvidenceWhenHttpRequestCannotConnect() throws Exception {
        int unusedPort = unusedLocalPort();

        AdapterExecutionResult result = executeProvider(
                Map.of(
                        "endpoint_ref", "http://127.0.0.1:" + unusedPort,
                        "timeout_seconds", 1,
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "method", "PUT",
                                        "path", "payments",
                                        "request_binding", "missing_payload"))),
                List.of(),
                Map.of("steps", List.of(Map.of("action", "submit_payment"))));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.timeout()).isFalse();
        assertThat(Files.readString(result.stdoutLog())).isEmpty();
        assertThat(Files.readString(result.actualOutput())).isEmpty();
        assertThat(Files.readString(result.stderrLog())).isNotBlank();
    }

    private AdapterExecutionResult executeProvider(
            Map<String, Object> contract,
            List<ResolvedBinding> resolvedBindings,
            Map<String, Object> testCase) {
        Path runDir = tempDir.resolve("run");
        return new RequestResponseProvider().execute(
                tempDir,
                contract,
                testCase,
                resolvedBindings,
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/response.json"));
    }

    private void writePayload(String relativePath, String content) throws IOException {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private int unusedLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
