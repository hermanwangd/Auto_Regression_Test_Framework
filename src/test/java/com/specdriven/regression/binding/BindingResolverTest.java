package com.specdriven.regression.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BindingResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesSupportedM1BindingTypesFromApprovedDslTest() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, approvedTest("db_seed", "expected-results/approved/RP-001-ER-001.yaml"));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.testCaseId()).isEqualTo("RP-001-TC-001");
        assertThat(report.acId()).isEqualTo("RP-001-AC-001");
        assertThat(report.resolvedBindings()).extracting(ResolvedBinding::bindingType)
                .containsExactly("db_seed");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksReservedBindingTypesBeforeProviderExecution() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, approvedTest("existing_state", "expected-results/approved/RP-001-ER-001.yaml"));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::bindingName).contains("orders_seed");
        assertThat(report.gaps()).extracting(BindingGap::bindingType).contains("existing_state");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains("Use supported M1 binding type input_file, dataset, db_seed, api_payload, or message_event; or implement provider support.");
    }

    @Test
    void blocksExpectedResultArtifactOracleWithoutExpectedReference() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, approvedTest("db_seed", ""));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains("expected.ref");
    }

    private String approvedTest(String bindingType, String expectedRef) {
        String expectedBlock = expectedRef.isBlank() ? "expected: {}\n" : "expected:\n  ref: " + expectedRef + "\n";
        return """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                rp_id: RP-001
                ac_id: RP-001-AC-001
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#RP-001-AC-001
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-transform-job
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/RP-001
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution, file_assertion]
                %s\
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/RP-001-ER-001.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/db/orders_seed.yaml
                      bind_as: %s
                steps:
                  - id: run_pipeline
                    action: call_ru
                    target_ru_id: RU-transform-job
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_orders}
                evidence_required:
                  - execution_log
                """.formatted(expectedBlock, bindingType);
    }
}
