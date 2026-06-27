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

    public RequestResponseProvider() {
        this(HttpClient.newHttpClient());
    }

    public RequestResponseProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
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
        try {
            Files.createDirectories(stdoutLog.getParent());
            Files.createDirectories(stderrLog.getParent());
            Files.createDirectories(actualOutput.getParent());

            Map<?, ?> action = selectedAction(contract, testCase);
            String bindingName = stringValue(action.get("request_binding"));
            String payload = requestPayload(packageRoot, resolvedBindings, bindingName);
            HttpResponse<String> response = httpClient.send(
                    request(contract, action, payload),
                    HttpResponse.BodyHandlers.ofString());

            Files.writeString(stdoutLog, response.body());
            Files.writeString(stderrLog, "");
            Files.writeString(actualOutput, response.body());
            return new AdapterExecutionResult(
                    response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1,
                    false,
                    stdoutLog,
                    stderrLog,
                    actualOutput);
        } catch (HttpTimeoutException e) {
            writeFailure(stdoutLog, stderrLog, actualOutput, e);
            return new AdapterExecutionResult(-1, true, stdoutLog, stderrLog, actualOutput);
        } catch (IOException e) {
            writeFailure(stdoutLog, stderrLog, actualOutput, e);
            return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request/response provider interrupted.", e);
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

    private String firstText(Map<String, Object> map, String... fields) {
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
