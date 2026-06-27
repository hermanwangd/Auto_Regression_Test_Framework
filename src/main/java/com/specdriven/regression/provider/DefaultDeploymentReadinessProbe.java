package com.specdriven.regression.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class DefaultDeploymentReadinessProbe implements DeploymentReadinessProbe {

    private final HttpClient httpClient;

    DefaultDeploymentReadinessProbe() {
        this(HttpClient.newHttpClient());
    }

    DefaultDeploymentReadinessProbe(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public DeploymentReadinessProbeResult check(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        return switch (request.providerType().toLowerCase(Locale.ROOT)) {
            case "k8s" -> checkK8s(request);
            case "vm" -> checkVm(request);
            default -> throw new IOException("Unsupported deployment readiness provider_type `"
                    + request.providerType() + "` for native readiness execution.");
        };
    }

    private DeploymentReadinessProbeResult checkK8s(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        List<String> command = k8sCommand(request);
        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();
        boolean completed = process.waitFor(Math.max(1, request.timeoutSeconds()), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("K8s readiness probe timed out for `" + request.deploymentRef() + "`.");
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        if (process.exitValue() != 0) {
            throw new IOException("K8s readiness probe failed for `" + request.deploymentRef()
                    + "`: " + stderr.strip());
        }
        return new DeploymentReadinessProbeResult(
                stdout.isBlank() ? "k8s readiness passed for " + request.deploymentRef() + "\n" : stdout,
                "ready\n",
                1);
    }

    private List<String> k8sCommand(DeploymentReadinessProbeRequest request) throws IOException {
        String executable = stringValue(request.contract().get("kubectl_ref"));
        if (executable.isBlank()) {
            executable = "kubectl";
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        String context = resolveRef(request.kubeContextRef());
        if (!context.isBlank()) {
            command.add("--context");
            command.add(context);
        }
        if (!request.namespaceRef().isBlank()) {
            command.add("-n");
            command.add(resolveRef(request.namespaceRef()));
        }
        if ("pod_ready".equals(request.readinessProbe())) {
            command.add("wait");
            command.add("--for=condition=Ready");
            command.add("pod");
            command.add("-l");
            command.add(request.targetSelector());
            command.add("--timeout=" + Math.max(1, request.timeoutSeconds()) + "s");
            return command;
        }
        command.add("rollout");
        command.add("status");
        command.add(request.deploymentRef());
        command.add("--timeout=" + Math.max(1, request.timeoutSeconds()) + "s");
        return command;
    }

    private DeploymentReadinessProbeResult checkVm(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        if ("http_get".equals(request.readinessProbe())) {
            return checkVmHttp(request);
        }
        if ("tcp_connect".equals(request.readinessProbe())) {
            return checkVmTcp(request);
        }
        throw new IOException("Unsupported VM readiness_probe `" + request.readinessProbe() + "`.");
    }

    private DeploymentReadinessProbeResult checkVmTcp(DeploymentReadinessProbeRequest request)
            throws IOException {
        int timeoutMs = Math.max(1, request.timeoutSeconds()) * 1000;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(resolveRef(request.hostRef()), request.port()), timeoutMs);
        }
        return new DeploymentReadinessProbeResult(
                "vm tcp readiness passed for " + request.hostRef() + ":" + request.port() + "\n",
                "ready\n",
                1);
    }

    private DeploymentReadinessProbeResult checkVmHttp(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        URI uri = URI.create(resolveRef(firstText(request.contract(), "health_url_ref", "endpoint_ref")));
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, request.timeoutSeconds())))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("VM HTTP readiness failed with status `" + response.statusCode() + "`.");
        }
        return new DeploymentReadinessProbeResult(
                "vm http readiness passed for " + uri + "\n",
                response.body().isBlank() ? "ready\n" : response.body(),
                1);
    }

    private String resolveRef(String ref) throws IOException {
        if (ref.startsWith("env://")) {
            String envName = ref.substring("env://".length());
            String value = System.getenv(envName);
            if (value == null || value.isBlank()) {
                throw new IOException("Environment variable `" + envName
                        + "` is not set for deployment readiness.");
            }
            return value;
        }
        return ref;
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
