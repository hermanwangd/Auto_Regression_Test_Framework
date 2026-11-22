package com.specdriven.regression.sit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SitReadinessReport(SitReadinessState state, Instant startedAt, Instant finishedAt,
        List<Check> checks) {
    public SitReadinessReport { checks = List.copyOf(checks); }
    public boolean ready() { return state == SitReadinessState.READY; }
    public Map<String, Object> toMap() {
        return Map.of("contract_version", "sit-readiness.v1", "status", state.name(),
                "started_at", startedAt.toString(), "finished_at", finishedAt.toString(),
                "checks", checks.stream().map(Check::toMap).toList());
    }

    public record Check(String id, String kind, String adapter, String adapterVersion, boolean simulated,
            SitReadinessState state, String expectedVersion, String observedVersion, Instant observedAt,
            Map<String, String> evidence, String code, String ownerAction) {
        public Check { evidence = Map.copyOf(evidence); }
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id); map.put("kind", kind); map.put("adapter", adapter);
            map.put("adapter_version", adapterVersion); map.put("simulated", simulated);
            map.put("status", state.name()); map.put("expected_version", expectedVersion);
            map.put("observed_version", observedVersion);
            map.put("observed_at", observedAt == null ? null : observedAt.toString());
            map.put("evidence", evidence); map.put("code", code); map.put("owner_action", ownerAction);
            return map;
        }
    }
}
