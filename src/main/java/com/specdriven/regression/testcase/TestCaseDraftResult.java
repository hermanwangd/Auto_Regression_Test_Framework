package com.specdriven.regression.testcase;

import java.nio.file.Path;
import java.util.List;

public record TestCaseDraftResult(
        String generatedArtifactType,
        Path writtenPath,
        List<String> gaps) {
}
