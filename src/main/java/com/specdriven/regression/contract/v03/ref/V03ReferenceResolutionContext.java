package com.specdriven.regression.contract.v03.ref;

import java.nio.file.Path;
import java.util.Map;
import com.specdriven.regression.contract.v03.V03ProducedOutput;

public record V03ReferenceResolutionContext(
        Path suiteRoot,
        Map<String, Path> artifactRoots,
        String testCaseId,
        Map<String, Map<String, Object>> outputsByStep,
        Map<String, Map<String, Object>> generatedOutputsByTarget,
        Map<String, V03ProducedOutput> producedOutputs,
        Map<String, String> environment) {

    public V03ReferenceResolutionContext(Path suiteRoot, Map<String, Path> artifactRoots, String testCaseId,
            Map<String, Map<String, Object>> outputsByStep, Map<String, Map<String, Object>> generatedOutputsByTarget,
            Map<String, String> environment) {
        this(suiteRoot, artifactRoots, testCaseId, outputsByStep, generatedOutputsByTarget, Map.of(), environment);
    }

    public V03ReferenceResolutionContext {
        suiteRoot = suiteRoot.toAbsolutePath().normalize();
        artifactRoots = Map.copyOf(artifactRoots);
        outputsByStep = Map.copyOf(outputsByStep);
        generatedOutputsByTarget = Map.copyOf(generatedOutputsByTarget);
        producedOutputs = Map.copyOf(producedOutputs);
        environment = Map.copyOf(environment);
    }
}
