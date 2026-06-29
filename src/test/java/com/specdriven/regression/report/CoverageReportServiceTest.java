package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoverageReportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void batchReportUsesBatchRowWhenRunEvidenceFileIsMissing() throws Exception {
        Path packageRoot = tempDir.resolve("RP-001");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        Files.createDirectories(packageRoot.resolve("tests/approved"));
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), """
                acceptance_criteria:
                  - ac_id: AC-001
                    classification: automatable
                """);
        Files.writeString(packageRoot.resolve("tests/approved/TC-001.yaml"), """
                test_case_id: TC-001
                ac_id: AC-001
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-001
                status: completed
                runs:
                  - run_id: RUN-MISSING
                    test_case_id: TC-001
                    ac_id: AC-001
                    status: passed
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: missing_evidence")
                .contains("run_id: RUN-MISSING")
                .contains("test_case_id: TC-001")
                .contains("ac_id: AC-001"));
        assertThat(Files.readString(result.reviewDir().resolve("traceability_report.yaml")))
                .contains("run_id: RUN-MISSING")
                .contains("test_case_id: TC-001")
                .contains("ac_id: AC-001");
    }

    @Test
    void batchReportFlagsMissingBatchEvidenceAndWritesEmptyRunIndexes() throws Exception {
        Path packageRoot = packageRoot("RP-MISSING-BATCH");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-MISSING");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.totalAutomatable()).isEqualTo(1);
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: missing_evidence")
                .contains("evidence/batches/BATCH-MISSING/batch.yaml"));
        assertThat(Files.readString(result.reviewDir().resolve("traceability_report.yaml")))
                .isEqualTo("traceability:\n  []\n");
        assertThat(Files.readString(result.reviewDir().resolve("evidence_index.md")))
                .contains("- Runs: `[]`");
    }

    @Test
    void batchReportIsReviewReadyWhenEveryAutomatableAcHasApprovedPassingEvidence() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-READY");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                batch_id: BATCH-001
                rp_id: RP-BATCH-READY
                test_case_id: TC-001
                ac_id: AC-001
                parameter_case_id: happy-path
                status: passed
                assertion_status: passed
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-READY
                status: completed
                runs:
                  - run_id: RUN-001
                    test_case_id: TC-001
                    ac_id: AC-001
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isTrue();
        assertThat(result.covered()).isEqualTo(1);
        assertThat(result.totalAutomatable()).isEqualTo(1);
        assertThat(result.coveragePercent()).isEqualTo(100.0);
        assertThat(result.gaps()).isEmpty();
        assertThat(Files.readString(result.reviewDir().resolve("failure_summary.yaml")))
                .contains("unresolved_failures: 0")
                .contains("gaps:\n  []")
                .contains("failure_details:\n  []");
        assertThat(Files.readString(result.reviewDir().resolve("traceability_report.yaml")))
                .contains("parameter_case_id: happy-path")
                .contains("run_id: RUN-001");
    }

    @Test
    void batchReportCountsPartialAcceptanceCriteriaWhenApprovedRunPasses() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-PARTIAL-READY");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        writeAcceptanceCriteria(packageRoot, "AC-PARTIAL", "partial");
        writeApprovedTest(packageRoot, "TC-PARTIAL", "AC-PARTIAL");
        writeRun(packageRoot, "RUN-PARTIAL", """
                run_id: RUN-PARTIAL
                batch_id: BATCH-001
                rp_id: RP-BATCH-PARTIAL-READY
                test_case_id: TC-PARTIAL
                ac_id: AC-PARTIAL
                status: passed
                assertion_status: passed
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-PARTIAL-READY
                status: completed
                runs:
                  - run_id: RUN-PARTIAL
                    test_case_id: TC-PARTIAL
                    ac_id: AC-PARTIAL
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isTrue();
        assertThat(result.covered()).isEqualTo(1);
        assertThat(result.totalAutomatable()).isEqualTo(1);
        assertThat(result.gaps()).isEmpty();
        assertThat(Files.readString(result.reviewDir().resolve("traceability_report.yaml")))
                .contains("ac_id: AC-PARTIAL")
                .contains("test_case_id: TC-PARTIAL");
    }

    @Test
    void batchReportListsNonPassingTraceabilityAndAcClassificationGaps() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-GAPS");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), """
                acceptance_criteria:
                  - ac_id: AC-001
                    classification: automatable
                  - ac_id: AC-002
                    classification: partial
                  - ac_id: AC-003
                    classification: manual_only
                  - ac_id: AC-004
                    classification: waived
                    exclusion_record:
                      approved_by: release-owner
                      approved_at: 2026-06-28
                """);
        writeApprovedTest(packageRoot, "TC-APPROVED-OTHER", "AC-002");
        writeRun(packageRoot, "RUN-FAILED", """
                run_id: RUN-FAILED
                batch_id: BATCH-001
                rp_id: RP-BATCH-GAPS
                test_case_id: TC-NOT-APPROVED
                ac_id: AC-001
                status: failed
                assertion_status: passed
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-GAPS
                status: completed
                runs:
                  - run_id: RUN-FAILED
                    test_case_id: TC-NOT-APPROVED
                    ac_id: AC-001
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.totalAutomatable()).isEqualTo(3);
        assertThat(result.covered()).isZero();
        assertThat(result.gaps())
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: run_status_not_passed")
                        .contains("run_status: failed"))
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: missing_traceability")
                        .contains("missing_traceability: TC-NOT-APPROVED -> AC-001"))
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: unapproved_exclusion")
                        .contains("unapproved_exclusion: AC-003"))
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: uncovered_ac")
                        .contains("uncovered_ac: AC-002"));
    }

    @Test
    void batchReportHandlesBatchWithoutRunListAsUncoveredEvidence() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-NO-RUNS");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-NO-RUNS
                status: completed
                runs: not-a-list
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: uncovered_ac")
                .contains("uncovered_ac: AC-001"));
        assertThat(Files.readString(result.reviewDir().resolve("traceability_report.yaml")))
                .isEqualTo("traceability:\n  []\n");
    }

    @Test
    void batchReportWithNoAutomatableAcceptanceCriteriaIsNotReviewReady() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-NO-AUTOMATABLE-ACS");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), """
                acceptance_criteria:
                  - ac_id: AC-MANUAL
                    classification: manual_only
                    exclusion_record:
                      approved_by: release-owner
                      approved_at: 2026-06-28
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-NO-AUTOMATABLE-ACS
                status: completed
                runs: []
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.totalAutomatable()).isZero();
        assertThat(result.coveragePercent()).isZero();
        assertThat(result.gaps()).isEmpty();
        assertThat(Files.readString(result.reviewDir().resolve("coverage_report.yaml")))
                .contains("total_automatable: 0")
                .contains("review_ready: false");
    }

    @Test
    void batchReportFlagsManualOrWaivedExclusionsMissingApprovalFields() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-EXCLUSION-APPROVAL-GAPS");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), """
                acceptance_criteria:
                  - ac_id: AC-MISSING-APPROVER
                    classification: manual_only
                    exclusion_record:
                      approved_at: 2026-06-28
                  - ac_id: AC-MISSING-DATE
                    classification: waived
                    exclusion_record:
                      approved_by: release-owner
                  - ac_id: AC-APPROVED
                    classification: waived
                    exclusion_record:
                      approved_by: release-owner
                      approved_at: 2026-06-28
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-EXCLUSION-APPROVAL-GAPS
                status: completed
                runs: []
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.totalAutomatable()).isEqualTo(2);
        assertThat(result.gaps())
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: unapproved_exclusion")
                        .contains("unapproved_exclusion: AC-MISSING-APPROVER"))
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: unapproved_exclusion")
                        .contains("unapproved_exclusion: AC-MISSING-DATE"))
                .noneSatisfy(gap -> assertThat(gap).contains("AC-APPROVED"));
    }

    @Test
    void batchReportSkipsMalformedRunRowsBeforeComputingCoverage() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-MALFORMED-RUNS");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-MALFORMED-RUNS
                status: completed
                runs:
                  - malformed-row
                  - test_case_id: TC-001
                    ac_id: AC-001
                """);

        CoverageReportResult result = new CoverageReportService().generateBatch(packageRoot, "BATCH-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: uncovered_ac")
                .contains("uncovered_ac: AC-001"));
        assertThat(Files.readString(result.reviewDir().resolve("evidence_index.md")))
                .contains("- Runs: `[]`");
    }

    @Test
    void runReportFlagsMissingRunEvidence() throws Exception {
        Path packageRoot = packageRoot("RP-MISSING-RUN");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-MISSING");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: missing_evidence")
                .contains("evidence/runs/RUN-MISSING/run.yaml"));
    }

    @Test
    void runReportFlagsMissingTraceabilityForPassedRunWithoutApprovedTest() throws Exception {
        Path packageRoot = packageRoot("RP-RUN-MISSING-TRACEABILITY");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-RUN-MISSING-TRACEABILITY
                test_case_id: TC-NOT-APPROVED
                ac_id: AC-001
                status: passed
                assertion_status: passed
                """);

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.gaps())
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: missing_traceability")
                        .contains("missing_traceability: TC-NOT-APPROVED -> AC-001"))
                .anySatisfy(gap -> assertThat(gap)
                        .contains("reason: uncovered_ac")
                        .contains("uncovered_ac: AC-001"));
    }

    @Test
    void runReportHandlesMalformedAcceptanceCriteriaAndApprovedTestYaml() throws Exception {
        Path packageRoot = packageRoot("RP-RUN-MALFORMED-YAML");
        Files.createDirectories(packageRoot.resolve("tests/approved"));
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), "acceptance_criteria: not-a-list\n");
        Files.writeString(packageRoot.resolve("tests/approved/TC-001.yaml"), "[]\n");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-RUN-MALFORMED-YAML
                test_case_id: TC-001
                ac_id: AC-001
                status: passed
                assertion_status: passed
                """);

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isZero();
        assertThat(result.totalAutomatable()).isZero();
        assertThat(result.coveragePercent()).isZero();
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: missing_traceability")
                .contains("missing_traceability: TC-001 -> AC-001"));
    }

    @Test
    void runReportRequiresBatchEvidenceEvenWhenSingleRunPassed() throws Exception {
        Path packageRoot = packageRoot("RP-SINGLE-RUN");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-SINGLE-RUN
                test_case_id: TC-001
                ac_id: AC-001
                status: passed
                assertion_status: passed
                """);

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(result.reviewReady()).isFalse();
        assertThat(result.covered()).isEqualTo(1);
        assertThat(result.gaps()).anySatisfy(gap -> assertThat(gap)
                .contains("reason: batch_required_for_release_coverage")
                .contains("run_id: RUN-001"));
    }

    @Test
    void failedRunWithoutAssertionReferenceWritesActionableFailureSummary() throws Exception {
        Path packageRoot = packageRoot("RP-ASSERTION-MISSING-REF");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-ASSERTION-MISSING-REF
                test_case_id: TC-001
                ac_id: AC-001
                status: failed
                assertion_status: failed
                """);

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(Files.readString(result.reviewDir().resolve("failure_summary.yaml")))
                .contains("assertion_evidence: missing")
                .contains("Review assertion configuration");
    }

    @Test
    void failedRunReportsMissingAssertionEvidenceFile() throws Exception {
        Path packageRoot = packageRoot("RP-ASSERTION-MISSING-FILE");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-ASSERTION-MISSING-FILE
                test_case_id: TC-001
                ac_id: AC-001
                status: failed
                assertion_status: failed
                assertions: assertions.yaml
                """);

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(Files.readString(result.reviewDir().resolve("failure_summary.yaml")))
                .contains("assertion_evidence: assertions.yaml missing")
                .contains("Restore or regenerate the missing assertion evidence");
    }

    @Test
    void failedRunReportsUnreadableAssertionEvidenceFormat() throws Exception {
        Path packageRoot = packageRoot("RP-ASSERTION-UNREADABLE");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-ASSERTION-UNREADABLE
                test_case_id: TC-001
                ac_id: AC-001
                status: failed
                assertion_status: failed
                assertions: assertions.yaml
                """);
        Files.writeString(packageRoot.resolve("evidence/runs/RUN-001/assertions.yaml"), "assertions: []\n");

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(Files.readString(result.reviewDir().resolve("failure_summary.yaml")))
                .contains("assertion_evidence: assertions.yaml unreadable")
                .contains("Fix assertion evidence format");
    }

    @Test
    void failedRunCopiesAssertionFailureDetailsIntoFailureSummary() throws Exception {
        Path packageRoot = packageRoot("RP-ASSERTION-DETAIL");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-ASSERTION-DETAIL
                test_case_id: TC-001
                ac_id: AC-001
                status: failed
                assertion_status: failed
                assertions: assertions.yaml
                """);
        Files.writeString(packageRoot.resolve("evidence/runs/RUN-001/assertions.yaml"), """
                assertions:
                  - status: failed
                    expected_ref: expected/approved.json
                    actual_ref: actual/output.json
                    decision_rule: exact_match
                    diff_summary: value changed
                """);

        CoverageReportResult result = new CoverageReportService().generate(packageRoot, "RUN-001");

        assertThat(Files.readString(result.reviewDir().resolve("failure_summary.yaml")))
                .contains("expected_ref: expected/approved.json")
                .contains("actual_ref: actual/output.json")
                .contains("decision_rule: exact_match")
                .contains("diff_summary: value changed");
    }

    @Test
    void runReportThrowsUncheckedIoWhenReviewPackageCannotBeWritten() throws Exception {
        Path packageRoot = packageRoot("RP-RUN-REVIEW-IO");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                rp_id: RP-RUN-REVIEW-IO
                test_case_id: TC-001
                ac_id: AC-001
                status: passed
                assertion_status: passed
                """);
        Files.writeString(packageRoot.resolve("evidence/review"), "not a directory");

        assertThatThrownBy(() -> new CoverageReportService().generate(packageRoot, "RUN-001"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write coverage report package.");
    }

    @Test
    void batchReportThrowsUncheckedIoWhenReviewPackageCannotBeWritten() throws Exception {
        Path packageRoot = packageRoot("RP-BATCH-REVIEW-IO");
        Files.createDirectories(packageRoot.resolve("evidence/batches/BATCH-001"));
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        writeApprovedTest(packageRoot, "TC-001", "AC-001");
        writeRun(packageRoot, "RUN-001", """
                run_id: RUN-001
                batch_id: BATCH-001
                rp_id: RP-BATCH-REVIEW-IO
                test_case_id: TC-001
                ac_id: AC-001
                status: passed
                assertion_status: passed
                """);
        Files.writeString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"), """
                batch_id: BATCH-001
                rp_id: RP-BATCH-REVIEW-IO
                status: completed
                runs:
                  - run_id: RUN-001
                    test_case_id: TC-001
                    ac_id: AC-001
                """);
        Files.writeString(packageRoot.resolve("evidence/review"), "not a directory");

        assertThatThrownBy(() -> new CoverageReportService().generateBatch(packageRoot, "BATCH-001"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write batch coverage report package.");
    }

    @Test
    void reportThrowsUncheckedIoWhenApprovedTestsDirectoryCannotBeListed() throws Exception {
        Path packageRoot = packageRoot("RP-APPROVED-IO");
        writeAcceptanceCriteria(packageRoot, "AC-001", "automatable");
        Path approvedDir = packageRoot.resolve("tests/approved");
        Files.createDirectories(approvedDir);
        Files.setPosixFilePermissions(approvedDir, PosixFilePermissions.fromString("---------"));
        try {
            assertThatThrownBy(() -> new CoverageReportService().generate(packageRoot, "RUN-001"))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("Failed to read approved tests for report.");
        } finally {
            Files.setPosixFilePermissions(approvedDir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private Path packageRoot(String name) {
        return tempDir.resolve(name);
    }

    private void writeAcceptanceCriteria(Path packageRoot, String acId, String classification) throws Exception {
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), """
                acceptance_criteria:
                  - ac_id: %s
                    classification: %s
                """.formatted(acId, classification));
    }

    private void writeApprovedTest(Path packageRoot, String testCaseId, String acId) throws Exception {
        Files.createDirectories(packageRoot.resolve("tests/approved"));
        Files.writeString(packageRoot.resolve("tests/approved/" + testCaseId + ".yaml"), """
                test_case_id: %s
                ac_id: %s
                """.formatted(testCaseId, acId));
    }

    private void writeRun(Path packageRoot, String runId, String runYaml) throws Exception {
        Files.createDirectories(packageRoot.resolve("evidence/runs").resolve(runId));
        Files.writeString(packageRoot.resolve("evidence/runs").resolve(runId).resolve("run.yaml"), runYaml);
    }
}
