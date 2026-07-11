package com.specdriven.regression.contract.v03;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record V03CompiledSuite(
        String suiteId,
        String profile,
        Path suiteRoot,
        V03SuiteMetadata metadata,
        V03EnvironmentProfile environmentProfile,
        Map<String, V03ResolvedTarget> targets,
        Map<String, Path> artifactRoots,
        List<V03CompiledTestCase> tests,
        String planDigest) {

    public V03CompiledSuite {
        suiteRoot = suiteRoot.toAbsolutePath().normalize();
        targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        artifactRoots = Collections.unmodifiableMap(new LinkedHashMap<>(artifactRoots));
        tests = List.copyOf(tests);
    }
}
