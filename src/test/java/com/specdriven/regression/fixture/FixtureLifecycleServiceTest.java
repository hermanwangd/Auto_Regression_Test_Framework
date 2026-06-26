package com.specdriven.regression.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureLifecycleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsMutatingFixtureWithCleanupAndCleanupPolicy() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, testCaseWithFixture("""
                fixture:
                  setup:
                    - provider: relational_db
                      action: seed_orders
                      lifecycle: mutates_state
                  cleanup:
                    - provider: relational_db
                      action: cleanup_orders
                policy:
                  cleanup_required: true
                  cleanup_on_failure: true
                """));

        FixtureLifecycleReport report = new FixtureLifecycleService().validate(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.cleanupRequired()).isTrue();
        assertThat(report.fixtureProviders()).containsExactly("relational_db");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksMutatingFixtureWithoutCleanupBeforeExecution() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, testCaseWithFixture("""
                fixture:
                  setup:
                    - provider: relational_db
                      action: seed_orders
                      lifecycle: mutates_state
                policy:
                  cleanup_required: false
                """));

        FixtureLifecycleReport report = new FixtureLifecycleService().validate(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(FixtureLifecycleGap::fieldPath)
                .contains("fixture.cleanup", "policy.cleanup_required");
        assertThat(report.gaps()).extracting(FixtureLifecycleGap::ownerAction)
                .allMatch(action -> action.contains("Declare cleanup"));
    }

    private String testCaseWithFixture(String fixtureBlock) {
        return """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                rp_id: RP-001
                ac_id: RP-001-AC-001
                artifact_status: approved_for_regression
                revision: 1
                execution_target:
                  ru_id: RU-transform-job
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/RP-001
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution]
                expected:
                  ref: expected-results/approved/RP-001-ER-001.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/db/orders_seed.yaml
                      bind_as: db_seed
                %s
                steps:
                  - id: run_pipeline
                    action: call_ru
                    target_ru_id: RU-transform-job
                assertions:
                  - type: file_diff
                    inline_decision_rule: actual file exists
                evidence_required:
                  - execution_log
                  - cleanup_result
                """.formatted(fixtureBlock);
    }
}
