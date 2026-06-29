package com.specdriven.regression.provider;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RequestResponseProvider {

    private final HttpClient httpClient;
    private final GrpcClientInvoker grpcClientInvoker;

    public RequestResponseProvider() {
        this(HttpClient.newHttpClient(), new DefaultGrpcClientInvoker());
    }

    public RequestResponseProvider(HttpClient httpClient) {
        this(httpClient, new DefaultGrpcClientInvoker());
    }

    RequestResponseProvider(HttpClient httpClient, GrpcClientInvoker grpcClientInvoker) {
        this.httpClient = httpClient;
        this.grpcClientInvoker = grpcClientInvoker;
    }

    public AdapterExecutionResult execute(
            Path packageRoot,
            Map<String, Object> contract,
            Map<String, Object> testCase,
            List<ResolvedBinding> resolvedBindings,
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput) {
        Map<?, ?> action = Map.of();
        try {
            Files.createDirectories(stdoutLog.getParent());
            Files.createDirectories(stderrLog.getParent());
            Files.createDirectories(actualOutput.getParent());

            action = selectedAction(contract, testCase);
            if (isMockEndpoint(contract)) {
                return executeMock(contract, action, stdoutLog, stderrLog, actualOutput, runDir);
            }
            if ("grpc".equalsIgnoreCase(stringValue(contract.get("provider_type")))) {
                return executeGrpc(
                        packageRoot,
                        contract,
                        action,
                        resolvedBindings,
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput);
            }
            String bindingName = stringValue(action.get("request_binding"));
            String payload = requestPayload(packageRoot, resolvedBindings, bindingName);
            HttpResponse<String> response = httpClient.send(
                    request(contract, action, payload),
                    HttpResponse.BodyHandlers.ofString());

            Files.writeString(stdoutLog, response.body());
            Files.writeString(stderrLog, "");
            Files.writeString(actualOutput, response.body());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            writeRequestResponseEvidence(
                    runDir,
                    contract,
                    action,
                    success ? "passed" : "failed",
                    false,
                    "",
                    actualOutput,
                    response.statusCode());
            return new AdapterExecutionResult(
                    success ? 0 : 1,
                    false,
                    stdoutLog,
                    stderrLog,
                    actualOutput);
        } catch (HttpTimeoutException e) {
            writeFailure(stdoutLog, stderrLog, actualOutput, e);
            writeRequestResponseEvidence(runDir, contract, action, "failed", true, e.getMessage(), actualOutput, null);
            return new AdapterExecutionResult(-1, true, stdoutLog, stderrLog, actualOutput);
        } catch (IOException e) {
            writeFailure(stdoutLog, stderrLog, actualOutput, e);
            writeRequestResponseEvidence(runDir, contract, action, "failed", false, e.getMessage(), actualOutput, null);
            return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request/response provider interrupted.", e);
        }
    }

    private AdapterExecutionResult executeMock(
            Map<String, Object> contract,
            Map<?, ?> action,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput,
            Path runDir) throws IOException {
        String response = firstText(action, "mock_response", "response_body", "sample_response");
        if (response.isBlank()) {
            response = firstText(contract, "mock_response", "response_body", "sample_response");
        }
        int statusCode = intValue(firstText(action, "mock_status", "http_status", "status_code"),
                intValue(firstText(contract, "mock_status", "http_status", "status_code"), 200));
        Files.writeString(stdoutLog, response);
        Files.writeString(stderrLog, "");
        Files.writeString(actualOutput, response);
        boolean success = statusCode >= 200 && statusCode < 300;
        writeRequestResponseEvidence(
                runDir,
                contract,
                action,
                success ? "passed" : "failed",
                false,
                "",
                actualOutput,
                "grpc".equalsIgnoreCase(stringValue(contract.get("provider_type"))) ? null : statusCode);
        return new AdapterExecutionResult(success ? 0 : 1, false, stdoutLog, stderrLog, actualOutput);
    }

    private boolean isMockEndpoint(Map<String, Object> contract) {
        return firstText(contract, "endpoint_ref", "base_url_ref", "service_ref").startsWith("mock://");
    }

    private AdapterExecutionResult executeGrpc(
            Path packageRoot,
            Map<String, Object> contract,
            Map<?, ?> action,
            List<ResolvedBinding> resolvedBindings,
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput) throws InterruptedException {
        String bindingName = stringValue(action.get("request_binding"));
        try {
            GrpcClientResult response = grpcClientInvoker.invoke(new GrpcClientRequest(
                    firstText(contract, "endpoint_ref", "base_url_ref", "service_ref"),
                    packageRoot.resolve(stringValue(contract.get("descriptor_ref"))).normalize(),
                    firstText(action, "service", "service_ref"),
                    stringValue(action.get("method")),
                    requestPayload(packageRoot, resolvedBindings, bindingName),
                    intValue(contract.get("timeout_seconds"), 300),
                    booleanValue(contract.get("plaintext"), true),
                    firstText(contract, "authority_ref", "authority")));
            Files.writeString(stdoutLog, response.responseBody());
            Files.writeString(stderrLog, "");
            Files.writeString(actualOutput, response.responseBody());
            writeRequestResponseEvidence(runDir, contract, action, "passed", false, "", actualOutput, null);
            return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
        } catch (GrpcClientException e) {
            writeFailure(stdoutLog, stderrLog, actualOutput, e);
            writeRequestResponseEvidence(runDir, contract, action, "failed", e.timeout(), e.getMessage(), actualOutput, null);
            return new AdapterExecutionResult(e.timeout() ? -1 : 1, e.timeout(), stdoutLog, stderrLog, actualOutput);
        } catch (IOException e) {
            writeFailure(stdoutLog, stderrLog, actualOutput, e);
            writeRequestResponseEvidence(runDir, contract, action, "failed", false, e.getMessage(), actualOutput, null);
            return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
        }
    }

    private HttpRequest request(Map<String, Object> contract, Map<?, ?> action, String payload) {
        String method = stringValue(action.get("method"));
        if (method.isBlank()) {
            method = "POST";
        }
        return HttpRequest.newBuilder(uri(contract, action))
                .timeout(Duration.ofSeconds(intValue(contract.get("timeout_seconds"), 300)))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private URI uri(Map<String, Object> contract, Map<?, ?> action) {
        String endpoint = firstText(contract, "endpoint_ref", "base_url_ref", "service_ref");
        String path = stringValue(action.get("path"));
        if (endpoint.endsWith("/") && path.startsWith("/")) {
            return URI.create(endpoint + path.substring(1));
        }
        if (!endpoint.endsWith("/") && !path.startsWith("/")) {
            return URI.create(endpoint + "/" + path);
        }
        return URI.create(endpoint + path);
    }

    private Map<?, ?> selectedAction(Map<String, Object> contract, Map<String, Object> testCase) {
        Object actions = contract.get("actions");
        if (!(actions instanceof Map<?, ?> actionMap) || actionMap.isEmpty()) {
            return Map.of();
        }
        String requestedAction = firstStepAction(testCase);
        if (!requestedAction.isBlank() && actionMap.get(requestedAction) instanceof Map<?, ?> action) {
            return action;
        }
        Object firstAction = actionMap.values().iterator().next();
        return firstAction instanceof Map<?, ?> action ? action : Map.of();
    }

    private String firstStepAction(Map<String, Object> testCase) {
        Object steps = testCase.get("steps");
        if (steps instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> step) {
            return stringValue(step.get("action"));
        }
        return "";
    }

    private String requestPayload(Path packageRoot, List<ResolvedBinding> resolvedBindings, String bindingName)
            throws IOException {
        for (ResolvedBinding binding : resolvedBindings) {
            if (binding.bindingName().equals(bindingName)) {
                return Files.readString(packageRoot.resolve(binding.ref()));
            }
        }
        return "";
    }

    private void writeFailure(Path stdoutLog, Path stderrLog, Path actualOutput, Exception e) {
        try {
            Files.createDirectories(stdoutLog.getParent());
            Files.createDirectories(stderrLog.getParent());
            Files.createDirectories(actualOutput.getParent());
            Files.writeString(stdoutLog, "");
            Files.writeString(stderrLog, e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            Files.writeString(actualOutput, "");
        } catch (IOException ioException) {
            throw new UncheckedIOException("Failed to write request/response failure evidence.", ioException);
        }
    }

    private void writeRequestResponseEvidence(
            Path runDir,
            Map<String, Object> contract,
            Map<?, ?> action,
            String status,
            boolean timeout,
            String error,
            Path actualOutput,
            Integer httpStatus) {
        try {
            Files.createDirectories(runDir);
            StringBuilder builder = new StringBuilder();
            builder.append("provider_family: request_response\n");
            builder.append("provider_type: ").append(stringValue(contract.get("provider_type"))).append("\n");
            builder.append("status: ").append(status).append("\n");
            builder.append("timeout: ").append(timeout).append("\n");
            if (httpStatus != null) {
                builder.append("http_status: ").append(httpStatus).append("\n");
            }
            builder.append("endpoint_ref: ")
                    .append(firstText(contract, "endpoint_ref", "base_url_ref", "service_ref"))
                    .append("\n");
            builder.append("descriptor_ref: ").append(stringValue(contract.get("descriptor_ref"))).append("\n");
            builder.append("service: ").append(firstText(action, "service", "service_ref")).append("\n");
            builder.append("method: ").append(stringValue(action.get("method"))).append("\n");
            builder.append("request_binding: ").append(stringValue(action.get("request_binding"))).append("\n");
            builder.append("actual_output: ").append(runDir.relativize(actualOutput)).append("\n");
            if (error != null && !error.isBlank()) {
                builder.append("error: ").append(error.replace('\n', ' ').replace('\r', ' ')).append("\n");
            }
            Files.writeString(runDir.resolve("request_response.yaml"), builder.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write request/response provider evidence.", e);
        }
    }

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(text);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
