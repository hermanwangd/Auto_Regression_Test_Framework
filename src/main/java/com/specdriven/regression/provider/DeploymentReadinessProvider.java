package com.specdriven.regression.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import com.specdriven.regression.adapter.AdapterExecutionResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DeploymentReadinessProvider {

    private static final Pattern K8S = Pattern.compile("k8s", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL = Pattern.compile("local|mock", Pattern.CASE_INSENSITIVE);
    private static final Pattern NATIVE = Pattern.compile("k8s|vm", Pattern.CASE_INSENSITIVE);

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
            ProviderDataYaml.createParent(stdoutLog);
            ProviderDataYaml.createParent(stderrLog);
            ProviderDataYaml.createParent(actualOutput);

            String providerType = stringValue(contract.get("provider_type"));
            String readinessProbeName = stringValue(contract.get("readiness_probe"));
            String deploymentRef = firstText(contract, "deployment_ref", "service_ref", "target_selector");
            if (isNativeProvider(providerType) && usesMockNativeReadiness(contract)) {
                if (isK8sApiProbe(contract)) {
                    deploymentRef = stringValue(contract.get("deployment_ref"));
                    K8sApiResource.NAMESPACE.checkReference(stringValue(contract.get("namespace_ref")));
                    K8sApiResource.DEPLOYMENT.checkReference(deploymentRef);
                }
                Files.writeString(stdoutLog, ProviderDiagnosticRedactor.maskSensitiveText(
                        providerType + " mock readiness passed for " + deploymentRef + "\n"));
                Files.writeString(stderrLog, "");
                Files.writeString(actualOutput, "ready\n");
                writeReadinessEvidence(runDir, providerName, contract, "passed", "", 1);
                return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
            }
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
                if (!ProviderDiagnosticRedactor.canPersistObservation(probeResult.actualOutput())) {
                    return fail(runDir, stdoutLog, stderrLog, actualOutput, providerName, contract,
                            "Readiness observation withheld by the evidence redaction policy.");
                }
                Files.writeString(stdoutLog, ProviderDiagnosticRedactor.maskSensitiveText(probeResult.stdout()));
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
            if (!markerExists(packageRoot, deploymentRef)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        contract,
                        "Deployment readiness marker not found: `" + deploymentRef + "`.");
            }

            Files.writeString(stdoutLog, ProviderDiagnosticRedactor.maskSensitiveText(
                    "readiness passed for " + deploymentRef + "\n"));
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
        message = ProviderDiagnosticRedactor.maskSensitiveText(message);
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
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", status);
        evidence.put("provider", ProviderDiagnosticRedactor.maskSensitiveText(providerName));
        for (String field : List.of("provider_type", "readiness_probe", "api_server_ref", "kube_context_ref",
                "namespace_ref", "pod_ref", "target_selector", "deployment_ref", "service_ref", "log_tail_lines",
                "host_ref", "port", "ssh_ref", "winrm_ref", "user_ref", "command_ref", "health_url_ref", "deployed_version_ref")) {
            Object raw = contract.get(field);
            String value = stringValue(raw);
            if (value.isBlank()) continue;
            if (field.equals("api_server_ref") || field.equals("health_url_ref")) value = ProviderEndpoints.httpEvidenceRef(value);
            if (isK8sApiProbe(contract)) {
                if (field.equals("namespace_ref")) value = K8sApiResource.NAMESPACE.evidenceRef(value);
                if (field.equals("deployment_ref")) value = K8sApiResource.DEPLOYMENT.evidenceRef(value);
            }
            evidence.put(field, raw instanceof Number ? raw : ProviderDiagnosticRedactor.maskSensitiveText(value));
        }
        evidence.put("checks", List.of(Map.of("name", evidence.getOrDefault("readiness_probe", ""), "status", status)));
        evidence.put("check_count", checkCount);
        if (!error.isBlank()) evidence.put("error", error);
        ProviderDataYaml.writeEvidence(runDir.resolve("readiness.yaml"), evidence);
    }

    private boolean markerExists(Path root, String ref) throws IOException {
        if (ref.isBlank()) return false;
        try (var marker = SuiteAssetReader.open(root, ref, "Marker must be inside the RP package.")) {
            return true;
        } catch (java.nio.file.NoSuchFileException | IllegalArgumentException missing) {
            return false;
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

    private boolean isK8sApiProbe(Map<String, Object> contract) {
        return K8S.matcher(stringValue(contract.get("provider_type"))).matches()
                && "api_deployment_available".equals(contract.get("readiness_probe"));
    }

    private boolean isLocalProvider(String providerType) {
        return LOCAL.matcher(providerType).matches();
    }

    private boolean isNativeProvider(String providerType) {
        return NATIVE.matcher(providerType).matches();
    }

    private boolean usesMockNativeReadiness(Map<String, Object> contract) {
        for (String field : java.util.List.of(
                "api_server_ref",
                "endpoint_ref",
                "kube_context_ref",
                "connection_ref",
                "host_ref",
                "health_url_ref",
                "ssh_ref",
                "winrm_ref")) {
            if (stringValue(contract.get(field)).startsWith("mock://")) {
                return true;
            }
        }
        return false;
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
