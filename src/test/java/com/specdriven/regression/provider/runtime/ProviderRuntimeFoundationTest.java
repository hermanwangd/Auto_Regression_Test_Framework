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
    void resolverRejectsUnsupportedOperationUnsupportedBindAsAndMissingBindingKeys() {
        ProviderRuntimeRegistry registry = new ProviderRuntimeRegistry(Map.of(
                "wiremock_http_mock",
                (context, request) -> ProviderOperationResult.passed(Map.of(), List.of())));
        ProviderRuntimeResolver resolver = new ProviderRuntimeResolver(registry);
        ProviderExecutionContext context = new ProviderExecutionContext(
                "wiremock-payment-api",
                "wiremock_http_mock",
                "local_wiremock",
                Path.of("."),
                Path.of("target/provider-capability-test"),
                Map.of(
                        "provider_type", "wiremock_http_mock",
                        "binding_keys", Map.of("mappings_ref", Map.of("required", true)),
                        "operations", Map.of(
                                "load_stubs", Map.of(
                                        "allowed_bind_as", List.of("mock.mappings_ref"),
                                        "required_parameters", List.of("mock.mappings_ref")))),
                Map.of("provider_id", "wiremock-payment-api", "provider_type", "wiremock_http_mock"),
                Map.of("mappings_ref", "fixtures/payment_success_stub.json"));

        assertThat(resolver.resolve(context, new ProviderOperationRequest("load_stubs", List.of(
                        Map.of("bind_as", "mock.mappings_ref", "ref", "fixtures/payment_success_stub.json")), Map.of()))
                .valid()).isTrue();
        assertThat(resolver.resolve(context, new ProviderOperationRequest("query_database", List.of(), Map.of()))
                .failure().code()).isEqualTo("UNSUPPORTED_OPERATION");
        assertThat(resolver.resolve(context, new ProviderOperationRequest("load_stubs", List.of(
                        Map.of("bind_as", "db.sql_ref", "ref", "queries/order.sql")), Map.of()))
                .failure().code()).isEqualTo("UNSUPPORTED_BIND_AS");
        assertThat(resolver.resolve(context, new ProviderOperationRequest("load_stubs", List.of(), Map.of()))
                .failure().code()).isEqualTo("MISSING_REQUIRED_PARAMETER");

        ProviderExecutionContext missingBinding = new ProviderExecutionContext(
                context.providerId(),
                context.providerType(),
                context.profile(),
                context.suiteRoot(),
                context.runDir(),
                context.providerContract(),
                context.providerInstance(),
                Map.of());
        assertThat(resolver.resolve(missingBinding, new ProviderOperationRequest("load_stubs", List.of(
                        Map.of("bind_as", "mock.mappings_ref", "ref", "fixtures/payment_success_stub.json")), Map.of()))
                .failure().code()).isEqualTo("MISSING_BINDING_KEY");
    }
}
