package com.specdriven.regression.schema;

import java.nio.file.Path;

public record ArtifactValidationError(
        Path file,
        String fieldPath,
        String severity,
        String blocks,
        String ownerAction) {
}
