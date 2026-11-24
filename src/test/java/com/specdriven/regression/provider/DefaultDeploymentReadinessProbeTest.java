package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultDeploymentReadinessProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void k8sRolloutStatusUsesRunnerKubectlAndAllowsBlankNamespace() throws Exception {
        Path argsFile = tempDir.resolve("kubectl-args.txt");
        Path kubectl = executableScript("""
                printf '%%s\\n' "$@" > %s
                exit 0
                """.formatted(shellQuote(argsFile)));
        DeploymentReadinessProbeRequest request = request(
                "k8s",
                "rollout_status",
                "",
                "",
                "deployment/payment-api",
                "",
                "",
                "",
                0,
                Map.of("kubectl_ref", "kubectl"));

        var probe = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), key -> null, tool -> {
            assertThat(tool).isEqualTo(DefaultDeploymentReadinessProbe.ReadinessTool.KUBECTL);
            return new ProcessBuilder("/bin/sh", kubectl.toString());
        });
        DeploymentReadinessProbeResult result = probe.check(request);

        assertThat(result.stdout()).isEqualTo("k8s readiness passed for deployment/payment-api\n");
        assertThat(result.actualOutput()).isEqualTo("ready\n");
        assertThat(result.checkCount()).isEqualTo(1);
        assertThat(Files.readAllLines(argsFile))
                .containsExactly("rollout", "status", "deployment/payment-api", "--timeout=2s")
                .doesNotContain("-n");
    }

    @Test
    void k8sApiDeploymentAvailableAcceptsStringReplicaCountsAndMissingSpecMap() throws Exception {
        HttpServer server = server(200, """
                spec: []
                status:
                  availableReplicas: "1"
                  updatedReplicas: "1"
                """);
        try {
            DeploymentReadinessProbeRequest request = request(
                    "k8s",
                    "api_deployment_available",
                    "",
                    "payment",
                    "deployment/payment-api",
                    "",
                    "",
                    "",
                    0,
                    Map.of("api_server_ref", serverUrl(server)));

            DeploymentReadinessProbeResult result = new DefaultDeploymentReadinessProbe().check(request);

            assertThat(result.stdout())
                    .contains("k8s api deployment available for deployment/payment-api")
                    .contains("availableReplicas=1")
                    .contains("replicas=1")
                    .contains("updatedReplicas=1");
            assertThat(result.actualOutput()).isEqualTo("ready\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void k8sApiDeploymentAvailableFailsForNonSuccessStatus() throws Exception {
        HttpServer server = server(503, "service unavailable\n");
        try {
            DeploymentReadinessProbeRequest request = request(
                    "k8s",
                    "api_deployment_available",
                    "",
                    "payment",
                    "deployment/payment-api",
                    "",
                    "",
                    "",
                    0,
                    Map.of("api_server_ref", serverUrl(server)));

            assertThatThrownBy(() -> new DefaultDeploymentReadinessProbe().check(request))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("K8s API deployment readiness failed with status `503`");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vmHttpReadinessFailsForNonSuccessStatus() throws Exception {
        HttpServer server = server(500, "down\n");
        try {
            DeploymentReadinessProbeRequest request = request(
                    "vm",
                    "http_get",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "payment-vm",
                    0,
                    Map.of("health_url_ref", serverUrl(server)));

            assertThatThrownBy(() -> new DefaultDeploymentReadinessProbe().check(request))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("VM HTTP readiness failed with status `500`");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void vmCommandReadinessRequiresCommandRefBeforeExecutingDefaultSshOrWinrm() {
        DefaultDeploymentReadinessProbe probe = new DefaultDeploymentReadinessProbe();

        assertThatThrownBy(() -> probe.check(request(
                        "vm",
                        "ssh_command",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "payment-vm",
                        0,
                        Map.of())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("VM command readiness probe requires command_ref.");

        assertThatThrownBy(() -> probe.check(request(
                        "vm",
                        "winrm_command",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "payment-vm",
                        0,
                        Map.of())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("VM command readiness probe requires command_ref.");
    }

    @Test
    void readinessRefsFailWhenReferencedEnvironmentVariableIsMissing() {
        DeploymentReadinessProbeRequest request = request(
                "k8s",
                "api_deployment_available",
                "",
                "payment",
                "deployment/payment-api",
                "",
                "",
                "",
                0,
                Map.of("api_server_ref", "env://K8S_API_SERVER"));

        var probe = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), key -> null);
        assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class)
                .hasMessageContaining("Environment variable `K8S_API_SERVER` is not set");
    }

    @Test
    void bindsBearerToTheRunnerHttpsOrigin() throws Exception {
        String fixtureToken = java.util.UUID.randomUUID().toString();
        Map<String, String> bindings = Map.of("K8S_API_SERVER", "https://cluster.example:6443/",
                "K8S_BEARER_TOKEN", fixtureToken);
        var probe = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), bindings::get);
        var request = request("k8s", "api_deployment_available", "", "payment", "deployment/payment-api",
                "", "", "", 0, Map.of("api_server_ref", "env://K8S_API_SERVER", "bearer_ref", "env://K8S_BEARER_TOKEN"));

        var http = probe.k8sApiRequest(request);
        assertThat(http.uri().toString()).isEqualTo("https://cluster.example:6443/apis/apps/v1/namespaces/payment/deployments/payment-api");
        assertThat(http.headers().firstValue("Authorization")).contains("Bearer " + fixtureToken);
    }

    @Test
    void usesDedicatedResourceBindingsForTheDeploymentApiPath() throws Exception {
        Map<String, String> bindings = Map.of("K8S_NAMESPACE", "1payment", "K8S_DEPLOYMENT", "deploy/pay.v2");
        var probe = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), bindings::get);
        var request = request("k8s", "api_deployment_available", "", "env://K8S_NAMESPACE", "env://K8S_DEPLOYMENT",
                "", "", "", 0, Map.of("api_server_ref", "http://127.0.0.1"));

        assertThat(probe.k8sApiRequest(request).uri().getPath())
                .isEqualTo("/apis/apps/v1/namespaces/1payment/deployments/pay.v2");
    }

    @Test
    void rejectsOtherResourceBindingsWithoutReadingTheEnvironment() {
        var probe = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), key -> {
            throw new AssertionError("A resource path must only read its dedicated binding");
        });
        for (var names : List.of(List.of("env://FIXTURE_SETTING", "deploy/app"),
                List.of("default", "env://FIXTURE_SETTING"), List.of("env://K8S_DEPLOYMENT", "app"))) {
            var request = request("k8s", "api_deployment_available", "", names.get(0), names.get(1), "", "", "", 0,
                    Map.of("api_server_ref", "http://127.0.0.1"));
            assertThatThrownBy(() -> probe.k8sApiRequest(request)).isInstanceOf(IOException.class)
                    .hasMessageContaining("dedicated").hasMessageNotContaining("FIXTURE_SETTING");
        }
    }

    @Test
    void validatesResourceSyntaxAndLengthForLiteralsAndDedicatedBindings() throws Exception {
        String label = "n".repeat(63);
        String name = String.join(".", label, label, label, "d".repeat(61));
        assertThat(K8sApiResource.NAMESPACE.resolve(label, key -> null)).isEqualTo(label);
        assertThat(K8sApiResource.DEPLOYMENT.resolve("deployments/" + name, key -> null)).isEqualTo(name);
        for (String invalid : List.of("fixture.name", "Fixture", "fixture-", label + "n")) {
            assertThatThrownBy(() -> K8sApiResource.NAMESPACE.resolve(invalid, key -> null))
                    .isInstanceOf(IOException.class).hasMessageNotContaining(invalid);
        }
        for (String invalid : List.of("fixture..v2", "fixture-.v2", ".", "..", "app/fixture", name + "d")) {
            assertThatThrownBy(() -> K8sApiResource.DEPLOYMENT.resolve("env://K8S_DEPLOYMENT", key -> invalid))
                    .isInstanceOf(IOException.class).hasMessageContaining("K8S_DEPLOYMENT");
        }
    }

    @Test
    void rejectsSuiteSelectedExecutablesBeforeLaunch() {
        var probe = neverLaunch(Map.of());
        String path = tempDir.resolve("fixture-tool").toString();
        for (String ref : List.of(path, "env://FIXTURE_TOOL")) {
            for (var request : List.of(
                    request("k8s", "rollout_status", "", "", "deploy/app", "", "", "", 0, Map.of("kubectl_ref", ref)),
                    request("vm", "ssh_command", "", "", "", "", "", "fixture", 0, Map.of("ssh_ref", ref)),
                    request("vm", "winrm_command", "", "", "", "", "", "fixture", 0, Map.of("winrm_ref", ref)))) {
                assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class)
                        .hasMessageContaining("runner tool").hasMessageNotContaining(ref);
            }
        }
    }

    @Test
    void rejectsKubernetesContextChangesAndInvalidResourceArgumentsBeforeLaunch() {
        var probe = neverLaunch(Map.of("K8S_CONTEXT", "fixture", "K8S_POD", "invalid/name"));
        for (var request : List.of(
                request("k8s", "rollout_status", "other", "", "deploy/app", "", "", "", 0, Map.of()),
                request("k8s", "rollout_status", "", "other namespace", "deploy/app", "", "", "", 0, Map.of()),
                request("k8s", "rollout_status", "", "", "-fixture", "", "", "", 0, Map.of()),
                request("k8s", "pod_logs", "", "", "", "", "", "", 0, Map.of("pod_ref", "env://K8S_POD", "log_tail_lines", 1)),
                request("k8s", "pod_ready", "", "", "", "", "-fixture", "", 0, Map.of()),
                request("k8s", "unknown", "", "", "deploy/app", "", "", "", 0, Map.of()))) {
            assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsVmCommandDestinationUserPortAndCommandMismatchesBeforeLaunch() {
        for (String mode : List.of("ssh_command", "winrm_command")) {
            boolean ssh = mode.equals("ssh_command");
            String prefix = ssh ? "VM_SSH" : "VM_WINRM";
            String approvedCommand = ssh ? "systemctl is-active fixture" : "Get-Service fixture";
            var probe = neverLaunch(Map.of(prefix + "_ENDPOINT", (ssh ? "ssh" : "https") + "://deploy@fixture:9000",
                    prefix + "_COMMAND", approvedCommand));
            var command = Map.<String, Object>of("command_ref", "env://" + prefix + "_COMMAND");
            for (var request : List.of(
                    request("vm", mode, "", "", "", "", "", "other", 9000, command),
                    request("vm", mode, "", "", "", "", "", "fixture", 9001, command),
                    request("vm", mode, "", "", "", "", "", "fixture", 9000, Map.of("user_ref", "other", "command_ref", approvedCommand)),
                    request("vm", mode, "", "", "", "", "", "fixture", 9000, Map.of("command_ref", "other")))) {
                assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class).hasMessageContaining(prefix);
            }
        }
    }

    @Test
    void runnerCommandMustSelectExactlyOneService() {
        for (String mode : List.of("ssh_command", "winrm_command")) {
            boolean ssh = mode.equals("ssh_command");
            String prefix = ssh ? "VM_SSH" : "VM_WINRM";
            String verb = ssh ? "systemctl is-active " : "Get-Service ";
            var request = request("vm", mode, "", "", "", "", "", "fixture", 0,
                    Map.of("command_ref", "env://" + prefix + "_COMMAND"));
            for (String invalid : List.of("unsupported fixture", verb + "service other", verb + "-option")) {
                var probe = neverLaunch(Map.of(prefix + "_ENDPOINT", (ssh ? "ssh" : "https") + "://fixture",
                        prefix + "_COMMAND", invalid));
                assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class)
                        .hasMessageContaining("must select one service");
            }
        }
    }

    @Test
    void rejectsPlaintextRemoteReadinessAndUnrelatedEnvironmentBindings() {
        var probe = neverLaunch(Map.of("VM_HEALTH_URL", "http://fixture.invalid/ready",
                "K8S_API_SERVER", "http://fixture.invalid", "VM_TCP_ENDPOINT", "tcp://fixture.invalid:1234"));
        var tcp = request("vm", "tcp_connect", "", "", "", "", "", "fixture.invalid", 1234, Map.of());
        assertThatThrownBy(() -> probe.check(tcp)).isInstanceOf(IOException.class).hasMessageContaining("numeric loopback");
        var http = request("vm", "http_get", "", "", "", "", "", "", 0, Map.of("health_url_ref", "env://VM_HEALTH_URL"));
        assertThatThrownBy(() -> probe.check(http)).isInstanceOf(IOException.class).hasMessageContaining("HTTPS");
        var api = request("k8s", "api_deployment_available", "", "default", "deploy/app", "", "", "", 0,
                Map.of("api_server_ref", "env://K8S_API_SERVER"));
        assertThatThrownBy(() -> probe.check(api)).isInstanceOf(IOException.class).hasMessageContaining("HTTPS");

        var arbitraryRef = request("vm", "http_get", "", "", "", "", "", "", 0, Map.of("health_url_ref", "env://FIXTURE_VALUE"));
        var unreadable = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), key -> {
            throw new AssertionError("Unexpected access to an unrelated environment binding");
        });
        assertThatThrownBy(() -> unreadable.check(arbitraryRef)).isInstanceOf(IOException.class).hasMessageContaining("VM_HEALTH_URL");
    }

    @Test
    void requiresTheExactRunnerLoopbackTcpPort() {
        var probe = neverLaunch(Map.of("VM_TCP_ENDPOINT", "tcp://127.0.0.1:51000"));
        var wrongPort = request("vm", "tcp_connect", "", "", "", "", "", "127.0.0.1", 52000, Map.of());
        var wrongAddress = request("vm", "tcp_connect", "", "", "", "", "", "::1", 51000, Map.of());
        for (var request : List.of(wrongPort, wrongAddress)) {
            assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class).hasMessageContaining("VM_TCP_ENDPOINT");
        }
    }

    private DefaultDeploymentReadinessProbe neverLaunch(Map<String, String> bindings) {
        return new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), bindings::get, tool -> {
            throw new AssertionError("Invalid request reached tool selection");
        });
    }

    @Test
    void commandProbeStreamsUseUtf8UnderAnAsciiJvm() throws Exception {
        Path script = executableScript("""
                case "$*" in
                  *fixture-failure*) printf '診斷錯誤' >&2; exit 1 ;;
                  *) printf '服務就緒' ;;
                esac
                """);
        Path log = tempDir.resolve("charset-check.log");
        Process process = new ProcessBuilder("./java",
                "-Xmx96m", "-Dfile.encoding=US-ASCII", "-cp", System.getProperty("java.class.path"),
                AsciiProbeCheck.class.getName(), script.toString())
                .directory(Path.of(System.getProperty("java.home"), "bin").toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally {
            process.destroyForcibly();
        }
    }

    public static class AsciiProbeCheck {
        public static void main(String[] args) throws Exception {
            assertThat(Charset.defaultCharset()).isEqualTo(StandardCharsets.US_ASCII);
            for (String provider : List.of("k8s", "vm")) {
                boolean k8s = provider.equals("k8s");
                String mode = k8s ? "rollout_status" : "ssh_command";
                var contract = k8s ? Map.<String, Object>of() : Map.<String, Object>of("command_ref", "env://VM_SSH_COMMAND");
                for (String target : List.of("fixture-success", "fixture-failure")) {
                    var bindings = Map.of("VM_SSH_ENDPOINT", "ssh://" + target, "VM_SSH_COMMAND", "systemctl is-active fixture");
                    var probe = new DefaultDeploymentReadinessProbe(java.net.http.HttpClient.newHttpClient(), bindings::get,
                            tool -> new ProcessBuilder("/bin/sh", args[0]));
                    var request = request(provider, mode, "", "", target, "", "", target, 0, contract);
                    if (target.equals("fixture-success")) {
                        assertThat(probe.check(request).stdout()).isEqualTo("服務就緒");
                    } else {
                        String prefix = k8s ? "K8s readiness probe" : "VM " + mode + " readiness probe";
                        assertThatThrownBy(() -> probe.check(request)).isInstanceOf(IOException.class)
                                .hasMessage(prefix + " failed for `fixture-failure`: 診斷錯誤");
                    }
                }
            }
        }
    }

    private static DeploymentReadinessProbeRequest request(
            String providerType,
            String readinessProbe,
            String kubeContextRef,
            String namespaceRef,
            String deploymentRef,
            String serviceRef,
            String targetSelector,
            String hostRef,
            int port,
            Map<String, Object> contract) {
        return new DeploymentReadinessProbeRequest(
                "readiness_provider",
                providerType,
                readinessProbe,
                kubeContextRef,
                namespaceRef,
                deploymentRef,
                serviceRef,
                targetSelector,
                hostRef,
                port,
                "build-1",
                2,
                contract);
    }

    private HttpServer server(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private String serverUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Path executableScript(String body) throws IOException {
        Path script = tempDir.resolve("probe-script.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    private String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
