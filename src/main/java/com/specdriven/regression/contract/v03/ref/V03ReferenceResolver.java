package com.specdriven.regression.contract.v03.ref;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.specdriven.regression.contract.v03.V03ProducedOutput;
import com.specdriven.regression.contract.v03.V03GeneratedOutputKey;
import com.specdriven.regression.contract.v03.V03BindingDependency;
import com.specdriven.regression.contract.v03.V03OutputDefinitionResolver;
import com.specdriven.regression.contract.v03.V03ResolvedProducedOutput;
import com.specdriven.regression.contract.v03.V03StepOutputKey;

/** Single fail-closed resolver for v0.3 artifact, step, generated, and environment references. */
public final class V03ReferenceResolver {

    private static final int MAX_JSON_POINTER_DEPTH = 64;
    private final V03DocumentLoader documentLoader;
    private final V03OutputDefinitionResolver outputResolver = new V03OutputDefinitionResolver();

    public V03ReferenceResolver() {
        this(path -> {
            throw new IllegalStateException(
                    "document_loader_required: configure a document loader before resolving artifact JSON Pointers.");
        });
    }

    public V03ReferenceResolver(V03DocumentLoader documentLoader) {
        this.documentLoader = documentLoader;
    }

    public Path artifactPath(V03Reference.Artifact reference, Map<String, Path> artifactRoots) {
        Path root = artifactRoots.get(reference.root());
        if (root == null) {
            throw invalid("unknown_artifact_root", "`" + reference.root() + "` is not declared.");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(reference.path()).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw invalid("ref_outside_suite_root", "artifact ref escapes its declared root.");
        }
        if (!Files.isRegularFile(candidate)) {
            throw invalid("artifact_missing", "artifact file does not exist: `" + candidate + "`.");
        }
        try {
            Path realRoot = normalizedRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                throw invalid("artifact_symlink_escape", "artifact symlink escapes its declared root.");
            }
            return realCandidate;
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to resolve artifact path `" + candidate + "`.", error);
        }
    }

    public Path artifactPath(
            V03Reference.Artifact reference,
            V03ReferenceResolutionContext context) {
        return artifactPath(reference, context.artifactRoots());
    }

    public Object resolveValue(V03Reference reference, V03ReferenceResolutionContext context) {
        if (reference instanceof V03Reference.Literal literal) {
            return literal.value();
        }
        if (reference instanceof V03Reference.Artifact artifact) {
            Object document = documentLoader.load(artifactPath(artifact, context));
            return applyJsonPointer(document, artifact.jsonPointer());
        }
        if (reference instanceof V03Reference.Step step) {
            return applyJsonPointer(
                    outputValue(stepOutput(context, step.stepId(), step.outputPath()), step.outputPath()), step.jsonPointer());
        }
        if (reference instanceof V03Reference.Generated generated) {
            List<V03ProducedOutput> produced = context.generatedOutputs().entrySet().stream()
                    .filter(entry -> context.testCaseId().equals(entry.getKey().testCaseId()))
                    .filter(entry -> generated.target().equals(entry.getKey().target()))
                    .map(Map.Entry::getValue).toList();
            long matchingOutputs = produced.stream()
                    .filter(output -> output.outputName().equals(generated.output())
                            || generated.output().startsWith(output.outputName() + "."))
                    .count();
            if (matchingOutputs > 1) {
                throw invalid("ambiguous_generated_ref", "target `" + generated.target()
                        + "` produced `" + generated.output() + "` more than once.");
            }
            try {
                V03ResolvedProducedOutput resolved = outputResolver.resolveProducedPath(produced, generated.output());
                return applyJsonPointer(outputValue(resolved.output(), generated.output()), generated.jsonPointer());
            } catch (IllegalArgumentException ignored) {
                // Keep the public generated-ref failure code stable below.
            }
            throw invalid("unresolved_generated_ref", "target `" + generated.target()
                    + "` did not produce one bindable output `" + generated.output() + "`.");
        }
        V03Reference.Environment environment = (V03Reference.Environment) reference;
        String value = context.environment().get(environment.name());
        if (value == null) {
            throw invalid("missing_environment_value", "environment variable `" + environment.name() + "` is not set.");
        }
        if (value.isBlank()) {
            throw invalid("blank_environment_value", "environment variable `" + environment.name() + "` is blank.");
        }
        return value;
    }

    /** Resolves a generated target binding from its compiler-selected producer step only. */
    public Object resolveCompiledGenerated(
            V03Reference.Generated generated,
            V03BindingDependency dependency,
            V03ReferenceResolutionContext context) {
        List<V03ProducedOutput> outputs = context.generatedOutputs().entrySet().stream()
                .filter(entry -> entry.getKey().testCaseId().equals(dependency.producerTestCaseId()))
                .filter(entry -> entry.getKey().target().equals(dependency.producerTarget()))
                .filter(entry -> entry.getKey().stepId().equals(dependency.producerStepId()))
                .map(Map.Entry::getValue).toList();
        try {
            V03ResolvedProducedOutput resolved = outputResolver.resolveProducedPath(outputs, generated.output());
            return applyJsonPointer(outputValue(resolved.output(), generated.output()), generated.jsonPointer());
        } catch (IllegalArgumentException error) {
            throw invalid("unresolved_generated_ref", "compiled producer step `" + dependency.producerStepId()
                    + "` did not produce `" + generated.output() + "`.");
        }
    }

    private V03ProducedOutput stepOutput(
            V03ReferenceResolutionContext context, String stepId, String outputPath) {
        List<V03ProducedOutput> outputs = context.stepOutputs().entrySet().stream()
                .filter(entry -> entry.getKey().testCaseId().equals(context.testCaseId()))
                .filter(entry -> entry.getKey().stepId().equals(stepId))
                .map(Map.Entry::getValue).toList();
        try {
            return outputResolver.resolveProducedPath(outputs, outputPath).output();
        } catch (IllegalArgumentException error) {
            throw invalid("unresolved_step_ref", "step `" + stepId
                    + "` did not produce declared output `" + outputPath + "`.");
        }
    }

    private Object outputValue(V03ProducedOutput output, String outputPath) {
        if (output.outputName().equals(outputPath)) return output.value();
        Object current = output.value();
        String suffix = outputPath.substring(output.outputName().length() + 1);
        for (String part : suffix.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw invalid("unresolved_step_ref", "output path `" + outputPath + "` did not resolve.");
            }
            current = map.get(part);
        }
        return current;
    }

    public Object resolveProviderValue(V03Reference reference, V03ReferenceResolutionContext context) {
        if (reference instanceof V03Reference.Artifact artifact && artifact.jsonPointer().isBlank()) {
            Path path = artifactPath(artifact, context);
            Path suiteRoot = context.suiteRoot().toAbsolutePath().normalize();
            if (path.startsWith(suiteRoot)) {
                return suiteRoot.relativize(path).toString();
            }
            Path declaredRoot = context.artifactRoots().get(artifact.root()).toAbsolutePath().normalize();
            return declaredRoot.getFileName().resolve(artifact.path()).toString();
        }
        return resolveValue(reference, context);
    }

    private Object applyJsonPointer(Object value, String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return value;
        }
        String[] tokens = pointer.substring(1).split("/", -1);
        if (tokens.length > MAX_JSON_POINTER_DEPTH) {
            throw invalid("json_pointer_too_deep", "JSON Pointer exceeds " + MAX_JSON_POINTER_DEPTH + " segments.");
        }
        Object current = value;
        for (String rawToken : tokens) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw invalid("json_pointer_missing", "JSON Pointer segment `" + token + "` does not exist.");
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException error) {
                    throw invalid("json_pointer_missing", "JSON Pointer array segment `" + token + "` is not an index.");
                }
                if (index < 0 || index >= list.size()) {
                    throw invalid("json_pointer_missing", "JSON Pointer array index `" + token + "` is out of range.");
                }
                current = list.get(index);
            } else {
                throw invalid("json_pointer_missing", "JSON Pointer cannot descend through a scalar value.");
            }
        }
        return current;
    }

    private String scopedStepKey(String testCaseId, String stepId) {
        return testCaseId + "\n" + stepId;
    }

    private IllegalArgumentException invalid(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }
}
