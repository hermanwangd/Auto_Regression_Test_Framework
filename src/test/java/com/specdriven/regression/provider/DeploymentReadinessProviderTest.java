package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentReadinessProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void runsNativeK8sReadinessProbeAndWritesEvidence() throws Exception {
        RecordingDeploymentReadinessProbe probe = new RecordingDeploymentReadinessProbe();

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(probe),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kube_context_ref", "env://KUBE_CONTEXT",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(probe.request.providerType()).isEqualTo("k8s");
        assertThat(probe.request.readinessProbe()).isEqualTo("rollout_status");
        assertThat(probe.request.kubeContextRef()).isEqualTo("env://KUBE_CONTEXT");
        assertThat(probe.request.namespaceRef()).isEqualTo("payment");
        assertThat(probe.request.deploymentRef()).isEqualTo("deployment/payment-api");
        assertThat(Files.readString(result.stdoutLog())).contains("native readiness passed");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("status: passed")
                .contains("provider_type: k8s")
                .contains("readiness_probe: rollout_status")
                .contains("kube_context_ref: env://KUBE_CONTEXT")
                .contains("namespace_ref: payment")
                .contains("deployment_ref: deployment/payment-api")
                .contains("deployed_version_ref: build-42")
                .contains("check_count: 1");
    }

    @Test
    void runsNativeK8sPodLogProbeAndWritesEvidence() throws Exception {
        RecordingDeploymentReadinessProbe probe = new RecordingDeploymentReadinessProbe(
                new DeploymentReadinessProbeResult(
                        "captured pod logs\n",
                        "payment-api started\nhealth ready\n",
                        1));

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(probe),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kube_context_ref", "env://KUBE_CONTEXT",
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "log_tail_lines", 50,
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(probe.request.readinessProbe()).isEqualTo("pod_logs");
        assertThat(probe.request.targetSelector()).isEqualTo("app=payment-api");
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("captured pod logs\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("payment-api started\nhealth ready\n");
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("status: passed")
                .contains("provider_type: k8s")
                .contains("readiness_probe: pod_logs")
                .contains("target_selector: app=payment-api")
                .contains("log_tail_lines: 50")
                .contains("deployed_version_ref: build-42")
                .contains("check_count: 1");
    }

    @Test
    void runsNativeVmTcpReadinessProbeAndWritesEvidence() throws Exception {
        RecordingDeploymentReadinessProbe probe = new RecordingDeploymentReadinessProbe();

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(probe),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "tcp_connect",
                        "host_ref", "10.0.0.15",
                        "port", 8443,
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(probe.request.providerType()).isEqualTo("vm");
        assertThat(probe.request.readinessProbe()).isEqualTo("tcp_connect");
        assertThat(probe.request.hostRef()).isEqualTo("10.0.0.15");
        assertThat(probe.request.port()).isEqualTo(8443);
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("status: passed")
                .contains("provider_type: vm")
                .contains("readiness_probe: tcp_connect")
                .contains("host_ref: 10.0.0.15")
                .contains("port: 8443")
                .contains("service_ref: payment-api")
                .contains("deployed_version_ref: build-43")
                .contains("check_count: 1");
    }

    @Test
    void defaultProbeRunsBoundedKubectlRolloutStatus() throws Exception {
        Path kubectl = executable("kubectl-ready.sh", """
                #!/bin/sh
                echo "deployment ready"
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kubectl_ref", kubectl.toString(),
                        "kube_context_ref", "kind-ci",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("deployment ready\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
    }

    @Test
    void defaultProbeRunsBoundedKubectlPodReadyWait() throws Exception {
        Path kubectl = executable("kubectl-pod-ready.sh", """
                #!/bin/sh
                echo "pod ready"
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_ready",
                        "kubectl_ref", kubectl.toString(),
                        "kube_context_ref", "kind-ci",
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("pod ready\n");
    }

    @Test
    void defaultProbeRunsBoundedKubectlPodLogsAndCapturesOutput() throws Exception {
        Path argsFile = tempDir.resolve("kubectl-pod-logs.args");
        Path kubectl = executable("kubectl-pod-logs.sh", """
                #!/bin/sh
                printf '%%s\\n' "$@" > "%s"
                echo "payment-api started"
                echo "health ready"
                exit 0
                """.formatted(argsFile));

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kubectl_ref", kubectl.toString(),
                        "kube_context_ref", "kind-ci",
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "log_tail_lines", 50,
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(argsFile))
                .contains("--context\nkind-ci")
                .contains("-n\npayment")
                .contains("logs")
                .contains("-l\napp=payment-api")
                .contains("--tail=50");
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("payment-api started\nhealth ready\n");
        assertThat(Files.readString(result.actualOutput()))
                .isEqualTo("payment-api started\nhealth ready\n");
    }

    @Test
    void defaultProbeRunsDirectK8sApiDeploymentAvailableProbe() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apis/apps/v1/namespaces/payment/deployments/payment-api", exchange -> {
            byte[] response = """
                    {
                      "metadata": {"name": "payment-api"},
                      "spec": {"replicas": 3},
                      "status": {
                        "availableReplicas": 3,
                        "updatedReplicas": 3,
                        "observedGeneration": 7
                      }
                    }
                    """.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            AdapterExecutionResult result = executeProvider(
                    new DeploymentReadinessProvider(),
                    "payment_k8s",
                    Map.of(
                            "provider_type", "k8s",
                            "readiness_probe", "api_deployment_available",
                            "api_server_ref", "http://127.0.0.1:" + server.getAddress().getPort(),
                            "namespace_ref", "payment",
                            "deployment_ref", "deployment/payment-api",
                            "deployed_version_ref", "build-42",
                            "timeout_seconds", 2));

            Path runDir = tempDir.resolve("run");
            assertThat(result.exitCode()).isZero();
            assertThat(Files.readString(result.stdoutLog()))
                    .contains("k8s api deployment available for deployment/payment-api")
                    .contains("availableReplicas=3")
                    .contains("replicas=3");
            assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
            assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                    .contains("readiness_probe: api_deployment_available")
                    .contains("api_server_ref: http://127.0.0.1:" + server.getAddress().getPort())
                    .contains("namespace_ref: payment")
                    .contains("deployment_ref: deployment/payment-api")
                    .contains("check_count: 1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeWritesFailedEvidenceWhenK8sEnvRefIsMissing() throws Exception {
        Path kubectl = executable("kubectl-unused.sh", """
                #!/bin/sh
                echo "should not run"
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kubectl_ref", kubectl.toString(),
                        "kube_context_ref", "env://SPEC_REGRESSION_MISSING_KUBE_CONTEXT",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("SPEC_REGRESSION_MISSING_KUBE_CONTEXT");
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("status: failed")
                .contains("check_count: 0")
                .contains("SPEC_REGRESSION_MISSING_KUBE_CONTEXT");
    }

    @Test
    void defaultProbeRunsVmTcpConnectAgainstLocalSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptThread = new Thread(() -> {
                try (var ignored = server.accept()) {
                    // Accept one connection so the TCP readiness probe can complete.
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            acceptThread.start();

            AdapterExecutionResult result = executeProvider(
                    new DeploymentReadinessProvider(),
                    "payment_vm",
                    Map.of(
                            "provider_type", "vm",
                            "readiness_probe", "tcp_connect",
                            "host_ref", "127.0.0.1",
                            "port", server.getLocalPort(),
                            "service_ref", "payment-api",
                            "deployed_version_ref", "build-43",
                            "timeout_seconds", 2));

            acceptThread.join(2000);
            assertThat(result.exitCode()).isZero();
            assertThat(Files.readString(result.stdoutLog())).contains("vm tcp readiness passed");
            assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        }
    }

    @Test
    void defaultProbeRunsVmHttpGetAgainstLocalServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] response = "healthy\n".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            AdapterExecutionResult result = executeProvider(
                    new DeploymentReadinessProvider(),
                    "payment_vm",
                    Map.of(
                            "provider_type", "vm",
                            "readiness_probe", "http_get",
                            "health_url_ref", "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                            "service_ref", "payment-api",
                            "deployed_version_ref", "build-43",
                            "timeout_seconds", 2));

            assertThat(result.exitCode()).isZero();
            assertThat(Files.readString(result.stdoutLog())).contains("vm http readiness passed");
            assertThat(Files.readString(result.actualOutput())).isEqualTo("healthy\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeRunsBoundedVmSshCommandAndCapturesOutput() throws Exception {
        Path argsFile = tempDir.resolve("ssh-readiness.args");
        Path ssh = executable("ssh-readiness.sh", """
                #!/bin/sh
                printf '%%s\\n' "$@" > "%s"
                echo "active"
                exit 0
                """.formatted(argsFile));

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "ssh_ref", ssh.toString(),
                        "host_ref", "10.0.0.15",
                        "user_ref", "deploy",
                        "port", 2222,
                        "command_ref", "systemctl is-active payment-api",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 2));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(argsFile))
                .contains("-o\nBatchMode=yes")
                .contains("-o\nConnectTimeout=2")
                .contains("-p\n2222")
                .contains("deploy@10.0.0.15")
                .contains("systemctl is-active payment-api");
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("active\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("active\n");
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("readiness_probe: ssh_command")
                .contains("ssh_ref: " + ssh)
                .contains("host_ref: 10.0.0.15")
                .contains("user_ref: deploy")
                .contains("command_ref: systemctl is-active payment-api")
                .contains("check_count: 1");
    }

    @Test
    void defaultProbeRunsBoundedVmWinrmCommandAndCapturesOutput() throws Exception {
        Path argsFile = tempDir.resolve("winrm-readiness.args");
        Path winrm = executable("winrm-readiness.sh", """
                #!/bin/sh
                printf '%%s\\n' "$@" > "%s"
                echo "Running"
                exit 0
                """.formatted(argsFile));

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "winrm_command",
                        "winrm_ref", winrm.toString(),
                        "host_ref", "10.0.0.16",
                        "command_ref", "Get-Service payment-api",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-44",
                        "timeout_seconds", 2));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(argsFile))
                .contains("-r:10.0.0.16")
                .contains("Get-Service payment-api");
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("Running\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("Running\n");
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("readiness_probe: winrm_command")
                .contains("winrm_ref: " + winrm)
                .contains("host_ref: 10.0.0.16")
                .contains("command_ref: Get-Service payment-api")
                .contains("check_count: 1");
    }

    private AdapterExecutionResult executeProvider(
            DeploymentReadinessProvider provider,
            String providerName,
            Map<String, Object> contract) {
        Path runDir = tempDir.resolve("run");
        return provider.execute(
                providerName,
                tempDir,
                contract,
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/readiness.txt"));
    }

    private Path executable(String filename, String content) throws Exception {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private static class RecordingDeploymentReadinessProbe implements DeploymentReadinessProbe {

        private final DeploymentReadinessProbeResult result;
        private DeploymentReadinessProbeRequest request;

        RecordingDeploymentReadinessProbe() {
            this(new DeploymentReadinessProbeResult("native readiness passed\n", "ready\n", 1));
        }

        RecordingDeploymentReadinessProbe(DeploymentReadinessProbeResult result) {
            this.result = result;
        }

        @Override
        public DeploymentReadinessProbeResult check(DeploymentReadinessProbeRequest request) {
            this.request = request;
            return result;
        }
    }
}
