package com.specdriven.regression.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.yaml.snakeyaml.Yaml;

class DefaultDeploymentReadinessProbe implements DeploymentReadinessProbe {

    private final HttpClient httpClient;
    private final Function<String, String> environment;
    private final Function<ReadinessTool, ProcessBuilder> tools;

    DefaultDeploymentReadinessProbe() {
        this(HttpClient.newHttpClient());
    }

    DefaultDeploymentReadinessProbe(HttpClient httpClient) {
        this(httpClient, System::getenv);
    }

    DefaultDeploymentReadinessProbe(HttpClient httpClient, Function<String, String> environment) {
        this(httpClient, environment, DefaultDeploymentReadinessProbe::systemTool);
    }

    DefaultDeploymentReadinessProbe(HttpClient httpClient, Function<String, String> environment,
            Function<ReadinessTool, ProcessBuilder> tools) {
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Readiness requests require a client with redirects disabled.");
        }
        this.httpClient = httpClient;
        this.environment = environment;
        this.tools = tools;
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
        requireTool(request, "kubectl_ref", ReadinessTool.KUBECTL);
        ReadinessCommand command = new ReadinessCommand(ReadinessTool.KUBECTL, k8sArguments(request));
        String stdout = runTool(command, request.timeoutSeconds(),
                "K8s readiness probe", request.deploymentRef());
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

    private List<String> k8sArguments(DeploymentReadinessProbeRequest request) throws IOException {
        List<String> command = new ArrayList<>();
        String context = environment.apply("K8S_CONTEXT");
        if (!request.kubeContextRef().isEmpty()
                && (context == null || context.isBlank()
                || !(request.kubeContextRef().equals("env://K8S_CONTEXT") || request.kubeContextRef().equals(context)))) {
            throw new IOException("K8s CLI context must match the runner's K8S_CONTEXT.");
        }
        if (context != null && !context.isBlank()) {
            command.add("--context=" + context);
        }
        if (!request.namespaceRef().isBlank()) {
            command.add("-n");
            command.add(K8sApiResource.NAMESPACE.resolve(request.namespaceRef(), environment));
        }
        if ("pod_logs".equals(request.readinessProbe())) {
            command.add("logs");
            String podRef = firstText(request.contract(), "pod_ref");
            if (!podRef.isBlank()) {
                command.add(K8sApiResource.POD.resolve(podRef, environment));
            } else if (!request.targetSelector().isBlank()) {
                command.add("-l");
                command.add(labelSelector(request.targetSelector()));
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
            command.add(labelSelector(request.targetSelector()));
            command.add("--timeout=" + Math.max(1, request.timeoutSeconds()) + "s");
            return command;
        }
        if (!"rollout_status".equals(request.readinessProbe())) {
            throw new IOException("Unsupported Kubernetes CLI readiness probe.");
        }
        command.add("rollout");
        command.add("status");
        command.add("deployment/" + K8sApiResource.DEPLOYMENT.resolve(request.deploymentRef(), environment));
        command.add("--timeout=" + Math.max(1, request.timeoutSeconds()) + "s");
        return command;
    }

    private String labelSelector(String selector) throws IOException {
        if (!selector.matches("[A-Za-z0-9!][A-Za-z0-9_. /(),!=<>-]{0,1023}")) {
            throw new IOException("K8s readiness requires a label selector.");
        }
        return selector;
    }

    private DeploymentReadinessProbeResult checkK8sApiDeploymentAvailable(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(k8sApiRequest(request), HttpResponse.BodyHandlers.ofString());
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

    HttpRequest k8sApiRequest(DeploymentReadinessProbeRequest request) throws IOException {
        String apiRef = firstText(request.contract(), "api_server_ref", "endpoint_ref");
        if (apiRef.isBlank()) throw new IOException("K8s API deployment readiness probe requires api_server_ref.");
        URI origin = httpEndpoint(dedicatedValue(apiRef, "K8S_API_SERVER"));
        if (!(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath())) || origin.getRawQuery() != null) {
            throw new IOException("K8s API endpoint must be an origin without a path or query.");
        }
        boolean operatorOrigin = "env://K8S_API_SERVER".equals(apiRef);
        if (!ProviderEndpoints.localHost(origin.getHost()) && (!operatorOrigin || !"https".equals(origin.getScheme()))) {
            throw new IOException("Remote K8s API requires HTTPS and env://K8S_API_SERVER.");
        }
        String path = "/apis/apps/v1/namespaces/" + K8sApiResource.NAMESPACE.resolve(request.namespaceRef(), environment)
                + "/deployments/" + K8sApiResource.DEPLOYMENT.resolve(request.deploymentRef(), environment);
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(Math.max(1, request.timeoutSeconds()))).GET();
        String bearerRef = firstText(request.contract(), "bearer_ref", "credential_ref");
        if (!bearerRef.isBlank()) {
            if (!operatorOrigin || !"https".equals(origin.getScheme()) || !"env://K8S_BEARER_TOKEN".equals(bearerRef)) {
                throw new IOException("Bearer readiness requires the HTTPS K8S_API_SERVER and K8S_BEARER_TOKEN environment bindings.");
            }
            builder.header("Authorization", "Bearer " + dedicatedValue(bearerRef, "K8S_BEARER_TOKEN"));
        }
        return builder.build();
    }

