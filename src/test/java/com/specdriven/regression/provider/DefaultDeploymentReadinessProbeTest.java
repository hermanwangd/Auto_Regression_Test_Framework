package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private DeploymentReadinessProbeRequest request(
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
