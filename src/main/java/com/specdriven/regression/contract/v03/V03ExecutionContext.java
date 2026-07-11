package com.specdriven.regression.contract.v03;

import com.specdriven.regression.contract.v03.ref.V03ReferenceResolutionContext;
import com.specdriven.regression.contract.v03.ref.V03ReferenceResolver;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record V03ExecutionContext(
        Path suiteRoot,
        Path runDir,
        String profile,
        Map<String, V03ResolvedTarget> targets,
        Map<String, Path> artifactRoots,
        Map<String, Map<String, Object>> generatedOutputsByTarget,
        Map<String, Map<String, Object>> outputsByStep,
        Map<String, Object> envProfile,
        Map<String, String> environment,
        V03ReferenceResolver referenceResolver,
        Instant testStartTime) {

    public V03ReferenceResolutionContext referenceContext(String testCaseId) {
        return new V03ReferenceResolutionContext(
                suiteRoot,
                artifactRoots,
                testCaseId,
                outputsByStep,
                generatedOutputsByTarget,
                environment);
    }
}
