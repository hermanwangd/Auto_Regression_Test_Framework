package com.specdriven.regression.productrepo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductRepoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesRequiredLifecycleFoldersAndReportsReady() throws Exception {
        ProductRepoService service = new ProductRepoService();

        ProductRepoResult initResult = service.initialize(tempDir);
        ProductRepoReadinessReport report = service.checkReadiness(tempDir);

        assertThat(initResult.createdPaths()).contains(
                Path.of("docs/00-intake-scope"),
                Path.of("docs/01-specs"),
                Path.of("docs/02-architecture"),
                Path.of("docs/03-acceptance"),
                Path.of("docs/04-planning"),
                Path.of("docs/05-decisions-adr"),
                Path.of("docs/06-reviews"),
                Path.of("docs/07-validation-evidence"),
                Path.of("docs/08-release"),
                Path.of("docs/08-release/release-packages"),
                Path.of("docs/09-operations"),
                Path.of("docs/10-change-control"),
                Path.of("docs/99-archive"));
        assertThat(report.ready()).isTrue();
        assertThat(report.status()).isEqualTo("pass");
        assertThat(report.checkedItems()).isNotEmpty();
        assertThat(report.gaps()).isEmpty();
        assertThat(report.nextRequiredStep()).contains("init-rp");
    }

    @Test
    void checkReadinessReportsMissingItemsWithoutInventingScope() {
        ProductRepoService service = new ProductRepoService();

        ProductRepoReadinessReport report = service.checkReadiness(tempDir);

        assertThat(report.ready()).isFalse();
        assertThat(report.status()).isEqualTo("fail");
        assertThat(report.gaps()).extracting(ReadinessGap::path)
                .contains(Path.of("docs/00-intake-scope"));
        assertThat(report.gaps()).extracting(ReadinessGap::ownerAction)
                .allMatch(action -> action.contains("Create required Product Repo path"));
        assertThat(report.rpScopeInvented()).isFalse();
        assertThat(report.rpRuMembershipInvented()).isFalse();
    }

    @Test
    void initializeIsIdempotentAndDoesNotOverwriteExistingFiles() throws Exception {
        ProductRepoService service = new ProductRepoService();
        Path existing = tempDir.resolve("docs/00-intake-scope/README.md");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "owner content");

        ProductRepoResult result = service.initialize(tempDir);

        assertThat(Files.readString(existing)).isEqualTo("owner content");
        assertThat(result.skippedExistingPaths()).contains(Path.of("docs/00-intake-scope"));
    }
}