    private URI httpEndpoint(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535 && !uri.getRawAuthority().endsWith(":")) {
                return uri;
            }
        } catch (java.net.URISyntaxException ignored) {
            // Report only the policy error; URLs can contain sensitive material.
        }
        throw new IOException("Invalid HTTP readiness endpoint.");
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
        if ("ssh_command".equals(request.readinessProbe()) || "winrm_command".equals(request.readinessProbe())) {
            return checkVmCommand(request);
        }
        throw new IOException("Unsupported VM readiness_probe `" + request.readinessProbe() + "`.");
    }

    private DeploymentReadinessProbeResult checkVmTcp(DeploymentReadinessProbeRequest request)
            throws IOException {
        URI endpoint = runnerEndpoint("VM_TCP_ENDPOINT", "tcp");
        if (!ProviderEndpoints.localHost(endpoint.getHost()) || endpoint.getRawUserInfo() != null
                || endpoint.getPort() < 1 || endpoint.getPort() != request.port()
                || !hostMatches(request.hostRef(), endpoint, "VM_TCP_ENDPOINT")) {
            throw new IOException("VM TCP readiness requires the runner's numeric loopback VM_TCP_ENDPOINT and port.");
        }
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, TimeUnit.SECONDS.toMillis(Math.max(1, request.timeoutSeconds())));
        LoopbackTcpTransport.checkConnection(endpoint.getHost(), endpoint.getPort(), timeoutMs);
        return new DeploymentReadinessProbeResult(
                "vm tcp readiness passed for " + request.hostRef() + ":" + request.port() + "\n",
                "ready\n",
                1);
    }

    private DeploymentReadinessProbeResult checkVmHttp(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        String reference = firstText(request.contract(), "health_url_ref", "endpoint_ref");
        URI uri = httpEndpoint(dedicatedValue(reference, "VM_HEALTH_URL"));
        if (!ProviderEndpoints.localHost(uri.getHost()) && !("env://VM_HEALTH_URL".equals(reference) && "https".equals(uri.getScheme()))) {
            throw new IOException("Remote VM HTTP readiness requires HTTPS and env://VM_HEALTH_URL.");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, request.timeoutSeconds())))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("VM HTTP readiness failed with status `" + response.statusCode() + "`.");
        }
        return new DeploymentReadinessProbeResult(
                "vm http readiness passed for " + ProviderEndpoints.httpEvidenceRef(reference) + "\n",
                response.body().isBlank() ? "ready\n" : response.body(),
                1);
    }

    private DeploymentReadinessProbeResult checkVmCommand(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException {
        boolean ssh = "ssh_command".equals(request.readinessProbe());
        ReadinessTool tool = ssh ? ReadinessTool.SSH : ReadinessTool.WINRS;
        String prefix = ssh ? "VM_SSH" : "VM_WINRM";
        requireTool(request, ssh ? "ssh_ref" : "winrm_ref", tool);
        String commandRef = firstText(request.contract(), "command_ref");
        if (commandRef.isBlank()) throw new IOException("VM command readiness probe requires command_ref.");
        String command = requiredBinding(prefix + "_COMMAND");
        if (!commandRef.equals("env://" + prefix + "_COMMAND") && !commandRef.equals(command)) {
            throw new IOException("VM readiness command must match the runner's " + prefix + "_COMMAND.");
        }
        String binding = prefix + "_ENDPOINT";
        URI endpoint = runnerEndpoint(binding, ssh ? "ssh" : "https");
        int port = endpoint.getPort() == -1 ? (ssh ? 22 : 5986) : endpoint.getPort();
        String user = endpoint.getRawUserInfo();
        String requestedUser = firstText(request.contract(), "user_ref");
        if (!hostMatches(request.hostRef(), endpoint, binding)
                || (request.port() != 0 && request.port() != port)
                || (!requestedUser.isEmpty() && !requestedUser.equals(user))
                || (user != null && !user.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}"))) {
            throw new IOException("VM readiness destination, port and user must match the runner's " + binding + ".");
        }
        String verb = ssh ? "systemctl is-active " : "Get-Service ";
        String service = command.startsWith(verb) ? command.substring(verb.length()) : "";
        if (!service.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}")) {
            throw new IOException(prefix + "_COMMAND must select one service using " + verb.strip() + ".");
        }
        List<String> arguments = new ArrayList<>();
        if (ssh) {
            arguments.addAll(List.of("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                    "-o", "ClearAllForwardings=yes", "-o", "PermitLocalCommand=no", "-o", "ConnectTimeout=" + Math.max(1, request.timeoutSeconds()), "-p", Integer.toString(port), "--"));
            arguments.add((user == null ? "" : user + "@") + endpoint.getHost());
            arguments.addAll(List.of("systemctl", "is-active", "--", service));
        } else {
            arguments.add("-r:https://" + endpoint.getHost() + ":" + port);
            if (user != null) arguments.add("-u:" + user);
            arguments.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-Service -Name '" + service + "' -ErrorAction Stop"));
        }
        String stdout = runTool(new ReadinessCommand(tool, arguments), request.timeoutSeconds(),
                "VM " + request.readinessProbe() + " readiness probe", request.hostRef());
        String output = stdout.isBlank() ? "ready\n" : stdout;
        return new DeploymentReadinessProbeResult(output, output, 1);
    }

    private URI runnerEndpoint(String binding, String scheme) throws IOException {
        String value = requiredBinding(binding);
        try {
            URI endpoint = new URI(value);
            if (scheme.equals(endpoint.getScheme()) && endpoint.getHost() != null
                    && !endpoint.getRawAuthority().endsWith(":") && endpoint.getPort() != 0 && endpoint.getPort() <= 65535
                    && endpoint.getRawPath().isEmpty() && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null) {
                return endpoint;
            }
        } catch (java.net.URISyntaxException ignored) {
            // Report the name of the binding without echoing its value.
        }
        throw new IOException("Invalid readiness endpoint in " + binding + ".");
    }

    private boolean hostMatches(String reference, URI endpoint, String binding) {
        return reference.equals("env://" + binding) || reference.equals(endpoint.getHost())
                || (endpoint.getHost().startsWith("[") && ("[" + reference + "]").equals(endpoint.getHost()));
    }

    private String dedicatedValue(String reference, String binding) throws IOException {
        if (!reference.startsWith("env://")) return reference;
        if (!reference.equals("env://" + binding)) {
            throw new IOException("Readiness requires its dedicated env://" + binding + " binding.");
        }
        return requiredBinding(binding);
    }

    private String requiredBinding(String binding) throws IOException {
        String value = environment.apply(binding);
        if (value == null || value.isBlank()) throw new IOException("Environment variable `" + binding + "` is not set for deployment readiness.");
        return value;
    }

    private void requireTool(DeploymentReadinessProbeRequest request, String field, ReadinessTool tool) throws IOException {
        String reference = firstText(request.contract(), field);
        if (!reference.isEmpty() && !reference.equals(tool.name)) {
            throw new IOException("Readiness " + field + " only accepts the runner tool " + tool.name + ".");
        }
    }

    enum ReadinessTool {
        KUBECTL("kubectl"), SSH("ssh"), WINRS("winrs");

        final String name;

        ReadinessTool(String name) {
            this.name = name;
        }
    }

    private record ReadinessCommand(ReadinessTool tool, List<String> arguments) {
        ReadinessCommand {
            arguments = List.copyOf(arguments);
        }
    }

    private static ProcessBuilder systemTool(ReadinessTool tool) {
        boolean windows = java.io.File.separatorChar == '\\';
        ProcessBuilder builder = switch (tool) {
            case SSH -> windows ? new ProcessBuilder("C:\\Windows\\System32\\OpenSSH\\ssh.exe") : new ProcessBuilder("/usr/bin/ssh");
            case WINRS -> {
                if (!windows) throw new IllegalArgumentException("WinRM readiness requires a Windows runner.");
                yield new ProcessBuilder("C:\\Windows\\System32\\winrs.exe");
            }
            case KUBECTL -> {
                if (windows) yield new ProcessBuilder("C:\\Program Files\\Kubernetes\\kubectl.exe");
                if (Files.isExecutable(Path.of("/usr/bin/kubectl"))) yield new ProcessBuilder("/usr/bin/kubectl");
                if (Files.isExecutable(Path.of("/opt/homebrew/bin/kubectl"))) yield new ProcessBuilder("/opt/homebrew/bin/kubectl");
                yield new ProcessBuilder("/usr/local/bin/kubectl");
            }
        };
        Path executable = Path.of(builder.command().get(0));
        builder.directory(executable.getParent().toFile());
        builder.environment().put("PATH", windows ? "C:\\Windows\\System32;C:\\Windows;C:\\Windows\\System32\\WindowsPowerShell\\v1.0"
                : "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin");
        return builder;
    }

    private String runTool(ReadinessCommand command, int timeoutSeconds, String description, String target)
            throws IOException, InterruptedException {
        ProcessBuilder builder;
        try {
            builder = tools.apply(command.tool());
        } catch (IllegalArgumentException error) {
            throw new IOException(error.getMessage(), error);
        }
        builder.command().addAll(command.arguments());
        Process process = builder.start();
        try {
            if (!process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                throw new IOException(description + " timed out for `" + target + "`.");
            }
            if (process.exitValue() != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException(description + " failed for `" + target + "`: " + stderr.strip());
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
