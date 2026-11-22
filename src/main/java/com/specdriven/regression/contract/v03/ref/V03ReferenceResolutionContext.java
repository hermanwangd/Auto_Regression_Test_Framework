package com.specdriven.regression.contract.v03.ref;

import com.specdriven.regression.contract.v03.V03ProducedOutput;
import com.specdriven.regression.contract.v03.V03GeneratedOutputKey;
import com.specdriven.regression.contract.v03.V03StepOutputKey;
import java.nio.file.Path;
import java.util.Map;

/** Resolver-only view of runtime output provenance. */
public final class V03ReferenceResolutionContext {
    private final Path suiteRoot;
    private final Map<String, Path> artifactRoots;
    private final String testCaseId;
    private final Map<V03StepOutputKey, V03ProducedOutput> stepOutputs;
    private final Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs;
    private final Map<String, String> environment;

    public V03ReferenceResolutionContext(
            Path suiteRoot,
            Map<String, Path> artifactRoots,
            String testCaseId,
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs,
            Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs,
            Map<String, String> environment) {
        this.suiteRoot = suiteRoot.toAbsolutePath().normalize();
        this.artifactRoots = Map.copyOf(artifactRoots);
        this.testCaseId = testCaseId;
        this.stepOutputs = Map.copyOf(stepOutputs);
        this.generatedOutputs = Map.copyOf(generatedOutputs);
        this.environment = Map.copyOf(environment);
    }

    Path suiteRoot() { return suiteRoot; }
    Map<String, Path> artifactRoots() { return artifactRoots; }
    String testCaseId() { return testCaseId; }
    Map<V03StepOutputKey, V03ProducedOutput> stepOutputs() { return stepOutputs; }
    Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs() { return generatedOutputs; }
    Map<String, String> environment() { return environment; }
}
