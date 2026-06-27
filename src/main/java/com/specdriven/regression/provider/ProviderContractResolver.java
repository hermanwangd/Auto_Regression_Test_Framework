package com.specdriven.regression.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class ProviderContractResolver {

    public ProviderContractResolutionReport resolve(
            Path mappingYaml,
            String adapter,
            List<String> bindingTypes,
            List<String> fixtureProviders) {
        List<ReleaseUnitContracts> releaseUnits = releaseUnits(mappingYaml);
        List<ResolvedProviderContract> resolved = new ArrayList<>();
        List<ProviderContractGap> gaps = new ArrayList<>();

        ReleaseUnitContracts adapterOwner = ownerForAdapter(releaseUnits, adapter);
        if (hasRequiredAdapterContract(adapterOwner, adapter)) {
            resolved.add(resolved(adapterOwner, "adapters", "adapter", adapter));
        } else if (hasContract(adapterOwner.contracts(), "adapters", adapter)) {
            AdapterGapDetail detail = adapterGapDetail(adapterOwner, adapter);
            gaps.add(gap(
                    adapterOwner,
                    "adapters",
                    "adapter",
                    adapter,
                    detail.pathSuffix(),
                    detail.ownerAction()));
        } else {
            gaps.add(gap(adapterOwner, "adapters", "adapter", adapter, "",
                    "Add provider contract `" + adapter + "` under "
                            + path(adapterOwner, "adapters", adapter)
                            + " for `" + adapterOwner.ruId() + "` before execution."));
        }

        for (String bindingType : bindingTypes) {
            ReleaseUnitContracts owner = ownerFor(releaseUnits, "bindings", bindingType, adapterOwner);
            if (hasContract(owner.contracts(), "bindings", bindingType)) {
                resolved.add(resolved(owner, "bindings", "binding", bindingType));
            } else {
                gaps.add(gap(owner, "bindings", "binding", bindingType, "",
                        "Add provider contract `" + bindingType + "` under "
                                + path(owner, "bindings", bindingType)
                                + " for `" + owner.ruId() + "` before execution."));
            }
        }

        for (String fixtureProvider : fixtureProviders) {
            ReleaseUnitContracts owner = ownerFor(releaseUnits, "fixtures", fixtureProvider, adapterOwner);
            if (hasContract(owner.contracts(), "fixtures", fixtureProvider)) {
                resolved.add(resolved(owner, "fixtures", "fixture", fixtureProvider));
            } else {
                gaps.add(gap(owner, "fixtures", "fixture", fixtureProvider, "",
                        "Add provider contract `" + fixtureProvider + "` under "
                                + path(owner, "fixtures", fixtureProvider)
                                + " for `" + owner.ruId() + "` before execution."));
            }
        }

        return new ProviderContractResolutionReport(
                gaps.isEmpty(),
                List.copyOf(resolved),
                List.copyOf(gaps));
    }

    private ResolvedProviderContract resolved(
            ReleaseUnitContracts owner,
            String section,
            String contractType,
            String providerName) {
        return new ResolvedProviderContract(
                contractType,
                providerName,
                "ru",
                providerFamily(contract(owner.contracts(), section, providerName), section, providerName, owner),
                owner.ruId(),
                providerName,
                path(owner, section, providerName));
    }

    private ProviderContractGap gap(
            ReleaseUnitContracts owner,
            String section,
            String contractType,
            String providerName,
            String pathSuffix,
            String ownerAction) {
        return new ProviderContractGap(
                path(owner, section, providerName) + pathSuffix,
                contractType,
                providerName,
                providerFamily(contract(owner.contracts(), section, providerName), section, providerName, owner),
                owner.ruId(),
                providerName,
                ownerAction);
    }

    private ReleaseUnitContracts ownerForAdapter(List<ReleaseUnitContracts> releaseUnits, String adapter) {
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (unit.adapter().equals(adapter)) {
                return unit;
            }
        }
        return ownerFor(releaseUnits, "adapters", adapter, fallbackUnit(releaseUnits));
    }

    private ReleaseUnitContracts ownerFor(
            List<ReleaseUnitContracts> releaseUnits,
            String section,
            String name,
            ReleaseUnitContracts fallback) {
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (hasContract(unit.contracts(), section, name)) {
                return unit;
            }
        }
        return fallback;
    }

    private ReleaseUnitContracts fallbackUnit(List<ReleaseUnitContracts> releaseUnits) {
        if (releaseUnits.isEmpty()) {
            return new ReleaseUnitContracts(0, "", "", "", Map.of());
        }
        return releaseUnits.get(0);
    }

    private String path(ReleaseUnitContracts owner, String section, String providerName) {
        return "release_units[" + owner.index() + "].provider_contracts." + section + "." + providerName;
    }

    private boolean hasContract(Map<String, Object> contracts, String section, String name) {
        Object value = contracts.get(section);
        return value instanceof Map<?, ?> map && map.containsKey(name);
    }

    private boolean hasRequiredAdapterContract(ReleaseUnitContracts owner, String adapter) {
        Map<String, Object> contractMap = contract(owner.contracts(), "adapters", adapter);
        if (contractMap.isEmpty()) {
            return false;
        }
        String family = providerFamily(contractMap, "adapters", adapter, owner);
        if (family.equals("request_response")) {
            return hasAnyText(contractMap, "endpoint_ref", "base_url_ref", "service_ref")
                    && hasMap(contractMap, "actions");
        }
        if (family.equals("messaging")) {
            return hasAnyText(contractMap, "topic_ref", "subject_ref", "stream_ref", "endpoint_ref");
        }
        if (family.equals("deployment_readiness")) {
            return hasAnyText(contractMap, "readiness_probe", "target_selector", "deployment_ref", "service_ref");
        }
        if (family.equals("external_runner")) {
            return hasAnyText(contractMap, "command", "container_ref");
        }
        return hasAnyText(contractMap, "command");
    }

    private AdapterGapDetail adapterGapDetail(ReleaseUnitContracts owner, String adapter) {
        Map<String, Object> contractMap = contract(owner.contracts(), "adapters", adapter);
        String family = providerFamily(contractMap, "adapters", adapter, owner);
        if (family.equals("request_response")) {
            if (!hasAnyText(contractMap, "endpoint_ref", "base_url_ref", "service_ref")) {
                return new AdapterGapDetail(".endpoint_ref",
                        "Declare endpoint_ref, base_url_ref, or service_ref for `" + adapter
                                + "` on `" + owner.ruId() + "` before execution.");
            }
            return new AdapterGapDetail(".actions",
                    "Declare at least one request/response action for `" + adapter
                            + "` on `" + owner.ruId() + "` before execution.");
        }
        if (family.equals("messaging")) {
            return new AdapterGapDetail(".topic_ref",
                    "Declare topic_ref, subject_ref, stream_ref, or endpoint_ref for `" + adapter
                            + "` on `" + owner.ruId() + "` before execution.");
        }
        if (family.equals("deployment_readiness")) {
            return new AdapterGapDetail(".readiness_probe",
                    "Declare readiness_probe, target_selector, deployment_ref, or service_ref for `" + adapter
                            + "` on `" + owner.ruId() + "` before execution.");
        }
        if (family.equals("external_runner")) {
            return new AdapterGapDetail(".command",
                    "Declare command or container_ref for external runner `" + adapter
                            + "` on `" + owner.ruId() + "` before execution.");
        }
        return new AdapterGapDetail(".command",
                "Declare executable adapter command for `" + adapter + "` on `"
                        + owner.ruId() + "` before execution.");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> contract(Map<String, Object> contracts, String section, String name) {
        Object sectionValue = contracts.get(section);
        if (!(sectionValue instanceof Map<?, ?> sectionMap)) {
            return Map.of();
        }
        Object contract = sectionMap.get(name);
        if (contract instanceof Map<?, ?> contractMap) {
            return (Map<String, Object>) contractMap;
        }
        return Map.of();
    }

    private String providerFamily(
            Map<String, Object> contract,
            String section,
            String providerName,
            ReleaseUnitContracts owner) {
        String declared = stringValue(contract.get("provider_family"));
        if (!declared.isBlank()) {
            return declared;
        }
        String providerType = stringValue(contract.get("provider_type")).toLowerCase(Locale.ROOT);
        String normalizedName = providerName.toLowerCase(Locale.ROOT);
        String boundary = owner.validationBoundary().toLowerCase(Locale.ROOT);
        if (providerType.equals("rest") || providerType.equals("grpc")
                || normalizedName.contains("request") || boundary.contains("request")) {
            return "request_response";
        }
        if (providerType.equals("kafka") || providerType.equals("nats")
                || normalizedName.contains("message") || normalizedName.contains("event")
                || boundary.contains("event")) {
            return "messaging";
        }
        if (providerType.contains("db") || normalizedName.contains("db")
                || normalizedName.contains("relational")) {
            return "db_fixture";
        }
        if (providerType.contains("k8s") || providerType.contains("vm")
                || normalizedName.contains("readiness") || boundary.contains("deployed")) {
            return "deployment_readiness";
        }
        if (normalizedName.contains("runner") || providerType.contains("runner")) {
            return "external_runner";
        }
        if (section.equals("adapters") || normalizedName.contains("file")
                || normalizedName.contains("batch") || normalizedName.contains("cli")
                || providerType.contains("shell")) {
            return "file_batch";
        }
        return section.substring(0, section.length() - 1);
    }

    @SuppressWarnings("unchecked")
    private List<ReleaseUnitContracts> releaseUnits(Path mappingYaml) {
        try {
            Object loaded = new Yaml().load(Files.readString(mappingYaml));
            if (!(loaded instanceof Map<?, ?> root)) {
                return List.of();
            }
            Object unitsValue = root.get("release_units");
            if (!(unitsValue instanceof List<?> units)) {
                return List.of();
            }
            List<ReleaseUnitContracts> releaseUnitContracts = new ArrayList<>();
            for (int index = 0; index < units.size(); index++) {
                Object entry = units.get(index);
                if (!(entry instanceof Map<?, ?> unit)) {
                    continue;
                }
                Object contracts = unit.get("provider_contracts");
                Map<String, Object> contractMap = contracts instanceof Map<?, ?> map
                        ? (Map<String, Object>) map
                        : Map.of();
                releaseUnitContracts.add(new ReleaseUnitContracts(
                        index,
                        stringValue(unit.get("ru_id")),
                        stringValue(unit.get("adapter")),
                        stringValue(unit.get("validation_boundary")),
                        contractMap));
            }
            return List.copyOf(releaseUnitContracts);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read provider contracts: " + mappingYaml, e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record ReleaseUnitContracts(
            int index,
            String ruId,
            String adapter,
            String validationBoundary,
            Map<String, Object> contracts) {
    }

    private record AdapterGapDetail(String pathSuffix, String ownerAction) {
    }
}
