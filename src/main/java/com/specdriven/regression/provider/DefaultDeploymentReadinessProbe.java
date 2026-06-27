package com.specdriven.regression.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.Yaml;

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
        if ("api_deployment_available".equals(request.readinessProbe())) {
            return checkK8sApiDeploymentAvailable(request);
        }
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
        if ("pod_logs".equals(request.readinessProbe())) {
            return new DeploymentReadinessProbeResult(
                    stdout,
                    stdout,
                    1);
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
        if ("pod_logs".equals(request.readinessProbe())) {
            command.add("logs");
            String podRef = firstText(request.contract(), "pod_ref");
            if (!podRef.isBlank()) {
                command.add(resolveRef(podRef));
            } else if (!request.targetSelector().isBlank()) {
                command.add("-l");
                command.add(request.targetSelector());
            } else {
                throw new IOException("K8s pod_logs readiness probe requires pod_ref or target_selector.");
            }
            command.add("--tail=" + logTailLines(request));
            return command;
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

    private DeploymentReadinessProbeResult checkK8sApiDeploymentAvailable(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        URI uri = URI.create(k8sApiServer(request)
                + "/apis/apps/v1/namespaces/"
                + encodePathSegment(resolveRef(request.namespaceRef()))
                + "/deployments/"
                + encodePathSegment(k8sDeploymentName(request.deploymentRef())));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, request.timeoutSeconds())))
                .GET();
        String bearerRef = firstText(request.contract(), "bearer_ref", "credential_ref");
        if (!bearerRef.isBlank()) {
            builder.header("Authorization", "Bearer " + resolveRef(bearerRef));
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("K8s API deployment readiness failed with status `"
                    + response.statusCode() + "` for `" + request.deploymentRef() + "`.");
        }
        Map<?, ?> deployment = yamlMap(response.body());
        int replicas = Math.max(1, intAt(deployment, "spec", "replicas"));
        int availableReplicas = intAt(deployment, "status", "availableReplicas");
        int updatedReplicas = intAt(deployment, "status", "updatedReplicas");
        if (availableReplicas < replicas) {
            throw new IOException("K8s API deployment readiness failed for `" + request.deploymentRef()
                    + "`: availableReplicas=" + availableReplicas + " replicas=" + replicas + ".");
        }
        return new DeploymentReadinessProbeResult(
                "k8s api deployment available for " + request.deploymentRef()
                        + " availableReplicas=" + availableReplicas
                        + " replicas=" + replicas
                        + " updatedReplicas=" + updatedReplicas + "\n",
                "ready\n",
                1);
    }

    private String k8sApiServer(DeploymentReadinessProbeRequest request) throws IOException {
        String apiServer = firstText(request.contract(), "api_server_ref", "endpoint_ref");
        if (apiServer.isBlank()) {
            throw new IOException("K8s API deployment readiness probe requires api_server_ref.");
        }
        return resolveRef(apiServer).replaceAll("/+$", "");
    }

    private String k8sDeploymentName(String deploymentRef) throws IOException {
        String deploymentName = resolveRef(deploymentRef);
        for (String prefix : List.of("deployment/", "deploy/", "deployments/")) {
            if (deploymentName.startsWith(prefix)) {
                return deploymentName.substring(prefix.length());
            }
        }
        return deploymentName;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<?, ?> yamlMap(String content) throws IOException {
        Object loaded = new Yaml().load(content);
        if (loaded instanceof Map<?, ?> map) {
            return map;
        }
        throw new IOException("K8s API deployment readiness response must be an object.");
    }

    private int intAt(Map<?, ?> root, String section, String field) {
        Object child = root.get(section);
        if (child instanceof Map<?, ?> childMap) {
            Object value = childMap.get(field);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Integer.parseInt(text);
            }
        }
        return 0;
    }

    private int logTailLines(DeploymentReadinessProbeRequest request) throws IOException {
        String value = firstText(request.contract(), "log_tail_lines");
        if (value.isBlank()) {
            throw new IOException("K8s pod_logs readiness probe requires positive log_tail_lines.");
        }
        try {
            int tailLines = Integer.parseInt(value);
            if (tailLines > 0) {
                return tailLines;
            }
        } catch (NumberFormatException e) {
            throw new IOException("K8s pod_logs log_tail_lines must be a positive integer.", e);
        }
        throw new IOException("K8s pod_logs log_tail_lines must be a positive integer.");
    }

    private DeploymentReadinessProbeResult checkVm(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        if ("http_get".equals(request.readinessProbe())) {
            return checkVmHttp(request);
        }
        if ("tcp_connect".equals(request.readinessProbe())) {
            return checkVmTcp(request);
        }
        if ("ssh_command".equals(request.readinessProbe())) {
            return checkVmCommand(request, vmSshCommand(request), "ssh_command");
        }
        if ("winrm_command".equals(request.readinessProbe())) {
            return checkVmCommand(request, vmWinrmCommand(request), "winrm_command");
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

    private DeploymentReadinessProbeResult checkVmCommand(
            DeploymentReadinessProbeRequest request,
            List<String> command,
            String probeName) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        boolean completed = process.waitFor(Math.max(1, request.timeoutSeconds()), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("VM " + probeName + " readiness probe timed out for `"
                    + request.hostRef() + "`.");
        }
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        if (process.exitValue() != 0) {
            throw new IOException("VM " + probeName + " readiness probe failed for `"
                    + request.hostRef() + "`: " + stderr.strip());
        }
        String output = stdout.isBlank() ? "ready\n" : stdout;
        return new DeploymentReadinessProbeResult(output, output, 1);
    }

    private List<String> vmSshCommand(DeploymentReadinessProbeRequest request) throws IOException {
        String executable = firstText(request.contract(), "ssh_ref");
        if (executable.isBlank()) {
            executable = "ssh";
        }
        List<String> command = new ArrayList<>();
        command.add(resolveRef(executable));
        command.add("-o");
        command.add("BatchMode=yes");
        command.add("-o");
        command.add("ConnectTimeout=" + Math.max(1, request.timeoutSeconds()));
        if (request.port() > 0) {
            command.add("-p");
            command.add(Integer.toString(request.port()));
        }
        command.add(vmLoginTarget(request));
        command.add(vmCommandRef(request));
        return command;
    }

    private List<String> vmWinrmCommand(DeploymentReadinessProbeRequest request) throws IOException {
        String executable = firstText(request.contract(), "winrm_ref");
        if (executable.isBlank()) {
            executable = "winrs";
        }
        List<String> command = new ArrayList<>();
        command.add(resolveRef(executable));
        String host = resolveRef(request.hostRef());
        if (request.port() > 0) {
            host = host + ":" + request.port();
        }
        command.add("-r:" + host);
        String user = firstText(request.contract(), "user_ref");
        if (!user.isBlank()) {
            command.add("-u:" + resolveRef(user));
        }
        command.add(vmCommandRef(request));
        return command;
    }

    private String vmLoginTarget(DeploymentReadinessProbeRequest request) throws IOException {
        String host = resolveRef(request.hostRef());
        String user = firstText(request.contract(), "user_ref");
        if (user.isBlank()) {
            return host;
        }
        return resolveRef(user) + "@" + host;
    }

    private String vmCommandRef(DeploymentReadinessProbeRequest request) throws IOException {
        String command = firstText(request.contract(), "command_ref");
        if (command.isBlank()) {
            throw new IOException("VM command readiness probe requires command_ref.");
        }
        return resolveRef(command);
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
