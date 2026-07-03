package com.specdriven.regression.provider.ibmmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IbmMqProviderRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void mqPayloadMatchBrowsesEnvProfileQueueAndWritesEvidence() throws Exception {
        Path suiteRoot = tempDir.resolve("suite");
        Files.createDirectories(suiteRoot.resolve("fixtures"));
        Files.createDirectories(suiteRoot.resolve("expected_results"));
        Files.writeString(suiteRoot.resolve("fixtures/order_request.json"), """
                {
                  "orderId": "ORD-MQ-001",
                  "status": "READY"
                }
                """);
        Files.writeString(suiteRoot.resolve("expected_results/order_request.json"), """
                {
                  "orderId": "ORD-MQ-001",
                  "status": "READY"
                }
                """);
        IbmMqProviderRuntime runtime = new IbmMqProviderRuntime();
        ProviderExecutionContext context = context(suiteRoot);

        ProviderOperationResult put = runtime.execute(context, new ProviderOperationRequest(
                "mq_put",
                List.of(
                        Map.of("bind_as", "correlation_id", "ref", "CORR-MQ-001"),
                        Map.of("bind_as", "payload_ref", "ref", "fixtures/order_request.json")),
                Map.of("_operation_id", "put_order")));
        ProviderOperationResult matched = runtime.execute(context, new ProviderOperationRequest(
                "mq_payload_match",
                List.of(
                        Map.of("bind_as", "expected_ref", "ref", "expected_results/order_request.json"),
                        Map.of("bind_as", "correlation_id", "ref", "CORR-MQ-001")),
                Map.of("_operation_id", "match_order", "_test_start_time", Instant.EPOCH.toString())));

        assertThat(put.passed()).isTrue();
        assertThat(matched.passed()).isTrue();
        assertThat(matched.outputs())
                .containsEntry("queue", "PAYMENT.REQUEST.LOCAL")
                .containsEntry("matched", true)
                .containsEntry("browse_count", 1);
        Path evidence = tempDir.resolve("run/provider-evidence/ibm_mq/match_order.yaml");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("evidence_type: ibm_mq_event")
                .contains("provider_type: ibm_mq")
                .contains("provider_id: payment-mq")
                .contains("queue: PAYMENT.REQUEST.LOCAL")
                .contains("browse_only: true")
                .contains("status: passed")
                .contains("failure_code: ")
                .contains("masking:")
                .contains("raw_secret_found: false");
    }

    @Test
    void mqGetIsExplicitlyUnsupported() throws Exception {
        IbmMqProviderRuntime runtime = new IbmMqProviderRuntime();

        ProviderOperationResult result = runtime.execute(context(tempDir.resolve("suite")), new ProviderOperationRequest(
                "mq_get",
                List.of(),
                Map.of("_operation_id", "destructive_get")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("DESTRUCTIVE_OPERATION_UNSUPPORTED");
        assertThat(result.outputs()).containsKey("mq_evidence_ref");
        assertThat(Files.readString(tempDir.resolve("run/provider-evidence/ibm_mq/destructive_get.yaml")))
                .contains("status: failed")
                .contains("failure_code: DESTRUCTIVE_OPERATION_UNSUPPORTED")
                .contains("browse_only: true")
                .contains("raw_secret_found: false");
    }

    @Test
    void mqNativeModeFailsFastWithoutPretendingQueueManagerRuntimeExists() {
        IbmMqProviderRuntime runtime = new IbmMqProviderRuntime();
        ProviderExecutionContext context = new ProviderExecutionContext(
                "payment-mq",
                "ibm_mq",
                "sit_mq",
                "native",
                tempDir.resolve("suite"),
                tempDir.resolve("run"),
                Map.of("provider_type", "ibm_mq"),
                Map.of("provider_id", "payment-mq", "provider_type", "ibm_mq"),
                Map.of(
                        "queue_manager", "QM.SIT",
                        "channel", "APP.SVRCONN",
                        "conn_name", "mq.example.test(1414)",
                        "queue", "PAYMENT.REQUEST",
                        "credential", Map.of("secret_ref", "vault://sit/mq")));

        ProviderOperationResult result = runtime.execute(context, new ProviderOperationRequest(
                "mq_put",
                List.of(Map.of("bind_as", "payload", "value", "{\"orderId\":\"ORD-MQ-001\"}")),
                Map.of("_operation_id", "native_put")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().code()).isEqualTo("MQ_CONNECTION_FAILED");
        assertThat(result.outputs()).containsKey("mq_evidence_ref");
    }

    private ProviderExecutionContext context(Path suiteRoot) {
        return new ProviderExecutionContext(
                "payment-mq",
                "ibm_mq",
                "local_mq",
                "mock",
                suiteRoot,
                tempDir.resolve("run"),
                Map.of(
                        "provider_type", "ibm_mq",
                        "binding_keys", Map.of(
                                "queue_manager", Map.of("required", true),
                                "channel", Map.of("required", true),
                                "conn_name", Map.of("required", true),
                                "queue", Map.of("required", true),
                                "credential.secret_ref", Map.of("required", true)),
                        "operations", Map.of(
                                "mq_put", Map.of(
                                        "allowed_inputs", List.of("payload_ref", "correlation_id"),
                                        "required_inputs", List.of("payload_ref")),
                                "mq_payload_match", Map.of(
                                        "allowed_inputs", List.of("expected_ref", "correlation_id", "timeout", "poll_interval"),
                                        "required_inputs", List.of("expected_ref")))),
                Map.of("provider_id", "payment-mq", "provider_type", "ibm_mq"),
                Map.of(
                        "queue_manager", "QM.LOCAL",
                        "channel", "APP.SVRCONN",
                        "conn_name", "approved_local_mq_ref",
                        "queue", "PAYMENT.REQUEST.LOCAL",
                        "credential", Map.of("secret_ref", "env://LOCAL_MQ_CREDENTIAL")));
    }
}
