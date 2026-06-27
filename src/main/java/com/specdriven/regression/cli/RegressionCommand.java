package com.specdriven.regression.cli;

import com.specdriven.regression.binding.BindingGap;
import com.specdriven.regression.binding.BindingResolutionReport;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.discovery.ReleasePackageCompletenessReport;
import com.specdriven.regression.discovery.ReleasePackageGap;
import com.specdriven.regression.discovery.ReleasePackageResult;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.dsl.DslTestCaseNormalizer;
import com.specdriven.regression.dsl.DslTestCaseValidator;
import com.specdriven.regression.dsl.DslValidationGap;
import com.specdriven.regression.dsl.DslValidationReport;
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

    private static final List<String> AP_GATE_NAMES = List.of(
            "Discovery and Context",
            "Definition and Validation",
            "Planning and Binding",
            "Fixture and State Manager",
            "Execution Engine",
            "Oracle and Assertion Engine",
            "Evidence and Reporting");

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
    private final DslTestCaseNormalizer dslTestCaseNormalizer = new DslTestCaseNormalizer();
    private final DslTestCaseValidator dslTestCaseValidator = new DslTestCaseValidator();

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
            out.println("    reason: missing_product_repo_path");
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
            builder.append("    reason: missing_product_repo_path\n");
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
            out.println("    reason: missing_required_rp_artifact");
            out.println("    owner_action: " + gap.ownerAction());
        }
        out.println("package_schema_errors:");
        for (ArtifactValidationError error : report.packageSchemaErrors()) {
            out.println("  - field_path: " + error.fieldPath());
            out.println("    reason: artifact_schema_validation_failed");
            out.println("    blocks: " + error.blocks());
            out.println("    owner_action: " + error.ownerAction());
        }
        out.println("mapping_gaps:");
        for (RpRuMappingGap gap : report.mappingGaps()) {
            out.println("  - field_path: " + gap.fieldPath());
            out.println("    reason: rp_ru_mapping_readiness_failed");
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
                printGenerationGap(out, item, gap);
            }
        }
        return blocked ? 1 : 0;
    }

    private void printGenerationGap(PrintStream out, AcReadinessItem item, String gap) {
        boolean acReadinessGap = "AC is not ready for generation".equals(gap);
        out.println("    ap: " + (acReadinessGap ? "Definition and Validation" : "Planning and Binding"));
        out.println("    field_path: " + (acReadinessGap ? "acceptance_criteria.md#" + item.acId() : gap));
        out.println("    reason: " + (acReadinessGap ? "ac_readiness_gap" : "execution_context_incomplete"));
        out.println("    gap: " + gap);
        out.println("    owner_action: " + (acReadinessGap
                ? "Clarify owner-authored AC before executable test generation."
                : "Complete RP/RU mapping execution context before executable test generation."));
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
        if (preflight.globalBlocked()) {
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

        String batchId = nextAvailableId(packageRoot.resolve("evidence/batches"), "BATCH");
        String batchStartedAt = java.time.OffsetDateTime.now().toString();
        int runNumber = nextAvailableNumber(packageRoot.resolve("evidence/runs"), "RUN");
        boolean passed = true;
        List<ExecutionResult> results = new java.util.ArrayList<>();
        Map<String, String> ruStatuses = new LinkedHashMap<>();
        Path mappingYaml = packageRoot.resolve("rp_ru_mapping.yaml");
        List<Path> executionTests = dependencyOrderedApprovedTests(packageRoot, preflight.approvedTests());
        out.println("adapter_execution_started: " + hasPreflightRunnableTest(preflight, executionTests));
        out.println("batch_id: " + batchId);
        out.println("execution_results:");
        for (Path approvedTest : executionTests) {
            List<String> preflightFailureDetails = preflight.failureDetails(approvedTest);
            if (!preflightFailureDetails.isEmpty()) {
                String runId = formatSequentialId("RUN", runNumber++);
                while (Files.exists(packageRoot.resolve("evidence/runs").resolve(runId))) {
                    runId = formatSequentialId("RUN", runNumber++);
                }
                String targetRuId = targetRuId(approvedTest);
                List<String> dependencies = targetDependencies(mappingYaml, targetRuId);
                ExecutionResult result = evidenceWriter.writeBlockedRun(
                        packageRoot,
                        batchId,
                        runId,
                        List.of(approvedTest),
                        preflightFailureDetails,
                        preflight.executionMode(),
                        preflight.environmentRef(),
                        dependencies);
                results.add(result);
                passed = false;
                recordRuStatus(ruStatuses, targetRuId, result.status());
                printExecutionResult(out, packageRoot, result);
                continue;
            }
            for (ParameterCase parameterCase : parameterCases(approvedTest)) {
                String runId = formatSequentialId("RUN", runNumber++);
                while (Files.exists(packageRoot.resolve("evidence/runs").resolve(runId))) {
                    runId = formatSequentialId("RUN", runNumber++);
                }
                Path executionTest = parameterizedTestCase(approvedTest, parameterCase);
                try {
                    String targetRuId = targetRuId(executionTest);
                    List<String> dependencies = targetDependencies(mappingYaml, targetRuId);
                    DependencyBlock dependencyBlock =
                            dependencyBlock(mappingYaml, executionTest, targetRuId, dependencies, ruStatuses);
                    ExecutionResult result;
                    if (dependencyBlock.blocked()) {
                        result = evidenceWriter.writeBlockedRun(
                                packageRoot,
                                batchId,
                                runId,
                                List.of(executionTest),
                                List.of(dependencyBlock.failureDetail()),
                                preflight.executionMode(),
                                preflight.environmentRef(),
                                dependencies);
                    } else {
                        result = executionEngine.execute(packageRoot, executionTest, batchId, runId);
                    }
                    results.add(result);
                    passed = passed && result.passed();
                    recordRuStatus(ruStatuses, targetRuId, result.status());
                    printExecutionResult(out, packageRoot, result);
                } finally {
                    deleteParameterizedTestCase(approvedTest, executionTest);
                }
            }
        }
        String runStatus = aggregateRunStatus(results);
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

    private void printExecutionResult(PrintStream out, Path packageRoot, ExecutionResult result) {
        out.println("  - test_case_id: " + result.testCaseId());
        out.println("    ac_id: " + result.acId());
        if (!result.parameterCaseId().isBlank()) {
            out.println("    parameter_case_id: " + result.parameterCaseId());
        }
        out.println("    run_id: " + result.runId());
        out.println("    run_dir: " + packageRoot.relativize(result.runDir()));
        out.println("    status: " + result.status());
        out.println("    exit_code: " + result.exitCode());
        out.println("    timeout: " + result.timeout());
        out.println("    stdout: " + result.runDir().relativize(result.stdoutLog()));
        out.println("    stderr: " + result.runDir().relativize(result.stderrLog()));
    }

    private String aggregateRunStatus(List<ExecutionResult> results) {
        if (results.stream().allMatch(ExecutionResult::passed)) {
            return "passed";
        }
        if (!results.isEmpty() && results.stream().allMatch(result -> "blocked".equals(result.status()))) {
            return "blocked";
        }
        return "failed";
    }

    private List<Path> dependencyOrderedApprovedTests(Path packageRoot, List<Path> approvedTests) {
        Map<String, Integer> ruOrder = new LinkedHashMap<>();
        RpRuMappingValidationReport mappingReport = rpRuMappingService.validate(packageRoot.resolve("rp_ru_mapping.yaml"));
        for (int index = 0; index < mappingReport.releaseUnits().size(); index++) {
            ruOrder.put(mappingReport.releaseUnits().get(index).ruId(), index);
        }
        return approvedTests.stream()
                .sorted((left, right) -> {
                    int leftOrder = ruOrder.getOrDefault(targetRuId(left), Integer.MAX_VALUE);
                    int rightOrder = ruOrder.getOrDefault(targetRuId(right), Integer.MAX_VALUE);
                    int orderComparison = Integer.compare(leftOrder, rightOrder);
                    if (orderComparison != 0) {
                        return orderComparison;
                    }
                    return left.getFileName().toString().compareTo(right.getFileName().toString());
                })
                .toList();
    }

    private boolean hasPreflightRunnableTest(PreflightResult preflight, List<Path> executionTests) {
        for (Path approvedTest : executionTests) {
            if (preflight.failureDetails(approvedTest).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<ParameterCase> parameterCases(Path approvedTest) {
        Object parametersValue = readYamlMap(approvedTest).get("parameters");
        if (!(parametersValue instanceof Map<?, ?> parameters)
                || !"explicit_cases".equals(stringValue(parameters.get("strategy")))
                || !(parameters.get("cases") instanceof List<?> cases)
                || cases.isEmpty()) {
            return List.of(new ParameterCase("", Map.of()));
        }
        List<ParameterCase> parameterCases = new java.util.ArrayList<>();
        for (Object caseValue : cases) {
            if (!(caseValue instanceof Map<?, ?> parameterCase)) {
                continue;
            }
            String caseId = stringValue(parameterCase.get("case_id"));
            Object valuesValue = parameterCase.get("values");
            if (caseId.isBlank() || !(valuesValue instanceof Map<?, ?> values) || values.isEmpty()) {
                continue;
            }
            Map<String, Object> copiedValues = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                copiedValues.put(stringValue(entry.getKey()), entry.getValue());
            }
            parameterCases.add(new ParameterCase(caseId, copiedValues));
        }
        return parameterCases.isEmpty() ? List.of(new ParameterCase("", Map.of())) : List.copyOf(parameterCases);
    }

    private Path parameterizedTestCase(Path approvedTest, ParameterCase parameterCase) {
        if (parameterCase.caseId().isBlank()) {
            return approvedTest;
        }
        Map<String, Object> resolved = new LinkedHashMap<>(readYamlMap(approvedTest));
        resolved.put("parameter_case_id", parameterCase.caseId());
        resolved.put("resolved_parameters", parameterCase.values());
        resolved.replaceAll((key, value) -> resolveParameterReferences(value, parameterCase.values()));
        // replaceAll will also process parameters; restore resolved metadata after substitution.
        resolved.put("parameter_case_id", parameterCase.caseId());
        resolved.put("resolved_parameters", parameterCase.values());
        try {
            Path parameterizedTest = Files.createTempFile("regression-parameter-case-", ".yaml");
            Files.writeString(parameterizedTest, new Yaml().dump(resolved));
            return parameterizedTest;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create parameterized test case.", e);
        }
    }

    private void deleteParameterizedTestCase(Path approvedTest, Path executionTest) {
        if (approvedTest.equals(executionTest)) {
            return;
        }
        try {
            Files.deleteIfExists(executionTest);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete parameterized test case.", e);
        }
    }

    private Object resolveParameterReferences(Object value, Map<String, Object> parameters) {
        if (value instanceof String text) {
            String resolved = text;
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                resolved = resolved.replace("${parameters." + entry.getKey() + "}", stringValue(entry.getValue()));
            }
            return resolved;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                resolved.put(stringValue(entry.getKey()), resolveParameterReferences(entry.getValue(), parameters));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new java.util.ArrayList<>();
            for (Object item : list) {
                resolved.add(resolveParameterReferences(item, parameters));
            }
            return resolved;
        }
        return value;
    }

    private DependencyBlock dependencyBlock(
            Path mappingYaml,
            Path approvedTest,
            String targetRuId,
            List<String> dependencies,
            Map<String, String> ruStatuses) {
        for (String dependency : dependencies) {
            String dependencyStatus = ruStatuses.get(dependency);
            if (dependencyStatus != null && !"passed".equals(dependencyStatus)) {
                String failureDetail = failureDetail(
                        "Planning and Binding",
                        dependencyFieldPath(mappingYaml, targetRuId, dependency),
                        "Resolve failed or blocked upstream RU `" + dependency
                                + "` before running dependent RU `" + targetRuId + "`.",
                        "test_case_id: " + testCaseId(approvedTest),
                        "ac_id: " + acId(approvedTest),
                        "affected_ru: " + targetRuId,
                        "blocked_dependency_ru: " + dependency,
                        "dependency_status: " + dependencyStatus);
                return new DependencyBlock(true, failureDetail);
            }
        }
        return new DependencyBlock(false, "");
    }

    private void recordRuStatus(Map<String, String> ruStatuses, String targetRuId, String status) {
        if (targetRuId.isBlank()) {
            return;
        }
        String existing = ruStatuses.get(targetRuId);
        if (existing == null || !"passed".equals(status)) {
            ruStatuses.put(targetRuId, status);
        }
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
        Map<Path, List<String>> blockedTestFailureDetails = new LinkedHashMap<>();
        boolean globalBlocked = !environmentReport.ready() || approvedTests.isEmpty();
        boolean blocked = globalBlocked;

        if (dryRun) {
            out.println("adapter_execution_started: false");
        }
        out.println("environment_gaps:");
        for (ExecutionEnvironmentGap gap : environmentReport.gaps()) {
            out.println("  - ap: Discovery and Context");
            out.println("    field_path: " + gap.fieldPath());
            out.println("    reason: " + failureReason("Discovery and Context", gap.fieldPath()));
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
            out.println("    reason: " + failureReason("Definition and Validation", "tests/approved"));
            out.println("    owner_action: Add approved_for_regression DSL test cases before run.");
            failureDetails.add(failureDetail(
                    "Definition and Validation",
                    "tests/approved",
                    "Add approved_for_regression DSL test cases before run."));
        }

        Map<Path, DslValidationReport> dslReports = new LinkedHashMap<>();
        out.println("dsl_gaps:");
        for (Path approvedTest : approvedTests) {
            List<String> testFailureDetails = new java.util.ArrayList<>();
            DslValidationReport dslReport = dslTestCaseValidator.validate(approvedTest);
            dslReports.put(approvedTest, dslReport);
            blocked = blocked || !dslReport.ready();
            for (DslValidationGap gap : dslReport.gaps()) {
                out.println("  - ap: Definition and Validation");
                out.println("    test_case_id: " + gap.testCaseId());
                out.println("    ac_id: " + gap.acId());
                out.println("    section: " + gap.section());
                out.println("    field_path: " + gap.fieldPath());
                out.println("    reason: " + failureReason("Definition and Validation", gap.fieldPath()));
                if (!gap.verifyId().isBlank()) {
                    out.println("    verify_id: " + gap.verifyId());
                }
                out.println("    owner_action: " + gap.ownerAction());
                addTestFailure(failureDetails, testFailureDetails, failureDetail(
                        "Definition and Validation",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + gap.testCaseId(),
                        "ac_id: " + gap.acId(),
                        "section: " + gap.section(),
                        gap.verifyId().isBlank() ? "" : "verify_id: " + gap.verifyId()));
            }
            if (!testFailureDetails.isEmpty()) {
                blockedTestFailureDetails.put(approvedTest, List.copyOf(testFailureDetails));
            }
        }

        out.println("binding_gaps:");
        for (Path approvedTest : approvedTests) {
            if (!dslReports.getOrDefault(approvedTest,
                    new DslValidationReport(true, "", "", List.of())).ready()) {
                continue;
            }
            List<String> testFailureDetails = new java.util.ArrayList<>();
            BindingResolutionReport bindingReport = bindingResolver.resolve(approvedTest);
            blocked = blocked || !bindingReport.ready();
            for (BindingGap gap : bindingReport.gaps()) {
                out.println("  - ap: Planning and Binding");
                out.println("    test_case_id: " + gap.testCaseId());
                out.println("    ac_id: " + gap.acId());
                out.println("    field_path: " + gap.fieldPath());
                out.println("    reason: " + failureReason("Planning and Binding", gap.fieldPath()));
                out.println("    binding_name: " + gap.bindingName());
                out.println("    binding_type: " + gap.bindingType());
                out.println("    owner_action: " + gap.ownerAction());
                addTestFailure(failureDetails, testFailureDetails, failureDetail(
                        "Planning and Binding",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + gap.testCaseId(),
                        "ac_id: " + gap.acId(),
                        "binding_name: " + gap.bindingName(),
                        "binding_type: " + gap.bindingType()));
            }
            ExpectedResultEligibilityReport eligibility = expectedResultEligibility(
                    packageRoot,
                    approvedTest,
                    bindingReport.acId());
            blocked = blocked || !eligibility.eligible();
            out.println("expected_result_eligibility:");
            out.println("  - ap: Oracle and Assertion Engine");
            out.println("    ac_id: " + bindingReport.acId());
            out.println("    eligible: " + eligibility.eligible());
            out.println("    status: " + eligibility.status());
            for (ExpectedResultGap gap : eligibility.gaps()) {
                out.println("    field_path: " + gap.fieldPath());
                out.println("    reason: " + failureReason("Oracle and Assertion Engine", gap.fieldPath()));
                out.println("    owner_action: " + gap.ownerAction());
                addTestFailure(failureDetails, testFailureDetails, failureDetail(
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
                out.println("    reason: " + failureReason("Oracle and Assertion Engine", gap.fieldPath()));
                out.println("    oracle_type: " + gap.oracleType());
                out.println("    owner_action: " + gap.ownerAction());
                addTestFailure(failureDetails, testFailureDetails, failureDetail(
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
                out.println("    reason: " + failureReason("Fixture and State Manager", gap.fieldPath()));
                out.println("    owner_action: " + gap.ownerAction());
                addTestFailure(failureDetails, testFailureDetails, failureDetail(
                        "Fixture and State Manager",
                        gap.fieldPath(),
                        gap.ownerAction(),
                        "test_case_id: " + bindingReport.testCaseId(),
                        "ac_id: " + bindingReport.acId()));
            }

            ProviderContractResolutionReport providerReport = providerContractResolver.resolve(
                    packageRoot.resolve("rp_ru_mapping.yaml"),
                    targetRuId(approvedTest),
                    adapterName(approvedTest),
                    bindingReport.resolvedBindings().stream().map(ResolvedBinding::bindingType).toList(),
                    fixtureReport.fixtureProviders());
            List<ProviderContractGap> providerGaps = new java.util.ArrayList<>(providerReport.gaps());
            providerGaps.addAll(requestResponseTestContextGaps(
                    packageRoot.resolve("rp_ru_mapping.yaml"),
                    approvedTest,
                    bindingReport));
            providerGaps.addAll(messagingTestContextGaps(
                    packageRoot.resolve("rp_ru_mapping.yaml"),
                    approvedTest,
                    bindingReport));
            blocked = blocked || !providerGaps.isEmpty();
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
            for (ProviderContractGap gap : providerGaps) {
                out.println("  - ap: Planning and Binding");
                out.println("    test_case_id: " + bindingReport.testCaseId());
                out.println("    ac_id: " + bindingReport.acId());
                out.println("    contract_path: " + gap.fieldPath());
                out.println("    reason: " + failureReason("Planning and Binding", gap.fieldPath()));
                out.println("    contract_type: " + gap.contractType());
                out.println("    provider_name: " + gap.providerName());
                out.println("    provider_family: " + gap.providerFamily());
                out.println("    provider_type: " + gap.providerType());
                out.println("    registry_status: " + gap.registryStatus());
                out.println("    runtime_status: " + gap.runtimeStatus());
                out.println("    affected_ru: " + gap.affectedRu());
                out.println("    capability: " + gap.capability());
                out.println("    owner_action: " + gap.ownerAction());
                addTestFailure(failureDetails, testFailureDetails, failureDetail(
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
            if (!testFailureDetails.isEmpty()) {
                blockedTestFailureDetails.put(approvedTest, List.copyOf(testFailureDetails));
            }
        }

        if (dryRun) {
            printApGateStatus(out, failureDetails);
            out.println("run_status: " + (blocked ? "blocked" : "dry_run_ready"));
        }
        return new PreflightResult(
                blocked,
                globalBlocked,
                approvedTests,
                List.copyOf(failureDetails),
                Map.copyOf(blockedTestFailureDetails),
                environmentReport.executionMode(),
                environmentReport.environmentRef());
    }

    private List<ProviderContractGap> requestResponseTestContextGaps(
            Path mappingYaml,
            Path approvedTest,
            BindingResolutionReport bindingReport) {
        AdapterContractContext context =
                adapterContractContext(mappingYaml, targetRuId(approvedTest), adapterName(approvedTest));
        if (!"request_response".equals(context.providerFamily())
                || !List.of("rest", "grpc").contains(context.providerType())) {
            return List.of();
        }
        Object actionsValue = context.contract().get("actions");
        if (!(actionsValue instanceof Map<?, ?> actions) || actions.isEmpty()) {
            return List.of();
        }
        List<String> bindingNames = bindingReport.resolvedBindings().stream()
                .map(ResolvedBinding::bindingName)
                .toList();
        List<ProviderContractGap> gaps = new java.util.ArrayList<>();
        for (String actionName : stepActions(approvedTest)) {
            if (actionName.isBlank()) {
                continue;
            }
            Object actionValue = actions.get(actionName);
            if (!(actionValue instanceof Map<?, ?> action)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName,
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Declare request/response action `" + actionName
                                + "` before invocation or update the DSL step action."));
                continue;
            }
            String requestBinding = stringValue(action.get("request_binding"));
            if (requestBinding.isBlank()) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".request_binding",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Declare request_binding for request/response action `" + actionName
                                + "` before invocation."));
            } else if (!bindingNames.contains(requestBinding)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".request_binding",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Add package input binding `" + requestBinding
                                + "` before invoking request/response action `" + actionName + "`."));
            }
        }
        return gaps;
    }

    private List<ProviderContractGap> messagingTestContextGaps(
            Path mappingYaml,
            Path approvedTest,
            BindingResolutionReport bindingReport) {
        AdapterContractContext context =
                adapterContractContext(mappingYaml, targetRuId(approvedTest), adapterName(approvedTest));
        if (!"messaging".equals(context.providerFamily())
                || !List.of("local", "mock", "kafka", "nats").contains(context.providerType())) {
            return List.of();
        }
        Object actionsValue = context.contract().get("actions");
        if (!(actionsValue instanceof Map<?, ?> actions) || actions.isEmpty()) {
            return List.of();
        }
        List<String> bindingNames = bindingReport.resolvedBindings().stream()
                .map(ResolvedBinding::bindingName)
                .toList();
        List<ProviderContractGap> gaps = new java.util.ArrayList<>();
        for (String actionName : stepActions(approvedTest)) {
            if (actionName.isBlank()) {
                continue;
            }
            Object actionValue = actions.get(actionName);
            if (!(actionValue instanceof Map<?, ?> action)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName,
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Declare messaging action `" + actionName
                                + "` before invocation or update the DSL step action."));
                continue;
            }
            String mode = messagingActionMode(action);
            if (!List.of("publish", "request", "request_reply", "consume", "observe", "cleanup").contains(mode)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".mode",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "unsupported",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Use supported messaging action mode `publish`, `request_reply`, `consume`, `observe`, or `cleanup` "
                                + "before invoking `" + actionName + "`."));
            }
            String payloadBinding = firstText(action, "payload_binding", "message_binding", "event_binding");
            if (messagingRequiresPayload(mode) && payloadBinding.isBlank()) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".payload_binding",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Declare payload_binding, message_binding, or event_binding for messaging action `"
                                + actionName + "` before invocation."));
            } else if (messagingRequiresPayload(mode) && !bindingNames.contains(payloadBinding)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".payload_binding",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Add package input binding `" + payloadBinding
                                + "` before invoking messaging action `" + actionName + "`."));
            }
            if ("cleanup".equals(mode)) {
                String cleanupStrategy = stringValue(action.get("cleanup_strategy"));
                if (cleanupStrategy.isBlank()) {
                    gaps.add(new ProviderContractGap(
                            context.contractPath() + ".actions." + actionName + ".cleanup_strategy",
                            "adapter",
                            context.providerName(),
                            context.providerFamily(),
                            context.providerType(),
                            "incomplete",
                            "blocked",
                            context.ruId(),
                            context.providerName(),
                            "Declare cleanup_strategy for messaging cleanup action `" + actionName
                                    + "` before invocation."));
                } else if (!"drain".equalsIgnoreCase(cleanupStrategy)) {
                    gaps.add(new ProviderContractGap(
                            context.contractPath() + ".actions." + actionName + ".cleanup_strategy",
                            "adapter",
                            context.providerName(),
                            context.providerFamily(),
                            context.providerType(),
                            "unsupported",
                            "blocked",
                            context.ruId(),
                            context.providerName(),
                            "Use supported messaging cleanup_strategy `drain` before invoking messaging cleanup action `"
                                    + actionName + "`."));
                }
                if (!isPositiveInteger(action.get("max_count"))) {
                    gaps.add(new ProviderContractGap(
                            context.contractPath() + ".actions." + actionName + ".max_count",
                            "adapter",
                            context.providerName(),
                            context.providerFamily(),
                            context.providerType(),
                            "incomplete",
                            "blocked",
                            context.ruId(),
                            context.providerName(),
                            "Declare max_count as a positive bounded integer for messaging cleanup action `"
                                    + actionName + "` before invocation."));
                }
            }
            String serialization = stringValue(action.get("serialization"));
            if (!serialization.isBlank() && !"json".equalsIgnoreCase(serialization)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".serialization",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "unsupported",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Use supported messaging serialization `json` before invoking messaging action `"
                                + actionName + "`."));
            }
            if (isTruthy(action.get("requires_correlation"))
                    && firstText(action, "correlation_id", "correlation_id_ref", "correlation_key").isBlank()) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName + ".correlation_id_ref",
                        "adapter",
                        context.providerName(),
                        context.providerFamily(),
                        context.providerType(),
                        "incomplete",
                        "blocked",
                        context.ruId(),
                        context.providerName(),
                        "Declare correlation_id, correlation_id_ref, or correlation_key before invoking messaging action `"
                                + actionName + "`."));
            }
        }
        return gaps;
    }

    private String messagingActionMode(Map<?, ?> action) {
        String mode = stringValue(action.get("mode"));
        return mode.isBlank() ? "publish" : mode.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private boolean messagingRequiresPayload(String mode) {
        return mode.isBlank()
                || "publish".equals(mode)
                || "request".equals(mode)
                || "request_reply".equals(mode);
    }

    private boolean isPositiveInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private List<String> stepActions(Path approvedTest) {
        Object stepsValue = testCaseMap(approvedTest).get("steps");
        if (!(stepsValue instanceof List<?> steps)) {
            return List.of();
        }
        return steps.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(step -> stringValue(step.get("action")))
                .filter(action -> !action.isBlank())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private AdapterContractContext adapterContractContext(Path mappingYaml, String targetRuId, String adapter) {
        Object unitsValue = readYamlMap(mappingYaml).get("release_units");
        if (!(unitsValue instanceof List<?> units)) {
            return AdapterContractContext.empty(adapter);
        }
        AdapterContractContext adapterMatch = AdapterContractContext.empty(adapter);
        for (int index = 0; index < units.size(); index++) {
            Object entry = units.get(index);
            if (!(entry instanceof Map<?, ?> unit)) {
                continue;
            }
            String ruId = stringValue(unit.get("ru_id"));
            String unitAdapter = stringValue(unit.get("adapter"));
            Map<String, Object> adapterContract = adapterContract(unit, adapter);
            if (adapterContract.isEmpty()) {
                continue;
            }
            AdapterContractContext context = new AdapterContractContext(
                    index,
                    ruId,
                    adapter,
                    stringValue(adapterContract.get("provider_family")),
                    stringValue(adapterContract.get("provider_type")),
                    adapterContract);
            if (!targetRuId.isBlank() && targetRuId.equals(ruId)) {
                return context;
            }
            if (unitAdapter.equals(adapter)) {
                adapterMatch = context;
            }
        }
        return adapterMatch;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adapterContract(Map<?, ?> unit, String adapter) {
        Object contractsValue = unit.get("provider_contracts");
        if (!(contractsValue instanceof Map<?, ?> contracts)) {
            return Map.of();
        }
        Object adaptersValue = contracts.get("adapters");
        if (!(adaptersValue instanceof Map<?, ?> adapters)) {
            return Map.of();
        }
        Object adapterValue = adapters.get(adapter);
        if (adapterValue instanceof Map<?, ?> adapterMap) {
            return (Map<String, Object>) adapterMap;
        }
        return Map.of();
    }

    private String firstText(Map<?, ?> map, String... fields) {
        for (String field : fields) {
            String value = stringValue(map.get(field));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = stringValue(value);
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private void printApGateStatus(PrintStream out, List<String> failureDetails) {
        List<String> blockedAps = blockedAps(failureDetails);
        out.println("ap_gate_status:");
        for (String ap : AP_GATE_NAMES) {
            boolean blocked = blockedAps.contains(ap)
                    || ("Execution Engine".equals(ap) && !failureDetails.isEmpty());
            out.println("  - ap: " + ap);
            out.println("    status: " + (blocked ? "blocked" : "passed"));
        }
    }

    private List<String> blockedAps(List<String> failureDetails) {
        List<String> aps = new java.util.ArrayList<>();
        for (String failureDetail : failureDetails) {
            for (String line : failureDetail.split("\\R")) {
                if (line.startsWith("ap: ")) {
                    String ap = line.substring("ap: ".length());
                    if (!aps.contains(ap)) {
                        aps.add(ap);
                    }
                }
            }
        }
        return aps;
    }

    private String failureDetail(String ap, String fieldPath, String ownerAction, String... extraLines) {
        StringBuilder builder = new StringBuilder();
        builder.append("ap: ").append(ap).append("\n");
        builder.append("field_path: ").append(fieldPath).append("\n");
        builder.append("reason: ").append(failureReason(ap, fieldPath)).append("\n");
        for (String extraLine : extraLines) {
            if (extraLine != null && !extraLine.isBlank()) {
                builder.append(extraLine).append("\n");
            }
        }
        builder.append("owner_action: ").append(ownerAction);
        return builder.toString();
    }

    private String failureReason(String ap, String fieldPath) {
        if ("parameters".equals(fieldPath) || fieldPath.startsWith("parameters.")) {
            return "parameter_resolution_failed";
        }
        if (fieldPath.startsWith("package_inputs.")
                || fieldPath.startsWith("setup.fixtures.")
                || fieldPath.matches("execute\\[\\d+]\\.with\\..*")) {
            return "binding_resolution_failed";
        }
        if (fieldPath.contains("provider_contracts")) {
            return "provider_contract_resolution_failed";
        }
        if (fieldPath.startsWith("tests/approved")) {
            return "approved_test_case_missing";
        }
        if (fieldPath.contains(".deployment.") || "Discovery and Context".equals(ap)) {
            return "environment_readiness_failed";
        }
        if ("Fixture and State Manager".equals(ap)) {
            return "fixture_policy_failed";
        }
        if ("Oracle and Assertion Engine".equals(ap)) {
            return "oracle_or_truth_source_readiness_failed";
        }
        if ("Definition and Validation".equals(ap)) {
            return "definition_validation_failed";
        }
        return "readiness_gate_failed";
    }

    private void addTestFailure(
            List<String> failureDetails,
            List<String> testFailureDetails,
            String failureDetail) {
        failureDetails.add(failureDetail);
        testFailureDetails.add(failureDetail);
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
                out.println("    field_path: " + gap.fieldPath());
                out.println("    reason: ac_readiness_gap");
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
                out.println("    field_path: " + gap.fieldPath());
                out.println("    reason: expected_result_readiness_failed");
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
        Object executionTarget = testCaseMap(testCasePath).get("execution_target");
        if (executionTarget instanceof Map<?, ?> target) {
            Object adapter = target.get("adapter");
            return adapter == null ? "" : adapter.toString();
        }
        return "";
    }

    private String targetRuId(Path testCasePath) {
        Object executionTarget = testCaseMap(testCasePath).get("execution_target");
        if (executionTarget instanceof Map<?, ?> target) {
            return stringValue(target.get("ru_id"));
        }
        return "";
    }

    private String testCaseId(Path testCasePath) {
        return stringValue(testCaseMap(testCasePath).get("test_case_id"));
    }

    private String acId(Path testCasePath) {
        return stringValue(testCaseMap(testCasePath).get("ac_id"));
    }

    private Map<String, Object> testCaseMap(Path testCasePath) {
        return dslTestCaseNormalizer.normalize(readYamlMap(testCasePath));
    }

    private ExpectedResultEligibilityReport expectedResultEligibility(Path packageRoot, Path testCasePath, String acId) {
        if (approvedExpectedResultRequired(testCasePath)) {
            return expectedResultService.checkEligibility(packageRoot, acId);
        }
        return new ExpectedResultEligibilityReport(true, "not_required", null, List.of());
    }

    private boolean approvedExpectedResultRequired(Path testCasePath) {
        Object oracles = testCaseMap(testCasePath).get("oracles");
        if (!(oracles instanceof Map<?, ?> oracleMap)) {
            return false;
        }
        for (Object value : oracleMap.values()) {
            if (value instanceof Map<?, ?> oracle) {
                String type = stringValue(oracle.get("type"));
                if ("expected_result_artifact".equals(type) || "golden_file".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> targetDependencies(Path mappingYaml, String targetRuId) {
        if (targetRuId.isBlank()) {
            return List.of();
        }
        Object unitsValue = readYamlMap(mappingYaml).get("release_units");
        if (!(unitsValue instanceof List<?> units)) {
            return List.of();
        }
        for (Object entry : units) {
            if (!(entry instanceof Map<?, ?> unit) || !targetRuId.equals(stringValue(unit.get("ru_id")))) {
                continue;
            }
            Object dependenciesValue = unit.get("dependencies");
            if (!(dependenciesValue instanceof List<?> dependencyValues)) {
                return List.of();
            }
            return dependencyValues.stream()
                    .map(this::stringValue)
                    .filter(dependency -> !dependency.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String dependencyFieldPath(Path mappingYaml, String targetRuId, String dependencyRuId) {
        Object unitsValue = readYamlMap(mappingYaml).get("release_units");
        if (!(unitsValue instanceof List<?> units)) {
            return "release_units.dependencies";
        }
        for (int unitIndex = 0; unitIndex < units.size(); unitIndex++) {
            Object entry = units.get(unitIndex);
            if (!(entry instanceof Map<?, ?> unit) || !targetRuId.equals(stringValue(unit.get("ru_id")))) {
                continue;
            }
            Object dependenciesValue = unit.get("dependencies");
            if (!(dependenciesValue instanceof List<?> dependencies)) {
                return "release_units[" + unitIndex + "].dependencies";
            }
            for (int dependencyIndex = 0; dependencyIndex < dependencies.size(); dependencyIndex++) {
                if (dependencyRuId.equals(stringValue(dependencies.get(dependencyIndex)))) {
                    return "release_units[" + unitIndex + "].dependencies[" + dependencyIndex + "]";
                }
            }
            return "release_units[" + unitIndex + "].dependencies";
        }
        return "release_units.dependencies";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record PreflightResult(
            boolean blocked,
            boolean globalBlocked,
            List<Path> approvedTests,
            List<String> failureDetails,
            Map<Path, List<String>> blockedTestFailureDetails,
            String executionMode,
            String environmentRef) {

        List<String> failureDetails(Path approvedTest) {
            return blockedTestFailureDetails.getOrDefault(approvedTest, List.of());
        }
    }

    private record ParameterCase(String caseId, Map<String, Object> values) {
    }

    private record DependencyBlock(boolean blocked, String failureDetail) {
    }

    private record AdapterContractContext(
            int index,
            String ruId,
            String providerName,
            String providerFamily,
            String providerType,
            Map<String, Object> contract) {

        static AdapterContractContext empty(String providerName) {
            return new AdapterContractContext(-1, "", providerName, "", "", Map.of());
        }

        String contractPath() {
            return index < 0
                    ? "release_units.provider_contracts.adapters." + providerName
                    : "release_units[" + index + "].provider_contracts.adapters." + providerName;
        }
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
