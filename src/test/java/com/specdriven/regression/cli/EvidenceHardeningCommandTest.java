package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceHardeningCommandTest {

    private static final Path VALID_RESULT = Path.of("samples/evidence_hardening/valid_result.json");
    private static final Path MISSING_INDEX_RESULT = Path.of("samples/evidence_hardening/invalid_missing_evidence_result.json");
    private static final Path SECRET_LEAK_RESULT = Path.of("samples/evidence_hardening/invalid_secret_leak_result.json");

    @TempDir
    Path tempDir;

    @Test
    void evidenceHardeningSampleArtifactsAreCheckedInAtRequiredPaths() {
        List<String> requiredPaths = List.of(
                "schemas/evidence_index.v0.2.schema.yaml",
                "samples/evidence_hardening/valid_result.json",
                "samples/evidence_hardening/invalid_missing_evidence_result.json",
                "samples/evidence_hardening/invalid_secret_leak_result.json",
                "samples/evidence_hardening/evidence/evidence_index.yaml",
                "samples/evidence_hardening/evidence/execution_log.txt",
                "samples/evidence_hardening/evidence/batch_summary.json",
                "samples/evidence_hardening/evidence/assertion_diff.json",
                "samples/evidence_hardening/evidence/provider_evidence.json");

        assertThat(requiredPaths).allSatisfy(path -> assertThat(Files.exists(Path.of(path)))
                .as(path + " should be checked in")
                .isTrue());
    }

    @Test
    void validEvidenceIndexPassesValidateEvidenceAndReportShowsSummary() {
        CommandResult validation = execute("validate-evidence", "--result", VALID_RESULT.toString());
        CommandResult report = execute("report", "--result", VALID_RESULT.toString());

        assertThat(validation.exit()).as(validation.stderr() + validation.stdout()).isZero();
        assertThat(validation.stdout())
                .contains("evidence_validation_status: passed")
                .contains("suite_id: EVIDENCE-HARDENING-v0.2")
                .contains("missing_evidence_count: 0")
                .contains("masking_status: passed")
                .contains("provider_type: wiremock_http_mock")
                .contains("provider_type: jdbc")
                .contains("provider_type: nats");

        assertThat(report.exit()).as(report.stderr() + report.stdout()).isZero();
        assertThat(report.stdout())
                .contains("report_status: review_ready")
                .contains("evidence_folder_path:")
                .contains("missing_evidence_count: 0")
                .contains("failed_evidence_summary:")
                .contains("provider_evidence_summary:")
                .contains("masking_status: passed");
    }

    @Test
    void reportWithInvalidEvidenceDoesNotClaimReviewReady() throws Exception {
        Path resultJson = mutableEvidenceHardening("report_invalid_evidence");
        Files.delete(resultJson.getParent().resolve("evidence/provider_evidence.json"));

        CommandResult result = execute("report", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("report_status: invalid")
                .contains("reason: missing_evidence_file")
                .doesNotContain("report_status: review_ready\n");
    }

    @Test
    void validateEvidenceRejectsProviderResultWithoutStandardSuiteSummary() throws Exception {
        Path resultJson = mutableEvidenceHardening("provider_result_without_suite_summary");
        Files.writeString(resultJson, read(resultJson)
                .replaceFirst("(?m)^  \"test_count\": 1,\\n", "")
                .replaceFirst("(?s)^  \"test_results\": \\[.*?^  \\],\\n", ""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_field")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsStandardResultWithoutGeneratedTimingFields() throws Exception {
        Path resultJson = mutableEvidenceHardening("missing_generated_timing_fields");
        Files.writeString(resultJson, read(resultJson)
                .replaceFirst("(?m)^  \"start_time\": \"2026-06-30T00:00:00Z\",\\n", ""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_field")
                .contains("field_path: start_time")
                .contains("owner_action:");
    }

    @Test
    void missingEvidenceIndexFailsClearly() {
        CommandResult result = execute("validate-evidence", "--result", MISSING_INDEX_RESULT.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("evidence_validation_status: failed")
                .contains("reason: missing_evidence_index")
                .contains("owner_action:");
    }

    @Test
    void missingEvidenceFileFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("missing_file");
        Files.delete(resultJson.getParent().resolve("evidence/provider_evidence.json"));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_evidence_file")
                .contains("failure_code: EVIDENCE_MISSING_EVIDENCE_FILE")
                .contains("category: EVIDENCE_ERROR")
                .contains("provider_evidence.json")
                .contains("owner_action:");
    }

    @Test
    void unknownResultEvidenceRefFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("unknown_ref");
        Files.writeString(resultJson, read(resultJson)
                .replace("\"polling-observation-001\"", "\"polling-observation-001\", \"unknown-evidence-id\""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: unknown_evidence_ref")
                .contains("unknown-evidence-id");
    }

    @Test
    void duplicateEvidenceIdFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("duplicate_id");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(index, read(index).replace(
                "evidence_id: batch-summary-001",
                "evidence_id: execution-log-001"));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: duplicate_evidence_id");
    }

    @Test
    void unsupportedEvidenceTypeFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("unsupported_type");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(index, read(index).replace("evidence_type: nats_event", "evidence_type: unknown_event"));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: unsupported_evidence_type");
    }

    @Test
    void kafkaProviderResultRequiresKafkaEventEvidence() throws Exception {
        Path resultJson = providerResultWithoutProviderEventEvidence("kafka_missing_event", "kafka", "order-events");

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_evidence")
                .contains("Add required evidence entry type `kafka_event`");
    }

    @Test
    void ibmMqProviderResultRequiresIbmMqEventEvidence() throws Exception {
        Path resultJson = providerResultWithoutProviderEventEvidence("ibm_mq_missing_event", "ibm_mq", "payment-mq");

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_evidence")
                .contains("Add required evidence entry type `ibm_mq_event`");
    }

    @Test
    void providerEvidenceMissingProviderTypeFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("missing_provider_type");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(index, read(index).replace("    provider_type: jdbc\n", ""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_provider_metadata")
                .contains("provider_type");
    }

    @Test
    void failedVerifierWithoutFailureEvidenceFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("failed_without_failure_evidence");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(resultJson, read(resultJson)
                .replace("\"status\": \"passed\"", "\"status\": \"failed\"")
                .replace("\"status\": \"passed\",\n      \"expected_ref\"", "\"status\": \"failed\",\n      \"expected_ref\""));
        Files.writeString(index, read(index)
                .replace("    status: failed\n    created_at:", "    status: passed\n    created_at:")
                .replace("    failure_code: ASSERTION_MISMATCH\n", ""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: missing_failure_evidence");
    }

    @Test
    void cleanupFailureNotIndexedFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("cleanup_failure_not_indexed");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(resultJson, read(resultJson)
                .replace("\"cleanup_status\": \"passed\"", "\"cleanup_status\": \"failed\""));
        Files.writeString(index, read(index)
                .replace("    evidence_type: jdbc_cleanup", "    evidence_type: jdbc_query"));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: cleanup_failure_not_indexed");
    }

    @Test
    void cleanupFailureMustPreserveOriginalFailure() throws Exception {
        Path resultJson = mutableEvidenceHardening("cleanup_hides_original_failure");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(resultJson, read(resultJson)
                .replace("\"status\": \"passed\"", "\"status\": \"failed\"")
                .replace("\"status\": \"passed\",\n      \"expected_ref\"", "\"status\": \"failed\",\n      \"expected_ref\"")
                .replace("\"cleanup_status\": \"passed\"", "\"cleanup_status\": \"failed\"")
                .replace("\"code\": null", "\"code\": \"DB_CLEANUP_FAILED\"")
                .replace("\"classification\": null", "\"classification\": \"CLEANUP_ERROR\""));
        Files.writeString(index, read(index)
                .replace("    evidence_type: assertion_diff", "    evidence_type: assertion_diff")
                .replace("    status: passed\n    created_at:", "    status: failed\n    failure_code: ASSERTION_MISMATCH\n    created_at:")
                .replace("    evidence_type: jdbc_cleanup", "    evidence_type: jdbc_cleanup")
                .replace("    linked_result_field: provider_results.cleanup_status",
                        "    failure_code: DB_CLEANUP_FAILED\n    linked_result_field: provider_results.cleanup_status"));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: cleanup_failure_hides_original_failure")
                .contains("category: CLEANUP_ERROR");
    }

    @Test
    void pollingTimeoutRequiresLastObservedEvidenceRef() throws Exception {
        Path resultJson = mutableEvidenceHardening("polling_timeout_without_last_observed");
        Files.writeString(resultJson, read(resultJson)
                .replace("\"status\": \"passed\"", "\"status\": \"failed\"")
                .replace("\"status\": \"passed\",\n      \"polling\"", "\"status\": \"failed\",\n      \"polling\"")
                .replace("\"last_observed_ref\": \"polling-observation-001\",\n", "")
                .replace("\"code\": null", "\"code\": \"POLLING_TIMEOUT\"")
                .replace("\"classification\": null", "\"classification\": \"VERIFICATION_FAILED\""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_polling_last_observed_evidence")
                .contains("category: EVIDENCE_ERROR");
    }

    @Test
    void rawSecretInEvidenceFailsClearly() throws Exception {
        Path resultJson = mutableEvidenceHardening("raw_secret_evidence");
        Files.writeString(resultJson.getParent().resolve("evidence/provider_evidence.json"),
                read(resultJson.getParent().resolve("evidence/provider_evidence.json"))
                        .replace("\"provider_runtime\": \"masked\"", "\"password\": \"raw-secret-value\""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: raw_secret");
    }

    @Test
    void rawJdbcUrlInEvidenceFailsForAnyJdbcScheme() throws Exception {
        Path resultJson = mutableEvidenceHardening("raw_jdbc_url_evidence");
        Files.writeString(resultJson.getParent().resolve("evidence/provider_evidence.json"),
                read(resultJson.getParent().resolve("evidence/provider_evidence.json"))
                        .replace("\"provider_runtime\": \"masked\"",
                                "\"provider_runtime\": \"jdbc:h2:mem:leaked_db;DB_CLOSE_DELAY=-1\""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout()).contains("reason: raw_secret");
    }

    @Test
    void rawSecretInResultJsonFailsClearly() {
        CommandResult result = execute("validate-evidence", "--result", SECRET_LEAK_RESULT.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: raw_secret")
                .contains("invalid_secret_leak_result.json");
    }

    @Test
    void validateEvidenceRejectsMismatchedMultiTestResultCount() throws Exception {
        Path resultJson = mutableEvidenceHardening("mismatched_test_count");
        Files.writeString(resultJson, addMultiTestResultFields(
                read(resultJson),
                2,
                """
                    {
                      "test_case_id": "EVIDENCE-HARDENING-TC-001",
                      "status": "passed",
                      "profile": "local_evidence"
                    }
                """));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_test_count")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsNonIntegerMultiTestResultCount() throws Exception {
        Path resultJson = mutableEvidenceHardening("non_integer_test_count");
        Files.writeString(resultJson, addMultiTestResultFields(
                read(resultJson),
                "\"1\"",
                """
                    {
                      "test_case_id": "EVIDENCE-HARDENING-TC-001",
                      "status": "passed",
                      "profile": "local_evidence"
                    }
                """));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_test_count")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsTestResultsWithoutTestCount() throws Exception {
        Path resultJson = mutableEvidenceHardening("test_results_without_count");
        Files.writeString(resultJson, insertBeforeFailure(stripStandardSuiteSummary(read(resultJson)), """
                  "test_results": [
                    {
                      "test_case_id": "EVIDENCE-HARDENING-TC-001",
                      "status": "passed",
                      "profile": "local_evidence"
                    }
                  ],
                """));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_field")
                .contains("field_path: test_count")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsTestResultMissingRequiredFields() throws Exception {
        Path resultJson = mutableEvidenceHardening("invalid_test_result_entry");
        Files.writeString(resultJson, addMultiTestResultFields(
                read(resultJson),
                1,
                """
                    {
                      "test_case_id": "EVIDENCE-HARDENING-TC-001",
                      "profile": "local_evidence"
                    }
                """));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_field")
                .contains("field_path: test_results[0].status")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsInvalidTestResultStatus() throws Exception {
        Path resultJson = mutableEvidenceHardening("invalid_test_result_status");
        Files.writeString(resultJson, addMultiTestResultFields(
                read(resultJson),
                1,
                """
                    {
                      "test_case_id": "EVIDENCE-HARDENING-TC-001",
                      "status": "running",
                      "profile": "local_evidence"
                    }
                """));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_status")
                .contains("field_path: test_results[0].status")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsNonObjectTestResultEntry() throws Exception {
        Path resultJson = mutableEvidenceHardening("non_object_test_result_entry");
        Files.writeString(resultJson, addMultiTestResultFields(read(resultJson), 1, "    \"not-an-object\""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_test_results")
                .contains("field_path: test_results[0]")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsMultiProviderResultWithTopLevelProviderIdentity() throws Exception {
        Path resultJson = mutableEvidenceHardening("multi_provider_root_provider");
        String multiProviderResult = addMultiTestResultFields(
                read(resultJson),
                2,
                """
                    {
                      "test_case_id": "KAFKA-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "order-events",
                      "provider_type": "kafka"
                    },
                    {
                      "test_case_id": "IBM-MQ-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "payment-mq",
                      "provider_type": "ibm_mq"
                    }
                """).replaceFirst("  \\\"status\\\": \\\"passed\\\",\\n", "  \"provider_type\": \"kafka\",\n  \"status\": \"passed\",\n");
        Files.writeString(resultJson, multiProviderResult);

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_provider_summary")
                .contains("field_path: provider_type")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsMultiProviderResultWithoutProviderSummary() throws Exception {
        Path resultJson = mutableEvidenceHardening("multi_provider_missing_summary");
        String multiProviderResult = addMultiTestResultFields(
                read(resultJson),
                2,
                """
                    {
                      "test_case_id": "KAFKA-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "order-events",
                      "provider_type": "kafka"
                    },
                    {
                      "test_case_id": "IBM-MQ-CAPABILITY-TC-001",
                      "status": "passed",
                      "profile": "local_messaging",
                      "provider_id": "payment-mq",
                      "provider_type": "ibm_mq"
                    }
                """);
        Files.writeString(resultJson, multiProviderResult);

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_field")
                .contains("field_path: provider_summary")
                .contains("owner_action:");
    }

    @Test
    void validateEvidenceRejectsMultiProviderResultWithoutProviderSummaryWhenInferredFromProviderResults()
            throws Exception {
        Path resultJson = mutableEvidenceHardening("multi_provider_provider_results_missing_summary");
        String multiProviderResult = addMultiTestResultFields(
                read(resultJson),
                1,
                """
                    {
                      "test_case_id": "EVIDENCE-HARDENING-TC-001",
                      "status": "passed",
                      "profile": "local_evidence"
                    }
                """);
        Files.writeString(resultJson, multiProviderResult);

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: missing_required_field")
                .contains("field_path: provider_summary")
                .contains("owner_action:");
    }

    @Test
    void evidenceFilePathMustStayUnderEvidenceFolder() throws Exception {
        Path resultJson = mutableEvidenceHardening("outside_evidence_path");
        Path outsideEvidence = resultJson.getParent().resolve("outside.txt");
        Files.writeString(outsideEvidence, "outside evidence with safe text\n");
        Path index = resultJson.getParent().resolve("evidence/evidence_index.yaml");
        Files.writeString(index, read(index).replace("file_path: provider_evidence.json", "file_path: ../outside.txt"));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_evidence_path")
                .contains("Keep evidence file paths under the evidence index folder");
    }

    @Test
    void resultEvidenceRefMayUseResultRelativeFilePath() throws Exception {
        Path resultJson = mutableEvidenceHardening("result_relative_file_ref");
        Files.writeString(resultJson, read(resultJson)
                .replaceFirst("\\\"assertion-diff-001\\\"", "\"evidence/assertion_diff.json\""));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).as(result.stdout()).isZero();
        assertThat(result.stdout()).doesNotContain("unknown_evidence_ref");
    }

    @Test
    void providerEvidenceRefsRejectFrameworkEvidenceRefs() throws Exception {
        Path resultJson = mutableEvidenceHardening("provider_evidence_refs_with_framework_ref");
        Files.writeString(resultJson, read(resultJson)
                .replace("\"provider_evidence_refs\": [", "\"provider_evidence_refs\": [\n    \"logs/execution.log\","));

        CommandResult result = execute("validate-evidence", "--result", resultJson.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.stdout())
                .contains("reason: invalid_provider_evidence_ref")
                .contains("field_path: provider_evidence_refs[0]")
                .contains("reserve `provider_evidence_refs` for provider-produced evidence");
    }

    private Path mutableEvidenceHardening(String name) throws IOException {
        Path target = tempDir.resolve(name);
        copyDirectory(Path.of("samples/evidence_hardening"), target);
        return target.resolve("valid_result.json");
    }

    private Path providerResultWithoutProviderEventEvidence(
            String name,
            String providerType,
            String providerId) throws IOException {
        Path target = tempDir.resolve(name);
        Path evidence = target.resolve("evidence");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("execution_log.txt"), "evidence_type: execution_log\nstatus: passed\n");
        Files.writeString(evidence.resolve("batch_summary.json"), "{\"status\":\"passed\"}\n");
        Files.writeString(evidence.resolve("evidence_index.yaml"), """
                evidence_index_version: v0.2
                suite_id: %s-REQUIRED-EVIDENCE
                batch_id: BATCH-001
                run_id: RUN-001
                test_case_id: TC-001
                profile: local
                entries:
                  - evidence_id: execution-log-001
                    evidence_type: execution_log
                    produced_by: framework
                    test_case_id: TC-001
                    run_id: RUN-001
                    batch_id: BATCH-001
                    file_path: execution_log.txt
                    content_type: text/plain
                    status: passed
                    created_at: 2026-07-02T00:00:00Z
                    masking_applied: true
                    linked_result_field: evidence_refs
                  - evidence_id: batch-summary-001
                    evidence_type: batch_summary
                    produced_by: framework
                    test_case_id: TC-001
                    run_id: RUN-001
                    batch_id: BATCH-001
                    file_path: batch_summary.json
                    content_type: application/json
                    status: passed
                    created_at: 2026-07-02T00:00:00Z
                    masking_applied: true
                    linked_result_field: evidence_refs
                masking:
                  raw_secret_found: false
                """.formatted(providerType.toUpperCase().replace('_', '-')));
        Path resultJson = target.resolve("result.json");
        Files.writeString(resultJson, """
                {
                  "framework_version": "0.2.2",
                  "suite_id": "%s-REQUIRED-EVIDENCE",
                  "batch_id": "BATCH-001",
                  "run_id": "RUN-001",
                  "test_case_id": "TC-001",
                  "profile": "local",
                  "status": "passed",
                  "provider_results": [
                    {
                      "provider_type": "%s",
                      "provider_id": "%s",
                      "resolved_operation_result": {
                        "operation": "provider_capability_flow",
                        "status": "passed",
                        "outputs": {}
                      }
                    }
                  ],
                  "verify_results": [],
                  "evidence_refs": ["execution-log-001", "batch-summary-001"],
                  "failure": {
                    "code": null,
                    "classification": null,
                    "reason": null,
                    "owner_action": null
                  }
                }
                """.formatted(providerType.toUpperCase().replace('_', '-'), providerType, providerId));
        return resultJson;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private CommandResult execute(String... args) {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exit, stdout.toString(), stderr.toString());
    }

    private String read(Path path) throws IOException {
        return Files.readString(path);
    }

    private String addMultiTestResultFields(String json, int testCount, String testResultsEntries) {
        return addMultiTestResultFields(json, String.valueOf(testCount), testResultsEntries);
    }

    private String addMultiTestResultFields(String json, String testCountJson, String testResultsEntries) {
        return insertBeforeFailure(stripStandardSuiteSummary(json), """
                  "test_count": %s,
                  "test_results": [
                %s
                  ],
                """.formatted(testCountJson, testResultsEntries.indent(4).stripTrailing()));
    }

    private String stripStandardSuiteSummary(String json) {
        return json
                .replaceFirst("(?m)^  \\\"test_count\\\": 1,\\n", "")
                .replaceFirst("(?s)  \\\"test_results\\\": \\[.*?\\n  \\],\\n", "")
                .replaceFirst("(?s)  \\\"provider_summary\\\": \\[.*?\\n  \\],\\n", "");
    }

    private String insertBeforeFailure(String json, String insertedFields) {
        return json.replace("  \"failure\": {", insertedFields + "  \"failure\": {");
    }

    private PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream);
    }

    private record CommandResult(int exit, String stdout, String stderr) {
    }
}
