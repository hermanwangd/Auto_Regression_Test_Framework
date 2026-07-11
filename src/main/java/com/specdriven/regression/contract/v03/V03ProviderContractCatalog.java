package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.FrameworkProviderContractCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** Loads the bundled v0.3 contract catalog into immutable typed records. */
public final class V03ProviderContractCatalog {
    private static final Path FRAMEWORK_CONTRACTS = Path.of("docs/02-architecture/contracts/provider-contracts");
    private final Yaml yaml = new Yaml();

    public Map<String, V03ProviderContract> load(Path suiteRoot) {
        Path directory = FrameworkProviderContractCatalog.resolveDirectory(suiteRoot, FRAMEWORK_CONTRACTS);
        Map<String, V03ProviderContract> contracts = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .forEach(path -> add(contracts, path));
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load v0.3 Provider Contracts.", error);
        }
        return Map.copyOf(contracts);
    }

    private void add(Map<String, V03ProviderContract> contracts, Path path) {
        Map<String, Object> map;
        try {
            map = map(yaml.load(Files.readString(path)));
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read Provider Contract `" + path + "`.", error);
        }
        if (!"v0.3".equals(text(map.get("contract_version")))) {
            return;
        }
        String id = text(map.get("provider_contract"));
        if (id.isBlank()) {
            return;
        }
        V03ProviderContract previous = contracts.putIfAbsent(id, new V03ProviderContract(
                id,
                text(map.get("provider_type")),
                strings(map.get("runtime_modes")),
                bindings(map.get("binding_keys")),
                operations(map.get("operations")),
                strings(map.get("bindable_outputs")),
                strings(map(map.get("evidence")).get("outputs")),
                strings(map(map.get("failure_mapping")).get("allowed_codes"))));
        if (previous != null) {
            throw new IllegalArgumentException("duplicate_provider_contract: `" + id + "`.");
        }
    }

    private Map<String, V03ProviderContract.V03BindingDefinition> bindings(Object value) {
        Map<String, V03ProviderContract.V03BindingDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map(value).entrySet()) {
            Map<String, Object> definition = map(entry.getValue());
            result.put(entry.getKey(), new V03ProviderContract.V03BindingDefinition(
                    Boolean.parseBoolean(text(definition.get("required"))), text(definition.get("source"))));
        }
        return Map.copyOf(result);
    }

    private Map<String, V03ProviderContract.V03OperationDefinition> operations(Object value) {
        Map<String, V03ProviderContract.V03OperationDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map(value).entrySet()) {
            Map<String, Object> definition = map(entry.getValue());
            result.put(entry.getKey(), new V03ProviderContract.V03OperationDefinition(
                    strings(definition.get("allowed_inputs")),
                    strings(definition.get("required_inputs")),
                    strings(definition.get("output_refs"))));
        }
        return Map.copyOf(result);
    }

    private Set<String> strings(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                String text = text(item);
                if (!text.isBlank()) result.add(text);
            }
        }
        return Set.copyOf(result);
    }

    private Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) {
            values.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
