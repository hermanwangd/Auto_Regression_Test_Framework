package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.v03.ref.V03ReferenceResolutionContext;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** Adapter-facing runtime context. Raw output provenance remains framework-private. */
public final class V03ExecutionContext {
    private final Path suiteRoot;
    private final Path runDir;
    private final String profile;
    private final Map<String, V03ResolvedTarget> targets;
    private final Map<String, Path> artifactRoots;
    private final Map<String, V03ProducedOutput> producedOutputs;
    private final Map<String, Object> envProfile;
    private final Map<String, String> environment;
    private final V03ReferenceResolver referenceResolver;
    private final Instant testStartTime;

    public V03ExecutionContext(
            Path suiteRoot,
            Path runDir,
            String profile,
            Map<String, V03ResolvedTarget> targets,
            Map<String, Path> artifactRoots,
            Map<String, V03ProducedOutput> producedOutputs,
            Map<String, Object> envProfile,
            Map<String, String> environment,
            V03ReferenceResolver referenceResolver,
            Instant testStartTime) {
        this.suiteRoot = suiteRoot;
        this.runDir = runDir;
        this.profile = profile;
        this.targets = targets;
        this.artifactRoots = artifactRoots;
        this.producedOutputs = producedOutputs;
        this.envProfile = envProfile;
        this.environment = environment;
        this.referenceResolver = referenceResolver;
        this.testStartTime = testStartTime;
    }

    public Path suiteRoot() { return suiteRoot; }
    public Path runDir() { return runDir; }
    public String profile() { return profile; }
    public Map<String, V03ResolvedTarget> targets() { return targets; }
    public Map<String, Path> artifactRoots() { return artifactRoots; }
    public Map<String, Object> envProfile() { return envProfile; }
    public Map<String, String> environment() { return environment; }
    public V03ReferenceResolver referenceResolver() { return referenceResolver; }
    public Instant testStartTime() { return testStartTime; }

    Map<String, V03ProducedOutput> producedOutputs() { return producedOutputs; }

    public V03ReferenceResolutionContext referenceContext(String testCaseId) {
        return new V03ReferenceResolutionContext(suiteRoot, artifactRoots, testCaseId, producedOutputs, environment);
    }
}
