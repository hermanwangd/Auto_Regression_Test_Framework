package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegressionCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void initProductRepoAndCheckReadinessReturnSuccessOutput() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int initExit = command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(output), print(new ByteArrayOutputStream()));
        int checkExit = command.execute(new String[] {"check-readiness", "--root", tempDir.toString(), "--format", "yaml"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(initExit).isZero();
        assertThat(checkExit).isZero();
        assertThat(output.toString()).contains("status: pass");
        assertThat(output.toString()).contains("next_required_step:");
    }

    @Test
    void checkReadinessWritesAgentReadableReportWhenRpIdIsProvided() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "RP-001", "--format", "yaml"},
                print(output), print(new ByteArrayOutputStream()));

        Path report = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/evidence/readiness/readiness.yaml");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("report_path: docs/08-release/release-packages/RP-001/evidence/readiness/readiness.yaml");
        assertThat(Files.readString(report)).contains("status: pass");
        assertThat(Files.readString(report)).contains("ready: true");
        assertThat(Files.readString(report)).contains("next_required_step:");
        assertThat(Files.readString(report)).contains("rp_scope_invented: false");
        assertThat(Files.readString(report)).contains("rp_ru_membership_invented: false");
    }

    @Test
    void checkReadinessReturnsFailureWhenProductRepoIsIncomplete() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"check-readiness", "--root", tempDir.toString(), "--format", "yaml"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("status: fail");
        assertThat(output.toString()).contains("owner_action:");
    }

    @Test
    void initRpAndCheckRpReturnCompletenessOutput() {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int initExit = command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(output), print(new ByteArrayOutputStream()));
        int checkExit = command.execute(new String[] {"check-rp", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(initExit).isZero();
        assertThat(checkExit).isZero();
        assertThat(Files.exists(tempDir.resolve("docs/08-release/release-packages/RP-001/package.yaml"))).isTrue();
        assertThat(output.toString()).contains("status: pass");
    }

    @Test
    void checkRpReturnsFailureWithGapsWhenRequiredArtifactsAreMissing() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"check-rp", "--root", tempDir.toString(), "--rp-id", "RP-404"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("status: fail");
        assertThat(output.toString()).contains("package.yaml");
        assertThat(output.toString()).contains("owner_action:");
    }

    @Test
    void checkRpStrictSchemaBlocksIncompleteMappingBeforeExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        Files.writeString(tempDir.resolve("docs/08-release/release-packages/RP-001/rp_ru_mapping.yaml"), """
                rp_id: RP-001
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    execution_mode: ci_ephemeral
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--strict-schema"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("mapping_gaps:");
        assertThat(output.toString()).contains("release_units[0].unit_type");
        assertThat(output.toString()).contains("owner_action:");
    }

    @Test
    void checkRpIncludeAcReadinessReportsAmbiguousAcWithoutInventingBehavior() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        Path acFile = tempDir.resolve("docs/08-release/release-packages/RP-001/acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: RP-001-AC-001
                    rp_id: RP-001
                    title: Missing behavior
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    expected_output: expected/output/orders.csv
                    status: ready_for_generation
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--include-ac-readiness"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("ac_readiness:");
        assertThat(output.toString()).contains("RP-001-AC-001");
        assertThat(output.toString()).contains("not_ready_for_generation");
        assertThat(output.toString()).contains("Clarify owner-authored AC");
    }

    @Test
    void generateTestsWritesDraftSkeletonWhenExecutionContextIsIncomplete() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        Path acFile = tempDir.resolve("docs/08-release/release-packages/RP-001/acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: RP-001-AC-001
                    rp_id: RP-001
                    title: Valid input produces output
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: actual output matches approved expected output
                    status: ready_for_generation
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "generate-tests", "--root", tempDir.toString(), "--rp-id", "RP-001", "--mode", "draft"},
                print(output), print(new ByteArrayOutputStream()));

        Path draft = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/draft/RP-001-TC-001-draft_test_skeleton.yaml");
        assertThat(exit).isZero();
        assertThat(Files.exists(draft)).isTrue();
        assertThat(Files.readString(draft)).contains("artifact_status: draft_test_skeleton");
        assertThat(output.toString()).contains("generated_artifact_type: draft_test_skeleton");
    }

    @Test
    void draftExpectedResultsWritesReviewableDraftArtifact() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "draft-expected-results", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path draft = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/expected-results/draft/RP-001-ER-001.yaml");
        assertThat(exit).isZero();
        assertThat(Files.exists(draft)).isTrue();
        assertThat(Files.readString(draft)).contains("status: draft");
        assertThat(Files.readString(draft)).contains("acceptance_criteria.md#RP-001-AC-001");
        assertThat(output.toString()).contains("expected_result_status: draft");
    }

    @Test
    void checkRpIncludeExpectedResultsBlocksDraftTruthSource() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        command.execute(new String[] {
                "draft-expected-results", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--include-expected-results"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("expected_result_eligibility:");
        assertThat(output.toString()).contains("eligible: false");
        assertThat(output.toString()).contains("Approve expected result before using it as regression truth.");
    }

    @Test
    void runDryRunBlocksUnsupportedBindingBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "api_payload");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("binding_gaps:");
        assertThat(output.toString()).contains("api_payload");
        assertThat(output.toString()).contains("adapter_execution_started: false");
    }

    @Test
    void runWritesBlockedEvidenceWhenPreflightFailsBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "api_payload");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(Files.exists(runDir.resolve("run.yaml"))).isTrue();
        assertThat(Files.exists(runDir.resolve("failure_details.yaml"))).isTrue();
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        String failureDetails = Files.readString(runDir.resolve("failure_details.yaml"));
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(runEvidence).contains("rp_id: RP-001");
        assertThat(runEvidence).contains("test_case_id: RP-001-TC-001");
        assertThat(runEvidence).contains("ac_id: RP-001-AC-001");
        assertThat(runEvidence).contains("status: blocked");
        assertThat(runEvidence).contains("adapter_execution_started: false");
        assertThat(runEvidence).contains("execution_mode: ci_ephemeral");
        assertThat(runEvidence).contains("environment_ref: ci://pipeline/RP-001");
        assertThat(failureDetails).contains("package_inputs.inputs.orders_seed.bind_as");
        assertThat(failureDetails).contains("api_payload");
    }

    @Test
    void runDryRunBlocksMissingProviderContractBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMappingWithoutProviderContracts("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("provider_contracts.adapters.spring_boot_cli");
        assertThat(output.toString()).contains("provider_contracts.bindings.db_seed");
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).doesNotContain("run_status: dry_run_ready");
    }

    @Test
    void runDryRunBlocksMutatingFixtureWithoutCleanupBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCaseWithUnsafeFixture("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("fixture_gaps:");
        assertThat(output.toString()).contains("ap: Fixture and State Manager");
        assertThat(output.toString()).contains("fixture.cleanup");
        assertThat(output.toString()).contains("policy.cleanup_required");
        assertThat(output.toString()).contains("adapter_execution_started: false");
    }

    @Test
    void runDryRunBlocksUnsupportedOracleBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCaseWithOracleType("RP-001", "schema");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("oracle_gaps:");
        assertThat(output.toString()).contains("ap: Oracle and Assertion Engine");
        assertThat(output.toString()).contains("oracles.normalized_orders.type");
        assertThat(output.toString()).contains("schema");
        assertThat(output.toString()).contains("adapter_execution_started: false");
    }

    @Test
    void runExecutesApprovedTestThroughAdapterAndWritesEvidence() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("adapter_execution_started: true");
        assertThat(output.toString()).contains("run_status: passed");
        assertThat(output.toString()).contains("exit_code: 0");
        assertThat(Files.readString(runDir.resolve("logs/stdout.log"))).contains("adapter-ok");
        assertThat(Files.readString(runDir.resolve("logs/stderr.log"))).contains("adapter-warn");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("status: passed");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("rp_id: RP-001");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("execution_mode: ci_ephemeral");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("environment_ref: ci://pipeline/RP-001");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("ru_refs:");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("exit_code: 0");
    }

    @Test
    void runWritesCleanupEvidenceForMutatingFixtureWithCleanupPolicy() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMappingWithFixtureProvider("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCaseWithCleanupFixture("RP-001");

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(exit).isZero();
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("cleanup_result: cleanup.yaml");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml"))).contains("status: passed");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml"))).contains("provider: relational_db");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml"))).contains("action: cleanup_orders");
    }

    @Test
    void runFailsWhenFileDiffAssertionDoesNotMatchExpectedResultArtifact() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001", "actual-value");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "expected-value\n");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        Path assertions = runDir.resolve("assertions.yaml");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: failed");
        assertThat(Files.exists(assertions)).isTrue();
        String assertionEvidence = Files.readString(assertions);
        assertThat(assertionEvidence).contains("status: failed");
        assertThat(assertionEvidence).contains("test_case_id: RP-001-TC-001");
        assertThat(assertionEvidence).contains("ac_id: RP-001-AC-001");
        assertThat(assertionEvidence).contains("oracle: ${oracles.normalized_orders}");
        assertThat(assertionEvidence).contains("expected_ref: expected/output/orders.csv");
        assertThat(assertionEvidence).contains("actual_ref: actual/output.txt");
        assertThat(assertionEvidence).contains("decision_rule: file_diff");
        assertThat(assertionEvidence).contains("diff_summary:");
        assertThat(Files.readString(runDir.resolve("run.yaml"))).contains("status: failed");
    }

    @Test
    void reportGeneratesCoverageTraceabilityEvidencePackageForPassedRun() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--run-id", "RUN-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/RUN-001");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("report_status: review_ready");
        assertThat(output.toString()).contains("coverage_percent: 100.0");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("covered: 1");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("total_automatable: 1");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RP-001-AC-001");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RP-001-TC-001");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RUN-001");
        assertThat(Files.exists(reviewDir.resolve("evidence_index.md"))).isTrue();
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("unresolved_failures: 0");
        assertThat(Files.readString(reviewDir.resolve("release_review_summary.yaml"))).contains("review_ready: true");
    }

    @Test
    void reportWritesNotReadyEvidenceWhenRunEvidenceIsMissing() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--run-id", "RUN-404"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/RUN-404");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("report_status: not_review_ready");
        assertThat(output.toString()).contains("coverage_percent: 0.0");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("review_ready: false");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("unresolved_failures: 1");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("missing_evidence");
        assertThat(Files.readString(reviewDir.resolve("release_review_summary.yaml"))).contains("review_ready: false");
    }

    @Test
    void reportIncludesAssertionFailureDetailsForFailedRun() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001", "actual-value");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "expected-value\n");
        writeApprovedTestCase("RP-001", "db_seed");
        int runExit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int reportExit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--run-id", "RUN-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/RUN-001");
        String failureSummary = Files.readString(reviewDir.resolve("failure_summary.yaml"));
        assertThat(runExit).isEqualTo(1);
        assertThat(reportExit).isEqualTo(1);
        assertThat(output.toString()).contains("report_status: not_review_ready");
        assertThat(failureSummary).contains("run_status: failed");
        assertThat(failureSummary).contains("assertion_status: failed");
        assertThat(failureSummary).contains("expected_ref: expected/output/orders.csv");
        assertThat(failureSummary).contains("actual_ref: actual/output.txt");
        assertThat(failureSummary).contains("decision_rule: file_diff");
        assertThat(failureSummary).contains("diff_summary:");
    }

    @Test
    void reportDoesNotExcludeManualOnlyAcWithoutApprovalRecord() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeAcceptanceCriteriaWithUnapprovedManualOnly("RP-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--run-id", "RUN-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("report_status: not_review_ready");
        assertThat(output.toString()).contains("coverage_percent: 50.0");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("total_automatable: 2");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("unapproved_exclusion: RP-001-AC-002");
        assertThat(Files.readString(reviewDir.resolve("release_review_summary.yaml"))).contains("review_ready: false");
    }

    @Test
    void reportKeepsPartialAcInCoverageDenominatorUntilSplitOrReclassified() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeAcceptanceCriteriaWithPartialCoverage("RP-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--run-id", "RUN-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("coverage_percent: 50.0");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("total_automatable: 2");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("uncovered_ac: RP-001-AC-002");
        assertThat(Files.readString(reviewDir.resolve("release_review_summary.yaml"))).contains("review_ready: false");
    }

    @Test
    void pilotRpWorkflowProducesReviewReadyEvidencePackageAboveCoverageTarget() throws Exception {
        String rpId = "RP-AR-M1-data-pipeline";
        String acId = rpId + "-AC-001";
        RegressionCommand command = command();
        int initProductExit = command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        int initRpExit = command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeCompletePackageYaml(rpId);
        writeReadyAcceptanceCriteria(rpId, acId);
        writeExecutableCiMapping(rpId);
        writeApprovedExpectedResult(rpId, acId);
        writeApprovedTestCase(rpId, "db_seed");

        int readinessExit = command.execute(new String[] {"check-readiness", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        int checkRpExit = command.execute(new String[] {
                "check-rp", "--root", tempDir.toString(), "--rp-id", rpId,
                "--strict-schema", "--include-ac-readiness", "--include-expected-results"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream generationOutput = new ByteArrayOutputStream();
        int generateExit = command.execute(new String[] {
                "generate-tests", "--root", tempDir.toString(), "--rp-id", rpId, "--mode", "draft"},
                print(generationOutput), print(new ByteArrayOutputStream()));
        int runExit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream reportOutput = new ByteArrayOutputStream();

        int reportExit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", rpId, "--run-id", "RUN-001"},
                print(reportOutput), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/" + rpId);
        Path reviewDir = packageRoot.resolve("evidence/review/RUN-001");
        String evidenceIndex = Files.readString(reviewDir.resolve("evidence_index.md"));
        assertThat(initProductExit).isZero();
        assertThat(initRpExit).isZero();
        assertThat(readinessExit).isZero();
        assertThat(checkRpExit).isZero();
        assertThat(generateExit).isZero();
        assertThat(generationOutput.toString()).contains("generated_artifact_type: update_proposal");
        assertThat(runExit).isZero();
        assertThat(reportExit).isZero();
        assertThat(reportOutput.toString()).contains("coverage_percent: 100.0");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("coverage_percent: 100.0");
        assertThat(Files.readString(reviewDir.resolve("release_review_summary.yaml"))).contains("review_ready: true");
        assertThat(evidenceIndex).contains(rpId);
        assertThat(evidenceIndex).contains(acId);
        assertThat(evidenceIndex).contains(rpId + "-TC-001");
        assertThat(evidenceIndex).contains("RUN-001");
        assertThat(evidenceIndex).contains("evidence/runs/RUN-001");
    }

    private RegressionCommand command() {
        return new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
    }

    private PrintStream print(ByteArrayOutputStream output) {
        return new PrintStream(output);
    }

    private void writeReadyAcceptanceCriteria(String rpId, String acId) throws Exception {
        Path acFile = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: %s
                    rp_id: %s
                    title: Valid input produces output
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: actual output matches approved expected output
                    status: ready_for_generation
                """.formatted(acId, rpId));
    }

    private void writeCompletePackageYaml(String rpId) throws Exception {
        Path packageYaml = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/package.yaml");
        Files.writeString(packageYaml, """
                product_id: PROD-AR
                rp_id: %s
                name: Auto Regression Pilot Data Pipeline
                owner: product_developer
                target_release: M1
                package_type: data_pipeline
                lifecycle_status: ready_for_regression
                default_execution_mode: ci_ephemeral
                artifact_paths:
                  feature_spec: rp_feature_spec.md
                  acceptance_criteria: acceptance_criteria.md
                  ru_mapping: rp_ru_mapping.yaml
                  tests: tests
                  expected_results: expected-results
                  traceability: traceability.md
                  evidence_index: evidence_index.md
                """.formatted(rpId));
    }

    private void writeAcceptanceCriteriaWithUnapprovedManualOnly(String rpId) throws Exception {
        Path acFile = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: %s-AC-001
                    rp_id: %s
                    title: Valid input produces output
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: actual output matches approved expected output
                    status: ready_for_generation
                  - ac_id: %s-AC-002
                    rp_id: %s
                    title: Manual review without approval
                    owner: product_owner
                    classification: manual_only
                    input: manual-review
                    behavior: human reviews release notes
                    expected_output: approval record
                    pass_fail_rule: approved manual review exists
                    status: ready_for_generation
                """.formatted(rpId, rpId, rpId, rpId));
    }

    private void writeAcceptanceCriteriaWithPartialCoverage(String rpId) throws Exception {
        Path acFile = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/acceptance_criteria.md");
        Files.writeString(acFile, """
                acceptance_criteria:
                  - ac_id: %s-AC-001
                    rp_id: %s
                    title: Valid input produces output
                    owner: product_owner
                    classification: automatable
                    input: fixture/input/orders.csv
                    behavior: transform valid orders
                    expected_output: expected/output/orders.csv
                    pass_fail_rule: actual output matches approved expected output
                    status: ready_for_generation
                  - ac_id: %s-AC-002
                    rp_id: %s
                    title: Boundary records are partially specified
                    owner: product_owner
                    classification: partial
                    input: fixture/input/boundary-orders.csv
                    behavior: transform boundary orders
                    expected_output: expected/output/boundary-orders.csv
                    pass_fail_rule: partial coverage until split into smaller AC
                    status: ready_for_generation
                """.formatted(rpId, rpId, rpId, rpId));
    }

    private void writeCompleteCiMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          command: java -jar ${repo}/target/release-unit.jar
                      bindings:
                        db_seed:
                          provider: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId));
    }

    private void writeCompleteCiMappingWithoutProviderContracts(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s
                    adapter: spring_boot_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId));
    }

    private void writeExecutableCiMapping(String rpId) throws Exception {
        writeExecutableCiMapping(rpId, "adapter-ok");
    }

    private void writeExecutableCiMapping(String rpId, String stdoutValue) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          command: /bin/sh -c 'echo %s; echo adapter-warn >&2'
                          working_directory: .
                          timeout_seconds: 10
                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      bindings:
                        db_seed:
                          provider: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId, stdoutValue));
    }

    private void writeExecutableCiMappingWithFixtureProvider(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-transform-job
                    repo: /repo/transform
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          command: /bin/sh -c 'echo adapter-ok; echo adapter-warn >&2'
                          working_directory: .
                          timeout_seconds: 10
                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      bindings:
                        db_seed:
                          provider: file_fixture
                          materialize_as: input_file
                      fixtures:
                        relational_db:
                          setup_action: seed_orders
                          cleanup_action: cleanup_orders
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, cleanup_result]
                    dependencies: []
                """.formatted(rpId, rpId));
    }

    private void writeApprovedExpectedResult(String rpId, String acId) throws Exception {
        writeApprovedExpectedResult(rpId, acId, "adapter-ok\n");
    }

    private void writeApprovedExpectedResult(String rpId, String acId, String expectedOutput) throws Exception {
        String erId = acId.replace("-AC-", "-ER-");
        Path expectedOutputPath = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/expected/output/orders.csv");
        Files.createDirectories(expectedOutputPath.getParent());
        Files.writeString(expectedOutputPath, expectedOutput);
        Path expected = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/expected-results/approved/" + erId + ".yaml");
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, """
                expected_result_id: %s
                rp_id: %s
                ac_id: %s
                status: approved_for_regression
                source_refs:
                  - acceptance_criteria.md#%s
                input_refs:
                  - fixture/input/orders.csv
                expected_outputs:
                  output_ref: expected/output/orders.csv
                assumptions: []
                unresolved_gaps: []
                approved_by: product_developer
                approved_at: 2026-06-27T00:00:00+08:00
                approval_ref: docs/10-change-control/13_issue_template.md#APP-001
                blocked_reason: null
                """.formatted(erId, rpId, acId, acId));
    }

    private void writeApprovedTestCase(String rpId, String bindingType) throws Exception {
        writeApprovedTestCaseWithOracleType(rpId, bindingType, "expected_result_artifact");
    }

    private void writeApprovedTestCaseWithOracleType(String rpId, String oracleType) throws Exception {
        writeApprovedTestCaseWithOracleType(rpId, "db_seed", oracleType);
    }

    private void writeApprovedTestCaseWithOracleType(String rpId, String bindingType, String oracleType) throws Exception {
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s-AC-001
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s-AC-001
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-transform-job
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s-ER-001.yaml
                oracles:
                  normalized_orders:
                    type: %s
                    ref: expected-results/approved/%s-ER-001.yaml
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
                """.formatted(rpId, rpId, rpId, rpId, rpId, rpId, oracleType, rpId, bindingType));
    }

    private void writeApprovedTestCaseWithUnsafeFixture(String rpId) throws Exception {
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s-AC-001
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s-AC-001
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-transform-job
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s-ER-001.yaml
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s-ER-001.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/db/orders_seed.yaml
                      bind_as: db_seed
                fixture:
                  setup:
                    - provider: relational_db
                      action: seed_orders
                      lifecycle: mutates_state
                policy:
                  cleanup_required: false
                steps:
                  - id: run_pipeline
                    action: call_ru
                    target_ru_id: RU-transform-job
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_orders}
                evidence_required:
                  - execution_log
                  - cleanup_result
                """.formatted(rpId, rpId, rpId, rpId, rpId, rpId, rpId));
    }

    private void writeApprovedTestCaseWithCleanupFixture(String rpId) throws Exception {
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s-AC-001
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s-AC-001
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-transform-job
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s-ER-001.yaml
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s-ER-001.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/db/orders_seed.yaml
                      bind_as: db_seed
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
                steps:
                  - id: run_pipeline
                    action: call_ru
                    target_ru_id: RU-transform-job
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_orders}
                evidence_required:
                  - execution_log
                  - cleanup_result
                """.formatted(rpId, rpId, rpId, rpId, rpId, rpId, rpId));
    }
}
