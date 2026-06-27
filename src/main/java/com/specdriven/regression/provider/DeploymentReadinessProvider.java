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
            String readinessProbe = stringValue(contract.get("readiness_probe"));
            String deploymentRef = firstText(contract, "deployment_ref", "service_ref", "target_selector");
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
            if (!"file_exists".equals(readinessProbe)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        contract,
                        "Unsupported local readiness_probe `" + readinessProbe + "`.");
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
            writeReadinessEvidence(runDir, providerName, contract, "passed", "");
            return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute deployment readiness provider.", e);
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
        writeReadinessEvidence(runDir, providerName, contract, "failed", message);
        return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
    }

    private void writeReadinessEvidence(
            Path runDir,
            String providerName,
            Map<String, Object> contract,
            String status,
            String error) throws IOException {
        Files.createDirectories(runDir);
        StringBuilder builder = new StringBuilder();
        builder.append("status: ").append(status).append("\n");
        builder.append("provider: ").append(providerName).append("\n");
        appendIfPresent(builder, "provider_type", stringValue(contract.get("provider_type")));
        appendIfPresent(builder, "readiness_probe", stringValue(contract.get("readiness_probe")));
        appendIfPresent(builder, "target_selector", stringValue(contract.get("target_selector")));
        appendIfPresent(builder, "deployment_ref", stringValue(contract.get("deployment_ref")));
        appendIfPresent(builder, "service_ref", stringValue(contract.get("service_ref")));
        appendIfPresent(builder, "deployed_version_ref", stringValue(contract.get("deployed_version_ref")));
        builder.append("checks:\n");
        builder.append("  - name: ").append(stringValue(contract.get("readiness_probe"))).append("\n");
        builder.append("    status: ").append(status).append("\n");
        builder.append("check_count: ").append("passed".equals(status) ? 1 : 0).append("\n");
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
