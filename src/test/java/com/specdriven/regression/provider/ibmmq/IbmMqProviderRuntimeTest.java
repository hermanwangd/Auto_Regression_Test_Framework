package com.specdriven.regression.provider.ibmmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.ibm.mq.constants.MQConstants;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IbmMqProviderRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeClientUsesMqCspAuthenticationWhenCredentialHasPassword() throws Exception {
        Class<?> transport = Class.forName(
                "com.specdriven.regression.provider.ibmmq.IbmMqProviderRuntime$DefaultIbmMqClientTransport");
        Method propertiesMethod = transport.getDeclaredMethod(
                "connectionProperties", IbmMqProviderRuntime.MqConnection.class);
        propertiesMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Hashtable<String, Object> properties = (Hashtable<String, Object>) propertiesMethod.invoke(
                null,
                new IbmMqProviderRuntime.MqConnection(
                        "QM1", "DEV.APP.SVRCONN", "localhost(1414)", "PAYMENTS.V03", "app:password"));

        assertThat(properties)
                .containsEntry(MQConstants.USE_MQCSP_AUTHENTICATION_PROPERTY, Boolean.TRUE);
    }

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
    void mqPutGeneratesCorrelationIdWhenMissingSoVerifyCanAvoidStaleMessages() throws Exception {
        Path suiteRoot = tempDir.resolve("suite");
        Files.createDirectories(suiteRoot.resolve("expected_results"));
        Files.writeString(suiteRoot.resolve("expected_results/current_order.json"), """
                {
                  "orderId": "ORD-MQ-CURRENT",
                  "status": "READY"
                }
                """);
        IbmMqProviderRuntime runtime = new IbmMqProviderRuntime();
        ProviderExecutionContext context = context(suiteRoot);

        ProviderOperationResult stale = runtime.execute(context, new ProviderOperationRequest(
                "mq_put",
                List.of(
                        Map.of("bind_as", "correlation_id", "ref", "CORR-MQ-001"),
                        Map.of("bind_as", "payload", "value", "{\"orderId\":\"ORD-MQ-STALE\",\"status\":\"READY\"}")),
                Map.of("_operation_id", "put_stale")));
        ProviderOperationResult current = runtime.execute(context, new ProviderOperationRequest(
                "mq_put",
                List.of(Map.of("bind_as", "payload", "value", "{\"orderId\":\"ORD-MQ-CURRENT\",\"status\":\"READY\"}")),
                Map.of("_operation_id", "put_current")));
        String currentCorrelationId = String.valueOf(current.outputs().get("correlation_id"));
        ProviderOperationResult matched = runtime.execute(context, new ProviderOperationRequest(
                "mq_payload_match",
                List.of(
                        Map.of("bind_as", "expected_ref", "ref", "expected_results/current_order.json"),
                        Map.of("bind_as", "correlation_id", "ref", currentCorrelationId)),
                Map.of("_operation_id", "match_current", "_test_start_time", Instant.EPOCH.toString())));

        assertThat(stale.passed()).isTrue();
        assertThat(current.passed()).isTrue();
        assertThat(currentCorrelationId)
                .startsWith("CORR-")
                .isNotEqualTo("CORR-MQ-001");
        assertThat(matched.passed()).isTrue();
        assertThat(matched.outputs()).containsEntry("browse_count", 1);
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
    void mqNativeModeFailsFastWithOwnerActionableConnectionFailure() {
        IbmMqProviderRuntime runtime = new IbmMqProviderRuntime(new FailingIbmMqClientTransport("queue manager unavailable"));
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
        assertThat(result.failure().reason())
                .contains("IBM MQ client operation failed")
                .doesNotContain("queue manager unavailable");
        assertThat(result.outputs()).containsKey("mq_evidence_ref");
    }

    @Test
    void mqNativeModeUsesExternalClientTransportWithoutStartingQueueManager() throws Exception {
        Path suiteRoot = tempDir.resolve("suite");
        Files.createDirectories(suiteRoot.resolve("expected_results"));
        Files.writeString(suiteRoot.resolve("expected_results/order_request.json"), """
                {
                  "orderId": "ORD-MQ-001",
                  "status": "READY"
                }
                """);
        System.setProperty("IBM_MQ_CONN_NAME", "mq.example.test(1414)");
        System.setProperty("IBM_MQ_CREDENTIAL", "secret-ref-materialized-outside-framework");
        try {
            RecordingIbmMqClientTransport transport = new RecordingIbmMqClientTransport();
            IbmMqProviderRuntime runtime = new IbmMqProviderRuntime(transport);
            ProviderExecutionContext context = new ProviderExecutionContext(
                    "payment-mq",
                    "ibm_mq",
                    "ci_ibm_mq_external",
                    "native",
                    suiteRoot,
                    tempDir.resolve("run"),
                    Map.of("provider_type", "ibm_mq"),
                    Map.of("provider_id", "payment-mq", "provider_type", "ibm_mq"),
                    Map.of(
                            "queue_manager", "QM1",
                            "channel", "DEV.APP.SVRCONN",
                            "conn_name", Map.of("secret_ref", "env://IBM_MQ_CONN_NAME"),
                            "queue", "PAYMENT.REQUEST.CI",
                            "credential.secret_ref", Map.of("secret_ref", "env://IBM_MQ_CREDENTIAL")));

            ProviderOperationResult put = runtime.execute(context, new ProviderOperationRequest(
                    "mq_put",
                    List.of(
                            Map.of("bind_as", "correlation_id", "ref", "CORR-MQ-001"),
                            Map.of("bind_as", "payload", "value", "{\"orderId\":\"ORD-MQ-001\",\"status\":\"READY\"}")),
                    Map.of("_operation_id", "native_put")));
            ProviderOperationResult matched = runtime.execute(context, new ProviderOperationRequest(
                    "mq_payload_match",
                    List.of(
                            Map.of("bind_as", "expected_ref", "ref", "expected_results/order_request.json"),
                            Map.of("bind_as", "correlation_id", "ref", "CORR-MQ-001")),
                    Map.of("_operation_id", "native_match", "_test_start_time", Instant.EPOCH.toString())));

            assertThat(put.passed()).isTrue();
            assertThat(matched.passed()).isTrue();
            assertThat(transport.queueManager()).isEqualTo("QM1");
            assertThat(transport.channel()).isEqualTo("DEV.APP.SVRCONN");
            assertThat(transport.connName()).isEqualTo("mq.example.test(1414)");
            assertThat(transport.queue()).isEqualTo("PAYMENT.REQUEST.CI");
            assertThat(matched.outputs())
                    .containsEntry("queue", "PAYMENT.REQUEST.CI")
                    .containsEntry("matched", true)
                    .containsEntry("browse_count", 1);
            assertThat(Files.readString(tempDir.resolve("run/provider-evidence/ibm_mq/native_match.yaml")))
                    .contains("runtime_mode: native")
                    .contains("provider_type: ibm_mq")
                    .contains("provider_id: payment-mq")
                    .contains("queue: PAYMENT.REQUEST.CI")
                    .contains("status: passed")
                    .contains("raw_secret_found: false");
        } finally {
            System.clearProperty("IBM_MQ_CONN_NAME");
            System.clearProperty("IBM_MQ_CREDENTIAL");
        }
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

    private static final class RecordingIbmMqClientTransport implements IbmMqProviderRuntime.IbmMqClientTransport {
        private String queueManager = "";
        private String channel = "";
        private String connName = "";
        private String queue = "";
        private Object payload = Map.of();
        private String correlationId = "";

        @Override
        public IbmMqProviderRuntime.MqPutResult put(
                IbmMqProviderRuntime.MqConnection connection,
                Object payload,
                String messageId,
                String correlationId,
                java.time.Duration timeout) {
            this.queueManager = connection.queueManager();
            this.channel = connection.channel();
            this.connName = connection.connName();
            this.queue = connection.queue();
            this.payload = payload;
            this.correlationId = correlationId;
            return new IbmMqProviderRuntime.MqPutResult(
                    "ID:414d51204d5144455620202020202020",
                    correlationId,
                    Instant.parse("2026-07-05T00:00:00Z"));
        }

        @Override
        public IbmMqProviderRuntime.MqBrowseResult browse(
                IbmMqProviderRuntime.MqConnection connection,
                String correlationId,
                java.time.Duration timeout,
                java.time.Duration pollInterval) {
            this.queueManager = connection.queueManager();
            this.channel = connection.channel();
            this.connName = connection.connName();
            this.queue = connection.queue();
            return new IbmMqProviderRuntime.MqBrowseResult(List.of(new IbmMqProviderRuntime.MqMessage(
                    connection.queue(),
                    "ID:414d51204d5144455620202020202020",
                    this.correlationId,
                    payload,
                    Instant.parse("2026-07-05T00:00:01Z"))), 1);
        }

        String queueManager() {
            return queueManager;
        }

        String channel() {
            return channel;
        }

        String connName() {
            return connName;
        }

        String queue() {
            return queue;
        }
    }

    private static final class FailingIbmMqClientTransport implements IbmMqProviderRuntime.IbmMqClientTransport {
        private final String message;

        private FailingIbmMqClientTransport(String message) {
            this.message = message;
        }

        @Override
        public IbmMqProviderRuntime.MqPutResult put(
                IbmMqProviderRuntime.MqConnection connection,
                Object payload,
                String messageId,
                String correlationId,
                java.time.Duration timeout) throws Exception {
            throw new Exception(message);
        }

        @Override
        public IbmMqProviderRuntime.MqBrowseResult browse(
                IbmMqProviderRuntime.MqConnection connection,
                String correlationId,
                java.time.Duration timeout,
                java.time.Duration pollInterval) throws Exception {
            throw new Exception(message);
        }
    }
}
