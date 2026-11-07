package com.specdriven.regression.contract.v03;

/** Runtime-safe profile metadata retained after v0.3 YAML compilation. */
public record V03EnvironmentProfile(
        String profileId,
        String executionMode,
        String isolation,
        String evidenceClassification,
        boolean downstreamReleaseEvidence,
        Object sitReadiness,
        Object productTest) {
    public V03EnvironmentProfile {
        sitReadiness = immutable(sitReadiness);
        productTest = immutable(productTest);
    }

    public V03EnvironmentProfile(String profileId, String executionMode, String isolation,
            String evidenceClassification, boolean downstreamReleaseEvidence) {
        this(profileId, executionMode, isolation, evidenceClassification, downstreamReleaseEvidence, null, null);
    }

    public V03EnvironmentProfile(String profileId, String executionMode, String isolation,
            String evidenceClassification, boolean downstreamReleaseEvidence, Object sitReadiness) {
        this(profileId, executionMode, isolation, evidenceClassification, downstreamReleaseEvidence, sitReadiness, null);
    }

    private static Object immutable(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            var copy = new java.util.LinkedHashMap<Object, Object>();
            map.forEach((key, entry) -> copy.put(key, immutable(entry)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.List<?> list) {
            return java.util.Collections.unmodifiableList(list.stream().map(V03EnvironmentProfile::immutable).toList());
        }
        return value;
    }
}
