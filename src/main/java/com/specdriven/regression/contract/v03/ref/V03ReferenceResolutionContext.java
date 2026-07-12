package com.specdriven.regression.contract.v03.ref;

import com.specdriven.regression.contract.v03.V03ProducedOutput;
import java.nio.file.Path;
import java.util.Map;

/** Resolver-only view of runtime output provenance. */
public final class V03ReferenceResolutionContext {
    private final Path suiteRoot;
    private final Map<String, Path> artifactRoots;
    private final String testCaseId;
    private final Map<String, V03ProducedOutput> producedOutputs;
    private final Map<String, String> environment;

    public V03ReferenceResolutionContext(
            Path suiteRoot,
            Map<String, Path> artifactRoots,
            String testCaseId,
            Map<String, V03ProducedOutput> producedOutputs,
            Map<String, String> environment) {
        this.suiteRoot = suiteRoot.toAbsolutePath().normalize();
        this.artifactRoots = Map.copyOf(artifactRoots);
        this.testCaseId = testCaseId;
        this.producedOutputs = Map.copyOf(producedOutputs);
        this.environment = Map.copyOf(environment);
    }

    Path suiteRoot() { return suiteRoot; }
    Map<String, Path> artifactRoots() { return artifactRoots; }
    String testCaseId() { return testCaseId; }
    Map<String, V03ProducedOutput> producedOutputs() { return producedOutputs; }
    Map<String, String> environment() { return environment; }
}
