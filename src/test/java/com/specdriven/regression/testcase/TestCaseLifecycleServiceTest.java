package com.specdriven.regression.testcase;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.readiness.AcReadinessItem;
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
        assertThat(yaml).contains("dsl_version: v1");
        assertThat(yaml).contains("test_case_id: RP-AR-M1-data-pipeline-TC-001");
        assertThat(yaml).contains("status: draft_executable");
        assertThat(yaml).contains("traceability:");
        assertThat(yaml).contains("package_id: RP-AR-M1-data-pipeline");
        assertThat(yaml).contains("acceptance_criteria_id: RP-AR-M1-data-pipeline-AC-001");
        assertThat(yaml).contains("source: acceptance_criteria.md#RP-AR-M1-data-pipeline-AC-001");
        assertThat(yaml).contains("source_fingerprint:");
        assertThat(yaml).contains("targets:");
        assertThat(yaml).contains("runner: spring_boot_cli");
        assertThat(yaml).contains("environment: ci://pipeline/rp-ar-m1-data-pipeline");
        assertThat(yaml).contains("setup:");
        assertThat(yaml).contains("execute:");
        assertThat(yaml).contains("primary_input:");
        assertThat(yaml).contains("type: input_file");
        assertThat(yaml).contains("lifecycle: read_only");
        assertThat(yaml).contains("operation: call_ru");
        assertThat(yaml).contains("expected_results:");
        assertThat(yaml).contains("verify:");
        assertThat(yaml).contains("evidence:");
        assertThat(yaml).contains("runtime:");
        assertThat(yaml).contains("cleanup_required: false");
        assertThat(yaml)
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("execution_target:")
                .doesNotContain("package_inputs:")
                .doesNotContain("oracles:")
                .doesNotContain("steps:")
                .doesNotContain("assertions:")
                .doesNotContain("evidence_required:")
                .doesNotContain("policy:");
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
        assertThat(yaml).contains("artifact_status: draft_test_skeleton");
        assertThat(yaml).contains("readiness_gaps:");
        assertThat(yaml).contains("field_path: execution_target.adapter");
        assertThat(yaml).contains("reason: execution_context_incomplete");
        assertThat(yaml).contains("owner_action: Complete RP/RU mapping execution context before executable test generation.");
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
        assertThat(Files.readString(result.writtenPath())).contains("replaces: tests/approved/RP-AR-M1-data-pipeline-TC-001.yaml");
    }
}
