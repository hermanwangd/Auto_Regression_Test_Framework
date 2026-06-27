package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicReference;
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
    void checkReadinessWritesAgentReadableReportWhenWriteReportIsRequested() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "RP-001", "--format", "yaml",
                "--write-report"},
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
    void checkReadinessWithRpIdDoesNotCreateReportUnlessWriteReportIsRequested() {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "RP-NOT-CREATED", "--format", "yaml"},
                print(output), print(new ByteArrayOutputStream()));

        Path report = tempDir.resolve(
                "docs/08-release/release-packages/RP-NOT-CREATED/evidence/readiness/readiness.yaml");
        assertThat(exit).isZero();
        assertThat(output.toString()).doesNotContain("report_path:");
        assertThat(Files.exists(report)).isFalse();
        assertThat(Files.exists(tempDir.resolve("docs/08-release/release-packages/RP-NOT-CREATED"))).isFalse();
    }

    @Test
    void checkReadinessReturnsFailureWhenProductRepoIsIncomplete() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"check-readiness", "--root", tempDir.toString(), "--format", "yaml"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("status: fail");
        assertThat(output.toString()).contains("reason: missing_product_repo_path");
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
        assertThat(output.toString()).contains("reason: rp_ru_mapping_readiness_failed");
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
        assertThat(output.toString()).contains("field_path: behavior");
        assertThat(output.toString()).contains("reason: ac_readiness_gap");
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
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("field_path: release_units");
        assertThat(output.toString()).contains("reason: execution_context_incomplete");
        assertThat(output.toString()).contains("owner_action: Complete RP/RU mapping execution context before executable test generation.");
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
        writeApprovedTestCase("RP-001", "existing_state");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("binding_gaps:");
        assertThat(output.toString()).contains("reason: binding_resolution_failed");
        assertThat(output.toString()).contains("existing_state");
        assertThat(output.toString()).contains("adapter_execution_started: false");
    }

    @Test
    void runDryRunBlocksParameterizedTestsUntilExpansionIsImplemented() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedParameterizedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("binding_gaps:");
        assertThat(output.toString()).contains("test_case_id: RP-001-TC-001");
        assertThat(output.toString()).contains("ac_id: RP-001-AC-001");
        assertThat(output.toString()).contains("field_path: parameters");
        assertThat(output.toString()).contains("reason: parameter_expansion_unsupported");
        assertThat(output.toString()).contains("owner_action: Parameter expansion is not implemented yet; remove parameters or implement explicit case expansion before execution.");
        assertThat(output.toString()).contains("adapter_execution_started: false");
    }

    @Test
    void runDryRunReportsPassedApGatesBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: dry_run_ready");
        assertThat(output.toString())
                .contains("ap_gate_status:")
                .contains("ap: Discovery and Context\n    status: passed")
                .contains("ap: Definition and Validation\n    status: passed")
                .contains("ap: Planning and Binding\n    status: passed")
                .contains("ap: Fixture and State Manager\n    status: passed")
                .contains("ap: Execution Engine\n    status: passed")
                .contains("ap: Oracle and Assertion Engine\n    status: passed")
                .contains("ap: Evidence and Reporting\n    status: passed");
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
        writeApprovedTestCase("RP-001", "existing_state");
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
        assertThat(failureDetails).contains("reason: binding_resolution_failed");
        assertThat(failureDetails).contains("package_inputs.inputs.orders_seed.bind_as");
        assertThat(failureDetails).contains("existing_state");
    }

    @Test
    void blockedRunDoesNotOverwriteExistingRunEvidence() throws Exception {
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
        writeApprovedTestCase("RP-001", "api_payload");

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));

        Path firstRun = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001/run.yaml");
        Path blockedRun = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-002/run.yaml");
        assertThat(exit).isEqualTo(1);
        assertThat(Files.readString(firstRun)).contains("status: passed");
        assertThat(Files.readString(blockedRun)).contains("status: blocked");
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
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.bindings.db_seed");
        assertThat(output.toString()).contains("reason: provider_contract_resolution_failed");
        assertThat(output.toString()).contains("provider_family: file_batch");
        assertThat(output.toString()).contains("provider_family: db_fixture");
        assertThat(output.toString()).contains("affected_ru: RU-transform-job");
        assertThat(output.toString()).contains("capability: spring_boot_cli");
        assertThat(output.toString()).contains("capability: db_seed");
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).doesNotContain("run_status: dry_run_ready");
    }

    @Test
    void runDryRunBlocksFileBatchShellWithoutOutputRefBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMappingWithoutOutputRef("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli.outputs.actual_output_ref");
        assertThat(output.toString()).contains("provider_family: file_batch");
        assertThat(output.toString()).contains("provider_type: shell");
        assertThat(output.toString()).contains("Declare actual_output_ref for executable adapter `spring_boot_cli`");
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).doesNotContain("run_status: dry_run_ready");
    }

    @Test
    void runDryRunBlocksFileBatchShellWithoutTimeoutBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMappingWithoutTimeout("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli.timeout_seconds");
        assertThat(output.toString()).contains("provider_family: file_batch");
        assertThat(output.toString()).contains("provider_type: shell");
        assertThat(output.toString()).contains("Declare timeout_seconds as a positive bounded integer");
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
    void runSitDeployedBlocksWithoutDeploymentReadinessAndWritesStructuredEvidence() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeSitMappingWithoutDeploymentReadiness("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "ready\n");
        writeApprovedDeploymentReadinessTestCase("RP-001", "RP-001-AC-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "sit_deployed"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        String failureDetails = Files.readString(runDir.resolve("failure_details.yaml"));
        String batchEvidence = Files.readString(tempDir.resolve(
                "docs/08-release/release-packages/RP-001/evidence/batches/BATCH-001/batch.yaml"));
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(runEvidence).contains("status: blocked");
        assertThat(runEvidence).contains("execution_mode: sit_deployed");
        assertThat(runEvidence).contains("environment_ref: sit://payment/k8s");
        assertThat(batchEvidence).contains("status: blocked");
        assertThat(batchEvidence).contains("execution_mode: sit_deployed");
        assertThat(failureDetails).contains("ap: Discovery and Context");
        assertThat(failureDetails).contains("field_path: release_units[0].deployment.deployment_ref");
        assertThat(failureDetails).contains("field_path: release_units[0].deployment.readiness_check");
        assertThat(failureDetails).contains("field_path: release_units[0].deployment.deployed_version_ref");
        assertThat(failureDetails).contains("owner_action: Provide SIT deployment readiness evidence");
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
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(runEvidence).contains("status: passed");
        assertThat(runEvidence).contains("rp_id: RP-001");
        assertThat(runEvidence).contains("execution_mode: ci_ephemeral");
        assertThat(runEvidence).contains("environment_ref: ci://pipeline/RP-001");
        assertThat(runEvidence).contains("ru_refs:");
        assertThat(runEvidence).contains("exit_code: 0");
        assertThat(runEvidence).contains("resolved_bindings:");
        assertThat(runEvidence).contains("binding_type: db_seed");
        assertThat(runEvidence).contains("provider_contracts_used:");
        assertThat(runEvidence).contains("contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli");
        assertThat(runEvidence).contains("contract_path: release_units[0].provider_contracts.bindings.db_seed");
        assertThat(runEvidence).contains("provider_family: file_batch");
        assertThat(runEvidence).contains("provider_type: file_fixture");
        assertThat(runEvidence).contains("affected_ru: RU-transform-job");
    }

    @Test
    void runExecutesApprovedExternalRunnerAndWritesEvidenceMap() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedExternalRunnerMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "runner-ok\n");
        writeApprovedExternalRunnerTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("adapter_execution_started: true");
        assertThat(output.toString()).contains("run_status: passed");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(runEvidence).contains("provider_family: external_runner");
        assertThat(runEvidence).contains("provider_type: command_runner");
        assertThat(runEvidence).contains("provider_evidence:");
        assertThat(runEvidence).contains("external_runner: external_runner.yaml");
        String externalRunnerEvidence = Files.readString(runDir.resolve("external_runner.yaml"));
        assertThat(externalRunnerEvidence).contains("escape_hatch_status: approved");
        assertThat(externalRunnerEvidence).contains("approval_ref: docs/10-change-control/runner-approval.md");
        assertThat(externalRunnerEvidence).contains("approved_by: qa_lead");
        assertThat(externalRunnerEvidence).contains("runner_stdout: logs/stdout.log");
        assertThat(externalRunnerEvidence).contains("runner_actual_output: actual/output.txt");
    }

    @Test
    void runFailsApprovedExternalRunnerWhenMappedEvidenceArtifactIsMissing() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedExternalRunnerMapping("RP-001", "", "actual/output.txt", 30, "logs/stdout.log",
                "runner_diagnostics: diagnostics/result.json");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "runner-ok\n");
        writeApprovedExternalRunnerTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: true");
        assertThat(output.toString()).contains("run_status: failed");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(runEvidence).contains("status: failed");
        assertThat(runEvidence).contains("assertion_status: not_run");
        String externalRunnerEvidence = Files.readString(runDir.resolve("external_runner.yaml"));
        assertThat(externalRunnerEvidence).contains("evidence_complete: false");
        assertThat(externalRunnerEvidence).contains("missing_mapped_artifacts:");
        assertThat(externalRunnerEvidence).contains("name: runner_diagnostics");
        assertThat(externalRunnerEvidence).contains("path: diagnostics/result.json");
    }

    @Test
    void runDryRunBlocksExternalRunnerWhenBuiltInProviderAlternativeExists() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedExternalRunnerMapping("RP-001", "request_response/rest");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "runner-ok\n");
        writeApprovedExternalRunnerTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.external_runner.built_in_provider_alternative");
        assertThat(output.toString()).contains("Configure built-in provider `request_response/rest` before using external runner");
    }

    @Test
    void runDryRunBlocksExternalRunnerWithUnsafeOutputPath() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedExternalRunnerMapping("RP-001", "", "../outside/output.txt");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "runner-ok\n");
        writeApprovedExternalRunnerTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.external_runner.outputs.actual_output_ref");
        assertThat(output.toString()).contains("Keep external runner output path `../outside/output.txt` under the run evidence directory");
    }

    @Test
    void runDryRunBlocksExternalRunnerWithUnsafeEvidenceMapPath() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedExternalRunnerMapping("RP-001", "", "actual/output.txt", 30, "../outside/stdout.log");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "runner-ok\n");
        writeApprovedExternalRunnerTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.external_runner.evidence_map.runner_stdout");
        assertThat(output.toString()).contains("Keep external runner evidence map path `../outside/stdout.log` under the run evidence directory");
    }

    @Test
    void runDryRunBlocksExternalRunnerWithUnboundedTimeout() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeApprovedExternalRunnerMapping("RP-001", "", "actual/output.txt", 0);
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "runner-ok\n");
        writeApprovedExternalRunnerTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.external_runner.timeout_seconds");
        assertThat(output.toString()).contains("Declare timeout_seconds as a positive bounded integer");
    }

    @Test
    void runUsesProviderContractForExecutionTargetRuInMultiRuMapping() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableMultiRuMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(exit).isZero();
        assertThat(Files.readString(runDir.resolve("logs/stdout.log"))).contains("adapter-ok");
        assertThat(Files.readString(runDir.resolve("logs/stdout.log"))).doesNotContain("wrong-ru");
        assertThat(runEvidence)
                .contains("status: passed")
                .contains("resolved_dependencies:")
                .contains("RU-unrelated-job")
                .contains("contract_path: release_units[1].provider_contracts.adapters.spring_boot_cli")
                .contains("affected_ru: RU-transform-job");
    }

    @Test
    void runResolvesProviderContractsFromTargetRuWhenAdapterNameRepeats() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeSameAdapterMultiRuMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "downstream-ok\n");
        writeApprovedTargetRuTestCaseWithBinding(
                "RP-001", "RP-001-TC-001", "RP-001-AC-001", "RU-downstream-job", "spring_boot_cli", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(exit).isZero();
        assertThat(Files.readString(runDir.resolve("logs/stdout.log"))).contains("downstream-ok");
        assertThat(runEvidence)
                .contains("contract_path: release_units[1].provider_contracts.adapters.spring_boot_cli")
                .contains("contract_path: release_units[1].provider_contracts.bindings.db_seed")
                .contains("affected_ru: RU-downstream-job");
        assertThat(runEvidence)
                .doesNotContain("contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli")
                .doesNotContain("contract_path: release_units[0].provider_contracts.bindings.db_seed")
                .doesNotContain("affected_ru: RU-upstream-job");
    }

    @Test
    void runExecutesIndependentRuWhenAnotherRuIsBlockedByProviderGap() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001", "RP-001-AC-002");
        writePartialBlockMultiRuMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "adapter-ok\n");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-002", "blocked-ru-should-not-run\n");
        writeApprovedTargetRuTestCaseWithBinding(
                "RP-001", "RP-001-TC-001", "RP-001-AC-001", "RU-ready-job", "ready_cli", "db_seed");
        writeApprovedDependencyTestCase(
                "RP-001", "RP-001-TC-002", "RP-001-AC-002", "RU-missing-provider-job", "missing_cli");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path readyRun = packageRoot.resolve("evidence/runs/RUN-001/run.yaml");
        Path blockedRun = packageRoot.resolve("evidence/runs/RUN-002/run.yaml");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("test_case_id: RP-001-TC-001");
        assertThat(output.toString()).contains("test_case_id: RP-001-TC-002");
        assertThat(Files.readString(readyRun))
                .contains("test_case_id: RP-001-TC-001")
                .contains("status: passed")
                .contains("adapter_execution_started: true");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/logs/stdout.log")))
                .contains("adapter-ok");
        assertThat(Files.readString(blockedRun))
                .contains("test_case_id: RP-001-TC-002")
                .contains("status: blocked")
                .contains("adapter_execution_started: false")
                .contains("failure_details: failure_details.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-002/failure_details.yaml")))
                .contains("release_units[1].provider_contracts.adapters.missing_cli")
                .contains("affected_ru: RU-missing-provider-job");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/logs/stdout.log"))).isFalse();
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("run_id: RUN-001")
                .contains("status: passed")
                .contains("run_id: RUN-002")
                .contains("status: blocked");
    }

    @Test
    void runBlocksDownstreamRuWhenRequiredUpstreamRunFails() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001", "RP-001-AC-002");
        writeDependencyFailureMultiRuMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "expected-upstream\n");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-002", "downstream-ok\n");
        writeApprovedDependencyTestCase(
                "RP-001", "RP-001-TC-001", "RP-001-AC-001", "RU-upstream-job", "upstream_cli");
        writeApprovedDependencyTestCase(
                "RP-001", "RP-001-TC-002", "RP-001-AC-002", "RU-downstream-job", "downstream_cli");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path upstreamRun = packageRoot.resolve("evidence/runs/RUN-001/run.yaml");
        Path downstreamRun = packageRoot.resolve("evidence/runs/RUN-002/run.yaml");
        String downstreamEvidence = Files.readString(downstreamRun);
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: failed");
        assertThat(Files.readString(upstreamRun)).contains("status: failed");
        assertThat(downstreamEvidence)
                .contains("status: blocked")
                .contains("adapter_execution_started: false")
                .contains("resolved_dependencies:")
                .contains("RU-upstream-job")
                .contains("failure_details: failure_details.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-002/failure_details.yaml")))
                .contains("blocked_dependency_ru: RU-upstream-job")
                .contains("test_case_id: RP-001-TC-002")
                .contains("ac_id: RP-001-AC-002");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/logs/stdout.log"))).isFalse();
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("run_id: RUN-001")
                .contains("status: failed")
                .contains("run_id: RUN-002")
                .contains("status: blocked");
    }

    @Test
    void runExecutesRestRequestResponseProviderAndWritesEvidence() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String response = "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n";
            exchange.sendResponseHeaders(200, response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            RegressionCommand command = command();
            String rpId = "RP-REST";
            String acId = rpId + "-AC-001";
            command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                    print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
            command.execute(new String[] {
                    "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                    print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
            writeReadyAcceptanceCriteria(rpId, acId);
            writeRestProviderMapping(rpId, server.getAddress().getPort());
            writeRestPayload(rpId);
            writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
            writeApprovedRestTestCase(rpId, acId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            int exit = command.execute(new String[] {
                    "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                    print(output), print(new ByteArrayOutputStream()));

            Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
            String runEvidence = Files.readString(runDir.resolve("run.yaml"));
            assertThat(exit).isZero();
            assertThat(requestBody.get()).isEqualTo("{\"amount\":100,\"currency\":\"USD\"}\n");
            assertThat(output.toString()).contains("adapter_execution_started: true");
            assertThat(Files.readString(runDir.resolve("actual/response.json")))
                    .isEqualTo("{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
            assertThat(runEvidence)
                    .contains("status: passed")
                    .contains("binding_type: api_payload")
                    .contains("provider_family: request_response")
                    .contains("contract_path: release_units[0].provider_contracts.adapters.request_response")
                    .contains("actual_output: actual/response.json")
                    .contains("assertion_status: passed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runDryRunBlocksRestProviderWithUnsupportedActionBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-ACTION";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeRestProviderMapping(rpId, 65535);
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId, "authorize_payment");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.request_response.actions.authorize_payment");
        assertThat(output.toString()).contains("Declare request/response action `authorize_payment` before invocation");
    }

    @Test
    void runDryRunBlocksRestProviderWithMissingPayloadBindingBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-BINDING";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeRestProviderMapping(rpId, 65535, "submit_payment", "missing_payload");
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.request_response.actions.submit_payment.request_binding");
        assertThat(output.toString()).contains("Add package input binding `missing_payload` before invoking request/response action `submit_payment`");
    }

    @Test
    void runExecutesLocalMessagingProviderAndWritesEvidence() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId);
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(exit).isZero();
        assertThat(Files.readString(runDir.resolve("messaging.yaml")))
                .contains("provider: message_bus")
                .contains("provider_type: local")
                .contains("topic_ref: mock://payment.events")
                .contains("action: publish_payment_event")
                .contains("message_count: 1")
                .contains("status: passed");
        assertThat(Files.readString(runDir.resolve("actual/message.json"))).isEqualTo(payload);
        assertThat(runEvidence)
                .contains("status: passed")
                .contains("binding_type: message_event")
                .contains("provider_family: messaging")
                .contains("contract_path: release_units[0].provider_contracts.adapters.message_bus")
                .contains("actual_output: actual/message.json")
                .contains("assertion_status: passed")
                .contains("provider_evidence:")
                .contains("messaging: messaging.yaml");
    }

    @Test
    void runBlocksUnsupportedMessagingProviderTypeBeforeExecution() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-KAFKA";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId, "kafka", "kafka://payment.events");
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("contract_path: release_units[0].provider_contracts.adapters.message_bus.provider_type")
                .contains("provider_family: messaging")
                .contains("provider_type: kafka")
                .contains("registry_status: unsupported")
                .contains("Unsupported provider_type `kafka`");
        assertThat(Files.exists(runDir.resolve("messaging.yaml"))).isFalse();
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: blocked")
                .contains("adapter_execution_started: false");
    }

    @Test
    void runBlocksMessagingProviderWhenPayloadBindingIsUnresolvedBeforeExecution() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-MISSING-BINDING";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "missing_event");
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("adapter_execution_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_family: messaging")
                .contains("provider_type: local")
                .contains("contract_path: release_units[0].provider_contracts.adapters.message_bus.actions.publish_payment_event.payload_binding")
                .contains("Add package input binding `missing_event` before invoking messaging action `publish_payment_event`");
        assertThat(Files.exists(runDir.resolve("messaging.yaml"))).isFalse();
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: blocked")
                .contains("adapter_execution_started: false");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithUnsupportedActionBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-ACTION";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId);
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId, "consume_payment_event");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.message_bus.actions.consume_payment_event");
        assertThat(output.toString()).contains("Declare messaging action `consume_payment_event` before invocation");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithUnsupportedSerializationBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-SERIALIZATION";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "payment_event",
                "publish_payment_event", "avro");
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("adapter_execution_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_family: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.adapters.message_bus.actions.publish_payment_event.serialization");
        assertThat(output.toString()).contains("Use supported messaging serialization `json` before invoking messaging action `publish_payment_event`");
    }

    @Test
    void runExecutesLocalDeploymentReadinessProviderAndWritesEvidence() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-READY";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "deployment"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDeploymentReadinessMapping(rpId);
        writeDeploymentReadyMarker(rpId);
        writeApprovedExpectedResult(rpId, acId, "ready\n");
        writeApprovedDeploymentReadinessTestCase(rpId, acId);

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(exit).isZero();
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("status: passed")
                .contains("provider: k8s_readiness")
                .contains("provider_type: local")
                .contains("readiness_probe: file_exists")
                .contains("deployment_ref: fixtures/readiness/payment-api.ready")
                .contains("check_count: 1");
        assertThat(Files.readString(runDir.resolve("actual/readiness.txt"))).isEqualTo("ready\n");
        assertThat(runEvidence)
                .contains("status: passed")
                .contains("provider_family: deployment_readiness")
                .contains("contract_path: release_units[0].provider_contracts.adapters.k8s_readiness")
                .contains("actual_output: actual/readiness.txt")
                .contains("assertion_status: passed");
    }

    @Test
    void runFailsDeploymentReadinessWhenMarkerIsMissing() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-READY-MISSING";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "deployment"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDeploymentReadinessMapping(rpId);
        writeApprovedExpectedResult(rpId, acId, "ready\n");
        writeApprovedDeploymentReadinessTestCase(rpId, acId);

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(Files.readString(runDir.resolve("readiness.yaml")))
                .contains("status: failed")
                .contains("provider: k8s_readiness")
                .contains("deployment_ref: fixtures/readiness/payment-api.ready")
                .contains("check_count: 0")
                .contains("Deployment readiness marker not found");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: failed")
                .contains("provider_family: deployment_readiness")
                .contains("assertion_status: not_run");
    }

    @Test
    void runExecutesDbFixtureSetupAndCleanupWithEvidence() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-DB";
        String acId = rpId + "-AC-001";
        String jdbcUrl = "jdbc:h2:mem:rp_db_fixture_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_db"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDbFixtureMapping(rpId, jdbcUrl);
        writeDbFixtureSql(rpId);
        writeApprovedExpectedResult(rpId, acId, "db-fixture-ok\n");
        writeApprovedDbFixtureTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(exit).isZero();
        assertThat(Files.readString(runDir.resolve("fixture_setup.yaml")))
                .contains("provider: relational_db")
                .contains("action: seed_orders")
                .contains("row_count: 1");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml")))
                .contains("provider: relational_db")
                .contains("action: cleanup_orders")
                .contains("status: passed");
        assertThat(countOrders(jdbcUrl)).isZero();
        assertThat(runEvidence)
                .contains("status: passed")
                .contains("provider_family: db_fixture")
                .contains("contract_path: release_units[0].provider_contracts.fixtures.relational_db")
                .contains("cleanup_result: cleanup.yaml");
    }

    @Test
    void runCleansDbFixtureWhenAssertionFails() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-DB-FAIL";
        String acId = rpId + "-AC-001";
        String jdbcUrl = "jdbc:h2:mem:rp_db_fixture_fail_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_db"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDbFixtureMapping(rpId, jdbcUrl);
        writeDbFixtureSql(rpId);
        writeApprovedExpectedResult(rpId, acId, "unexpected-output\n");
        writeApprovedDbFixtureTestCase(rpId, acId);

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: failed")
                .contains("assertion_status: failed")
                .contains("cleanup_result: cleanup.yaml");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml")))
                .contains("provider: relational_db")
                .contains("action: cleanup_orders")
                .contains("status: passed");
        assertThat(countOrders(jdbcUrl)).isZero();
    }

    @Test
    void runCreatesBatchAndSeparateRunEvidenceForEachApprovedTest() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001", "RP-001-AC-002");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-002");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        writeApprovedTestCase("RP-001", "RP-001-TC-002", "RP-001-AC-002", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path batchYaml = packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml");
        Path firstRun = packageRoot.resolve("evidence/runs/RUN-001/run.yaml");
        Path secondRun = packageRoot.resolve("evidence/runs/RUN-002/run.yaml");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("batch_id: BATCH-001");
        assertThat(output.toString()).contains("run_id: RUN-001");
        assertThat(output.toString()).contains("run_id: RUN-002");
        String batchEvidence = Files.readString(batchYaml);
        assertThat(batchEvidence).contains("batch_id: BATCH-001");
        assertThat(batchEvidence).contains("execution_mode: ci_ephemeral");
        assertThat(batchEvidence).contains("environment_ref: ci://pipeline/RP-001");
        assertThat(batchEvidence).contains("started_at:");
        assertThat(batchEvidence).contains("finished_at:");
        assertThat(yamlLineValue(batchEvidence, "started_at")).isNotBlank();
        assertThat(yamlLineValue(batchEvidence, "finished_at")).isNotBlank();
        assertThat(java.time.OffsetDateTime.parse(yamlLineValue(batchEvidence, "finished_at")))
                .isAfterOrEqualTo(java.time.OffsetDateTime.parse(yamlLineValue(batchEvidence, "started_at")));
        assertThat(batchEvidence).doesNotContain("env:");
        assertThat(batchEvidence).doesNotContain("completed_at:");
        assertThat(batchEvidence).contains("run_id: RUN-001");
        assertThat(batchEvidence).contains("run_id: RUN-002");
        assertThat(Files.readString(firstRun)).contains("batch_id: BATCH-001");
        assertThat(Files.readString(firstRun)).contains("test_case_id: RP-001-TC-001");
        assertThat(Files.readString(firstRun)).contains("ac_id: RP-001-AC-001");
        assertThat(Files.readString(secondRun)).contains("batch_id: BATCH-001");
        assertThat(Files.readString(secondRun)).contains("test_case_id: RP-001-TC-002");
        assertThat(Files.readString(secondRun)).contains("ac_id: RP-001-AC-002");
    }

    @Test
    void runBatchEvidenceUsesResolvedExecutionModeWhenEnvOptionIsOmitted() throws Exception {
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

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));

        Path batchYaml = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/evidence/batches/BATCH-001/batch.yaml");
        assertThat(exit).isZero();
        String batchEvidence = Files.readString(batchYaml);
        assertThat(batchEvidence).contains("execution_mode: ci_ephemeral");
        assertThat(batchEvidence).contains("environment_ref: ci://pipeline/RP-001");
        assertThat(batchEvidence).doesNotContain("env:");
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
        writeDbFixtureSql("RP-001");
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
    void reportRunIdCreatesDiagnosticPackageButDoesNotClaimReleaseReviewReady() throws Exception {
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
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("report_status: not_review_ready");
        assertThat(output.toString()).contains("coverage_percent: 100.0");
        assertThat(output.toString()).contains("batch_required_for_release_coverage: RUN-001");
        assertThat(output.toString()).contains("ap: Evidence and Reporting");
        assertThat(output.toString()).contains("reason: batch_required_for_release_coverage");
        assertThat(output.toString()).contains("owner_action: Generate release coverage with --batch-id");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("covered: 1");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("total_automatable: 1");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RP-001-AC-001");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RP-001-TC-001");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RUN-001");
        assertThat(Files.exists(reviewDir.resolve("evidence_index.md"))).isTrue();
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml")))
                .contains("unresolved_failures: 1")
                .contains("batch_required_for_release_coverage: RUN-001")
                .contains("reason: batch_required_for_release_coverage")
                .contains("owner_action: Generate release coverage with --batch-id");
        assertThat(Files.readString(reviewDir.resolve("release_review_summary.yaml"))).contains("review_ready: false");
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
    void reportAggregatesBatchCoverageForMultiplePassedRuns() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001", "RP-001-AC-002");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-002");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        writeApprovedTestCase("RP-001", "RP-001-TC-002", "RP-001-AC-002", "db_seed");
        command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/BATCH-001");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("report_status: review_ready");
        assertThat(output.toString()).contains("coverage_percent: 100.0");
        assertThat(output.toString()).contains("covered: 2");
        assertThat(output.toString()).contains("total_automatable: 2");
        assertThat(Files.readString(reviewDir.resolve("coverage_report.yaml"))).contains("batch_id: BATCH-001");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RUN-001");
        assertThat(Files.readString(reviewDir.resolve("traceability_report.yaml"))).contains("RUN-002");
    }

    @Test
    void reportCountsOnlyPassedTraceableAcFromBatch() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001", "RP-001-AC-002");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001", "adapter-ok\n");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-002", "different-expected-value\n");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        writeApprovedTestCase("RP-001", "RP-001-TC-002", "RP-001-AC-002", "db_seed");
        command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/BATCH-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("coverage_percent: 50.0");
        assertThat(output.toString()).contains("covered: 1");
        assertThat(output.toString()).contains("total_automatable: 2");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("run_status: failed");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("uncovered_ac: RP-001-AC-002");
    }

    @Test
    void reportDeduplicatesCoverageWhenMultiplePassedRunsCoverSameAc() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001", "RP-001-AC-002");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        writeApprovedTestCase("RP-001", "RP-001-TC-002", "RP-001-AC-001", "db_seed");
        command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/BATCH-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("coverage_percent: 50.0");
        assertThat(output.toString()).contains("covered: 1");
        assertThat(output.toString()).contains("total_automatable: 2");
        assertThat(Files.readString(reviewDir.resolve("failure_summary.yaml"))).contains("uncovered_ac: RP-001-AC-002");
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
        assertThat(failureSummary).contains("ap: Oracle and Assertion Engine");
        assertThat(failureSummary).contains("reason: assertion_failed");
        assertThat(failureSummary).contains("owner_action: Review assertion evidence");
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
                "report", "--root", tempDir.toString(), "--rp-id", rpId, "--batch-id", "BATCH-001"},
                print(reportOutput), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/" + rpId);
        Path reviewDir = packageRoot.resolve("evidence/review/BATCH-001");
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
        assertThat(evidenceIndex).contains("BATCH-001");
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

    private String yamlLineValue(String yaml, String field) {
        return yaml.lines()
                .filter(line -> line.startsWith(field + ":"))
                .map(line -> line.substring((field + ":").length()).trim())
                .findFirst()
                .orElse("");
    }

    private void writeReadyAcceptanceCriteriaForAcs(String rpId, String... acIds) throws Exception {
        Path acFile = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/acceptance_criteria.md");
        StringBuilder builder = new StringBuilder("acceptance_criteria:\n");
        for (String acId : acIds) {
            builder.append("""
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
        Files.writeString(acFile, builder.toString());
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
                          provider_family: file_batch
                          provider_type: shell
                          command: java -jar ${repo}/target/release-unit.jar
                          timeout_seconds: 10
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      bindings:
                        db_seed:
                          provider_family: file_batch
                          provider_type: file_fixture
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
        writeExecutableCiMapping(rpId, stdoutValue, true, true);
    }

    private void writeExecutableCiMappingWithoutOutputRef(String rpId) throws Exception {
        writeExecutableCiMapping(rpId, "adapter-ok", true, false);
    }

    private void writeExecutableCiMappingWithoutTimeout(String rpId) throws Exception {
        writeExecutableCiMapping(rpId, "adapter-ok", false, true);
    }

    private void writeExecutableCiMapping(
            String rpId,
            String stdoutValue,
            boolean includeTimeout,
            boolean includeOutputRef) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String timeoutField = includeTimeout ? "                          timeout_seconds: 10\n" : "";
        String outputField = includeOutputRef
                ? "                          outputs:\n"
                        + "                            actual_output_ref: actual/output.txt\n"
                : "                          outputs: {}\n";
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
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo %s; echo adapter-warn >&2'
                          working_directory: .
%s                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
%s                      bindings:
                        db_seed:
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId, stdoutValue, timeoutField, outputField));
    }

    private void writeApprovedExternalRunnerMapping(String rpId) throws Exception {
        writeApprovedExternalRunnerMapping(rpId, "");
    }

    private void writeApprovedExternalRunnerMapping(String rpId, String builtInProviderAlternative) throws Exception {
        writeApprovedExternalRunnerMapping(rpId, builtInProviderAlternative, "actual/output.txt");
    }

    private void writeApprovedExternalRunnerMapping(
            String rpId,
            String builtInProviderAlternative,
            String actualOutputRef) throws Exception {
        writeApprovedExternalRunnerMapping(rpId, builtInProviderAlternative, actualOutputRef, 30);
    }

    private void writeApprovedExternalRunnerMapping(
            String rpId,
            String builtInProviderAlternative,
            String actualOutputRef,
            int timeoutSeconds) throws Exception {
        writeApprovedExternalRunnerMapping(rpId, builtInProviderAlternative, actualOutputRef, timeoutSeconds,
                "logs/stdout.log");
    }

    private void writeApprovedExternalRunnerMapping(
            String rpId,
            String builtInProviderAlternative,
            String actualOutputRef,
            int timeoutSeconds,
            String runnerStdoutRef) throws Exception {
        writeApprovedExternalRunnerMapping(rpId, builtInProviderAlternative, actualOutputRef, timeoutSeconds,
                runnerStdoutRef, "");
    }

    private void writeApprovedExternalRunnerMapping(
            String rpId,
            String builtInProviderAlternative,
            String actualOutputRef,
            int timeoutSeconds,
            String runnerStdoutRef,
            String extraEvidenceMapLine) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String builtInProviderAlternativeLine = builtInProviderAlternative.isBlank()
                ? ""
                : "built_in_provider_alternative: \"" + builtInProviderAlternative + "\"";
        String extraEvidenceMapEntry = extraEvidenceMapLine.isBlank()
                ? ""
                : "            " + extraEvidenceMapLine;
        String mappingContent = """
                rp_id: %s
                release_units:
                  - ru_id: RU-legacy-runner
                    repo: /repo/legacy-runner
                    unit_type: external_test_runner
                    owner: qa
                    version_ref: runner-42
                    validation_boundary: external_runner
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://runner/%s
                    adapter: external_runner
                    provider_contracts:
                      adapters:
                        external_runner:
                          provider_family: external_runner
                          provider_type: command_runner
                          approval_ref: docs/10-change-control/runner-approval.md
                          approved_by: qa_lead
                          reason: Legacy RP-owned harness is required until a reusable provider exists.
                          __BUILT_IN_PROVIDER_ALTERNATIVE__
                          command: printf 'runner-ok\\n'
                          timeout_seconds: %s
                          inputs:
                            test_case: ${test_case.test_case_id}
                          outputs:
                            actual_output_ref: %s
                          evidence_map:
                            runner_stdout: %s
                            runner_actual_output: actual/output.txt
                %s
                    evidence_responsibility: [runner_result]
                    dependencies: []
                """.formatted(rpId, rpId, timeoutSeconds, actualOutputRef, runnerStdoutRef, extraEvidenceMapEntry)
                .replace("__BUILT_IN_PROVIDER_ALTERNATIVE__",
                        builtInProviderAlternativeLine);
        Files.writeString(mapping, mappingContent);
    }

    private void writeExecutableMultiRuMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-unrelated-job
                    repo: /repo/unrelated
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/unrelated
                    adapter: other_cli
                    provider_contracts:
                      adapters:
                        other_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo wrong-ru'
                          working_directory: .
                          timeout_seconds: 10
                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
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
                          provider_family: file_batch
                          provider_type: shell
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
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [RU-unrelated-job]
                """.formatted(rpId, rpId, rpId));
    }

    private void writeSameAdapterMultiRuMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-upstream-job
                    repo: /repo/upstream
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/upstream
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo upstream-wrong'
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
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-downstream-job
                    repo: /repo/downstream
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/downstream
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo downstream-ok'
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
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId, rpId));
    }

    private void writePartialBlockMultiRuMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-ready-job
                    repo: /repo/ready
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/ready
                    adapter: ready_cli
                    provider_contracts:
                      adapters:
                        ready_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo adapter-ok'
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
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-missing-provider-job
                    repo: /repo/missing-provider
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/missing-provider
                    adapter: missing_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId, rpId));
    }

    private void writeDependencyFailureMultiRuMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-upstream-job
                    repo: /repo/upstream
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/upstream
                    adapter: upstream_cli
                    provider_contracts:
                      adapters:
                        upstream_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo upstream-actual'
                          working_directory: .
                          timeout_seconds: 10
                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-downstream-job
                    repo: /repo/downstream
                    unit_type: data_pipeline
                    owner: product_developer
                    version_ref: main
                    validation_boundary: execute_pipeline_with_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://pipeline/%s/downstream
                    adapter: downstream_cli
                    provider_contracts:
                      adapters:
                        downstream_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo downstream-ok'
                          working_directory: .
                          timeout_seconds: 10
                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
                          outputs:
                            actual_output_ref: actual/output.txt
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [RU-upstream-job]
                """.formatted(rpId, rpId, rpId));
    }

    private void writeRestProviderMapping(String rpId, int port) throws Exception {
        writeRestProviderMapping(rpId, port, "submit_payment", "payment_payload");
    }

    private void writeRestProviderMapping(
            String rpId,
            int port,
            String actionName,
            String requestBinding) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-payment-api
                    repo: /repo/payment-api
                    unit_type: service_api
                    owner: product_developer
                    version_ref: build-123
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api
                    adapter: request_response
                    provider_contracts:
                      adapters:
                        request_response:
                          provider_family: request_response
                          provider_type: rest
                          endpoint_ref: http://127.0.0.1:%s
                          timeout_seconds: 10
                          actions:
                            %s:
                              method: POST
                              path: /payments
                              request_binding: %s
                          logs:
                            stdout: logs/response.log
                            stderr: logs/error.log
                          outputs:
                            actual_output_ref: actual/response.json
                      bindings:
                        api_payload:
                          provider_family: request_response
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, port, actionName, requestBinding));
    }

    private void writeRestPayload(String rpId) throws Exception {
        Path payload = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/fixtures/api/payment_payload.json");
        Files.createDirectories(payload.getParent());
        Files.writeString(payload, "{\"amount\":100,\"currency\":\"USD\"}\n");
    }

    private void writeMessagingProviderMapping(String rpId) throws Exception {
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "payment_event");
    }

    private void writeMessagingProviderMapping(String rpId, String providerType, String topicRef) throws Exception {
        writeMessagingProviderMapping(rpId, providerType, topicRef, "payment_event");
    }

    private void writeMessagingProviderMapping(
            String rpId,
            String providerType,
            String topicRef,
            String payloadBinding) throws Exception {
        writeMessagingProviderMapping(rpId, providerType, topicRef, payloadBinding,
                "publish_payment_event", "");
    }

    private void writeMessagingProviderMapping(
            String rpId,
            String providerType,
            String topicRef,
            String payloadBinding,
            String actionName,
            String serialization) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String serializationLine = serialization.isBlank()
                ? ""
                : "\n              serialization: " + serialization;
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service_event
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/events
                    adapter: message_bus
                    provider_contracts:
                      adapters:
                        message_bus:
                          provider_family: messaging
                          provider_type: %s
                          topic_ref: %s
                          timeout_seconds: 10
                          actions:
                            %s:
                              mode: publish
                              payload_binding: %s%s
                          logs:
                            stdout: logs/message.log
                            stderr: logs/message-error.log
                          outputs:
                            actual_output_ref: actual/message.json
                      bindings:
                        message_event:
                          provider_family: messaging
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, message_event]
                    dependencies: []
                """.formatted(rpId, providerType, topicRef, actionName, payloadBinding, serializationLine));
    }

    private void writeMessagingEventPayload(String rpId, String payload) throws Exception {
        Path eventPayload = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/fixtures/events/payment_event.json");
        Files.createDirectories(eventPayload.getParent());
        Files.writeString(eventPayload, payload);
    }

    private void writeDeploymentReadinessMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-payment-k8s
                    repo: /repo/payment-k8s
                    unit_type: deployment
                    owner: platform
                    version_ref: deploy-123
                    validation_boundary: deployed_service
                    execution_mode: ci_ephemeral
                    deployment_required: true
                    environment_ref: ci://payment/k8s
                    adapter: k8s_readiness
                    provider_contracts:
                      adapters:
                        k8s_readiness:
                          provider_family: deployment_readiness
                          provider_type: local
                          readiness_probe: file_exists
                          target_selector: deployment/payment-api
                          deployment_ref: fixtures/readiness/payment-api.ready
                          timeout_seconds: 10
                          logs:
                            stdout: logs/readiness.log
                            stderr: logs/readiness-error.log
                          outputs:
                            actual_output_ref: actual/readiness.txt
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [readiness_result]
                    dependencies: []
                """.formatted(rpId));
    }

    private void writeDeploymentReadyMarker(String rpId) throws Exception {
        Path marker = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/fixtures/readiness/payment-api.ready");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "ready\n");
    }

    private void writeSitMappingWithoutDeploymentReadiness(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-payment-k8s
                    repo: /repo/payment-k8s
                    unit_type: deployment
                    owner: platform
                    version_ref: deploy-123
                    validation_boundary: deployed_service
                    execution_mode: sit_deployed
                    deployment_required: true
                    environment_ref: sit://payment/k8s
                    adapter: k8s_readiness
                    provider_contracts:
                      adapters:
                        k8s_readiness:
                          provider_family: deployment_readiness
                          provider_type: local
                          readiness_probe: file_exists
                          target_selector: deployment/payment-api
                          deployment_ref: fixtures/readiness/payment-api.ready
                          timeout_seconds: 10
                          logs:
                            stdout: logs/readiness.log
                            stderr: logs/readiness-error.log
                          outputs:
                            actual_output_ref: actual/readiness.txt
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [readiness_result]
                    dependencies: []
                """.formatted(rpId));
    }

    private void writeDbFixtureMapping(String rpId, String jdbcUrl) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        Files.writeString(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-order-db
                    repo: /repo/order-db
                    unit_type: service_db
                    owner: product_developer
                    version_ref: schema-123
                    validation_boundary: db_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://order/db
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo db-fixture-ok'
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
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: jdbc_seed
                      fixtures:
                        relational_db:
                          provider_family: db_fixture
                          provider_type: jdbc
                          connection_ref: %s
                          cleanup_strategy: by_test_run_id
                          setup_actions:
                            seed_orders:
                              sql_ref: fixtures/db/seed_orders.sql
                          cleanup_actions:
                            cleanup_orders:
                              sql_ref: fixtures/db/cleanup_orders.sql
                          verification_queries:
                            seeded_orders:
                              sql: SELECT COUNT(*) FROM orders
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, cleanup_result]
                    dependencies: []
                """.formatted(rpId, jdbcUrl));
    }

    private void writeDbFixtureSql(String rpId) throws Exception {
        Path fixtureDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/fixtures/db");
        Files.createDirectories(fixtureDir);
        Files.writeString(fixtureDir.resolve("seed_orders.sql"), """
                CREATE TABLE IF NOT EXISTS orders (
                  id VARCHAR(64) PRIMARY KEY,
                  status VARCHAR(32)
                );
                MERGE INTO orders KEY(id) VALUES ('ORDER-001', 'READY');
                """);
        Files.writeString(fixtureDir.resolve("cleanup_orders.sql"), """
                DELETE FROM orders WHERE id = 'ORDER-001';
                """);
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
                          provider_family: file_batch
                          provider_type: shell
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
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures:
                        relational_db:
                          provider_family: db_fixture
                          provider_type: jdbc
                          connection_ref: jdbc:h2:mem:cleanup_fixture;DB_CLOSE_DELAY=-1
                          cleanup_strategy: by_test_run_id
                          setup_actions:
                            seed_orders:
                              sql_ref: fixtures/db/seed_orders.sql
                          cleanup_actions:
                            cleanup_orders:
                              sql_ref: fixtures/db/cleanup_orders.sql
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
        String expectedOutputRef = expectedOutputRef(acId);
        Path expectedOutputPath = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/" + expectedOutputRef);
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
                  output_ref: %s
                assumptions: []
                unresolved_gaps: []
                approved_by: product_developer
                approved_at: 2026-06-27T00:00:00+08:00
                approval_ref: docs/10-change-control/13_issue_template.md#APP-001
                blocked_reason: null
                """.formatted(erId, rpId, acId, acId, expectedOutputRef));
    }

    private String expectedOutputRef(String acId) {
        if (acId.endsWith("-AC-001")) {
            return "expected/output/orders.csv";
        }
        return "expected/output/" + acId + ".csv";
    }

    private void writeApprovedTestCase(String rpId, String bindingType) throws Exception {
        writeApprovedTestCaseWithOracleType(rpId, bindingType, "expected_result_artifact");
    }

    private void writeApprovedTestCase(String rpId, String testCaseId, String acId, String bindingType)
            throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
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
                  ref: expected-results/approved/%s.yaml
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
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
                """.formatted(testCaseId, rpId, acId, acId, rpId, expectedResultId, expectedResultId, bindingType));
    }

    private void writeApprovedParameterizedTestCase(String rpId) throws Exception {
        String testCaseId = rpId + "-TC-001";
        String acId = rpId + "-AC-001";
        String expectedResultId = rpId + "-ER-001";
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-transform-job
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [file_input, batch_execution, file_assertion]
                parameters:
                  strategy: explicit_cases
                  cases:
                    - name: baseline
                      bindings:
                        orders_seed: fixtures/input/orders_seed_baseline.csv
                    - name: boundary
                      bindings:
                        orders_seed: fixtures/input/orders_seed_boundary.csv
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/input/orders_seed.csv
                      bind_as: input_file
                steps:
                  - id: run_pipeline
                    action: call_ru
                    target_ru_id: RU-transform-job
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_orders}
                evidence_required:
                  - execution_log
                """.formatted(testCaseId, rpId, acId, acId, rpId, expectedResultId, expectedResultId));
    }

    private void writeApprovedExternalRunnerTestCase(String rpId) throws Exception {
        String testCaseId = rpId + "-TC-001";
        String acId = rpId + "-AC-001";
        String expectedResultId = rpId + "-ER-001";
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-legacy-runner
                  adapter: external_runner
                  execution_mode: ci_ephemeral
                  environment_ref: ci://runner/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  legacy_runner_expected:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs: {}
                steps:
                  - id: run_legacy_harness
                    action: call_ru
                    target_ru_id: RU-legacy-runner
                assertions:
                  - type: file_diff
                    oracle: ${oracles.legacy_runner_expected}
                evidence_required:
                  - execution_log
                  - runner_result
                """.formatted(testCaseId, rpId, acId, acId, rpId, expectedResultId, expectedResultId));
    }

    private void writeApprovedDependencyTestCase(
            String rpId,
            String testCaseId,
            String acId,
            String ruId,
            String adapter) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: %s
                  adapter: %s
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  normalized_output:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs: {}
                steps:
                  - id: run_ru
                    action: call_ru
                    target_ru_id: %s
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_output}
                evidence_required:
                  - execution_log
                """.formatted(
                testCaseId,
                rpId,
                acId,
                acId,
                ruId,
                adapter,
                rpId,
                expectedResultId,
                expectedResultId,
                ruId));
    }

    private void writeApprovedTargetRuTestCaseWithBinding(
            String rpId,
            String testCaseId,
            String acId,
            String ruId,
            String adapter,
            String bindingType) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: %s
                  adapter: %s
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  normalized_output:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/db/orders_seed.yaml
                      bind_as: %s
                steps:
                  - id: run_ru
                    action: call_ru
                    target_ru_id: %s
                assertions:
                  - type: file_diff
                    oracle: ${oracles.normalized_output}
                evidence_required:
                  - execution_log
                """.formatted(
                testCaseId,
                rpId,
                acId,
                acId,
                ruId,
                adapter,
                rpId,
                expectedResultId,
                expectedResultId,
                bindingType,
                ruId));
    }

    private void writeApprovedRestTestCase(String rpId, String acId) throws Exception {
        writeApprovedRestTestCase(rpId, acId, "submit_payment");
    }

    private void writeApprovedRestTestCase(String rpId, String acId, String actionName) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-payment-api
                  adapter: request_response
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/api
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [api_payload, request_response, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  payment_response:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs:
                    payment_payload:
                      ref: fixtures/api/payment_payload.json
                      bind_as: api_payload
                steps:
                  - id: submit_payment
                    action: %s
                    target_ru_id: RU-payment-api
                assertions:
                  - type: file_diff
                    oracle: ${oracles.payment_response}
                evidence_required:
                  - execution_log
                """.formatted(rpId, rpId, acId, acId, expectedResultId, expectedResultId, actionName));
    }

    private void writeApprovedMessagingTestCase(String rpId, String acId) throws Exception {
        writeApprovedMessagingTestCase(rpId, acId, "publish_payment_event");
    }

    private void writeApprovedMessagingTestCase(String rpId, String acId, String actionName) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-payment-events
                  adapter: message_bus
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/events
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [message_event, messaging, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  payment_event:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs:
                    payment_event:
                      ref: fixtures/events/payment_event.json
                      bind_as: message_event
                steps:
                  - id: publish_payment_event
                    action: %s
                    target_ru_id: RU-payment-events
                assertions:
                  - type: file_diff
                    oracle: ${oracles.payment_event}
                evidence_required:
                  - execution_log
                  - message_event
                """.formatted(rpId, rpId, acId, acId, expectedResultId, expectedResultId, actionName));
    }

    private void writeApprovedDeploymentReadinessTestCase(String rpId, String acId) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-payment-k8s
                  adapter: k8s_readiness
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/k8s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [deployment_readiness, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  readiness_result:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs: {}
                steps:
                  - id: check_deployment
                    action: check_readiness
                    target_ru_id: RU-payment-k8s
                assertions:
                  - type: file_diff
                    oracle: ${oracles.readiness_result}
                evidence_required:
                  - execution_log
                  - readiness_result
                """.formatted(rpId, rpId, acId, acId, expectedResultId, expectedResultId));
    }

    private void writeApprovedDbFixtureTestCase(String rpId, String acId) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s-TC-001
                rp_id: %s
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:test
                execution_target:
                  ru_id: RU-order-db
                  adapter: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://order/db
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [db_seed, db_fixture, batch_execution, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  db_fixture_output:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs:
                    orders_seed:
                      ref: fixtures/db/seed_orders.sql
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
                steps:
                  - id: run_db_backed_check
                    action: call_ru
                    target_ru_id: RU-order-db
                assertions:
                  - type: file_diff
                    oracle: ${oracles.db_fixture_output}
                evidence_required:
                  - execution_log
                  - cleanup_result
                """.formatted(rpId, rpId, acId, acId, expectedResultId, expectedResultId));
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

    private int countOrders(String jdbcUrl) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
            result.next();
            return result.getInt(1);
        }
    }
}
