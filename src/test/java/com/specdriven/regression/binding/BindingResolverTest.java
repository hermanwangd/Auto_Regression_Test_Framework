package com.specdriven.regression.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
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

    @Test
    void resolvesExecutionFocusedDslBindingsFromSetupAndExecuteSections() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest());

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.testCaseId()).isEqualTo("RP-001-TC-001");
        assertThat(report.acId()).isEqualTo("RP-001-AC-001");
        assertThat(report.resolvedBindings()).extracting(ResolvedBinding::bindingName)
                .containsExactly("orders_seed", "api_payload");
        assertThat(report.resolvedBindings()).extracting(ResolvedBinding::bindingType)
                .containsExactly("db_seed", "api_payload");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void resolvesV02ParameterSetReferenceAndBindNamespaceBeforeProviderExecution() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/RP-001-TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders_regression_cases.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                parameter_set_id: orders_regression_cases
                status: approved_for_regression
                cases:
                  - case_id: baseline
                    values:
                      orders_seed_ref: fixtures/db/orders_seed_baseline.yaml
                  - case_id: boundary
                    values:
                      orders_seed_ref: fixtures/db/orders_seed_boundary.yaml
                """);
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  ref: parameter-sets/orders_regression_cases.yaml
                  bind_as: orders_case
                """, "${param.orders_case.orders_seed_ref}"));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.resolvedBindings()).extracting(ResolvedBinding::bindingName)
                .containsExactly("orders_seed", "api_payload");
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void blocksMalformedV02ParameterSetBeforeProviderExecution() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/RP-001-TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders_regression_cases.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                parameter_set_id: orders_regression_cases
                status: approved_for_regression
                cases:
                  - case_id: baseline
                    values:
                      orders_seed_ref: fixtures/db/orders_seed_baseline.yaml
                  - case_id: baseline
                    values:
                      unused_ref: fixtures/db/orders_seed_unused.yaml
                """);
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  ref: parameter-sets/orders_regression_cases.yaml
                  bind_as: orders_case
                """, "${param.orders_case.orders_seed_ref}"));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains(
                        "parameters.ref.cases[1].case_id",
                        "parameters.ref.cases[1].values.orders_seed_ref");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains(
                        "Use a unique case_id for each reviewed parameter case.",
                        "Declare a value for parameter reference `${param.orders_case.orders_seed_ref}`.");
    }

    @Test
    void blocksV02ParameterReferencesOutsideDeclaredBindNamespace() throws Exception {
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path testCase = packageRoot.resolve("tests/approved/RP-001-TC-001.yaml");
        Path parameterSet = packageRoot.resolve("parameter-sets/orders_regression_cases.yaml");
        Files.createDirectories(testCase.getParent());
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                parameter_set_id: orders_regression_cases
                status: approved_for_regression
                cases:
                  - case_id: baseline
                    values:
                      orders_seed_ref: fixtures/db/orders_seed_baseline.yaml
                """);
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  ref: parameter-sets/orders_regression_cases.yaml
                  bind_as: orders_case
                """, "${param.wrong_namespace.orders_seed_ref}"));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains("parameters.bind_as");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains("Use declared parameter namespace `orders_case` for `${param.wrong_namespace.orders_seed_ref}`.");
    }

    @Test
    void blocksUnsupportedParameterStrategyBeforeProviderExecution() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  strategy: combinatorial
                  cases:
                    - case_id: baseline
                      values:
                        orders_seed_ref: fixtures/db/orders_seed.yaml
                """));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains("parameters.strategy");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains("Use M1-supported parameter strategy explicit_cases.");
    }

    @Test
    void blocksMalformedExplicitParameterCasesBeforeProviderExecution() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  strategy: explicit_cases
                  cases:
                    - case_id: baseline
                      values:
                        orders_seed_ref: fixtures/db/orders_seed.yaml
                    - case_id: baseline
                      values:
                        orders_seed_ref: fixtures/db/orders_seed_boundary.yaml
                    - values:
                        orders_seed_ref: fixtures/db/orders_seed_missing_id.yaml
                    - case_id: missing_ref
                      values:
                        unused_ref: fixtures/db/orders_seed_unused.yaml
                """));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains(
                        "parameters.cases[1].case_id",
                        "parameters.cases[2].case_id",
                        "parameters.cases[3].values.orders_seed_ref");
    }

    @Test
    void blocksExecutionFocusedFixtureWithoutBindingTypeAtSourceFieldPath() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest().replace("type: database_seed", "type: "));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains("setup.fixtures.orders_seed.type");
        assertThat(report.gaps()).extracting(BindingGap::bindingName)
                .contains("orders_seed");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains("Declare binding type for package input `orders_seed`.");
    }

    @Test
    void blocksEmptyExplicitParameterCaseListBeforeExpansion() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  strategy: explicit_cases
                  cases: []
                """));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .containsExactly("parameters.cases");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains("Declare at least one explicit parameter case.");
    }

    @Test
    void blocksNonMapParameterCaseAndEmptyValuesBeforeExpansion() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  strategy: explicit_cases
                  cases:
                    - malformed-case
                    - case_id: empty_values
                      values: {}
                """));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains(
                        "parameters.cases[0]",
                        "parameters.cases[1].values");
        assertThat(report.gaps()).extracting(BindingGap::ownerAction)
                .contains(
                        "Declare each parameter case as a map with case_id and values.",
                        "Declare non-empty values for each explicit parameter case.");
    }

    @Test
    void blocksScalarExplicitParameterCasesBeforeExpansion() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  strategy: explicit_cases
                  cases: malformed-cases
                """));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .containsExactly("parameters.cases");
    }

    @Test
    void blocksScalarExplicitParameterValuesAndBlankReferencedValuesBeforeExpansion() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, executionFocusedTest("""
                parameters:
                  strategy: explicit_cases
                  cases:
                    - case_id: scalar_values
                      values: malformed-values
                    - case_id: blank_ref
                      values:
                        orders_seed_ref: " "
                """));

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .contains(
                        "parameters.cases[0].values",
                        "parameters.cases[1].values.orders_seed_ref");
    }

    @Test
    void reportsPackageInputFieldPathWhenSetupAndExecuteDoNotOwnBinding() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                rp_id: RP-001
                ac_id: RP-001-AC-001
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#RP-001-AC-001
                expected:
                  ref: expected-results/approved/RP-001-ER-001.yaml
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/RP-001-ER-001.yaml
                package_inputs:
                  inputs:
                    audit_payload:
                      ref: fixtures/audit.json
                      bind_as: ""
                setup: {}
                execute:
                  - malformed-step
                  - id: no_with
                  - id: different_binding
                    with:
                      api_payload:
                        type: api_payload
                steps:
                  - id: run_pipeline
                    action: call_ru
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_orders}
                evidence_required:
                  - execution_log
                """);

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .containsExactly("package_inputs.inputs.audit_payload.bind_as");
    }

    @Test
    void ignoresMalformedPackageInputEntriesAndBlocksMissingExpectedArtifactReference() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/RP-001-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                rp_id: RP-001
                ac_id: RP-001-AC-001
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#RP-001-AC-001
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                package_inputs:
                  inputs:
                    orders_seed: malformed-input-entry
                steps:
                  - id: run_pipeline
                    action: call_ru
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_orders}
                evidence_required:
                  - execution_log
                """);

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isFalse();
        assertThat(report.resolvedBindings()).isEmpty();
        assertThat(report.gaps()).extracting(BindingGap::fieldPath)
                .containsExactly("expected.ref");
    }

    @Test
    void treatsNonMappingDslAsEmptyDocumentWithExpectedResultReadinessGap() throws Exception {
        Path testCase = tempDir.resolve("tests/approved/not-a-map.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, "[]\n");

        BindingResolutionReport report = new BindingResolver().resolve(testCase);

        assertThat(report.ready()).isTrue();
        assertThat(report.testCaseId()).isBlank();
        assertThat(report.acId()).isBlank();
        assertThat(report.resolvedBindings()).isEmpty();
        assertThat(report.gaps()).isEmpty();
    }

    @Test
    void wrapsUnreadableDslTestCaseAsUncheckedIOException() {
        Path missing = tempDir.resolve("tests/approved/missing-test-case.yaml");

        assertThatThrownBy(() -> new BindingResolver().resolve(missing))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read DSL test case");
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
                  provider: spring_boot_cli
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

    private String executionFocusedTest() {
        return executionFocusedTest("");
    }

    private String executionFocusedTest(String parametersBlock) {
        String fixtureRef = parametersBlock.isBlank()
                ? "fixtures/db/orders_seed.yaml"
                : "${parameters.orders_seed_ref}";
        return executionFocusedTest(parametersBlock, fixtureRef);
    }

    private String executionFocusedTest(String parametersBlock, String fixtureRef) {
        return """
                dsl_version: v1
                test_case_id: RP-001-TC-001
                status: approved_for_regression
                revision: 1
                traceability:
                  package_id: RP-001
                  acceptance_criteria_id: RP-001-AC-001
                  source: acceptance_criteria.md#RP-001-AC-001
                targets:
                  RU-api:
                    type: application
                    runner: request_response
                    environment: ci://api
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, api_payload, response_assertion]
                %s\
                setup:
                  fixtures:
                    orders_seed:
                      type: database_seed
                      ref: %s
                      lifecycle: state_mutating
                execute:
                  - id: submit_payment
                    target: RU-api
                    operation: submit
                    with:
                      api_payload:
                        type: api_payload
                        ref: fixtures/request.json
                expected_results:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/RP-001-ER-001.yaml
                verify:
                  - type: file_diff
                    actual: ${execute.submit_payment.outputs.actual_response}
                    expected: ${expected_results.primary.ref}
                evidence:
                  required: [execution_log, assertion_result]
                runtime:
                  cleanup_required: true
                  destructive_actions_allowed: false
                """.formatted(parametersBlock, fixtureRef);
    }
}
