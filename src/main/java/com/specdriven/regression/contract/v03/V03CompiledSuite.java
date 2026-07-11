package com.specdriven.regression.contract.v03;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record V03CompiledSuite(
        String suiteId,
        String profile,
        Path suiteRoot,
        Map<String, Object> suiteDocument,
        Map<String, Object> envProfile,
        Map<String, V03ResolvedTarget> targets,
        Map<String, Path> artifactRoots,
        List<V03CompiledTestCase> tests) {
}
