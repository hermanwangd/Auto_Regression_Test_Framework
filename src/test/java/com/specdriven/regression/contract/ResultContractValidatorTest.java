package com.specdriven.regression.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class ResultContractValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsConsistentSingleProviderResultSummary() {
        List<Map<String, Object>> providerResults = List.of(Map.of(
                "provider_type", "kafka",
                "provider_id", "order-events",
                "runtime_mode", "native",
                "topic", "orders.created",
                "status", "passed"));
        List<Map<String, Object>> testResults = List.of(Map.of(
                "test_case_id", "KAFKA-CAPABILITY-TC-001",
                "status", "passed",
                "profile", "local_kafka",
                "provider_id", "order-events",
                "provider_type", "kafka"));
        Map<String, Object> result = new ProviderCapabilityResultWriter.ResultDocument(
                "v0.2",
                "KAFKA-CAPABILITY-SAMPLE-v0.2",
                "BATCH-1",
                "RUN-1",
                "KAFKA-CAPABILITY-TC-001",
                1,
                "local_kafka",
                "local_kafka",
                "passed",
                Instant.parse("2026-07-03T00:00:00Z"),
                Instant.parse("2026-07-03T00:00:01Z"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                testResults,
                providerResults,
                List.of("evidence_index.yaml"),
                List.of("evidence_index.yaml"),
                ProviderCapabilityResultWriter.singleProviderRootFields(providerResults),
                null,
                null,
                true)
                .toMap();

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings).isEmpty();
    }

    @Test
    void rejectsMultiProviderRootProviderIdentityAndMissingProviderSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suite_id", "MIXED-MESSAGING-SAMPLE-v0.2");
        result.put("batch_id", "BATCH-1");
        result.put("run_id", "RUN-1");
        result.put("profile", "local_messaging");
        result.put("test_count", 2);
        result.put("provider_type", "kafka");
        result.put("topic", "orders.created");
        result.put("test_results", List.of(
                Map.of(
                        "test_case_id", "KAFKA-CAPABILITY-TC-001",
                        "status", "passed",
                        "profile", "local_messaging",
                        "provider_id", "order-events",
                        "provider_type", "kafka"),
                Map.of(
                        "test_case_id", "IBM-MQ-CAPABILITY-TC-001",
                        "status", "passed",
                        "profile", "local_messaging",
                        "provider_id", "payment-mq",
                        "provider_type", "ibm_mq")));

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings)
                .extracting(ContractFinding::fieldPath)
                .contains("provider_summary", "provider_type", "topic");
        assertThat(findings)
                .extracting(ContractFinding::reason)
                .contains("missing_required_field", "invalid_provider_summary");
    }

    @Test
    void rejectsFrameworkEvidenceInProviderEvidenceRefs() {
        Map<String, Object> result = validSingleProviderResult();
        result.put("provider_evidence_refs", List.of(
                "provider-evidence/wiremock/request_journal.json",
                "logs/execution.log"));

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings)
                .extracting(ContractFinding::fieldPath)
                .contains("provider_evidence_refs[1]");
        assertThat(findings)
                .extracting(ContractFinding::reason)
                .contains("invalid_provider_evidence_ref");
    }

    @Test
    void requiresV03SummaryFieldsByExplicitResultContractVersion() {
        Map<String, Object> result = validSingleProviderResult();
        result.put("result_contract_version", "v0.3");

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings).extracting(ContractFinding::fieldPath)
                .contains("completion_status", "termination_reason", "suite_summary_ref");
    }

    @Test
    void acceptsNullTerminationReasonForCompleteV03ResultIndependentOfDslVersion() {
        Map<String, Object> result = validV03Result();
        result.put("dsl_version", "v0.2");

        assertThat(ResultContractValidator.validate(Path.of("result.json"), result)).isEmpty();
    }

    @Test
    void rejectsUnsupportedCompletionStatus() {
        Map<String, Object> result = validV03Result();
        result.put("completion_status", "finished");

        assertOwnerActionableFinding(result, "completion_status", "invalid_completion_status");
    }

    @Test
    void rejectsUnsupportedV03ResultStatus() {
        Map<String, Object> result = validV03Result();
        result.put("status", "unknown");

        assertOwnerActionableFinding(result, "status", "invalid_status");
    }

    @Test
    void rejectsTerminationReasonForCompleteResult() {
        Map<String, Object> result = validV03Result();
        result.put("termination_reason", "timeout");

        assertOwnerActionableFinding(result, "termination_reason", "invalid_termination_reason");
    }

    @Test
    void rejectsPartialResultUnlessBlockedWithSupportedTerminationReason() {
        Map<String, Object> wrongStatus = validV03Result();
        wrongStatus.put("completion_status", "partial");
        wrongStatus.put("termination_reason", "timeout");
        assertOwnerActionableFinding(wrongStatus, "status", "invalid_completion_status_tuple");

        Map<String, Object> unsupportedReason = validV03Result();
        unsupportedReason.put("completion_status", "partial");
        unsupportedReason.put("status", "blocked");
        unsupportedReason.put("termination_reason", "crashed");
        assertOwnerActionableFinding(unsupportedReason, "termination_reason", "invalid_termination_reason");

        Map<String, Object> missingReason = validV03Result();
        missingReason.put("completion_status", "partial");
        missingReason.put("status", "blocked");
        missingReason.put("termination_reason", null);
        assertOwnerActionableFinding(missingReason, "termination_reason", "invalid_termination_reason");
    }

    @Test
    void acceptsPartialBlockedResultWithAllowedTerminationReason() {
        Map<String, Object> result = validV03Result();
        result.put("completion_status", "partial");
        result.put("status", "blocked");
        result.put("termination_reason", "timeout");

        assertThat(ResultContractValidator.validate(Path.of("result.json"), result)).isEmpty();
    }

    @Test
    void rejectsUnsafeSuiteSummaryRefForms() {
        Map<String, String> unsafeRefs = Map.of(
                "/tmp/suite_summary.json", "absolute",
                "../suite_summary.json", "parent traversal",
                "summaries/../suite_summary.json", "normalized relative",
                "~/suite_summary.json", "home expansion");

        unsafeRefs.forEach((ref, ownerActionText) -> {
            Map<String, Object> result = validV03Result();
            result.put("suite_summary_ref", ref);

            List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

            assertThat(findings).anySatisfy(finding -> {
                assertThat(finding.fieldPath()).isEqualTo("suite_summary_ref");
                assertThat(finding.reason()).isEqualTo("invalid_suite_summary_ref");
                assertThat(finding.ownerAction()).contains(ownerActionText);
            });
        });
    }

    @Test
    void rejectsExistingSuiteSummaryRefSymlinkEscape() throws Exception {
        Path runDir = tempDir.resolve("run");
        Path resultJson = runDir.resolve("result.json");
        Path outsideSummary = tempDir.resolve("outside/suite_summary.json");
        Files.createDirectories(runDir);
        Files.createDirectories(outsideSummary.getParent());
        Files.writeString(resultJson, "{}\n");
        Files.writeString(outsideSummary, "{}\n");
        Files.createSymbolicLink(runDir.resolve("suite_summary.json"), outsideSummary);
        Map<String, Object> result = validV03Result();

        List<ContractFinding> findings = ResultContractValidator.validate(resultJson, result);

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.fieldPath()).isEqualTo("suite_summary_ref");
            assertThat(finding.reason()).isEqualTo("invalid_suite_summary_ref");
            assertThat(finding.ownerAction()).contains("real path").contains("result directory");
        });
    }

    @Test
    void rejectsUnsupportedResultContractVersionWithOwnerAction() {
        Map<String, Object> result = validSingleProviderResult();
        result.put("result_contract_version", "v9.9");

        assertThat(ResultContractValidator.validate(Path.of("result.json"), result))
                .anySatisfy(finding -> {
                    assertThat(finding.fieldPath()).isEqualTo("result_contract_version");
                    assertThat(finding.reason()).isEqualTo("unsupported_result_contract_version");
                    assertThat(finding.ownerAction()).contains("v0.3");
                });
    }

    @Test
    void v03StillRequiresAllExistingStandardFields() {
        Map<String, Object> result = validSingleProviderResult();
        result.put("result_contract_version", "v0.3");
        result.put("completion_status", "complete");
        result.put("termination_reason", null);
        result.put("suite_summary_ref", "suite_summary.json");
        result.remove("steps");

        assertThat(ResultContractValidator.validate(Path.of("result.json"), result))
                .anySatisfy(finding -> {
                    assertThat(finding.fieldPath()).isEqualTo("steps");
                    assertThat(finding.reason()).isEqualTo("missing_required_field");
                });
    }

    @Test
    void rejectsDuplicateAggregationTestResultIdsAndEmptySuitePath() {
        Map<String, Object> result = validSingleProviderResult();
        result.put("result_contract_version", "v0.3");
        result.put("completion_status", "complete");
        result.put("termination_reason", null);
        result.put("suite_summary_ref", "suite_summary.json");
        result.put("test_count", 3);
        result.put("test_results", List.of(
                Map.of("test_result_id", "child/TC-1", "suite_path", "child", "test_case_id", "TC-1",
                        "status", "passed", "profile", "local_wiremock"),
                Map.of("test_result_id", "child/TC-1", "suite_path", "", "test_case_id", "TC-2",
                        "status", "passed", "profile", "local_wiremock"),
                Map.of("test_result_id", "", "suite_path", "child", "test_case_id", "TC-3",
                        "status", "passed", "profile", "local_wiremock")));

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings).extracting(ContractFinding::fieldPath)
                .contains("test_results[1].test_result_id", "test_results[1].suite_path",
                        "test_results[2].test_result_id");
        assertThat(findings).extracting(ContractFinding::reason)
                .contains("duplicate_test_result_id", "missing_required_field");
    }

    @Test
    void preservesUnversionedV02Compatibility() {
        Map<String, Object> result = validSingleProviderResult();

        assertThat(result).doesNotContainKey("result_contract_version");
        assertThat(ResultContractValidator.validate(Path.of("result.json"), result)).isEmpty();
    }

    @Test
    void allowsSkippedTestResultsOnlyForV03() {
        Map<String, Object> v03Result = validV03Result();
        v03Result.put("status", "skipped");
        v03Result.put("test_results", List.of(Map.of(
                "test_case_id", "WIREMOCK-CAPABILITY-TC-001",
                "status", "skipped",
                "profile", "local_wiremock")));

        assertThat(ResultContractValidator.validate(Path.of("result.json"), v03Result)).isEmpty();

        for (String version : List.of("", "v0.2")) {
            Map<String, Object> legacyResult = validSingleProviderResult();
            if (!version.isEmpty()) {
                legacyResult.put("result_contract_version", version);
            }
            legacyResult.put("test_results", v03Result.get("test_results"));

            assertOwnerActionableFinding(legacyResult, "test_results[0].status", "invalid_status");
        }
    }

    @Test
    void doesNotAllowSkippedProviderStatusForV03() {
        Map<String, Object> result = validV03Result();
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = new LinkedHashMap<>(((List<Map<String, Object>>) result.get("provider_results")).get(0));
        provider.put("status", "skipped");
        result.put("provider_results", List.of(provider));

        assertOwnerActionableFinding(result, "provider_results[0].status", "invalid_status");
    }

    @Test
    void checkedInPositiveSampleResultsMatchStandardResultContract() throws IOException {
        for (Path sample : List.of(
                Path.of("samples/90-compatibility/legacy-v0.2/10-contract-baseline/mixed_wiremock_jdbc_nats/result/sample_result.json"),
                Path.of("samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e/result/expected_result_shape.json"),
                Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/result/expected_result_shape.json"),
                Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/http/wiremock_http_mock/result/expected_result_shape.json"),
                Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/data/jdbc/result/expected_result_shape.json"),
                Path.of("samples/90-compatibility/legacy-v0.2/20-provider-capability-p0/messaging/nats/result/expected_result_shape.json"),
                Path.of("samples/90-compatibility/legacy-v0.2/40-evidence-reporting/evidence_hardening/valid_result.json"))) {
            Map<String, Object> result = loadJsonMap(sample);

            assertThat(ResultContractValidator.validate(sample, result))
                    .as(sample.toString())
                    .isEmpty();
            assertThat(result)
                    .as(sample + " shared result fields")
                    .containsKeys(
                            "provider_summary",
                            "step_results",
                            "evidence_index_ref",
                            "provider_evidence_refs");
            assertThat(result.get("failure"))
                    .as(sample + " failure contract")
                    .isInstanceOfSatisfying(Map.class, failure -> assertThat(failure).containsKey("code"));
        }
    }

    private static Map<String, Object> validSingleProviderResult() {
        List<Map<String, Object>> providerResults = List.of(Map.of(
                "provider_type", "wiremock_http_mock",
                "provider_id", "wiremock-payment-api",
                "runtime_mode", "mock",
                "status", "passed"));
        List<Map<String, Object>> testResults = List.of(Map.of(
                "test_case_id", "WIREMOCK-CAPABILITY-TC-001",
                "status", "passed",
                "profile", "local_wiremock",
                "provider_id", "wiremock-payment-api",
                "provider_type", "wiremock_http_mock"));
        return new ProviderCapabilityResultWriter.ResultDocument(
                "v0.2",
                "WIREMOCK-CAPABILITY-v0.2",
                "BATCH-1",
                "RUN-1",
                "WIREMOCK-CAPABILITY-TC-001",
                1,
                "local_wiremock",
                "local_wiremock",
                "passed",
                Instant.parse("2026-07-03T00:00:00Z"),
                Instant.parse("2026-07-03T00:00:01Z"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                testResults,
                providerResults,
                List.of("provider-evidence/wiremock/request_journal.json"),
                List.of("evidence_index.yaml", "provider-evidence/wiremock/request_journal.json", "logs/execution.log"),
                ProviderCapabilityResultWriter.singleProviderRootFields(providerResults),
                null,
                null,
                true)
                .toMap();
    }

    private static Map<String, Object> validV03Result() {
        Map<String, Object> result = validSingleProviderResult();
        result.put("result_contract_version", "v0.3");
        result.put("completion_status", "complete");
        result.put("termination_reason", null);
        result.put("suite_summary_ref", "suite_summary.json");
        result.put("provider_results", List.of(Map.ofEntries(
                Map.entry("provider_id", "wiremock-payment-api"),
                Map.entry("target", "wiremock-payment-api"),
                Map.entry("provider_contract", "wiremock_http_mock.v0.3"),
                Map.entry("provider_type", "wiremock_http_mock"),
                Map.entry("profile", "local_wiremock"),
                Map.entry("runtime_mode", "mock"),
                Map.entry("operation", "http_request"),
                Map.entry("status", "passed"),
                Map.entry("evidence_refs", List.of()))));
        return result;
    }

    private static void assertOwnerActionableFinding(
            Map<String, Object> result,
            String fieldPath,
            String reason) {
        assertThat(ResultContractValidator.validate(Path.of("result.json"), result))
                .anySatisfy(finding -> {
                    assertThat(finding.fieldPath()).isEqualTo(fieldPath);
                    assertThat(finding.reason()).isEqualTo(reason);
                    assertThat(finding.ownerAction()).isNotBlank();
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadJsonMap(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        assertThat(loaded)
                .as(path + " must parse as an object")
                .isInstanceOf(Map.class);
        return (Map<String, Object>) loaded;
    }
}
