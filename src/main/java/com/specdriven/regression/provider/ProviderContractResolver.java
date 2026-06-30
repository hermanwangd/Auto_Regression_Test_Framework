package com.specdriven.regression.provider;

import com.specdriven.regression.runtime.GeneratedRuntimeArtifacts;
import com.specdriven.regression.runtime.GeneratedRuntimeArtifacts.ProviderContractRef;
import com.specdriven.regression.runtime.GeneratedRuntimeContext;
import com.specdriven.regression.runtime.GeneratedRuntimeGap;
import com.specdriven.regression.runtime.GeneratedRuntimeTarget;
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
    private final GeneratedRuntimeArtifacts generatedRuntimeArtifacts;

    public ProviderContractResolver() {
        this(new ProviderCapabilityRegistry(), new GeneratedRuntimeArtifacts());
    }

    ProviderContractResolver(ProviderCapabilityRegistry providerCapabilityRegistry) {
        this(providerCapabilityRegistry, new GeneratedRuntimeArtifacts());
    }

    ProviderContractResolver(
            ProviderCapabilityRegistry providerCapabilityRegistry,
            GeneratedRuntimeArtifacts generatedRuntimeArtifacts) {
        this.providerCapabilityRegistry = providerCapabilityRegistry;
        this.generatedRuntimeArtifacts = generatedRuntimeArtifacts;
    }

    public ProviderContractResolutionReport resolveGenerated(
            Path packageRoot,
            String requestedProfile,
            String targetId,
            String adapter,
            List<String> bindingTypes,
            List<String> fixtureProviders) {
        GeneratedRuntimeContext context = generatedRuntimeArtifacts.resolve(packageRoot, requestedProfile);
        List<ResolvedProviderContract> resolved = new ArrayList<>();
        List<ProviderContractGap> gaps = new ArrayList<>();
        for (GeneratedRuntimeGap gap : context.gaps()) {
            gaps.add(new ProviderContractGap(
                    gap.fieldPath(),
                    "runtime_config",
                    "",
                    "",
                    "",
                    "missing",
                    "blocked",
                    targetId,
                    "",
                    gap.ownerAction()));
        }
        List<GeneratedRuntimeTarget> matchingTargets = context.matchingTargets(targetId, adapter);
        if (targetId.isBlank() && matchingTargets.size() > 1) {
            gaps.add(ambiguousGeneratedGap(packageRoot, matchingTargets, adapter));
            return new ProviderContractResolutionReport(false, List.of(), List.copyOf(gaps));
        }
        GeneratedRuntimeTarget target = context.target(targetId, adapter);
        if (target == null) {
            gaps.add(new ProviderContractGap(
                    "generated-framework/environment_bindings.targets",
                    "provider",
                    adapter,
                    "",
                    "",
                    "missing",
                    "blocked",
                    targetId,
                    adapter,
                    "Generate environment binding target for `" + targetId + "` before execution."));
            return new ProviderContractResolutionReport(false, List.of(), List.copyOf(gaps));
        }

        ProviderContractRef adapterRef = generatedRuntimeArtifacts.providerContractRef(target.providerContractRef());
        String fileRef = adapterRef.fileRef();
        String adapterProviderName = firstNonBlank(adapterRef.providerName(), adapter);
        resolveGeneratedContract(
                packageRoot,
                resolved,
                gaps,
                target,
                fileRef,
                "providers",
                "provider",
                adapterProviderName,
                context.executionMode());

        for (String bindingType : bindingTypes) {
            resolveGeneratedContract(
                    packageRoot,
                    resolved,
                    gaps,
                    target,
                    fileRef,
                    "bindings",
                    "binding",
                    bindingType,
                    context.executionMode());
        }
        for (String fixtureProvider : fixtureProviders) {
            resolveGeneratedContract(
                    packageRoot,
                    resolved,
                    gaps,
                    target,
                    fileRef,
                    "fixtures",
                    "fixture",
                    fixtureProvider,
                    context.executionMode());
        }
        return new ProviderContractResolutionReport(gaps.isEmpty(), List.copyOf(resolved), List.copyOf(gaps));
    }

    public ProviderContractResolutionReport resolve(
            Path mappingYaml,
            String adapter,
            List<String> bindingTypes,
            List<String> fixtureProviders) {
        return resolve(mappingYaml, "", adapter, bindingTypes, fixtureProviders);
    }

    public Map<String, Object> generatedAdapterContract(
            Path packageRoot,
            String requestedProfile,
            String targetId,
            String adapter) {
        GeneratedRuntimeContext context = generatedRuntimeArtifacts.resolve(packageRoot, requestedProfile);
        GeneratedRuntimeTarget target = context.target(targetId, adapter);
        if (target == null) {
            return Map.of();
        }
        ProviderContractRef ref = generatedRuntimeArtifacts.providerContractRef(target.providerContractRef());
        return generatedRuntimeArtifacts.providerContract(
                packageRoot,
                ref.fileRef(),
                "providers",
                firstNonBlank(ref.providerName(), adapter));
    }

    public Map<String, Object> generatedFixtureContract(
            Path packageRoot,
            String requestedProfile,
            String targetId,
            String adapter,
            String fixtureProvider) {
        GeneratedRuntimeContext context = generatedRuntimeArtifacts.resolve(packageRoot, requestedProfile);
        GeneratedRuntimeTarget target = context.target(targetId, adapter);
        if (target == null) {
            return Map.of();
        }
        ProviderContractRef ref = generatedRuntimeArtifacts.providerContractRef(target.providerContractRef());
        return generatedRuntimeArtifacts.providerContract(packageRoot, ref.fileRef(), "fixtures", fixtureProvider);
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

        List<ReleaseUnitContracts> adapterOwners = ownersForAdapter(releaseUnits, targetRuId, adapter);
        if (adapterOwners.size() > 1) {
            gaps.add(ambiguousGap(adapterOwners, "providers", "provider", adapter));
            return new ProviderContractResolutionReport(false, List.of(), List.copyOf(gaps));
        }
        ReleaseUnitContracts adapterOwner = adapterOwners.get(0);
        if (hasContract(adapterOwner.contracts(), "providers", adapter)) {
            resolveContract(resolved, gaps, adapterOwner, "providers", "provider", adapter);
        } else {
            gaps.add(gap(adapterOwner, "providers", "provider", adapter, "",
                    "Add provider contract `" + adapter + "` under "
                            + path(adapterOwner, "providers", adapter)
                            + " for `" + adapterOwner.ruId() + "` before execution."));
        }

        for (String bindingType : bindingTypes) {
            List<ReleaseUnitContracts> owners = ownersFor(releaseUnits, "bindings", bindingType, adapterOwner);
            if (owners.size() > 1) {
                gaps.add(ambiguousGap(owners, "bindings", "binding", bindingType));
                continue;
            }
            ReleaseUnitContracts owner = owners.get(0);
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
            List<ReleaseUnitContracts> owners = ownersFor(releaseUnits, "fixtures", fixtureProvider, adapterOwner);
            if (owners.size() > 1) {
                gaps.add(ambiguousGap(owners, "fixtures", "fixture", fixtureProvider));
                continue;
            }
            ReleaseUnitContracts owner = owners.get(0);
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

    private void resolveGeneratedContract(
            Path packageRoot,
            List<ResolvedProviderContract> resolved,
            List<ProviderContractGap> gaps,
            GeneratedRuntimeTarget target,
            String fileRef,
            String section,
            String contractType,
            String providerName,
            String executionMode) {
        if (providerName.isBlank()) {
            gaps.add(new ProviderContractGap(
                    "generated-framework/provider_contracts",
                    contractType,
                    providerName,
                    "",
                    "",
                    "missing",
                    "blocked",
                    target.targetId(),
                    providerName,
                    "Generate " + contractType + " provider contract before execution."));
            return;
        }
        Map<String, Object> contract =
                generatedRuntimeArtifacts.providerContract(packageRoot, fileRef, section, providerName);
        String contractPath = generatedRuntimeArtifacts.contractPath(
                fileRef + "#" + section + "." + providerName,
                contract);
        if (contract.isEmpty()) {
            gaps.add(new ProviderContractGap(
                    contractPath,
                    contractType,
                    providerName,
                    "",
                    "",
                    "missing",
                    "blocked",
                    target.targetId(),
                    providerName,
                    "Generate provider contract `" + providerName + "` under generated provider contracts before execution."));
            return;
        }
        ProviderCapabilityRegistry.ProviderContractValidation validation =
                providerCapabilityRegistry.validate(section, providerName, contract, executionMode);
        if (validation.ready()) {
            resolved.add(new ResolvedProviderContract(
                    contractType,
                    providerName,
                    "generated",
                    validation.providerFamily(),
                    validation.providerType(),
                    validation.registryStatus(),
                    validation.runtimeStatus(),
                    target.targetId(),
                    providerName,
                    contractPath));
            return;
        }
        for (ProviderCapabilityRegistry.ProviderContractViolation violation : validation.violations()) {
            gaps.add(new ProviderContractGap(
                    contractPath + violation.pathSuffix(),
                    contractType,
                    providerName,
                    validation.providerFamily(),
                    validation.providerType(),
                    violation.registryStatus(),
                    violation.runtimeStatus(),
                    target.targetId(),
                    providerName,
                    ownerAction(target.targetId(), violation.ownerAction())));
        }
    }

    private ProviderContractGap ambiguousGeneratedGap(
            Path packageRoot,
            List<GeneratedRuntimeTarget> targets,
            String providerName) {
        GeneratedRuntimeTarget first = targets.get(0);
        ProviderContractRef ref = generatedRuntimeArtifacts.providerContractRef(first.providerContractRef());
        String resolvedProviderName = firstNonBlank(ref.providerName(), providerName);
        Map<String, Object> contract = generatedRuntimeArtifacts.providerContract(
                packageRoot,
                ref.fileRef(),
                "providers",
                resolvedProviderName);
        return new ProviderContractGap(
                generatedRuntimeArtifacts.contractPath(
                        ref.fileRef() + "#providers." + resolvedProviderName,
                        contract),
                "provider",
                resolvedProviderName,
                stringValue(contract.get("provider_contract_kind")),
                stringValue(contract.get("provider_type")),
                "ambiguous",
                "blocked",
                targetIds(targets),
                resolvedProviderName,
                "Select target ID so provider contract `" + resolvedProviderName
                        + "` resolves to one execution target only. Candidate targets: "
                        + targetIds(targets) + ".");
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

    private String ownerAction(String targetId, String ownerAction) {
        if (targetId.isBlank() || ownerAction.contains(targetId)) {
            return ownerAction;
        }
        return ownerAction + " Affected target: `" + targetId + "`.";
    }

    private List<ReleaseUnitContracts> ownersForAdapter(
            List<ReleaseUnitContracts> releaseUnits,
            String targetRuId,
            String adapter) {
        if (!targetRuId.isBlank()) {
            ReleaseUnitContracts targetOwner = ownerByRuId(releaseUnits, targetRuId);
            if (targetOwner != null) {
                return List.of(targetOwner);
            }
        }
        List<ReleaseUnitContracts> adapterMatches = new ArrayList<>();
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (unit.adapter().equals(adapter)) {
                adapterMatches.add(unit);
            }
        }
        if (!adapterMatches.isEmpty()) {
            return List.copyOf(adapterMatches);
        }
        return ownersFor(releaseUnits, "providers", adapter, fallbackUnit(releaseUnits));
    }

    private ReleaseUnitContracts ownerByRuId(List<ReleaseUnitContracts> releaseUnits, String targetRuId) {
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (unit.ruId().equals(targetRuId)) {
                return unit;
            }
        }
        return null;
    }

    private List<ReleaseUnitContracts> ownersFor(
            List<ReleaseUnitContracts> releaseUnits,
            String section,
            String name,
            ReleaseUnitContracts fallback) {
        if (hasContract(fallback.contracts(), section, name)) {
            return List.of(fallback);
        }
        List<ReleaseUnitContracts> matches = new ArrayList<>();
        for (ReleaseUnitContracts unit : releaseUnits) {
            if (hasContract(unit.contracts(), section, name)) {
                matches.add(unit);
            }
        }
        if (!matches.isEmpty()) {
            return List.copyOf(matches);
        }
        return List.of(fallback);
    }

    private ProviderContractGap ambiguousGap(
            List<ReleaseUnitContracts> owners,
            String section,
            String contractType,
            String providerName) {
        ReleaseUnitContracts first = owners.get(0);
        Map<String, Object> contract = contract(first.contracts(), section, providerName);
        return new ProviderContractGap(
                path(first, section, providerName),
                contractType,
                providerName,
                providerFamily(contract, section, providerName, first),
                stringValue(contract.get("provider_type")),
                "ambiguous",
                "blocked",
                ruIds(owners),
                providerName,
                "Select target RU or rename provider contract `" + providerName
                        + "` so it resolves to one RU only. Candidate RUs: " + ruIds(owners) + ".");
    }

    private String ruIds(List<ReleaseUnitContracts> owners) {
        return String.join(",", owners.stream()
                .map(ReleaseUnitContracts::ruId)
                .filter(ruId -> !ruId.isBlank())
                .toList());
    }

    private String targetIds(List<GeneratedRuntimeTarget> targets) {
        return String.join(",", targets.stream()
                .map(GeneratedRuntimeTarget::targetId)
                .filter(targetId -> !targetId.isBlank())
                .toList());
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
        String declared = stringValue(contract.get("provider_contract_kind"));
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
        if (section.equals("providers") || normalizedName.contains("file")
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
                        firstText(unit, "provider", "runner"),
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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

    private record ReleaseUnitContracts(
            int index,
            String ruId,
            String adapter,
            String validationBoundary,
            String executionMode,
            Map<String, Object> contracts) {
    }
}
