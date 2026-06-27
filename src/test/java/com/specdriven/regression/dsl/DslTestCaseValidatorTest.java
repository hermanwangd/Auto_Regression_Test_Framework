package com.specdriven.regression.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DslTestCaseValidatorTest {

    @Test
    void acceptsValidExecutionFocusedDsl() {
        DslValidationReport report = new DslTestCaseValidator().validate(validExecutionFocusedDsl());

        assertThat(report.ready()).isTrue();
        assertThat(report.testCaseId()).isEqualTo("RP-001-TC-001");
        assertThat(report.acId()).isEqualTo("RP-001-AC-001");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksMissingCoreIdentityAndUnsupportedStatus() {
        String yaml = validExecutionFocusedDsl()
                .replace("status: active\n", "status: approved\n")
                .replace("  acceptance_criteria_id: RP-001-AC-001\n", "");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("status", "traceability.acceptance_criteria_id");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Use allowed DSL execution status draft_skeleton, draft_executable, active, needs_update, or retired.");
    }

    @Test
    void blocksLegacyOnlyAndGovernanceHeavyFieldsInExecutionFocusedDsl() {
        String yaml = validExecutionFocusedDsl()
                + """
                execution_target:
                  adapter: spring_boot_cli
                approval_status: approved
                waiver:
                  id: W-001
                """;

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("execution_target", "approval_status", "waiver");
    }

    @Test
    void blocksLegacyOperationUnknownTargetAndMissingOutputs() {
        String yaml = validExecutionFocusedDsl()
                .replace("target: RU-transform-job", "target: RU-missing")
                .replace("operation: run_batch", "operation: call_ru")
                .replace("""
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                """, "");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("execute[0].target", "execute[0].operation", "execute[0].outputs");
    }

    @Test
    void blocksVerifyRuleWithoutExpectedOrActualSource() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_output
                    type: file_diff
                """);

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].actual", "verify[0].expected");
        assertThat(report.gaps()).extracting(DslValidationGap::verifyId)
                .contains("verify_output");
    }

    @Test
    void reportsSyntaxGapForInvalidYamlInsteadOfThrowing() {
        DslValidationReport report = new DslTestCaseValidator().validate("""
                dsl_version: v1
                traceability:
                  package_id: RP-001
                    acceptance_criteria_id: RP-001-AC-001
                """);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::section).contains("syntax");
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath).contains("dsl");
    }

    @Test
    void blocksStateMutatingFixtureWithoutCleanupReference() {
        String yaml = validExecutionFocusedDsl()
                .replace("  fixtures: {}", """
                  fixtures:
                    orders_seed:
                      type: database_seed
                      ref: fixtures/db/orders_seed.yaml
                """);

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("setup.fixtures.orders_seed.cleanup_ref");
    }

    @Test
    void blocksVerifyTargetAndExpectedReferencesThatCannotResolve() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_output
                    type: db_record_exists
                    target: missing_database
                    query:
                      ref: queries/order_exists.sql
                    expected:
                      min_rows: 1
                """);

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].target");
    }

    @Test
    void blocksEvidenceAndVerifyReferencesThatDoNotResolveToDeclaredOutputs() {
        String yaml = validExecutionFocusedDsl()
                .replace("${execute.run_pipeline.outputs.actual_output}", "${execute.run_pipeline.outputs.missing_output}")
                .replace("${verify.verify_output.result}", "${verify.missing_verify.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].actual", "evidence.required[0]", "evidence.required[1]");
    }

    @Test
    void blocksUnboundedRetryPolicy() {
        String yaml = validExecutionFocusedDsl()
                .replace("max_attempts: 0", "max_attempts: -1");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("runtime.retry.max_attempts");
    }

    @Test
    void blocksMissingRequiredSectionsAndIdentityFields() {
        DslValidationReport report = new DslTestCaseValidator().validate("""
                dsl_version: v2
                test_case_id: ""
                status: ""
                targets: {}
                """);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains(
                        "dsl_version",
                        "test_case_id",
                        "status",
                        "revision",
                        "traceability",
                        "targets",
                        "scenario",
                        "setup",
                        "execute",
                        "expected_results",
                        "verify",
                        "evidence",
                        "runtime");
    }

    @Test
    void blocksMalformedSectionShapesAndUnsupportedEnums() {
        DslValidationReport report = new DslTestCaseValidator().validate("""
                dsl_version: v1
                test_case_id: RP-001-TC-001
                status: active
                revision: 1
                traceability:
                  package_id: RP-001
                  acceptance_criteria_id: RP-001-AC-001
                  source: acceptance_criteria.md#RP-001-AC-001
                targets:
                  RU-transform-job:
                    runner: spring_boot_cli
                scenario:
                  type: integration
                setup:
                  fixtures: []
                execute:
                  - id: ""
                    target_ru_id: RU-transform-job
                    operation: unsupported_operation
                    outputs: {}
                expected_results:
                  primary:
                    type: golden_file
                verify:
                  - id: verify_exists
                    type: exists
                evidence:
                  required: execution_log
                runtime:
                  timeout: ""
                  retry: {}
                """);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains(
                        "targets.RU-transform-job.type",
                        "targets.RU-transform-job.environment",
                        "setup.fixtures",
                        "execute[0].id",
                        "execute[0].target",
                        "execute[0].target_ru_id",
                        "execute[0].operation",
                        "execute[0].outputs",
                        "expected_results.primary.ref",
                        "verify[0].actual",
                        "evidence.required",
                        "runtime.timeout",
                        "runtime.retry.max_attempts");
    }

    @Test
    void acceptsEventVerificationAndStringRetryPolicy() {
        String yaml = validExecutionFocusedDsl()
                .replace("capabilities: [file_input, batch_execution, file_assertion]",
                        "capabilities: [message_event, event_assertion]")
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_event
                    type: event_published
                    target: RU-transform-job
                    event:
                      topic: order.normalized
                      key: ORD-001
                    expected:
                      match:
                        $.status: NORMALIZED
                """)
                .replace("max_attempts: 0", "max_attempts: \"1\"");
        yaml = yaml.replace("${verify.verify_output.result}", "${verify.verify_event.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isTrue();
    }

    @Test
    void blocksNonMapYamlDocuments() {
        DslValidationReport report = new DslTestCaseValidator().validate("""
                - not
                - a
                - map
                """);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::section).contains("syntax");
    }

    private String validExecutionFocusedDsl() {
        return """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                status: active
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
                  capabilities: [file_input, batch_execution, file_assertion]
                setup:
                  fixtures: {}
                execute:
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: run_batch
                    with: {}
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                expected_results:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/RP-001-ER-001.yaml
                verify:
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                evidence:
                  required:
                    - ${execute.run_pipeline.outputs.actual_output}
                    - ${verify.verify_output.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """;
    }
}
