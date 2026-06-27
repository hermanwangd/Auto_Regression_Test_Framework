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
            case "request_response" -> type.equals("rest") || type.equals("request_body");
            case "messaging" -> type.equals("local") || type.equals("mock") || type.equals("event_payload");
            case "db_fixture" -> type.equals("jdbc") || type.equals("relational_db");
            case "deployment_readiness" -> type.equals("local") || type.equals("mock");
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
        if (family.equals("file_batch") && type.equals("shell") && !hasAnyText(contract, "command")) {
            violations.add(required(".command", "Declare executable adapter command for `" + providerName + "`."));
        }
        if (family.equals("request_response") && type.equals("rest")) {
            if (!hasAnyText(contract, "endpoint_ref", "base_url_ref", "service_ref")) {
                violations.add(required(".endpoint_ref",
                        "Declare endpoint_ref, base_url_ref, or service_ref for `" + providerName + "`."));
            }
            if (!hasMap(contract, "actions")) {
                violations.add(required(".actions",
                        "Declare at least one request/response action for `" + providerName + "`."));
            }
        }
        if (family.equals("messaging") && (type.equals("local") || type.equals("mock"))
                && !hasAnyText(contract, "topic_ref", "subject_ref", "stream_ref", "endpoint_ref")) {
            violations.add(required(".topic_ref",
                    "Declare topic_ref, subject_ref, stream_ref, or endpoint_ref for `" + providerName + "`."));
        }
        if (family.equals("deployment_readiness") && (type.equals("local") || type.equals("mock"))) {
            if (!hasAnyText(contract, "readiness_probe")) {
                violations.add(required(".readiness_probe", "Declare readiness_probe for `" + providerName + "`."));
            }
            if (!hasAnyText(contract, "deployment_ref", "service_ref", "target_selector")) {
                violations.add(required(".deployment_ref",
                        "Declare deployment_ref, service_ref, or target_selector for `" + providerName + "`."));
            }
        }
        if (family.equals("external_runner")) {
            validateExternalRunner(providerName, contract, violations);
        }
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
        }
        if (!hasMap(contract, "inputs")) {
            violations.add(escapeHatch(".inputs",
                    "Declare external runner inputs before invoking `" + providerName + "`."));
        }
        if (!hasMap(contract, "outputs")) {
            violations.add(escapeHatch(".outputs",
                    "Declare external runner outputs before invoking `" + providerName + "`."));
        }
        if (!hasMap(contract, "evidence_map")) {
            violations.add(escapeHatch(".evidence_map",
                    "Declare external runner evidence_map before invoking `" + providerName + "`."));
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
