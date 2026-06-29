package com.specdriven.regression.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
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

    @Test
    void acceptsExecutionFocusedSetupFixtureWithCleanupPolicy() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                status: approved_for_regression
                revision: 1
                traceability:
                  package_id: RP-001
                  acceptance_criteria_id: RP-001-AC-001
                  source: acceptance_criteria.md#RP-001-AC-001
                targets:
                  RU-transform-job:
                    type: batch_runner
                    runner: spring_boot_cli
                    environment: ci://pipeline/RP-001
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution]
                setup:
                  fixtures:
                    orders_seed:
                      type: database_seed
                      ref: fixtures/db/orders_seed.yaml
                      provider: relational_db
                      setup_action: seed_orders
                      cleanup_action: cleanup_orders
                      lifecycle: state_mutating
                execute:
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: call_ru
                expected_results: {}
                verify: []
                evidence:
                  required: [execution_log, cleanup_result]
                runtime:
                  cleanup_required: true
                  cleanup_on_failure: true
                """);

        FixtureLifecycleReport report = new FixtureLifecycleService().validate(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.cleanupRequired()).isTrue();
        assertThat(report.fixtureProviders()).containsExactly("relational_db");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void treatsNonMappingDslAsNoFixtureLifecycleWork() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/non-mapping.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, "free-form notes only\n");

        FixtureLifecycleReport report = new FixtureLifecycleService().validate(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.cleanupRequired()).isFalse();
        assertThat(report.fixtureProviders()).isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void ignoresMalformedFixtureSetupRowsWithoutInventingProviders() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-002.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, testCaseWithFixture("""
                fixture:
                  setup:
                    - scalar setup row
                    - action: inspect_without_provider
                      lifecycle: read_only
                    - provider: " "
                      action: inspect_orders
                      lifecycle: read_only
                  cleanup: []
                policy:
                  cleanup_required: false
                """));

        FixtureLifecycleReport report = new FixtureLifecycleService().validate(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.cleanupRequired()).isFalse();
        assertThat(report.fixtureProviders()).isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void ignoresFixtureSetupWhenSetupSectionIsNotAList() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-003.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, testCaseWithFixture("""
                fixture:
                  setup: not-a-list
                  cleanup:
                    - provider: relational_db
                      action: cleanup_orders
                policy:
                  cleanup_required: false
                """));

        FixtureLifecycleReport report = new FixtureLifecycleService().validate(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.fixtureProviders()).isEmpty();
        assertThat(report.cleanupRequired()).isFalse();
    }

    @Test
    void throwsUncheckedIoExceptionWhenDslFileCannotBeRead() {
        Path missingFile = tempDir.resolve("tests/approved/missing.yaml");

        assertThatThrownBy(() -> new FixtureLifecycleService().validate(missingFile))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read DSL test fixture lifecycle");
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
