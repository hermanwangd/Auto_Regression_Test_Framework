package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
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
            assertThat(Files.readString(tempDir.resolve("run/request_response.yaml")))
                    .contains("provider_family: request_response")
                    .contains("status: failed")
                    .contains("http_status: 409")
                    .contains("actual_output: actual/response.json");
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

    @Test
    void usesFallbackActionAlternateEndpointRefAndStringTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PATCH");
            assertThat(new String(exchange.getRequestBody().readAllBytes()))
                    .isEqualTo("{\"paymentId\":\"P-002\"}");
            byte[] response = "{\"status\":\"updated\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            writePayload("fixtures/payment.json", "{\"paymentId\":\"P-002\"}");
            AdapterExecutionResult result = executeProvider(
                    Map.of(
                            "base_url_ref", endpoint(server) + "/",
                            "timeout_seconds", "5",
                            "actions", Map.of(
                                    "fallback_submit", Map.of(
                                            "method", "PATCH",
                                            "path", "/payments",
                                            "request_binding", "payment_payload"))),
                    List.of(new ResolvedBinding("payment_payload", "api_payload", "fixtures/payment.json")),
                    Map.of("steps", List.of(Map.of("action", "not_declared"))));

            assertThat(result.exitCode()).isZero();
            assertThat(result.timeout()).isFalse();
            assertThat(Files.readString(result.stdoutLog())).isEqualTo("{\"status\":\"updated\"}");
            assertThat(Files.readString(result.stderrLog())).isEmpty();
            assertThat(Files.readString(result.actualOutput())).isEqualTo("{\"status\":\"updated\"}");
            assertThat(Files.readString(tempDir.resolve("run/request_response.yaml")))
                    .contains("provider_family: request_response")
                    .contains("status: passed")
                    .contains("http_status: 200")
                    .contains("method: PATCH")
                    .contains("actual_output: actual/response.json");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writesTimeoutEvidenceWhenHttpClientTimesOut() throws Exception {
        RequestResponseProvider provider = new RequestResponseProvider(new ThrowingHttpClient(
                new HttpTimeoutException("request timed out")));
        Path runDir = tempDir.resolve("run-timeout");

        AdapterExecutionResult result = provider.execute(
                tempDir,
                Map.of(
                        "endpoint_ref", "http://127.0.0.1:8080",
                        "actions", Map.of("submit_payment", Map.of("path", "/payments"))),
                Map.of("steps", List.of(Map.of("action", "submit_payment"))),
                List.of(),
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/response.json"));

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timeout()).isTrue();
        assertThat(Files.readString(result.stdoutLog())).isEmpty();
        assertThat(Files.readString(result.actualOutput())).isEmpty();
        assertThat(Files.readString(result.stderrLog())).contains("request timed out");
    }

    @Test
    void reinterruptsThreadWhenHttpClientIsInterrupted() {
        RequestResponseProvider provider = new RequestResponseProvider(new ThrowingHttpClient(
                new InterruptedException("interrupted")));
        Path runDir = tempDir.resolve("run-interrupted");

        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.execute(
                            tempDir,
                            Map.of(
                                    "endpoint_ref", "http://127.0.0.1:8080",
                                    "actions", Map.of("submit_payment", Map.of("path", "/payments"))),
                            Map.of("steps", List.of(Map.of("action", "submit_payment"))),
                            List.of(),
                            runDir,
                            runDir.resolve("logs/stdout.log"),
                            runDir.resolve("logs/stderr.log"),
                            runDir.resolve("actual/response.json")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Request/response provider interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invokesGrpcUnaryActionThroughNativeInvokerAndWritesEvidence() throws Exception {
        writePayload("fixtures/payment.json", "{\"paymentId\":\"P-003\"}");
        CapturingGrpcClientInvoker invoker = new CapturingGrpcClientInvoker(
                new GrpcClientResult("{\"status\":\"approved\"}"));
        RequestResponseProvider provider = new RequestResponseProvider(HttpClient.newHttpClient(), invoker);
        Path runDir = tempDir.resolve("run-grpc");

        AdapterExecutionResult result = provider.execute(
                tempDir,
                Map.of(
                        "provider_type", "grpc",
                        "service_ref", "127.0.0.1:9090",
                        "descriptor_ref", "descriptors/payment.desc",
                        "timeout_seconds", 5,
                        "outputs", Map.of("actual_output_ref", "actual/grpc-response.json"),
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "service", "payment.PaymentService",
                                        "method", "SubmitPayment",
                                        "request_binding", "payment_payload"))),
                Map.of("steps", List.of(Map.of("action", "submit_payment"))),
                List.of(new ResolvedBinding("payment_payload", "api_payload", "fixtures/payment.json")),
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/grpc-response.json"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.timeout()).isFalse();
        assertThat(invoker.request().endpoint()).isEqualTo("127.0.0.1:9090");
        assertThat(invoker.request().descriptorPath()).isEqualTo(tempDir.resolve("descriptors/payment.desc"));
        assertThat(invoker.request().service()).isEqualTo("payment.PaymentService");
        assertThat(invoker.request().method()).isEqualTo("SubmitPayment");
        assertThat(invoker.request().payloadJson()).isEqualTo("{\"paymentId\":\"P-003\"}");
        assertThat(invoker.request().timeoutSeconds()).isEqualTo(5);
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("{\"status\":\"approved\"}");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("{\"status\":\"approved\"}");
        assertThat(Files.readString(result.stderrLog())).isEmpty();
        assertThat(Files.readString(runDir.resolve("request_response.yaml")))
                .contains("provider_type: grpc")
                .contains("status: passed")
                .contains("service: payment.PaymentService")
                .contains("method: SubmitPayment")
                .contains("descriptor_ref: descriptors/payment.desc")
                .contains("actual_output: actual/grpc-response.json");
    }

    @Test
    void writesFailedGrpcEvidenceWhenNativeInvokerFails() throws Exception {
        writePayload("fixtures/payment.json", "{\"paymentId\":\"P-004\"}");
        RequestResponseProvider provider = new RequestResponseProvider(
                HttpClient.newHttpClient(),
                request -> {
                    throw new IOException("gRPC service unavailable");
                });
        Path runDir = tempDir.resolve("run-grpc-failure");

        AdapterExecutionResult result = provider.execute(
                tempDir,
                Map.of(
                        "provider_type", "grpc",
                        "service_ref", "127.0.0.1:9090",
                        "descriptor_ref", "descriptors/payment.desc",
                        "timeout_seconds", 5,
                        "outputs", Map.of("actual_output_ref", "actual/grpc-response.json"),
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "service", "payment.PaymentService",
                                        "method", "SubmitPayment",
                                        "request_binding", "payment_payload"))),
                Map.of("steps", List.of(Map.of("action", "submit_payment"))),
                List.of(new ResolvedBinding("payment_payload", "api_payload", "fixtures/payment.json")),
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/grpc-response.json"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.timeout()).isFalse();
        assertThat(Files.readString(result.stdoutLog())).isEmpty();
        assertThat(Files.readString(result.actualOutput())).isEmpty();
        assertThat(Files.readString(result.stderrLog())).contains("gRPC service unavailable");
        assertThat(Files.readString(runDir.resolve("request_response.yaml")))
                .contains("provider_type: grpc")
                .contains("status: failed")
                .contains("error: gRPC service unavailable");
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

    private static class ThrowingHttpClient extends HttpClient {

        private final Exception exception;

        ThrowingHttpClient(Exception exception) {
            this.exception = exception;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception e) {
                throw new IllegalStateException("Default SSL context unavailable.", e);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            if (exception instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            throw new IllegalStateException(exception);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(exception);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static class CapturingGrpcClientInvoker implements GrpcClientInvoker {

        private final GrpcClientResult result;
        private GrpcClientRequest request;

        CapturingGrpcClientInvoker(GrpcClientResult result) {
            this.result = result;
        }

        @Override
        public GrpcClientResult invoke(GrpcClientRequest request) {
            this.request = request;
            return result;
        }

        GrpcClientRequest request() {
            return request;
        }
    }
}
