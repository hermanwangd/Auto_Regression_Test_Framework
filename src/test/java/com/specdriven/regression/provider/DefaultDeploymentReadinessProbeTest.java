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
    void k8sRolloutStatusUsesKubectlRefAndAllowsBlankNamespace() throws Exception {
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
                Map.of("kubectl_ref", kubectl.toString()));

        DeploymentReadinessProbeResult result = new DefaultDeploymentReadinessProbe().check(request);

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
                Map.of("api_server_ref", "env://SPEC_DRIVEN_REGRESSION_UNSET_READINESS_ENV"));

        assertThatThrownBy(() -> new DefaultDeploymentReadinessProbe().check(request))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Environment variable `SPEC_DRIVEN_REGRESSION_UNSET_READINESS_ENV` is not set");
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
            var probe = new DefaultDeploymentReadinessProbe();
            for (String provider : List.of("k8s", "vm")) {
                boolean k8s = provider.equals("k8s");
                String mode = k8s ? "rollout_status" : "ssh_command";
                var contract = k8s ? Map.<String, Object>of("kubectl_ref", args[0])
                        : Map.<String, Object>of("ssh_ref", args[0], "command_ref", "fixture");
                var success = request(provider, mode, "", "", "fixture-success", "", "", "fixture-success", 0, contract);
                assertThat(probe.check(success).stdout()).isEqualTo("服務就緒");
                var failure = request(provider, mode, "", "", "fixture-failure", "", "", "fixture-failure", 0, contract);
                String prefix = k8s ? "K8s readiness probe" : "VM " + mode + " readiness probe";
                assertThatThrownBy(() -> probe.check(failure)).isInstanceOf(IOException.class)
                        .hasMessage(prefix + " failed for `fixture-failure`: 診斷錯誤");
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
