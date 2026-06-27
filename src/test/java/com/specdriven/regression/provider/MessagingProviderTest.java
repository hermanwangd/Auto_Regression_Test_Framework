package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.binding.ResolvedBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void failsUnsupportedRuntimeProviderTypeWithMessagingEvidence() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-001\"}\n");

        AdapterExecutionResult result = executeProvider(
                "payment_events",
                Map.of(
                        "provider_type", "kafka",
                        "topic_ref", "kafka://payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(result.stderrLog()))
                .contains("Unsupported messaging provider_type `kafka`");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: failed")
                .contains("provider_type: kafka")
                .contains("topic_ref: kafka://payment.events")
                .contains("message_count: 0")
                .contains("payload_binding: payment_event");
    }

    @Test
    void usesFirstMessageEventBindingWhenActionDoesNotDeclarePayloadBinding() throws Exception {
        writePayload("fixtures/events/payment.json", "{\"eventId\":\"EVT-002\"}\n");

        AdapterExecutionResult result = executeProvider(
                "payment_events",
                Map.of(
                        "provider_type", "local",
                        "subject_ref", "mock://payment.events",
                        "actions", Map.of(
                                "publish_payment_event", Map.of("mode", "publish"))),
                Map.of("steps", List.of(Map.of("action", "publish_payment_event"))),
                List.of(new ResolvedBinding("payment_event", "message_event", "fixtures/events/payment.json")));

        Path runDir = tempDir.resolve("run");
        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(result.stdoutLog()))
                .contains("published 1 message to mock://payment.events");
        assertThat(Files.readString(result.actualOutput())).isEqualTo("{\"eventId\":\"EVT-002\"}\n");
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("status: passed")
                .contains("provider_type: local")
                .contains("topic_ref: mock://payment.events")
                .contains("payload_binding: payment_event")
                .contains("message_count: 1");
    }

    private AdapterExecutionResult executeProvider(
            String providerName,
            Map<String, Object> contract,
            Map<String, Object> testCase,
            List<ResolvedBinding> bindings) {
        Path runDir = tempDir.resolve("run");
        return new MessagingProvider().execute(
                providerName,
                tempDir,
                contract,
                testCase,
                bindings,
                runDir,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/message.json"));
    }

    private void writePayload(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
