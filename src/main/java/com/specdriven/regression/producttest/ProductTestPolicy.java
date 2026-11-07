package com.specdriven.regression.producttest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The deliberately finite first reviewed capability; it does not grant approval. */
public record ProductTestPolicy(String testLevel, String testType, String environmentStage,
        String knowledgeMode, Map<String, String> providerModes, Map<String, String> dependencyModes,
        SharedState sharedState, String reviewedHandoffRef) {
    public ProductTestPolicy {
        member(testLevel, Set.of("component", "integration"), "test_level");
        exact(testType, "regression", "test_type");
        exact(environmentStage, "local", "environment_stage");
        member(knowledgeMode, Set.of("required", "not_used"), "knowledge_mode");
        text(reviewedHandoffRef, "reviewed_handoff_ref");
        providerModes = modes(providerModes, "provider_modes", false);
        dependencyModes = modes(dependencyModes, "dependency_modes", true);
        if ("integration".equals(testLevel) && (!"not_used".equals(knowledgeMode) || sharedState != null
                || !dependencyModes.isEmpty() || providerModes.size() != 1
                || providerModes.values().stream().anyMatch(mode -> !"real".equals(mode))))
            throw invalid("integration", "requires one real local REST target, not_used, and no shared/dependency policy");
    }

    public boolean nativeHttp() { return "integration".equals(testLevel); }

    public record SharedState(String environmentId, String namespace, String owner,
            String dataAccess, String cleanup) {
        public SharedState {
            identifier(environmentId, "shared_state.environment_id");
            identifier(namespace, "shared_state.namespace");
            identifier(owner, "shared_state.owner");
            member(dataAccess, Set.of("read_only", "owned"), "shared_state.data_access");
            member(cleanup, Set.of("none", "owned_only"), "shared_state.cleanup");
            if ("read_only".equals(dataAccess) && !"none".equals(cleanup)) {
                throw invalid("shared_state.cleanup", "read_only requires cleanup none");
            }
        }
    }

    public static ProductTestPolicy parse(Object value) {
        Map<String,Object> p = object(value, "product_test");
        only(p, Set.of("contract_version", "test_level", "test_type", "environment_stage", "knowledge_mode",
                "provider_modes", "dependency_modes", "shared_state", "reviewed_handoff_ref"), "product_test");
        exact(text(p.get("contract_version"), "contract_version"), "product-test.v1", "contract_version");
        SharedState shared = null;
        if (p.containsKey("shared_state")) {
            Map<String,Object> s = object(p.get("shared_state"), "shared_state");
            only(s, Set.of("environment_id", "namespace", "owner", "data_access", "cleanup"), "shared_state");
            shared = new SharedState(text(s.get("environment_id"), "environment_id"), text(s.get("namespace"), "namespace"),
                    text(s.get("owner"), "owner"), optional(s, "data_access", "read_only"), optional(s, "cleanup", "none"));
        }
        return new ProductTestPolicy(text(p.get("test_level"), "test_level"), text(p.get("test_type"), "test_type"),
                text(p.get("environment_stage"), "environment_stage"), text(p.get("knowledge_mode"), "knowledge_mode"),
                modes(p.get("provider_modes"), "provider_modes", false),
                p.containsKey("dependency_modes") ? modes(p.get("dependency_modes"), "dependency_modes", true) : Map.of(),
                shared, text(p.get("reviewed_handoff_ref"), "reviewed_handoff_ref"));
    }

    private static Map<String,String> modes(Object value, String field, boolean emptyAllowed) {
        Map<String,Object> input = object(value, field);
        if ((!emptyAllowed && input.isEmpty()) || input.size() > 32) throw invalid(field, "requires 1..32 entries");
        Map<String,String> result = new LinkedHashMap<>();
        input.forEach((key, raw) -> {
            identifier(key, field + " key");
            String mode = text(raw, field + "." + key);
            member(mode, Set.of("real", "simulated"), field);
            result.put(key, mode);
        });
        return Map.copyOf(result);
    }

    private static String optional(Map<String,Object> map, String field, String fallback) {
        return map.containsKey(field) ? text(map.get(field), field) : fallback;
    }
    private static void only(Map<String,Object> map, Set<String> fields, String field) {
        for (String key : map.keySet()) if (!fields.contains(key) || map.get(key) == null) {
            throw invalid(field + "." + key, "unknown field or explicit null");
        }
    }
    private static Map<String,Object> object(Object value, String field) {
        if (!(value instanceof Map<?,?> map)) throw invalid(field, "must be an object");
        Map<String,Object> result = new LinkedHashMap<>();
        for (var e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) throw invalid(field, "keys must be strings");
            result.put(key, e.getValue());
        }
        return result;
    }
    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 1024 || !text.equals(text.trim())
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid(field, "must be a bounded nonempty string");
        return text;
    }
    private static void identifier(String value, String field) {
        text(value, field);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw invalid(field, "must be a bounded identifier");
    }
    private static void exact(String value, String expected, String field) {
        if (!expected.equals(value)) throw invalid(field, "only " + expected + " is supported");
    }
    private static void member(String value, Set<String> values, String field) {
        if (!values.contains(value)) throw invalid(field, "unsupported value");
    }
    private static IllegalArgumentException invalid(String field, String reason) {
        return new IllegalArgumentException("invalid_product_test_" + field + ": " + reason);
    }
}
