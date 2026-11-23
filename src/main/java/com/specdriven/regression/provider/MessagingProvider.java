package com.specdriven.regression.provider;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MessagingProvider {

    private final MessagingTransport transport;

    public MessagingProvider() {
        this(new DefaultMessagingTransport());
    }

    MessagingProvider(MessagingTransport transport) {
        this.transport = transport;
    }

    public AdapterExecutionResult execute(
            String providerName,
            Path packageRoot,
            Map<String, Object> contract,
            Map<String, Object> testCase,
            List<ResolvedBinding> resolvedBindings,
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput) {
        String providerType = "";
        String actionName = "";
        String connectionRef = "";
        String targetRef = "";
        String targetField = "";
        String mode = "";
        String correlationId = "";
        String payloadBinding = "";
        String cleanupStrategy = "";
        try {
            Files.createDirectories(stdoutLog.getParent());
            Files.createDirectories(stderrLog.getParent());
            Files.createDirectories(actualOutput.getParent());

            providerType = stringValue(contract.get("provider_type"));
            actionName = firstStepAction(testCase);
            Map<?, ?> action = selectedAction(contract, actionName);
            String transportConnection = connectionRef(contract);
            String transportTarget = targetRef(contract);
            targetField = targetField(contract);
            boolean nats = "nats".equalsIgnoreCase(providerType);
            connectionRef = nats ? ProviderEndpoints.natsConnectionEvidenceRef(transportConnection) : transportConnection;
            targetRef = nats ? ProviderEndpoints.natsTargetEvidenceRef(transportTarget, targetField) : transportTarget;
            mode = mode(action);
            correlationId = firstText(action, "correlation_id", "correlation_id_ref", "correlation_key");
            payloadBinding = firstText(action, "payload_binding", "message_binding", "event_binding");
            cleanupStrategy = firstText(action, "cleanup_strategy");
            if (requiresPayload(mode) && payloadBinding.isBlank()) {
                payloadBinding = firstMessageBinding(resolvedBindings);
            }
            if (!isSupportedProvider(providerType)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        providerType,
                        connectionRef,
                        targetRef,
                        targetField,
                        actionName,
                        mode,
                        payloadBinding,
                        correlationId,
                        cleanupStrategy,
                        "Unsupported messaging provider_type `" + providerType + "` for local execution.");
            }
            if (requiresPayload(mode) && !hasResolvedBinding(resolvedBindings, payloadBinding)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        providerType,
                        connectionRef,
                        targetRef,
                        targetField,
                        actionName,
                        mode,
                        payloadBinding,
                        correlationId,
                        cleanupStrategy,
                        "Cannot resolve messaging payload binding `" + payloadBinding + "`.");
            }

            String payload = requiresPayload(mode) ? payload(packageRoot, resolvedBindings, payloadBinding) : "";
            MessagingTransportResult transportResult = transport.execute(new MessagingTransportRequest(
                    providerName,
                    providerType,
                    transportConnection,
                    transportTarget,
                    targetField,
                    actionName,
                    mode,
                    payloadBinding,
                    correlationId,
                    payload,
                    intValue(contract.get("timeout_seconds"), 300),
                    contract,
                    action));
            String diagnostic = nats ? natsSuccessDiagnostic(mode, targetRef, transportResult.messageCount(),
                    transportConnection.startsWith("mock://")) : transportResult.stdout();
            Files.writeString(stdoutLog, diagnostic);
            Files.writeString(stderrLog, "");
            Files.writeString(actualOutput, transportResult.actualOutput());
            writeMessagingEvidence(
                    runDir,
                    providerName,
                    providerType,
                    connectionRef,
                    targetRef,
                    targetField,
                    actionName,
                    mode,
                    payloadBinding,
                    correlationId,
                    cleanupStrategy,
                    actualOutput,
                    "passed",
                    "",
                    transportResult.messageCount());
            return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if ("nats".equalsIgnoreCase(providerType)) throw new IllegalStateException("NATS operation interrupted.");
            throw new IllegalStateException("Messaging provider interrupted.", e);
        } catch (IOException | IllegalArgumentException e) {
            boolean nats = "nats".equalsIgnoreCase(providerType);
            if (!nats && e instanceof IllegalArgumentException invalid) throw invalid;
            String diagnostic = e.getMessage() == null ? "Failed to execute messaging provider." : e.getMessage();
            if (nats) diagnostic = "NATS operation failed. Check the broker binding, subject and action settings.";
            try {
                Files.createDirectories(stdoutLog.getParent());
                Files.createDirectories(stderrLog.getParent());
                Files.createDirectories(actualOutput.getParent());
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        providerType,
                        connectionRef,
                        targetRef,
                        targetField,
                        actionName,
                        mode,
                        payloadBinding,
                        correlationId,
                        cleanupStrategy,
                        diagnostic);
            } catch (IOException writeFailure) {
                throw new UncheckedIOException("Failed to execute messaging provider.", writeFailure);
            }
        }
    }

    // NATS transport text can contain a resolved endpoint or an untrusted broker response.
    private String natsSuccessDiagnostic(String mode, String target, int count, boolean mock) {
        return switch (mode) {
            case "publish" -> "published " + count + (mock ? " message to " : " native message to ") + target + "\n";
            case "request", "request_reply" -> "requested " + count + " native reply from " + target + "\n";
            case "consume", "observe" -> "observed " + count + " native messages from " + target + "\n";
            case "cleanup" -> "cleaned " + count + " native messages from " + target + "\n";
            default -> "NATS operation completed with " + count + " messages.\n";
        };
    }

    private AdapterExecutionResult fail(
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput,
            String providerName,
            String providerType,
            String connectionRef,
            String targetRef,
            String targetField,
            String actionName,
            String mode,
            String payloadBinding,
            String correlationId,
            String cleanupStrategy,
            String message) throws IOException {
        Files.writeString(stdoutLog, "");
        Files.writeString(stderrLog, message);
        Files.writeString(actualOutput, "");
        writeMessagingEvidence(
                runDir,
                providerName,
                providerType,
                connectionRef,
                targetRef,
                targetField,
                actionName,
                mode,
                payloadBinding,
                correlationId,
                cleanupStrategy,
                actualOutput,
                "failed",
                message,
                0);
        return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
    }

    private void writeMessagingEvidence(
            Path runDir,
            String providerName,
            String providerType,
            String connectionRef,
            String targetRef,
            String targetField,
            String actionName,
            String mode,
            String payloadBinding,
            String correlationId,
            String cleanupStrategy,
            Path actualOutput,
            String status,
            String error,
            int messageCount) throws IOException {
        Files.createDirectories(runDir);
        StringBuilder builder = new StringBuilder();
        builder.append("status: ").append(status).append("\n");
        builder.append("provider: ").append(providerName).append("\n");
        builder.append("provider_type: ").append(providerType).append("\n");
        if ("nats".equalsIgnoreCase(providerType)) {
            connectionRef = ProviderEndpoints.yamlEvidenceRef(connectionRef);
            targetRef = ProviderEndpoints.yamlEvidenceRef(targetRef);
        }
        if (!connectionRef.isBlank()) {
            builder.append("connection_ref: ").append(connectionRef).append("\n");
        }
        if (!targetRef.isBlank()) {
            builder.append(targetField).append(": ").append(targetRef).append("\n");
        }
        builder.append("actions:\n");
        builder.append("  - action: ").append(actionName).append("\n");
        builder.append("    mode: ").append(mode.isBlank() ? "publish" : mode).append("\n");
        if (!payloadBinding.isBlank()) {
            builder.append("    payload_binding: ").append(payloadBinding).append("\n");
        }
        if (!correlationId.isBlank()) {
            builder.append("    correlation_id: ").append(correlationId).append("\n");
        }
        if (!cleanupStrategy.isBlank()) {
            builder.append("    cleanup_strategy: ").append(cleanupStrategy).append("\n");
        }
        builder.append("    message_count: ").append(messageCount).append("\n");
        builder.append("    actual_output: ").append(runDir.relativize(actualOutput)).append("\n");
        if (!error.isBlank()) {
            builder.append("error: ").append(error).append("\n");
        }
        Files.writeString(runDir.resolve("messaging.yaml"), builder.toString());
    }

    private Map<?, ?> selectedAction(Map<String, Object> contract, String actionName) {
        Object actions = contract.get("actions");
        if (!(actions instanceof Map<?, ?> actionMap) || actionMap.isEmpty()) {
            return Map.of();
        }
        if (!actionName.isBlank() && actionMap.get(actionName) instanceof Map<?, ?> action) {
            return action;
        }
        Object firstAction = actionMap.values().iterator().next();
        return firstAction instanceof Map<?, ?> action ? action : Map.of();
    }

    private String payload(Path packageRoot, List<ResolvedBinding> resolvedBindings, String bindingName)
            throws IOException {
        for (ResolvedBinding binding : resolvedBindings) {
            if (binding.bindingName().equals(bindingName)) {
                return Files.readString(packageRoot.resolve(binding.ref()));
            }
        }
        return "";
    }

    private boolean hasResolvedBinding(List<ResolvedBinding> resolvedBindings, String bindingName) {
        for (ResolvedBinding binding : resolvedBindings) {
            if (binding.bindingName().equals(bindingName)) {
                return true;
            }
        }
        return false;
    }

    private String firstStepAction(Map<String, Object> testCase) {
        Object steps = testCase.get("steps");
        if (steps instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> step) {
            return stringValue(step.get("action"));
        }
        return "";
    }

    private String firstMessageBinding(List<ResolvedBinding> resolvedBindings) {
        for (ResolvedBinding binding : resolvedBindings) {
            if ("message_event".equals(binding.bindingType())) {
                return binding.bindingName();
            }
        }
        return "";
    }

    private String targetRef(Map<String, Object> contract) {
        return firstText(contract, "topic_ref", "subject_ref", "stream_ref", "endpoint_ref");
    }

    private String targetField(Map<String, Object> contract) {
        if (!stringValue(contract.get("topic_ref")).isBlank()) {
            return "topic_ref";
        }
        if (!stringValue(contract.get("subject_ref")).isBlank()) {
            return "subject_ref";
        }
        if (!stringValue(contract.get("stream_ref")).isBlank()) {
            return "stream_ref";
        }
        return "endpoint_ref";
    }

    private String connectionRef(Map<String, Object> contract) {
        return firstText(contract, "bootstrap_servers_ref", "server_ref", "connection_ref", "endpoint_ref");
    }

    private boolean isSupportedProvider(String providerType) {
        String normalized = providerType.toLowerCase(Locale.ROOT);
        return normalized.equals("local")
                || normalized.equals("mock")
                || normalized.equals("kafka")
                || normalized.equals("nats");
    }

    private String mode(Map<?, ?> action) {
        String mode = stringValue(action.get("mode"));
        return mode.isBlank() ? "publish" : mode.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean requiresPayload(String mode) {
        return mode.isBlank()
                || "publish".equals(mode)
                || "request".equals(mode)
                || "request_reply".equals(mode);
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

    private String firstText(Map<?, ?> map, String... fields) {
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
