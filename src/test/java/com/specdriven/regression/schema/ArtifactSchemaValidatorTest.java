package com.specdriven.regression.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSchemaValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void validatesPackageYamlRequiredFields() throws Exception {
        Path packageYaml = tempDir.resolve("package.yaml");
        Files.writeString(packageYaml, """
                product_id: PROD-auto-regression
                rp_id: RP-AR-M1-data-pipeline
                name: M1 Data Pipeline Regression Pilot
                owner: product_developer
                target_release: M1
                package_type: data_pipeline
                lifecycle_status: draft
                default_execution_mode: ci_ephemeral
                artifact_paths:
                  feature_spec: rp_feature_spec.md
                  acceptance_criteria: acceptance_criteria.md
                  ru_mapping: rp_ru_mapping.yaml
                  tests: tests/
                  expected_results: expected-results/
                  traceability: traceability.md
                  evidence_index: evidence_index.md
                """);

        ArtifactValidationReport report = new ArtifactSchemaValidator().validatePackageYaml(packageYaml);

        assertThat(report.valid()).isTrue();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    void reportsMissingPackageYamlFieldsWithFieldPathAndOwnerAction() throws Exception {
        Path packageYaml = tempDir.resolve("package.yaml");
        Files.writeString(packageYaml, """
                rp_id: RP-AR-M1-data-pipeline
                package_type: data_pipeline
                artifact_paths:
                  feature_spec: rp_feature_spec.md
                """);

        ArtifactValidationReport report = new ArtifactSchemaValidator().validatePackageYaml(packageYaml);

        assertThat(report.valid()).isFalse();
        assertThat(report.errors()).extracting(ArtifactValidationError::fieldPath)
                .contains("product_id", "owner", "target_release", "lifecycle_status",
                        "default_execution_mode", "artifact_paths.acceptance_criteria",
                        "artifact_paths.ru_mapping", "artifact_paths.tests",
                        "artifact_paths.expected_results", "artifact_paths.traceability",
                        "artifact_paths.evidence_index");
        assertThat(report.errors()).extracting(ArtifactValidationError::blocks)
                .contains("generation", "execution", "release_evidence");
        assertThat(report.errors()).extracting(ArtifactValidationError::ownerAction)
                .allMatch(action -> action.contains("Add required field"));
    }
}
