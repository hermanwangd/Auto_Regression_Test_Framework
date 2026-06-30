package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoReadinessReport;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class RegressionCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void commandReturnsUsageErrorForMissingAndUnknownCommand() {
        RegressionCommand command = command();
        ByteArrayOutputStream missingError = new ByteArrayOutputStream();
        ByteArrayOutputStream unknownError = new ByteArrayOutputStream();

        int missingExit = command.execute(new String[] {},
                print(new ByteArrayOutputStream()), print(missingError));
        int unknownExit = command.execute(new String[] {"dance"},
                print(new ByteArrayOutputStream()), print(unknownError));

        assertThat(missingExit).isEqualTo(2);
        assertThat(unknownExit).isEqualTo(2);
        assertThat(missingError.toString()).contains("Missing command.");
        assertThat(unknownError.toString()).contains("Unknown command: dance");
    }

    @Test
    void reportReturnsUsageErrorWhenFormatOptionHasNoValue() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001", "--format"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --format");
    }

    @Test
    void reportReturnsUsageErrorWhenRpIdIsMissing() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--batch-id", "BATCH-001"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --rp-id");
    }

    @Test
    void reportReturnsUsageErrorWhenFormatIsBlank() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001", "--format", ""},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --format");
    }

    @Test
    void reportRequiresBatchIdOrRunId() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --batch-id");
    }

    @Test
    void reportReturnsUsageErrorWhenRunIdOptionHasNoValue() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--run-id"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --run-id");
    }

    @Test
    void initRpRequiresRpIdEvenWhenPackageTypeIsPresent() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--package-type", "service"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --rp-id");
    }

    @Test
    void lifecycleCommandsRequireExplicitReleasePackageOptions() {
        RegressionCommand command = command();
        ByteArrayOutputStream initError = new ByteArrayOutputStream();
        ByteArrayOutputStream checkError = new ByteArrayOutputStream();
        ByteArrayOutputStream generateError = new ByteArrayOutputStream();
        ByteArrayOutputStream expectedResultError = new ByteArrayOutputStream();

        int initExit = command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(new ByteArrayOutputStream()), print(initError));
        int checkExit = command.execute(new String[] {"check-rp", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(checkError));
        int generateExit = command.execute(new String[] {"generate-tests", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(generateError));
        int expectedResultExit = command.execute(new String[] {"draft-expected-results", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(expectedResultError));

        assertThat(initExit).isEqualTo(2);
        assertThat(checkExit).isEqualTo(2);
        assertThat(generateExit).isEqualTo(2);
        assertThat(expectedResultExit).isEqualTo(2);
        assertThat(initError.toString()).contains("Missing required option: --package-type");
        assertThat(checkError.toString()).contains("Missing required option: --rp-id");
        assertThat(generateError.toString()).contains("Missing required option: --rp-id");
        assertThat(expectedResultError.toString()).contains("Missing required option: --rp-id");
    }

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
    void checkReadinessWriteReportIncludesMissingPathGapsWhenRepoIsIncomplete() throws Exception {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "RP-001", "--write-report"},
                print(output), print(new ByteArrayOutputStream()));

        Path report = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/evidence/readiness/readiness.yaml");
        assertThat(exit).as(output.toString()).isEqualTo(1);
        assertThat(output.toString())
                .contains("status: fail")
                .contains("report_path: docs/08-release/release-packages/RP-001/evidence/readiness/readiness.yaml");
        assertThat(Files.readString(report))
                .contains("ready: false")
                .contains("gaps:")
                .contains("path: docs/00-intake-scope")
                .contains("reason: missing_product_repo_path")
                .contains("owner_action: Create required Product Repo path `docs/00-intake-scope`");
    }

    @Test
    void checkReadinessWriteReportHandlesEmptyCheckedItemsFromReadinessProvider() throws Exception {
        ProductRepoService emptyReadinessService = new ProductRepoService() {
            @Override
            public ProductRepoReadinessReport checkReadiness(Path root) {
                return new ProductRepoReadinessReport(
                        true,
                        "pass",
                        List.of(),
                        List.of(),
                        "No readiness items configured.",
                        false,
                        false);
            }
        };
        RegressionCommand command = new RegressionCommand(emptyReadinessService, new ReleasePackageService());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "RP-EMPTY", "--write-report"},
                print(output), print(new ByteArrayOutputStream()));

        Path report = tempDir.resolve(
                "docs/08-release/release-packages/RP-EMPTY/evidence/readiness/readiness.yaml");
        assertThat(exit).isZero();
        assertThat(output.toString()).contains("report_path:");
        assertThat(Files.readString(report))
                .contains("checked_items:\n  []")
                .contains("gaps:\n  []");
    }

    @Test
    void checkReadinessWriteReportReturnsStableErrorWhenReportCannotBeWritten() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        Path blockedEvidencePath = tempDir.resolve(
                "docs/08-release/release-packages/RP-BLOCKED/evidence");
        Files.createDirectories(blockedEvidencePath.getParent());
        Files.writeString(blockedEvidencePath, "not a directory");
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "RP-BLOCKED", "--write-report"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(1);
        assertThat(error.toString())
                .contains("Failed to write readiness report")
                .contains("docs/08-release/release-packages/RP-BLOCKED/evidence/readiness/readiness.yaml");
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
    void checkReadinessWriteReportRequiresNonBlankRpId() {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        ByteArrayOutputStream missingRpOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream blankRpOutput = new ByteArrayOutputStream();

        int missingRpExit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--write-report"},
                print(missingRpOutput), print(new ByteArrayOutputStream()));
        int blankRpExit = command.execute(new String[] {
                "check-readiness", "--root", tempDir.toString(), "--rp-id", "", "--write-report"},
                print(blankRpOutput), print(new ByteArrayOutputStream()));

        assertThat(missingRpExit).isZero();
        assertThat(blankRpExit).isZero();
        assertThat(missingRpOutput.toString()).doesNotContain("report_path:");
        assertThat(blankRpOutput.toString()).doesNotContain("report_path:");
        assertThat(Files.exists(tempDir.resolve(
                "docs/08-release/release-packages/evidence/readiness/readiness.yaml"))).isFalse();
    }

    @Test
    void checkReadinessReturnsFailureWhenProductRepoIsIncomplete() {
        RegressionCommand command = command();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {"check-readiness", "--root", tempDir.toString(), "--format", "yaml"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).as(output.toString()).isEqualTo(1);
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
        assertThat(Files.readString(draft))
                .contains("dsl_version: v0.2")
                .contains("status: draft_skeleton")
                .contains("labels:")
                .contains("package: RP-001")
                .contains("source_refs:")
                .contains("acceptance_criteria: acceptance_criteria.md#RP-001-AC-001")
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("traceability:");
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
    void draftExpectedResultsBlocksIncompleteAcWithoutInventingInputsOrExpectedOutputs() throws Exception {
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
                    title: Missing owner-authored data and oracle
                    owner: product_owner
                    classification: automatable
                    behavior: transform valid orders
                    pass_fail_rule: output matches approved expected output
                    status: ready_for_generation
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "draft-expected-results", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(output), print(new ByteArrayOutputStream()));

        Path draft = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/expected-results/draft/RP-001-ER-001.yaml");
        String draftYaml = Files.readString(draft);
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("expected_result_status: blocked");
        assertThat(draftYaml)
                .contains("status: blocked")
                .contains("input_refs:\n  []")
                .contains("output_ref: pending")
                .contains("input: Clarify owner-authored AC")
                .contains("expected_output: Clarify owner-authored AC")
                .contains("blocked_reason: AC is not ready for expected-result drafting");
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("binding_gaps:");
        assertThat(output.toString()).contains("reason: binding_resolution_failed");
        assertThat(output.toString()).contains("existing_state");
        assertThat(output.toString()).contains("provider_runtime_started: false");
    }

    @Test
    void runDryRunBlocksInvalidExecutionFocusedDslBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeInvalidExecutionFocusedDslTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("dsl_gaps:");
        assertThat(output.toString()).contains("ap: Definition and Validation");
        assertThat(output.toString()).contains("field_path: execute[0].operation");
        assertThat(output.toString()).contains("field_path: execute[0].outputs");
        assertThat(output.toString()).contains("Use operation `run_batch`");
        assertThat(output.toString()).contains("run_status: blocked");
    }

    @Test
    void runUsesGeneratedFrameworkArtifactsWithoutProductMapping() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Files.deleteIfExists(packageRoot.resolve("rp_ru_mapping.yaml"));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeGeneratedRuntimeArtifacts("RP-001", "spring_boot_cli", "RU-transform-job", "adapter-ok", List.of());
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).as(output.toString()).isZero();
        assertThat(Files.exists(packageRoot.resolve("rp_ru_mapping.yaml"))).isFalse();
        assertThat(output.toString())
                .contains("provider_runtime_started: true")
                .contains("provider_contracts_used:")
                .contains("contract_path: generated-framework/provider_contracts/providers.yaml#providers.spring_boot_cli")
                .contains("run_status: passed");
    }

    @Test
    void runIgnoresConflictingProductRpRuLabelsWhenResolvingRuntimeTarget() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeGeneratedRuntimeArtifacts("RP-001", "spring_boot_cli", "RU-transform-job", "adapter-ok", List.of());
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/approved/RP-001-TC-001.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("""
                        labels:
                          package: RP-001
                        """, """
                        labels:
                          product: PROD-wrong
                          package: RP-wrong
                          runtime_unit: RU-wrong
                          team: unrelated
                        """));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString()).contains("run_status: passed");
        assertThat(runEvidence)
                .contains("rp_id: RP-wrong")
                .contains("ru_refs:\n  - RU-transform-job")
                .contains("environment_ref: ci://pipeline/RP-001")
                .contains("affected_ru: RU-transform-job")
                .doesNotContain("RU-wrong");
    }

    @Test
    void runExecutesLegacyExplicitParameterCasesAsSeparateRuns() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedParameterizedTestCase("RP-001");
        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(runOutput), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String runOne = Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"));
        String runTwo = Files.readString(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"));
        assertThat(exit).as(runOutput.toString()).isZero();
        assertThat(runOutput.toString())
                .contains("run_status: passed")
                .contains("run_id: RUN-001")
                .contains("run_id: RUN-002");
        assertThat(runOne)
                .contains("test_case_id: RP-001-TC-001")
                .contains("ac_id: RP-001-AC-001")
                .contains("parameter_case_id: baseline")
                .contains("orders_seed_ref: fixtures/input/orders_seed_baseline.csv");
        assertThat(runTwo)
                .contains("test_case_id: RP-001-TC-001")
                .contains("ac_id: RP-001-AC-001")
                .contains("parameter_case_id: boundary")
                .contains("orders_seed_ref: fixtures/input/orders_seed_boundary.csv");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("parameter_case_id: baseline")
                .contains("parameter_case_id: boundary");

        ByteArrayOutputStream reportOutput = new ByteArrayOutputStream();
        int reportExit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001"},
                print(reportOutput), print(new ByteArrayOutputStream()));

        assertThat(reportExit).isZero();
        assertThat(reportOutput.toString()).contains("report_status: review_ready");
        assertThat(reportOutput.toString()).contains("coverage_percent: 100.0");
        assertThat(Files.readString(packageRoot.resolve("evidence/review/BATCH-001/traceability_report.yaml")))
                .contains("parameter_case_id: baseline")
                .contains("parameter_case_id: boundary");
    }

    @Test
    void runExecutesV02ParameterSetReferenceAsSeparateRuns() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedV02ParameterizedTestCase("RP-001");
        writeApprovedParameterSet("RP-001");
        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(runOutput), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String runOne = Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"));
        String runTwo = Files.readString(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"));
        assertThat(exit).as(runOutput.toString()).isZero();
        assertThat(runOutput.toString())
                .contains("run_status: passed")
                .contains("parameter_case_id: baseline")
                .contains("parameter_case_id: boundary");
        assertThat(runOne)
                .contains("parameter_case_id: baseline")
                .contains("orders_seed_ref: fixtures/input/orders_seed_baseline.csv")
                .doesNotContain("${param.orders_case.orders_seed_ref}");
        assertThat(runTwo)
                .contains("parameter_case_id: boundary")
                .contains("orders_seed_ref: fixtures/input/orders_seed_boundary.csv")
                .doesNotContain("${param.orders_case.orders_seed_ref}");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("parameter_case_id: baseline")
                .contains("parameter_case_id: boundary");
    }

    @Test
    void runDryRunBlocksMalformedV02ParameterSetBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedV02ParameterizedTestCase("RP-001");
        writeMalformedParameterSet("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("binding_gaps:")
                .contains("field_path: parameters.ref.cases[1].case_id")
                .contains("field_path: parameters.ref.cases[1].values.orders_seed_ref")
                .contains("reason: parameter_resolution_failed")
                .contains("owner_action: Use a unique case_id for each reviewed parameter case.")
                .contains("owner_action: Declare a value for parameter reference `${param.orders_case.orders_seed_ref}`.")
                .contains("run_status: blocked");
    }

    @Test
    void runDryRunBlocksMalformedLegacyExplicitParametersBeforeAdapterExecution() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeMalformedParameterizedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("binding_gaps:")
                .contains("ap: Planning and Binding")
                .contains("test_case_id: RP-001-TC-001")
                .contains("ac_id: RP-001-AC-001")
                .contains("field_path: parameters.cases[1].case_id")
                .contains("field_path: parameters.cases[1].values.orders_seed_ref")
                .contains("reason: parameter_resolution_failed")
                .contains("owner_action: Use a unique case_id for each explicit parameter case.")
                .contains("owner_action: Declare a value for parameter reference `${parameters.orders_seed_ref}`.")
                .contains("run_status: blocked");
    }

    @Test
    void runBlocksMalformedLegacyExplicitParametersBeforeParameterExpansion() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeMalformedParameterizedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("run_id: RUN-001")
                .doesNotContain("run_id: RUN-002")
                .contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("run_id: RUN-001")
                .doesNotContain("RUN-002")
                .doesNotContain("parameter_case_id:");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("provider_runtime_started: false")
                .doesNotContain("parameter_case_id: baseline");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("field_path: parameters.cases[1].case_id")
                .contains("field_path: parameters.cases[1].values.orders_seed_ref")
                .contains("reason: parameter_resolution_failed");
    }

    @Test
    void runMarksBatchBlockedWhenEverySelectedTestHasPreflightFailure() throws Exception {
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
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "existing_state");
        writeApprovedTestCase("RP-001", "RP-001-TC-002", "RP-001-AC-002", "existing_state");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("run_id: RUN-001")
                .contains("run_id: RUN-002")
                .contains("status: blocked")
                .contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("status: blocked")
                .contains("RUN-001")
                .contains("RUN-002");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("reason: binding_resolution_failed");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-002/failure_details.yaml")))
                .contains("reason: binding_resolution_failed");
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("provider_runtime_started: false");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(runEvidence).contains("rp_id: RP-001");
        assertThat(runEvidence).contains("test_case_id: RP-001-TC-001");
        assertThat(runEvidence).contains("ac_id: RP-001-AC-001");
        assertThat(runEvidence).contains("status: blocked");
        assertThat(runEvidence).contains("provider_runtime_started: false");
        assertThat(runEvidence).contains("execution_mode: ci_ephemeral");
        assertThat(runEvidence).contains("environment_ref: ci://pipeline/RP-001");
        assertThat(failureDetails).contains("reason: binding_resolution_failed");
        assertThat(failureDetails).contains("package_inputs.inputs.orders_seed.bind_as");
        assertThat(failureDetails).contains("existing_state");
    }

    @Test
    void blockedExecutionFocusedDslV02RunPreservesDslRuntimeEvidence() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001", "existing_state");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        String failureDetails = Files.readString(runDir.resolve("failure_details.yaml"));
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(runEvidence)
                .contains("status: blocked")
                .contains("provider_runtime_started: false")
                .contains("dsl_runtime:")
                .contains("dsl_version: v0.2")
                .contains("target_id: RU-transform-job")
                .contains("fixture_name: orders_seed")
                .contains("type: existing_state")
                .contains("operation: run_batch")
                .contains("expected_results:")
                .contains("verify_rules:")
                .contains("runtime:");
        assertThat(failureDetails)
                .contains("reason: binding_resolution_failed")
                .contains("setup.fixtures.orders_seed.type")
                .contains("existing_state");
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
    void blockedPerTestPreflightRunSkipsExistingRunIdWhenAssigningEvidence() throws Exception {
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
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "api_payload");
        writeApprovedTestCase("RP-001", "RP-001-TC-002", "RP-001-AC-002", "api_payload");
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Files.createDirectories(packageRoot.resolve("evidence/runs/RUN-002"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("run_id: RUN-001")
                .contains("run_id: RUN-003")
                .doesNotContain("run_id: RUN-002");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("test_case_id: RP-001-TC-001");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-003/run.yaml")))
                .contains("status: blocked")
                .contains("test_case_id: RP-001-TC-002");
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("provider_contracts/RU-transform-job.yaml#providers.spring_boot_cli");
        assertThat(output.toString()).contains("provider_contracts/RU-transform-job.yaml#bindings.db_seed");
        assertThat(output.toString()).contains("reason: provider_contract_resolution_failed");
        assertThat(output.toString()).contains("registry_status: missing");
        assertThat(output.toString()).contains("affected_ru: RU-transform-job");
        assertThat(output.toString()).contains("capability: spring_boot_cli");
        assertThat(output.toString()).contains("capability: db_seed");
        assertThat(output.toString()).contains("provider_runtime_started: false");
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.spring_boot_cli.outputs.actual_output_ref");
        assertThat(output.toString()).contains("provider_contract_kind: file_batch");
        assertThat(output.toString()).contains("provider_type: shell");
        assertThat(output.toString()).contains("Declare actual_output_ref for executable provider `spring_boot_cli`");
        assertThat(output.toString()).contains("provider_runtime_started: false");
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("ap: Planning and Binding");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.spring_boot_cli.timeout_seconds");
        assertThat(output.toString()).contains("provider_contract_kind: file_batch");
        assertThat(output.toString()).contains("provider_type: shell");
        assertThat(output.toString()).contains("Declare timeout_seconds as a positive bounded integer");
        assertThat(output.toString()).contains("provider_runtime_started: false");
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("fixture_gaps:");
        assertThat(output.toString()).contains("ap: Fixture and State Manager");
        assertThat(output.toString()).contains("fixture.cleanup");
        assertThat(output.toString()).contains("policy.cleanup_required");
        assertThat(output.toString()).contains("provider_runtime_started: false");
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
        writeApprovedTestCaseWithOracleType("RP-001", "invariant");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("oracle_gaps:");
        assertThat(output.toString()).contains("ap: Oracle and Assertion Engine");
        assertThat(output.toString()).contains("oracles.normalized_orders.type");
        assertThat(output.toString()).contains("invariant");
        assertThat(output.toString()).contains("provider_runtime_started: false");
    }

    @Test
    void runDryRunReportsProviderGapWhenApprovedTestReferencesUnmappedAdapter() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeCompleteCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/approved/RP-001-TC-001.yaml");
        Files.writeString(testCase, Files.readString(testCase).replace(
                "provider: spring_boot_cli",
                "provider: missing_adapter"));
        assertThat(Files.readString(testCase)).contains("provider: missing_adapter");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).as(output.toString()).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_contract_gaps:")
                .contains("provider_name: missing_adapter")
                .contains("contract_path: generated-framework/environment_bindings.targets")
                .contains("Generate environment binding target for `RU-transform-job` before execution.")
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(runEvidence).contains("status: blocked");
        assertThat(runEvidence).contains("execution_mode: sit_deployed");
        assertThat(runEvidence).contains("environment_ref: sit://payment/k8s");
        assertThat(batchEvidence).contains("status: blocked");
        assertThat(batchEvidence).contains("execution_mode: sit_deployed");
        assertThat(failureDetails).contains("ap: Planning and Binding");
        assertThat(failureDetails).contains("field_path: release_units[0].provider_contracts.providers.k8s_readiness.deployed_version_ref");
        assertThat(failureDetails).contains("provider_contract_kind: deployment_readiness");
        assertThat(failureDetails).contains("provider_type: local");
        assertThat(failureDetails).contains("owner_action: Declare deployed_version_ref for `k8s_readiness` before execution.");
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
        assertThat(output.toString()).contains("provider_runtime_started: true");
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
        assertThat(runEvidence).contains("contract_path: release_units[0].provider_contracts.providers.spring_boot_cli");
        assertThat(runEvidence).contains("contract_path: release_units[0].provider_contracts.bindings.db_seed");
        assertThat(runEvidence).contains("provider_contract_kind: file_batch");
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
        assertThat(output.toString()).contains("provider_runtime_started: true");
        assertThat(output.toString()).contains("run_status: passed");
        String runEvidence = Files.readString(runDir.resolve("run.yaml"));
        assertThat(runEvidence).contains("provider_contract_kind: external_runner");
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
        assertThat(output.toString()).contains("provider_runtime_started: true");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.external_runner.built_in_provider_alternative");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.external_runner.outputs.actual_output_ref");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.external_runner.evidence_map.runner_stdout");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: external_runner");
        assertThat(output.toString()).contains("provider_type: command_runner");
        assertThat(output.toString()).contains("registry_status: unapproved_escape_hatch");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.external_runner.timeout_seconds");
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
                .contains("contract_path: release_units[1].provider_contracts.providers.spring_boot_cli")
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
                .contains("contract_path: release_units[1].provider_contracts.providers.spring_boot_cli")
                .contains("contract_path: release_units[1].provider_contracts.bindings.db_seed")
                .contains("affected_ru: RU-downstream-job");
        assertThat(runEvidence)
                .doesNotContain("contract_path: release_units[0].provider_contracts.providers.spring_boot_cli")
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
                .contains("provider_runtime_started: true");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/logs/stdout.log")))
                .contains("adapter-ok");
        assertThat(Files.readString(blockedRun))
                .contains("test_case_id: RP-001-TC-002")
                .contains("status: blocked")
                .contains("provider_runtime_started: false")
                .contains("failure_details: failure_details.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-002/failure_details.yaml")))
                .contains("generated-framework/provider_contracts/RU-missing-provider-job.yaml#providers.missing_cli")
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
                .contains("provider_runtime_started: false")
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
            assertThat(output.toString()).contains("provider_runtime_started: true");
            assertThat(Files.readString(runDir.resolve("actual/response.json")))
                    .isEqualTo("{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
            assertThat(runEvidence)
                    .contains("status: passed")
                    .contains("binding_type: api_payload")
                    .contains("provider_contract_kind: request_response")
                    .contains("contract_path: release_units[0].provider_contracts.providers.request_response")
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.request_response.actions.authorize_payment");
        assertThat(output.toString()).contains("Declare request/response action `authorize_payment` before invocation");
    }

    @Test
    void runDryRunAllowsRestProviderContextCheckWhenActionsSectionIsAbsent() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-NO-ACTIONS";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeRestProviderMapping(rpId, 65535);
        Path generatedContract = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId
                        + "/generated-framework/provider_contracts/RU-payment-api.yaml");
        Files.writeString(generatedContract, Files.readString(generatedContract)
                .replace("actions:", "omitted_actions:"));
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_contract_gaps:")
                .contains("provider_contract_kind: request_response")
                .contains("run_status: blocked");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.request_response.actions.submit_payment.request_binding");
        assertThat(output.toString()).contains("Add package input binding `missing_payload` before invoking request/response action `submit_payment`");
    }

    @Test
    void runDryRunBlocksRestProviderWithBlankRequestBindingBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-BLANK-BINDING";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeRestProviderMapping(rpId, 65535, "submit_payment", "");
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.request_response.actions.submit_payment.request_binding");
        assertThat(output.toString()).contains("Declare request_binding for request/response action `submit_payment`");
    }

    @Test
    void runDryRunBlocksGrpcProviderWithMissingPayloadBindingBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-GRPC-BINDING";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeGrpcProviderMapping(rpId, "submit_payment", "missing_payload");
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: request_response");
        assertThat(output.toString()).contains("provider_type: grpc");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.request_response.actions.submit_payment.request_binding");
        assertThat(output.toString()).contains("Add package input binding `missing_payload` before invoking request/response action `submit_payment`");
    }

    @Test
    void runDryRunBlocksRestProviderWithoutTimeoutBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-TIMEOUT";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeRestProviderMappingWithoutTimeout(rpId, 65535);
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.request_response.timeout_seconds");
        assertThat(output.toString()).contains("Declare timeout_seconds as a positive bounded integer for request/response provider `request_response`");
    }

    @Test
    void runDryRunBlocksRestProviderWithoutOutputRefBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-OUTPUT";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeRestProviderMappingWithoutOutputRef(rpId, 65535);
        writeRestPayload(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n");
        writeApprovedRestTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: request_response");
        assertThat(output.toString()).contains("provider_type: rest");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.request_response.outputs.actual_output_ref");
        assertThat(output.toString()).contains("Declare actual_output_ref for request/response provider `request_response`");
    }

    @Test
    void runDryRunAllowsRestProviderContextCheckWhenLegacyStepsAreNotAList() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-REST-NO-STEPS";
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
        writeApprovedRestTestCase(rpId, acId);
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("""
                        steps:
                          - id: submit_payment
                            action: submit_payment
                            target_ru_id: RU-payment-api
                        """, """
                        steps: {}
                        """));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString())
                .contains("provider_contracts_used:")
                .contains("provider_contract_kind: request_response")
                .contains("run_status: dry_run_ready");
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
                .contains("provider_contract_kind: messaging")
                .contains("contract_path: release_units[0].provider_contracts.providers.message_bus")
                .contains("actual_output: actual/message.json")
                .contains("assertion_status: passed")
                .contains("provider_evidence:")
                .contains("messaging: messaging.yaml");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithoutTimeoutBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-TIMEOUT";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMappingWithoutTimeout(rpId);
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.message_bus.timeout_seconds");
        assertThat(output.toString()).contains("Declare timeout_seconds as a positive bounded integer for messaging provider `message_bus`");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithoutOutputRefBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-OUTPUT";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMappingWithoutOutputRef(rpId);
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.message_bus.outputs.actual_output_ref");
        assertThat(output.toString()).contains("Declare actual_output_ref for messaging provider `message_bus`");
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
                .contains("contract_path: release_units[0].provider_contracts.providers.message_bus.bootstrap_servers_ref")
                .contains("provider_contract_kind: messaging")
                .contains("provider_type: kafka")
                .contains("registry_status: incomplete")
                .contains("Declare bootstrap_servers_ref or connection_ref");
        assertThat(Files.exists(runDir.resolve("messaging.yaml"))).isFalse();
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: blocked")
                .contains("provider_runtime_started: false");
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
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_contract_kind: messaging")
                .contains("provider_type: local")
                .contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.payload_binding")
                .contains("Add package input binding `missing_event` before invoking messaging action `publish_payment_event`");
        assertThat(Files.exists(runDir.resolve("messaging.yaml"))).isFalse();
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: blocked")
                .contains("provider_runtime_started: false");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithoutPayloadBindingBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-NO-PAYLOAD-BINDING";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "");
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_contract_kind: messaging")
                .contains("provider_type: local")
                .contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.payload_binding")
                .contains("Declare payload_binding, message_binding, or event_binding for messaging action `publish_payment_event`");
    }

    @Test
    void runDryRunTreatsMissingMessagingModeAsPublishAndRequiresPayloadBinding() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-DEFAULT-MODE";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "");
        Path generatedContract = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId
                        + "/generated-framework/provider_contracts/RU-payment-events.yaml");
        Files.writeString(generatedContract, Files.readString(generatedContract)
                .replace("                              mode: publish\n", ""));
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_contract_kind: messaging")
                .contains("provider_type: local")
                .contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.payload_binding")
                .contains("Declare payload_binding, message_binding, or event_binding for messaging action `publish_payment_event`");
    }

    @Test
    void runDryRunAllowsMessagingObservationWithoutPayloadBinding() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-OBSERVE";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingObservationProviderMapping(rpId);
        writeApprovedExpectedResult(rpId, acId, "{\"eventId\":\"EVT-OBS-001\"}\n");
        writeApprovedMessagingObservationTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: dry_run_ready");
        assertThat(output.toString()).doesNotContain("payload_binding");
    }

    @Test
    void runDryRunAllowsMessagingCleanupWithoutPayloadBinding() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-CLEANUP";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingCleanupProviderMapping(rpId);
        writeApprovedExpectedResult(rpId, acId, "");
        writeApprovedMessagingCleanupTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: dry_run_ready");
        assertThat(output.toString()).doesNotContain("payload_binding");
    }

    @Test
    void runDryRunAllowsMessagingCleanupWhenMaxCountIsStringPositiveInteger() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-CLEANUP-STRING-MAX";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingCleanupProviderMapping(rpId);
        Path generatedContract = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId
                        + "/generated-framework/provider_contracts/RU-payment-events.yaml");
        Files.writeString(generatedContract, Files.readString(generatedContract)
                .replace("                              max_count: 25\n", "                              max_count: \"25\"\n"));
        writeApprovedExpectedResult(rpId, acId, "");
        writeApprovedMessagingCleanupTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: dry_run_ready");
        assertThat(output.toString()).doesNotContain(".actions.cleanup_payment_event.max_count");
    }

    @Test
    void runDryRunBlocksMessagingCleanupWhenStrategyIsMissing() throws Exception {
        assertMessagingCleanupPreflightGap(
                "RP-MSG-CLEANUP-MISSING-STRATEGY",
                content -> content
                        .replaceAll("(?m)^\\s*cleanup_strategy: drain\\R", "")
                        .replace("cleanup_strategy: drain, ", "")
                        .replace(", cleanup_strategy: drain", ""),
                ".actions.cleanup_payment_event.cleanup_strategy",
                "Declare cleanup_strategy for messaging cleanup action `cleanup_payment_event` before invocation.");
    }

    @Test
    void runDryRunBlocksMessagingCleanupWhenStrategyIsUnsupported() throws Exception {
        assertMessagingCleanupPreflightGap(
                "RP-MSG-CLEANUP-BAD-STRATEGY",
                content -> content.replace("cleanup_strategy: drain", "cleanup_strategy: purge"),
                ".actions.cleanup_payment_event.cleanup_strategy",
                "Use supported messaging cleanup_strategy `drain` before invoking messaging cleanup action `cleanup_payment_event`.");
    }

    @Test
    void runDryRunBlocksMessagingCleanupWhenMaxCountIsMissing() throws Exception {
        assertMessagingCleanupPreflightGap(
                "RP-MSG-CLEANUP-MISSING-MAX",
                content -> content
                        .replaceAll("(?m)^\\s*max_count: 25\\R", "")
                        .replace("max_count: 25, ", "")
                        .replace(", max_count: 25", ""),
                ".actions.cleanup_payment_event.max_count",
                "Declare max_count as a positive bounded integer for messaging cleanup action `cleanup_payment_event` before invocation.");
    }

    @Test
    void runDryRunBlocksMessagingCleanupWhenMaxCountIsNotNumeric() throws Exception {
        assertMessagingCleanupPreflightGap(
                "RP-MSG-CLEANUP-BAD-MAX",
                content -> content.replace("max_count: 25", "max_count: many"),
                ".actions.cleanup_payment_event.max_count",
                "Declare max_count as a positive bounded integer for messaging cleanup action `cleanup_payment_event` before invocation.");
    }

    @Test
    void runDryRunAllowsNativeMessagingRequestReplyWithPayloadBinding() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-REQUEST-REPLY";
        String acId = rpId + "-AC-001";
        String payload = "{\"requestId\":\"REQ-001\",\"status\":\"accepted\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingRequestReplyProviderMapping(rpId);
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId, "request_payment_status");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: dry_run_ready");
        assertThat(output.toString()).doesNotContain("Use supported messaging action mode");
        assertThat(output.toString()).doesNotContain("Add package input binding `payment_event`");
    }

    @Test
    void runDryRunAllowsNativeMessagingRequestReplyHyphenModeAlias() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-REQUEST-REPLY-HYPHEN";
        String acId = rpId + "-AC-001";
        String payload = "{\"requestId\":\"REQ-001\",\"status\":\"accepted\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingRequestReplyProviderMapping(rpId);
        Path generatedContract = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId
                        + "/generated-framework/provider_contracts/RU-payment-events.yaml");
        Files.writeString(generatedContract, Files.readString(generatedContract)
                .replace("                              mode: request_reply\n",
                        "                              mode: request-reply\n"));
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId, "request_payment_status");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: dry_run_ready");
        assertThat(output.toString()).doesNotContain("Use supported messaging action mode");
        assertThat(output.toString()).doesNotContain("Add package input binding `payment_event`");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.consume_payment_event");
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
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.serialization");
        assertThat(output.toString()).contains("Use supported messaging serialization `json` before invoking messaging action `publish_payment_event`");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithUnsupportedModeBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-MODE";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId);
        Path generatedContract = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId
                        + "/generated-framework/provider_contracts/RU-payment-events.yaml");
        Files.writeString(generatedContract, Files.readString(generatedContract)
                .replace("mode: publish", "mode: stream"));
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.mode");
        assertThat(output.toString()).contains("Use supported messaging action mode `publish`, `request_reply`, `consume`, `observe`, or `cleanup`");
    }

    @Test
    void runDryRunBlocksMessagingProviderWithMissingRequiredCorrelationBeforeInvocation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-CORRELATION";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMappingRequiringCorrelationWithoutId(rpId);
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: messaging");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.correlation_id_ref");
        assertThat(output.toString()).contains("Declare correlation_id, correlation_id_ref, or correlation_key");
    }

    @Test
    void runDryRunTreatsStringYesAsRequiredMessagingCorrelation() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-MSG-CORRELATION-YES";
        String acId = rpId + "-AC-001";
        String payload = "{\"eventId\":\"EVT-001\",\"status\":\"published\"}\n";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "payment_event",
                "publish_payment_event", "", "\n                              requires_correlation: yes");
        writeMessagingEventPayload(rpId, payload);
        writeApprovedExpectedResult(rpId, acId, payload);
        writeApprovedMessagingTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_contract_kind: messaging")
                .contains("provider_type: local")
                .contains("contract_path: release_units[0].provider_contracts.providers.message_bus.actions.publish_payment_event.correlation_id_ref")
                .contains("Declare correlation_id, correlation_id_ref, or correlation_key");
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
                .contains("deployed_version_ref: deploy-123")
                .contains("check_count: 1");
        assertThat(Files.readString(runDir.resolve("actual/readiness.txt"))).isEqualTo("ready\n");
        assertThat(runEvidence)
                .contains("status: passed")
                .contains("provider_contract_kind: deployment_readiness")
                .contains("contract_path: release_units[0].provider_contracts.providers.k8s_readiness")
                .contains("actual_output: actual/readiness.txt")
                .contains("assertion_status: passed");
    }

    @Test
    void runDryRunBlocksDeploymentReadinessWithoutVersionRefBeforeExecution() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-READY-VERSION";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "deployment"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDeploymentReadinessMappingWithoutVersionRef(rpId);
        writeDeploymentReadyMarker(rpId);
        writeApprovedExpectedResult(rpId, acId, "ready\n");
        writeApprovedDeploymentReadinessTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: deployment_readiness");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.k8s_readiness.deployed_version_ref");
        assertThat(output.toString()).contains("Declare deployed_version_ref for `k8s_readiness`");
    }

    @Test
    void runDryRunBlocksDeploymentReadinessWithoutTimeoutBeforeExecution() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-READY-TIMEOUT";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "deployment"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDeploymentReadinessMappingWithoutTimeout(rpId);
        writeDeploymentReadyMarker(rpId);
        writeApprovedExpectedResult(rpId, acId, "ready\n");
        writeApprovedDeploymentReadinessTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: deployment_readiness");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.k8s_readiness.timeout_seconds");
        assertThat(output.toString()).contains("Declare timeout_seconds as a positive bounded integer for deployment readiness provider `k8s_readiness`");
    }

    @Test
    void runDryRunBlocksDeploymentReadinessWithoutOutputRefBeforeExecution() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-READY-OUTPUT";
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "deployment"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDeploymentReadinessMappingWithoutOutputRef(rpId);
        writeDeploymentReadyMarker(rpId);
        writeApprovedExpectedResult(rpId, acId, "ready\n");
        writeApprovedDeploymentReadinessTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: deployment_readiness");
        assertThat(output.toString()).contains("provider_type: local");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.providers.k8s_readiness.outputs.actual_output_ref");
        assertThat(output.toString()).contains("Declare actual_output_ref for deployment readiness provider `k8s_readiness`");
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
                .contains("provider_contract_kind: deployment_readiness")
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
                .contains("isolation_key: test_run_id")
                .contains("row_count: 1");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml")))
                .contains("provider: relational_db")
                .contains("action: cleanup_orders")
                .contains("isolation_key: test_run_id")
                .contains("status: passed");
        assertThat(countOrders(jdbcUrl)).isZero();
        assertThat(runEvidence)
                .contains("status: passed")
                .contains("provider_contract_kind: db_fixture")
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
    void runDryRunBlocksDbFixtureWithInlineVerificationSqlBeforeSetup() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-DB-INLINE-SQL";
        String acId = rpId + "-AC-001";
        String jdbcUrl = "jdbc:h2:mem:rp_db_fixture_inline_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_db"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDbFixtureMappingWithInlineVerificationSql(rpId, jdbcUrl);
        writeDbFixtureSql(rpId);
        writeApprovedExpectedResult(rpId, acId, "db-fixture-ok\n");
        writeApprovedDbFixtureTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: db_fixture");
        assertThat(output.toString()).contains("provider_type: jdbc");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.fixtures.relational_db.verification_queries.seeded_orders.sql");
        assertThat(output.toString()).contains("Move DB fixture verification SQL for `seeded_orders` into sql_ref");
    }

    @Test
    void runDryRunBlocksDbFixtureWithoutIsolationKeyBeforeSetup() throws Exception {
        RegressionCommand command = command();
        String rpId = "RP-DB-ISOLATION";
        String acId = rpId + "-AC-001";
        String jdbcUrl = "jdbc:h2:mem:rp_db_fixture_isolation_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_db"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeDbFixtureMappingWithoutIsolationKey(rpId, jdbcUrl);
        writeDbFixtureSql(rpId);
        writeApprovedExpectedResult(rpId, acId, "db-fixture-ok\n");
        writeApprovedDbFixtureTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("provider_runtime_started: false");
        assertThat(output.toString()).contains("run_status: blocked");
        assertThat(output.toString()).contains("provider_contract_gaps:");
        assertThat(output.toString()).contains("provider_contract_kind: db_fixture");
        assertThat(output.toString()).contains("provider_type: jdbc");
        assertThat(output.toString()).contains("contract_path: release_units[0].provider_contracts.fixtures.relational_db.isolation_key");
        assertThat(output.toString()).contains("Declare isolation_key for DB fixture `relational_db` before setup.");
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
    void runSkipsExistingRunIdWhenAssigningMultipleExecutionRuns() throws Exception {
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
        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Files.createDirectories(packageRoot.resolve("evidence/runs/RUN-002"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("run_id: RUN-001")
                .contains("run_id: RUN-003")
                .doesNotContain("run_id: RUN-002");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"))).isTrue();
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-003/run.yaml")))
                .contains("test_case_id: RP-001-TC-002");
    }

    @Test
    void runWithTestCaseOptionExecutesOnlySelectedApprovedTest() throws Exception {
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
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--test-case", "RP-001-TC-002"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String batchEvidence = Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"));
        String runEvidence = Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"));
        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("test_case_id: RP-001-TC-002")
                .doesNotContain("test_case_id: RP-001-TC-001");
        assertThat(batchEvidence)
                .contains("run_id: RUN-001")
                .contains("test_case_id: RP-001-TC-002")
                .doesNotContain("RUN-002")
                .doesNotContain("test_case_id: RP-001-TC-001");
        assertThat(runEvidence)
                .contains("test_case_id: RP-001-TC-002")
                .contains("ac_id: RP-001-AC-002");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
    }

    @Test
    void runWithTagOptionExecutesOnlyMatchingApprovedTests() throws Exception {
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
        Path taggedTest = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/approved/RP-001-TC-001.yaml");
        Files.writeString(taggedTest, Files.readString(taggedTest)
                .replace("revision: 1\n", "revision: 1\ntags: [smoke, ci]\n"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--tag", "smoke"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String batchEvidence = Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"));
        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("test_case_id: RP-001-TC-001")
                .doesNotContain("test_case_id: RP-001-TC-002");
        assertThat(batchEvidence)
                .contains("test_case_id: RP-001-TC-001")
                .doesNotContain("test_case_id: RP-001-TC-002");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
    }

    @Test
    void runWithTagOptionIgnoresBlankTagEntries() throws Exception {
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
        Path taggedTest = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/approved/RP-001-TC-001.yaml");
        Files.writeString(taggedTest, Files.readString(taggedTest)
                .replace("revision: 1\n", "revision: 1\ntags: [\"\", smoke]\n"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--tag", "smoke"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String batchEvidence = Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"));
        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("test_case_id: RP-001-TC-001")
                .doesNotContain("test_case_id: RP-001-TC-002");
        assertThat(batchEvidence)
                .contains("test_case_id: RP-001-TC-001")
                .doesNotContain("test_case_id: RP-001-TC-002");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
    }

    @Test
    void runWithSuiteOptionExecutesOnlyTestsListedBySuiteManifest() throws Exception {
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
        Path suiteManifest = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/suite_manifest.yaml");
        Files.writeString(suiteManifest, """
                suite_id: smoke-suite
                tests:
                  - ""
                  - tests/approved/RP-001-TC-002
                coverage_source_ref: acceptance_criteria.md
                traceability_map_ref: traceability_map.yaml
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--suite", "smoke-suite"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String batchEvidence = Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"));
        String runEvidence = Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"));
        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("test_case_id: RP-001-TC-002")
                .doesNotContain("test_case_id: RP-001-TC-001");
        assertThat(batchEvidence)
                .contains("test_case_id: RP-001-TC-002")
                .doesNotContain("test_case_id: RP-001-TC-001");
        assertThat(runEvidence).contains("test_case_id: RP-001-TC-002");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
    }

    @Test
    void runWithSuiteOptionAcceptsYamlManifestTestPaths() throws Exception {
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
        Path suiteManifest = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/suite_manifest.yaml");
        Files.writeString(suiteManifest, """
                suite_id: smoke-suite
                tests:
                  - tests/approved/RP-001-TC-001.yaml
                coverage_source_ref: acceptance_criteria.md
                traceability_map_ref: traceability_map.yaml
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--suite", "smoke-suite"},
                print(output), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        String batchEvidence = Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml"));
        String runEvidence = Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"));
        assertThat(exit).as(output.toString()).isZero();
        assertThat(output.toString())
                .contains("test_case_id: RP-001-TC-001")
                .doesNotContain("test_case_id: RP-001-TC-002");
        assertThat(batchEvidence)
                .contains("test_case_id: RP-001-TC-001")
                .doesNotContain("test_case_id: RP-001-TC-002");
        assertThat(runEvidence).contains("test_case_id: RP-001-TC-001");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs/RUN-002/run.yaml"))).isFalse();
    }

    @Test
    void runDryRunReportsSelectionGapWhenTagMatchesNoApprovedTests() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        Files.deleteIfExists(tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/suite_manifest.yaml"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--tag", "missing-tag", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("field_path: run.selection.tag")
                .contains("reason: suite_selection_failed")
                .contains("Select a tag that matches at least one approved DSL test case.")
                .contains("run_status: blocked")
                .doesNotContain("field_path: tests/approved");
    }

    @Test
    void runDryRunReportsSelectionGapWhenTestCaseMatchesNoApprovedTests() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--test-case", "RP-001-TC-404", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("field_path: run.selection.test_case")
                .contains("reason: suite_selection_failed")
                .contains("Select a test case ID that matches one approved DSL test case.")
                .contains("run_status: blocked")
                .doesNotContain("field_path: tests/approved");
    }

    @Test
    void runDryRunReportsSelectionGapWhenSuiteMatchesNoApprovedTests() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        Path suiteManifest = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/suite_manifest.yaml");
        Files.writeString(suiteManifest, """
                suite_id: other-suite
                tests:
                  - tests/approved/RP-001-TC-001.yaml
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--suite", "smoke-suite", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("field_path: run.selection.suite")
                .contains("reason: suite_selection_failed")
                .contains("Select a suite ID whose generated suite manifest lists at least one approved DSL test case.")
                .contains("run_status: blocked")
                .doesNotContain("field_path: tests/approved");
    }

    @Test
    void runDryRunReportsSelectionGapWhenSuiteManifestTestsIsNotAList() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        Path suiteManifest = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/suite_manifest.yaml");
        Files.writeString(suiteManifest, """
                suite_id: smoke-suite
                tests: tests/approved/RP-001-TC-001.yaml
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--suite", "smoke-suite", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("field_path: run.selection.suite")
                .contains("reason: suite_selection_failed")
                .contains("run_status: blocked");
    }

    @Test
    void runDryRunReportsSelectionGapWhenSuiteManifestIsMissing() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCase("RP-001", "RP-001-TC-001", "RP-001-AC-001", "db_seed");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral",
                "--suite", "smoke-suite", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("field_path: run.selection.suite")
                .contains("reason: suite_selection_failed")
                .contains("Select a suite ID whose generated suite manifest lists at least one approved DSL test case.")
                .contains("run_status: blocked");
    }

    @Test
    void runRequiresExplicitEnvOption() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --env");
    }

    @Test
    void runRequiresExplicitRpIdEvenWhenEnvIsPresent() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "--env", "ci_ephemeral"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --rp-id");
    }

    @Test
    void runDryRunBlocksWhenApprovedTestYamlIsNotAMap() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteriaForAcs("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        Path approvedDir = tempDir.resolve("docs/08-release/release-packages/RP-001/tests/approved");
        Files.createDirectories(approvedDir);
        Files.writeString(approvedDir.resolve("RP-001-TC-001.yaml"), "[]\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("field_path: dsl")
                .contains("reason: definition_validation_failed")
                .contains("Provide a YAML mapping for the DSL test case.")
                .contains("run_status: blocked");
    }

    @Test
    void runReturnsUsageErrorWhenEnvOptionHasNoValue() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --env");
    }

    @Test
    void runDryRunBlocksWhenApprovedTestDirectoryIsMissing() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("field_path: tests/approved")
                .contains("Add approved_for_regression DSL test cases before run.")
                .contains("run_status: blocked");
        assertThat(Files.exists(tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/approved"))).isFalse();
    }

    @Test
    void runWritesBlockedEvidenceWhenApprovedDslHasNoExecutionTarget() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedTestCaseWithoutExecutionTarget("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(output), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked")
                .contains("field_path: targets")
                .contains("field_path: execute[0].target");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: blocked")
                .contains("provider_runtime_started: false");
        assertThat(Files.readString(runDir.resolve("failure_details.yaml")))
                .contains("field_path: targets")
                .contains("field_path: execute[0].target")
                .contains("test_case_id: RP-001-TC-001")
                .contains("ac_id: RP-001-AC-001");
    }

    @Test
    void runDryRunBlocksExecutionFocusedTestWhenSelectedProfileIsNotCompatible() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/tests/approved/RP-001-TC-001.yaml");
        Files.writeString(testCase, Files.readString(testCase)
                .replace("revision: 1\n", "revision: 1\ncompatible_profiles: [sit_deployed]\n"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("dsl_gaps:")
                .contains("test_case_id: RP-001-TC-001")
                .contains("field_path: compatible_profiles")
                .contains("reason: suite_selection_failed")
                .contains("Select a compatible run profile or update compatible_profiles before execution.")
                .contains("run_status: blocked");
    }

    @Test
    void runDryRunBlocksWhenGeneratedEnvironmentBindingTargetOmitsEnvironmentRef() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeGeneratedRuntimeArtifacts("RP-001", "spring_boot_cli", "RU-transform-job", "adapter-ok", List.of());
        Path environmentBinding = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/environment_bindings/ci_ephemeral.yaml");
        Files.writeString(environmentBinding, Files.readString(environmentBinding)
                .replace("    environment_ref: ci://pipeline/RP-001\n", ""));
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("environment_gaps:")
                .contains("field_path: generated-framework/environment_bindings.targets.RU-transform-job.environment_ref")
                .contains("reason: environment_readiness_failed")
                .contains("Generate environment_ref for target `RU-transform-job` before execution.")
                .contains("run_status: blocked");
    }

    @Test
    void runDryRunBlocksWhenGeneratedRunProfileOmitsRequiredField() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeGeneratedRuntimeArtifacts("RP-001", "spring_boot_cli", "RU-transform-job", "adapter-ok", List.of());
        Path runProfile = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/run_profiles/ci_ephemeral.yaml");
        Files.writeString(runProfile, Files.readString(runProfile)
                .replace("max_duration: PT10M\n", ""));
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("environment_gaps:")
                .contains("field_path: generated-framework/run_profiles/ci_ephemeral.yaml#max_duration")
                .contains("reason: environment_readiness_failed")
                .contains("Declare run profile max_duration before execution.")
                .contains("run_status: blocked");
    }

    @Test
    void runDryRunBlocksWhenGeneratedRunProfileUsesUnsupportedExecutionMode() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeGeneratedRuntimeArtifacts("RP-001", "spring_boot_cli", "RU-transform-job", "adapter-ok", List.of());
        Path runProfile = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/run_profiles/ci_ephemeral.yaml");
        Files.writeString(runProfile, Files.readString(runProfile)
                .replace("execution_mode: ci_ephemeral\n", "execution_mode: unsafe_prod\n"));
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("environment_gaps:")
                .contains("field_path: generated-framework/run_profiles/ci_ephemeral.yaml#execution_mode")
                .contains("reason: environment_readiness_failed")
                .contains("Use supported execution_mode for run profile `ci_ephemeral`.")
                .contains("run_status: blocked");
    }

    @Test
    void runDryRunBlocksSitProfileWhenEnvironmentBindingOmitsReadinessRef() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeGeneratedRuntimeArtifacts("RP-001", "spring_boot_cli", "RU-transform-job", "adapter-ok", List.of());
        Path runProfile = tempDir.resolve(
                "docs/08-release/release-packages/RP-001/generated-framework/run_profiles/ci_ephemeral.yaml");
        Files.writeString(runProfile, Files.readString(runProfile)
                .replace("execution_mode: ci_ephemeral\n", "execution_mode: sit_deployed\n"));
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("environment_gaps:")
                .contains("field_path: generated-framework/environment_bindings.targets.RU-transform-job.readiness_ref")
                .contains("reason: environment_readiness_failed")
                .contains("Declare readiness_ref for target `RU-transform-job` before sit_deployed execution.")
                .contains("run_status: blocked");
    }

    @Test
    void runExecutesExecutionFocusedDslV02AndProducesReviewReadyBatchReport() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001");
        writeApprovedExpectedResult("RP-001", "RP-001-AC-001");
        writeApprovedExecutionFocusedTestCase("RP-001");
        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();

        int runExit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(runOutput), print(new ByteArrayOutputStream()));

        Path packageRoot = tempDir.resolve("docs/08-release/release-packages/RP-001");
        Path runDir = packageRoot.resolve("evidence/runs/RUN-001");
        assertThat(runExit).isZero();
        assertThat(runOutput.toString()).contains("provider_runtime_started: true");
        assertThat(runOutput.toString()).contains("run_status: passed");
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("test_case_id: RP-001-TC-001")
                .contains("ac_id: RP-001-AC-001")
                .contains("status: passed")
                .contains("dsl_runtime:")
                .contains("dsl_version: v0.2")
                .contains("targets:")
                .contains("target_id: RU-transform-job")
                .contains("provider: spring_boot_cli")
                .contains("setup_fixtures:")
                .contains("fixture_name: orders_seed")
                .contains("execute_steps:")
                .contains("operation: run_batch")
                .contains("actual_output: actual/output.txt")
                .contains("expected_results:")
                .contains("ref: expected-results/approved/RP-001-ER-001.yaml")
                .contains("verify_rules:")
                .contains("type: file_diff")
                .contains("evidence_required:")
                .contains("runtime:")
                .contains("timeout: PT10M")
                .contains("provider_contract_kind: file_batch")
                .contains("provider_type: shell")
                .contains("contract_path: release_units[0].provider_contracts.providers.spring_boot_cli");
        assertThat(Files.readString(runDir.resolve("actual/output.txt"))).isEqualTo("adapter-ok\n");
        assertThat(Files.readString(runDir.resolve("assertions.yaml"))).contains("status: passed");

        ByteArrayOutputStream reportOutput = new ByteArrayOutputStream();
        int reportExit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id", "BATCH-001"},
                print(reportOutput), print(new ByteArrayOutputStream()));

        assertThat(reportExit).isZero();
        assertThat(reportOutput.toString()).contains("report_status: review_ready");
        assertThat(reportOutput.toString()).contains("coverage_percent: 100.0");
        assertThat(Files.readString(packageRoot.resolve("evidence/review/BATCH-001/traceability_report.yaml")))
                .contains("RP-001-TC-001")
                .contains("RP-001-AC-001")
                .contains("RUN-001");
    }

    @Test
    void runExecutesExecutionFocusedDslV02JsonPathSelectorVerify() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001", "{\\\"status\\\":\\\"ACCEPTED\\\"}");
        writeApprovedExecutionFocusedJsonSelectorTestCase("RP-001");
        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();

        int runExit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(runOutput), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(runExit).as(runOutput.toString()).isZero();
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: passed")
                .contains("type: json_path_equals")
                .contains("selector: $.status");
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("type: json_path_equals")
                .contains("decision_rule: json_path_equals")
                .contains("json path `$.status` matched `ACCEPTED`");
    }

    @Test
    void runExecutesExecutionFocusedDslV02NumericToleranceSelectorVerify() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        writeExecutableCiMapping("RP-001", "{\\\"riskScore\\\":0.049}");
        writeApprovedExecutionFocusedNumericToleranceTestCase("RP-001");
        ByteArrayOutputStream runOutput = new ByteArrayOutputStream();

        int runExit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", "RP-001", "--env", "ci_ephemeral"},
                print(runOutput), print(new ByteArrayOutputStream()));

        Path runDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/runs/RUN-001");
        assertThat(runExit).as(runOutput.toString()).isZero();
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("status: passed")
                .contains("type: numeric_tolerance")
                .contains("selector: $.riskScore");
        assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                .contains("type: numeric_tolerance")
                .contains("decision_rule: numeric_tolerance")
                .contains("numeric path `$.riskScore` matched `0.05` within tolerance `0.005`");
    }

    @Test
    void runExecutesExecutionFocusedDslV02ResponseStatusFromProviderMetadata() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            String response = "{\"status\":\"accepted\",\"paymentId\":\"PAY-001\"}\n";
            exchange.sendResponseHeaders(202, response.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            RegressionCommand command = command();
            String rpId = "RP-REST-STATUS";
            String acId = rpId + "-AC-001";
            command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                    print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
            command.execute(new String[] {
                    "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_api"},
                    print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
            writeReadyAcceptanceCriteria(rpId, acId);
            writeRestProviderMapping(rpId, server.getAddress().getPort(), "call_api", "payment_payload");
            writeRestPayload(rpId);
            writeApprovedExecutionFocusedRestStatusTestCase(rpId, acId);
            ByteArrayOutputStream runOutput = new ByteArrayOutputStream();

            int runExit = command.execute(new String[] {
                    "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral"},
                    print(runOutput), print(new ByteArrayOutputStream()));

            Path runDir = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/evidence/runs/RUN-001");
            assertThat(runExit).as(runOutput.toString()).isZero();
            assertThat(requestBody.get()).isEqualTo("{\"amount\":100,\"currency\":\"USD\"}\n");
            assertThat(Files.readString(runDir.resolve("request_response.yaml")))
                    .contains("provider_contract_kind: request_response")
                    .contains("http_status: 202")
                    .contains("actual_output: actual/response.json");
            assertThat(Files.readString(runDir.resolve("run.yaml")))
                    .contains("status: passed")
                    .contains("dsl_runtime:")
                    .contains("type: response_status_equals")
                    .contains("expected: 202")
                    .contains("provider_contract_kind: request_response")
                    .contains("assertion_status: passed");
            assertThat(Files.readString(runDir.resolve("assertions.yaml")))
                    .contains("type: response_status_equals")
                    .contains("decision_rule: response_status_equals")
                    .contains("response status `http_status` matched `202`");
        } finally {
            server.stop(0);
        }
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
    void reportReturnsUsageErrorForUnsupportedFormat() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001",
                "--batch-id", "BATCH-001", "--format", "json"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Unsupported --format: json");
    }

    @Test
    void reportReturnsUsageErrorWhenBatchIdOptionHasNoValue() {
        RegressionCommand command = command();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001", "--batch-id"},
                print(new ByteArrayOutputStream()), print(error));

        assertThat(exit).isEqualTo(2);
        assertThat(error.toString()).contains("Missing required option: --batch-id");
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
    void reportUsesRunIdWhenBatchIdOptionIsBlank() throws Exception {
        RegressionCommand command = command();
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", "RP-001", "--package-type", "data_pipeline"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria("RP-001", "RP-001-AC-001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "report", "--root", tempDir.toString(), "--rp-id", "RP-001",
                "--batch-id", "", "--run-id", "RUN-404"},
                print(output), print(new ByteArrayOutputStream()));

        Path reviewDir = tempDir.resolve("docs/08-release/release-packages/RP-001/evidence/review/RUN-404");
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("review_dir: evidence/review/RUN-404");
        assertThat(Files.exists(reviewDir.resolve("coverage_report.yaml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve(
                "docs/08-release/release-packages/RP-001/evidence/review/BATCH-001"))).isFalse();
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

    private void writeGeneratedRuntimeArtifacts(
            String rpId,
            String adapter,
            String targetId,
            String stdoutValue,
            List<String> dependencies) throws Exception {
        Path generated = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/generated-framework");
        Files.createDirectories(generated.resolve("run_profiles"));
        Files.createDirectories(generated.resolve("environment_bindings"));
        Files.createDirectories(generated.resolve("provider_contracts"));
        Files.writeString(generated.resolve("suite_manifest.yaml"), """
                suite_id: %s-regression
                tests:
                  - tests/approved/%s-TC-001.yaml
                coverage_source_ref: acceptance_criteria.md
                traceability_map_ref: traceability_map.yaml
                """.formatted(rpId, rpId));
        Files.writeString(generated.resolve("run_plan.yaml"), """
                run_profile_ref: run_profiles/ci_ephemeral.yaml
                environment_binding_ref: environment_bindings/ci_ephemeral.yaml
                execution_mode: ci_ephemeral
                target_dependencies:
                %s
                runtime:
                  timeout: PT10M
                """.formatted(targetDependenciesYaml(targetId, dependencies).indent(2)));
        Files.writeString(generated.resolve("run_profiles/ci_ephemeral.yaml"), """
                profile_id: ci_ephemeral
                execution_mode: ci_ephemeral
                environment_binding_ref: environment_bindings/ci_ephemeral.yaml
                isolation_scope: single_target
                dependency_policy: generated_target_graph
                max_duration: PT10M
                data_policy:
                  cleanup_required: true
                """);
        Files.writeString(generated.resolve("environment_bindings/ci_ephemeral.yaml"), """
                environment_id: ci_ephemeral
                environment_type: isolated
                targets:
                  %s:
                    target_id: %s
                    provider: %s
                    execution_mode: ci_ephemeral
                    environment_ref: ci://pipeline/%s
                    provider_contract_ref: provider_contracts/providers.yaml#providers.%s
                """.formatted(targetId, targetId, adapter, rpId, adapter));
        Files.writeString(generated.resolve("provider_contracts/providers.yaml"), """
                provider_contracts:
                  providers:
                    %s:
                      provider_contract_kind: file_batch
                      provider_type: shell
                      contract_path: generated-framework/provider_contracts/providers.yaml#providers.%s
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
                      provider_contract_kind: file_batch
                      provider_type: file_fixture
                      contract_path: generated-framework/provider_contracts/providers.yaml#bindings.db_seed
                      materialize_as: input_file
                  fixtures: {}
                """.formatted(adapter, adapter, stdoutValue));
        Files.writeString(generated.resolve("traceability_map.yaml"), """
                package_id: %s
                source_labels:
                  %s:
                    logical_target_id: %s
                """.formatted(rpId, targetId, targetId));
    }

    private String targetDependenciesYaml(String targetId, List<String> dependencies) {
        if (dependencies.isEmpty()) {
            return targetId + ": []\n";
        }
        StringBuilder builder = new StringBuilder(targetId + ":\n");
        for (String dependency : dependencies) {
            builder.append("  - target_id: ").append(dependency).append("\n");
            builder.append("    required: true\n");
        }
        return builder.toString();
    }

    private void writeMappingAndGeneratedArtifacts(Path mapping, String content) throws Exception {
        Files.writeString(mapping, content);
        writeGeneratedRuntimeArtifactsFromMapping(mapping.getParent(), content);
    }

    @SuppressWarnings("unchecked")
    private void writeGeneratedRuntimeArtifactsFromMapping(Path packageRoot, String mappingYaml) throws Exception {
        Object loaded = new Yaml().load(mappingYaml);
        if (!(loaded instanceof Map<?, ?> root)
                || !(root.get("release_units") instanceof List<?> releaseUnits)
                || releaseUnits.isEmpty()) {
            return;
        }
        Path generated = packageRoot.resolve("generated-framework");
        Files.createDirectories(generated.resolve("run_profiles"));
        Files.createDirectories(generated.resolve("environment_bindings"));
        Files.createDirectories(generated.resolve("provider_contracts"));
        String executionMode = firstUnitText(releaseUnits, "execution_mode", "ci_ephemeral");
        Files.writeString(generated.resolve("suite_manifest.yaml"), """
                suite_id: generated-regression
                tests: []
                coverage_source_ref: acceptance_criteria.md
                traceability_map_ref: traceability_map.yaml
                """);
        Files.writeString(generated.resolve("run_plan.yaml"), generatedRunPlanYaml(releaseUnits, executionMode));
        Files.writeString(generated.resolve("run_profiles/" + executionMode + ".yaml"), """
                profile_id: %s
                execution_mode: %s
                environment_binding_ref: environment_bindings/%s.yaml
                isolation_scope: target_graph
                dependency_policy: generated_target_graph
                max_duration: PT10M
                data_policy:
                  cleanup_required: true
                """.formatted(executionMode, executionMode, executionMode));
        Files.writeString(generated.resolve("environment_bindings/" + executionMode + ".yaml"),
                generatedEnvironmentBindingYaml(releaseUnits, executionMode));
        Files.writeString(generated.resolve("traceability_map.yaml"), generatedTraceabilityMapYaml(releaseUnits));
        for (int index = 0; index < releaseUnits.size(); index++) {
            if (releaseUnits.get(index) instanceof Map<?, ?> unit) {
                writeGeneratedProviderContracts(generated, (Map<String, Object>) unit, index);
            }
        }
    }

    private String generatedRunPlanYaml(List<?> releaseUnits, String executionMode) {
        StringBuilder builder = new StringBuilder();
        builder.append("run_profile_ref: run_profiles/").append(executionMode).append(".yaml\n");
        builder.append("environment_binding_ref: environment_bindings/").append(executionMode).append(".yaml\n");
        builder.append("execution_mode: ").append(executionMode).append("\n");
        builder.append("target_dependencies:\n");
        for (Object entry : releaseUnits) {
            if (!(entry instanceof Map<?, ?> unit)) {
                continue;
            }
            String targetId = text(unit, "ru_id");
            Object dependencies = unit.get("dependencies");
            if (!(dependencies instanceof List<?> list) || list.isEmpty()) {
                builder.append("  ").append(targetId).append(": []\n");
                continue;
            }
            builder.append("  ").append(targetId).append(":\n");
            for (Object dependency : list) {
                builder.append("    - target_id: ").append(dependency).append("\n");
                builder.append("      required: true\n");
            }
        }
        builder.append("runtime:\n");
        builder.append("  timeout: PT10M\n");
        return builder.toString();
    }

    private String generatedEnvironmentBindingYaml(List<?> releaseUnits, String executionMode) {
        StringBuilder builder = new StringBuilder();
        builder.append("environment_id: ").append(executionMode).append("\n");
        builder.append("environment_type: isolated\n");
        builder.append("targets:\n");
        for (Object entry : releaseUnits) {
            if (!(entry instanceof Map<?, ?> unit)) {
                continue;
            }
            String targetId = text(unit, "ru_id");
            String adapter = firstText(unit, "provider", "runner");
            builder.append("  ").append(targetId).append(":\n");
            builder.append("    target_id: ").append(targetId).append("\n");
            builder.append("    provider: ").append(adapter).append("\n");
            builder.append("    execution_mode: ").append(executionMode).append("\n");
            builder.append("    environment_ref: ").append(text(unit, "environment_ref")).append("\n");
            builder.append("    provider_contract_ref: provider_contracts/")
                    .append(safeFileName(targetId))
                    .append(".yaml#providers.")
                    .append(adapter)
                    .append("\n");
        }
        return builder.toString();
    }

    private String generatedTraceabilityMapYaml(List<?> releaseUnits) {
        StringBuilder builder = new StringBuilder("source_labels:\n");
        for (Object entry : releaseUnits) {
            if (entry instanceof Map<?, ?> unit) {
                String targetId = text(unit, "ru_id");
                builder.append("  ").append(targetId).append(":\n");
                builder.append("    ru_id: ").append(targetId).append("\n");
                builder.append("    version_ref: ").append(text(unit, "version_ref")).append("\n");
            }
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeGeneratedProviderContracts(Path generated, Map<String, Object> unit, int unitIndex) throws Exception {
        Object contractsValue = unit.get("provider_contracts");
        Map<String, Object> contracts = contractsValue instanceof Map<?, ?> map
                ? deepCopy((Map<String, Object>) map)
                : new LinkedHashMap<>();
        addContractPaths(contracts, unitIndex);
        Files.writeString(
                generated.resolve("provider_contracts/" + safeFileName(text(unit, "ru_id")) + ".yaml"),
                new Yaml().dump(Map.of("provider_contracts", contracts)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copied.put(entry.getKey(), deepCopy((Map<String, Object>) map));
            } else if (value instanceof List<?> list) {
                copied.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copied.put(entry.getKey(), value);
            }
        }
        return copied;
    }

    @SuppressWarnings("unchecked")
    private void addContractPaths(Map<String, Object> contracts, int unitIndex) {
        for (Map.Entry<String, Object> sectionEntry : contracts.entrySet()) {
            if (!(sectionEntry.getValue() instanceof Map<?, ?> section)) {
                continue;
            }
            for (Map.Entry<?, ?> contractEntry : section.entrySet()) {
                if (contractEntry.getValue() instanceof Map<?, ?> contract
                        && !contract.containsKey("contract_path")) {
                    ((Map<String, Object>) contract).put(
                            "contract_path",
                            "release_units[" + unitIndex + "].provider_contracts."
                                    + sectionEntry.getKey() + "." + contractEntry.getKey());
                }
            }
        }
    }

    private String firstUnitText(List<?> releaseUnits, String field, String fallback) {
        for (Object entry : releaseUnits) {
            if (entry instanceof Map<?, ?> unit) {
                String value = text(unit, field);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private String text(Map<?, ?> map, String field) {
        Object value = map.get(field);
        return value == null ? "" : value.toString();
    }

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = text(map, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
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
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
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
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: spring_boot_cli
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
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
                          provider_type: shell
                          command: /bin/sh -c 'echo %s; echo adapter-warn >&2'
                          working_directory: .
%s                          success_exit_codes: [0]
                          logs:
                            stdout: logs/stdout.log
                            stderr: logs/stderr.log
%s                      bindings:
                        db_seed:
                          provider_contract_kind: file_batch
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
                    provider: external_runner
                    provider_contracts:
                      providers:
                        external_runner:
                          provider_contract_kind: external_runner
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
        writeMappingAndGeneratedArtifacts(mapping, mappingContent);
    }

    private void writeExecutableMultiRuMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: other_cli
                    provider_contracts:
                      providers:
                        other_cli:
                          provider_contract_kind: file_batch
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
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
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
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
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: ready_cli
                    provider_contracts:
                      providers:
                        ready_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
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
                    provider: missing_cli
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, rpId, rpId));
    }

    private void writeDependencyFailureMultiRuMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: upstream_cli
                    provider_contracts:
                      providers:
                        upstream_cli:
                          provider_contract_kind: file_batch
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
                    provider: downstream_cli
                    provider_contracts:
                      providers:
                        downstream_cli:
                          provider_contract_kind: file_batch
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
        writeRestProviderMapping(rpId, port, actionName, requestBinding, true, true);
    }

    private void writeRestProviderMappingWithoutTimeout(String rpId, int port) throws Exception {
        writeRestProviderMapping(rpId, port, "submit_payment", "payment_payload", false, true);
    }

    private void writeRestProviderMappingWithoutOutputRef(String rpId, int port) throws Exception {
        writeRestProviderMapping(rpId, port, "submit_payment", "payment_payload", true, false);
    }

    private void writeRestProviderMapping(
            String rpId,
            int port,
            String actionName,
            String requestBinding,
            boolean includeTimeout,
            boolean includeOutputRef) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String timeoutField = includeTimeout ? "                          timeout_seconds: 10\n" : "";
        String outputField = includeOutputRef
                ? "                          outputs:\n"
                        + "                            actual_output_ref: actual/response.json\n"
                : "                          outputs: {}\n";
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: rest
                          endpoint_ref: http://127.0.0.1:%s
%s                          actions:
                            %s:
                              method: POST
                              path: /payments
                              request_binding: %s
                          logs:
                            stdout: logs/response.log
                            stderr: logs/error.log
%s                      bindings:
                        api_payload:
                          provider_contract_kind: request_response
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, port, timeoutField, actionName, requestBinding, outputField));
    }

    private void writeGrpcProviderMapping(
            String rpId,
            String actionName,
            String requestBinding) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: request_response
                    provider_contracts:
                      providers:
                        request_response:
                          provider_contract_kind: request_response
                          provider_type: grpc
                          service_ref: 127.0.0.1:9090
                          descriptor_ref: descriptors/payment.desc
                          timeout_seconds: 10
                          actions:
                            %s:
                              service: payment.PaymentService
                              method: SubmitPayment
                              request_binding: %s
                          logs:
                            stdout: logs/response.log
                            stderr: logs/error.log
                          outputs:
                            actual_output_ref: actual/response.json
                      bindings:
                        api_payload:
                          provider_contract_kind: request_response
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """.formatted(rpId, actionName, requestBinding));
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

    private void assertMessagingCleanupPreflightGap(
            String rpId,
            java.util.function.UnaryOperator<String> contractMutation,
            String expectedPathSuffix,
            String expectedOwnerAction) throws Exception {
        RegressionCommand command = command();
        String acId = rpId + "-AC-001";
        command.execute(new String[] {"init-product-repo", "--root", tempDir.toString()},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        command.execute(new String[] {
                "init-rp", "--root", tempDir.toString(), "--rp-id", rpId, "--package-type", "service_event"},
                print(new ByteArrayOutputStream()), print(new ByteArrayOutputStream()));
        writeReadyAcceptanceCriteria(rpId, acId);
        writeMessagingCleanupProviderMapping(rpId);
        Path generatedContract = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId
                        + "/generated-framework/provider_contracts/RU-payment-events.yaml");
        Files.writeString(generatedContract, contractMutation.apply(Files.readString(generatedContract)));
        writeApprovedExpectedResult(rpId, acId, "");
        writeApprovedMessagingCleanupTestCase(rpId, acId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exit = command.execute(new String[] {
                "run", "--root", tempDir.toString(), "--rp-id", rpId, "--env", "ci_ephemeral", "--dry-run"},
                print(output), print(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(output.toString())
                .contains("provider_runtime_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_contract_kind: messaging")
                .contains("provider_type: kafka")
                .contains(expectedPathSuffix)
                .contains(expectedOwnerAction);
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
        writeMessagingProviderMapping(rpId, providerType, topicRef, payloadBinding, actionName, serialization, "");
    }

    private void writeMessagingProviderMappingRequiringCorrelationWithoutId(String rpId) throws Exception {
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "payment_event",
                "publish_payment_event", "", "\n                              requires_correlation: true");
    }

    private void writeMessagingProviderMappingWithoutTimeout(String rpId) throws Exception {
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "payment_event",
                "publish_payment_event", "", "", false, true);
    }

    private void writeMessagingProviderMappingWithoutOutputRef(String rpId) throws Exception {
        writeMessagingProviderMapping(rpId, "local", "mock://payment.events", "payment_event",
                "publish_payment_event", "", "", true, false);
    }

    private void writeMessagingProviderMapping(
            String rpId,
            String providerType,
            String topicRef,
            String payloadBinding,
            String actionName,
            String serialization,
            String actionExtraLines) throws Exception {
        writeMessagingProviderMapping(rpId, providerType, topicRef, payloadBinding, actionName, serialization,
                actionExtraLines, true, true);
    }

    private void writeMessagingProviderMapping(
            String rpId,
            String providerType,
            String topicRef,
            String payloadBinding,
            String actionName,
            String serialization,
            String actionExtraLines,
            boolean includeTimeout,
            boolean includeOutputRef) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String serializationLine = serialization.isBlank()
                ? ""
                : "\n                              serialization: " + serialization;
        String actionLines = serializationLine + actionExtraLines;
        String timeoutLine = includeTimeout ? "                          timeout_seconds: 10\n" : "";
        String outputsBlock = includeOutputRef
                ? "                          outputs:\n"
                        + "                            actual_output_ref: actual/message.json\n"
                : "";
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_contract_kind: messaging
                          provider_type: %s
                          topic_ref: %s
%s                          actions:
                            %s:
                              mode: publish
                              payload_binding: %s%s
                          logs:
                            stdout: logs/message.log
                            stderr: logs/message-error.log
%s                      bindings:
                        message_event:
                          provider_contract_kind: messaging
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, message_event]
                    dependencies: []
                """.formatted(rpId, providerType, topicRef, timeoutLine, actionName, payloadBinding,
                actionLines, outputsBlock));
    }

    private void writeMessagingObservationProviderMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_contract_kind: messaging
                          provider_type: kafka
                          bootstrap_servers_ref: env://KAFKA_BOOTSTRAP_SERVERS
                          topic_ref: payment.events
                          timeout_seconds: 10
                          actions:
                            observe_payment_event:
                              mode: observe
                              serialization: json
                              min_count: 1
                              requires_correlation: true
                              correlation_id: EVT-OBS-001
                          logs:
                            stdout: logs/message.log
                            stderr: logs/message-error.log
                          outputs:
                            actual_output_ref: actual/message.json
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, message_event]
                    dependencies: []
                """.formatted(rpId));
    }

    private void writeMessagingCleanupProviderMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_contract_kind: messaging
                          provider_type: kafka
                          bootstrap_servers_ref: env://KAFKA_BOOTSTRAP_SERVERS
                          topic_ref: payment.events
                          timeout_seconds: 10
                          actions:
                            cleanup_payment_event:
                              mode: cleanup
                              cleanup_strategy: drain
                              max_count: 25
                              serialization: json
                          logs:
                            stdout: logs/message.log
                            stderr: logs/message-error.log
                          outputs:
                            actual_output_ref: actual/message.json
                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, cleanup_result]
                    dependencies: []
                """.formatted(rpId));
    }

    private void writeMessagingRequestReplyProviderMapping(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
                rp_id: %s
                release_units:
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service_event
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: event_request_reply
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/events
                    provider: message_bus
                    provider_contracts:
                      providers:
                        message_bus:
                          provider_contract_kind: messaging
                          provider_type: nats
                          server_ref: env://NATS_SERVER
                          subject_ref: payment.status.request
                          timeout_seconds: 10
                          actions:
                            request_payment_status:
                              mode: request_reply
                              payload_binding: payment_event
                              serialization: json
                              requires_correlation: true
                              correlation_id: REQ-001
                          logs:
                            stdout: logs/message.log
                            stderr: logs/message-error.log
                          outputs:
                            actual_output_ref: actual/message.json
                      bindings:
                        message_event:
                          provider_contract_kind: messaging
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, message_event]
                    dependencies: []
                """.formatted(rpId));
    }

    private void writeMessagingEventPayload(String rpId, String payload) throws Exception {
        Path eventPayload = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/fixtures/events/payment_event.json");
        Files.createDirectories(eventPayload.getParent());
        Files.writeString(eventPayload, payload);
    }

    private void writeDeploymentReadinessMapping(String rpId) throws Exception {
        writeDeploymentReadinessMapping(rpId, "                          deployed_version_ref: deploy-123\n");
    }

    private void writeDeploymentReadinessMappingWithoutVersionRef(String rpId) throws Exception {
        writeDeploymentReadinessMapping(rpId, "");
    }

    private void writeDeploymentReadinessMappingWithoutTimeout(String rpId) throws Exception {
        writeDeploymentReadinessMapping(rpId, "                          deployed_version_ref: deploy-123\n",
                false, true);
    }

    private void writeDeploymentReadinessMappingWithoutOutputRef(String rpId) throws Exception {
        writeDeploymentReadinessMapping(rpId, "                          deployed_version_ref: deploy-123\n",
                true, false);
    }

    private void writeDeploymentReadinessMapping(String rpId, String deployedVersionRefLine) throws Exception {
        writeDeploymentReadinessMapping(rpId, deployedVersionRefLine, true, true);
    }

    private void writeDeploymentReadinessMapping(
            String rpId,
            String deployedVersionRefLine,
            boolean includeTimeout,
            boolean includeOutputRef) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String timeoutLine = includeTimeout ? "                          timeout_seconds: 10\n" : "";
        String outputsBlock = includeOutputRef
                ? "                          outputs:\n"
                        + "                            actual_output_ref: actual/readiness.txt\n"
                : "";
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: k8s_readiness
                    provider_contracts:
                      providers:
                        k8s_readiness:
                          provider_contract_kind: deployment_readiness
                          provider_type: local
                          readiness_probe: file_exists
                          target_selector: deployment/payment-api
                          deployment_ref: fixtures/readiness/payment-api.ready
%s%s                          logs:
                            stdout: logs/readiness.log
                            stderr: logs/readiness-error.log
%s                      bindings: {}
                      fixtures: {}
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [readiness_result]
                    dependencies: []
                """.formatted(rpId, deployedVersionRefLine, timeoutLine, outputsBlock));
    }

    private void writeDeploymentReadyMarker(String rpId) throws Exception {
        Path marker = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/fixtures/readiness/payment-api.ready");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "ready\n");
    }

    private void writeSitMappingWithoutDeploymentReadiness(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: k8s_readiness
                    provider_contracts:
                      providers:
                        k8s_readiness:
                          provider_contract_kind: deployment_readiness
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
        writeDbFixtureMapping(rpId, jdbcUrl, "sql_ref: fixtures/db/count_orders.sql", true);
    }

    private void writeDbFixtureMappingWithInlineVerificationSql(String rpId, String jdbcUrl) throws Exception {
        writeDbFixtureMapping(rpId, jdbcUrl, "sql: SELECT COUNT(*) FROM orders", true);
    }

    private void writeDbFixtureMappingWithoutIsolationKey(String rpId, String jdbcUrl) throws Exception {
        writeDbFixtureMapping(rpId, jdbcUrl, "sql_ref: fixtures/db/count_orders.sql", false);
    }

    private void writeDbFixtureMapping(
            String rpId,
            String jdbcUrl,
            String verificationQuery,
            boolean includeIsolationKey) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        String isolationKeyLine = includeIsolationKey ? "                          isolation_key: test_run_id\n" : "";
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
                          provider_type: file_fixture
                          materialize_as: jdbc_seed
                      fixtures:
                        relational_db:
                          provider_contract_kind: db_fixture
                          provider_type: jdbc
                          connection_ref: %s
