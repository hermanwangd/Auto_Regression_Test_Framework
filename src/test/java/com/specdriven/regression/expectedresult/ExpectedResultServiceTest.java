package com.specdriven.regression.expectedresult;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.readiness.AcReadinessGap;
import com.specdriven.regression.readiness.AcReadinessItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpectedResultServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void draftsReviewableExpectedResultFromReadyAcWithSourceReferences() throws Exception {
        AcReadinessItem readyAc = AcReadinessItem.ready(
                "RP-001-AC-001",
                "RP-001",
                "Valid input produces expected output",
                "automatable",
                List.of("rp_feature_spec.md"));

        ExpectedResultDraftResult result = new ExpectedResultService().draftExpectedResult(
                tempDir, readyAc, List.of("fixture/input/orders.csv"), "expected/output/orders.csv");

        assertThat(result.status()).isEqualTo("draft");
        assertThat(result.writtenPath()).isEqualTo(tempDir.resolve("expected-results/draft/RP-001-ER-001.yaml"));
        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("expected_result_id: RP-001-ER-001");
        assertThat(yaml).contains("rp_id: RP-001");
        assertThat(yaml).contains("ac_id: RP-001-AC-001");
        assertThat(yaml).contains("status: draft");
        assertThat(yaml).contains("acceptance_criteria.md#RP-001-AC-001");
        assertThat(yaml).contains("fixture/input/orders.csv");
        assertThat(yaml).contains("output_ref: expected/output/orders.csv");
        assertThat(yaml).contains("unresolved_gaps: []");
        assertThat(yaml).contains("approved_by: null");
    }

    @Test
    void blocksExpectedResultDraftWhenAcIsNotReady() throws Exception {
        AcReadinessItem notReady = AcReadinessItem.notReady(
                "RP-001-AC-002",
                "RP-001",
                "Missing rules",
                "automatable",
                List.of(new AcReadinessGap("pass_fail_rule", "Clarify owner-authored AC pass/fail rule.")));

        ExpectedResultDraftResult result = new ExpectedResultService().draftExpectedResult(
                tempDir, notReady, List.of("fixture/input/orders.csv"), "expected/output/orders.csv");

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.writtenPath()).isEqualTo(tempDir.resolve("expected-results/draft/RP-001-ER-002.yaml"));
        String yaml = Files.readString(result.writtenPath());
        assertThat(yaml).contains("status: blocked");
        assertThat(yaml).contains("blocked_reason: AC is not ready for expected-result drafting");
        assertThat(yaml).contains("pass_fail_rule");
    }

    @Test
    void approvesOnlyExpectedResultsWithApprovalFieldsForRegressionTruth() throws Exception {
        Path approved = tempDir.resolve("expected-results/approved/RP-001-ER-001.yaml");
        Files.createDirectories(approved.getParent());
        Files.writeString(approved, """
                expected_result_id: RP-001-ER-001
                rp_id: RP-001
                ac_id: RP-001-AC-001
                status: approved_for_regression
                source_refs:
                  - acceptance_criteria.md#RP-001-AC-001
                input_refs:
                  - fixture/input/orders.csv
                expected_outputs:
                  output_ref: expected/output/orders.csv
                assumptions: []
                unresolved_gaps: []
                approved_by: product_developer
                approved_at: 2026-06-27T00:00:00+08:00
                approval_ref: docs/10-change-control/13_issue_template.md#APP-001
                blocked_reason: null
                """);

        ExpectedResultEligibilityReport report = new ExpectedResultService().checkEligibility(tempDir, "RP-001-AC-001");

        assertThat(report.eligible()).isTrue();
        assertThat(report.status()).isEqualTo("approved_for_regression");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksMissingDraftBlockedOrUnapprovedExpectedResultsBeforeAssertionEvaluation() throws Exception {
        Path draft = tempDir.resolve("expected-results/draft/RP-001-ER-001.yaml");
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, """
                expected_result_id: RP-001-ER-001
                rp_id: RP-001
                ac_id: RP-001-AC-001
                status: draft
                source_refs:
                  - acceptance_criteria.md#RP-001-AC-001
                unresolved_gaps: []
                """);

        ExpectedResultEligibilityReport draftReport =
                new ExpectedResultService().checkEligibility(tempDir, "RP-001-AC-001");
        ExpectedResultEligibilityReport missingReport =
                new ExpectedResultService().checkEligibility(tempDir, "RP-001-AC-404");

        assertThat(draftReport.eligible()).isFalse();
        assertThat(draftReport.gaps()).extracting(ExpectedResultGap::ownerAction)
                .contains("Approve expected result before using it as regression truth.");
        assertThat(missingReport.eligible()).isFalse();
        assertThat(missingReport.gaps()).extracting(ExpectedResultGap::fieldPath)
                .contains("expected-results.approved");
    }
}
