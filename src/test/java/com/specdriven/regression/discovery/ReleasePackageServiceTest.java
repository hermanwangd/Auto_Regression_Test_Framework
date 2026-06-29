package com.specdriven.regression.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleasePackageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesReleasePackageSkeletonAndReportsComplete() {
        ReleasePackageService service = new ReleasePackageService();

        ReleasePackageResult initResult = service.initialize(tempDir, "RP-001", "data_pipeline");
        ReleasePackageCompletenessReport report = service.checkCompleteness(tempDir, "RP-001");

        assertThat(initResult.packageRoot())
                .isEqualTo(tempDir.resolve("docs/08-release/release-packages/RP-001"));
        assertThat(report.complete()).isTrue();
        assertThat(report.status()).isEqualTo("pass");
        assertThat(report.requiredArtifacts()).contains(
                Path.of("package.yaml"),
                Path.of("rp_feature_spec.md"),
                Path.of("rp_ru_mapping.yaml"),
                Path.of("acceptance_criteria.md"),
                Path.of("tests"),
                Path.of("expected-results"),
                Path.of("traceability.md"),
                Path.of("evidence_index.md"));
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void checkCompletenessReportsRequiredGapsWithOwnerAction() {
        ReleasePackageService service = new ReleasePackageService();

        ReleasePackageCompletenessReport report = service.checkCompleteness(tempDir, "RP-404");

        assertThat(report.complete()).isFalse();
        assertThat(report.status()).isEqualTo("fail");
        assertThat(report.gaps()).extracting(ReleasePackageGap::path)
                .contains(Path.of("docs/08-release/release-packages/RP-404/package.yaml"));
        assertThat(report.gaps()).extracting(ReleasePackageGap::ownerAction)
                .allMatch(action -> action.contains("Create required RP artifact"));
    }

    @Test
    void initializeIsIdempotentAndDoesNotOverwriteOwnerAuthoredArtifacts() throws Exception {
        ReleasePackageService service = new ReleasePackageService();
        ReleasePackageResult first = service.initialize(tempDir, "RP-001", "data_pipeline");
        Path packageYaml = first.packageRoot().resolve("package.yaml");
        Files.writeString(packageYaml, "rp_id: RP-001\npackage_type: owner-authored\nstatus: active\n");

        ReleasePackageResult second = service.initialize(tempDir, "RP-001", "service");

        assertThat(second.packageRoot()).isEqualTo(first.packageRoot());
        assertThat(Files.readString(packageYaml)).contains("package_type: owner-authored");
    }

    @Test
    void initializeWrapsIoFailuresWithReleasePackageContext() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-BLOCKED");
        Files.createDirectories(packageRoot.getParent());
        Files.writeString(packageRoot, "not a directory");

        assertThatThrownBy(() -> new ReleasePackageService().initialize(tempDir, "RP-BLOCKED", "service"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to initialize Release Package: RP-BLOCKED");
    }

    @Test
    void strictCompletenessReportsPackageSchemaAndRuMappingGaps() throws Exception {
        ReleasePackageService service = new ReleasePackageService();
        ReleasePackageResult result = service.initialize(tempDir, "RP-STRICT", "service");
        Files.writeString(result.packageRoot().resolve("package.yaml"), """
                rp_id: RP-STRICT
                package_type: service
                artifact_paths:
                  feature_spec: rp_feature_spec.md
                """);
        Files.writeString(result.packageRoot().resolve("rp_ru_mapping.yaml"), """
                rp_id: RP-STRICT
                release_units:
                  - ru_id: RU-payment
                """);

        ReleasePackageCompletenessReport report = service.checkCompleteness(tempDir, "RP-STRICT", true);

        assertThat(report.complete()).isFalse();
        assertThat(report.status()).isEqualTo("fail");
        assertThat(report.gaps()).isEmpty();
        assertThat(report.packageSchemaErrors()).extracting(error -> error.fieldPath())
                .contains("product_id", "artifact_paths.acceptance_criteria");
        assertThat(report.mappingGaps()).extracting(gap -> gap.fieldPath())
                .contains("release_units[0].repo", "release_units[0].unit_type");
    }
}
