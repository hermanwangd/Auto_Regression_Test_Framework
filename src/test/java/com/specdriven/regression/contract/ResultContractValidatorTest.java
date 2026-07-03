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
import org.yaml.snakeyaml.Yaml;

class ResultContractValidatorTest {

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
    void checkedInPositiveSampleResultsMatchStandardResultContract() throws IOException {
        for (Path sample : List.of(
                Path.of("samples/contract_baseline/result/sample_result.json"),
                Path.of("samples/golden_e2e/result/expected_result_shape.json"),
                Path.of("samples/provider_capability/result/expected_result_shape.json"),
                Path.of("samples/provider_capability/wiremock/result/expected_result_shape.json"),
                Path.of("samples/provider_capability/jdbc/result/expected_result_shape.json"),
                Path.of("samples/provider_capability/nats/result/expected_result_shape.json"),
                Path.of("samples/evidence_hardening/valid_result.json"))) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadJsonMap(Path path) throws IOException {
        Object loaded = new Yaml().load(Files.readString(path));
        assertThat(loaded)
                .as(path + " must parse as an object")
                .isInstanceOf(Map.class);
        return (Map<String, Object>) loaded;
    }
}
