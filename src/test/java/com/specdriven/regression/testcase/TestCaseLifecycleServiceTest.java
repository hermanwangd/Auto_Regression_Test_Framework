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
        assertThat(yaml).contains("ac_id: RP-AR-M1-data-pipeline-AC-001");
        assertThat(yaml).contains("artifact_status: draft_executable_test_case");
        assertThat(yaml).contains("owner: product_developer");
        assertThat(yaml).contains("source_refs:");
        assertThat(yaml).contains("source_fingerprint:");
        assertThat(yaml).contains("execution_target:");
        assertThat(yaml).contains("steps:");
        assertThat(yaml).contains("assertions:");
        assertThat(yaml).contains("evidence_required:");
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
        assertThat(yaml).contains("execution_target.adapter");
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
