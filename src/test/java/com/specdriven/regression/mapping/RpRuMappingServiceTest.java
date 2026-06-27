package com.specdriven.regression.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
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

    @Test
    void blocksNonMappingReleaseUnitEntry() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-BAD-ENTRY
                release_units:
                  - RU-not-a-map
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.executionBlocked()).isTrue();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .containsExactly("release_units[0]");
        assertThat(report.releaseUnits()).isEmpty();
    }

    @Test
    void reportsOnlyMissingSitDeploymentFieldsWhenPartialEvidenceExists() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-SIT-PARTIAL
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api
                    unit_type: service
                    owner: product_developer
                    version_ref: build-42
                    validation_boundary: request_response_api
                    execution_mode: sit_deployed
                    deployment_required: true
                    environment_ref: sit://payment/api
                    adapter: request_response
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                    deployment:
                      deployment_ref: deploy://payment-api/build-42
                      readiness_check: " "
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .containsExactly(
                        "release_units[0].deployment.readiness_check",
                        "release_units[0].deployment.deployed_version_ref");
    }

    @Test
    void blocksDependencyListWithWrongTypeAndBlankItems() throws Exception {
        Path wrongType = tempDir.resolve("wrong-type.yaml");
        Files.writeString(wrongType, """
                rp_id: RP-WRONG-DEPENDENCIES
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://api
                    adapter: request_response
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: RU-db
                """);
        Path blankItem = tempDir.resolve("blank-item.yaml");
        Files.writeString(blankItem, """
                rp_id: RP-BLANK-DEPENDENCY
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://api
                    adapter: request_response
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [" "]
                """);

        RpRuMappingValidationReport wrongTypeReport = new RpRuMappingService().validate(wrongType);
        RpRuMappingValidationReport blankItemReport = new RpRuMappingService().validate(blankItem);

        assertThat(wrongTypeReport.gaps()).extracting(RpRuMappingGap::fieldPath)
                .containsExactly("release_units[0].dependencies");
        assertThat(blankItemReport.gaps()).extracting(RpRuMappingGap::fieldPath)
                .containsExactly("release_units[0].dependencies[0]");
    }

    @Test
    void blocksDuplicateReleaseUnitIdsBeforeOrdering() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-DUPLICATE
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api-blue
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://api-blue
                    adapter: request_response
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-api
                    repo: /repo/api-green
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://api-green
                    adapter: request_response
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .contains("release_units[1].ru_id");
        assertThat(report.gaps()).extracting(RpRuMappingGap::ownerAction)
                .anyMatch(action -> action.contains("unique ru_id"));
    }

    @Test
    void blocksDependencyCycleBeforeExecution() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: RP-CYCLE
                release_units:
                  - ru_id: RU-api
                    repo: /repo/api
                    unit_type: service
                    owner: product_developer
                    version_ref: main
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://api
                    adapter: request_response
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [RU-events]
                  - ru_id: RU-events
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
                    dependencies: [RU-api]
                """);

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .contains("release_units.dependencies");
        assertThat(report.gaps()).extracting(RpRuMappingGap::ownerAction)
                .anyMatch(action -> action.contains("dependency cycle"));
    }

    @Test
    void reportsMissingReleaseUnitsWhenYamlRootIsNotMapping() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, "- not-a-mapping-root\n");

        RpRuMappingValidationReport report = new RpRuMappingService().validate(mapping);

        assertThat(report.valid()).isFalse();
        assertThat(report.gaps()).extracting(RpRuMappingGap::fieldPath)
                .containsExactly("release_units");
    }

    @Test
    void wrapsUnreadableMappingAsUncheckedIOException() {
        Path missing = tempDir.resolve("missing-rp-ru-mapping.yaml");

        assertThatThrownBy(() -> new RpRuMappingService().validate(missing))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read RP/RU mapping");
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
