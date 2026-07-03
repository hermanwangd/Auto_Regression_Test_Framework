package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceIndexFormatterTest {

    @TempDir
    Path tempDir;

    @Test
    void nestedMultiTestEvidenceKeepsOwningTestCaseId() throws Exception {
        Path evidence = tempDir.resolve("tests/TC-002/assertions/payload_matches.yaml");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, """
                comparison_status: passed
                """);

        String index = EvidenceIndexFormatter.format(
                new EvidenceIndexFormatter.Context(
                        "SUITE-001",
                        "BATCH-001",
                        "RUN-001",
                        "SUITE-001-MULTI",
                        "local",
                        "json_match",
                        "json-verifier"),
                tempDir,
                List.of("tests/TC-002/assertions/payload_matches.yaml"));

        assertThat(index)
                .contains("file_path: tests/TC-002/assertions/payload_matches.yaml")
                .contains("    test_case_id: TC-002")
                .doesNotContain("    test_case_id: SUITE-001-MULTI\n    run_id: RUN-001");
    }

    @Test
    void checkedInEvidenceIndexFixturesUseStandardContractShape() throws Exception {
        try (Stream<Path> paths = Files.walk(Path.of("samples"))) {
            List<Path> evidenceIndexes = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("evidence_index.yaml")
                            || path.getFileName().toString().endsWith("expected_evidence_index.yaml"))
                    .sorted()
                    .toList();

            assertThat(evidenceIndexes)
                    .as("checked-in evidence index fixtures")
                    .isNotEmpty();
            for (Path evidenceIndex : evidenceIndexes) {
                String text = Files.readString(evidenceIndex);
                assertThat(text)
                        .as(evidenceIndex + " standard evidence index fields")
                        .contains("evidence_id:")
                        .contains("file_path:")
                        .contains("masking_applied:")
                        .doesNotContain("    ref:")
                        .doesNotContain("    masked:")
                        .doesNotContain("required_entries:")
                        .doesNotContain("evidence_type: provider_result")
                        .doesNotContain("evidence_type: query_result")
                        .doesNotContain("evidence_type: event_result")
                        .doesNotContain("evidence_type: resolved_execution_plan")
                        .doesNotContain("evidence_type: assertion_result")
                        .doesNotContain("evidence_type: actual_output")
                        .doesNotContain("evidence_type: expected_result_ref");
            }
        }
    }
}
