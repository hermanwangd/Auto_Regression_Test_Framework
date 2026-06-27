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
}
