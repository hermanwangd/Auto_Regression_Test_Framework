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
        try {
            Files.createDirectories(stdoutLog.getParent());
            Files.createDirectories(stderrLog.getParent());
            Files.createDirectories(actualOutput.getParent());

            String providerType = stringValue(contract.get("provider_type"));
            String actionName = firstStepAction(testCase);
            Map<?, ?> action = selectedAction(contract, actionName);
            String payloadBinding = firstText(action, "payload_binding", "message_binding", "event_binding");
            if (payloadBinding.isBlank()) {
                payloadBinding = firstMessageBinding(resolvedBindings);
            }
            if (!isLocalProvider(providerType)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        providerType,
                        topicRef(contract),
                        actionName,
                        stringValue(action.get("mode")),
                        payloadBinding,
                        "Unsupported messaging provider_type `" + providerType + "` for local execution.");
            }
            if (!hasResolvedBinding(resolvedBindings, payloadBinding)) {
                return fail(
                        runDir,
                        stdoutLog,
                        stderrLog,
                        actualOutput,
                        providerName,
                        providerType,
                        topicRef(contract),
                        actionName,
                        stringValue(action.get("mode")),
                        payloadBinding,
                        "Cannot resolve messaging payload binding `" + payloadBinding + "`.");
            }

            String payload = payload(packageRoot, resolvedBindings, payloadBinding);
            Files.writeString(stdoutLog, "published 1 message to " + topicRef(contract) + "\n");
            Files.writeString(stderrLog, "");
            Files.writeString(actualOutput, payload);
            writeMessagingEvidence(
                    runDir,
                    providerName,
                    providerType,
                    topicRef(contract),
                    actionName,
                    stringValue(action.get("mode")),
                    payloadBinding,
                    actualOutput,
                    "passed",
                    "");
            return new AdapterExecutionResult(0, false, stdoutLog, stderrLog, actualOutput);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute messaging provider.", e);
        }
    }

    private AdapterExecutionResult fail(
            Path runDir,
            Path stdoutLog,
            Path stderrLog,
            Path actualOutput,
            String providerName,
            String providerType,
            String topicRef,
            String actionName,
            String mode,
            String payloadBinding,
            String message) throws IOException {
        Files.writeString(stdoutLog, "");
        Files.writeString(stderrLog, message);
        Files.writeString(actualOutput, "");
        writeMessagingEvidence(
                runDir,
                providerName,
                providerType,
                topicRef,
                actionName,
                mode,
                payloadBinding,
                actualOutput,
                "failed",
                message);
        return new AdapterExecutionResult(1, false, stdoutLog, stderrLog, actualOutput);
    }

    private void writeMessagingEvidence(
            Path runDir,
            String providerName,
            String providerType,
            String topicRef,
            String actionName,
            String mode,
            String payloadBinding,
            Path actualOutput,
            String status,
            String error) throws IOException {
        Files.createDirectories(runDir);
        StringBuilder builder = new StringBuilder();
        builder.append("status: ").append(status).append("\n");
        builder.append("provider: ").append(providerName).append("\n");
        builder.append("provider_type: ").append(providerType).append("\n");
        if (!topicRef.isBlank()) {
            builder.append("topic_ref: ").append(topicRef).append("\n");
        }
        builder.append("actions:\n");
        builder.append("  - action: ").append(actionName).append("\n");
        builder.append("    mode: ").append(mode.isBlank() ? "publish" : mode).append("\n");
        builder.append("    payload_binding: ").append(payloadBinding).append("\n");
        builder.append("    message_count: ").append("passed".equals(status) ? 1 : 0).append("\n");
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

    private String topicRef(Map<String, Object> contract) {
        return firstText(contract, "topic_ref", "subject_ref", "stream_ref", "endpoint_ref");
    }

    private boolean isLocalProvider(String providerType) {
        String normalized = providerType.toLowerCase(Locale.ROOT);
        return normalized.equals("local") || normalized.equals("mock");
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
