package com.specdriven.regression.testcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.dsl.DslTestCaseValidator;
import com.specdriven.regression.dsl.DslValidationReport;
import com.specdriven.regression.readiness.AcReadinessItem;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCaseLifecycleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesExecutableDslDraftWhenAcAndExecutionContextAreReady() throws Exception {
        AcReadinessItem readyAc = AcReadinessItem.ready(
                "RP-AR-M1-data-pipeline-AC-001",
                "RP-AR-M1-data-pipeline",
                "Valid input produces approved output",
                "automatable",
                List.of("docs/01-specs/rp_feature_spec.md"));

        TestCaseDraftResult result = new TestCaseLifecycleService().generateDraft(
                tempDir, readyAc, ExecutionContextReadiness.ready(
                        "RU-transform-job",
                        "spring_boot_cli",
                        "ci_ephemeral",
                        "ci://pipeline/rp-ar-m1-data-pipeline",
                        List.of("file_input", "batch_execution", "file_assertion")));

        assertThat(result.generatedArtifactType()).isEqualTo("draft_executable_test_case");
        assertThat(result.writtenPath()).isNotNull();
        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("dsl_version: v0.2");
        assertThat(yaml).contains("test_case_id: RP-AR-M1-data-pipeline-TC-001");
        assertThat(yaml).contains("status: draft_executable");
        assertThat(yaml).contains("labels:");
        assertThat(yaml).contains("package: RP-AR-M1-data-pipeline");
        assertThat(yaml).contains("runtime_unit: RU-transform-job");
        assertThat(yaml).contains("source_refs:");
        assertThat(yaml).contains("acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001");
        assertThat(yaml).contains("source_fingerprint:");
        assertThat(yaml).contains("targets:");
        assertThat(yaml).contains("runner: spring_boot_cli");
        assertThat(yaml).contains("environment: ci://pipeline/rp-ar-m1-data-pipeline");
        assertThat(yaml).contains("setup:");
        assertThat(yaml).contains("execute:");
        assertThat(yaml).contains("primary_input:");
        assertThat(yaml).contains("type: input_file");
        assertThat(yaml).contains("lifecycle: read_only");
        assertThat(yaml).contains("operation: run_batch");
        assertThat(yaml).doesNotContain("operation: call_ru");
        assertThat(yaml).contains("expected_results:");
        assertThat(yaml).contains("verify:");
        assertThat(yaml).contains("evidence:");
        assertThat(yaml).contains("runtime:");
        assertThat(yaml).contains("cleanup_required: false");
        assertThat(yaml)
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("traceability:")
                .doesNotContain("execution_target:")
                .doesNotContain("package_inputs:")
                .doesNotContain("oracles:")
                .doesNotContain("steps:")
                .doesNotContain("assertions:")
                .doesNotContain("evidence_required:")
                .doesNotContain("policy:");
        DslValidationReport validation = new DslTestCaseValidator().validate(yaml);
        assertThat(validation.ready()).isTrue();
        assertThat(validation.gaps()).isEmpty();
    }

    @Test
    void writesCleanupFixtureWhenExecutableDraftMutatesState() throws Exception {
        AcReadinessItem readyAc = AcReadinessItem.ready(
                "RP-AR-M1-order-flow-AC-001",
                "RP-AR-M1-order-flow",
                "Seeded order produces persisted allocation",
                "automatable",
                List.of("docs/01-specs/rp_feature_spec.md"));

        TestCaseDraftResult result = new TestCaseLifecycleService().generateDraft(
                tempDir, readyAc, ExecutionContextReadiness.ready(
                        "RU-order-service",
                        "spring_boot_cli",
                        "ci_ephemeral",
                        "ci://pipeline/rp-ar-m1-order-flow",
                        List.of("db_seed", "batch_execution", "db_assertion")));

        assertThat(result.generatedArtifactType()).isEqualTo("draft_executable_test_case");
        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("type: db_seed");
        assertThat(yaml).contains("lifecycle: state_mutating");
        assertThat(yaml).contains("cleanup_required: true");
        assertThat(yaml).contains("cleanup:");
        assertThat(yaml).contains("- id: cleanup_primary_input");
        assertThat(yaml).contains("action: cleanup_bound_input");
        assertThat(yaml).contains("input: ${setup.fixtures.primary_input}");
        DslValidationReport validation = new DslTestCaseValidator().validate(yaml);
        assertThat(validation.gaps()).isEmpty();
        assertThat(validation.ready()).isTrue();
    }

    @Test
    void writesDatasetInputAsReadOnlyFixtureWhenNoCleanupCapabilityIsPresent() throws Exception {
        TestCaseDraftResult result = generateExecutableDraft(
                "RP-AR-M1-dataset-AC-001",
                List.of("dataset_input"));

        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("capabilities: [dataset_input]");
        assertThat(yaml).contains("type: dataset");
        assertThat(yaml).contains("lifecycle: read_only");
        assertThat(yaml).contains("type: application");
        assertThat(yaml).contains("operation: run_application");
        assertThat(yaml).contains("cleanup_required: false");
    }

    @Test
    void writesApiPayloadAsCallApiApplicationTarget() throws Exception {
        TestCaseDraftResult result = generateExecutableDraft(
                "RP-AR-M1-api-AC-001",
                List.of("api_payload"));

        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("type: api_payload");
        assertThat(yaml).contains("type: application");
        assertThat(yaml).contains("operation: call_api");
        assertThat(yaml).contains("cleanup_required: false");
    }

    @Test
    void writesMessageEventAsEventBusTargetWithCleanup() throws Exception {
        TestCaseDraftResult result = generateExecutableDraft(
                "RP-AR-M1-message-AC-001",
                List.of("message_event"));

        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("type: message_event");
        assertThat(yaml).contains("type: event_bus");
        assertThat(yaml).contains("operation: publish_message");
        assertThat(yaml).contains("lifecycle: state_mutating");
        assertThat(yaml).contains("cleanup_required: true");
    }

    @Test
    void writesConfigInputAndEnvVarFixturesAsStateMutatingInputs() throws Exception {
        TestCaseDraftResult configResult = generateExecutableDraft(
                "RP-AR-M1-config-AC-001",
                List.of("config_input"));
        TestCaseDraftResult envResult = generateExecutableDraft(
                "RP-AR-M1-env-AC-001",
                List.of("env_var"));

        String configYaml = Files.readString(configResult.writtenPath());
        assertThat(configYaml).contains("type: config_file");
        assertThat(configYaml).contains("lifecycle: state_mutating");
        assertThat(configYaml).contains("cleanup_required: true");

        String envYaml = Files.readString(envResult.writtenPath());
        assertThat(envYaml).contains("type: env_var");
        assertThat(envYaml).contains("lifecycle: state_mutating");
        assertThat(envYaml).contains("cleanup_required: true");
    }

    @Test
    void writesExistingStateDefaultsWhenCapabilitiesAreEmpty() throws Exception {
        TestCaseDraftResult result = generateExecutableDraft(
                "RP-AR-M1-existing-state-AC-001",
                List.of());

        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("capabilities: []");
        assertThat(yaml).contains("type: existing_state");
        assertThat(yaml).contains("type: application");
        assertThat(yaml).contains("operation: run_application");
        assertThat(yaml).contains("cleanup: []");
    }

    @Test
    void writesSkeletonOnlyWhenAcReadyButExecutionContextIncomplete() throws Exception {
        AcReadinessItem readyAc = AcReadinessItem.ready(
                "RP-AR-M1-data-pipeline-AC-002",
                "RP-AR-M1-data-pipeline",
                "Valid input records evidence",
                "automatable",
                List.of());

        TestCaseDraftResult result = new TestCaseLifecycleService().generateDraft(
                tempDir, readyAc, ExecutionContextReadiness.incomplete(List.of("execution_target.adapter")));

        assertThat(result.generatedArtifactType()).isEqualTo("draft_test_skeleton");
        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("dsl_version: v0.2");
        assertThat(yaml).contains("test_case_id: RP-AR-M1-data-pipeline-TC-002");
        assertThat(yaml).contains("status: draft_skeleton");
        assertThat(yaml).contains("revision: 1");
        assertThat(yaml).contains("labels:");
        assertThat(yaml).contains("package: RP-AR-M1-data-pipeline");
        assertThat(yaml).contains("source_refs:");
        assertThat(yaml).contains("acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-002");
        assertThat(yaml).contains("readiness_gaps:");
        assertThat(yaml).contains("field_path: execution_target.adapter");
        assertThat(yaml).contains("reason: execution_context_incomplete");
        assertThat(yaml).contains("owner_action: Complete RP/RU mapping execution context before executable test generation.");
        assertThat(yaml)
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("traceability:");
    }

    @Test
    void writesEmptyReadinessGapListWhenSkeletonHasNoExplicitGaps() throws Exception {
        AcReadinessItem readyAc = readyAc("RP-AR-M1-data-pipeline-AC-004");

        TestCaseDraftResult result = new TestCaseLifecycleService().generateDraft(
                tempDir, readyAc, ExecutionContextReadiness.incomplete(List.of()));

        String yaml = Files.readString(result.writtenPath());
        assertThat(result.generatedArtifactType()).isEqualTo("draft_test_skeleton");
        assertThat(yaml).contains("readiness_gaps:\n  []");
    }

    @Test
    void doesNotGenerateDraftForAmbiguousAc() {
        AcReadinessItem ambiguousAc = AcReadinessItem.notReady(
                "RP-AR-M1-data-pipeline-AC-003",
                "RP-AR-M1-data-pipeline",
                "Ambiguous",
                "automatable",
                List.of(new com.specdriven.regression.readiness.AcReadinessGap(
                        "behavior", "Clarify owner-authored AC behavior.")));

        TestCaseDraftResult result = new TestCaseLifecycleService().generateDraft(
                tempDir, ambiguousAc, ExecutionContextReadiness.ready(
                        "RU-transform-job",
                        "spring_boot_cli",
                        "ci_ephemeral",
                        "ci://pipeline/rp-ar-m1-data-pipeline",
                        List.of("file_input")));

        assertThat(result.generatedArtifactType()).isEqualTo("none");
        assertThat(result.writtenPath()).isNull();
        assertThat(result.gaps()).contains("AC is not ready for generation");
    }

    @Test
    void createsUpdateProposalInsteadOfOverwritingApprovedTest() throws Exception {
        Path approved = tempDir.resolve("tests/approved/RP-AR-M1-data-pipeline-TC-001.yaml");
        Files.createDirectories(approved.getParent());
        Files.writeString(approved, "artifact_status: approved_for_regression\nowner content\n");
        AcReadinessItem readyAc = AcReadinessItem.ready(
                "RP-AR-M1-data-pipeline-AC-001",
                "RP-AR-M1-data-pipeline",
                "Valid input produces approved output",
                "automatable",
                List.of());

        TestCaseDraftResult result = new TestCaseLifecycleService().generateDraft(
                tempDir, readyAc, ExecutionContextReadiness.incomplete(List.of("expected.result_ref")));

        assertThat(Files.readString(approved)).isEqualTo("artifact_status: approved_for_regression\nowner content\n");
        assertThat(result.generatedArtifactType()).isEqualTo("update_proposal");
        assertThat(result.writtenPath()).isNotNull();
        assertThat(result.writtenPath().toString()).contains("tests/draft");
        String proposal = Files.readString(result.writtenPath());
        assertThat(proposal).contains("proposal_type: test_case_update");
        assertThat(proposal).contains("dsl_version: v0.2");
        assertThat(proposal).contains("test_case_id: RP-AR-M1-data-pipeline-TC-001");
        assertThat(proposal).contains("status: needs_update");
        assertThat(proposal).contains("revision: 1");
        assertThat(proposal).contains("labels:");
        assertThat(proposal).contains("package: RP-AR-M1-data-pipeline");
        assertThat(proposal).contains("source_refs:");
        assertThat(proposal).contains("acceptance_criteria: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001");
        assertThat(proposal).contains("replaces: tests/approved/RP-AR-M1-data-pipeline-TC-001.yaml");
        assertThat(proposal).contains("readiness_gaps:");
        assertThat(proposal)
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("traceability:");
    }

    @Test
    void throwsUncheckedIoExceptionWhenDraftDirectoryCannotBeCreated() throws Exception {
        Files.writeString(tempDir.resolve("tests"), "not a directory");
        AcReadinessItem readyAc = readyAc("RP-AR-M1-data-pipeline-AC-005");

        assertThatThrownBy(() -> new TestCaseLifecycleService().generateDraft(
                        tempDir, readyAc, ExecutionContextReadiness.incomplete(List.of("execution_target.adapter"))))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write test case draft:");
    }

    private TestCaseDraftResult generateExecutableDraft(String acId, List<String> capabilities) {
        return new TestCaseLifecycleService().generateDraft(
                tempDir,
                readyAc(acId),
                ExecutionContextReadiness.ready(
                        "RU-" + acId.toLowerCase().replace("_", "-"),
                        "spring_boot_cli",
                        "ci_ephemeral",
                        "ci://pipeline/" + acId,
                        capabilities));
    }

    private AcReadinessItem readyAc(String acId) {
        String rpId = acId.substring(0, acId.indexOf("-AC-"));
        return AcReadinessItem.ready(
                acId,
                rpId,
                "Owner approved behavior",
                "automatable",
                List.of("docs/01-specs/rp_feature_spec.md"));
    }
}
