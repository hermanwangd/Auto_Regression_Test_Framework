package com.specdriven.regression.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionEnvironmentResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesCiEphemeralEnvironmentFromMapping() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mapping("ci_ephemeral", "deployment_required: false\n", ""));

        ExecutionEnvironmentReport report = new ExecutionEnvironmentResolver().resolve(mapping, "ci_ephemeral");

        assertThat(report.ready()).isTrue();
        assertThat(report.executionMode()).isEqualTo("ci_ephemeral");
        assertThat(report.environmentRef()).isEqualTo("ci://pipeline/RP-001");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksSitDeployedWithoutDeploymentReadinessEvidence() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mapping("sit_deployed", "deployment_required: true\n", ""));

        ExecutionEnvironmentReport report = new ExecutionEnvironmentResolver().resolve(mapping, "sit_deployed");

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ExecutionEnvironmentGap::fieldPath)
                .contains("release_units[0].deployment.deployment_ref",
                        "release_units[0].deployment.readiness_check",
                        "release_units[0].deployment.deployed_version_ref");
        assertThat(report.gaps()).extracting(ExecutionEnvironmentGap::ownerAction)
                .allMatch(action -> action.contains("Provide SIT deployment readiness evidence"));
    }

    @Test
    void resolvesExecutionModeFromMappingWhenRequestIsBlank() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mapping("evidence_only", "deployment_required: false\n", ""));

        ExecutionEnvironmentReport report = new ExecutionEnvironmentResolver().resolve(mapping, " ");

        assertThat(report.ready()).isTrue();
        assertThat(report.executionMode()).isEqualTo("evidence_only");
        assertThat(report.environmentRef()).isEqualTo("ci://pipeline/RP-001");
    }

    @Test
    void blocksEnvironmentAwareExecutionWhenEnvironmentRefIsMissing() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mappingWithoutEnvironmentRef("ci_ephemeral", "deployment_required: false\n", ""));

        ExecutionEnvironmentReport report = new ExecutionEnvironmentResolver().resolve(mapping, "ci_ephemeral");

        assertThat(report.ready()).isFalse();
        assertThat(report.environmentRef()).isEmpty();
        assertThat(report.gaps()).extracting(ExecutionEnvironmentGap::fieldPath)
                .containsExactly("release_units[0].environment_ref");
    }

    @Test
    void reportsOnlyMissingSitDeploymentFieldsWhenPartialEvidenceExists() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, mapping("sit_deployed", "deployment_required: true\n",
                """
                    deployment:
                      deployment_ref: deploy://RP-001/build-42
                      readiness_check: " "
                """));

        ExecutionEnvironmentReport report = new ExecutionEnvironmentResolver().resolve(mapping, "sit_deployed");

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(ExecutionEnvironmentGap::fieldPath)
                .containsExactly(
                        "release_units[0].deployment.readiness_check",
                        "release_units[0].deployment.deployed_version_ref");
    }

    @Test
    void blocksReadinessWhenYamlDoesNotDeclareReleaseUnits() throws Exception {
        Path mapping = tempDir.resolve("rp_ru_mapping.yaml");
        Files.writeString(mapping, "- not-a-mapping-document\n");

        ExecutionEnvironmentReport report = new ExecutionEnvironmentResolver().resolve(mapping, "ci_ephemeral");

        assertThat(report.ready()).isFalse();
        assertThat(report.executionMode()).isEqualTo("ci_ephemeral");
        assertThat(report.gaps()).extracting(ExecutionEnvironmentGap::fieldPath)
                .containsExactly("release_units");
    }

    @Test
    void wrapsUnreadableMappingAsUncheckedIOException() {
        Path missing = tempDir.resolve("missing-rp-ru-mapping.yaml");

        assertThatThrownBy(() -> new ExecutionEnvironmentResolver().resolve(missing, "ci_ephemeral"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read execution environment mapping");
    }

    private String mapping(String executionMode, String deploymentRequiredLine, String deploymentBlock) {
        return """
                rp_id: RP-001
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: %s
                    %s\
                    environment_ref: ci://pipeline/RP-001
                    adapter: spring_boot_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                %s
                """.formatted(executionMode, deploymentRequiredLine, deploymentBlock);
    }

    private String mappingWithoutEnvironmentRef(String executionMode, String deploymentRequiredLine, String deploymentBlock) {
        return """
                rp_id: RP-001
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: %s
                    %s\
                    adapter: spring_boot_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                %s
                """.formatted(executionMode, deploymentRequiredLine, deploymentBlock);
    }
}
