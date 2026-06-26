package com.specdriven.regression.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        Map<String, Object> contracts = providerContracts(mappingYaml);
        List<ResolvedProviderContract> resolved = new ArrayList<>();
        List<ProviderContractGap> gaps = new ArrayList<>();

        if (hasRequiredAdapterContract(contracts, adapter)) {
            resolved.add(new ResolvedProviderContract("adapter", adapter, "ru"));
        } else if (hasContract(contracts, "adapters", adapter)) {
            gaps.add(new ProviderContractGap(
                    "provider_contracts.adapters." + adapter + ".command",
                    "adapter",
                    adapter,
                    "Declare executable adapter command before execution."));
        } else {
            gaps.add(gap("adapters", adapter));
        }

        for (String bindingType : bindingTypes) {
            if (hasContract(contracts, "bindings", bindingType)) {
                resolved.add(new ResolvedProviderContract("binding", bindingType, "ru"));
            } else {
                gaps.add(gap("bindings", bindingType));
            }
        }

        for (String fixtureProvider : fixtureProviders) {
            if (hasContract(contracts, "fixtures", fixtureProvider)) {
                resolved.add(new ResolvedProviderContract("fixture", fixtureProvider, "ru"));
            } else {
                gaps.add(gap("fixtures", fixtureProvider));
            }
        }

        return new ProviderContractResolutionReport(
                gaps.isEmpty(),
                List.copyOf(resolved),
                List.copyOf(gaps));
    }

    private ProviderContractGap gap(String section, String name) {
        return new ProviderContractGap(
                "provider_contracts." + section + "." + name,
                section.substring(0, section.length() - 1),
                name,
                "Add provider contract `" + name + "` under provider_contracts." + section + " before execution.");
    }

    private boolean hasContract(Map<String, Object> contracts, String section, String name) {
        Object value = contracts.get(section);
        return value instanceof Map<?, ?> map && map.containsKey(name);
    }

    private boolean hasRequiredAdapterContract(Map<String, Object> contracts, String adapter) {
        Object value = contracts.get("adapters");
        if (!(value instanceof Map<?, ?> adapters)) {
            return false;
        }
        Object contract = adapters.get(adapter);
        if (!(contract instanceof Map<?, ?> contractMap)) {
            return false;
        }
        Object command = contractMap.get("command");
        return command instanceof String text && !text.isBlank();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> providerContracts(Path mappingYaml) {
        try {
            Object loaded = new Yaml().load(Files.readString(mappingYaml));
            if (!(loaded instanceof Map<?, ?> root)) {
                return Map.of();
            }
            Object unitsValue = root.get("release_units");
            if (!(unitsValue instanceof List<?> units) || units.isEmpty() || !(units.get(0) instanceof Map<?, ?> unit)) {
                return Map.of();
            }
            Object contracts = unit.get("provider_contracts");
            if (contracts instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read provider contracts: " + mappingYaml, e);
        }
    }
}
