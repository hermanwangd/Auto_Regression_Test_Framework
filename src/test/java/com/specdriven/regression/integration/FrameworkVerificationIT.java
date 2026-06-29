package com.specdriven.regression.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.mapping.RpRuMappingService;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.specdriven.regression.provider.ProviderContractGap;
import com.specdriven.regression.provider.ProviderContractResolutionReport;
import com.specdriven.regression.provider.ProviderContractResolver;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class FrameworkVerificationIT {

    private static final String SAMPLE_RP_ID = "RP-FWK-SAMPLE";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("FWK-IT-001 | AC-001 AC-002 AC-004 AC-005 AC-009 AC-012 AC-013 AC-015 AC-017 | happy path check-run-report")
    void sampleProductRepoFixtureRunsThroughCheckRunAndReportWithoutSitDeployment() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        RegressionCommand command = command();

        CommandResult check = execute(command, "check-rp", productRepo, "--strict-schema");
        CommandResult run = execute(command, "run", productRepo, "--env", "ci_ephemeral");
        CommandResult report = execute(command, "report", productRepo, "--batch-id", "BATCH-001");

        assertThat(check.exitCode()).isZero();
        assertThat(check.stdout()).contains("status: pass");
        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).contains("adapter_execution_started: true");
        assertThat(run.stdout()).contains("batch_id: BATCH-001");
        assertThat(report.exitCode()).isZero();
        assertThat(report.stdout()).contains("coverage_percent: 100.0");
        assertThat(Files.readString(productRepo.resolve("FRAMEWORK_VERIFICATION_FIXTURE.md")))
                .contains("not downstream Product/RP release evidence");
        assertThat(Files.readString(packageRoot.resolve("package.yaml")))
                .contains("fixture_scope: framework_verification");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("rp_id: RP-FWK-SAMPLE")
                .contains("execution_mode: ci_ephemeral")
                .contains("environment_ref: ci://framework-verification/RP-FWK-SAMPLE");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: passed")
                .contains("test_case_id: RP-FWK-SAMPLE-TC-001");
        assertThat(Files.readString(packageRoot.resolve("evidence/review/BATCH-001/coverage_report.yaml")))
                .contains("coverage_percent: 100.0")
                .contains("review_ready: true");
    }

    @Test
    @DisplayName("SUP-IT-002 | SUP-AC-002 | RP completeness gaps block readiness before execution evidence")
    void strictRpCheckReportsPackageSchemaAndMappingGapsBeforeExecution() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writePackageYaml(packageRoot, Files.readString(packageRoot.resolve("package.yaml"))
                .replace("owner: platform", "owner: "));
        writeMappingYaml(packageRoot, Files.readString(packageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("    repo: /repo/framework-sample\n", ""));

        CommandResult check = execute(command(), "check-rp", productRepo, "--strict-schema");

        assertThat(check.exitCode()).isEqualTo(1);
        assertThat(check.stdout()).contains("status: fail");
        assertThat(check.stdout()).contains("field_path: owner");
        assertThat(check.stdout()).contains("blocks: generation");
        assertThat(check.stdout()).contains("field_path: release_units[0].repo");
        assertThat(check.stdout()).contains("blocks_execution: true");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("evidence/batches"))).isFalse();
    }

    @Test
    @DisplayName("FWK-IT-003 | AC-002 AC-005 AC-016 | provider contract gap blocks adapter execution")
    void runBlocksBeforeAdapterExecutionWhenProviderContractIsIncomplete() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writeMappingYaml(packageRoot, Files.readString(packageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("          command: 'echo framework-sample-ok; echo framework-sample-warn >&2'\n", ""));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: false");
        assertThat(run.stdout()).contains(
                "contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli.command");
        assertThat(run.stdout()).contains("provider_family: file_batch");
        assertThat(run.stdout()).contains("affected_ru: RU-framework-sample-adapter");
        assertThat(run.stdout()).contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("adapter_execution_started: false")
                .contains("failure_details: failure_details.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("release_units[0].provider_contracts.adapters.spring_boot_cli.command")
                .contains("provider_family: file_batch")
                .contains("affected_ru: RU-framework-sample-adapter")
                .contains("Declare executable adapter command for `spring_boot_cli`")
                .contains("Affected target: `RU-framework-sample-adapter`");
    }

    @Test
    @DisplayName("SUP-IT-006 | SUP-AC-006 | unapproved expected result is not regression truth")
    void runBlocksBeforeAdapterExecutionWhenExpectedResultIsNotApproved() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        Path expectedResult = packageRoot.resolve("expected-results/approved/RP-FWK-SAMPLE-ER-001.yaml");
        Files.writeString(expectedResult, Files.readString(expectedResult)
                .replace("status: approved_for_regression", "status: draft"));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: false");
        assertThat(run.stdout()).contains("expected_result_eligibility:");
        assertThat(run.stdout()).contains("eligible: false");
        assertThat(run.stdout()).contains("field_path: status");
        assertThat(run.stdout()).contains("Approve expected result before using it as regression truth.");
        assertThat(run.stdout()).contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("adapter_execution_started: false")
                .contains("failure_details: failure_details.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("field_path: status")
                .contains("Approve expected result before using it as regression truth.");
    }

    @Test
    @DisplayName("FWK-IT-005 | AC-015 | missing approved DSL test blocks execution")
    void runBlocksWhenNoApprovedDslTestCaseIsCheckedIn() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        Files.delete(packageRoot.resolve("tests/approved/RP-FWK-SAMPLE-TC-001.yaml"));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: false");
        assertThat(run.stdout()).contains("field_path: tests/approved");
        assertThat(run.stdout()).contains("run_status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/batches/BATCH-001/batch.yaml")))
                .contains("status: blocked");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: blocked")
                .contains("test_case_id: ")
                .contains("adapter_execution_started: false");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/failure_details.yaml")))
                .contains("tests/approved")
                .contains("Add approved_for_regression DSL test cases before run.");
    }

    @Test
    @DisplayName("FWK-IT-006 | AC-005 AC-009 AC-012 AC-013 | failed assertion creates failed evidence and not-review-ready report")
    void failedAssertionProducesRunEvidenceAndNotReviewReadyReport() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writeMappingYaml(packageRoot, Files.readString(packageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("          command: 'echo framework-sample-ok; echo framework-sample-warn >&2'",
                        "          command: 'echo unexpected-framework-output'"));

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");
        CommandResult report = execute(command(), "report", productRepo, "--batch-id", "BATCH-001");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.stdout()).contains("adapter_execution_started: true");
        assertThat(run.stdout()).contains("status: failed");
        assertThat(run.stdout()).contains("run_status: failed");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml")))
                .contains("status: failed")
                .contains("exit_code: 0")
                .contains("assertion_status: failed")
                .contains("assertions: assertions.yaml");
        assertThat(Files.readString(packageRoot.resolve("evidence/runs/RUN-001/assertions.yaml")))
                .contains("status: failed")
                .contains("expected `expected/output/orders.csv` differs from actual `actual/output.txt`");
        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(report.stdout()).contains("report_status: not_review_ready");
        assertThat(report.stdout()).contains("coverage_percent: 0.0");
        assertThat(report.stdout()).contains("run_status: failed");
        assertThat(Files.readString(packageRoot.resolve("evidence/review/BATCH-001/failure_summary.yaml")))
                .contains("assertion_status: failed")
                .contains("expected_ref: expected/output/orders.csv")
                .contains("actual_ref: actual/output.txt");
    }

    @Test
    @DisplayName("SUP-IT-001 | SUP-AC-001 | product repo bootstrap readiness and idempotency")
    void productRepoBootstrapReadinessAndIdempotencyAreDeterministic() throws Exception {
        Path productRepo = tempDir.resolve("empty-product-repo");
        RegressionCommand command = command();

        CommandResult beforeInit = execute(command, "check-readiness", productRepo);
        CommandResult init = execute(command, "init-product-repo", productRepo);
        Files.writeString(productRepo.resolve("docs/01-specs/sentinel.md"), "preserve me\n");
        CommandResult afterInit = execute(command, "check-readiness", productRepo);
        CommandResult initAgain = execute(command, "init-product-repo", productRepo);

        assertThat(beforeInit.exitCode()).isEqualTo(1);
        assertThat(beforeInit.stdout()).contains("status: fail");
        assertThat(beforeInit.stdout()).contains("Create missing Product Repo lifecycle paths");
        assertThat(init.exitCode()).isZero();
        assertThat(init.stdout()).contains("status: pass");
        assertThat(init.stdout()).contains("created_count: 13");
        assertThat(afterInit.exitCode()).isZero();
        assertThat(afterInit.stdout()).contains("status: pass");
        assertThat(afterInit.stdout()).contains("ready: true");
        assertThat(afterInit.stdout()).contains("Run init-rp to create the first Release Package record.");
        assertThat(initAgain.exitCode()).isZero();
        assertThat(initAgain.stdout()).contains("created_count: 0");
        assertThat(initAgain.stdout()).contains("skipped_existing_count: 13");
        assertThat(Files.readString(productRepo.resolve("docs/01-specs/sentinel.md"))).isEqualTo("preserve me\n");
        assertThat(Files.exists(productRepo.resolve("docs/08-release/release-packages/" + SAMPLE_RP_ID))).isFalse();
    }

    @Test
    @DisplayName("SUP-IT-003 | SUP-AC-003 | AC readiness preserves owner-authored truth")
    void acReadinessPreservesOwnerAuthoredTruthWithoutRewritingRpAc() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        Path acceptanceCriteria = packageRoot.resolve("acceptance_criteria.md");
        String originalAc = Files.readString(acceptanceCriteria);

        CommandResult check = execute(command(), "check-rp", productRepo, "--strict-schema", "--include-ac-readiness");

        assertThat(check.exitCode()).isZero();
        assertThat(check.stdout()).contains("ac_readiness:");
        assertThat(check.stdout()).contains("ac_id: RP-FWK-SAMPLE-AC-001");
        assertThat(check.stdout()).contains("readiness: ready_for_generation");
        assertThat(check.stdout()).contains("classification: automatable");
        assertThat(check.stdout()).contains("owner_authored_truth_preserved: true");
        assertThat(Files.readString(acceptanceCriteria)).isEqualTo(originalAc);
    }

    @Test
    @DisplayName("SUP-IT-004 | SUP-AC-004 | RP mapping translation boundary is explicit")
    void rpMappingTranslationBoundaryIsExplicit() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);

        var mappingReport = new RpRuMappingService().validate(packageRoot.resolve("rp_ru_mapping.yaml"));

        assertThat(mappingReport.valid()).isTrue();
        assertThat(mappingReport.releaseUnits()).hasSize(1);
        assertThat(mappingReport.releaseUnits().get(0).ruId()).isEqualTo("RU-framework-sample-adapter");
        assertThat(mappingReport.releaseUnits().get(0).adapter()).isEqualTo("spring_boot_cli");
        assertThat(mappingReport.releaseUnits().get(0).environmentRef())
                .isEqualTo("ci://framework-verification/RP-FWK-SAMPLE");
        assertThat(Files.readString(packageRoot.resolve("rp_ru_mapping.yaml")))
                .contains("provider_contracts:")
                .contains("provider_family: file_batch")
                .contains("provider_type: shell");
        assertThat(Files.exists(packageRoot.resolve("generated-framework"))).isTrue();
        assertThat(Files.exists(packageRoot.resolve("generated-framework/run_plan.yaml"))).isTrue();
    }

    @Test
    @DisplayName("SUP-IT-005 | SUP-AC-005 | test drafting is gated by AC and execution context readiness")
    void testDraftingIsGatedByAcAndExecutionContextReadiness() throws Exception {
        Path readyRepo = sampleProductRepo();
        Path readyPackageRoot = packageRoot(readyRepo);
        Files.delete(readyPackageRoot.resolve("tests/approved/RP-FWK-SAMPLE-TC-001.yaml"));
        CommandResult readyDraft = execute(command(), "generate-tests", readyRepo);

        assertThat(readyDraft.exitCode()).isZero();
        assertThat(readyDraft.stdout()).contains("generated_artifact_type: draft_executable_test_case");
        assertThat(Files.readString(readyPackageRoot.resolve(
                        "tests/draft/RP-FWK-SAMPLE-TC-001-draft_executable_test_case.yaml")))
                .contains("dsl_version: v0.2")
                .contains("status: draft_executable")
                .contains("labels:")
                .contains("package: RP-FWK-SAMPLE")
                .contains("runtime_unit: RU-framework-sample-adapter")
                .contains("source_refs:")
                .contains("acceptance_criteria: acceptance_criteria.md#RP-FWK-SAMPLE-AC-001")
                .contains("scenario:")
                .contains("targets:")
                .contains("setup:")
                .contains("execute:")
                .contains("expected_results:")
                .contains("verify:")
                .contains("evidence:")
                .contains("runtime:")
                .doesNotContain("traceability:")
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("execution_target:")
                .doesNotContain("package_inputs:")
                .doesNotContain("oracles:")
                .doesNotContain("steps:")
                .doesNotContain("assertions:")
                .doesNotContain("evidence_required:");

        Path existingRepo = sampleProductRepo();
        Path existingPackageRoot = packageRoot(existingRepo);
        Path approvedTest = existingPackageRoot.resolve("tests/approved/RP-FWK-SAMPLE-TC-001.yaml");
        String originalApprovedTest = Files.readString(approvedTest);
        CommandResult existingDraft = execute(command(), "generate-tests", existingRepo);

        assertThat(existingDraft.exitCode()).isZero();
        assertThat(existingDraft.stdout()).contains("generated_artifact_type: update_proposal");
        assertThat(Files.readString(approvedTest)).isEqualTo(originalApprovedTest);
        assertThat(Files.readString(existingPackageRoot.resolve(
                        "tests/draft/RP-FWK-SAMPLE-TC-001-update_proposal.yaml")))
                .contains("proposal_type: test_case_update")
                .contains("dsl_version: v0.2")
                .contains("status: needs_update")
                .contains("labels:")
                .contains("package: RP-FWK-SAMPLE")
                .contains("source_refs:")
                .contains("acceptance_criteria: acceptance_criteria.md#RP-FWK-SAMPLE-AC-001")
                .contains("replaces: tests/approved/RP-FWK-SAMPLE-TC-001.yaml")
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("traceability:");

        Path ambiguousRepo = sampleProductRepo();
        Path ambiguousPackageRoot = packageRoot(ambiguousRepo);
        writeAcceptanceCriteria(ambiguousPackageRoot, Files.readString(
                        ambiguousPackageRoot.resolve("acceptance_criteria.md"))
                .replace("    pass_fail_rule: actual output matches approved expected output\n", ""));
        CommandResult ambiguousDraft = execute(command(), "generate-tests", ambiguousRepo);

        assertThat(ambiguousDraft.exitCode()).isEqualTo(1);
        assertThat(ambiguousDraft.stdout()).contains("generated_artifact_type: none");
        assertThat(ambiguousDraft.stdout()).contains("gap: AC is not ready for generation");

        Path incompleteContextRepo = sampleProductRepo();
        Path incompleteContextPackageRoot = packageRoot(incompleteContextRepo);
        Files.delete(incompleteContextPackageRoot.resolve("tests/approved/RP-FWK-SAMPLE-TC-001.yaml"));
        writeMappingYaml(incompleteContextPackageRoot, Files.readString(
                        incompleteContextPackageRoot.resolve("rp_ru_mapping.yaml"))
                .replace("    repo: /repo/framework-sample\n", ""));
        CommandResult skeletonDraft = execute(command(), "generate-tests", incompleteContextRepo);

        assertThat(skeletonDraft.exitCode()).isZero();
        assertThat(skeletonDraft.stdout()).contains("generated_artifact_type: draft_test_skeleton");
        assertThat(skeletonDraft.stdout()).contains("gap: release_units[0].repo");
        assertThat(Files.readString(incompleteContextPackageRoot.resolve(
                        "tests/draft/RP-FWK-SAMPLE-TC-001-draft_test_skeleton.yaml")))
                .contains("dsl_version: v0.2")
                .contains("status: draft_skeleton")
                .contains("source_refs:")
                .contains("acceptance_criteria: acceptance_criteria.md#RP-FWK-SAMPLE-AC-001")
                .contains("readiness_gaps:")
                .contains("release_units[0].repo")
                .doesNotContain("rp_id:")
                .doesNotContain("ac_id:")
                .doesNotContain("artifact_status:")
                .doesNotContain("traceability:");
    }

    @Test
    @DisplayName("FWK-IT-010 | AC-002 AC-004 AC-016 | provider-family contract dry-run")
    void providerFamilyContractDryRunReportsMetadataAndBlocking() throws Exception {
        Path mapping = tempDir.resolve("heterogeneous-rp_ru_mapping.yaml");
        Files.writeString(mapping, providerFamilyMapping());
        ProviderContractResolver resolver = new ProviderContractResolver();

        List<ProviderContractResolutionReport> reports = List.of(
                resolver.resolve(mapping, "request_response", List.of("api_payload"), List.of("relational_db")),
                resolver.resolve(mapping, "message_bus", List.of("message_event"), List.of()),
                resolver.resolve(mapping, "k8s_readiness", List.of(), List.of()),
                resolver.resolve(mapping, "external_runner", List.of(), List.of()),
                resolver.resolve(mapping, "spring_boot_cli", List.of("db_seed"), List.of()));

        assertThat(reports).allMatch(ProviderContractResolutionReport::ready);
        assertThat(reports.stream()
                        .flatMap(report -> report.resolvedContracts().stream())
                        .map(ResolvedProviderContract::providerFamily)
                        .toList())
                .contains("request_response", "messaging", "db_fixture",
                        "deployment_readiness", "external_runner", "file_batch");
        assertThat(reports.stream()
                        .flatMap(report -> report.resolvedContracts().stream())
                        .map(ResolvedProviderContract::contractPath)
                        .toList())
                .contains("release_units[1].provider_contracts.bindings.message_event",
                        "release_units[2].provider_contracts.fixtures.relational_db");

        Path missingMapping = tempDir.resolve("missing-provider-rp_ru_mapping.yaml");
        Files.writeString(missingMapping, """
                rp_id: RP-HETEROGENEOUS
                release_units:
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service
                    owner: product_developer
                    version_ref: build-456
                    validation_boundary: event_observation
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/events
                    adapter: message_bus
                    provider_contracts: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """);

        ProviderContractResolutionReport blocked = resolver.resolve(
                missingMapping, "message_bus", List.of("message_event"), List.of());

        assertThat(blocked.ready()).isFalse();
        assertThat(blocked.gaps())
                .anySatisfy(gap -> {
                    assertThat(gap.providerFamily()).isEqualTo("messaging");
                    assertThat(gap.affectedRu()).isEqualTo("RU-payment-events");
                    assertThat(gap.capability()).isEqualTo("message_bus");
                    assertThat(gap.fieldPath())
                            .isEqualTo("release_units[0].provider_contracts.adapters.message_bus");
                    assertThat(gap.ownerAction()).contains("RU-payment-events");
                })
                .extracting(ProviderContractGap::fieldPath)
                .contains("release_units[0].provider_contracts.bindings.message_event");

        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);
        writeMappingYaml(packageRoot, providerFamilyMapping().replace("rp_id: RP-HETEROGENEOUS", "rp_id: RP-FWK-SAMPLE"));
        writeProviderFamilyDryRunArtifacts(packageRoot);

        CommandResult dryRun = execute(command(), "run", productRepo, "--env", "ci_ephemeral", "--dry-run");

        assertThat(dryRun.exitCode()).isZero();
        assertThat(dryRun.stdout()).contains("adapter_execution_started: false");
        assertThat(dryRun.stdout()).contains("run_status: dry_run_ready");
        assertThat(dryRun.stdout())
                .contains("provider_family: request_response")
                .contains("provider_type: rest")
                .contains("affected_ru: RU-payment-api")
                .contains("contract_path: release_units[0].provider_contracts.adapters.request_response")
                .contains("provider_family: messaging")
                .contains("provider_type: local")
                .contains("affected_ru: RU-payment-events")
                .contains("contract_path: release_units[1].provider_contracts.adapters.message_bus")
                .contains("provider_family: db_fixture")
                .contains("provider_type: relational_db")
                .contains("affected_ru: RU-payment-db")
                .contains("contract_path: release_units[2].provider_contracts.adapters.db_fixture")
                .contains("provider_family: deployment_readiness")
                .contains("provider_type: local")
                .contains("affected_ru: RU-payment-k8s")
                .contains("contract_path: release_units[3].provider_contracts.adapters.k8s_readiness")
                .contains("provider_family: external_runner")
                .contains("provider_type: command_runner")
                .contains("affected_ru: RU-legacy-runner")
                .contains("contract_path: release_units[4].provider_contracts.adapters.external_runner")
                .contains("provider_family: file_batch")
                .contains("provider_type: shell")
                .contains("affected_ru: RU-batch-job")
                .contains("contract_path: release_units[5].provider_contracts.adapters.spring_boot_cli")
                .contains("registry_status: supported")
                .contains("ap: Planning and Binding");
        assertThat(Files.exists(packageRoot.resolve("evidence/runs"))).isFalse();
        assertThat(Files.exists(packageRoot.resolve("evidence/batches"))).isFalse();

        Path blockedRepo = sampleProductRepo();
        Path blockedPackageRoot = packageRoot(blockedRepo);
        writeMappingYaml(blockedPackageRoot, providerFamilyMapping()
                .replace("rp_id: RP-HETEROGENEOUS", "rp_id: RP-FWK-SAMPLE")
                .replace("approval_ref: docs/10-change-control/runner-approval.md",
                        "approval_ref_missing: docs/10-change-control/runner-approval.md"));
        clearApprovedTests(blockedPackageRoot);
        writeDryRunExpectedResult(blockedPackageRoot, "RP-FWK-SAMPLE-AC-RUNNER");
        writeDryRunTestCase(
                blockedPackageRoot,
                "RP-FWK-SAMPLE-TC-RUNNER",
                "RP-FWK-SAMPLE-AC-RUNNER",
                "RU-legacy-runner",
                "external_runner",
                "external_runner",
                "");

        CommandResult blockedDryRun = execute(command(), "run", blockedRepo, "--env", "ci_ephemeral", "--dry-run");

        assertThat(blockedDryRun.exitCode()).isEqualTo(1);
        assertThat(blockedDryRun.stdout()).contains("run_status: blocked");
        assertThat(blockedDryRun.stdout())
                .contains("provider_contract_gaps:")
                .contains("provider_family: external_runner")
                .contains("provider_type: command_runner")
                .contains("registry_status: unapproved_escape_hatch")
                .contains("runtime_status: blocked")
                .contains("affected_ru: RU-legacy-runner")
                .contains("capability: external_runner")
                .contains("contract_path: release_units[4].provider_contracts.adapters.external_runner.approval_ref")
                .contains("Declare external runner approval metadata")
                .contains("ap: Planning and Binding");

        Path ambiguousRepo = sampleProductRepo();
        Path ambiguousPackageRoot = packageRoot(ambiguousRepo);
        writeMappingYaml(ambiguousPackageRoot, ambiguousRequestResponseMapping());
        clearApprovedTests(ambiguousPackageRoot);
        writeDryRunExpectedResult(ambiguousPackageRoot, "RP-FWK-SAMPLE-AC-AMBIGUOUS");
        writeDryRunTestCase(
                ambiguousPackageRoot,
                "RP-FWK-SAMPLE-TC-AMBIGUOUS",
                "RP-FWK-SAMPLE-AC-AMBIGUOUS",
                "",
                "request_response",
                "request_response",
                """
                  payment_payload:
                    ref: fixtures/api/payment_payload.json
                    bind_as: api_payload
                """);

        CommandResult ambiguousDryRun =
                execute(command(), "run", ambiguousRepo, "--env", "ci_ephemeral", "--dry-run");

        assertThat(ambiguousDryRun.exitCode()).isEqualTo(1);
        assertThat(ambiguousDryRun.stdout())
                .contains("adapter_execution_started: false")
                .contains("run_status: blocked")
                .contains("provider_contract_gaps:")
                .contains("provider_family: request_response")
                .contains("registry_status: ambiguous")
                .contains("runtime_status: blocked")
                .contains("affected_ru: RU-payment-api-blue,RU-payment-api-green")
                .contains("contract_path: release_units[0].provider_contracts.adapters.request_response")
                .contains("Select target ID")
                .contains("ap: Planning and Binding");
    }

    @Test
    @DisplayName("FWK-IT-011 | AC-012 AC-013 AC-016 | provider-family evidence normalization")
    void sampleRunEvidenceNormalizesProviderContractsAndBindings() throws Exception {
        Path productRepo = sampleProductRepo();
        Path packageRoot = packageRoot(productRepo);

        CommandResult run = execute(command(), "run", productRepo, "--env", "ci_ephemeral");

        assertThat(run.exitCode()).isZero();
        String runEvidence = Files.readString(packageRoot.resolve("evidence/runs/RUN-001/run.yaml"));
        assertThat(runEvidence)
                .contains("resolved_bindings:")
                .contains("binding_type: db_seed")
                .contains("provider_contracts_used:")
                .contains("provider_family: file_batch")
                .contains("provider_type: file_fixture")
                .contains("registry_status: supported")
                .contains("contract_path: release_units[0].provider_contracts.adapters.spring_boot_cli")
                .contains("contract_path: release_units[0].provider_contracts.bindings.db_seed")
                .contains("affected_ru: RU-framework-sample-adapter")
                .contains("status: passed");
    }

    private Path sampleProductRepo() throws Exception {
        Path productRepo = tempDir.resolve("sample-product-repo-" + System.nanoTime());
        copyResourceDirectory("framework-verification/sample-product-repo", productRepo);
        Path packageRoot = packageRoot(productRepo);
        writeGeneratedRuntimeArtifactsFromMapping(
                packageRoot,
                Files.readString(packageRoot.resolve("rp_ru_mapping.yaml")));
        return productRepo;
    }

    private Path packageRoot(Path productRepo) {
        return productRepo.resolve("docs/08-release/release-packages").resolve(SAMPLE_RP_ID);
    }

    private RegressionCommand command() {
        return new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
    }

    private CommandResult execute(RegressionCommand command, String commandName, Path productRepo, String... extraArgs) {
        String[] args = new String[5 + extraArgs.length];
        args[0] = commandName;
        args[1] = "--root";
        args[2] = productRepo.toString();
        args[3] = "--rp-id";
        args[4] = SAMPLE_RP_ID;
        System.arraycopy(extraArgs, 0, args, 5, extraArgs.length);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = command.execute(args, print(stdout), print(stderr));
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    private void writePackageYaml(Path packageRoot, String content) throws Exception {
        Files.writeString(packageRoot.resolve("package.yaml"), content);
    }

    private void writeMappingYaml(Path packageRoot, String content) throws Exception {
        Files.writeString(packageRoot.resolve("rp_ru_mapping.yaml"), content);
        writeGeneratedRuntimeArtifactsFromMapping(packageRoot, content);
    }

    private void writeAcceptanceCriteria(Path packageRoot, String content) throws Exception {
        Files.writeString(packageRoot.resolve("acceptance_criteria.md"), content);
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
            String adapter = text(unit, "adapter");
            builder.append("  ").append(targetId).append(":\n");
            builder.append("    target_id: ").append(targetId).append("\n");
            builder.append("    runner: ").append(adapter).append("\n");
            builder.append("    execution_mode: ").append(executionMode).append("\n");
            builder.append("    environment_ref: ").append(text(unit, "environment_ref")).append("\n");
            builder.append("    provider_contract_ref: provider_contracts/")
                    .append(safeFileName(targetId))
                    .append(".yaml#adapters.")
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

    private String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void writeProviderFamilyDryRunArtifacts(Path packageRoot) throws Exception {
        clearApprovedTests(packageRoot);
        writeDryRunExpectedResult(packageRoot, "RP-FWK-SAMPLE-AC-API");
        writeDryRunExpectedResult(packageRoot, "RP-FWK-SAMPLE-AC-EVENTS");
        writeDryRunExpectedResult(packageRoot, "RP-FWK-SAMPLE-AC-DB");
        writeDryRunExpectedResult(packageRoot, "RP-FWK-SAMPLE-AC-K8S");
        writeDryRunExpectedResult(packageRoot, "RP-FWK-SAMPLE-AC-RUNNER");
        writeDryRunExpectedResult(packageRoot, "RP-FWK-SAMPLE-AC-BATCH");
        writeDryRunTestCase(
                packageRoot,
                "RP-FWK-SAMPLE-TC-API",
                "RP-FWK-SAMPLE-AC-API",
                "RU-payment-api",
                "request_response",
                "request_response",
                """
                  payment_payload:
                    ref: fixtures/api/payment_payload.json
                    bind_as: api_payload
                """);
        writeDryRunTestCase(
                packageRoot,
                "RP-FWK-SAMPLE-TC-EVENTS",
                "RP-FWK-SAMPLE-AC-EVENTS",
                "RU-payment-events",
                "message_bus",
                "messaging",
                """
                  payment_event:
                    ref: fixtures/events/payment_event.json
                    bind_as: message_event
                """);
        writeDryRunTestCase(
                packageRoot,
                "RP-FWK-SAMPLE-TC-DB",
                "RP-FWK-SAMPLE-AC-DB",
                "RU-payment-db",
                "db_fixture",
                "db_fixture",
                "");
        writeDryRunTestCase(
                packageRoot,
                "RP-FWK-SAMPLE-TC-K8S",
                "RP-FWK-SAMPLE-AC-K8S",
                "RU-payment-k8s",
                "k8s_readiness",
                "deployment_readiness",
                "");
        writeDryRunTestCase(
                packageRoot,
                "RP-FWK-SAMPLE-TC-RUNNER",
                "RP-FWK-SAMPLE-AC-RUNNER",
                "RU-legacy-runner",
                "external_runner",
                "external_runner",
                "");
        writeDryRunTestCase(
                packageRoot,
                "RP-FWK-SAMPLE-TC-BATCH",
                "RP-FWK-SAMPLE-AC-BATCH",
                "RU-batch-job",
                "spring_boot_cli",
                "file_batch",
                """
                  orders_seed:
                    ref: fixtures/db/orders_seed.yaml
                    bind_as: db_seed
                """);
    }

    private void clearApprovedTests(Path packageRoot) throws Exception {
        Path approvedDir = packageRoot.resolve("tests/approved");
        Files.createDirectories(approvedDir);
        try (Stream<Path> paths = Files.list(approvedDir)) {
            for (Path path : paths.toList()) {
                if (path.getFileName().toString().endsWith(".yaml")) {
                    Files.delete(path);
                }
            }
        }
    }

    private void writeDryRunExpectedResult(Path packageRoot, String acId) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path expected = packageRoot.resolve("expected-results/approved/" + expectedResultId + ".yaml");
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, """
                expected_result_id: %s
                rp_id: RP-FWK-SAMPLE
                ac_id: %s
                status: approved_for_regression
                source_refs:
                  - acceptance_criteria.md#%s
                input_refs: []
                expected_outputs:
                  output_ref: expected/output/%s.txt
                assumptions: []
                unresolved_gaps: []
                approved_by: platform
                approved_at: 2026-06-27T00:00:00+08:00
                approval_ref: FRAMEWORK_VERIFICATION_FIXTURE.md
                blocked_reason: null
                """.formatted(expectedResultId, acId, acId, acId));
    }

    private void writeDryRunTestCase(
            Path packageRoot,
            String testCaseId,
            String acId,
            String ruId,
            String adapter,
            String capability,
            String inputsYaml) throws Exception {
        String expectedResultId = acId.replace("-AC-", "-ER-");
        Path testCase = packageRoot.resolve("tests/approved/" + testCaseId + ".yaml");
        Files.createDirectories(testCase.getParent());
        Files.writeString(testCase, """
                dsl_version: v1
                test_case_id: %s
                rp_id: RP-FWK-SAMPLE
                ac_id: %s
                artifact_status: approved_for_regression
                revision: 1
                source_refs:
                  acceptance_criteria: acceptance_criteria.md#%s
                source_fingerprint: sha256:provider-family-dry-run
                execution_target:
                %s\
                  adapter: %s
                  execution_mode: ci_ephemeral
                  environment_ref: ci://framework-verification/%s
                scenario:
                  type: integration
                  scope: release_package
                  capabilities: [%s, file_assertion]
                expected:
                  ref: expected-results/approved/%s.yaml
                oracles:
                  primary:
                    type: expected_result_artifact
                    ref: expected-results/approved/%s.yaml
                package_inputs:
                  inputs:
                %s
                steps:
                  - id: dry_run_provider
                    action: %s
                    target_ru_id: %s
                assertions:
                  - type: file_diff
                    oracle: ${oracles.primary}
                evidence_required:
                  - execution_log
                """.formatted(
                testCaseId,
                acId,
                acId,
                targetRuYaml(ruId),
                adapter,
                ruId,
                capability,
                expectedResultId,
                expectedResultId,
                inputsYaml.isBlank() ? "    {}\n" : inputsYaml.indent(4),
                firstAction(adapter),
                ruId));
    }

    private String targetRuYaml(String ruId) {
        if (ruId.isBlank()) {
            return "";
        }
        return "  ru_id: " + ruId + "\n";
    }

    private String firstAction(String adapter) {
        return switch (adapter) {
            case "request_response" -> "submit_payment";
            case "message_bus" -> "publish_payment_event";
            case "k8s_readiness" -> "check_readiness";
            default -> "call_ru";
        };
    }

    private String providerFamilyMapping() {
        return """
                rp_id: RP-HETEROGENEOUS
                release_units:
                  - ru_id: RU-payment-api
                    repo: /repo/payment-api
                    unit_type: service
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
                          endpoint_ref: env://PAYMENT_API
                          timeout_seconds: 10
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                              request_binding: payment_payload
                          outputs:
                            actual_output_ref: actual/response.json
                      bindings:
                        api_payload:
                          provider_family: request_response
                          provider_type: request_body
                          bind_as: request_body
                      fixtures: {}
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-payment-events
                    repo: /repo/payment-events
                    unit_type: service
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
                          provider_type: local
                          topic_ref: mock://payment.events
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/message.json
                      bindings:
                        message_event:
                          provider_family: messaging
                          provider_type: event_payload
                          bind_as: event_payload
                      fixtures: {}
                    evidence_responsibility: [execution_log]
                    dependencies: [RU-payment-api]
                  - ru_id: RU-payment-db
                    repo: /repo/payment-db
                    unit_type: database
                    owner: product_developer
                    version_ref: schema-789
                    validation_boundary: state_fixture
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/db
                    adapter: db_fixture
                    provider_contracts:
                      adapters:
                        db_fixture:
                          provider_family: db_fixture
                          provider_type: relational_db
                          command: validate-db-fixture
                      fixtures:
                        relational_db:
                          provider_family: db_fixture
                          provider_type: jdbc
                          connection_ref: secret://ci/payment-db
                          isolation_key: test_run_id
                          cleanup_strategy: by_test_run_id
                    evidence_responsibility: [cleanup_result]
                    dependencies: []
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
                          deployment_ref: fixtures/readiness/payment-api.ready
                          deployed_version_ref: deploy-123
                          timeout_seconds: 10
                          outputs:
                            actual_output_ref: actual/readiness.txt
                    evidence_responsibility: [readiness_result]
                    dependencies: []
                  - ru_id: RU-legacy-runner
                    repo: /repo/legacy-runner
                    unit_type: external_test_runner
                    owner: qa
                    version_ref: runner-42
                    validation_boundary: external_runner
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://runner
                    adapter: external_runner
                    provider_contracts:
                      adapters:
                        external_runner:
                          provider_family: external_runner
                          provider_type: command_runner
                          approval_ref: docs/10-change-control/runner-approval.md
                          approved_by: release_owner
                          reason: legacy harness cannot use a reusable built-in provider yet
                          command: ./run-legacy-check.sh
                          timeout_seconds: 60
                          inputs:
                            request: fixtures/legacy/request.json
                          outputs:
                            result: evidence/legacy/result.json
                          evidence_map:
                            runner_log: logs/legacy-runner.log
                    evidence_responsibility: [runner_result]
                    dependencies: []
                  - ru_id: RU-batch-job
                    repo: /repo/batch-job
                    unit_type: batch
                    owner: product_developer
                    version_ref: batch-100
                    validation_boundary: file_batch
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://batch
                    adapter: spring_boot_cli
                    provider_contracts:
                      adapters:
                        spring_boot_cli:
                          provider_family: file_batch
                          provider_type: shell
                          command: java -jar batch.jar
                          timeout_seconds: 60
                          logs:
                            stdout: logs/batch-stdout.log
                            stderr: logs/batch-stderr.log
                          outputs:
                            actual_output_ref: actual/batch-output.txt
                      bindings:
                        db_seed:
                          provider_family: file_batch
                          provider_type: file_fixture
                          materialize_as: input_file
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """;
    }

    private String ambiguousRequestResponseMapping() {
        return """
                rp_id: RP-FWK-SAMPLE
                release_units:
                  - ru_id: RU-payment-api-blue
                    repo: /repo/payment-api-blue
                    unit_type: service
                    owner: product_developer
                    version_ref: build-blue
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api-blue
                    adapter: request_response
                    provider_contracts:
                      adapters:
                        request_response:
                          provider_family: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API_BLUE
                          timeout_seconds: 10
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                              request_binding: payment_payload
                          outputs:
                            actual_output_ref: actual/blue-response.json
                      bindings:
                        api_payload:
                          provider_family: request_response
                          provider_type: request_body
                          bind_as: request_body
                    evidence_responsibility: [execution_log]
                    dependencies: []
                  - ru_id: RU-payment-api-green
                    repo: /repo/payment-api-green
                    unit_type: service
                    owner: product_developer
                    version_ref: build-green
                    validation_boundary: request_response_api
                    execution_mode: ci_ephemeral
                    deployment_required: false
                    environment_ref: ci://payment/api-green
                    adapter: request_response
                    provider_contracts:
                      adapters:
                        request_response:
                          provider_family: request_response
                          provider_type: rest
                          endpoint_ref: env://PAYMENT_API_GREEN
                          timeout_seconds: 10
                          actions:
                            submit_payment:
                              method: POST
                              path: /payments
                              request_binding: payment_payload
                          outputs:
                            actual_output_ref: actual/green-response.json
                      bindings:
                        api_payload:
                          provider_family: request_response
                          provider_type: request_body
                          bind_as: request_body
                    evidence_responsibility: [execution_log]
                    dependencies: []
                """;
    }

    private void copyResourceDirectory(String resourceName, Path target) throws Exception {
        URL resource = getClass().getClassLoader().getResource(resourceName);
        assertThat(resource).as("sample Product Repo fixture resource").isNotNull();
        Path source = Path.of(resource.toURI());
        try (Stream<Path> paths = Files.walk(source)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private PrintStream print(ByteArrayOutputStream output) {
        return new PrintStream(output);
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
