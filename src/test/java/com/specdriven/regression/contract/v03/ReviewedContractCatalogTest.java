package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.contract.ContractBaselineService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewedContractCatalogTest {
    @TempDir Path temporary;
    private static final Path SUITE = Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml");

    @Test
    void explicitEmptyCatalogCannotFallBackToTheWorkingDirectory() throws Exception {
        Path catalog = Files.createDirectory(temporary.resolve("approved-contracts"));
        assertThat(new V03ProviderContractCatalog().loadDirectory(catalog).keySet()).isEmpty();
        var validation = new ContractBaselineService(catalog).validateSuite(SUITE);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.findings().toString()).contains("sample_fake_provider");
    }

    @Test
    void explicitCatalogControlsBothValidationAndCompilation() throws Exception {
        Path catalog = Files.createDirectory(temporary.resolve("approved-contracts"));
        Path source = Path.of("docs/02-architecture/contracts/provider-contracts/sample_fake_provider_v0_3.yaml");
        Path pinned = catalog.resolve("sample.yaml");
        Files.copy(source, pinned);
        ContractBaselineService baseline = new ContractBaselineService(catalog);
        var valid = baseline.validateSuite(SUITE);
        assertThat(valid.valid()).as(valid.findings().toString()).isTrue();
        var builder = new V03ExecutionPlanBuilder(baseline, catalog);
        assertThat(builder.build(SUITE, "local_v03", valid).providerContracts().keySet())
                .containsExactly("sample_fake_provider.v0.3");
        Files.writeString(pinned, Files.readString(pinned).replace("execute_sample:", "unreviewed_operation:"));
        assertThat(baseline.validateSuite(SUITE).valid()).isFalse();
        assertThatThrownBy(() -> builder.build(SUITE, "local_v03", valid))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
