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

    private final ProviderCapabilityRegistry providerCapabilityRegistry;

    public ProviderContractResolver() {
        this(new ProviderCapabilityRegistry());
    }

    ProviderContractResolver(ProviderCapabilityRegistry providerCapabilityRegistry) {
        this.providerCapabilityRegistry = providerCapabilityRegistry;
    }

    public ProviderContractResolutionReport resolve(
            Path mappingYaml,
            String adapter,
            List<String> bindingTypes,
            List<String> fixtureProviders) {
        return resolve(mappingYaml, "", adapter, bindingTypes, fixtureProviders);
    }

    public ProviderContractResolutionReport resolve(
            Path mappingYaml,
            String targetRuId,
            String adapter,
            List<String> bindingTypes,
            List<String> fixtureProviders) {
        List<ReleaseUnitContracts> releaseUnits = releaseUnits(mappingYaml);
        List<ResolvedProviderContract> resolved = new ArrayList<>();
        List<ProviderContractGap> gaps = new ArrayList<>();

        ReleaseUnitContracts adapterOwner = ownerForAdapter(releaseUnits, targetRuId, adapter);
        if (hasContract(adapterOwner.contracts(), "adapters", adapter)) {
            resolveContract(resolved, gaps, adapterOwner, "adapters", "adapter", adapter);
        } else {
            gaps.add(gap(adapterOwner, "adapters", "adapter", adapter, "",
                    "Add provider contract `" + adapter + "` under "
                            + path(adapterOwner, "adapters", adapter)
                            + " for `" + adapterOwner.ruId() + "` before execution."));
        }

        for (String bindingType : bindingTypes) {
            ReleaseUnitContracts owner = ownerFor(releaseUnits, "bindings", bindingType, adapterOwner);
            if (hasContract(owner.contracts(), "bindings", bindingType)) {
                resolveContract(resolved, gaps, owner, "bindings", "binding", bindingType);
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
                resolveContract(resolved, gaps, owner, "fixtures", "fixture", fixtureProvider);
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

    private void resolveContract(
            List<ResolvedProviderContract> resolved,
            List<ProviderContractGap> gaps,
            ReleaseUnitContracts owner,
            String section,
            String contractType,
            String providerName) {
        Map<String, Object> contract = contract(owner.contracts(), section, providerName);
        ProviderCapabilityRegistry.ProviderContractValidation validation =
                providerCapabilityRegistry.validate(section, providerName, contract, owner.executionMode());
        if (validation.ready()) {
            resolved.add(resolved(owner, section, contractType, providerName, validation));
            return;
        }
        for (ProviderCapabilityRegistry.ProviderContractViolation violation : validation.violations()) {
            gaps.add(gap(
                    owner,
                    section,
                    contractType,
                    providerName,
                    violation.pathSuffix(),
                    validation.providerFamily(),
                    validation.providerType(),
                    violation.registryStatus(),
                    violation.runtimeStatus(),
                    ownerAction(owner, violation.ownerAction())));
        }
    }

    private ResolvedProviderContract resolved(
            ReleaseUnitContracts owner,
            String section,
            String contractType,
            String providerName,
            ProviderCapabilityRegistry.ProviderContractValidation validation) {
        return new ResolvedProviderContract(
                contractType,
                providerName,
                "ru",
                validation.providerFamily(),
                validation.providerType(),
                validation.registryStatus(),
                validation.runtimeStatus(),
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

    private ProviderContractGap gap(
            ReleaseUnitContracts owner,
            String section,
            String contractType,
            String providerName,
            String pathSuffix,
            String providerFamily,
            String providerType,
            String registryStatus,
            String runtimeStatus,
            String ownerAction) {
        return new ProviderContractGap(
                path(owner, section, providerName) + pathSuffix,
                contractType,
                providerName,
                providerFamily,
                providerType,
                registryStatus,
                runtimeStatus,
                owner.ruId(),
                providerName,
                ownerAction);
    }

    private String ownerAction(ReleaseUnitContracts owner, String ownerAction) {
        if (owner.ruId().isBlank() || ownerAction.contains(owner.ruId())) {
            return ownerAction;
        }
        return ownerAction + " Affected RU: `" + owner.ruId() + "`.";
    }

    private ReleaseUnitContracts ownerForAdapter(
            List<ReleaseUnitContracts> releaseUnits,
            String targetRuId,
            String adapter) {
        if (!targetRuId.isBlank()) {
            ReleaseUnitContracts targetOwner = ownerByRuId(releaseUnits, targetRuId);
            if (targetOwner != null) {
                return targetOwner;
            }
        }
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (unit.adapter().equals(adapter)) {
                return unit;
            }
        }
        return ownerFor(releaseUnits, "adapters", adapter, fallbackUnit(releaseUnits));
    }

    private ReleaseUnitContracts ownerByRuId(List<ReleaseUnitContracts> releaseUnits, String targetRuId) {
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (unit.ruId().equals(targetRuId)) {
                return unit;
            }
        }
        return null;
    }

    private ReleaseUnitContracts ownerFor(
            List<ReleaseUnitContracts> releaseUnits,
            String section,
            String name,
            ReleaseUnitContracts fallback) {
        if (hasContract(fallback.contracts(), section, name)) {
            return fallback;
        }
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (hasContract(unit.contracts(), section, name)) {
                return unit;
            }
        }
        return fallback;
    }

    private ReleaseUnitContracts fallbackUnit(List<ReleaseUnitContracts> releaseUnits) {
        if (releaseUnits.isEmpty()) {
            return new ReleaseUnitContracts(0, "", "", "", "", Map.of());
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
                        stringValue(unit.get("execution_mode")),
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
            String executionMode,
            Map<String, Object> contracts) {
    }
}
