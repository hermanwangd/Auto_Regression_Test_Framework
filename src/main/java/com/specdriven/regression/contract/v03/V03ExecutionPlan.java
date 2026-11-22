package com.specdriven.regression.contract.v03;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record V03ExecutionPlan(
        String suiteId,
        String profile,
        Path suiteRoot,
        V03SuiteMetadata metadata,
        V03EnvironmentProfile environmentProfile,
        Map<String, V03ResolvedTarget> targets,
        Map<String, V03ProviderContract> providerContracts,
        Map<String, Path> artifactRoots,
        List<V03CompiledTestCase> tests,
        List<V03ExecutionStep> steps,
        List<V03BindingDependency> bindingDependencies,
        String planDigest) {

    public V03ExecutionPlan {
        suiteRoot = suiteRoot.toAbsolutePath().normalize();
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        providerContracts = Collections.unmodifiableMap(new LinkedHashMap<>(providerContracts));
        artifactRoots = Collections.unmodifiableMap(new LinkedHashMap<>(artifactRoots));
        tests = List.copyOf(tests);
        steps = List.copyOf(steps);
        bindingDependencies = List.copyOf(bindingDependencies);
    }

}
