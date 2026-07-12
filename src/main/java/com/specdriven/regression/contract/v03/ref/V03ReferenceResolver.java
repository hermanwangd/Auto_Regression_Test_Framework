package com.specdriven.regression.contract.v03.ref;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.specdriven.regression.contract.v03.V03ProducedOutput;

/** Single fail-closed resolver for v0.3 artifact, step, generated, and environment references. */
public final class V03ReferenceResolver {

    private static final int MAX_JSON_POINTER_DEPTH = 64;
    private final V03DocumentLoader documentLoader;

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
            V03ProducedOutput produced = producedOutput(
                    context.producedOutputs(), scopedStepKey(context.testCaseId(), step.stepId()), step.outputPath());
            if (produced != null) return applyJsonPointer(outputValue(produced, step.outputPath()), step.jsonPointer());
            throw invalid("unresolved_step_ref", "step `" + step.stepId()
                    + "` did not produce declared output `" + step.outputPath() + "`.");
        }
        if (reference instanceof V03Reference.Generated generated) {
            List<V03ProducedOutput> produced = context.producedOutputs().values().stream()
                    .filter(output -> generated.target().equals(output.target()))
                    .filter(output -> matchesOutputPath(output, generated.output()))
                    .toList();
            produced = produced.stream().filter(V03ProducedOutput::bindable).toList();
            if (produced.size() == 1) return applyJsonPointer(outputValue(produced.get(0), generated.output()), generated.jsonPointer());
            if (produced.size() > 1) throw invalid("ambiguous_generated_ref",
                    "target `" + generated.target() + "` produced `" + generated.output() + "` more than once.");
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

    private V03ProducedOutput producedOutput(
            Map<String, V03ProducedOutput> producedOutputs, String stepKey, String outputPath) {
        V03ProducedOutput exact = producedOutputs.get(stepKey + "\n" + outputPath);
        if (exact != null) return exact;
        String root = outputPath.contains(".") ? outputPath.substring(0, outputPath.indexOf('.')) : outputPath;
        V03ProducedOutput parent = producedOutputs.get(stepKey + "\n" + root);
        return matchesOutputPath(parent, outputPath) ? parent : null;
    }

    private boolean matchesOutputPath(V03ProducedOutput output, String outputPath) {
        if (output == null) return false;
        if (output.outputName().equals(outputPath)) return true;
        return outputPath.startsWith(output.outputName() + ".")
                && (output.valueType() == com.specdriven.regression.contract.v03.V03ValueType.OBJECT
                || output.valueType() == com.specdriven.regression.contract.v03.V03ValueType.ANY);
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
