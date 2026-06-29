package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
    void passesStringTimeoutToNativeReadinessProbe() throws Exception {
        RecordingDeploymentReadinessProbe probe = new RecordingDeploymentReadinessProbe();

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(probe),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", "45"));

        assertThat(result.exitCode()).isZero();
        assertThat(probe.request.timeoutSeconds()).isEqualTo(45);
    }

    @Test
    void usesDefaultTimeoutWhenNativeReadinessTimeoutStringIsBlank() throws Exception {
        RecordingDeploymentReadinessProbe probe = new RecordingDeploymentReadinessProbe();

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(probe),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", ""));

        assertThat(result.exitCode()).isZero();
        assertThat(probe.request.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void writesActionableFailureWhenNativeReadinessProbeThrowsIoWithoutMessage() throws Exception {
        DeploymentReadinessProvider provider = new DeploymentReadinessProvider(request -> {
            throw new IOException();
        });

        AdapterExecutionResult result = executeProvider(
                provider,
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .isEqualTo("Failed to execute deployment readiness provider.");
        assertThat(Files.readString(tempDir.resolve("run/readiness.yaml")))
                .contains("status: failed")
                .contains("error: Failed to execute deployment readiness provider.");
    }

    @Test
    void throwsUncheckedIoWhenFailureEvidenceCannotBeWrittenAfterNativeProbeIo() throws Exception {
        DeploymentReadinessProvider provider = new DeploymentReadinessProvider(request -> {
            throw new IOException("probe unavailable");
        });
        Path runDir = tempDir.resolve("run");
        Path stdoutDirectory = runDir.resolve("logs/stdout.log");
        Files.createDirectories(stdoutDirectory);

        assertThatThrownBy(() -> provider.execute(
                        "payment_k8s",
                        tempDir,
                        Map.of(
                                "provider_type", "k8s",
                                "readiness_probe", "rollout_status",
                                "namespace_ref", "payment",
                                "deployment_ref", "deployment/payment-api",
                                "deployed_version_ref", "build-42",
                                "timeout_seconds", 2),
                        runDir,
                        stdoutDirectory,
                        runDir.resolve("logs/stderr.log"),
                        runDir.resolve("actual/readiness.txt")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed to execute deployment readiness provider.");
    }

    @Test
    void reinterruptsThreadWhenNativeReadinessProbeIsInterrupted() {
        DeploymentReadinessProvider provider = new DeploymentReadinessProvider(request -> {
            throw new InterruptedException("stop readiness");
        });

        try {
            assertThatThrownBy(() -> executeProvider(
                            provider,
                            "payment_k8s",
                            Map.of(
                                    "provider_type", "k8s",
                                    "readiness_probe", "rollout_status",
                                    "namespace_ref", "payment",
                                    "deployment_ref", "deployment/payment-api",
                                    "deployed_version_ref", "build-42",
                                    "timeout_seconds", 2)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Deployment readiness provider interrupted.");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void routesK8sMockRefWithoutCallingNativeProbe() throws Exception {
        DeploymentReadinessProvider provider = new DeploymentReadinessProvider(request -> {
            throw new AssertionError("Native K8s probe should not be called.");
        });

        AdapterExecutionResult result = executeProvider(
                provider,
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kube_context_ref", "mock://k8s",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("k8s mock readiness passed for deployment/payment-api\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        assertThat(Files.readString(tempDir.resolve("run/readiness.yaml")))
                .contains("provider_type: k8s")
                .contains("kube_context_ref: mock://k8s")
                .contains("deployment_ref: deployment/payment-api")
                .contains("check_count: 1");
    }

    @Test
    void routesVmMockRefWithoutCallingNativeProbe() throws Exception {
        DeploymentReadinessProvider provider = new DeploymentReadinessProvider(request -> {
            throw new AssertionError("Native VM probe should not be called.");
        });

        AdapterExecutionResult result = executeProvider(
                provider,
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "tcp_connect",
                        "host_ref", "mock://payment-vm",
                        "port", 8443,
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("vm mock readiness passed for payment-api\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        assertThat(Files.readString(tempDir.resolve("run/readiness.yaml")))
                .contains("provider_type: vm")
                .contains("host_ref: mock://payment-vm")
                .contains("service_ref: payment-api")
                .contains("check_count: 1");
    }

    @Test
    void runsLocalMockFileExistsReadinessProbeAndWritesEvidence() throws Exception {
        Files.createDirectories(tempDir.resolve("markers"));
        Files.writeString(tempDir.resolve("markers/ready.txt"), "ready");

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_local",
                Map.of(
                        "provider_type", "mock",
                        "readiness_probe", "file_exists",
                        "service_ref", "markers/ready.txt",
                        "deployed_version_ref", "build-local"));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("readiness passed for markers/ready.txt\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        assertThat(Files.readString(tempDir.resolve("run/readiness.yaml")))
                .contains("status: passed")
                .contains("provider_type: mock")
                .contains("readiness_probe: file_exists")
                .contains("service_ref: markers/ready.txt")
                .contains("check_count: 1");
    }

    @Test
    void rejectsUnsupportedLocalReadinessProbeAndWritesEvidence() throws Exception {
        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_local",
                Map.of(
                        "provider_type", "local",
                        "readiness_probe", "tcp_connect",
                        "deployment_ref", "markers/ready.txt",
                        "deployed_version_ref", "build-local"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .isEqualTo("Unsupported local readiness_probe `tcp_connect`.");
        assertThat(Files.readString(tempDir.resolve("run/readiness.yaml")))
                .contains("status: failed")
                .contains("readiness_probe: tcp_connect")
                .contains("check_count: 0")
                .contains("error: Unsupported local readiness_probe `tcp_connect`.");
    }

    @Test
    void failsLocalReadinessWhenMarkerRefIsBlank() throws Exception {
        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_local",
                Map.of(
                        "provider_type", "local",
                        "readiness_probe", "file_exists",
                        "deployed_version_ref", "build-local"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .isEqualTo("Deployment readiness marker not found: ``.");
    }

    @Test
    void failsLocalReadinessWhenMarkerDoesNotExist() throws Exception {
        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_local",
                Map.of(
                        "provider_type", "local",
                        "readiness_probe", "file_exists",
                        "target_selector", "markers/missing.txt",
                        "deployed_version_ref", "build-local"));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .isEqualTo("Deployment readiness marker not found: `markers/missing.txt`.");
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
    void defaultProbeUsesFallbackK8sRolloutOutputWhenKubectlStdoutIsBlank() throws Exception {
        Path kubectl = executable("kubectl-blank-ready.sh", """
                #!/bin/sh
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog()))
                .isEqualTo("k8s readiness passed for deployment/payment-api\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
    }

    @Test
    void defaultProbeTimesOutBoundedKubectlRolloutStatus() throws Exception {
        Path kubectl = executable("kubectl-timeout.sh", """
                #!/bin/sh
                sleep 3
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("K8s readiness probe timed out for `deployment/payment-api`");
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
    void defaultProbeRunsK8sPodLogsWithPodRefAndResolvedContext() throws Exception {
        Path argsFile = tempDir.resolve("kubectl-pod-ref.args");
        Path kubectl = executable("kubectl-pod-ref.sh", """
                #!/bin/sh
                printf '%%s\\n' "$@" > "%s"
                echo "pod log line"
                exit 0
                """.formatted(argsFile));

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kubectl_ref", kubectl.toString(),
                        "kube_context_ref", "env://PATH",
                        "namespace_ref", "payment",
                        "pod_ref", "payment-api-abc123",
                        "log_tail_lines", "3",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(argsFile))
                .contains("--context\n")
                .contains("-n\npayment")
                .contains("logs\npayment-api-abc123")
                .contains("--tail=3")
                .doesNotContain("-l\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("pod log line\n");
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
    void defaultProbeSendsK8sApiBearerTokenWhenConfigured() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apis/apps/v1/namespaces/payment/deployments/payment-api", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer token-123");
            byte[] response = """
                    spec:
                      replicas: 1
                    status:
                      availableReplicas: 1
                      updatedReplicas: 1
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
                            "bearer_ref", "token-123",
                            "namespace_ref", "payment",
                            "deployment_ref", "deployments/payment-api",
                            "deployed_version_ref", "build-42",
                            "timeout_seconds", 2));

            assertThat(result.exitCode()).isZero();
            assertThat(Files.readString(result.stdoutLog()))
                    .contains("k8s api deployment available for deployments/payment-api");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeFailsK8sApiWhenServerReturnsErrorStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apis/apps/v1/namespaces/payment/deployments/payment-api", exchange -> {
            byte[] response = "temporarily unavailable".getBytes();
            exchange.sendResponseHeaders(503, response.length);
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

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(Files.readString(result.stderrLog()))
                    .contains("K8s API deployment readiness failed with status `503`");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeRequiresK8sApiServerRefForApiReadiness() throws Exception {
        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "api_deployment_available",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("K8s API deployment readiness probe requires api_server_ref");
    }

    @Test
    void defaultProbeWritesFailedEvidenceWhenProviderTypeIsUnsupported() throws Exception {
        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_unknown",
                Map.of(
                        "provider_type", "orbix",
                        "readiness_probe", "native_probe",
                        "deployed_version_ref", "build-unknown",
                        "timeout_seconds", 1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("Unsupported deployment readiness provider_type `orbix`");
        assertThat(Files.readString(tempDir.resolve("run/readiness.yaml")))
                .contains("status: failed")
                .contains("check_count: 0");
    }

    @Test
    void defaultProbeWritesFailedEvidenceWhenKubectlCommandFails() throws Exception {
        Path kubectl = executable("kubectl-fail.sh", """
                #!/bin/sh
                echo "rollout denied" >&2
                exit 7
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("K8s readiness probe failed for `deployment/payment-api`: rollout denied");
    }

    @Test
    void defaultProbeRejectsK8sPodLogsWithoutPodOrSelector() throws Exception {
        Path kubectl = executable("kubectl-unused-pod-logs.sh", """
                #!/bin/sh
                echo "should not run"
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "log_tail_lines", 10,
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("K8s pod_logs readiness probe requires pod_ref or target_selector");
    }

    @Test
    void defaultProbeRejectsK8sPodLogsWithInvalidTailLines() throws Exception {
        Path kubectl = executable("kubectl-unused-tail.sh", """
                #!/bin/sh
                echo "should not run"
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "log_tail_lines", "many",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("K8s pod_logs log_tail_lines must be a positive integer");
    }

    @Test
    void defaultProbeRejectsK8sPodLogsWithMissingOrNonPositiveTailLines() throws Exception {
        Path kubectl = executable("kubectl-unused-tail-empty.sh", """
                #!/bin/sh
                echo "should not run"
                exit 0
                """);

        AdapterExecutionResult missingTail = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(missingTail.exitCode()).isEqualTo(1);
        assertThat(Files.readString(missingTail.stderrLog()))
                .contains("K8s pod_logs readiness probe requires positive log_tail_lines");

        AdapterExecutionResult zeroTail = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_k8s",
                Map.of(
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kubectl_ref", kubectl.toString(),
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "log_tail_lines", 0,
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 2));

        assertThat(zeroTail.exitCode()).isEqualTo(1);
        assertThat(Files.readString(zeroTail.stderrLog()))
                .contains("K8s pod_logs log_tail_lines must be a positive integer");
    }

    @Test
    void defaultProbeFailsK8sApiWhenDeploymentIsNotAvailable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apis/apps/v1/namespaces/payment/deployments/payment-api", exchange -> {
            byte[] response = """
                    spec:
                      replicas: "3"
                    status:
                      availableReplicas: "1"
                      updatedReplicas: 2
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
                            "deployment_ref", "payment-api",
                            "deployed_version_ref", "build-42",
                            "timeout_seconds", 2));

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(Files.readString(result.stderrLog()))
                    .contains("availableReplicas=1 replicas=3");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeFailsK8sApiWhenResponseIsNotAnObject() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apis/apps/v1/namespaces/payment/deployments/payment-api", exchange -> {
            byte[] response = "[]".getBytes();
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

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(Files.readString(result.stderrLog()))
                    .contains("K8s API deployment readiness response must be an object");
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
    void defaultProbeUsesReadyOutputWhenVmHttpBodyIsBlank() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            AdapterExecutionResult result = executeProvider(
                    new DeploymentReadinessProvider(),
                    "payment_vm",
                    Map.of(
                            "provider_type", "vm",
                            "readiness_probe", "http_get",
                            "endpoint_ref", "http://127.0.0.1:" + server.getAddress().getPort() + "/health",
                            "service_ref", "payment-api",
                            "deployed_version_ref", "build-43",
                            "timeout_seconds", 2));

            assertThat(result.exitCode()).isZero();
            assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeFailsVmHttpGetWhenServerReturnsErrorStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] response = "not ready".getBytes();
            exchange.sendResponseHeaders(500, response.length);
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

            assertThat(result.exitCode()).isEqualTo(1);
            assertThat(Files.readString(result.stderrLog()))
                    .contains("VM HTTP readiness failed with status `500`");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultProbeWritesFailedEvidenceForUnsupportedVmProbe() throws Exception {
        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "process_table",
                        "host_ref", "10.0.0.15",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("Unsupported VM readiness_probe `process_table`");
    }

    @Test
    void defaultProbeWritesFailedEvidenceWhenVmCommandRefIsMissing() throws Exception {
        Path ssh = executable("ssh-unused.sh", """
                #!/bin/sh
                echo "should not run"
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "ssh_ref", ssh.toString(),
                        "host_ref", "10.0.0.15",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("VM command readiness probe requires command_ref");
    }

    @Test
    void defaultProbeWritesFailedEvidenceWhenVmCommandFails() throws Exception {
        Path ssh = executable("ssh-fail.sh", """
                #!/bin/sh
                echo "service inactive" >&2
                exit 8
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "ssh_ref", ssh.toString(),
                        "host_ref", "10.0.0.15",
                        "command_ref", "systemctl is-active payment-api",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("VM ssh_command readiness probe failed for `10.0.0.15`: service inactive");
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
    void defaultProbeUsesReadyOutputWhenVmSshCommandStdoutIsBlank() throws Exception {
        Path ssh = executable("ssh-blank-readiness.sh", """
                #!/bin/sh
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "ssh_ref", ssh.toString(),
                        "host_ref", "10.0.0.15",
                        "command_ref", "systemctl is-active payment-api",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog())).isEqualTo("ready\n");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("ready\n");
    }

    @Test
    void defaultProbeTimesOutBoundedVmSshCommand() throws Exception {
        Path ssh = executable("ssh-timeout.sh", """
                #!/bin/sh
                sleep 3
                exit 0
                """);

        AdapterExecutionResult result = executeProvider(
                new DeploymentReadinessProvider(),
                "payment_vm",
                Map.of(
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "ssh_ref", ssh.toString(),
                        "host_ref", "10.0.0.15",
                        "command_ref", "systemctl is-active payment-api",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 1));

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("VM ssh_command readiness probe timed out for `10.0.0.15`");
    }

    @Test
    void defaultProbeDirectlyRejectsUnsupportedNativeProviderType() {
        DefaultDeploymentReadinessProbe probe = new DefaultDeploymentReadinessProbe();

        assertThatThrownBy(() -> probe.check(new DeploymentReadinessProbeRequest(
                "legacy_deploy",
                "mainframe",
                "native_probe",
                "",
                "",
                "",
                "",
                "",
                "",
                0,
                "build-legacy",
                1,
                Map.of())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported deployment readiness provider_type `mainframe`");
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

    @Test
    void defaultProbeRunsBoundedVmWinrmCommandWithPortAndUser() throws Exception {
        Path argsFile = tempDir.resolve("winrm-port-user.args");
        Path winrm = executable("winrm-port-user.sh", """
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
                        "port", 5986,
                        "user_ref", "deploy",
                        "command_ref", "Get-Service payment-api",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-44",
                        "timeout_seconds", 2));

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(argsFile))
                .contains("-r:10.0.0.16:5986")
                .contains("-u:deploy")
                .contains("Get-Service payment-api");
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
