package com.specdriven.regression.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RpRuMappingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsCompleteOwnerAuthoredMapping() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, completeMapping("ci_ephemeral", "deployment_required: false\n"));

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isTrue();
        assertThat(report.executionBlocked()).isFalse();
        assertThat(report.releaseUnits()).extracting(ReleaseUnitMapping::ruId)
                .containsExactly("RU-transform-job");
        assertThat(report.gaps()).isEmpty();
        assertThat(report.membershipInferred()).isFalse();
    }

    @Test
    void reportsMissingRuFieldsAsExecutionBlockingOwnerActions() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-AR-M1-data-pipeline
                release_units:
                  - ru_id: RU-transform-job
                    repo: /path/to/release-unit-repo
                    execution_mode: ci_ephemeral
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.executionBlocked()).isTrue();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .contains("release_units[0].unit_type", "release_units[0].owner",
                        "release_units[0].version_ref", "release_units[0].validation_boundary",
                        "release_units[0].deployment_required", "release_units[0].environment_ref",
                        "release_units[0].adapter", "release_units[0].provider_contracts",
                        "release_units[0].evidence_responsibility", "release_units[0].dependencies");
        assertThat(report.gaps()).extracting(RpRuMappingGap::ownerAction)
                .allMatch(action -> action.contains("Update owner-authored rp_ru_mapping.yaml"));
        assertThat(report.membershipInferred()).isFalse();
    }

    @Test
    void blocksSitExecutionWhenDeploymentEvidenceIsMissing() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, completeMapping("sit_deployed", "deployment_required: true\n"));

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.executionBlocked()).isTrue();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .contains("release_units[0].deployment.deployment_ref",
                        "release_units[0].deployment.readiness_check",
                        "release_units[0].deployment.deployed_version_ref");
    }

    @Test
    void rejectsDependencyOrderWithoutDeclaredDependencyGraph() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-AR-M1-data-pipeline
                release_units:
                  - ru_id: RU-load-job
                    repo: /repo/load
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/load
                    adapter: spring_boot_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependency_order: 1
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.executionBlocked()).isTrue();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .contains("release_units[0].dependencies");
        assertThat(report.gaps()).extracting(RpRuMappingGap::ownerAction)
                .anyMatch(action -> action.contains("Declare dependencies as a graph"));
    }

    @Test
    void derivesReleaseUnitOrderFromDeclaredDependenciesInsteadOfFileOrder() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-ORDERED
                release_units:
                  - ru_id: RU-publish-events
                    repo: /repo/events
                    unit_type: service_event
                    owner: product_developer
                    version_ref: main
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://events
                    adapter: message_bus
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [RU-transform-job]
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://transform
                    adapter: spring_boot_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isTrue();
        assertThat(report.releaseUnits()).extracting(ReleaseUnitMapping::ruId)
                .containsExactly("RU-transform-job", "RU-publish-events");
    }

    @Test
    void blocksUnknownDependencyWithIndexedOwnerAction() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-BAD-DEPENDENCY
                release_units:
                  - ru_id: RU-publish-events
                    repo: /repo/events
                    unit_type: service_event
                    owner: product_developer
                    version_ref: main
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://events
                    adapter: message_bus
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [RU-missing]
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.executionBlocked()).isTrue();
        assertThat(report.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.fieldPath()).isEqualTo("release_units[0].dependencies[0]");
                    assertThat(gap.ownerAction()).contains("RU-missing", "existing RU ID");
                });
    }

    private String completeMapping(String executionMode, String deploymentRequiredLine) {
        return """
                rp_id: RP-AR-M1-data-pipeline
                release_units:
                  - ru_id: RU-transform-job
                    repo: /path/to/release-unit-repo
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: %s
                    %s\
                    environment_ref: ci://pipeline/rp-ar-m1-data-pipeline
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          command: java -jar ${repo}/target/release-unit.jar
                    evidence_responsibility:
                      - execution_log
                    dependencies: []
                """.formatted(executionMode, deploymentRequiredLine);
    }
}
