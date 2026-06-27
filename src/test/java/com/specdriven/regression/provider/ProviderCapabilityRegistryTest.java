package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderCapabilityRegistryTest {

    private final ProviderCapabilityRegistry registry = new ProviderCapabilityRegistry();

    @Test
    void rejectsUnsupportedExecutionModeBeforeRuntimeSelection() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "request_response",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "rest",
                        "endpoint_ref", "http://127.0.0.1:8080",
                        "timeout_seconds", 5,
                        "outputs", Map.of("actual_output_ref", "actual/response.json"),
                        "actions", Map.of("submit", Map.of("method", "POST", "path", "/submit"))),
                "qa_sandbox");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.registryStatus()).isEqualTo("unsupported_execution_mode");
        assertThat(validation.runtimeStatus()).isEqualTo("blocked");
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".execution_mode");
    }

    @Test
    void acceptsSupportedAlternativeEndpointAndOutputReferences() {
        ProviderCapabilityRegistry.ProviderContractValidation messaging = registry.validate(
                "adapters",
                "message_bus",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "mock",
                        "stream_ref", "mock://payment.events",
                        "timeout_seconds", "10",
                        "outputs", Map.of("actual_output_ref", "actual/events.json")),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation deploymentReadiness = registry.validate(
                "adapters",
                "deployment_readiness",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "local",
                        "readiness_probe", "http_get",
                        "target_selector", "app=payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.json")),
                "sit_deployed");

        assertThat(messaging.ready()).isTrue();
        assertThat(deploymentReadiness.ready()).isTrue();
    }

    @Test
    void blocksInvalidTimeoutAndMissingActionMapForRequestResponseProvider() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "request_response",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "rest",
                        "base_url_ref", "env://PAYMENT_API",
                        "timeout_seconds", "not-a-number",
                        "outputs", Map.of("actual_output_ref", "actual/response.json")),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".timeout_seconds",
                        ".actions");
    }

    @Test
    void blocksUnsafeDbFixtureSqlDefinitionsAndPaths() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "fixtures",
                "relational_db",
                Map.of(
                        "provider_family", "db_fixture",
                        "provider_type", "jdbc",
                        "connection_ref", "vault://ci/payment-db",
                        "cleanup_strategy", "by_test_run_id",
                        "isolation_key", "test_run_id",
                        "setup_actions", Map.of(
                                "inline_seed", Map.of("sql", "insert into payment values (1)"),
                                "missing_ref", Map.of(),
                                "unsafe_ref", Map.of("sql_ref", "../seed.sql"),
                                "ignored", "not-a-map"),
                        "cleanup_actions", Map.of(
                                "unsafe_cleanup", Map.of("sql_ref", "/tmp/cleanup.sql")),
                        "verification_queries", Map.of(
                                "inline_query", Map.of("sql", "select 1"),
                                "missing_query", Map.of(),
                                "unsafe_query", Map.of("sql_ref", "~/verify.sql"),
                                "ignored", "not-a-map")),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".setup_actions.inline_seed.sql",
                        ".setup_actions.missing_ref.sql_ref",
                        ".setup_actions.unsafe_ref.sql_ref",
                        ".cleanup_actions.unsafe_cleanup.sql_ref",
                        ".verification_queries.inline_query.sql",
                        ".verification_queries.missing_query.sql_ref",
                        ".verification_queries.unsafe_query.sql_ref");
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::runtimeStatus)
                .containsOnly("blocked");
    }

    @Test
    void acceptsFullyApprovedExternalRunnerContract() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "external_runner",
                Map.ofEntries(
                        entry("provider_family", "external_runner"),
                        entry("provider_type", "command_runner"),
                        entry("approval_ref", "ADR-012"),
                        entry("approved_by", "SA"),
                        entry("reason", "legacy protocol bridge"),
                        entry("container_ref", "registry.example.com/runner:1.0"),
                        entry("timeout_seconds", 30),
                        entry("inputs", Map.of("payload", "fixtures/request.json")),
                        entry("outputs", Map.of("actual_output_ref", "actual/runner-output.json")),
                        entry("evidence_map", Map.of("runner_log", "logs/external-runner.log"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isTrue();
        assertThat(validation.registryStatus()).isEqualTo("supported");
        assertThat(validation.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksUnsafeExternalRunnerEvidenceAndBuiltInAlternative() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "external_runner",
                Map.ofEntries(
                        entry("provider_family", "external_runner"),
                        entry("provider_type", "command_runner"),
                        entry("approval_ref", "ADR-012"),
                        entry("approved_by", "SA"),
                        entry("reason", "legacy protocol bridge"),
                        entry("command", "./run-legacy.sh"),
                        entry("timeout_seconds", 0),
                        entry("inputs", Map.of("payload", "fixtures/request.json")),
                        entry("outputs", Map.of(
                                "absolute", "/tmp/output.json",
                                "parent", "../outside.json",
                                "windows", "C:\\temp\\output.json")),
                        entry("evidence_map", Map.of(
                                "absolute", "/tmp/evidence.yaml",
                                "parent", "logs/../evidence.yaml")),
                        entry("built_in_provider_alternative", "request_response")),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".timeout_seconds",
                        ".outputs.absolute",
                        ".outputs.parent",
                        ".outputs.windows",
                        ".evidence_map.absolute",
                        ".evidence_map.parent",
                        ".built_in_provider_alternative");
        assertThat(validation.registryStatus()).isEqualTo("unapproved_escape_hatch");
    }
}
