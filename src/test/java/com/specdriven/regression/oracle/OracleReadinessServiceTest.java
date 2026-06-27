package com.specdriven.regression.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OracleReadinessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsQueryResultOracleForDbAssertions() throws Exception {
        Path testCase = tempDir.resolve("TC-DB-ASSERT.yaml");
        Files.writeString(testCase, """
                test_case_id: TC-DB-ASSERT
                ac_id: AC-DB-ASSERT
                oracles:
                  order_projection:
                    type: query_result
                    provider: relational_db
                    query: count_payment_orders
                    ref: queries/count_payment_orders.sql
                    expected_count: 1
                assertions:
                  - type: db_row_matches
                    oracle: ${oracles.order_projection}
                """);

        OracleReadinessReport report = new OracleReadinessService().check(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void acceptsSchemaAndContractOraclesForResponseAssertions() throws Exception {
        Path testCase = tempDir.resolve("TC-SCHEMA-CONTRACT.yaml");
        Files.writeString(testCase, """
                test_case_id: TC-SCHEMA-CONTRACT
                ac_id: AC-SCHEMA-CONTRACT
                oracles:
                  payment_response_schema:
                    type: schema
                    ref: schemas/payment-response-schema.yaml
                  payment_submit_contract:
                    type: contract
                    ref: contracts/payment-submit-contract.yaml
                assertions:
                  - type: schema_matches
                    oracle: ${oracles.payment_response_schema}
                  - type: contract_matches
                    oracle: ${oracles.payment_submit_contract}
                """);

        OracleReadinessReport report = new OracleReadinessService().check(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void acceptsExecutionFocusedExpectedResultsForVerification() throws Exception {
        Path testCase = tempDir.resolve("TC-EXPECTED-RESULTS.yaml");
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: TC-EXPECTED-RESULTS
                status: approved_for_regression
                revision: 1
                traceability:
                  package_id: RP-001
                  acceptance_criteria_id: AC-EXPECTED-RESULTS
                  source: acceptance_criteria.md#AC-EXPECTED-RESULTS
                targets:
                  RU-api:
                    type: application
                    runner: request_response
                    environment: ci://api
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [api_payload, schema_assertion, contract_assertion]
                setup:
                  fixtures: {}
                execute:
                  - id: submit_payment
                    target: RU-api
                    operation: submit
                expected_results:
                  payment_response_schema:
                    type: schema
                    ref: schemas/payment-response-schema.yaml
                  payment_submit_contract:
                    type: contract
                    ref: contracts/payment-submit-contract.yaml
                verify:
                  - type: schema_matches
                    expected: ${expected_results.payment_response_schema.ref}
                  - type: contract_matches
                    expected: ${expected_results.payment_submit_contract.ref}
                evidence:
                  required: [assertion_result]
                runtime:
                  cleanup_required: false
                """);

        OracleReadinessReport report = new OracleReadinessService().check(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
    }
}