%s                          cleanup_strategy: by_test_run_id
                          setup_actions:
                            seed_orders:
                              sql_ref: fixtures/db/seed_orders.sql
                          cleanup_actions:
                            cleanup_orders:
                              sql_ref: fixtures/db/cleanup_orders.sql
                          verification_queries:
                            seeded_orders:
                              %s
                      oracles: {}
                      assertions: {}
                      observations: {}
                    evidence_responsibility: [execution_log, cleanup_result]
                    dependencies: []
                """.formatted(rpId, jdbcUrl, isolationKeyLine, verificationQuery));
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
        Files.writeString(fixtureDir.resolve("count_orders.sql"), "SELECT COUNT(*) FROM orders");
    }

    private void writeExecutableCiMappingWithFixtureProvider(String rpId) throws Exception {
        Path mapping = tempDir.resolve("docs/08-release/release-packages/" + rpId + "/rp_ru_mapping.yaml");
        writeMappingAndGeneratedArtifacts(mapping, """
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
                    provider: spring_boot_cli
                    provider_contracts:
                      providers:
                        spring_boot_cli:
                          provider_contract_kind: file_batch
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
                          provider_contract_kind: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                      fixtures:
                        relational_db:
                          provider_contract_kind: db_fixture
                          provider_type: jdbc
                          connection_ref: jdbc:h2:mem:cleanup_fixture;DB_CLOSE_DELAY=-1
                          isolation_key: test_run_id
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

    private void writeInvalidExecutionFocusedDslTestCase(String rpId) throws Exception {
        String acId = rpId + "-AC-001";
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s-TC-001
                status: active
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                targets:
                  RU-transform-job:
                    type: batch_runner
                    provider: spring_boot_cli
                    environment: ci://pipeline/%s
                setup:
                  fixtures:
                    orders_seed:
                      type: db_seed
                      ref: fixtures/db/orders_seed.yaml
                execute:
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: call_ru
                    with:
                      orders_seed: ${setup.fixtures.orders_seed}
                expected_results:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                verify:
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.normalized_orders.ref}
                evidence:
                  required:
                    - ${verify.verify_output.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(rpId, rpId, acId, acId, rpId, expectedResultId));
    }

    private void writeApprovedExecutionFocusedTestCase(String rpId) throws Exception {
        writeApprovedExecutionFocusedTestCase(rpId, "db_seed");
    }

    private void writeApprovedExecutionFocusedTestCase(String rpId, String fixtureType) throws Exception {
        String acId = rpId + "-AC-001";
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s-TC-001
                status: active
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                targets:
                  RU-transform-job:
                    type: batch_runner
                    provider: spring_boot_cli
                    environment: ci://pipeline/%s
                setup:
                  fixtures:
                    orders_seed:
                      type: %s
                      ref: fixtures/db/orders_seed.yaml
                      cleanup_ref: fixtures/db/orders_cleanup.yaml
                execute:
                  - id: run_pipeline
                    target: RU-transform-job
                    operation: run_batch
                    with:
                      orders_seed: ${setup.fixtures.orders_seed}
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                      execution_log:
                        ref: logs/stdout.log
                expected_results:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                verify:
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.normalized_orders.ref}
                evidence:
                  required:
                    - ${execute.run_pipeline.outputs.execution_log}
                    - ${execute.run_pipeline.outputs.actual_output}
                    - ${verify.verify_output.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(rpId, rpId, acId, rpId, fixtureType, expectedResultId));
    }

    private void writeApprovedExecutionFocusedJsonSelectorTestCase(String rpId) throws Exception {
        String acId = rpId + "-AC-001";
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s-TC-001
                status: active
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                targets:
                  RU-transform-job:
                    type: batch_runner
                    provider: spring_boot_cli
                    environment: ci://pipeline/%s
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
                      execution_log:
                        ref: logs/stdout.log
                expected_results: {}
                verify:
                  - id: verify_status
                    type: json_path_equals
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    selector: $.status
                    expected: ACCEPTED
                evidence:
                  required:
                    - ${execute.run_pipeline.outputs.execution_log}
                    - ${execute.run_pipeline.outputs.actual_output}
                    - ${verify.verify_status.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(rpId, rpId, acId, rpId));
    }

    private void writeApprovedExecutionFocusedNumericToleranceTestCase(String rpId) throws Exception {
        String acId = rpId + "-AC-001";
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s-TC-001
                status: active
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                targets:
                  RU-transform-job:
                    type: batch_runner
                    provider: spring_boot_cli
                    environment: ci://pipeline/%s
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
                      execution_log:
                        ref: logs/stdout.log
                expected_results: {}
                verify:
                  - id: verify_risk_score
                    type: numeric_tolerance
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    selector: $.riskScore
                    expected: 0.05
                    options:
                      tolerance: 0.005
                evidence:
                  required:
                    - ${execute.run_pipeline.outputs.execution_log}
                    - ${execute.run_pipeline.outputs.actual_output}
                    - ${verify.verify_risk_score.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(rpId, rpId, acId, rpId));
    }

    private void writeApprovedExecutionFocusedRestStatusTestCase(String rpId, String acId) throws Exception {
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s-TC-001
                status: active
                revision: 1
                labels:
                  package: %s
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                targets:
                  RU-payment-api:
                    type: application
                    runner: request_response
                    environment: ci://payment/api
                setup:
                  fixtures: {}
                execute:
                  - id: submit_payment
                    target: RU-payment-api
                    operation: call_api
                    with:
                      payment_payload:
                        type: api_payload
                        ref: fixtures/api/payment_payload.json
                    outputs:
                      actual_response:
                        ref: actual/response.json
                      execution_log:
                        ref: logs/response.log
                expected_results: {}
                verify:
                  - id: verify_http_status
                    type: response_status_equals
                    expected: 202
                evidence:
                  required:
                    - ${execute.submit_payment.outputs.execution_log}
                    - ${execute.submit_payment.outputs.actual_response}
                    - ${verify.verify_http_status.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(rpId, rpId, acId));
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
                  provider: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
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
                status: active
                revision: 1
                traceability:
                  package_id: %s
                  acceptance_criteria_id: %s
                  source: acceptance_criteria.md#%s
                targets:
                  RU-transform-job:
                    type: spring_boot_application
                    provider: spring_boot_cli
                    environment: ci://pipeline/%s
                parameters:
                  strategy: explicit_cases
                  cases:
                    - case_id: baseline
                      values:
                        orders_seed_ref: fixtures/input/orders_seed_baseline.csv
                    - case_id: boundary
                      values:
                        orders_seed_ref: fixtures/input/orders_seed_boundary.csv
                setup:
                  fixtures:
                    orders_seed:
                      type: db_seed
                      ref: ${parameters.orders_seed_ref}
                      cleanup_ref: fixtures/cleanup/orders_seed_cleanup.sql
                execute:
                  - id: run_pipeline
                    operation: run_batch
                    target: RU-transform-job
                    with:
                      orders_seed: ${setup.fixtures.orders_seed}
                    outputs:
                      exit_code: ${result.exit_code}
                      normalized_orders_file: ${result.files.normalized_orders}
                      execution_log: ${result.logs.execution_log}
                expected_results:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                verify:
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.normalized_orders_file}
                    expected: ${expected_results.normalized_orders.ref}
                evidence:
                  required:
                    - ${execute.run_pipeline.outputs.execution_log}
                    - ${verify.verify_output.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(testCaseId, rpId, acId, acId, rpId, expectedResultId));
    }

    private void writeApprovedV02ParameterizedTestCase(String rpId) throws Exception {
        String testCaseId = rpId + "-TC-001";
        String acId = rpId + "-AC-001";
        String expectedResultId = rpId + "-ER-001";
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s
                status: active
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                targets:
                  RU-transform-job:
                    type: spring_boot_application
                    provider: spring_boot_cli
                    environment: ci://pipeline/%s
                parameters:
                  ref: parameter-sets/orders_regression_cases.yaml
                  bind_as: orders_case
                setup:
                  fixtures:
                    orders_seed:
                      type: db_seed
                      ref: ${param.orders_case.orders_seed_ref}
                      cleanup_ref: fixtures/cleanup/orders_seed_cleanup.sql
                execute:
                  - id: run_pipeline
                    operation: run_batch
                    target: RU-transform-job
                    with:
                      orders_seed: ${setup.fixtures.orders_seed}
                    outputs:
                      exit_code: ${result.exit_code}
                      normalized_orders_file: ${result.files.normalized_orders}
                      execution_log: ${result.logs.execution_log}
                expected_results:
                  normalized_orders:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                verify:
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.normalized_orders_file}
                    expected: ${expected_results.normalized_orders.ref}
                evidence:
                  required:
                    - ${execute.run_pipeline.outputs.execution_log}
                    - ${verify.verify_output.result}
                runtime:
                  timeout: PT10M
                  retry:
                    max_attempts: 0
                """.formatted(testCaseId, acId, rpId, expectedResultId));
    }

    private void writeApprovedParameterSet(String rpId) throws Exception {
        Path parameterSet = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/parameter-sets/orders_regression_cases.yaml");
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                parameter_set_id: orders_regression_cases
                status: approved_for_regression
                cases:
                  - case_id: baseline
                    values:
                      orders_seed_ref: fixtures/input/orders_seed_baseline.csv
                  - case_id: boundary
                    values:
                      orders_seed_ref: fixtures/input/orders_seed_boundary.csv
                """);
    }

    private void writeMalformedParameterSet(String rpId) throws Exception {
        Path parameterSet = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/parameter-sets/orders_regression_cases.yaml");
        Files.createDirectories(parameterSet.getParent());
        Files.writeString(parameterSet, """
                parameter_set_id: orders_regression_cases
                status: approved_for_regression
                cases:
                  - case_id: baseline
                    values:
                      orders_seed_ref: fixtures/input/orders_seed_baseline.csv
                  - case_id: baseline
                    values:
                      unused_ref: fixtures/input/orders_seed_unused.csv
                """);
    }

    private void writeMalformedParameterizedTestCase(String rpId) throws Exception {
        writeApprovedParameterizedTestCase(rpId);
        String testCaseId = rpId + "-TC-001";
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + testCaseId + ".yaml");
        String yaml = Files.readString(testCase)
                .replace("case_id: boundary", "case_id: baseline")
                .replace("orders_seed_ref: fixtures/input/orders_seed_boundary.csv",
                        "unused_ref: fixtures/input/orders_seed_unused.csv");
        Files.writeString(testCase, yaml);
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
                  provider: external_runner
                  execution_mode: ci_ephemeral
                  environment_ref: ci://runner/%s
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
                  provider: %s
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
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
                  provider: %s
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
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
                  provider: request_response
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/api
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
                  provider: message_bus
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/events
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

    private void writeApprovedMessagingObservationTestCase(String rpId, String acId) throws Exception {
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
                  provider: message_bus
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/events
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  payment_event:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs: {}
                steps:
                  - id: observe_payment_event
                    action: observe_payment_event
                    target_ru_id: RU-payment-events
                assertions:
                  - type: file_diff
                    oracle: ${oracles.payment_event}
                evidence_required:
                  - execution_log
                  - message_event
                """.formatted(rpId, rpId, acId, acId, expectedResultId, expectedResultId));
    }

    private void writeApprovedMessagingCleanupTestCase(String rpId, String acId) throws Exception {
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
                  provider: message_bus
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/events
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  cleanup_result:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs: {}
                steps:
                  - id: cleanup_payment_event
                    action: cleanup_payment_event
                    target_ru_id: RU-payment-events
                assertions:
                  - type: file_diff
                    oracle: ${oracles.cleanup_result}
                evidence_required:
                  - execution_log
                  - cleanup_result
                """.formatted(rpId, rpId, acId, acId, expectedResultId, expectedResultId));
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
                  provider: k8s_readiness
                  execution_mode: ci_ephemeral
                  environment_ref: ci://payment/k8s
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
                  provider: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://order/db
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
                  provider: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
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
                  provider: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
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
                  provider: spring_boot_cli
                  execution_mode: ci_ephemeral
                  environment_ref: ci://pipeline/%s
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

    private void writeApprovedTestCaseWithoutExecutionTarget(String rpId) throws Exception {
        Path testCase = tempDir.resolve(
                "docs/08-release/release-packages/" + rpId + "/tests/approved/" + rpId + "-TC-001.yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v0.2
                test_case_id: %s-TC-001
                status: active
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s-AC-001
                source_fingerprint: sha256:test
                labels:
                  package: %s
                setup:
                  fixtures:
                    orders_seed:
                      ref: fixtures/input/orders.csv
                      bind_as: db_seed
                execute:
                  - id: run_pipeline
                    operation: run_batch
                    with: {}
                    outputs:
                      actual_output:
                        ref: actual/output.txt
                expected_results:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s-ER-001.yaml
                verify:
                  - id: verify_output
                    type: file_diff
                    actual: ${execute.run_pipeline.outputs.actual_output}
                    expected: ${expected_results.primary.ref}
                evidence:
                  collect:
                    - execution_log
                runtime:
                  execution_mode: ci_ephemeral
                """.formatted(rpId, rpId, rpId, rpId));
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
