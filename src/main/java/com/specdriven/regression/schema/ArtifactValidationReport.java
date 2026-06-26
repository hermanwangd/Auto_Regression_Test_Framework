package com.specdriven.regression.schema;

import java.util.List;

public record ArtifactValidationReport(boolean valid, List<ArtifactValidationError> errors) {
}
