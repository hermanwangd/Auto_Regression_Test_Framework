package com.specdriven.regression.discovery;

import com.specdriven.regression.mapping.RpRuMappingGap;
import com.specdriven.regression.schema.ArtifactValidationError;
import java.nio.file.Path;
import java.util.List;

public record ReleasePackageCompletenessReport(
        boolean complete,
        String status,
        List<Path> requiredArtifacts,
        List<ReleasePackageGap> gaps,
        List<ArtifactValidationError> packageSchemaErrors,
        List<RpRuMappingGap> mappingGaps) {
}
