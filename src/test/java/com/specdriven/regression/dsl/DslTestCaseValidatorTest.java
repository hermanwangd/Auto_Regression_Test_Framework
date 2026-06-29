package com.specdriven.regression.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DslTestCaseValidatorTest {

    @TempDir
    Path tempDir;

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
                .replace("  acceptance_criteria: acceptance_criteria.md#RP-001-AC-001\n", "");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("status", "source_refs");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Use allowed DSL execution status draft_skeleton, draft_executable, active, needs_update, or retired.");
    }

    @Test
    void blocksStatusOnlyExecutionFocusedDocumentWithoutDslVersionAndRequiredSections() {
        DslValidationReport report = new DslTestCaseValidator().validate("""
                status: active
                """);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains(
                        "dsl_version",
                        "test_case_id",
                        "revision",
                        "source_refs",
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
    void blocksV02TraceabilityAndNestedGovernanceFields() {
        String yaml = validExecutionFocusedDsl()
                + """
                traceability:
                  package_id: RP-001
                metadata:
                  reviewers:
                    - name: SA
                      approval_required: true
                """;

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("traceability", "metadata.reviewers[0].approval_required");
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
    void blocksBlankExecuteOperation() {
        String yaml = validExecutionFocusedDsl()
                .replace("operation: run_batch", "operation: \"\"");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("execute[0].operation");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Declare execute operation before provider dispatch.");
    }

    @Test
    void blocksMultipleExecuteStepsUntilMultiStepOrchestrationIsSupported() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: run_batch
                    with: {}
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                """, """
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: run_batch
                    with: {}
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                  - id: run_postcheck
                    target: RU-transform-job
                    operation: run_batch
                    with: {}
                    outputs:
                      actual_output:
                        ref: actual/postcheck.txt
                """);

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("execute");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Use exactly one execute step per M1 test case; split additional operations into separate approved tests in the same batch.");
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
    void blocksVerifyRuleWithoutType() {
        String yaml = validExecutionFocusedDsl()
                .replace("type: file_diff", "type: \"\"");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].type");
        assertThat(report.gaps()).extracting(DslValidationGap::verifyId)
                .contains("verify_output");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Declare verify type.");
    }

    @Test
    void blocksJsonPathVerifyRulesWithoutSelector() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_status
                    type: json_path_equals
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: NORMALIZED
                  - id: verify_error_absent
                    type: json_path_absent
                    actual: ${execute.run_pipeline.outputs.actual_output}
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_status.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].selector", "verify[1].selector");
        assertThat(report.gaps()).extracting(DslValidationGap::verifyId)
                .contains("verify_status", "verify_error_absent");
    }

    @Test
    void blocksNumericToleranceWithoutSelectorOrTolerance() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_risk_score
                    type: numeric_tolerance
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: 0.05
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_risk_score.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].selector", "verify[0].options.tolerance");
        assertThat(report.gaps()).extracting(DslValidationGap::verifyId)
                .contains("verify_risk_score");
    }

    @Test
    void acceptsResponseStatusVerifyWithoutActualWhenProviderMetadataIsUsed() {
        String yaml = validExecutionFocusedDsl()
                .replace("runner: spring_boot_cli", "runner: request_response")
                .replace("operation: run_batch", "operation: call_api")
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_http_status
                    type: response_status_equals
                    expected: 202
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_http_status.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isTrue();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksResponseStatusVerifyWithoutActualWhenNoRequestResponseMetadataSourceExists() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_http_status
                    type: response_status_equals
                    expected: 202
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_http_status.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].actual");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Declare actual plus selector, or execute through a request_response target that supplies provider HTTP status metadata.");
    }

    @Test
    void blocksResponseStatusVerifyWithActualButWithoutSelector() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_http_status
                    type: response_status_equals
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: 202
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_http_status.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].selector");
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
    void throwsUncheckedIoWhenPathCannotBeRead() {
        assertThatThrownBy(() -> new DslTestCaseValidator().validate(tempDir.resolve("missing.yaml")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read DSL test case");
    }

    @Test
    void blocksLegacyV1DslWhenTraceabilityIsMissing() {
        DslValidationReport report = new DslTestCaseValidator().validate("""
                dsl_version: v1
                test_case_id: RP-001-TC-001
                status: active
                revision: 1
                targets:
                  RU-transform-job:
                    type: batch_runner
                    runner: spring_boot_cli
                    environment: ci://pipeline/RP-001
                scenario:
                  type: integration
                setup:
                  fixtures: {}
                execute:
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: run_batch
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                expected_results: {}
                verify:
                  - id: verify_exists
                    type: file_exists
                    actual: ${execute.run_pipeline.outputs.actual_output}
                evidence:
                  required:
                    - ${verify.verify_exists.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("traceability");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Declare traceability.package_id, acceptance_criteria_id, and source.");
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
    void acceptsStateMutatingFixtureWithCleanupActionAndInlineExpectedResult() {
        String yaml = validExecutionFocusedDsl()
                .replace("  fixtures: {}", """
                  fixtures:
                    orders_seed:
                      type: db_seed
                      lifecycle: state_mutating
                      cleanup_action: cleanup_orders
                """)
                .replace("""
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/RP-001-ER-001.yaml
                """, """
                  primary:
                    type: inline_value
                    value: NORMALIZED
                """)
                .replace("expected: ${expected_results.primary.ref}", "expected: NORMALIZED");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isTrue();
    }

    @Test
    void blocksEmptySetupMapAsMissingFixtureDeclaration() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                setup:
                  fixtures: {}
                """, """
                setup: {}
                """);

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("setup.fixtures");
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
    void blocksStateVerificationWithoutQueryRefOrExpectedValue() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_database
                    type: db_row_matches
                    target: RU-transform-job
                    query: {}
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_database.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].query.ref", "verify[0].expected");
        assertThat(report.gaps()).extracting(DslValidationGap::verifyId)
                .contains("verify_database");
    }

    @Test
    void blocksEventVerificationWithoutEventOrExpectedValue() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_event
                    type: event_published
                    target: RU-transform-job
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_event.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].event", "verify[0].expected");
    }

    @Test
    void blocksUnsupportedVerifyTypeAndMalformedExpectedReferences() {
        String yaml = validExecutionFocusedDsl()
                .replace("""
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                """, """
                  - id: verify_unknown
                    type: semantic_similarity
                  - id: verify_missing_expected
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.missing.ref}
                  - id: verify_malformed_expected
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.}
                """)
                .replace("${verify.verify_output.result}", "${verify.verify_missing_expected.result}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].type", "verify[1].expected", "verify[2].expected");
        assertThat(report.gaps()).extracting(DslValidationGap::verifyId)
                .contains("verify_unknown", "verify_missing_expected", "verify_malformed_expected");
    }

    @Test
    void blocksActualReferenceToMissingExpectedResultsEntry() {
        String yaml = validExecutionFocusedDsl()
                .replace(
                        "actual: ${execute.run_pipeline.outputs.actual_output}",
                        "actual: ${expected_results.missing.ref}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].actual");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Reference a declared expected_results entry before assertion evaluation.");
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
    void blocksMalformedExecuteAndVerifyEvidenceReferences() {
        String yaml = validExecutionFocusedDsl()
                .replace("${execute.run_pipeline.outputs.actual_output}", "${execute.run_pipeline}")
                .replace("${verify.verify_output.result}", "${verify.verify_output.status}");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].actual", "evidence.required[0]", "evidence.required[1]");
    }

    @Test
    void blocksMalformedExpressionWithoutClosingBrace() {
        String yaml = validExecutionFocusedDsl()
                .replace(
                        "${execute.run_pipeline.outputs.actual_output}",
                        "${execute.run_pipeline.outputs.actual_output");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("verify[0].actual", "evidence.required[0]");
    }

    @Test
    void blocksLiteralEvidenceReferenceBeforeEvidenceCollection() {
        String yaml = validExecutionFocusedDsl()
                .replace("- ${verify.verify_output.result}", "- logs/execution.log");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("evidence.required[1]");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Reference concrete execute or verify outputs in evidence.required.");
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
    void blocksNonNumericRetryPolicy() {
        String yaml = validExecutionFocusedDsl()
                .replace("max_attempts: 0", "max_attempts: many");

        DslValidationReport report = new DslTestCaseValidator().validate(yaml);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(DslValidationGap::fieldPath)
                .contains("runtime.retry.max_attempts");
        assertThat(report.gaps()).extracting(DslValidationGap::ownerAction)
                .contains("Declare runtime.retry.max_attempts as a non-negative integer.");
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
                        "source_refs",
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
                dsl_version: v0.2
                test_case_id: RP-001-TC-001
                status: active
                revision: 1
                labels:
                  package: RP-001
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#RP-001-AC-001
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
