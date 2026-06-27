package com.specdriven.regression.provider;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeploymentReadinessProvider {

    private final DeploymentReadinessProbe readinessProbe;

    public DeploymentReadinessProvider() {
        this(new DefaultDeploymentReadinessProbe());
    }

    DeploymentReadinessProvider(DeploymentReadinessProbe readinessProbe) {
        this.readinessProbe = readinessProbe;
    }

    public AdapterExecutionResult execute(
            String providerName,
            Path packageRoot,
            Map<String, Object> contract,
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput) {
        try {
            Files.createDirectories(stdoutLog.getParent());
            Files.createDirectories(stderrLog.getParent());
            Files.createDirectories(actualOutput.getParent());

            String providerType = stringValue(contract.get("provider_type"));
            String readinessProbeName = stringValue(contract.get("readiness_probe"));
            String deploymentRef = firstText(contract, "deployment_ref", "service_ref", "target_selector");
            if (isNativeProvider(providerType)) {
                DeploymentReadinessProbeResult probeResult = readinessProbe.check(new DeploymentReadinessProbeRequest(
                        providerName,
                        providerType,
                        readinessProbeName,
                        stringValue(contract.get("kube_context_ref")),
                        stringValue(contract.get("namespace_ref")),
                        stringValue(contract.get("deployment_ref")),
                        stringValue(contract.get("service_ref")),
                        stringValue(contract.get("target_selector")),
                        stringValue(contract.get("host_ref")),
                        intValue(contract.get("port"), 0),
                        stringValue(contract.get("deployed_version_ref")),
                        intValue(contract.get("timeout_seconds"), 300),
                        contract));
                Files.writeString(stdoutLog, probeResult.stdout());
                Files.writeString(stderrLog, "");
                Files.writeString(actualOutput, probeResult.actualOutput());
                writeReadinessEvidence(runDir, providerName, contract, "passed", "", probeResult.checkCount());
                return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
            }
            if (!isLocalProvider(providerType)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        contract,
                        "Unsupported deployment readiness provider_type `" + providerType + "` for local execution.");
            }
            if (!"file_exists".equals(readinessProbeName)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        contract,
                        "Unsupported local readiness_probe `" + readinessProbeName + "`.");
            }
            if (deploymentRef.isBlank() || !Files.exists(packageRoot.resolve(deploymentRef))) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        contract,
                        "Deployment readiness marker not found: `" + deploymentRef + "`.");
            }

            Files.writeString(stdoutLog, "readiness passed for " + deploymentRef + "\n");
            Files.writeString(stderrLog, "");
            Files.writeString(actualOutput, "ready\n");
            writeReadinessEvidence(runDir, providerName, contract, "passed", "", 1);
            return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Deployment readiness provider interrupted.", e);
        } catch (IOException e) {
            try {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        contract,
                        e.getMessage() == null ? "Failed to execute deployment readiness provider." : e.getMessage());
            } catch (IOException writeFailure) {
                throw new UncheckedIOException("Failed to execute deployment readiness provider.", writeFailure);
            }
        }
    }

    private AdapterExecutionResult fail(
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput,
            String providerName,
            Map<String, Object> contract,
            String message) throws IOException {
        Files.writeString(stdoutLog, "");
        Files.writeString(stderrLog, message);
        Files.writeString(actualOutput, "");
        writeReadinessEvidence(runDir, providerName, contract, "failed", message, 0);
        return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
    }

    private void writeReadinessEvidence(
            Path runDir,
            String providerName,
            Map<String, Object> contract,
            String status,
            String error,
            int checkCount) throws IOException {
        Files.createDirectories(runDir);
        StringBuilder builder = new StringBuilder();
        builder.append("status: ").append(status).append("\n");
        builder.append("provider: ").append(providerName).append("\n");
        appendIfPresent(builder, "provider_type", stringValue(contract.get("provider_type")));
        appendIfPresent(builder, "readiness_probe", stringValue(contract.get("readiness_probe")));
        appendIfPresent(builder, "api_server_ref", stringValue(contract.get("api_server_ref")));
        appendIfPresent(builder, "kube_context_ref", stringValue(contract.get("kube_context_ref")));
        appendIfPresent(builder, "namespace_ref", stringValue(contract.get("namespace_ref")));
        appendIfPresent(builder, "pod_ref", stringValue(contract.get("pod_ref")));
        appendIfPresent(builder, "target_selector", stringValue(contract.get("target_selector")));
        appendIfPresent(builder, "deployment_ref", stringValue(contract.get("deployment_ref")));
        appendIfPresent(builder, "service_ref", stringValue(contract.get("service_ref")));
        appendIfPresent(builder, "log_tail_lines", stringValue(contract.get("log_tail_lines")));
        appendIfPresent(builder, "host_ref", stringValue(contract.get("host_ref")));
        appendIfPresent(builder, "port", stringValue(contract.get("port")));
        appendIfPresent(builder, "ssh_ref", stringValue(contract.get("ssh_ref")));
        appendIfPresent(builder, "winrm_ref", stringValue(contract.get("winrm_ref")));
        appendIfPresent(builder, "user_ref", stringValue(contract.get("user_ref")));
        appendIfPresent(builder, "command_ref", stringValue(contract.get("command_ref")));
        appendIfPresent(builder, "health_url_ref", stringValue(contract.get("health_url_ref")));
        appendIfPresent(builder, "deployed_version_ref", stringValue(contract.get("deployed_version_ref")));
        builder.append("checks:\n");
        builder.append("  - name: ").append(stringValue(contract.get("readiness_probe"))).append("\n");
        builder.append("    status: ").append(status).append("\n");
        builder.append("check_count: ").append(checkCount).append("\n");
        if (!error.isBlank()) {
            builder.append("error: ").append(error).append("\n");
        }
        Files.writeString(runDir.resolve("readiness.yaml"), builder.toString());
    }

    private void appendIfPresent(StringBuilder builder, String field, String value) {
        if (!value.isBlank()) {
            builder.append(field).append(": ").append(value).append("\n");
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

    private boolean isLocalProvider(String providerType) {
        String normalized = providerType.toLowerCase(Locale.ROOT);
        return normalized.equals("local") || normalized.equals("mock");
    }

    private boolean isNativeProvider(String providerType) {
        String normalized = providerType.toLowerCase(Locale.ROOT);
        return normalized.equals("k8s") || normalized.equals("vm");
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
