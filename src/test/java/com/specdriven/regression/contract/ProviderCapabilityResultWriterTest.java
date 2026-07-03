package com.specdriven.regression.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderCapabilityResultWriterTest {

    @Test
    void buildsValidMultiProviderResultWithoutRootProviderIdentity() {
        List<Map<String, Object>> providerResults = List.of(
                Map.of(
                        "provider_id", "wiremock-payment-api",
                        "provider_type", "wiremock_http_mock",
                        "runtime_mode", "mock"),
                Map.of(
                        "provider_id", "payment-api-client",
                        "provider_type", "rest_client",
                        "runtime_mode", "native"));
        List<Map<String, Object>> testResults = List.of(Map.of(
                "test_case_id", "WIREMOCK-HTTP-REQUEST-TC-001",
                "status", "passed",
                "profile", "local_wiremock_http",
                "provider_ids", List.of("wiremock-payment-api", "payment-api-client"),
                "provider_types", List.of("wiremock_http_mock", "rest_client")));

        Map<String, Object> result = new ProviderCapabilityResultWriter.ResultDocument(
                "v0.2",
                "WIREMOCK-HTTP-REQUEST-SAMPLE-v0.2",
                "BATCH-1",
                "RUN-1",
                "WIREMOCK-HTTP-REQUEST-TC-001",
                1,
                "local_wiremock_http",
                "local_wiremock_http",
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
                Map.of(),
                null,
                null,
                true)
                .toMap();

        assertThat(result)
                .containsKey("provider_summary")
                .doesNotContainKeys("provider_id", "provider_type", "topic", "queue");
        assertThat(result).containsEntry("environment", "local_wiremock_http");
        List<ContractFinding> findings = ResultContractValidator.validate(Path.of("result.json"), result);
        assertThat(findings).isEmpty();
    }

    @Test
    void providerEvidenceRefsExcludeFrameworkAndAssertionEvidence() {
        assertThat(ProviderCapabilityResultWriter.providerEvidenceRefs(List.of(
                        "evidence_index.yaml",
                        "logs/execution.log",
                        "batch/batch.yaml",
                        "provider-evidence/wiremock/request_journal.json",
                        "query-evidence/order_database/query_result.yaml",
                        "event-evidence/nats_event_bus/published_message.yaml",
                        "fixture/setup.yaml",
                        "actual/actual_output.json",
                        "expected/expected_output.ref",
                        "assertions/output_matches_expected_json.yaml",
                        "diffs/output_matches_expected_json.diff",
                        "TC-001/provider-evidence/jdbc/query.yaml",
                        "jdbc-query-001",
                        "execution-log-001",
                        "batch-summary-001",
                        "TC-001/assertions/status.yaml")))
                .containsExactly(
                        "provider-evidence/wiremock/request_journal.json",
                        "query-evidence/order_database/query_result.yaml",
                        "event-evidence/nats_event_bus/published_message.yaml",
                        "fixture/setup.yaml",
                        "actual/actual_output.json",
                        "TC-001/provider-evidence/jdbc/query.yaml",
                        "jdbc-query-001");
    }

    @Test
    void resultDocumentPublishesOnlyProviderProducedProviderEvidenceRefs() {
        List<Map<String, Object>> providerResults = List.of(Map.of(
                "provider_id", "wiremock-payment-api",
                "provider_type", "wiremock_http_mock",
                "runtime_mode", "mock"));
        List<String> allEvidenceRefs = List.of(
                "evidence_index.yaml",
                "provider-evidence/wiremock/request_journal.json",
                "assertions/output_matches_expected_json.yaml",
                "logs/execution.log",
                "batch/batch.yaml");

        Map<String, Object> result = new ProviderCapabilityResultWriter.ResultDocument(
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
                List.of(Map.of(
                        "test_case_id", "WIREMOCK-CAPABILITY-TC-001",
                        "status", "passed",
                        "profile", "local_wiremock")),
                providerResults,
                allEvidenceRefs,
                allEvidenceRefs,
                ProviderCapabilityResultWriter.singleProviderRootFields(providerResults),
                null,
                null,
                true)
                .toMap();

        assertThat(result.get("provider_evidence_refs"))
                .as("provider_evidence_refs must not duplicate framework, assertion, or batch evidence")
                .isEqualTo(List.of("provider-evidence/wiremock/request_journal.json"));
        assertThat(result.get("evidence_refs")).isEqualTo(allEvidenceRefs);
    }

    @Test
    void exposesRootProviderFieldsOnlyForSingleProviderResults() {
        List<Map<String, Object>> providerResults = List.of(Map.of(
                "provider_id", "order-events",
                "provider_type", "kafka",
                "runtime_mode", "native",
                "base_url", "http://localhost:18080",
                "dialect", "oracle",
                "subject", "orders.created",
                "topic", "orders.created"));

        assertThat(ProviderCapabilityResultWriter.singleProviderRootFields(providerResults))
                .containsEntry("provider_id", "order-events")
                .containsEntry("provider_type", "kafka")
                .containsEntry("runtime_mode", "native")
                .containsEntry("base_url", "http://localhost:18080")
                .containsEntry("dialect", "oracle")
                .containsEntry("subject", "orders.created")
                .containsEntry("topic", "orders.created")
                .doesNotContainKey("queue");
        assertThat(ProviderCapabilityResultWriter.providerSummary(providerResults).get(0))
                .containsEntry("base_url", "http://localhost:18080")
                .containsEntry("dialect", "oracle")
                .containsEntry("subject", "orders.created");
    }

    @Test
    void providerSummaryDeduplicatesProviderInstancesAcrossMultiTestResults() {
        List<Map<String, Object>> providerResults = List.of(
                Map.of(
                        "provider_id", "wiremock-payment-api",
                        "provider_type", "wiremock_http_mock",
                        "runtime_mode", "mock",
                        "base_url", "http://localhost:10001",
                        "status", "passed"),
                Map.of(
                        "provider_id", "wiremock-payment-api",
                        "provider_type", "wiremock_http_mock",
                        "runtime_mode", "mock",
                        "base_url", "http://localhost:10002",
                        "status", "failed"),
                Map.of(
                        "provider_id", "payment-api-client",
                        "provider_type", "rest_client",
                        "runtime_mode", "native",
                        "status", "passed"));

        List<Map<String, Object>> summary = ProviderCapabilityResultWriter.providerSummary(providerResults);

        assertThat(summary).hasSize(2);
        assertThat(summary.get(0))
                .containsEntry("provider_id", "wiremock-payment-api")
                .containsEntry("provider_type", "wiremock_http_mock")
                .containsEntry("status", "failed");
        assertThat(summary.get(1))
                .containsEntry("provider_id", "payment-api-client")
                .containsEntry("provider_type", "rest_client")
                .containsEntry("status", "passed");
    }
}
