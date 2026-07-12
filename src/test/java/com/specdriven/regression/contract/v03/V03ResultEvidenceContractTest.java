package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ResultContractValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class V03ResultEvidenceContractTest {

    private static final Path RUNTIME_SCHEMA_ROOT = Path.of("schemas");
    private static final Path DOCUMENTATION_SCHEMA_ROOT = Path.of("docs/02-architecture/contracts");

    @Test
    void publishesByteIdenticalV03ResultAndSuiteSummarySchemas() throws IOException {
        for (String schemaName : List.of("result.v0.3.schema.yaml", "suite_summary.v0.3.schema.yaml")) {
            Path runtimeSchema = RUNTIME_SCHEMA_ROOT.resolve(schemaName);
            Path documentationSchema = DOCUMENTATION_SCHEMA_ROOT.resolve(schemaName);

            assertThat(runtimeSchema).exists().isRegularFile();
            assertThat(documentationSchema).exists().isRegularFile();
            assertThat(Files.readAllBytes(runtimeSchema))
                    .as("%s must be byte-identical in schemas and docs", schemaName)
                    .isEqualTo(Files.readAllBytes(documentationSchema));
        }
    }

    @Test
    void resultV03SchemaPreservesStandardResultAndAddsSuiteAggregationContract() throws IOException {
        Map<?, ?> schema = loadSchema("result.v0.3.schema.yaml");

        assertThat(schema.get("contract_version")).isEqualTo("v0.3");
        assertThat(stringList(schema, "required_fields")).containsExactly(
                "result_contract_version", "framework_version", "dsl_version", "suite_id", "batch_id",
                "run_id", "test_case_id", "test_count", "status", "profile", "environment", "start_time",
                "end_time", "duration_ms", "timestamps", "test_results", "provider_results", "steps",
                "verify_results", "evidence_refs", "failure", "completion_status", "termination_reason",
                "suite_summary_ref");

        Map<?, ?> versionIdentity = nestedMap(schema, "result_model", "version_identity");
        assertThat(versionIdentity.get("result_contract_version")).isEqualTo("v0.3");
        assertThat(versionIdentity.get("independent_from_dsl_version")).isEqualTo(true);

        Map<?, ?> aggregation = nestedMap(schema, "result_model", "aggregation_result");
        assertThat(stringList(aggregation, "test_result_required_identity_fields"))
                .containsExactly("suite_path", "test_result_id", "test_case_id");
        assertThat(stringList(aggregation, "provider_result_required_identity_fields"))
                .containsExactly("suite_path");
        assertThat(stringList(aggregation, "step_required_identity_fields")).containsExactly("suite_path");
        assertThat(stringList(aggregation, "verify_result_required_identity_fields"))
                .containsExactly("suite_path");
        assertThat(stringList(schema, "allowed_result_statuses"))
                .containsExactly("passed", "failed", "blocked", "skipped");
        assertThat(stringList(schema, "allowed_test_statuses"))
                .containsExactly("passed", "failed", "blocked", "skipped");
        assertThat(stringList(schema, "allowed_completion_statuses")).containsExactly("complete", "partial");
        assertThat(stringList(schema, "nullable_fields")).containsExactly("failure", "termination_reason");
    }

    @Test
    void suiteSummaryV03SchemaDefinesCompleteRecursiveSummaryContract() throws IOException {
        Map<?, ?> schema = loadSchema("suite_summary.v0.3.schema.yaml");

        assertThat(schema.get("contract_version")).isEqualTo("v0.3");
        assertThat(stringList(schema, "required_fields")).containsExactly(
                "suite_summary_version", "suite_id", "batch_id", "run_id", "profile", "status",
                "completion_status", "termination_reason", "start_time", "end_time", "duration_ms",
                "generated_at", "framework_version", "dsl_version", "suite_manifest_ref",
                "suite_manifest_digest", "self_summary", "child_aggregate_summary", "total_summary",
                "failure_summary", "evidence_summary", "aggregation_errors", "children");
        assertThat(stringList(schema, "nullable_fields")).containsExactly("termination_reason");
        assertThat(stringList(schema, "allowed_statuses"))
                .containsExactly("passed", "failed", "blocked", "skipped");
        assertThat(stringList(schema, "allowed_completion_statuses")).containsExactly("complete", "partial");
        assertThat(stringList(schema, "allowed_count_completeness")).containsExactly("complete", "partial");
        assertThat(stringList(schema, "allowed_partial_termination_reasons"))
                .containsExactly("timeout", "cancelled", "framework_error", "aggregation_error");
        assertThat(nestedMap(schema, "field_contracts", "suite_manifest_digest").get("pattern"))
                .isEqualTo("^sha256:[0-9a-f]{64}$");
        assertThat(nestedMap(schema, "field_contracts").containsKey("timestamps")).isFalse();
        Map<?, ?> timingContract = nestedMap(schema, "timing_contract");
        assertThat(stringList(timingContract, "timestamp_fields"))
                .containsExactly("start_time", "end_time", "generated_at");
        assertThat(timingContract.get("format")).isEqualTo("utc_rfc3339");
        assertThat(stringList(timingContract, "invariants"))
                .containsExactly("end_time >= start_time", "generated_at >= end_time");

        assertThat(stringList(nestedMap(schema, "summary_shapes", "test_summary"), "required_fields"))
                .containsExactly("count_completeness", "unknown_test_case_count", "test_case_count", "executed_count",
                        "pass_count", "fail_count", "blocked_count", "skipped_count", "pass_rate_percent",
                        "completion_rate_percent");
        assertThat(stringList(nestedMap(schema, "summary_shapes", "child_aggregate_summary"), "required_fields"))
                .containsExactly("count_completeness", "unknown_test_case_count", "child_suite_count",
                        "completed_child_suite_count", "blocked_child_suite_count", "skipped_child_suite_count",
                        "errored_child_suite_count", "test_case_count", "executed_count", "pass_count",
                        "fail_count", "blocked_count", "skipped_count", "pass_rate_percent",
                        "completion_rate_percent");
        assertThat(stringList(nestedMap(schema, "summary_shapes", "failure_summary"), "required_fields"))
                .containsExactly("test_failure_count", "test_blocked_count", "aggregation_error_count",
                        "total_issue_count", "by_category", "failed_test_refs", "failed_child_refs");
        assertThat(stringList(nestedMap(schema, "summary_shapes", "evidence_summary"), "required_fields"))
                .containsExactly("evidence_count", "masking_applied", "evidence_index_ref");
        assertThat(stringList(nestedMap(schema, "summary_shapes", "child_snapshot"), "required_fields"))
                .containsExactly("child_suite_id", "ref", "batch_id", "run_id", "status", "start_time", "end_time",
                        "duration_ms", "summary_ref", "total_summary");
        assertThat(stringList(nestedMap(schema, "summary_shapes", "aggregation_error"), "required_fields"))
                .containsExactly("child_suite_id", "artifact_ref", "failure_code", "owner_action");
        assertThat(stringList(schema, "invariants")).containsExactly(
                "test_case_count == pass_count + fail_count + blocked_count + skipped_count",
                "executed_count == pass_count + fail_count",
                "child_suite_count == completed_child_suite_count + blocked_child_suite_count + skipped_child_suite_count + errored_child_suite_count",
                "total_summary counts == self_summary counts + child_aggregate_summary counts field-by-field",
                "child_aggregate_summary suite and test counts == sums of immediate children total_summary values",
                "errored_child_suite_count > 0 requires child_aggregate_summary.count_completeness partial, unknown_test_case_count true, completion_status partial, and status blocked",
                "test_failure_count == total_summary.fail_count",
                "test_blocked_count == total_summary.blocked_count",
                "aggregation_error_count == aggregation_errors.length",
                "total_issue_count == test_failure_count + test_blocked_count + aggregation_error_count",
                "sum(failure_summary.by_category.values) == total_issue_count",
                "evidence_summary.evidence_count == referenced_evidence_index.entries.length");
        assertThat(nestedMap(schema, "artifact_reference_rules").get("prohibited_segments"))
                .isEqualTo(List.of("..", "~"));
    }

    @Test
    void acceptsV03ProviderResultsWithoutProviderInstanceRefs() {
        Map<String, Object> result = validV03Result();

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings).isEmpty();
    }

    @Test
    void rejectsV03ProviderResultsMissingOperationAndUsingProviderInstanceRefs() {
        Map<String, Object> result = validV03Result();
        @SuppressWarnings("unchecked")
        Map<String, Object> providerResult =
                (Map<String, Object>) ((List<?>) result.get("provider_results")).get(0);
        providerResult.remove("operation");
        providerResult.put("provider_instance_ref", "provider_instances/payment_api.yaml");

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings).extracting(ContractFinding::reason)
                .contains("missing_required_field", "prohibited_provider_instance_ref");
        assertThat(findings).extracting(ContractFinding::fieldPath)
                .contains("provider_results[0].operation", "provider_results[0]");
    }

    @Test
    void rejectsFailedV03ProviderResultWithoutFailureCode() {
        Map<String, Object> result = validV03Result();
        @SuppressWarnings("unchecked")
        Map<String, Object> providerResult =
                (Map<String, Object>) ((List<?>) result.get("provider_results")).get(0);
        providerResult.put("status", "failed");

        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);

        assertThat(findings).extracting(ContractFinding::reason)
                .contains("missing_failure_code");
        assertThat(findings).extracting(ContractFinding::fieldPath)
                .contains("provider_results[0].failure_code");
    }

    private Map<String, Object> validV03Result() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result_contract_version", "v0.3");
        result.put("framework_version", "0.3.1");
        result.put("dsl_version", "v0.3");
        result.put("suite_id", "HTTP-MOCK-REST-CLIENT-v0.3");
        result.put("batch_id", "BATCH-1");
        result.put("run_id", "RUN-1");
        result.put("test_case_id", "HTTP-MOCK-REST-CLIENT-V03-TC-001");
        result.put("test_count", 1);
        result.put("status", "passed");
        result.put("completion_status", "complete");
        result.put("termination_reason", null);
        result.put("suite_summary_ref", "suite_summary.json");
        result.put("profile", "local_v03");
        result.put("environment", "local_v03");
        result.put("start_time", "2026-07-10T00:00:00Z");
        result.put("end_time", "2026-07-10T00:00:01Z");
        result.put("duration_ms", 1000);
        result.put("timestamps", Map.of(
                "started_at", "2026-07-10T00:00:00Z",
                "finished_at", "2026-07-10T00:00:01Z"));
        result.put("test_results", List.of(Map.of(
                "test_case_id", "HTTP-MOCK-REST-CLIENT-V03-TC-001",
                "status", "passed",
                "profile", "local_v03")));
        result.put("provider_summary", List.of(Map.of(
                "target", "payment_api",
                "provider_contract", "rest_client.v0.3",
                "provider_type", "rest_client",
                "runtime_mode", "mock",
                "status", "passed")));
        result.put("provider_results", new ArrayList<>(List.of(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("provider_id", "payment_api"),
                Map.entry("target", "payment_api"),
                Map.entry("provider_contract", "rest_client.v0.3"),
                Map.entry("provider_type", "rest_client"),
                Map.entry("profile", "local_v03"),
                Map.entry("runtime_mode", "mock"),
                Map.entry("operation", "http_request"),
                Map.entry("resolved_operation_result", Map.of("http_status", 200)),
                Map.entry("status", "passed"),
                Map.entry("evidence_refs", List.of("provider-evidence/http/request.json")),
                Map.entry("release_evidence_eligible", false))))));
        result.put("steps", List.of(Map.of("id", "call_payment_api", "status", "passed")));
        result.put("verify_results", List.of());
        result.put("evidence_refs", List.of("provider-evidence/http/request.json"));
        result.put("failure", Map.of(
                "code", "",
                "classification", "",
                "reason", "",
                "owner_action", ""));
        return result;
    }

    private Map<?, ?> loadSchema(String schemaName) throws IOException {
        Object loaded = new Yaml().load(Files.readString(RUNTIME_SCHEMA_ROOT.resolve(schemaName)));
        assertThat(loaded).isInstanceOf(Map.class);
        return (Map<?, ?>) loaded;
    }

    private Map<?, ?> nestedMap(Map<?, ?> root, String... keys) {
        Map<?, ?> current = root;
        for (String key : keys) {
            Object value = current.get(key);
            assertThat(value).as("contract field %s", String.join(".", keys)).isInstanceOf(Map.class);
            current = (Map<?, ?>) value;
        }
        return current;
    }

    private List<String> stringList(Map<?, ?> root, String key) {
        Object value = root.get(key);
        assertThat(value).as("contract field %s", key).isInstanceOf(List.class);
        return ((List<?>) value).stream().map(String::valueOf).toList();
    }
}
