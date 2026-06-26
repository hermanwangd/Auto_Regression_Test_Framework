package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderContractResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesAdapterAndBindingContractsFromRuMapping() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  adapters:
                    spring_boot_cli:
                      command: java -jar ${repo}/target/release-unit.jar
                  bindings:
                    db_seed:
                      provider: file_fixture
                      materialize_as: input_file
                  fixtures: {}
                  oracles: {}
                  assertions: {}
                  observations: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedContracts()).extracting(ResolvedProviderContract::contractType)
                .contains("adapter", "binding");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksMissingAdapterAndBindingContractBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("provider_contracts: {}\n"));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains("provider_contracts.adapters.spring_boot_cli",
                        "provider_contracts.bindings.db_seed");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .allMatch(action -> action.contains("Add provider contract"));
    }

    @Test
    void blocksAdapterContractWithoutCommandBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  adapters:
                    spring_boot_cli: {}
                  bindings:
                    db_seed:
                      provider: file_fixture
                      materialize_as: input_file
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of());

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains("provider_contracts.adapters.spring_boot_cli.command");
        assertThat(report.gaps()).extracting(ProviderContractGap::ownerAction)
                .anyMatch(action -> action.contains("Declare executable adapter command"));
    }

    @Test
    void blocksFixtureActionWithoutFixtureProviderContract() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithContracts("""
                provider_contracts:
                  adapters:
                    spring_boot_cli:
                      command: java -jar ${repo}/target/release-unit.jar
                  bindings:
                    db_seed:
                      provider: file_fixture
                      materialize_as: input_file
                  fixtures: {}
                """));

        ProviderContractResolutionReport report = new ProviderContractResolver().resolve(
                mapping,
                "spring_boot_cli",
                List.of("db_seed"),
                List.of("relational_db"));

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ProviderContractGap::fieldPath)
                .contains("provider_contracts.fixtures.relational_db");
    }

    private String mappingWithContracts(String providerContracts) {
        return """
                rp_id: RP-001
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/RP-001
                    adapter: spring_boot_cli
                %s\
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(providerContracts.indent(4));
    }
}
