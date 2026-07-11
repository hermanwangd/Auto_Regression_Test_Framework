package com.specdriven.regression.contract.v03.ref;

import java.nio.file.Path;
import java.util.Map;

public final class V03ReferenceResolver {

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
}
