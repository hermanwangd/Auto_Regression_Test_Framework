package com.specdriven.regression.discovery;

import static org.assertj.core.api.Assertions.assertThat;

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
}
