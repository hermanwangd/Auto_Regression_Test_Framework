package com.specdriven.regression.contract.v03.ref;

import java.nio.file.Path;
import java.util.Map;

public record V03ReferenceResolutionContext(
        Path suiteRoot,
        Map<String, Path> artifactRoots,
        String testCaseId,
        Map<String, Map<String, Object>> outputsByStep,
        Map<String, Map<String, Object>> generatedOutputsByTarget,
        Map<String, String> environment) {

    public V03ReferenceResolutionContext {
        suiteRoot = suiteRoot.toAbsolutePath().normalize();
        artifactRoots = Map.copyOf(artifactRoots);
        outputsByStep = Map.copyOf(outputsByStep);
        generatedOutputsByTarget = Map.copyOf(generatedOutputsByTarget);
        environment = Map.copyOf(environment);
    }
}
