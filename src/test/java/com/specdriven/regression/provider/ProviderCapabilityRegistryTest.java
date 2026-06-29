package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderCapabilityRegistryTest {

    private final ProviderCapabilityRegistry registry = new ProviderCapabilityRegistry();

    @Test
    void rejectsMissingProviderMetadataBeforeFamilySpecificValidation() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "unknown_provider",
                Map.of(),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.registryStatus()).isEqualTo("missing_metadata");
        assertThat(validation.runtimeStatus()).isEqualTo("blocked");
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".provider_family", ".provider_type");
    }

    @Test
    void rejectsUnsupportedProviderFamilyAndTypeCombination() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "orbix_bridge",
                Map.of(
                        "provider_family", "orbix",
                        "provider_type", "iiop"),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.registryStatus()).isEqualTo("unsupported");
        assertThat(validation.runtimeStatus()).isEqualTo("unsupported");
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".provider_type");
    }

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
    void blocksExecutableShellAdapterWithoutCommandTimeoutOrOutput() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "batch_runner",
                Map.of(
                        "provider_family", "file_batch",
                        "provider_type", "shell",
                        "timeout_seconds", "soon"),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".command",
                        ".timeout_seconds",
                        ".outputs.actual_output_ref");
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
    void acceptsNativeNatsRequestReplyContractWithPayloadBinding() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_authorization",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.authorization",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-authorization.json"),
                        "actions", Map.of(
                                "request_payment_authorization", Map.of(
                                        "mode", "request_reply",
                                        "payload_binding", "authorization_request",
                                        "serialization", "json",
                                        "requires_correlation", true,
                                        "correlation_id", "PAY-REQ-001"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isTrue();
        assertThat(validation.registryStatus()).isEqualTo("supported");
        assertThat(validation.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksNativeNatsRequestReplyWithoutPayloadBinding() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_authorization",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.authorization",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-authorization.json"),
                        "actions", Map.of(
                                "request_payment_authorization", Map.of(
                                        "mode", "request_reply",
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(".actions.request_payment_authorization.payload_binding");
    }

    @Test
    void blocksNativeKafkaRequestReplyUntilReusableRuntimeExists() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_authorization",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.authorization",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-authorization.json"),
                        "actions", Map.of(
                                "request_payment_authorization", Map.of(
                                        "mode", "request_reply",
                                        "payload_binding", "authorization_request",
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(".actions.request_payment_authorization.mode");
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
    void blocksNativeGrpcContractWithUnsafeDescriptorAndMissingActionService() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "request_response",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "grpc",
                        "service_ref", "dns:///payment-api:9090",
                        "descriptor_ref", "../descriptors/payment.desc",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/grpc-response.json"),
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "method", "SubmitPayment",
                                        "request_binding", "payment_payload"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".descriptor_ref",
                        ".actions.submit_payment.service");
    }

    @Test
    void blocksNativeGrpcContractWithEmptyActionMap() {
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
                        "actions", Map.of()),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".actions");
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
    void acceptsNativeK8sDirectApiDeploymentAvailableContract() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "api_deployment_available",
                        "api_server_ref", "env://K8S_API_SERVER",
                        "namespace_ref", "payment",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/k8s-api-readiness.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isTrue();
        assertThat(validation.registryStatus()).isEqualTo("supported");
        assertThat(validation.runtimeStatus()).isEqualTo("supported");
    }

    @Test
    void blocksNativeK8sDirectApiReadinessWithoutApiServerOrDeployment() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "api_deployment_available",
                        "namespace_ref", "payment",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/k8s-api-readiness.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".api_server_ref",
                        ".deployment_ref");
    }

    @Test
    void blocksNativeK8sDirectApiReadinessWithoutNamespace() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "api_deployment_available",
                        "api_server_ref", "env://K8S_API_SERVER",
                        "deployment_ref", "deployment/payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/k8s-api-readiness.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".namespace_ref");
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
    void blocksDeploymentReadinessContractWithoutCoreFields() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_ready",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "local"),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".readiness_probe",
                        ".deployed_version_ref",
                        ".timeout_seconds",
                        ".outputs.actual_output_ref",
                        ".deployment_ref");
    }

    @Test
    void blocksNativeVmHttpAndCommandReadinessInvalidEndpointOrPort() {
        ProviderCapabilityRegistry.ProviderContractValidation http = registry.validate(
                "adapters",
                "payment_vm_http",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "http_get",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-http.txt")),
                "sit_deployed");
        ProviderCapabilityRegistry.ProviderContractValidation ssh = registry.validate(
                "adapters",
                "payment_vm_ssh",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "host_ref", "10.0.0.15",
                        "command_ref", "systemctl is-active payment-api",
                        "port", "ssh",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-ssh.txt")),
                "sit_deployed");

        assertThat(http.ready()).isFalse();
        assertThat(http.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".health_url_ref");
        assertThat(ssh.ready()).isFalse();
        assertThat(ssh.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".port");
    }

    @Test
    void blocksNativeVmTcpReadinessWithInvalidPort() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_vm_tcp",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "tcp_connect",
                        "host_ref", "10.0.0.15",
                        "port", "https",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-tcp.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".port");
    }

    @Test
    void blocksDeploymentReadinessContractWithInvalidTimeout() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_ready",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "local",
                        "readiness_probe", "http_get",
                        "deployment_ref", "payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", "soon",
                        "outputs", Map.of("actual_output_ref", "actual/readiness.json")),
                "sit_deployed");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".timeout_seconds");
    }

    @Test
    void blocksNonNativeMessagingWithoutEndpointOrPositiveTimeout() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "message_bus",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "mock",
                        "timeout_seconds", "soon",
                        "outputs", Map.of("actual_output_ref", "actual/events.json")),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(
                        ".topic_ref",
                        ".timeout_seconds");
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
    void blocksBindingAndDbFixtureWithoutRequiredMetadata() {
        ProviderCapabilityRegistry.ProviderContractValidation binding = registry.validate(
                "bindings",
                "payload",
                Map.of(
                        "provider_family", "file_batch",
                        "provider_type", "file_fixture"),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation fixture = registry.validate(
                "fixtures",
                "relational_db",
                Map.of(
                        "provider_family", "db_fixture",
                        "provider_type", "jdbc"),
                "ci_ephemeral");

        assertThat(binding.ready()).isFalse();
        assertThat(binding.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".bind_as");
        assertThat(fixture.ready()).isFalse();
        assertThat(fixture.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .contains(
                        ".connection_ref",
                        ".cleanup_strategy",
                        ".isolation_key");
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

    @Test
    void blocksExternalRunnerWithoutCommandOrContainerRef() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "external_runner",
                Map.ofEntries(
                        entry("provider_family", "external_runner"),
                        entry("provider_type", "command_runner"),
                        entry("approval_ref", "ADR-012"),
                        entry("approved_by", "SA"),
                        entry("reason", "legacy protocol bridge"),
                        entry("timeout_seconds", 30),
                        entry("inputs", Map.of("payload", "fixtures/request.json")),
                        entry("outputs", Map.of("actual_output_ref", "actual/runner-output.json")),
                        entry("evidence_map", Map.of("runner_log", "logs/external-runner.log"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".command");
    }

    @Test
    void acceptsBlankExecutionModeAndSupportedMetadataOnlyProviderTypes() {
        ProviderCapabilityRegistry.ProviderContractValidation fileFixture = registry.validate(
                "adapters",
                "fixture_file",
                Map.of(
                        "provider_family", "file_batch",
                        "provider_type", "file_fixture"),
                "");
        ProviderCapabilityRegistry.ProviderContractValidation requestBody = registry.validate(
                "adapters",
                "request_body",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "request_body"),
                "");
        ProviderCapabilityRegistry.ProviderContractValidation eventPayload = registry.validate(
                "adapters",
                "event_payload",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "event_payload"),
                "");
        ProviderCapabilityRegistry.ProviderContractValidation relationalDb = registry.validate(
                "fixtures",
                "relational_db",
                Map.of(
                        "provider_family", "db_fixture",
                        "provider_type", "relational_db"),
                "");

        assertThat(fileFixture.ready()).isTrue();
        assertThat(requestBody.ready()).isTrue();
        assertThat(eventPayload.ready()).isTrue();
        assertThat(relationalDb.ready()).isTrue();
    }

    @Test
    void rejectsExternalRunnerOutsideAdapterSection() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "fixtures",
                "external_runner",
                Map.of(
                        "provider_family", "external_runner",
                        "provider_type", "command_runner"),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.registryStatus()).isEqualTo("unsupported");
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".provider_type");
    }

    @Test
    void acceptsShellRestK8sAndVmAlternativeContractReferences() {
        ProviderCapabilityRegistry.ProviderContractValidation shell = registry.validate(
                "adapters",
                "batch_runner",
                Map.of(
                        "provider_family", "file_batch",
                        "provider_type", "shell",
                        "command", "./run.sh",
                        "timeout_seconds", "5",
                        "outputs", Map.of("actual_output_ref", "actual/batch.json")),
                "local_fixture");
        ProviderCapabilityRegistry.ProviderContractValidation rest = registry.validate(
                "adapters",
                "payment_api",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "rest",
                        "service_ref", "payment-api",
                        "timeout_seconds", 5,
                        "outputs", Map.of("actual_output_ref", "actual/rest.json"),
                        "actions", Map.of("submit", Map.of("method", "POST", "path", "/payments"))),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation k8s = registry.validate(
                "adapters",
                "payment_k8s",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "k8s",
                        "readiness_probe", "rollout_status",
                        "connection_ref", "kube://sit",
                        "namespace_ref", "payment",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 30,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.txt")),
                "sit_deployed");
        ProviderCapabilityRegistry.ProviderContractValidation vm = registry.validate(
                "adapters",
                "payment_vm_http",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "http_get",
                        "endpoint_ref", "https://payment.example.internal/health",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-http.txt")),
                "sit_deployed");

        assertThat(shell.ready()).isTrue();
        assertThat(rest.ready()).isTrue();
        assertThat(k8s.ready()).isTrue();
        assertThat(vm.ready()).isTrue();
    }

    @Test
    void acceptsGrpcServiceRefAndSkipsMalformedActionEntries() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "request_response",
                Map.of(
                        "provider_family", "request_response",
                        "provider_type", "grpc",
                        "base_url_ref", "dns:///payment-api:9090",
                        "descriptor_ref", "descriptors/payment.desc",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/grpc-response.json"),
                        "actions", Map.of(
                                "submit_payment", Map.of(
                                        "service_ref", "payment.PaymentService",
                                        "method", "SubmitPayment",
                                        "request_binding", "payment_payload"),
                                "metadata", "not-a-map")),
                "ci_ephemeral");

        assertThat(validation.ready()).isTrue();
        assertThat(validation.registryStatus()).isEqualTo("supported");
    }

    @Test
    void acceptsNativeMessagingDefaultModeHyphenatedModeAndBindingAliases() {
        ProviderCapabilityRegistry.ProviderContractValidation defaultPublish = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "connection_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "subject_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", Map.of(
                                "publish_payment_event", Map.of(
                                        "event_binding", "payment_event",
                                        "serialization", "json",
                                        "requires_correlation", "true",
                                        "correlation_id_ref", "bindings/payment-id"))),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation requestReply = registry.validate(
                "adapters",
                "payment_authorization",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "connection_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.authorization",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-authorization.json"),
                        "actions", Map.of(
                                "request_payment_authorization", Map.of(
                                        "mode", "request-reply",
                                        "message_binding", "authorization_request",
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(defaultPublish.ready()).isTrue();
        assertThat(requestReply.ready()).isTrue();
    }

    @Test
    void blocksNativeMessagingCleanupWithUnsupportedStrategy() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
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
                                        "cleanup_strategy", "delete",
                                        "max_count", "0"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(
                        ".actions.cleanup_payment_event.cleanup_strategy",
                        ".actions.cleanup_payment_event.max_count");
    }

    @Test
    void blocksUnsafeProviderPathsAcrossRelativePathShapes() {
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
                                "parent_only", Map.of("sql_ref", ".."),
                                "nested_parent", Map.of("sql_ref", "seed/../payment.sql"),
                                "trailing_parent", Map.of("sql_ref", "seed/.."),
                                "windows_drive", Map.of("sql_ref", "C:\\seed\\payment.sql")),
                        "cleanup_actions", Map.of(
                                "safe_cleanup", Map.of("sql_ref", "sql/cleanup-payment.sql")),
                        "verification_queries", Map.of(
                                "safe_query", Map.of("sql_ref", "sql/verify-payment.sql"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactlyInAnyOrder(
                        ".setup_actions.parent_only.sql_ref",
                        ".setup_actions.nested_parent.sql_ref",
                        ".setup_actions.trailing_parent.sql_ref",
                        ".setup_actions.windows_drive.sql_ref");
    }

    @Test
    void blocksUnsafeExternalRunnerBlankHomeParentAndTrailingParentOutputs() {
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
                        entry("timeout_seconds", 30),
                        entry("inputs", Map.of("payload", "fixtures/request.json")),
                        entry("outputs", Map.of(
                                "blank", "",
                                "home", "~/output.json",
                                "parent_only", "..",
                                "trailing_parent", "reports/..")),
                        entry("evidence_map", Map.of(
                                "safe_log", "logs/external-runner.log"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactlyInAnyOrder(
                        ".outputs.blank",
                        ".outputs.home",
                        ".outputs.parent_only",
                        ".outputs.trailing_parent");
    }

    @Test
    void rejectsUnsupportedTypesInsideSupportedProviderFamilies() {
        assertUnsupported(registry.validate(
                "adapters",
                "file_batch",
                Map.of("provider_family", "file_batch", "provider_type", "csv"),
                "ci_ephemeral"));
        assertUnsupported(registry.validate(
                "adapters",
                "request_response",
                Map.of("provider_family", "request_response", "provider_type", "soap"),
                "ci_ephemeral"));
        assertUnsupported(registry.validate(
                "adapters",
                "message_bus",
                Map.of("provider_family", "messaging", "provider_type", "orbix"),
                "ci_ephemeral"));
        assertUnsupported(registry.validate(
                "fixtures",
                "document_db",
                Map.of("provider_family", "db_fixture", "provider_type", "mongo"),
                "ci_ephemeral"));
        assertUnsupported(registry.validate(
                "adapters",
                "deployment_readiness",
                Map.of("provider_family", "deployment_readiness", "provider_type", "bare_metal"),
                "ci_ephemeral"));
        assertUnsupported(registry.validate(
                "adapters",
                "external_runner",
                Map.of("provider_family", "external_runner", "provider_type", "shell"),
                "ci_ephemeral"));
    }

    @Test
    void ignoresRequiredFieldValidationForUnknownContractSections() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "metadata",
                "batch_runner",
                Map.of(
                        "provider_family", "file_batch",
                        "provider_type", "shell"),
                "ci_ephemeral");

        assertThat(validation.ready()).isTrue();
    }

    @Test
    void acceptsMockDeploymentReadinessAndNonDbFixtureProviderMetadata() {
        ProviderCapabilityRegistry.ProviderContractValidation deployment = registry.validate(
                "adapters",
                "payment_ready",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "mock",
                        "readiness_probe", "health_check",
                        "service_ref", "payment-api",
                        "deployed_version_ref", "build-42",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/readiness.json")),
                "local_fixture");
        ProviderCapabilityRegistry.ProviderContractValidation fixtureMetadata = registry.validate(
                "fixtures",
                "fixture_file",
                Map.of(
                        "provider_family", "file_batch",
                        "provider_type", "file_fixture"),
                "local_fixture");

        assertThat(deployment.ready()).isTrue();
        assertThat(fixtureMetadata.ready()).isTrue();
    }

    @Test
    void blocksGrpcWhenActionsNodeIsNotAMap() {
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
                        "actions", "submit_payment"),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".actions");
    }

    @Test
    void skipsMalformedNativeMessagingActionEntriesButBlocksNonMapActionSets() {
        ProviderCapabilityRegistry.ProviderContractValidation malformedEntry = registry.validate(
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
                                "metadata", "not-a-map")),
                "ci_ephemeral");
        ProviderCapabilityRegistry.ProviderContractValidation nonMapActions = registry.validate(
                "adapters",
                "payment_events",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "kafka",
                        "bootstrap_servers_ref", "env://KAFKA_BOOTSTRAP_SERVERS",
                        "topic_ref", "payment.events",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-events.json"),
                        "actions", "publish"),
                "ci_ephemeral");

        assertThat(malformedEntry.ready()).isTrue();
        assertThat(nonMapActions.ready()).isFalse();
        assertThat(nonMapActions.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".actions");
    }

    @Test
    void acceptsNativeNatsRequestModeWithPayloadAlias() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_authorization",
                Map.of(
                        "provider_family", "messaging",
                        "provider_type", "nats",
                        "server_ref", "nats://127.0.0.1:4222",
                        "subject_ref", "payment.authorization",
                        "timeout_seconds", 10,
                        "outputs", Map.of("actual_output_ref", "actual/payment-authorization.json"),
                        "actions", Map.of(
                                "request_payment_authorization", Map.of(
                                        "mode", "request",
                                        "message_binding", "authorization_request",
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isTrue();
    }

    @Test
    void blocksNativeMessagingWithEmptyActionMap() {
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
                        "actions", Map.of()),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".actions");
    }

    @Test
    void blocksNativeNatsContractWithUnsupportedActionMode() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
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
                                "stream_payment_event", Map.of(
                                        "mode", "stream",
                                        "serialization", "json"))),
                "ci_ephemeral");

        assertThat(validation.ready()).isFalse();
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".actions.stream_payment_event.mode");
    }

    @Test
    void acceptsNativeVmCommandReadinessWithNumericPort() {
        ProviderCapabilityRegistry.ProviderContractValidation validation = registry.validate(
                "adapters",
                "payment_vm_ssh",
                Map.of(
                        "provider_family", "deployment_readiness",
                        "provider_type", "vm",
                        "readiness_probe", "ssh_command",
                        "host_ref", "10.0.0.15",
                        "command_ref", "systemctl is-active payment-api",
                        "port", "22",
                        "deployed_version_ref", "build-43",
                        "timeout_seconds", 15,
                        "outputs", Map.of("actual_output_ref", "actual/vm-ssh.txt")),
                "sit_deployed");

        assertThat(validation.ready()).isTrue();
    }

    private void assertUnsupported(ProviderCapabilityRegistry.ProviderContractValidation validation) {
        assertThat(validation.ready()).isFalse();
        assertThat(validation.registryStatus()).isEqualTo("unsupported");
        assertThat(validation.runtimeStatus()).isEqualTo("unsupported");
        assertThat(validation.violations()).extracting(ProviderCapabilityRegistry.ProviderContractViolation::pathSuffix)
                .containsExactly(".provider_type");
    }
}
