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
    void acceptsNativeKafkaAndNatsMessagingContracts() {
        ProviderCapabilityRegistry.ProviderContractValidation kafka = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event",
                                        "serialization", "json",
                                        "requires_correlation", true,
                                        "correlation_id", "PAY-001"))),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation nats = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event",
                                        "serialization", "json",
                                        "requires_correlation", true,
                                        "correlation_key", "paymentId"))),
                "ci_ephemeral");

        assertThat(kafka.ready()).isTrue();
        assertThat(kafka.registryStatus()).isEqualTo("supported");
        assertThat(kafka.runtimeStatus()).isEqualTo("supported");
        assertThat(nats.ready()).isTrue();
        assertThat(nats.registryStatus()).isEqualTo("supported");
        assertThat(nats.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void acceptsNativeKafkaAndNatsConsumeObserveContractsWithoutPayloadBinding() {
        ProviderCapabilityRegistry.ProviderContractValidation kafka = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "observe_payment_event", Map.of(
                                        "mode", "observe",
                                        "serialization", "json",
                                        "min_count", 1,
                                        "requires_correlation", true,
                                        "correlation_id", "PAY-001"))),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation nats = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "consume_payment_event", Map.of(
                                        "mode", "consume",
                                        "serialization", "json",
                                        "expected_count", 1))),
                "ci_ephemeral");

        assertThat(kafka.ready()).isTrue();
        assertThat(kafka.registryStatus()).isEqualTo("supported");
        assertThat(kafka.runtimeStatus()).isEqualTo("supported");
        assertThat(nats.ready()).isTrue();
        assertThat(nats.registryStatus()).isEqualTo("supported");
        assertThat(nats.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void acceptsNativeKafkaAndNatsCleanupContractsWithoutPayloadBinding() {
        ProviderCapabilityRegistry.ProviderContractValidation kafka = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "cleanup_payment_event", Map.of(
                                        "mode", "cleanup",
                                        "cleanup_strategy", "drain",
                                        "max_count", 25,
                                        "serialization", "json"))),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation nats = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "cleanup_payment_event", Map.of(
                                        "mode", "cleanup",
                                        "cleanup_strategy", "drain",
                                        "max_count", 25,
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(kafka.ready()).isTrue();
        assertThat(kafka.registryStatus()).isEqualTo("supported");
        assertThat(kafka.runtimeStatus()).isEqualTo("supported");
        assertThat(nats.ready()).isTrue();
        assertThat(nats.registryStatus()).isEqualTo("supported");
        assertThat(nats.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksNativeMessagingCleanupWithoutStrategyOrBound() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "cleanup_payment_event", Map.of(
                                        "mode", "cleanup",
                                        "max_count", 0))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".actions.cleanup_payment_event.cleanup_strategy",
                        ".actions.cleanup_payment_event.max_count");
    }

    @Test
    void blocksNativeMessagingContractWithUnsupportedActionMode() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "stream_payment_event", Map.of(
                                        "mode", "stream",
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(".actions.stream_payment_event.mode");
    }

    @Test
    void acceptsNativeGrpcRequestResponseContract() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "request_response",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "grpc",
                        "service_ref", "dns:///payment-api:9090",
                        "descriptor_ref", "descriptors/payment.desc",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/grpc-response.json"),
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "service", "payment.PaymentService",
                                        "method", "SubmitPayment",
                                        "request_binding", "payment_payload"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isTrue();
        assertThat(validation.registryStatus()).isEqualTo("supported");
        assertThat(validation.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksNativeGrpcContractWithoutDescriptorActionOrOutput() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "request_response",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "grpc",
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "service", "payment.PaymentService"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".service_ref",
                        ".descriptor_ref",
                        ".timeout_seconds",
                        ".outputs.actual_output_ref",
                        ".actions.submit_payment.method",
                        ".actions.submit_payment.request_binding");
    }

    @Test
    void acceptsNativeK8sAndVmDeploymentReadinessContracts() {
        ProviderCapabilityRegistry.ProviderContractValidation k8s = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "kube_context_ref", "env://KUBE_CONTEXT",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.txt")),
                "sit_deployed");
        ProviderCapabilityRegistry.ProviderContractValidation vm = registry.validate(
                "adapters",
                "payment_vm",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "tcp_connect",
                        "host_ref", "10.0.0.15",
                        "port", 8443,
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.txt")),
                "sit_deployed");

        assertThat(k8s.ready()).isTrue();
        assertThat(k8s.registryStatus()).isEqualTo("supported");
        assertThat(k8s.runtimeStatus()).isEqualTo("supported");
        assertThat(vm.ready()).isTrue();
        assertThat(vm.registryStatus()).isEqualTo("supported");
        assertThat(vm.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void acceptsNativeK8sPodLogReadinessContract() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kube_context_ref", "env://KUBE_CONTEXT",
                        "namespace_ref", "payment",
                        "target_selector", "app=payment-api",
                        "log_tail_lines", 50,
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/pod-logs.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isTrue();
        assertThat(validation.registryStatus()).isEqualTo("supported");
        assertThat(validation.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksNativeK8sPodLogReadinessWithoutSelectorOrBoundedTail() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "pod_logs",
                        "kube_context_ref", "env://KUBE_CONTEXT",
                        "namespace_ref", "payment",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/pod-logs.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".target_selector",
                        ".log_tail_lines");
    }

    @Test
    void acceptsNativeVmSshAndWinrmCommandReadinessContracts() {
        ProviderCapabilityRegistry.ProviderContractValidation ssh = registry.validate(
                "adapters",
                "payment_vm",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "ssh_ref", "tools/ssh-readiness.sh",
                        "host_ref", "10.0.0.15",
                        "user_ref", "deploy",
                        "command_ref", "systemctl is-active payment-api",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-ssh.txt")),
                "sit_deployed");
        ProviderCapabilityRegistry.ProviderContractValidation winrm = registry.validate(
                "adapters",
                "payment_vm",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "winrm_command",
                        "winrm_ref", "tools/winrm-readiness.sh",
                        "host_ref", "10.0.0.16",
                        "command_ref", "Get-Service payment-api",
                        "deployed_version_ref", "build-44",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-winrm.txt")),
                "sit_deployed");

        assertThat(ssh.ready()).isTrue();
        assertThat(ssh.registryStatus()).isEqualTo("supported");
        assertThat(ssh.runtimeStatus()).isEqualTo("supported");
        assertThat(winrm.ready()).isTrue();
        assertThat(winrm.registryStatus()).isEqualTo("supported");
        assertThat(winrm.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksNativeVmCommandReadinessWithoutHostOrCommand() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_vm",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-ssh.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".host_ref",
                        ".command_ref");
    }

    @Test
    void blocksNativeDeploymentReadinessContractsWithoutRequiredTargetFields() {
        ProviderCapabilityRegistry.ProviderContractValidation k8s = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.txt")),
                "sit_deployed");
        ProviderCapabilityRegistry.ProviderContractValidation vm = registry.validate(
                "adapters",
                "payment_vm",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "tcp_connect",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.txt")),
                "sit_deployed");

        assertThat(k8s.ready()).isFalse();
        assertThat(k8s.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".kube_context_ref",
                        ".namespace_ref",
                        ".deployment_ref");
        assertThat(vm.ready()).isFalse();
        assertThat(vm.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".host_ref",
                        ".port");
    }

    @Test
    void blocksNativeMessagingContractsWithoutConnectionActionOrCorrelation() {
        ProviderCapabilityRegistry.ProviderContractValidation kafka = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "mode", "publish",
                                        "payload_binding", "payment_event",
                                        "serialization", "xml",
                                        "requires_correlation", true))),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation nats = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json")),
                "ci_ephemeral");

        assertThat(kafka.ready()).isFalse();
        assertThat(kafka.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".bootstrap_servers_ref",
                        ".actions.publish_payment_event.serialization",
                        ".actions.publish_payment_event.correlation_id_ref");
        assertThat(nats.ready()).isFalse();
        assertThat(nats.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".server_ref",
                        ".subject_ref",
                        ".actions");
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
