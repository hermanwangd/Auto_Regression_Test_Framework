package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.v03.ref.V03ReferenceResolutionContext;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import com.specdriven.regression.contract.v03.ref.V03Reference;
import com.specdriven.regression.contract.v03.ref.V03ReferenceParser;
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
    private final Map<V03StepOutputKey, V03ProducedOutput> stepOutputs;
    private final Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs;
    private final java.util.List<V03BindingDependency> bindingDependencies;
    private final Map<String, Object> envProfile;
    private final Map<String, String> environment;
    private final V03ReferenceResolver referenceResolver;
    private final Instant testStartTime;
    private final V03ReferenceParser referenceParser = new V03ReferenceParser();

    public V03ExecutionContext(
            Path suiteRoot,
            Path runDir,
            String profile,
            Map<String, V03ResolvedTarget> targets,
            Map<String, Path> artifactRoots,
            Map<V03StepOutputKey, V03ProducedOutput> stepOutputs,
            Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs,
            java.util.List<V03BindingDependency> bindingDependencies,
            Map<String, Object> envProfile,
            Map<String, String> environment,
            V03ReferenceResolver referenceResolver,
            Instant testStartTime) {
        this.suiteRoot = suiteRoot;
        this.runDir = runDir;
        this.profile = profile;
        this.targets = targets;
        this.artifactRoots = artifactRoots;
        this.stepOutputs = stepOutputs;
        this.generatedOutputs = generatedOutputs;
        this.bindingDependencies = java.util.List.copyOf(bindingDependencies);
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

    Map<V03StepOutputKey, V03ProducedOutput> stepOutputs() { return stepOutputs; }

    Map<V03GeneratedOutputKey, V03ProducedOutput> generatedOutputs() { return generatedOutputs; }

    /** Resolves a target binding using its compile-time generated producer identity when applicable. */
    public Object resolveTargetBinding(V03ExecutionStep step, String bindingName, Object rawValue) {
        V03Reference reference = referenceParser.parse(rawValue);
        if (!(reference instanceof V03Reference.Generated generated)) {
            return referenceResolver.resolveProviderValue(reference, referenceContext(step.testCaseId()));
        }
        V03BindingDependency dependency = bindingDependencies.stream()
                .filter(item -> item.referenceKind() == V03ReferenceKind.GENERATED)
                .filter(item -> item.consumerTestCaseId().equals(step.testCaseId()))
                .filter(item -> item.consumerStepId().equals(step.id()))
                .filter(item -> item.consumerInput().equals("binding." + bindingName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("missing_compiled_generated_binding: target `"
                        + step.target() + "` binding `" + bindingName + "` has no compiled producer identity."));
        if (!dependency.producerTarget().equals(generated.target())
                || !dependency.producerOutput().equals(generated.output())) {
            throw new IllegalArgumentException("compiled_generated_binding_mismatch: target `" + step.target()
                    + "` binding `" + bindingName + "` no longer matches its compiled producer identity.");
        }
        return referenceResolver.resolveCompiledGenerated(generated, dependency, referenceContext(step.testCaseId()));
    }

    public V03ReferenceResolutionContext referenceContext(String testCaseId) {
        return new V03ReferenceResolutionContext(
                suiteRoot, artifactRoots, testCaseId, stepOutputs, generatedOutputs, environment);
    }
}
