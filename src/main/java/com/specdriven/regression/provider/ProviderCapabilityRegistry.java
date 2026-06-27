package com.specdriven.regression.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class ProviderCapabilityRegistry {

    private static final Set<String> EXECUTION_MODES =
            Set.of("local_fixture", "ci_ephemeral", "sit_deployed", "evidence_only");

    ProviderContractValidation validate(
            String section,
            String providerName,
            Map<String, Object> contract,
            String executionMode) {
        String providerFamily = stringValue(contract.get("provider_family"));
        String providerType = stringValue(contract.get("provider_type"));
        List<ProviderContractViolation> violations = new ArrayList<>();

        if (providerFamily.isBlank()) {
            violations.add(new ProviderContractViolation(
                    ".provider_family",
                    "missing_metadata",
                    "blocked",
                    "Declare provider_family for `" + providerName + "` before execution."));
        }
        if (providerType.isBlank()) {
            violations.add(new ProviderContractViolation(
                    ".provider_type",
                    "missing_metadata",
                    "blocked",
                    "Declare provider_type for `" + providerName + "` before execution."));
        }
        if (!executionMode.isBlank() && !EXECUTION_MODES.contains(executionMode)) {
            violations.add(new ProviderContractViolation(
                    ".execution_mode",
                    "unsupported_execution_mode",
                    "blocked",
                    "Use one of the supported execution modes before execution."));
        }
        if (!violations.isEmpty()) {
            return new ProviderContractValidation(providerFamily, providerType, violations);
        }

        String family = providerFamily.toLowerCase(Locale.ROOT);
        String type = providerType.toLowerCase(Locale.ROOT);
        if (!isSupportedProvider(section, family, type)) {
            violations.add(new ProviderContractViolation(
                    ".provider_type",
                    "unsupported",
                    "unsupported",
                    "Unsupported provider_type `" + providerType + "` for provider_family `"
                            + providerFamily + "` in `" + section + "`. Configure a supported provider type."));
            return new ProviderContractValidation(providerFamily, providerType, violations);
        }

        validateRequiredFields(section, family, type, providerName, contract, violations);
        return new ProviderContractValidation(providerFamily, providerType, violations);
    }

    private boolean isSupportedProvider(String section, String family, String type) {
        return switch (family) {
            case "file_batch" -> type.equals("shell") || type.equals("file_fixture");
            case "request_response" -> type.equals("rest") || type.equals("grpc") || type.equals("request_body");
            case "messaging" -> type.equals("local")
                    || type.equals("mock")
                    || type.equals("event_payload")
                    || type.equals("kafka")
                    || type.equals("nats");
            case "db_fixture" -> type.equals("jdbc") || type.equals("relational_db");
            case "deployment_readiness" -> type.equals("local")
                    || type.equals("mock")
                    || type.equals("k8s")
                    || type.equals("vm");
            case "external_runner" -> section.equals("adapters") && type.equals("command_runner");
            default -> false;
        };
    }

    private void validateRequiredFields(
            String section,
            String family,
            String type,
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (section.equals("adapters")) {
            validateAdapter(family, type, providerName, contract, violations);
        } else if (section.equals("bindings")) {
            validateBinding(providerName, contract, violations);
        } else if (section.equals("fixtures")) {
            validateFixture(family, type, providerName, contract, violations);
        }
    }

    private void validateAdapter(
            String family,
            String type,
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (family.equals("file_batch") && type.equals("shell")) {
            if (!hasAnyText(contract, "command")) {
                violations.add(required(".command", "Declare executable adapter command for `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "timeout_seconds")) {
                violations.add(required(".timeout_seconds",
                        "Declare timeout_seconds as a positive bounded integer for executable adapter `"
                                + providerName + "`."));
            } else if (!isPositiveInteger(contract.get("timeout_seconds"))) {
                violations.add(required(".timeout_seconds",
                        "Declare timeout_seconds as a positive bounded integer for executable adapter `"
                                + providerName + "`."));
            }
            if (!hasNestedAnyText(contract, "outputs", "actual_output_ref")) {
                violations.add(required(".outputs.actual_output_ref",
                        "Declare actual_output_ref for executable adapter `" + providerName + "`."));
            }
        }
        if (family.equals("request_response") && List.of("rest", "grpc").contains(type)) {
            if (!hasAnyText(contract, "endpoint_ref", "base_url_ref", "service_ref")) {
                violations.add(required(type.equals("grpc") ? ".service_ref" : ".endpoint_ref",
                        "Declare endpoint_ref, base_url_ref, or service_ref for `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "timeout_seconds")) {
                violations.add(required(".timeout_seconds",
                        "Declare timeout_seconds as a positive bounded integer for request/response provider `"
                                + providerName + "`."));
            } else if (!isPositiveInteger(contract.get("timeout_seconds"))) {
                violations.add(required(".timeout_seconds",
                        "Declare timeout_seconds as a positive bounded integer for request/response provider `"
                                + providerName + "`."));
            }
            if (!hasNestedAnyText(contract, "outputs", "actual_output_ref")) {
                violations.add(required(".outputs.actual_output_ref",
                        "Declare actual_output_ref for request/response provider `" + providerName + "`."));
            }
            if (!hasMap(contract, "actions")) {
                violations.add(required(".actions",
                        "Declare at least one request/response action for `" + providerName + "`."));
            }
            if (type.equals("grpc")) {
                validateGrpcRequestResponseAdapter(providerName, contract, violations);
            }
        }
        if (family.equals("messaging") && List.of("local", "mock", "kafka", "nats").contains(type)) {
            validateMessagingAdapter(type, providerName, contract, violations);
        }
        if (family.equals("deployment_readiness") && List.of("local", "mock", "k8s", "vm").contains(type)) {
            validateDeploymentReadinessAdapter(type, providerName, contract, violations);
        }
        if (family.equals("external_runner")) {
            validateExternalRunner(providerName, contract, violations);
        }
    }

    private void validateGrpcRequestResponseAdapter(
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        String descriptorRef = stringValue(contract.get("descriptor_ref"));
        if (descriptorRef.isBlank()) {
            violations.add(required(".descriptor_ref",
                    "Declare descriptor_ref for native gRPC request/response provider `" + providerName + "`."));
        } else if (isUnsafeRelativeProviderPath(descriptorRef)) {
            violations.add(required(".descriptor_ref",
                    "Keep native gRPC descriptor_ref `" + descriptorRef
                            + "` under the RP package before execution."));
        }
        if (!(contract.get("actions") instanceof Map<?, ?> actions) || actions.isEmpty()) {
            return;
        }
        for (Map.Entry<?, ?> entry : actions.entrySet()) {
            String actionName = stringValue(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> action)) {
                continue;
            }
            if (firstText(action, "service", "service_ref").isBlank()) {
                violations.add(required(".actions." + actionName + ".service",
                        "Declare gRPC service for request/response action `" + actionName + "`."));
            }
            if (stringValue(action.get("method")).isBlank()) {
                violations.add(required(".actions." + actionName + ".method",
                        "Declare gRPC method for request/response action `" + actionName + "`."));
            }
            if (stringValue(action.get("request_binding")).isBlank()) {
                violations.add(required(".actions." + actionName + ".request_binding",
                        "Declare request_binding for request/response action `" + actionName + "`."));
            }
        }
    }

    private void validateDeploymentReadinessAdapter(
            String type,
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (!hasAnyText(contract, "readiness_probe")) {
            violations.add(required(".readiness_probe", "Declare readiness_probe for `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "deployed_version_ref")) {
            violations.add(required(".deployed_version_ref",
                    "Declare deployed_version_ref for `" + providerName + "` before execution."));
        }
        if (!hasAnyText(contract, "timeout_seconds")) {
            violations.add(required(".timeout_seconds",
                    "Declare timeout_seconds as a positive bounded integer for deployment readiness provider `"
                            + providerName + "`."));
        } else if (!isPositiveInteger(contract.get("timeout_seconds"))) {
            violations.add(required(".timeout_seconds",
                    "Declare timeout_seconds as a positive bounded integer for deployment readiness provider `"
                            + providerName + "`."));
        }
        if (!hasNestedAnyText(contract, "outputs", "actual_output_ref")) {
            violations.add(required(".outputs.actual_output_ref",
                    "Declare actual_output_ref for deployment readiness provider `" + providerName + "`."));
        }
        if (type.equals("k8s")) {
            validateK8sReadiness(providerName, contract, violations);
        } else if (type.equals("vm")) {
            validateVmReadiness(providerName, contract, violations);
        } else if (!hasAnyText(contract, "deployment_ref", "service_ref", "target_selector")) {
            violations.add(required(".deployment_ref",
                    "Declare deployment_ref, service_ref, or target_selector for `" + providerName + "`."));
        }
    }

    private void validateK8sReadiness(
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        String readinessProbe = stringValue(contract.get("readiness_probe"));
        if ("api_deployment_available".equals(readinessProbe)) {
            if (!hasAnyText(contract, "api_server_ref", "endpoint_ref")) {
                violations.add(required(".api_server_ref",
                        "Declare api_server_ref or endpoint_ref for native K8s API readiness provider `"
                                + providerName + "`."));
            }
            if (!hasAnyText(contract, "namespace_ref")) {
                violations.add(required(".namespace_ref",
                        "Declare namespace_ref for native K8s API readiness provider `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "deployment_ref")) {
                violations.add(required(".deployment_ref",
                        "Declare deployment_ref for native K8s API readiness provider `" + providerName + "`."));
            }
            return;
        }
        if (!hasAnyText(contract, "kube_context_ref", "connection_ref")) {
            violations.add(required(".kube_context_ref",
                    "Declare kube_context_ref or connection_ref for native K8s readiness provider `"
                            + providerName + "`."));
        }
        if (!hasAnyText(contract, "namespace_ref")) {
            violations.add(required(".namespace_ref",
                    "Declare namespace_ref for native K8s readiness provider `" + providerName + "`."));
        }
        if ("pod_logs".equals(readinessProbe)) {
            if (!hasAnyText(contract, "target_selector", "pod_ref")) {
                violations.add(required(".target_selector",
                        "Declare target_selector or pod_ref for native K8s pod log readiness provider `"
                                + providerName + "`."));
            }
            if (!isPositiveInteger(contract.get("log_tail_lines"))) {
                violations.add(required(".log_tail_lines",
                        "Declare log_tail_lines as a positive bounded integer for native K8s pod log readiness "
                                + "provider `" + providerName + "`."));
            }
            return;
        }
        if (!hasAnyText(contract, "deployment_ref", "service_ref", "target_selector")) {
            violations.add(required(".deployment_ref",
                    "Declare deployment_ref, service_ref, or target_selector for native K8s readiness provider `"
                            + providerName + "`."));
        }
    }

    private void validateVmReadiness(
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        String readinessProbe = stringValue(contract.get("readiness_probe"));
        if ("http_get".equals(readinessProbe)) {
            if (!hasAnyText(contract, "health_url_ref", "endpoint_ref")) {
                violations.add(required(".health_url_ref",
                        "Declare health_url_ref or endpoint_ref for native VM HTTP readiness provider `"
                                + providerName + "`."));
            }
            return;
        }
        if (readinessProbe.equals("ssh_command") || readinessProbe.equals("winrm_command")) {
            if (!hasAnyText(contract, "host_ref")) {
                violations.add(required(".host_ref",
                        "Declare host_ref for native VM command readiness provider `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "command_ref")) {
                violations.add(required(".command_ref",
                        "Declare command_ref for native VM command readiness provider `" + providerName + "`."));
            }
            if (hasAnyText(contract, "port") && !isPositiveInteger(contract.get("port"))) {
                violations.add(required(".port",
                        "Declare port as a positive integer for native VM command readiness provider `"
                                + providerName + "`."));
            }
            return;
        }
        if (!hasAnyText(contract, "host_ref")) {
            violations.add(required(".host_ref",
                    "Declare host_ref for native VM readiness provider `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "port")) {
            violations.add(required(".port",
                    "Declare port for native VM readiness provider `" + providerName + "`."));
        } else if (!isPositiveInteger(contract.get("port"))) {
            violations.add(required(".port",
                    "Declare port as a positive integer for native VM readiness provider `" + providerName + "`."));
        }
    }

    private void validateMessagingAdapter(
            String type,
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (type.equals("kafka") && !hasAnyText(contract, "bootstrap_servers_ref", "connection_ref")) {
            violations.add(required(".bootstrap_servers_ref",
                    "Declare bootstrap_servers_ref or connection_ref for native Kafka provider `"
                            + providerName + "`."));
        }
        if (type.equals("nats") && !hasAnyText(contract, "server_ref", "connection_ref")) {
            violations.add(required(".server_ref",
                    "Declare server_ref or connection_ref for native NATS provider `" + providerName + "`."));
        }
        if (type.equals("nats") && !hasAnyText(contract, "subject_ref")) {
            violations.add(required(".subject_ref",
                    "Declare subject_ref for native NATS provider `" + providerName + "`."));
        }
        if (!type.equals("nats") && !hasAnyText(contract, "topic_ref", "subject_ref", "stream_ref", "endpoint_ref")) {
            violations.add(required(".topic_ref",
                    "Declare topic_ref, subject_ref, stream_ref, or endpoint_ref for `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "timeout_seconds")) {
            violations.add(required(".timeout_seconds",
                    "Declare timeout_seconds as a positive bounded integer for messaging provider `"
                            + providerName + "`."));
        } else if (!isPositiveInteger(contract.get("timeout_seconds"))) {
            violations.add(required(".timeout_seconds",
                    "Declare timeout_seconds as a positive bounded integer for messaging provider `"
                            + providerName + "`."));
        }
        if (!hasNestedAnyText(contract, "outputs", "actual_output_ref")) {
            violations.add(required(".outputs.actual_output_ref",
                    "Declare actual_output_ref for messaging provider `" + providerName + "`."));
        }
        if (type.equals("kafka") || type.equals("nats")) {
            validateNativeMessagingActions(type, providerName, contract, violations);
        }
    }

    private void validateNativeMessagingActions(
            String type,
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (!(contract.get("actions") instanceof Map<?, ?> actions) || actions.isEmpty()) {
            violations.add(required(".actions",
                    "Declare at least one native " + type + " messaging action for `" + providerName + "`."));
            return;
        }
        for (Map.Entry<?, ?> entry : actions.entrySet()) {
            String actionName = stringValue(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> action)) {
                continue;
            }
            String mode = messagingActionMode(action);
            if (!supportsNativeMessagingMode(type, mode)) {
                violations.add(required(".actions." + actionName + ".mode",
                        "Use supported messaging action mode `publish`, `request_reply`, `consume`, `observe`, "
                                + "or `cleanup` before invoking `" + actionName + "`."));
            }
            if (requiresMessagingPayload(mode)
                    && firstText(action, "payload_binding", "message_binding", "event_binding").isBlank()) {
                violations.add(required(".actions." + actionName + ".payload_binding",
                        "Declare payload_binding, message_binding, or event_binding for native messaging action `"
                                + actionName + "`."));
            }
            if ("cleanup".equals(mode)) {
                String cleanupStrategy = stringValue(action.get("cleanup_strategy"));
                if (cleanupStrategy.isBlank()) {
                    violations.add(required(".actions." + actionName + ".cleanup_strategy",
                            "Declare cleanup_strategy for native messaging cleanup action `"
                                    + actionName + "`."));
                } else if (!"drain".equalsIgnoreCase(cleanupStrategy)) {
                    violations.add(required(".actions." + actionName + ".cleanup_strategy",
                            "Use supported messaging cleanup_strategy `drain` before invoking native messaging "
                                    + "cleanup action `" + actionName + "`."));
                }
                if (!isPositiveInteger(action.get("max_count"))) {
                    violations.add(required(".actions." + actionName + ".max_count",
                            "Declare max_count as a positive bounded integer for native messaging cleanup action `"
                                    + actionName + "`."));
                }
            }
            String serialization = stringValue(action.get("serialization"));
            if (!serialization.isBlank() && !"json".equalsIgnoreCase(serialization)) {
                violations.add(required(".actions." + actionName + ".serialization",
                        "Use supported messaging serialization `json` before invoking native messaging action `"
                                + actionName + "`."));
            }
            if (isTruthy(action.get("requires_correlation"))
                    && firstText(action, "correlation_id", "correlation_id_ref", "correlation_key").isBlank()) {
                violations.add(required(".actions." + actionName + ".correlation_id_ref",
                        "Declare correlation_id, correlation_id_ref, or correlation_key before invoking native "
                                + "messaging action `" + actionName + "`."));
            }
        }
    }

    private String messagingActionMode(Map<?, ?> action) {
        String mode = stringValue(action.get("mode"));
        return mode.isBlank() ? "publish" : mode.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean requiresMessagingPayload(String mode) {
        return "publish".equals(mode) || "request".equals(mode) || "request_reply".equals(mode);
    }

    private boolean supportsNativeMessagingMode(String type, String mode) {
        if (List.of("publish", "consume", "observe", "cleanup").contains(mode)) {
            return true;
        }
        return "nats".equals(type) && List.of("request", "request_reply").contains(mode);
    }

    private void validateBinding(
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (!hasAnyText(contract, "bind_as", "materialize_as")) {
            violations.add(required(".bind_as",
                    "Declare bind_as or materialize_as for binding `" + providerName + "`."));
        }
    }

    private void validateFixture(
            String family,
            String type,
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (family.equals("db_fixture") && type.equals("jdbc")) {
            if (!hasAnyText(contract, "connection_ref")) {
                violations.add(required(".connection_ref", "Declare connection_ref for fixture `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "cleanup_strategy")) {
                violations.add(required(".cleanup_strategy",
                        "Declare cleanup_strategy for fixture `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "isolation_key")) {
                violations.add(required(".isolation_key",
                        "Declare isolation_key for DB fixture `" + providerName + "` before setup."));
            }
            validateDbFixtureSqlReferences(providerName, contract, violations);
        }
    }

    private void validateDbFixtureSqlReferences(
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        validateDbFixtureActionSqlReferences(providerName, contract, "setup_actions", violations);
        validateDbFixtureActionSqlReferences(providerName, contract, "cleanup_actions", violations);
        if (contract.get("verification_queries") instanceof Map<?, ?> queries) {
            for (Map.Entry<?, ?> entry : queries.entrySet()) {
                String queryName = stringValue(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> query)) {
                    continue;
                }
                if (!stringValue(query.get("sql")).isBlank()) {
                    violations.add(required(".verification_queries." + queryName + ".sql",
                            "Move DB fixture verification SQL for `" + queryName
                                    + "` into sql_ref before setup."));
                    continue;
                }
                String sqlRef = stringValue(query.get("sql_ref"));
                if (sqlRef.isBlank()) {
                    violations.add(required(".verification_queries." + queryName + ".sql_ref",
                            "Declare sql_ref for DB fixture verification query `" + queryName
                                    + "` in fixture `" + providerName + "`."));
                } else if (isUnsafeRelativeProviderPath(sqlRef)) {
                    violations.add(required(".verification_queries." + queryName + ".sql_ref",
                            "Keep DB fixture verification sql_ref `" + sqlRef
                                    + "` under the RP package before setup."));
                }
            }
        }
    }

    private void validateDbFixtureActionSqlReferences(
            String providerName,
            Map<String, Object> contract,
            String section,
            List<ProviderContractViolation> violations) {
        if (!(contract.get(section) instanceof Map<?, ?> actions)) {
            return;
        }
        for (Map.Entry<?, ?> entry : actions.entrySet()) {
            String actionName = stringValue(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> action)) {
                continue;
            }
            if (!stringValue(action.get("sql")).isBlank()) {
                violations.add(required("." + section + "." + actionName + ".sql",
                        "Move DB fixture SQL for action `" + actionName + "` into sql_ref before setup."));
                continue;
            }
            String sqlRef = stringValue(action.get("sql_ref"));
            if (sqlRef.isBlank()) {
                violations.add(required("." + section + "." + actionName + ".sql_ref",
                        "Declare sql_ref for DB fixture action `" + actionName
                                + "` in fixture `" + providerName + "`."));
            } else if (isUnsafeRelativeProviderPath(sqlRef)) {
                violations.add(required("." + section + "." + actionName + ".sql_ref",
                        "Keep DB fixture sql_ref `" + sqlRef + "` under the RP package before setup."));
            }
        }
    }

    private void validateExternalRunner(
            String providerName,
            Map<String, Object> contract,
            List<ProviderContractViolation> violations) {
        if (!hasAnyText(contract, "approval_ref")) {
            violations.add(escapeHatch(".approval_ref",
                    "Declare external runner approval metadata before invoking `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "approved_by")) {
            violations.add(escapeHatch(".approved_by",
                    "Declare external runner approval owner before invoking `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "reason")) {
            violations.add(escapeHatch(".reason",
                    "Explain why a reusable built-in provider cannot cover `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "command", "container_ref")) {
            violations.add(required(".command", "Declare command or container_ref for `" + providerName + "`."));
        }
        if (!hasAnyText(contract, "timeout_seconds")) {
            violations.add(escapeHatch(".timeout_seconds",
                    "Declare a bounded timeout for external runner `" + providerName + "`."));
        } else if (!isPositiveInteger(contract.get("timeout_seconds"))) {
            violations.add(escapeHatch(".timeout_seconds",
                    "Declare timeout_seconds as a positive bounded integer for external runner `"
                            + providerName + "`."));
        }
        if (!hasMap(contract, "inputs")) {
            violations.add(escapeHatch(".inputs",
                    "Declare external runner inputs before invoking `" + providerName + "`."));
        }
        if (!hasMap(contract, "outputs")) {
            violations.add(escapeHatch(".outputs",
                    "Declare external runner outputs before invoking `" + providerName + "`."));
        } else if (contract.get("outputs") instanceof Map<?, ?> outputs) {
            for (Map.Entry<?, ?> output : outputs.entrySet()) {
                String outputPath = stringValue(output.getValue());
                if (isUnsafeRelativeOutputPath(outputPath)) {
                    violations.add(escapeHatch(".outputs." + output.getKey(),
                            "Keep external runner output path `" + outputPath
                                    + "` under the run evidence directory before invoking `" + providerName + "`."));
                }
            }
        }
        if (!hasMap(contract, "evidence_map")) {
            violations.add(escapeHatch(".evidence_map",
                    "Declare external runner evidence_map before invoking `" + providerName + "`."));
        } else if (contract.get("evidence_map") instanceof Map<?, ?> evidenceMap) {
            for (Map.Entry<?, ?> evidenceRef : evidenceMap.entrySet()) {
                String evidencePath = stringValue(evidenceRef.getValue());
                if (isUnsafeRelativeOutputPath(evidencePath)) {
                    violations.add(escapeHatch(".evidence_map." + evidenceRef.getKey(),
                            "Keep external runner evidence map path `" + evidencePath
                                    + "` under the run evidence directory before invoking `" + providerName + "`."));
                }
            }
        }
        String builtInProviderAlternative = stringValue(contract.get("built_in_provider_alternative"));
        if (!builtInProviderAlternative.isBlank()) {
            violations.add(escapeHatch(".built_in_provider_alternative",
                    "Configure built-in provider `" + builtInProviderAlternative
                            + "` before using external runner `" + providerName + "`."));
        }
    }

    private ProviderContractViolation required(String pathSuffix, String ownerAction) {
        return new ProviderContractViolation(pathSuffix, "incomplete", "blocked", ownerAction);
    }

    private ProviderContractViolation escapeHatch(String pathSuffix, String ownerAction) {
        return new ProviderContractViolation(pathSuffix, "unapproved_escape_hatch", "blocked", ownerAction);
    }

    private boolean hasAnyText(Map<String, Object> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMap(Map<String, Object> map, String field) {
        return map.get(field) instanceof Map<?, ?> nested && !nested.isEmpty();
    }

    private boolean hasNestedAnyText(Map<String, Object> map, String field, String... nestedFields) {
        if (!(map.get(field) instanceof Map<?, ?> nested)) {
            return false;
        }
        for (String nestedField : nestedFields) {
            String value = stringValue(nested.get(nestedField));
            if (!value.isBlank()) {
                return true;
            }
        }
        return false;
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

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "true".equalsIgnoreCase(stringValue(value));
    }

    private boolean isUnsafeRelativeOutputPath(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.startsWith("~/")
                || normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.endsWith("/..")
                || normalized.matches("^[A-Za-z]:.*");
    }

    private boolean isUnsafeRelativeProviderPath(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.startsWith("~/")
                || normalized.equals("..")
                || normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.endsWith("/..")
                || normalized.matches("^[A-Za-z]:.*");
    }

    private boolean isPositiveInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0;
        }
        String text = stringValue(value);
        try {
            return Integer.parseInt(text) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    record ProviderContractValidation(
            String providerFamily,
            String providerType,
            List<ProviderContractViolation> violations) {
        boolean ready() {
            return violations.isEmpty();
        }

        String registryStatus() {
            return ready() ? "supported" : violations.get(0).registryStatus();
        }

        String runtimeStatus() {
            return ready() ? "supported" : violations.get(0).runtimeStatus();
        }
    }

    record ProviderContractViolation(String pathSuffix, String registryStatus, String runtimeStatus, String ownerAction) {
    }
}
