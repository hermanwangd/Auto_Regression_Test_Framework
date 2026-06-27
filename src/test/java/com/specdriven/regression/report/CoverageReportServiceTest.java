package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
