package com.specdriven.regression.contract.v03.ref;

import java.nio.file.Path;
import java.util.Map;

public final class V03ReferenceResolver {

    private final V03ReferenceParser parser = new V03ReferenceParser();

    public Path artifactPath(V03Reference.Artifact reference, Map<String, Path> artifactRoots) {
        Path root = artifactRoots.get(reference.root());
        if (root == null) {
            throw new IllegalArgumentException("unknown_artifact_root: `" + reference.root() + "` is not declared.");
        }
        Path resolved = root.resolve(reference.path()).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("ref_outside_suite_root: artifact ref escapes its declared root.");
        }
        return resolved;
    }

    /** Resolves the only environment-reference form accepted by the v0.3 runtime boundary. */
    public Object resolveBinding(Object value, Map<String, Object> generatedOutputs, Map<String, String> environment) {
        V03Reference reference = parser.parse(value);
        if (reference instanceof V03Reference.Environment environmentReference) {
            String resolved = environment.get(environmentReference.name());
            if (resolved == null || resolved.isBlank()) {
                throw new IllegalArgumentException("missing_environment_value: environment variable `"
                        + environmentReference.name() + "` is not set.");
            }
            return resolved;
        }
        if (reference instanceof V03Reference.Generated generated) {
            Object resolved = generatedOutputs.get(generated.target() + "\\n" + generated.output());
            if (resolved == null) {
                throw new IllegalArgumentException("unresolved_generated_ref: generated output `"
                        + generated.target() + "/" + generated.output() + "` is not available.");
            }
            return resolved;
        }
        return value;
    }
}
