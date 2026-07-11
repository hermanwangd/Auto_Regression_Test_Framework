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
                operations(map.get("operations"), map(map.get("operation_defaults"))),
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

    private Map<String, V03ProviderContract.V03OperationDefinition> operations(
            Object value, Map<String, Object> defaults) {
        Map<String, V03ProviderContract.V03OperationDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map(value).entrySet()) {
            Map<String, Object> definition = map(entry.getValue());
            Set<String> allowedInputs = strings(definition.get("allowed_inputs"));
            Set<String> requiredInputs = strings(definition.get("required_inputs"));
            Set<String> outputRefs = strings(definition.get("output_refs"));
            Map<String, V03InputDefinition> inputs = inputs(
                    definition.get("inputs"), allowedInputs, requiredInputs, map(defaults.get("input")));
            Map<String, V03OutputDefinition> outputs = outputs(
                    definition.get("outputs"), outputRefs, strings(definition.get("bindable_outputs")),
                    map(defaults.get("output")));
            result.put(entry.getKey(), new V03ProviderContract.V03OperationDefinition(
                    allowedInputs, requiredInputs, outputRefs, inputs, outputs, strings(definition.get("runtime_modes")),
                    phases(definition.get("phases"), defaults.get("phases"))));
        }
        return Map.copyOf(result);
    }

    private Map<String, V03InputDefinition> inputs(
            Object definitions, Set<String> allowedInputs, Set<String> requiredInputs, Map<String, Object> defaults) {
        Map<String, V03InputDefinition> result = new LinkedHashMap<>();
        Map<String, Object> typed = map(definitions);
        for (String input : allowedInputs) {
            Map<String, Object> definition = map(typed.get(input));
            Map<String, Object> effective = definition.isEmpty() ? defaults : definition;
            result.put(input, effective.isEmpty()
                    ? V03InputDefinition.legacy(requiredInputs.contains(input))
                    : new V03InputDefinition(
                            Boolean.parseBoolean(text(effective.get("required"))) || requiredInputs.contains(input),
                            V03ValueType.parse(text(effective.get("value_type"))),
                            referenceKinds(effective.get("reference_kinds")),
                            V03Sensitivity.parse(text(effective.get("sensitivity")))));
        }
        for (String input : typed.keySet()) {
            if (!result.containsKey(input)) {
                throw new IllegalArgumentException("unknown_typed_input: `" + input + "` is not listed in allowed_inputs.");
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, V03OutputDefinition> outputs(
            Object definitions, Set<String> outputRefs, Set<String> bindableOutputs, Map<String, Object> defaults) {
        Map<String, V03OutputDefinition> result = new LinkedHashMap<>();
        Map<String, Object> typed = map(definitions);
        for (String output : outputRefs) {
            Map<String, Object> definition = map(typed.get(output));
            Map<String, Object> effective = definition.isEmpty() ? defaults : definition;
            result.put(output, effective.isEmpty()
                    ? V03OutputDefinition.legacy(bindableOutputs.contains(output))
                    : new V03OutputDefinition(
                            V03ValueType.parse(text(effective.get("value_type"))),
                            V03Sensitivity.parse(text(effective.get("sensitivity"))),
                            Boolean.parseBoolean(text(effective.get("bindable"))) || bindableOutputs.contains(output),
                            !"false".equals(text(effective.get("evidence_included")))));
        }
        for (String output : typed.keySet()) {
            if (!result.containsKey(output)) {
                throw new IllegalArgumentException("unknown_typed_output: `" + output + "` is not listed in output_refs.");
            }
        }
        return Map.copyOf(result);
    }

    private Set<V03ReferenceKind> referenceKinds(Object value) {
        Set<V03ReferenceKind> result = new LinkedHashSet<>();
        for (String kind : strings(value)) result.add(V03ReferenceKind.parse(kind));
        return result.isEmpty()
                ? Set.of(V03ReferenceKind.LITERAL, V03ReferenceKind.ARTIFACT, V03ReferenceKind.STEP,
                        V03ReferenceKind.GENERATED, V03ReferenceKind.ENV)
                : Set.copyOf(result);
    }

    private Set<String> phases(Object value, Object defaultValue) {
        Set<String> phases = strings(value);
        if (phases.isEmpty()) {
            phases = strings(defaultValue);
        }
        if (phases.isEmpty()) {
            return Set.of("setup", "execute", "verify", "cleanup");
        }
        for (String phase : phases) {
            if (!Set.of("setup", "execute", "verify", "cleanup").contains(phase)) {
                throw new IllegalArgumentException("invalid_operation_phase: `" + phase + "`.");
            }
        }
        return phases;
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
