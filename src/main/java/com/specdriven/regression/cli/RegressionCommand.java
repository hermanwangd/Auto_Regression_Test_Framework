package com.specdriven.regression.cli;

import com.specdriven.regression.binding.BindingGap;
import com.specdriven.regression.binding.BindingResolutionReport;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.discovery.ReleasePackageCompletenessReport;
import com.specdriven.regression.discovery.ReleasePackageGap;
import com.specdriven.regression.discovery.ReleasePackageResult;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.environment.ExecutionEnvironmentGap;
import com.specdriven.regression.environment.ExecutionEnvironmentReport;
import com.specdriven.regression.environment.ExecutionEnvironmentResolver;
import com.specdriven.regression.execution.ExecutionEngine;
import com.specdriven.regression.execution.ExecutionResult;
import com.specdriven.regression.evidence.EvidenceWriter;
import com.specdriven.regression.expectedresult.ExpectedResultDraftResult;
import com.specdriven.regression.expectedresult.ExpectedResultEligibilityReport;
import com.specdriven.regression.expectedresult.ExpectedResultGap;
import com.specdriven.regression.expectedresult.ExpectedResultService;
import com.specdriven.regression.fixture.FixtureLifecycleGap;
import com.specdriven.regression.fixture.FixtureLifecycleReport;
import com.specdriven.regression.fixture.FixtureLifecycleService;
import com.specdriven.regression.mapping.RpRuMappingGap;
import com.specdriven.regression.mapping.RpRuMappingService;
import com.specdriven.regression.mapping.RpRuMappingValidationReport;
import com.specdriven.regression.oracle.OracleReadinessGap;
import com.specdriven.regression.oracle.OracleReadinessReport;
import com.specdriven.regression.oracle.OracleReadinessService;
import com.specdriven.regression.productrepo.ProductRepoReadinessReport;
import com.specdriven.regression.productrepo.ProductRepoResult;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.specdriven.regression.productrepo.ReadinessGap;
import com.specdriven.regression.provider.ProviderContractGap;
import com.specdriven.regression.provider.ProviderContractResolutionReport;
import com.specdriven.regression.provider.ProviderContractResolver;
import com.specdriven.regression.provider.ResolvedProviderContract;
import com.specdriven.regression.report.CoverageReportResult;
import com.specdriven.regression.report.CoverageReportService;
import com.specdriven.regression.readiness.AcIntakeReport;
import com.specdriven.regression.readiness.AcIntakeService;
import com.specdriven.regression.readiness.AcReadinessGap;
import com.specdriven.regression.readiness.AcReadinessItem;
import com.specdriven.regression.schema.ArtifactValidationError;
import com.specdriven.regression.testcase.ExecutionContextReadiness;
import com.specdriven.regression.testcase.TestCaseDraftResult;
import com.specdriven.regression.testcase.TestCaseLifecycleService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class RegressionCommand {

    private final ProductRepoService productRepoService;
    private final ReleasePackageService releasePackageService;
    private final AcIntakeService acIntakeService;
    private final RpRuMappingService rpRuMappingService;
    private final TestCaseLifecycleService testCaseLifecycleService;
    private final ExpectedResultService expectedResultService;
    private final ExecutionEnvironmentResolver executionEnvironmentResolver;
    private final BindingResolver bindingResolver;
    private final ProviderContractResolver providerContractResolver;
    private final FixtureLifecycleService fixtureLifecycleService;
    private final ExecutionEngine executionEngine;
    private final OracleReadinessService oracleReadinessService;
    private final EvidenceWriter evidenceWriter;
    private final CoverageReportService coverageReportService;

    public RegressionCommand(ProductRepoService productRepoService, ReleasePackageService releasePackageService) {
        this(
                productRepoService,
                releasePackageService,
                new AcIntakeService(),
                new RpRuMappingService(),
                new TestCaseLifecycleService(),
                new ExpectedResultService(),
                new ExecutionEnvironmentResolver(),
                new BindingResolver(),
                new ProviderContractResolver(),
                new FixtureLifecycleService(),
                new ExecutionEngine(),
                new OracleReadinessService(),
                new EvidenceWriter(),
                new CoverageReportService());
    }

    public RegressionCommand(
            ProductRepoService productRepoService,
            ReleasePackageService releasePackageService,
            AcIntakeService acIntakeService,
            RpRuMappingService rpRuMappingService,
            TestCaseLifecycleService testCaseLifecycleService,
            ExpectedResultService expectedResultService,
            ExecutionEnvironmentResolver executionEnvironmentResolver,
            BindingResolver bindingResolver,
            ProviderContractResolver providerContractResolver,
            FixtureLifecycleService fixtureLifecycleService,
            ExecutionEngine executionEngine,
            OracleReadinessService oracleReadinessService,
            EvidenceWriter evidenceWriter,
            CoverageReportService coverageReportService) {
        this.productRepoService = productRepoService;
        this.releasePackageService = releasePackageService;
        this.acIntakeService = acIntakeService;
        this.rpRuMappingService = rpRuMappingService;
        this.testCaseLifecycleService = testCaseLifecycleService;
        this.expectedResultService = expectedResultService;
        this.executionEnvironmentResolver = executionEnvironmentResolver;
        this.bindingResolver = bindingResolver;
        this.providerContractResolver = providerContractResolver;
        this.fixtureLifecycleService = fixtureLifecycleService;
        this.executionEngine = executionEngine;
        this.oracleReadinessService = oracleReadinessService;
        this.evidenceWriter = evidenceWriter;
        this.coverageReportService = coverageReportService;
    }

    public int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("Missing command.");
            return 2;
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        Path root = Path.of(options.getOrDefault("--root", "."));

        return switch (command) {
            case "init-product-repo" -> initProductRepo(root, out);
            case "check-readiness" -> checkReadiness(root, options, out);
            case "init-rp" -> initReleasePackage(root, options, out, err);
            case "check-rp" -> checkReleasePackage(root, options, out, err);
            case "generate-tests" -> generateTests(root, options, out, err);
            case "draft-expected-results" -> draftExpectedResults(root, options, out, err);
            case "run" -> runRegression(root, options, out, err);
            case "report" -> report(root, options, out, err);
            default -> {
                err.println("Unknown command: " + command);
                yield 2;
            }
        };
    }

    private int report(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String rpId = requiredOption(options, "--rp-id", err);
        String batchId = options.get("--batch-id");
        String runId = options.get("--run-id");
        if (rpId == null) {
            return 2;
        }
        if ((batchId == null || batchId.isBlank()) && (runId == null || runId.isBlank())) {
            err.println("Missing required option: --batch-id");
            return 2;
        }
        Path packageRoot = packageRoot(root, rpId);
        CoverageReportResult result = batchId == null || batchId.isBlank()
                ? coverageReportService.generate(packageRoot, runId)
                : coverageReportService.generateBatch(packageRoot, batchId);
        out.println("report_status: " + (result.reviewReady() ? "review_ready" : "not_review_ready"));
        out.println("coverage_percent: " + result.coveragePercent());
        out.println("covered: " + result.covered());
        out.println("total_automatable: " + result.totalAutomatable());
        out.println("review_dir: " + packageRoot.relativize(result.reviewDir()));
        out.println("gaps:");
        for (String gap : result.gaps()) {
            out.println("  - " + gap);
        }
        return result.reviewReady() ? 0 : 1;
    }

    private int initProductRepo(Path root, PrintStream out) {
        ProductRepoResult result = productRepoService.initialize(root);
        out.println("status: pass");
        out.println("created_count: " + result.createdPaths().size());
        out.println("skipped_existing_count: " + result.skippedExistingPaths().size());
        return 0;
    }

    private int checkReadiness(Path root, Map<String, String> options, PrintStream out) {
        ProductRepoReadinessReport report = productRepoService.checkReadiness(root);
        out.println("status: " + report.status());
        out.println("ready: " + report.ready());
        out.println("next_required_step: " + report.nextRequiredStep());
        out.println("gaps:");
        for (ReadinessGap gap : report.gaps()) {
            out.println("  - path: " + gap.path());
            out.println("    owner_action: " + gap.ownerAction());
        }
        String rpId = options.get("--rp-id");
        if (options.containsKey("--write-report") && rpId != null && !rpId.isBlank()) {
            Path relativeReport = Path.of("docs/08-release/release-packages")
                    .resolve(rpId)
                    .resolve("evidence/readiness/readiness.yaml");
            writeReadinessReport(root.resolve(relativeReport), report);
            out.println("report_path: " + relativeReport);
        }
        return report.ready() ? 0 : 1;
    }

    private void writeReadinessReport(Path reportPath, ProductRepoReadinessReport report) {
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, readinessReportYaml(report));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write readiness report: " + reportPath, e);
        }
    }

    private String readinessReportYaml(ProductRepoReadinessReport report) {
        return """
                status: %s
                ready: %s
                next_required_step: %s
                rp_scope_invented: %s
                rp_ru_membership_invented: %s
                checked_items:
                %s
                gaps:
                %s
                """.formatted(
                report.status(),
                report.ready(),
                report.nextRequiredStep(),
                report.rpScopeInvented(),
                report.rpRuMembershipInvented(),
                yamlList(report.checkedItems()),
                readinessGapYaml(report.gaps()));
    }

    private String yamlList(List<String> values) {
        if (values.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append("  - ").append(value).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private String readinessGapYaml(List<ReadinessGap> gaps) {
        if (gaps.isEmpty()) {
            return "  []";
        }
        StringBuilder builder = new StringBuilder();
        for (ReadinessGap gap : gaps) {
            builder.append("  - path: ").append(gap.path()).append("\n");
            builder.append("    owner_action: ").append(gap.ownerAction()).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private int initReleasePackage(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String rpId = requiredOption(options, "--rp-id", err);
        String packageType = requiredOption(options, "--package-type", err);
        if (rpId == null || packageType == null) {
            return 2;
        }
        ReleasePackageResult result = releasePackageService.initialize(root, rpId, packageType);
        out.println("status: pass");
        out.println("package_root: " + result.packageRoot());
        return 0;
    }

    private int checkReleasePackage(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String rpId = requiredOption(options, "--rp-id", err);
        if (rpId == null) {
            return 2;
        }
        boolean strictSchema = options.containsKey("--strict-schema");
        ReleasePackageCompletenessReport report = releasePackageService.checkCompleteness(root, rpId, strictSchema);
        Path packageRoot = packageRoot(root, rpId);
        out.println("status: " + report.status());
        out.println("complete: " + report.complete());
        out.println("gaps:");
        for (ReleasePackageGap gap : report.gaps()) {
            out.println("  - path: " + gap.path());
            out.println("    owner_action: " + gap.ownerAction());
        }
        out.println("package_schema_errors:");
        for (ArtifactValidationError error : report.packageSchemaErrors()) {
            out.println("  - field_path: " + error.fieldPath());
            out.println("    blocks: " + error.blocks());
            out.println("    owner_action: " + error.ownerAction());
        }
        out.println("mapping_gaps:");
        for (RpRuMappingGap gap : report.mappingGaps()) {
            out.println("  - field_path: " + gap.fieldPath());
            out.println("    blocks_execution: " + gap.blocksExecution());
            out.println("    owner_action: " + gap.ownerAction());
        }
        boolean acReady = true;
        if (options.containsKey("--include-ac-readiness")) {
            acReady = writeAcReadiness(packageRoot.resolve("acceptance_criteria.md"), out);
        }
        boolean expectedResultsReady = true;
        if (options.containsKey("--include-expected-results")) {
            expectedResultsReady = writeExpectedResultEligibility(packageRoot, out);
        }
        return report.complete() && acReady && expectedResultsReady ? 0 : 1;
    }

    private int generateTests(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String rpId = requiredOption(options, "--rp-id", err);
        if (rpId == null) {
            return 2;
        }
        Path packageRoot = packageRoot(root, rpId);
        AcIntakeReport acReport = acIntakeService.intake(packageRoot.resolve("acceptance_criteria.md"));
        ExecutionContextReadiness context = executionContext(packageRoot);
        boolean blocked = false;
        out.println("generated_tests:");
        for (AcReadinessItem item : acReport.items()) {
            TestCaseDraftResult result = testCaseLifecycleService.generateDraft(packageRoot, item, context);
            if ("none".equals(result.generatedArtifactType())) {
                blocked = true;
            }
            out.println("  - ac_id: " + item.acId());
            out.println("    generated_artifact_type: " + result.generatedArtifactType());
            if (result.writtenPath() != null) {
                out.println("    path: " + packageRoot.relativize(result.writtenPath()));
            }
            for (String gap : result.gaps()) {
                out.println("    gap: " + gap);
            }
        }
        return blocked ? 1 : 0;
    }

    private int runRegression(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String rpId = requiredOption(options, "--rp-id", err);
        if (rpId == null) {
            return 2;
        }
        Path packageRoot = packageRoot(root, rpId);
        String requestedEnv = options.getOrDefault("--env", "");
        boolean dryRun = options.containsKey("--dry-run");
        PreflightResult preflight = runPreflight(packageRoot, requestedEnv, dryRun, out);
        if (dryRun) {
            return preflight.blocked() ? 1 : 0;
        }
        if (preflight.blocked()) {
            String batchId = nextAvailableId(packageRoot.resolve("evidence/batches"), "BATCH");
            String runId = nextAvailableId(packageRoot.resolve("evidence/runs"), "RUN");
            String batchStartedAt = java.time.OffsetDateTime.now().toString();
            ExecutionResult blockedRun = evidenceWriter.writeBlockedRun(
                    packageRoot,
                    batchId,
                    runId,
                    preflight.approvedTests(),
                    preflight.failureDetails(),
                    preflight.executionMode(),
                    preflight.environmentRef());
            evidenceWriter.writeExecutionBatch(
                    packageRoot,
                    batchId,
                    preflight.executionMode(),
                    preflight.environmentRef(),
                    batchStartedAt,
                    "blocked",
                    List.of(blockedRun));
            out.println("adapter_execution_started: false");
            out.println("batch_id: " + batchId);
            out.println("run_id: " + runId);
            out.println("run_status: blocked");
            return 1;
        }

        out.println("adapter_execution_started: true");
        String batchId = nextAvailableId(packageRoot.resolve("evidence/batches"), "BATCH");
        String batchStartedAt = java.time.OffsetDateTime.now().toString();
        int runNumber = nextAvailableNumber(packageRoot.resolve("evidence/runs"), "RUN");
        boolean passed = true;
        List<ExecutionResult> results = new java.util.ArrayList<>();
        out.println("batch_id: " + batchId);
        out.println("execution_results:");
        for (Path approvedTest : preflight.approvedTests()) {
            String runId = formatSequentialId("RUN", runNumber++);
            while (Files.exists(packageRoot.resolve("evidence/runs").resolve(runId))) {
                runId = formatSequentialId("RUN", runNumber++);
            }
            ExecutionResult result = executionEngine.execute(packageRoot, approvedTest, batchId, runId);
            results.add(result);
            passed = passed && result.passed();
            out.println("  - test_case_id: " + result.testCaseId());
            out.println("    ac_id: " + result.acId());
            out.println("    run_id: " + result.runId());
            out.println("    run_dir: " + packageRoot.relativize(result.runDir()));
            out.println("    status: " + result.status());
            out.println("    exit_code: " + result.exitCode());
            out.println("    timeout: " + result.timeout());
            out.println("    stdout: " + result.runDir().relativize(result.stdoutLog()));
            out.println("    stderr: " + result.runDir().relativize(result.stderrLog()));
        }
        String runStatus = passed ? "passed" : "failed";
        evidenceWriter.writeExecutionBatch(
                packageRoot,
                batchId,
                preflight.executionMode(),
                preflight.environmentRef(),
                batchStartedAt,
                runStatus,
                results);
        out.println("run_status: " + runStatus);
        return passed ? 0 : 1;
    }

    private String nextAvailableId(Path parent, String prefix) {
        return formatSequentialId(prefix, nextAvailableNumber(parent, prefix));
    }

    private int nextAvailableNumber(Path parent, String prefix) {
        int number = 1;
        while (Files.exists(parent.resolve(formatSequentialId(prefix, number)))) {
            number++;
        }
        return number;
    }

    private String formatSequentialId(String prefix, int number) {
        return "%s-%03d".formatted(prefix, number);
    }

    private PreflightResult runPreflight(Path packageRoot, String requestedEnv, boolean dryRun, PrintStream out) {
        ExecutionEnvironmentReport environmentReport =
                executionEnvironmentResolver.resolve(packageRoot.resolve("rp_ru_mapping.yaml"), requestedEnv);
        List<Path> approvedTests = approvedTests(packageRoot);
        List<String> failureDetails = new java.util.ArrayList<>();
        boolean blocked = !environmentReport.ready() || approvedTests.isEmpty();

        if (dryRun) {
            out.println("adapter_execution_started: false");
        }
        out.println("environment_gaps:");
        for (ExecutionEnvironmentGap gap : environmentReport.gaps()) {
            out.println("  - ap: Discovery and Context");
            out.println("    field_path: " + gap.fieldPath());
            out.println("    owner_action: " + gap.ownerAction());
            failureDetails.add(failureDetail(
                    "Discovery and Context",
                    gap.fieldPath(),
                    gap.ownerAction()));
        }
        if (approvedTests.isEmpty()) {
            blocked = true;
            out.println("test_case_gaps:");
            out.println("  - ap: Definition and Validation");
            out.println("    field_path: tests/approved");
            out.println("    owner_action: Add approved_for_regression DSL test cases before run.");
            failureDetails.add(failureDetail(
                    "Definition and Validation",
                    "tests/approved",
                    "Add approved_for_regression DSL test cases before run."));
        }

        out.println("binding_gaps:");
        for (Path approvedTest : approvedTests) {
            BindingResolutionReport bindingReport = bindingResolver.resolve(approvedTest);
            blocked = blocked || !bindingReport.ready();
            for (BindingGap gap : bindingReport.gaps()) {
                out.println("  - ap: Planning and Binding");
                out.println("    test_case_id: " + gap.testCaseId());
                out.println("    ac_id: " + gap.acId());
                out.println("    field_path: " + gap.fieldPath());
                out.println("    binding_name: " + gap.bindingName());
                out.println("    binding_type: " + gap.bindingType());
                out.println("    owner_action: " + gap.ownerAction());
                failureDetails.add(failureDetail(
                        "Planning and Binding",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + gap.testCaseId(),
                        "ac_id: " + gap.acId(),
                        "binding_name: " + gap.bindingName(),
                        "binding_type: " + gap.bindingType()));
            }
            ExpectedResultEligibilityReport eligibility =
                    expectedResultService.checkEligibility(packageRoot, bindingReport.acId());
            blocked = blocked || !eligibility.eligible();
            out.println("expected_result_eligibility:");
            out.println("  - ap: Oracle and Assertion Engine");
            out.println("    ac_id: " + bindingReport.acId());
            out.println("    eligible: " + eligibility.eligible());
            out.println("    status: " + eligibility.status());
            for (ExpectedResultGap gap : eligibility.gaps()) {
                out.println("    field_path: " + gap.fieldPath());
                out.println("    owner_action: " + gap.ownerAction());
                failureDetails.add(failureDetail(
                        "Oracle and Assertion Engine",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "ac_id: " + bindingReport.acId()));
            }
            OracleReadinessReport oracleReport = oracleReadinessService.check(approvedTest);
            blocked = blocked || !oracleReport.ready();
            out.println("oracle_gaps:");
            for (OracleReadinessGap gap : oracleReport.gaps()) {
                out.println("  - ap: Oracle and Assertion Engine");
                out.println("    test_case_id: " + gap.testCaseId());
                out.println("    ac_id: " + gap.acId());
                out.println("    field_path: " + gap.fieldPath());
                out.println("    oracle_type: " + gap.oracleType());
                out.println("    owner_action: " + gap.ownerAction());
                failureDetails.add(failureDetail(
                        "Oracle and Assertion Engine",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + gap.testCaseId(),
                        "ac_id: " + gap.acId(),
                        "oracle_type: " + gap.oracleType()));
            }
            FixtureLifecycleReport fixtureReport = fixtureLifecycleService.validate(approvedTest);
            blocked = blocked || !fixtureReport.ready();
            out.println("fixture_gaps:");
            for (FixtureLifecycleGap gap : fixtureReport.gaps()) {
                out.println("  - ap: Fixture and State Manager");
                out.println("    test_case_id: " + bindingReport.testCaseId());
                out.println("    ac_id: " + bindingReport.acId());
                out.println("    field_path: " + gap.fieldPath());
                out.println("    owner_action: " + gap.ownerAction());
                failureDetails.add(failureDetail(
                        "Fixture and State Manager",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + bindingReport.testCaseId(),
                        "ac_id: " + bindingReport.acId()));
            }

            ProviderContractResolutionReport providerReport = providerContractResolver.resolve(
                    packageRoot.resolve("rp_ru_mapping.yaml"),
                    adapterName(approvedTest),
                    bindingReport.resolvedBindings().stream().map(ResolvedBinding::bindingType).toList(),
                    fixtureReport.fixtureProviders());
            blocked = blocked || !providerReport.ready();
            out.println("provider_contracts_used:");
            for (ResolvedProviderContract contract : providerReport.resolvedContracts()) {
                out.println("  - ap: Planning and Binding");
                out.println("    test_case_id: " + bindingReport.testCaseId());
                out.println("    ac_id: " + bindingReport.acId());
                out.println("    contract_type: " + contract.contractType());
                out.println("    provider_name: " + contract.providerName());
                out.println("    provider_family: " + contract.providerFamily());
                out.println("    provider_type: " + contract.providerType());
                out.println("    registry_status: " + contract.registryStatus());
                out.println("    runtime_status: " + contract.runtimeStatus());
                out.println("    affected_ru: " + contract.affectedRu());
                out.println("    capability: " + contract.capability());
                out.println("    contract_path: " + contract.contractPath());
                out.println("    source_level: " + contract.sourceLevel());
            }
            out.println("provider_contract_gaps:");
            for (ProviderContractGap gap : providerReport.gaps()) {
                out.println("  - ap: Planning and Binding");
                out.println("    test_case_id: " + bindingReport.testCaseId());
                out.println("    ac_id: " + bindingReport.acId());
                out.println("    contract_path: " + gap.fieldPath());
                out.println("    contract_type: " + gap.contractType());
                out.println("    provider_name: " + gap.providerName());
                out.println("    provider_family: " + gap.providerFamily());
                out.println("    provider_type: " + gap.providerType());
                out.println("    registry_status: " + gap.registryStatus());
                out.println("    runtime_status: " + gap.runtimeStatus());
                out.println("    affected_ru: " + gap.affectedRu());
                out.println("    capability: " + gap.capability());
                out.println("    owner_action: " + gap.ownerAction());
                failureDetails.add(failureDetail(
                        "Planning and Binding",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + bindingReport.testCaseId(),
                        "ac_id: " + bindingReport.acId(),
                        "contract_path: " + gap.fieldPath(),
                        "contract_type: " + gap.contractType(),
                        "provider_name: " + gap.providerName(),
                        "provider_family: " + gap.providerFamily(),
                        "provider_type: " + gap.providerType(),
                        "registry_status: " + gap.registryStatus(),
                        "runtime_status: " + gap.runtimeStatus(),
                        "affected_ru: " + gap.affectedRu(),
                        "capability: " + gap.capability()));
            }
        }

        if (dryRun) {
            out.println("run_status: " + (blocked ? "blocked" : "dry_run_ready"));
        }
        return new PreflightResult(
                blocked,
                approvedTests,
                List.copyOf(failureDetails),
                environmentReport.executionMode(),
                environmentReport.environmentRef());
    }

    private String failureDetail(String ap, String fieldPath, String ownerAction, String... extraLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("ap: ").append(ap).append("\n");
        builder.append("field_path: ").append(fieldPath).append("\n");
        for (String extraLine : extraLines) {
            if (extraLine != null && !extraLine.isBlank()) {
                builder.append(extraLine).append("\n");
            }
        }
        builder.append("owner_action: ").append(ownerAction);
        return builder.toString();
    }

    private int draftExpectedResults(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String rpId = requiredOption(options, "--rp-id", err);
        if (rpId == null) {
            return 2;
        }
        Path packageRoot = packageRoot(root, rpId);
        AcIntakeReport acReport = acIntakeService.intake(packageRoot.resolve("acceptance_criteria.md"));
        boolean blocked = false;
        out.println("expected_results:");
        for (AcReadinessItem item : acReport.items()) {
            ExpectedResultDraftResult result = expectedResultService.draftExpectedResult(
                    packageRoot,
                    item,
                    item.inputRef().isBlank() ? List.of() : List.of(item.inputRef()),
                    item.expectedOutputRef().isBlank() ? "pending" : item.expectedOutputRef());
            blocked = blocked || "blocked".equals(result.status());
            out.println("  - ac_id: " + item.acId());
            out.println("    expected_result_status: " + result.status());
            out.println("    path: " + packageRoot.relativize(result.writtenPath()));
        }
        return blocked ? 1 : 0;
    }

    private boolean writeAcReadiness(Path acceptanceCriteriaFile, PrintStream out) {
        AcIntakeReport report = acIntakeService.intake(acceptanceCriteriaFile);
        boolean ready = true;
        out.println("ac_readiness:");
        for (AcReadinessItem item : report.items()) {
            out.println("  - ac_id: " + item.acId());
            out.println("    readiness: " + item.readiness());
            out.println("    classification: " + item.classification());
            out.println("    owner_authored_truth_preserved: " + item.ownerAuthoredTruthPreserved());
            for (AcReadinessGap gap : item.gaps()) {
                out.println("    gap: " + gap.fieldPath());
                out.println("    owner_action: " + gap.ownerAction());
            }
            ready = ready && item.executableDraftAllowed();
        }
        return ready;
    }

    private boolean writeExpectedResultEligibility(Path packageRoot, PrintStream out) {
        AcIntakeReport report = acIntakeService.intake(packageRoot.resolve("acceptance_criteria.md"));
        boolean eligible = true;
        out.println("expected_result_eligibility:");
        for (AcReadinessItem item : report.items()) {
            ExpectedResultEligibilityReport eligibility =
                    expectedResultService.checkEligibility(packageRoot, item.acId());
            out.println("  - ac_id: " + item.acId());
            out.println("    eligible: " + eligibility.eligible());
            out.println("    status: " + eligibility.status());
            for (ExpectedResultGap gap : eligibility.gaps()) {
                out.println("    gap: " + gap.fieldPath());
                out.println("    owner_action: " + gap.ownerAction());
            }
            eligible = eligible && eligibility.eligible();
        }
        return eligible;
    }

    private ExecutionContextReadiness executionContext(Path packageRoot) {
        RpRuMappingValidationReport mappingReport = rpRuMappingService.validate(packageRoot.resolve("rp_ru_mapping.yaml"));
        if (!mappingReport.valid() || mappingReport.releaseUnits().isEmpty()) {
            List<String> gaps = mappingReport.gaps().stream().map(RpRuMappingGap::fieldPath).toList();
            return ExecutionContextReadiness.incomplete(gaps);
        }
        var firstUnit = mappingReport.releaseUnits().get(0);
        return ExecutionContextReadiness.ready(
                firstUnit.ruId(),
                firstUnit.adapter(),
                firstUnit.executionMode(),
                firstUnit.environmentRef(),
                List.of("file_input", "batch_execution", "file_assertion"));
    }

    private Path packageRoot(Path root, String rpId) {
        return root.resolve("docs/08-release/release-packages").resolve(rpId);
    }

    private List<Path> approvedTests(Path packageRoot) {
        Path approvedDir = packageRoot.resolve("tests/approved");
        if (!Files.isDirectory(approvedDir)) {
            return List.of();
        }
        try (var paths = Files.list(approvedDir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list approved tests: " + approvedDir, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path path) {
        try {
            Object loaded = new Yaml().load(Files.readString(path));
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read YAML artifact: " + path, e);
        }
    }

    private String adapterName(Path testCasePath) {
        Object executionTarget = readYamlMap(testCasePath).get("execution_target");
        if (executionTarget instanceof Map<?, ?> target) {
            Object adapter = target.get("adapter");
            return adapter == null ? "" : adapter.toString();
        }
        return "";
    }

    private record PreflightResult(
            boolean blocked,
            List<Path> approvedTests,
            List<String> failureDetails,
            String executionMode,
            String environmentRef) {
    }

    private Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(args[i], args[i + 1]);
                i++;
            } else if (args[i].startsWith("--")) {
                options.put(args[i], "true");
            }
        }
        return options;
    }

    private String requiredOption(Map<String, String> options, String name, PrintStream err) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            err.println("Missing required option: " + name);
            return null;
        }
        return value;
    }
}
