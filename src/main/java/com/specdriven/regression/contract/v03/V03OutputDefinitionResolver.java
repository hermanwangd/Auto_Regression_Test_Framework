package com.specdriven.regression.contract.v03;

/** Resolves contract-declared outputs and their nested dotted paths in one place. */
public final class V03OutputDefinitionResolver {

    public V03ResolvedOutputPath resolvePath(
            V03ProviderContract.V03OperationDefinition operation, String outputPath) {
        V03OutputDefinition exact = operation.outputDefinitions().get(outputPath);
        if (exact != null) {
            return new V03ResolvedOutputPath(outputPath, exact, "");
        }
        String declaredPath = operation.outputDefinitions().keySet().stream()
                .filter(candidate -> outputPath.startsWith(candidate + "."))
                .max(java.util.Comparator.comparingInt(String::length))
                .orElseThrow(() -> new IllegalArgumentException(
                        "missing_provider_output: `" + outputPath + "` is not declared."));
        V03OutputDefinition parent = operation.outputDefinitions().get(declaredPath);
        if (parent.valueType() != V03ValueType.OBJECT && parent.valueType() != V03ValueType.ANY) {
            throw new IllegalArgumentException("invalid_output_subpath: nested output refs require an object output.");
        }
        return new V03ResolvedOutputPath(
                declaredPath,
                parent,
                outputPath.substring(declaredPath.length() + 1));
    }

    public V03OutputDefinition resolve(V03ProviderContract.V03OperationDefinition operation, String outputPath) {
        V03ResolvedOutputPath resolved = resolvePath(operation, outputPath);
        if (resolved.exact()) {
            return resolved.definition();
        }
        return new V03OutputDefinition(
                resolved.effectiveValueType(),
                resolved.definition().sensitivity(),
                resolved.definition().bindable(),
                resolved.definition().evidenceIncluded());
    }

    public V03OutputDefinition find(V03ProviderContract.V03OperationDefinition operation, String outputPath) {
        try { return resolve(operation, outputPath); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public V03ResolvedProducedOutput resolveProducedPath(
            java.util.Collection<V03ProducedOutput> outputs, String outputPath) {
        V03ProducedOutput exact = outputs.stream()
                .filter(output -> output.outputName().equals(outputPath))
                .findFirst().orElse(null);
        if (exact != null) {
            return new V03ResolvedProducedOutput(exact, "");
        }
        V03ProducedOutput parent = outputs.stream()
                .filter(output -> outputPath.startsWith(output.outputName() + "."))
                .max(java.util.Comparator.comparingInt(output -> output.outputName().length()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "missing_provider_output: `" + outputPath + "` is not declared."));
        if (parent.valueType() != V03ValueType.OBJECT && parent.valueType() != V03ValueType.ANY) {
            throw new IllegalArgumentException("invalid_output_subpath: nested output refs require an object output.");
        }
        return new V03ResolvedProducedOutput(
                parent, outputPath.substring(parent.outputName().length() + 1));
    }
}
