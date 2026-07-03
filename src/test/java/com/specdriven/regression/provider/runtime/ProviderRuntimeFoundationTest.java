package com.specdriven.regression.provider.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderRuntimeFoundationTest {

    @Test
    void registryResolvesRuntimeByProviderTypeAndRejectsUnknownProviderType() {
        ProviderRuntime runtime = (context, request) -> ProviderOperationResult.passed(
                Map.of("ok", true),
                List.of(new ProviderEvidence("test", "evidence.yaml", true)));
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(Map.of("wiremock_http_mock", runtime));

        assertThat(registry.resolve("wiremock_http_mock")).isSameAs(runtime);
        assertThat(registry.tryResolve("missing_provider").failure())
                .isNotNull()
                .extracting(ProviderFailure::code)
                .isEqualTo("UNKNOWN_PROVIDER_TYPE");
    }

    @Test
    void resolverRejectsUnsupportedOperationUnsupportedInputAndMissingBindingKeys() {
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(Map.of(
                "wiremock_http_mock",
                (context, request) -> ProviderOperationResult.passed(Map.of(), List.of())));
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(registry);
        ProviderExecutionContext context = new ProviderExecutionContext(
                "wiremock-payment-api",
                "wiremock_http_mock",
                "local_wiremock",
                "mock",
                Path.of("."),
                Path.of("target/provider-capability-test"),
                Map.of(
                        "provider_type", "wiremock_http_mock",
                        "binding_keys", Map.of("mappings_ref", Map.of("required", true)),
                        "operations", Map.of(
                                "load_stubs", Map.of(
                                        "allowed_inputs", List.of("mock.mappings_ref"),
                                        "required_inputs", List.of("mock.mappings_ref")))),
                Map.of("provider_id", "wiremock-payment-api", "provider_type", "wiremock_http_mock"),
                Map.of("mappings_ref", "fixtures/payment_success_stub.json"));

        assertThat(resolver.resolve(context, new ProviderOperationRequest("load_stubs", List.of(
                        Map.of("bind_as", "mock.mappings_ref", "ref", "fixtures/payment_success_stub.json")), Map.of()))
                .valid()).isTrue();
        assertThat(resolver.resolve(context, new ProviderOperationRequest("query_database", List.of(), Map.of()))
                .failure().code()).isEqualTo("UNSUPPORTED_OPERATION");
        ProviderFailure unsupportedInput = resolver.resolve(context, new ProviderOperationRequest("load_stubs", List.of(
                        Map.of("bind_as", "db.sql_ref", "ref", "queries/order.sql")), Map.of()))
                .failure();
        assertThat(unsupportedInput.code()).isEqualTo("UNSUPPORTED_INPUT");
        assertThat(unsupportedInput.reason()).contains("input `db.sql_ref`").doesNotContain("bind_as");
        assertThat(unsupportedInput.ownerAction()).contains("input declared by the Provider Contract operation")
                .doesNotContain("bind_as");
        ProviderFailure missingInput = resolver.resolve(context, new ProviderOperationRequest("load_stubs", List.of(), Map.of()))
                .failure();
        assertThat(missingInput.code()).isEqualTo("MISSING_REQUIRED_INPUT");
        assertThat(missingInput.ownerAction()).contains("Add input `mock.mappings_ref`").doesNotContain("bind_as");

        ProviderExecutionContext missingBinding = new ProviderExecutionContext(
                context.providerId(),
                context.providerType(),
                context.profile(),
                context.runtimeMode(),
                context.suiteRoot(),
                context.runDir(),
                context.providerContract(),
                context.providerInstance(),
                Map.of());
        assertThat(resolver.resolve(missingBinding, new ProviderOperationRequest("load_stubs", List.of(
                        Map.of("bind_as", "mock.mappings_ref", "ref", "fixtures/payment_success_stub.json")), Map.of()))
                .failure().code()).isEqualTo("MISSING_BINDING_KEY");
    }

    @Test
    void resolverRejectsMessagingDestinationOverridesFromOperationInputs() {
        ProviderRuntime runtime = (context, request) -> ProviderOperationResult.passed(Map.of(), List.of());
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(new ProviderRuntimeRegistry(Map.of(
                "kafka", runtime,
                "ibm_mq", runtime)));

        ProviderFailure topicOverride = resolver.resolve(kafkaContext(), new ProviderOperationRequest(
                        "kafka_payload_match",
                        List.of(
                                Map.of("bind_as", "topic", "ref", "orders.override"),
                                Map.of("bind_as", "expected_ref", "ref", "expected/order.json")),
                        Map.of()))
                .failure();
        ProviderFailure queueOverride = resolver.resolve(ibmMqContext(), new ProviderOperationRequest(
                        "mq_payload_match",
                        List.of(
                                Map.of("bind_as", "queue", "ref", "OVERRIDE.QUEUE"),
                                Map.of("bind_as", "expected_ref", "ref", "expected/order.json")),
                        Map.of()))
                .failure();

        assertThat(topicOverride.code()).isEqualTo("UNSUPPORTED_INPUT");
        assertThat(topicOverride.reason()).contains("input `topic`");
        assertThat(queueOverride.code()).isEqualTo("UNSUPPORTED_INPUT");
        assertThat(queueOverride.reason()).contains("input `queue`");
    }

    @Test
    void resolverRejectsRuntimeModeNotExecutableByProviderContractBeforeDispatch() {
        ProviderRuntime runtime = (context, request) -> ProviderOperationResult.passed(Map.of(), List.of());
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(new ProviderRuntimeRegistry(Map.of("kafka", runtime)));
        ProviderExecutionContext context = new ProviderExecutionContext(
                "order-events",
                "kafka",
                "sit_kafka",
                "native",
                Path.of("."),
                Path.of("target/provider-capability-test"),
                Map.of(
                        "provider_type", "kafka",
                        "runtime_modes", List.of("native", "mock", "ephemeral"),
                        "executable_runtime_modes", List.of("mock"),
                        "binding_keys", Map.of(
                                "bootstrap_servers", Map.of("required", true),
                                "topic", Map.of("required", true),
                                "consumer_group", Map.of("required", true)),
                        "operations", Map.of(
                                "kafka_publish", Map.of(
                                        "allowed_inputs", List.of("payload_ref"),
                                        "required_inputs", List.of("payload_ref")))),
                Map.of("provider_id", "order-events", "provider_type", "kafka"),
                Map.of(
                        "bootstrap_servers", "broker:9092",
                        "topic", "orders.created",
                        "consumer_group", "artf-sit"));

        ProviderFailure failure = resolver.resolve(context, new ProviderOperationRequest("kafka_publish", List.of(
                        Map.of("bind_as", "payload_ref", "ref", "fixtures/order_event.json")), Map.of()))
                .failure();

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo("UNSUPPORTED_RUNTIME_MODE");
        assertThat(failure.reason()).contains("runtime_mode `native`").contains("provider_type `kafka`");
        assertThat(failure.ownerAction()).contains("executable_runtime_modes").contains("[mock]");
    }

    private ProviderExecutionContext kafkaContext() {
        return new ProviderExecutionContext(
                "order-events",
                "kafka",
                "local_kafka",
                "mock",
                Path.of("."),
                Path.of("target/provider-capability-test"),
                Map.of(
                        "provider_type", "kafka",
                        "binding_keys", Map.of(
                                "bootstrap_servers", Map.of("required", true),
                                "topic", Map.of("required", true),
                                "consumer_group", Map.of("required", true)),
                        "operations", Map.of(
                                "kafka_payload_match", Map.of(
                                        "allowed_inputs", List.of("expected_ref", "key", "consume_from"),
                                        "required_inputs", List.of("expected_ref")))),
                Map.of("provider_id", "order-events", "provider_type", "kafka"),
                Map.of(
                        "bootstrap_servers", "approved_local_kafka_ref",
                        "topic", "orders.created",
                        "consumer_group", "artf-local"));
    }

    private ProviderExecutionContext ibmMqContext() {
        return new ProviderExecutionContext(
                "payment-mq",
                "ibm_mq",
                "local_mq",
                "mock",
                Path.of("."),
                Path.of("target/provider-capability-test"),
                Map.of(
                        "provider_type", "ibm_mq",
                        "binding_keys", Map.of(
                                "queue_manager", Map.of("required", true),
                                "channel", Map.of("required", true),
                                "conn_name", Map.of("required", true),
                                "queue", Map.of("required", true),
                                "credential.secret_ref", Map.of("required", true)),
                        "operations", Map.of(
                                "mq_payload_match", Map.of(
                                        "allowed_inputs", List.of("expected_ref", "correlation_id"),
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
