package com.specdriven.regression.contract.v03;

/** Resolves exact declared outputs before allowing an object-root subpath fallback. */
public final class V03OutputDefinitionResolver {
    public V03OutputDefinition resolve(V03ProviderContract.V03OperationDefinition operation, String outputPath) {
        V03OutputDefinition exact = operation.outputDefinitions().get(outputPath);
        if (exact != null) return exact;
        int separator = outputPath.indexOf('.');
        String root = separator < 0 ? outputPath : outputPath.substring(0, separator);
        V03OutputDefinition parent = operation.outputDefinitions().get(root);
        if (parent == null) throw new IllegalArgumentException("missing_provider_output: `" + root + "` is not declared.");
        if (parent.valueType() != V03ValueType.OBJECT && parent.valueType() != V03ValueType.ANY) {
            throw new IllegalArgumentException("invalid_output_subpath: nested output refs require an object output.");
        }
        return new V03OutputDefinition(V03ValueType.ANY, parent.sensitivity(), parent.bindable(), parent.evidenceIncluded());
    }

    public V03OutputDefinition find(V03ProviderContract.V03OperationDefinition operation, String outputPath) {
        try { return resolve(operation, outputPath); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
