package com.specdriven.regression.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.binding.BindingGap;
import com.specdriven.regression.binding.BindingResolutionReport;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.DryRunResult;
import com.specdriven.regression.contract.ContractBaselineService.ReportResult;
import com.specdriven.regression.contract.ContractBaselineService.ResolvedTarget;
import com.specdriven.regression.contract.ContractBaselineService.ValidationResult;
import com.specdriven.regression.contract.ContractBaselineRuntimeService;
import com.specdriven.regression.contract.ContractBaselineRuntimeService.ContractBaselineRunResult;
import com.specdriven.regression.contract.CommonVerifyService;
import com.specdriven.regression.contract.CommonVerifyService.CommonVerifyRunResult;
import com.specdriven.regression.contract.GoldenE2eService;
import com.specdriven.regression.contract.GoldenE2eService.GoldenRunResult;
import com.specdriven.regression.contract.GrpcMockCapabilityService;
import com.specdriven.regression.contract.GrpcMockCapabilityService.GrpcRunResult;
import com.specdriven.regression.contract.JdbcProviderCapabilityService;
import com.specdriven.regression.contract.JdbcProviderCapabilityService.JdbcRunResult;
import com.specdriven.regression.contract.MessagingClientProviderCapabilityService;
import com.specdriven.regression.contract.MessagingClientProviderCapabilityService.MessagingClientRunResult;
import com.specdriven.regression.contract.NatsProviderCapabilityService;
import com.specdriven.regression.contract.NatsProviderCapabilityService.NatsRunResult;
import com.specdriven.regression.contract.RestClientCapabilityService;
import com.specdriven.regression.contract.RestClientCapabilityService.RestClientRunResult;
import com.specdriven.regression.contract.SoapMockCapabilityService;
import com.specdriven.regression.contract.SoapMockCapabilityService.SoapRunResult;
import com.specdriven.regression.contract.WireMockProviderCapabilityService;
import com.specdriven.regression.contract.WireMockProviderCapabilityService.WireMockRunResult;
import com.specdriven.regression.contract.WireMockHttpRequestCapabilityService;
import com.specdriven.regression.contract.WireMockHttpRequestCapabilityService.MixedRunResult;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService;
import com.specdriven.regression.contract.v03.V03RuntimeExecutionService.V03RuntimeRunResult;
import com.specdriven.regression.contract.v03.V03DryRunRenderer;
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
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.evidence.EvidenceHardeningService.EvidenceValidationResult;
import com.specdriven.regression.evidence.EvidenceHardeningService.ProviderEvidenceSummary;
import com.specdriven.regression.evidence.EvidenceWriter;
import com.specdriven.regression.summary.SuiteExecutionContext;
import com.specdriven.regression.summary.SuiteArtifactFinalizer;
import com.specdriven.regression.summary.SuiteAggregationService;
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
import com.specdriven.regression.parameter.ParameterCase;
import com.specdriven.regression.parameter.ParameterSetResolution;
import com.specdriven.regression.parameter.ParameterSetResolver;
import com.specdriven.regression.productrepo.ProductRepoReadinessReport;
import com.specdriven.regression.productrepo.ProductRepoResult;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.specdriven.regression.productrepo.ReadinessGap;
import com.specdriven.regression.provider.ProviderContractGap;
import com.specdriven.regression.provider.ProviderContractResolutionReport;
import com.specdriven.regression.provider.ProviderContractResolver;
import com.specdriven.regression.provider.ResolvedProviderContract;
import com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery;
import com.specdriven.regression.provider.jdbc.JdbcDriverLoader;
import com.specdriven.regression.report.CoverageReportResult;
import com.specdriven.regression.report.CoverageReportService;
import com.specdriven.regression.readiness.AcIntakeReport;
import com.specdriven.regression.readiness.AcIntakeService;
import com.specdriven.regression.readiness.AcReadinessGap;
import com.specdriven.regression.readiness.AcReadinessItem;
import com.specdriven.regression.runtime.GeneratedRuntimeArtifacts;
import com.specdriven.regression.runtime.GeneratedRuntimeContext;
import com.specdriven.regression.runtime.GeneratedRuntimeTarget;
import com.specdriven.regression.schema.ArtifactValidationError;
import com.specdriven.regression.testcase.ExecutionContextReadiness;
import com.specdriven.regression.testcase.TestCaseDraftResult;
import com.specdriven.regression.testcase.TestCaseLifecycleService;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class RegressionCommand {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> AP_GATE_NAMES = List.of(
            "Discovery and Context",
            "Definition and Validation",
            "Planning and Binding",
            "Fixture and State Manager",
            "Execution Engine",
            "Oracle and Assertion Engine",
            "Evidence and Reporting");
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--batch-id",
            "--driver-dir",
            "--driver-path",
            "--env",
            "--format",
            "--mode",
            "--package-type",
            "--profile",
            "--root",
            "--rp-id",
            "--run-id",
            "--result",
            "--suite",
            "--tag",
            "--test-case");

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
    private final GeneratedRuntimeArtifacts generatedRuntimeArtifacts = new GeneratedRuntimeArtifacts();
    private final ParameterSetResolver parameterSetResolver = new ParameterSetResolver();
    private final ContractBaselineService contractBaselineService = new ContractBaselineService();
    private final ContractBaselineRuntimeService contractBaselineRuntimeService = new ContractBaselineRuntimeService();
    private final EvidenceHardeningService evidenceHardeningService = new EvidenceHardeningService();
    private final CommonVerifyService commonVerifyService = new CommonVerifyService();
    private final GoldenE2eService goldenE2eService = new GoldenE2eService();
    private final WireMockProviderCapabilityService wireMockProviderCapabilityService =
            new WireMockProviderCapabilityService();
    private final WireMockHttpRequestCapabilityService wireMockHttpRequestCapabilityService =
            new WireMockHttpRequestCapabilityService();
    private final RestClientCapabilityService restClientCapabilityService =
            new RestClientCapabilityService();
    private final JdbcProviderCapabilityService jdbcProviderCapabilityService =
            new JdbcProviderCapabilityService();
    private final NatsProviderCapabilityService natsProviderCapabilityService =
            new NatsProviderCapabilityService();
    private final MessagingClientProviderCapabilityService messagingClientProviderCapabilityService =
            new MessagingClientProviderCapabilityService();
    private final SoapMockCapabilityService soapMockCapabilityService =
            new SoapMockCapabilityService();
    private final GrpcMockCapabilityService grpcMockCapabilityService =
            new GrpcMockCapabilityService();
    private final V03RuntimeExecutionService v03RuntimeExecutionService = new V03RuntimeExecutionService();
    private final V03DryRunRenderer v03DryRunRenderer = new V03DryRunRenderer();
    private final SuiteArtifactFinalizer suiteArtifactFinalizer = new SuiteArtifactFinalizer();
    private final SuiteAggregationService suiteAggregationService = new SuiteAggregationService();
    private final SuiteRuntimeDispatcher suiteRuntimeDispatcher = new SuiteRuntimeDispatcher();
    private boolean legacyRpModeEnabledForCompatibilityTests;

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

    void enableLegacyRpModeForCompatibilityTests() {
        this.legacyRpModeEnabledForCompatibilityTests = true;
    }

    public int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            printGeneralUsage(out);
            return 0;
        }
        String command = args[0];
        Map<String, String> options = parseOptions(args);
        Path root = Path.of(options.getOrDefault("--root", "."));

        if ("help".equals(command) || "--help".equals(command) || "-h".equals(command)) {
            printGeneralUsage(out);
            return 0;
        }
        if (options.containsKey("--help") || options.containsKey("-h")) {
            printCommandUsage(command, out);
            return 0;
        }

        return switch (command) {
            case "init-product-repo" -> initProductRepo(root, out);
            case "check-readiness" -> checkReadiness(root, options, out, err);
            case "init-rp" -> initReleasePackage(root, options, out, err);
            case "check-rp" -> checkReleasePackage(root, options, out, err);
            case "generate-tests" -> generateTests(root, options, out, err);
            case "draft-expected-results" -> draftExpectedResults(root, options, out, err);
            case "validate" -> validate(root, options, out, err);
            case "validate-evidence" -> validateEvidence(root, options, out, err);
            case "run" -> runRegression(root, options, out, err);
            case "report" -> report(root, options, out, err);
            case "doctor" -> doctor(args, root, options, out, err);
            default -> {
                err.println("Unknown command: " + command);
                yield 2;
            }
        };
    }

    private void printGeneralUsage(PrintStream out) {
        out.println("usage: regress <command> [options]");
        out.println("commands:");
        out.println("  validate --suite <suite_manifest> [--profile <profile>]");
        out.println("  run --suite <suite_manifest> --profile <profile>");
        out.println("  run --suite <suite_manifest> --dry-run [--profile <profile>]");
        out.println("  report --result <result_json> [--format text|yaml|json]");
        out.println("  validate-evidence --result <result_json>");
        out.println("  doctor drivers [--driver-path <jar>] [--driver-dir <dir>]");
    }

    private void printCommandUsage(String command, PrintStream out) {
        switch (command) {
            case "run" -> {
                out.println("usage: regress run --suite <suite_manifest> --profile <profile>");
                out.println("       regress run --suite <suite_manifest> --dry-run [--profile <profile>]");
            }
            case "validate" -> out.println("usage: regress validate --suite <suite_manifest> [--profile <profile>]");
            case "report" -> out.println("usage: regress report --result <result_json> [--format text|yaml|json]");
            case "validate-evidence" -> out.println("usage: regress validate-evidence --result <result_json>");
            case "doctor" -> out.println("usage: regress doctor drivers [--driver-path <jar>] [--driver-dir <dir>]");
            default -> printGeneralUsage(out);
        }
    }

    private int doctor(String[] args, Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println("Missing doctor subcommand.");
            printCommandUsage("doctor", err);
            return 2;
        }
        if (!"drivers".equals(args[1])) {
            err.println("Unknown doctor subcommand: " + args[1]);
            printCommandUsage("doctor", err);
            return 2;
        }
        return doctorDrivers(root, options, out);
    }

    private int doctorDrivers(Path root, Map<String, String> options, PrintStream out) {
        JdbcDriverDiscovery.DiscoveryResult discovery = jdbcDriverDiscovery(root, options);
        JdbcDriverLoader loader = new JdbcDriverLoader();
        JdbcDriverLoader.LoadResult oracle = discovery.found()
                ? loader.load("oracle", discovery)
                : new JdbcDriverLoader.LoadResult(false, "JDBC_DRIVER_NOT_FOUND", "No JDBC driver jars found.", discovery.ownerAction());
        JdbcDriverLoader.LoadResult db2 = discovery.found()
                ? loader.load("db2", discovery)
                : new JdbcDriverLoader.LoadResult(false, "JDBC_DRIVER_NOT_FOUND", "No JDBC driver jars found.", discovery.ownerAction());
        boolean passed = discovery.found() && (oracle.loaded() || db2.loaded());
        out.println("driver_diagnostics_status: " + (passed ? "passed" : "failed"));
        out.println("driver_source: " + discovery.driverSource());
        out.println("driver_status: " + discovery.driverStatus());
        out.println("driver_paths:");
        for (Path path : discovery.driverPaths()) {
            out.println("  - " + path);
        }
        out.println("oracle_driver_loadable: " + oracle.loaded());
        out.println("db2_driver_loadable: " + db2.loaded());
        if (!oracle.loaded()) {
            out.println("oracle_failure_code: " + oracle.failureCode());
        }
        if (!db2.loaded()) {
            out.println("db2_failure_code: " + db2.failureCode());
        }
        String ownerAction = firstNonBlank(discovery.ownerAction(), oracle.ownerAction(), db2.ownerAction());
        if (!ownerAction.isBlank()) {
            out.println("owner_action: " + ownerAction);
        }
        return passed ? 0 : 1;
    }

    private int validate(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String suite = requiredOption(options, "--suite", err);
        if (suite == null) {
            return 2;
        }
        Path suiteManifest = root.resolve(suite).normalize();
        if (isSuiteGroupManifest(suiteManifest)) {
            return validateSuiteGroup(suiteManifest, options.get("--profile"), out);
        }
        ValidationResult result = contractBaselineService.validateSuite(suiteManifest);
        List<ContractFinding> profileFindings = suiteProfileFindings(suiteManifest, options.get("--profile"));
        boolean valid = result.valid() && profileFindings.isEmpty();
        out.println("validation_status: " + (valid ? "passed" : "failed"));
        out.println("suite_id: " + result.suiteId());
        if (!isV03Validation(result)) {
            out.println("provider_instances_used:");
            for (String providerId : result.providerInstancesUsed()) {
                out.println("  - " + providerId);
            }
        }
        out.println("provider_types_used:");
        for (String providerType : result.providerTypesUsed()) {
            out.println("  - " + providerType);
        }
        out.println("targets_used:");
        for (String target : result.targetsUsed()) {
            out.println("  - " + target);
        }
        out.println("provider_contracts_used:");
        for (String providerContract : result.providerContractsUsed()) {
            out.println("  - " + providerContract);
        }
        List<ContractFinding> findings = new ArrayList<>(result.findings());
        findings.addAll(profileFindings);
        printContractFindings(out, findings);
        return valid ? 0 : 1;
    }

    private int validateEvidence(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String resultPath = requiredOption(options, "--result", err);
        if (resultPath == null) {
            return 2;
        }
        EvidenceValidationResult result = evidenceHardeningService.validateResult(root.resolve(resultPath).normalize());
        printEvidenceValidationSummary(out, result, "evidence_validation_status");
        printContractFindings(out, result.findings());
        return result.valid() ? 0 : 1;
    }

    private int report(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        if (options.containsKey("--result")) {
            return reportResult(root, options, out, err);
        }
        String rpId = requiredOption(options, "--rp-id", err);
        String format = options.getOrDefault("--format", "text");
        String batchId = options.get("--batch-id");
        String runId = options.get("--run-id");
        if (rpId == null) {
            return 2;
        }
        if (format.isBlank()) {
            err.println("Missing required option: --format");
            return 2;
        }
        if (!"text".equals(format)) {
            err.println("Unsupported --format: " + format);
            return 2;
        }
        if ((batchId == null || batchId.isBlank()) && (runId == null || runId.isBlank())) {
            err.println(options.containsKey("--run-id")
                    ? "Missing required option: --run-id"
                    : "Missing required option: --batch-id");
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

    private int reportResult(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String resultPath = requiredOption(options, "--result", err);
        if (resultPath == null) {
            return 2;
        }
        String format = options.getOrDefault("--format", "text");
        if (!List.of("text", "yaml", "json").contains(format)) {
            err.println("Unsupported --format: " + format);
            return 2;
        }
        Path resolvedResult = root.resolve(resultPath).normalize();
        ReportResult result = contractBaselineService.report(resolvedResult);
        if (!result.valid() && result.findings().stream().anyMatch(finding -> "missing_result_json".equals(finding.reason()))) {
            if ("json".equals(format)) {
                printReportJson(out, err, jsonFailure(
                        "RESULT_JSON_MISSING",
                        "CONFIGURATION_ERROR",
                        "Missing result JSON: " + resolvedResult,
                        result.findings()));
                return 1;
            }
            err.println("Missing result JSON: " + resolvedResult);
            return 1;
        }
        if (!result.valid()) {
            if ("json".equals(format)) {
                printReportJson(out, err, jsonFailure(
                        firstFailureCode(result.findings(), "RESULT_VALIDATION_FAILED"),
                        firstCategory(result.findings(), "VALIDATION_ERROR"),
                        firstOwnerAction(result.findings(), "Fix result JSON before running report."),
                        result.findings()));
                return 1;
            }
            out.println("report_status: invalid");
            printContractFindings(out, result.findings());
            return 1;
        }
        EvidenceValidationResult evidenceResult = evidenceHardeningService.hasEvidenceIndexReference(resolvedResult)
                ? evidenceHardeningService.validateResult(resolvedResult)
                : null;
        if ("json".equals(format)) {
            int exitCode = evidenceResult == null || evidenceResult.valid() ? 0 : 1;
            Map<String, Object> report = jsonReport(result, evidenceResult);
            addSuiteSummaryReportFields(report, resolvedResult);
            return printReportJson(out, err, report) ? exitCode : 1;
        }
        boolean evidenceInvalid = evidenceResult != null && !evidenceResult.valid();
        if (result.suiteId().isBlank()) {
            out.println("report_status: " + (evidenceInvalid ? "invalid" : result.status()));
        } else {
            String reportStatus = evidenceInvalid
                    ? "invalid"
                    : ("passed".equals(result.status()) ? "review_ready" : "review_ready_with_failures");
            out.println("report_status: " + reportStatus);
            out.println("suite_id: " + result.suiteId());
            out.println("batch_id: " + result.batchId());
            out.println("run_id: " + result.runId());
            out.println("status: " + result.status());
        }
        out.println("test_case_id: " + result.testCaseId());
        out.println("profile: " + result.profile());
        printSuiteSummaryReportSections(out, resolvedResult);
        out.println("provider_results_count: " + result.providerResultsCount());
        out.println("verify_results_count: " + result.verifyResultsCount());
        if (!result.failedVerifySummary().isEmpty()) {
            out.println("failed_verify_summary:");
            for (String failedVerify : result.failedVerifySummary()) {
                out.println("  - " + failedVerify);
            }
        }
        out.println("release_evidence_eligible: " + result.releaseEvidenceEligible());
        if (evidenceResult != null) {
            out.println("coverage_percent: " + coveragePercent(evidenceResult));
            printEvidenceReportSummary(out, evidenceResult);
            printContractFindings(out, evidenceResult.findings());
            return evidenceResult.valid() ? 0 : 1;
        }
        out.println("coverage_percent: " + ("passed".equals(result.status()) ? "100.0" : "0.0"));
        return 0;
    }

    private void addSuiteSummaryReportFields(Map<String, Object> report, Path resultJson) {
        Map<String, Object> summary = suiteSummaryForReport(resultJson);
        if (summary.isEmpty()) return;
        report.put("suite_summary_version", summary.get("suite_summary_version"));
        report.put("completion_status", summary.get("completion_status"));
        report.put("termination_reason", summary.get("termination_reason"));
        report.put("start_time", summary.get("start_time"));
        report.put("end_time", summary.get("end_time"));
        report.put("duration_ms", summary.get("duration_ms"));
        report.put("self_summary", summary.get("self_summary"));
        report.put("child_aggregate_summary", summary.get("child_aggregate_summary"));
        report.put("total_summary", summary.get("total_summary"));
        report.put("failure_summary", summary.get("failure_summary"));
        report.put("children", summary.get("children"));
        report.put("aggregation_errors", summary.get("aggregation_errors"));
        report.put("report_compatibility_source", "canonical_result");
    }

    private void printSuiteSummaryReportSections(PrintStream out, Path resultJson) {
        Map<String, Object> summary = suiteSummaryForReport(resultJson);
        if (summary.isEmpty()) return;
        out.println("completion_status: " + stringValue(summary.get("completion_status")));
        out.println("termination_reason: " + stringValue(summary.get("termination_reason")));
        out.println("duration_ms: " + stringValue(summary.get("duration_ms")));
        for (String section : List.of("self_summary", "child_aggregate_summary", "total_summary")) {
            out.println(section + ":");
            mapValue(summary.get(section)).forEach((key, value) -> out.println("  " + key + ": " + stringValue(value)));
        }
        out.println("children:");
        for (Object value : objectList(summary.get("children"))) {
            Map<String, Object> child = mapValue(value);
            out.println("  - child_suite_id: " + stringValue(child.get("child_suite_id")));
            out.println("    status: " + stringValue(child.get("status")));
            out.println("    run_id: " + stringValue(child.get("run_id")));
        }
        out.println("aggregation_errors:");
        for (Object value : objectList(summary.get("aggregation_errors"))) {
            Map<String, Object> error = mapValue(value);
            out.println("  - child_suite_id: " + stringValue(error.get("child_suite_id")));
            out.println("    failure_code: " + stringValue(error.get("failure_code")));
            out.println("    owner_action: " + stringValue(error.get("owner_action")));
        }
    }

    private Map<String, Object> suiteSummaryForReport(Path resultJson) {
        try {
            Map<String, Object> result = readYamlMap(resultJson);
            if (!"v0.3".equals(stringValue(result.get("result_contract_version")))) return Map.of();
            String ref = stringValue(result.get("suite_summary_ref"));
            if (ref.isBlank()) return Map.of();
            return readYamlMap(resultJson.toRealPath().getParent().resolve(ref).normalize().toRealPath());
        } catch (IOException | RuntimeException error) {
            return Map.of();
        }
    }

    private Map<String, Object> jsonReport(ReportResult result, EvidenceValidationResult evidenceResult) {
        if (evidenceResult != null && !evidenceResult.valid()) {
            return jsonEvidenceFailure(result, evidenceResult);
        }
        Map<String, Object> report = jsonReportBase(result, evidenceResult);
        report.put("report_status", result.suiteId().isBlank()
                ? result.status()
                : ("passed".equals(result.status()) ? "review_ready" : "review_ready_with_failures"));
        report.put("failure_codes", List.of());
        return report;
    }

    private Map<String, Object> jsonEvidenceFailure(ReportResult result, EvidenceValidationResult evidenceResult) {
        String category = evidenceResult.findings().stream()
                .map(ContractFinding::category)
                .filter(value -> value != null && !value.isBlank())
                .filter("SECRET_GUARDRAIL_ERROR"::equals)
                .findFirst()
                .orElse("EVIDENCE_ERROR");
        String failureCode = "SECRET_GUARDRAIL_ERROR".equals(category)
                ? "SECRET_GUARDRAIL_ERROR"
                : "EVIDENCE_VALIDATION_FAILED";
        Map<String, Object> report = jsonReportBase(result, evidenceResult);
        report.put("report_status", "failed");
        report.put("failure_code", failureCode);
        report.put("category", category);
        report.put("message", "SECRET_GUARDRAIL_ERROR".equals(category)
                ? "Remove or mask raw secret values before using this report."
                : "Fix result evidence references before using this report.");
        report.put("failure_codes", List.of(failureCode));
        report.put("findings", jsonFindings(evidenceResult.findings()));
        return report;
    }

    private Map<String, Object> jsonReportBase(ReportResult result, EvidenceValidationResult evidenceResult) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite_id", result.suiteId());
        report.put("batch_id", result.batchId());
        report.put("run_id", result.runId());
        report.put("test_case_id", result.testCaseId());
        report.put("status", result.status());
        report.put("profile", result.profile());
        report.put("test_count", evidenceResult == null ? (result.status().isBlank() ? 0 : 1) : evidenceResult.testCount());
        report.put("passed_count", evidenceResult == null ? ("passed".equals(result.status()) ? 1 : 0) : evidenceResult.passCount());
        report.put("failed_count", evidenceResult == null ? ("passed".equals(result.status()) ? 0 : 1) : evidenceResult.failCount());
        report.put("provider_results_count", result.providerResultsCount());
        report.put("verify_results_count", result.verifyResultsCount());
        report.put("release_evidence_eligible", result.releaseEvidenceEligible());
        report.put("evidence_dir", evidenceResult == null ? "" : stringValue(evidenceResult.evidenceDir()));
        report.put("missing_evidence_count", evidenceResult == null ? 0 : evidenceResult.missingEvidenceCount());
        report.put("failed_evidence_count", evidenceResult == null ? 0 : evidenceResult.failedEvidenceCount());
        report.put("failed_evidence_summary", evidenceResult == null ? List.of() : evidenceResult.failedEvidenceSummary());
        report.put("provider_evidence_summary", evidenceResult == null
                ? List.of()
                : jsonProviderEvidenceSummary(evidenceResult.providerEvidenceSummary()));
        report.put("masking_status", evidenceResult == null || evidenceResult.maskingPassed() ? "passed" : "failed");
        report.put("failed_verify_summary", result.failedVerifySummary());
        return report;
    }

    private Map<String, Object> jsonFailure(
            String failureCode,
            String category,
            String message,
            List<ContractFinding> findings) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("report_status", "failed");
        report.put("failure_code", failureCode);
        report.put("category", category);
        report.put("message", message);
        report.put("findings", jsonFindings(findings));
        return report;
    }

    private List<Map<String, Object>> jsonProviderEvidenceSummary(List<ProviderEvidenceSummary> summaries) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (ProviderEvidenceSummary summary : summaries) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("provider_type", summary.providerType());
            value.put("provider_id", summary.providerId());
            value.put("evidence_count", summary.evidenceCount());
            values.add(value);
        }
        return values;
    }

    private List<Map<String, Object>> jsonFindings(List<ContractFinding> findings) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (ContractFinding finding : findings) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("file_path", finding.filePath());
            value.put("field_path", finding.fieldPath());
            value.put("reason", finding.reason());
            value.put("provider_id", finding.providerId());
            value.put("provider_type", finding.providerType());
            value.put("profile", finding.profile());
            value.put("operation", finding.operation());
            value.put("owner_action", finding.ownerAction());
            value.put("failure_code", finding.failureCode());
            value.put("category", finding.category());
            value.put("original_cause", finding.originalCause());
            values.add(value);
        }
        return values;
    }

    private String firstFailureCode(List<ContractFinding> findings, String fallback) {
        return findings.stream()
                .map(ContractFinding::failureCode)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private String firstCategory(List<ContractFinding> findings, String fallback) {
        return findings.stream()
                .map(ContractFinding::category)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private String firstOwnerAction(List<ContractFinding> findings, String fallback) {
        return findings.stream()
                .map(ContractFinding::ownerAction)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private boolean printReportJson(PrintStream out, PrintStream err, Map<String, Object> report) {
        try {
            out.println(OBJECT_MAPPER.writeValueAsString(report));
            return true;
        } catch (JsonProcessingException e) {
            err.println("Failed to render JSON report: " + e.getMessage());
            return false;
        }
    }

    private String coveragePercent(EvidenceValidationResult result) {
        if (result.testCount() <= 0) {
            return result.valid() ? "100.0" : "0.0";
        }
        double coverage = (100.0 * result.passCount()) / result.testCount();
        return String.format(java.util.Locale.ROOT, "%.1f", coverage);
    }

    private void printEvidenceValidationSummary(
            PrintStream out,
            EvidenceValidationResult result,
            String statusLabel) {
        out.println(statusLabel + ": " + (result.valid() ? "passed" : "failed"));
        out.println("suite_id: " + result.suiteId());
        out.println("batch_id: " + result.batchId());
        out.println("run_id: " + result.runId());
        out.println("test_count: " + result.testCount());
        out.println("pass_count: " + result.passCount());
        out.println("fail_count: " + result.failCount());
        out.println("evidence_folder_path: " + result.evidenceDir());
        out.println("missing_evidence_count: " + result.missingEvidenceCount());
        out.println("failed_evidence_count: " + result.failedEvidenceCount());
        printFailedEvidenceSummary(out, result);
        printProviderEvidenceSummary(out, result.providerEvidenceSummary());
        out.println("masking_status: " + (result.maskingPassed() ? "passed" : "failed"));
    }

    private void printEvidenceReportSummary(PrintStream out, EvidenceValidationResult result) {
        out.println("test_count: " + result.testCount());
        out.println("pass_count: " + result.passCount());
        out.println("fail_count: " + result.failCount());
        out.println("evidence_folder_path: " + result.evidenceDir());
        out.println("missing_evidence_count: " + result.missingEvidenceCount());
        out.println("failed_evidence_count: " + result.failedEvidenceCount());
        printFailedEvidenceSummary(out, result);
        printProviderEvidenceSummary(out, result.providerEvidenceSummary());
        out.println("masking_status: " + (result.maskingPassed() ? "passed" : "failed"));
    }

    private void printFailedEvidenceSummary(PrintStream out, EvidenceValidationResult result) {
        out.println("failed_evidence_summary:");
        if (result.failedEvidenceSummary().isEmpty()) {
            out.println("  []");
            return;
        }
        for (String failedEvidence : result.failedEvidenceSummary()) {
            out.println("  - " + failedEvidence);
        }
    }

    private void printProviderEvidenceSummary(PrintStream out, List<ProviderEvidenceSummary> summaries) {
        out.println("provider_evidence_summary:");
        if (summaries.isEmpty()) {
            out.println("  []");
            return;
        }
        for (ProviderEvidenceSummary summary : summaries) {
            out.println("  - provider_type: " + summary.providerType());
            out.println("    provider_id: " + summary.providerId());
            out.println("    evidence_count: " + summary.evidenceCount());
        }
    }

    private int initProductRepo(Path root, PrintStream out) {
        ProductRepoResult result = productRepoService.initialize(root);
        out.println("status: pass");
        out.println("created_count: " + result.createdPaths().size());
        out.println("skipped_existing_count: " + result.skippedExistingPaths().size());
        return 0;
    }

    private int checkReadiness(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
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
            try {
                writeReadinessReport(root.resolve(relativeReport), report);
            } catch (UncheckedIOException e) {
                err.println(e.getMessage());
                return 1;
            }
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
        if (options.containsKey("--suite") && !options.containsKey("--rp-id")) {
            return runSuite(root, options, out, err);
        }
        if (!legacyRpModeEnabledForCompatibilityTests) {
            printLegacyRpModeBlocked(out);
            return 1;
        }
        err.println("RP-mode is deprecated for the public suite-mode runtime and is only available through compatibility tests.");
        String rpId = requiredOption(options, "--rp-id", err);
        String requestedEnv = requiredOption(options, "--env", err);
        if (rpId == null || requestedEnv == null) {
            return 2;
        }
        Path packageRoot = packageRoot(root, rpId);
        boolean dryRun = options.containsKey("--dry-run");
        GeneratedRuntimeContext runtimeContext = generatedRuntimeArtifacts.resolve(packageRoot, requestedEnv);
        TestSelection testSelection = selectApprovedTests(packageRoot, options);
        PreflightResult preflight = runPreflight(
                packageRoot,
                requestedEnv,
                dryRun,
                out,
                runtimeContext,
                testSelection.tests(),
                testSelection.gaps());
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
            out.println("provider_runtime_started: false");
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
        Map<String, String> targetStatuses = new LinkedHashMap<>();
        List<Path> executionTests = dependencyOrderedApprovedTests(runtimeContext, preflight.approvedTests());
        out.println("provider_runtime_started: " + hasPreflightRunnableTest(preflight, executionTests));
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
                List<String> dependencies = targetDependencies(runtimeContext, targetRuId, adapterName(approvedTest));
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
                recordRuStatus(targetStatuses, targetRuId, result.status());
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
                    String adapterName = adapterName(executionTest);
                    List<String> dependencies = targetDependencies(runtimeContext, targetRuId, adapterName);
                    DependencyBlock dependencyBlock =
                            dependencyBlock(runtimeContext, executionTest, targetRuId, adapterName, dependencies, targetStatuses);
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
                        result = executionEngine.execute(packageRoot, executionTest, batchId, runId, requestedEnv);
                    }
                    results.add(result);
                    passed = passed && result.passed();
                    recordRuStatus(targetStatuses, targetRuId, result.status());
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

    private void printLegacyRpModeBlocked(PrintStream out) {
        out.println("run_status: blocked");
        out.println("failure_code: LEGACY_RP_MODE_DEPRECATED");
        out.println("owner_action: Use run --suite <suite_manifest> --profile <profile>.");
    }

    private int runSuite(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String suite = requiredOption(options, "--suite", err);
        if (suite == null) {
            return 2;
        }
        Path suiteManifest = root.resolve(suite).normalize();
        if (!options.containsKey("--dry-run")) {
            String profile = requiredOption(options, "--profile", err);
            if (profile == null) {
                return 2;
            }
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery = jdbcDriverDiscovery(root, options);
            if (isSuiteGroupManifest(suiteManifest)) {
                return runSuiteGroup(
                        suiteManifest,
                        profile,
                        root.resolve("target/suite-groups").normalize(),
                        driverDiscovery,
                        out);
            }
            SuiteRuntimeResult result = suiteRuntimeDispatcher.dispatch(
                    suiteManifest,
                    suite,
                    profile,
                    root.resolve("target/provider-capability").normalize(),
                    driverDiscovery);
            printSuiteRuntimeResult(out, result);
            return result.passed() ? 0 : 1;
        }
        if (isSuiteGroupManifest(suiteManifest)) {
            return dryRunSuiteGroup(suiteManifest, options.get("--profile"), out);
        }
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        List<ContractFinding> profileFindings = suiteProfileFindings(suiteManifest, options.get("--profile"));
        boolean ready = validation.valid() && profileFindings.isEmpty();
        out.println("provider_runtime_invoked: false");
        out.println("run_status: " + (ready ? "dry_run_ready" : "blocked"));
        if (validation.valid() && isV03Validation(validation)) {
            v03DryRunRenderer.render(out, new com.specdriven.regression.contract.v03.V03ExecutionPlanBuilder(
                    contractBaselineService).compile(suiteManifest, options.get("--profile"), validation));
        } else {
            DryRunResult result = contractBaselineService.dryRun(suiteManifest);
            out.println("suite_id: " + result.validation().suiteId());
            out.println("resolved_execution_plan:");
            for (ResolvedTarget target : result.plan()) {
                out.println("  - test_case_id: " + target.testCaseId());
                out.println("    target: " + target.target());
                if (!target.providerId().isBlank()) {
                    out.println("    provider_id: " + target.providerId());
                }
                if (!target.providerContract().isBlank()) {
                    out.println("    provider_contract: " + target.providerContract());
                }
                out.println("    provider_type: " + target.providerType());
                out.println("    profile: " + target.profile());
                out.println("    runtime_mode: " + target.runtimeMode());
            }
        }
        List<ContractFinding> findings = new ArrayList<>(validation.findings());
        findings.addAll(profileFindings);
        printContractFindings(out, findings);
        return ready ? 0 : 1;
    }

    private SuiteRuntimeResult dispatchSuiteRuntimeInternal(
            Path suiteManifest,
            String suiteRef,
            String profile,
            Path outputRoot,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
        return dispatchSuiteRuntimeInternal(suiteManifest, suiteRef, profile, outputRoot, driverDiscovery, null);
    }

    private SuiteRuntimeResult dispatchSuiteRuntimeInternal(
            Path suiteManifest,
            String suiteRef,
            String profile,
            Path outputRoot,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery,
            SuiteExecutionContext parentContext) {
        SuiteRuntimeResult result = dispatchSuiteRuntimeUnfinalized(
                suiteManifest, suiteRef, profile, outputRoot, driverDiscovery, parentContext);
        if (result.resultJson() == null) {
            return result;
        }
        SuiteArtifactFinalizer.FinalizedArtifacts finalized =
                suiteArtifactFinalizer.finalizeLeaf(suiteManifest, result.resultJson());
        return result.withSummaryJson(finalized.summaryJson());
    }

    private SuiteRuntimeResult dispatchSuiteRuntimeUnfinalized(
            Path suiteManifest,
            String suiteRef,
            String profile,
            Path outputRoot,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery,
            SuiteExecutionContext parentContext) {
        if (parentContext != null && !parentContext.matchesRequestedProfile(profile)) {
            return SuiteRuntimeResult.blocked("", profile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "profile",
                    "profile_mismatch",
                    "",
                    "",
                    profile == null ? "" : profile,
                    "",
                    "Use the suite-group parent profile `" + parentContext.profile()
                            + "` for the child suite or split it into a separate batch.")));
        }
        ValidationResult validation = contractBaselineService.validateSuite(suiteManifest);
        if (!validation.valid()) {
            return SuiteRuntimeResult.blocked(validation.suiteId(), profile, validation.findings());
        }
        List<String> providerTypes = validation.providerTypesUsed();
        if (supportsV03RuntimeService(validation)) {
            return SuiteRuntimeResult.fromV03(v03RuntimeExecutionService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("v03-runtime"), profile)));
        }
        if (supportsWireMockHttpRequestSample(providerTypes)) {
            return SuiteRuntimeResult.fromMixed(wireMockHttpRequestCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("wiremock_http_request"), profile)));
        }
        if (supportsContractBaselineMixedSample(providerTypes)) {
            return SuiteRuntimeResult.fromContractBaseline(contractBaselineRuntimeService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("contract_baseline"), profile),
                    driverDiscovery));
        }
        if (supportsSoapMockSample(providerTypes)) {
            return SuiteRuntimeResult.fromSoap(soapMockCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("soap_mock"), profile)));
        }
        if (supportsGrpcMockSample(providerTypes)) {
            return SuiteRuntimeResult.fromGrpc(grpcMockCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("grpc_mock"), profile)));
        }
        if (providerTypes.equals(List.of("rest_client"))) {
            return SuiteRuntimeResult.fromRest(restClientCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("rest_client"), profile)));
        }
        if (providerTypes.equals(List.of("wiremock_http_mock"))) {
            return SuiteRuntimeResult.fromWireMock(wireMockProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("wiremock"), profile)));
        }
        if (providerTypes.equals(List.of("jdbc")) || suiteRef.contains("provider_capability/jdbc")) {
            return SuiteRuntimeResult.fromJdbc(jdbcProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("jdbc"), profile),
                    driverDiscovery));
        }
        if (supportsMessagingClientSample(providerTypes, suiteRef)) {
            return SuiteRuntimeResult.fromMessaging(messagingClientProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("messaging-client"), profile)));
        }
        if (providerTypes.equals(List.of("nats")) || suiteRef.contains("provider_capability/nats")) {
            return SuiteRuntimeResult.fromNats(natsProviderCapabilityService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("nats"), profile)));
        }
        if (providerTypes.equals(List.of("common_verify"))
                || suiteRef.contains("provider_capability/common_verify")
                || suiteRef.contains("provider_capability/polling")) {
            return SuiteRuntimeResult.fromCommonVerify(commonVerifyService.run(
                    suiteManifest,
                    profile,
                    runtimeContext(parentContext, outputRoot.resolve("common_verify"), profile)));
        }
        if (!providerTypes.equals(List.of("sample_fake_provider"))) {
            return SuiteRuntimeResult.blocked(validation.suiteId(), profile, List.of(new ContractFinding(
                    suiteManifest.toString(),
                    "provider_types_used",
                    "unsupported_suite_runtime",
                    "",
                    String.join(",", providerTypes),
                    profile,
                    "",
                    "Use a provider type supported by SuiteRuntimeDispatcher or add an explicit runtime dispatcher path.")));
        }
        return SuiteRuntimeResult.fromGolden(goldenE2eService.run(
                suiteManifest,
                profile,
                runtimeContext(parentContext, outputRoot.resolve("golden-e2e"), profile)));
    }

    private SuiteExecutionContext runtimeContext(
            SuiteExecutionContext parentContext, Path runtimeOutputRoot, String profile) {
        if (parentContext != null) {
            return parentContext.withOutputRoot(runtimeOutputRoot);
        }
        String runtime = runtimeOutputRoot.getFileName().toString();
        if ("golden-e2e".equals(runtime)) {
            return SuiteExecutionContext.standaloneWithBatchId(
                    profile, runtimeOutputRoot, "BATCH-GOLDEN-E2E-001");
        }
        String label = switch (runtime) {
            case "v03-runtime" -> "V03";
            case "wiremock_http_request" -> "WIREMOCK-HTTP";
            case "contract_baseline" -> "CONTRACT";
            case "soap_mock" -> "SOAP";
            case "grpc_mock" -> "GRPC";
            case "rest_client" -> "REST";
            case "wiremock" -> "WIREMOCK";
            case "jdbc" -> "JDBC";
            case "messaging-client" -> "MESSAGING";
            case "nats" -> "NATS";
            case "common_verify" -> "COMMON";
            default -> "STANDALONE";
        };
        return SuiteExecutionContext.standalone(profile, runtimeOutputRoot, label);
    }

    private void printSuiteRuntimeResult(PrintStream out, SuiteRuntimeResult result) {
        out.println("run_status: " + result.status());
        out.println("suite_id: " + result.suiteId());
        if (result.resultJson() != null) {
            out.println("batch_id: " + result.batchId());
            out.println("run_id: " + result.runId());
            out.println("test_case_id: " + result.testCaseId());
            out.println("test_count: " + result.testCount());
            StatusCounts statusCounts = suiteRuntimeStatusCounts(result);
            out.println("passed_count: " + statusCounts.passedCount());
            out.println("failed_count: " + statusCounts.failedCount());
            out.println("profile: " + result.profile());
            out.println("provider_runtime_executed: " + result.providerRuntimeExecuted());
            result.outputLines().forEach(out::println);
            out.println("evidence_classification: " + result.evidenceClassification());
            out.println("result_json: " + result.resultJson());
            if (result.summaryJson() != null) {
                out.println("suite_summary_json: " + result.summaryJson());
            }
            out.println("evidence_dir: " + result.evidenceDir());
            printRuntimeFailure(out, result);
        } else {
            out.println("provider_runtime_invoked: false");
        }
        printContractFindings(out, result.findings());
    }

    private void printRuntimeFailure(PrintStream out, SuiteRuntimeResult result) {
        if (result.passed() || result.resultJson() == null || !Files.isRegularFile(result.resultJson())) {
            return;
        }
        try {
            Map<String, Object> resultDocument = readYamlMap(result.resultJson());
            Map<String, Object> failure = mapValue(resultDocument.get("failure"));
            String code = stringValue(failure.get("code"));
            if (code.isBlank()) {
                return;
            }
            out.println("failure_code: " + code);
            String reason = stringValue(failure.get("reason"));
            if (!reason.isBlank()) {
                out.println("failure_reason: " + reason);
            }
            String ownerAction = stringValue(failure.get("owner_action"));
            if (!ownerAction.isBlank()) {
                out.println("owner_action: " + ownerAction);
            }
        } catch (RuntimeException ignored) {
            // Validation/report commands provide detailed parsing errors; run summary stays printable.
        }
    }

    private StatusCounts suiteRuntimeStatusCounts(SuiteRuntimeResult result) {
        if (result.resultJson() != null && Files.isRegularFile(result.resultJson())) {
            try {
                Map<String, Object> resultDocument = readYamlMap(result.resultJson());
                Object tests = resultDocument.get("test_results");
                if (tests instanceof List<?> testResults && !testResults.isEmpty()) {
                    int passed = 0;
                    int failed = 0;
                    for (Object value : testResults) {
                        String status = value instanceof Map<?, ?> map ? stringValue(map.get("status")) : "";
                        if ("passed".equals(status)) {
                            passed++;
                        } else {
                            failed++;
                        }
                    }
                    return new StatusCounts(passed, failed);
                }
            } catch (RuntimeException ignored) {
                // CLI summary should remain printable even if result validation reports details later.
            }
        }
        return result.passed()
                ? new StatusCounts(result.testCount(), 0)
                : new StatusCounts(0, result.testCount());
    }

    private int validateSuiteGroup(Path suiteManifest, String requestedProfile, PrintStream out) {
        SuiteGroupValidation validation = validateSuiteGroupArtifacts(suiteManifest);
        SuiteGroupDefinition definition = validation.definition();
        List<ContractFinding> profileFindings = suiteGroupProfileFindings(suiteManifest, definition, requestedProfile);
        List<ContractFinding> findings = new ArrayList<>(validation.findings());
        findings.addAll(profileFindings);
        boolean valid = validation.valid() && profileFindings.isEmpty();
        out.println("validation_status: " + (valid ? "passed" : "failed"));
        out.println("suite_id: " + definition.suiteId());
        out.println("test_count: " + definition.children().size());
        out.println("provider_instances_used:");
        for (String providerId : validation.providerInstances()) {
            out.println("  - " + providerId);
        }
        out.println("provider_types_used:");
        for (String providerType : validation.providerTypes()) {
            out.println("  - " + providerType);
        }
        out.println("child_suites:");
        for (SuiteGroupChild child : definition.children()) {
            out.println("  - id: " + child.id());
            out.println("    ref: " + child.ref());
            out.println("    expected_status: " + child.expectedStatus());
        }
        printContractFindings(out, findings);
        return valid ? 0 : 1;
    }

    private int dryRunSuiteGroup(Path suiteManifest, String requestedProfile, PrintStream out) {
        SuiteGroupValidation validation = validateSuiteGroupArtifacts(suiteManifest);
        SuiteGroupDefinition definition = validation.definition();
        List<ContractFinding> profileFindings = suiteGroupProfileFindings(suiteManifest, definition, requestedProfile);
        List<ContractFinding> findings = new ArrayList<>(validation.findings());
        findings.addAll(profileFindings);
        boolean valid = validation.valid() && profileFindings.isEmpty();
        out.println("provider_runtime_invoked: false");
        out.println("run_status: " + (valid ? "dry_run_ready" : "blocked"));
        out.println("suite_id: " + definition.suiteId());
        out.println("test_count: " + definition.children().size());
        out.println("provider_instances_used:");
        for (String providerId : validation.providerInstances()) {
            out.println("  - " + providerId);
        }
        out.println("provider_types_used:");
        for (String providerType : validation.providerTypes()) {
            out.println("  - " + providerType);
        }
        out.println("child_suites:");
        for (SuiteGroupChild child : definition.children()) {
            out.println("  - id: " + child.id());
            out.println("    ref: " + child.ref());
            out.println("    profile: " + child.profile());
            out.println("    expected_status: " + child.expectedStatus());
        }
        printContractFindings(out, findings);
        return valid ? 0 : 1;
    }

    private List<ContractFinding> suiteGroupProfileFindings(
            Path suiteManifest,
            SuiteGroupDefinition definition,
            String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank()) {
            return List.of();
        }
        if (!definition.profile().isBlank() && !definition.profile().equals(requestedProfile)) {
            return List.of(finding(
                    suiteManifest,
                    "profile",
                    "profile_mismatch",
                    "Run with an allowed profile: [" + definition.profile() + "]."));
        }
        return List.of();
    }

    private List<ContractFinding> suiteProfileFindings(Path suiteManifest, String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank() || !Files.isRegularFile(suiteManifest)) {
            return List.of();
        }
        Map<String, Object> suite;
        try {
            suite = readYamlMap(suiteManifest);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ContractFinding> findings = new ArrayList<>();
        List<String> selectedProfiles = selectedProfiles(suite);
        if (!selectedProfiles.isEmpty() && !selectedProfiles.contains(requestedProfile)) {
            findings.add(finding(
                    suiteManifest,
                    "profile",
                    "profile_mismatch",
                    "Run with an allowed profile: " + selectedProfiles + "."));
        }
        Path suiteRoot = suiteDirectory(suiteManifest);
        for (Object testRef : objectList(suite.get("tests"))) {
            Path testCasePath = suiteRoot.resolve(stringValue(testRef)).normalize();
            if (!Files.isRegularFile(testCasePath)) {
                continue;
            }
            Map<String, Object> testCase;
            try {
                testCase = readYamlMap(testCasePath);
            } catch (RuntimeException e) {
                continue;
            }
            List<String> compatibleProfiles = stringListValue(testCase.get("compatible_profiles"));
            if (!compatibleProfiles.isEmpty() && !compatibleProfiles.contains(requestedProfile)) {
                findings.add(finding(
                        testCasePath,
                        "compatible_profiles",
                        "profile_mismatch",
                        "Run with an allowed profile: " + compatibleProfiles + "."));
            }
            for (Map.Entry<String, Object> entry : mapValue(testCase.get("targets")).entrySet()) {
                Map<String, Object> target = mapValue(entry.getValue());
                String targetProfile = stringValue(target.get("profile"));
                if (targetProfile.isBlank() || targetProfile.equals(requestedProfile)) {
                    continue;
                }
                findings.add(finding(
                        testCasePath,
                        "targets." + entry.getKey() + ".profile",
                        "conflicting_profile_selection",
                        "Remove deprecated target profile or run with profile `" + targetProfile + "`."));
            }
        }
        return List.copyOf(findings);
    }

    private List<String> selectedProfiles(Map<String, Object> suite) {
        List<String> profiles = new ArrayList<>();
        addProfile(profiles, stringValue(suite.get("profile")));
        profiles.addAll(stringListValue(suite.get("profiles")));
        Object selection = suite.get("selection");
        if (selection instanceof Map<?, ?> map) {
            addProfile(profiles, stringValue(map.get("profile")));
        }
        return profiles.stream().distinct().toList();
    }

    private void addProfile(List<String> profiles, String profile) {
        if (!profile.isBlank()) {
            profiles.add(profile);
        }
    }

    private List<String> stringListValue(Object value) {
        return objectList(value).stream().map(this::stringValue).filter(text -> !text.isBlank()).toList();
    }

    private List<Object> objectList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private SuiteGroupValidation validateSuiteGroupArtifacts(Path suiteManifest) {
        Map<String, Object> manifest = readYamlMap(suiteManifest);
        List<ContractFinding> findings = new ArrayList<>();
        SuiteGroupDefinition definition = suiteGroupDefinition(suiteManifest, manifest, findings);
        List<String> providerInstances = new ArrayList<>();
        List<String> providerTypes = new ArrayList<>();
        for (SuiteGroupChild child : definition.children()) {
            if (!Files.isRegularFile(child.suiteManifest())) {
                findings.add(finding(
                        child.suiteManifest(),
                        child.fieldPath() + ".ref",
                        "missing_required_file",
                        "Create or correct the child suite manifest referenced by the suite group."));
                continue;
            }
            ValidationResult childValidation = contractBaselineService.validateSuite(child.suiteManifest());
            addUnique(providerInstances, childValidation.providerInstancesUsed());
            addUnique(providerTypes, childValidation.providerTypesUsed());
            if (!childValidation.valid()) {
                findings.addAll(childValidation.findings());
            }
        }
        return new SuiteGroupValidation(definition, providerInstances, providerTypes, findings);
    }

    private int runSuiteGroup(
            Path suiteManifest,
            String requestedProfile,
            Path outputRoot,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery,
            PrintStream out) {
        SuiteGroupValidation validation = validateSuiteGroupArtifacts(suiteManifest);
        SuiteGroupDefinition definition = validation.definition();
        List<ContractFinding> findings = new ArrayList<>(validation.findings());
        if (!definition.profile().isBlank() && !definition.profile().equals(requestedProfile)) {
            findings.add(finding(
                    suiteManifest,
                    "profile",
                    "profile_mismatch",
                    "Run the suite group with --profile " + definition.profile() + " or update the suite group profile."));
        }
        if (!validation.valid() || !findings.isEmpty()) {
            out.println("run_status: blocked");
            out.println("suite_id: " + definition.suiteId());
            out.println("test_count: " + definition.children().size());
            printContractFindings(out, findings);
            return 1;
        }

        long startedAt = System.currentTimeMillis();
        String batchId = "BATCH-" + Long.toString(startedAt, 36).toUpperCase();
        String runId = "RUN-" + UUID.randomUUID();
        Path runDir = outputRoot
                .resolve(safeFileName(definition.suiteId()))
                .resolve(batchId)
                .resolve(runId)
                .normalize();
        Path childOutputRoot = runDir.resolve("children");
        SuiteExecutionContext parentContext = new SuiteExecutionContext(
                batchId,
                requestedProfile,
                java.time.Instant.ofEpochMilli(startedAt),
                childOutputRoot);
        List<SuiteGroupChildResult> childResults = new ArrayList<>();
        for (SuiteGroupChild child : definition.children()) {
            childResults.add(runSuiteGroupChild(child, childOutputRoot, driverDiscovery, parentContext));
        }
        for (SuiteGroupChildResult childResult : childResults) {
            findings.addAll(childResult.findings());
        }

        int passedCount = (int) childResults.stream().filter(SuiteGroupChildResult::passed).count();
        int failedCount = childResults.size() - passedCount;
        int expectedFailureCount = (int) childResults.stream()
                .filter(result -> "failed".equals(result.expectedStatus()))
                .count();
        int expectedFailedObservedCount = (int) childResults.stream()
                .filter(result -> "expected_failed_observed".equals(result.statusTaxonomy()))
                .count();
        String status = failedCount == 0 ? "passed" : "failed";
        boolean canonicalV03 = "v0.3".equals(stringValue(readYamlMap(suiteManifest).get("manifest_version")));
        SuiteGroupOutput compatibilityOutput = writeSuiteGroupOutput(
                definition,
                requestedProfile,
                batchId,
                runId,
                status,
                startedAt,
                System.currentTimeMillis(),
                runDir,
                childResults,
                expectedFailureCount,
                expectedFailedObservedCount,
                canonicalV03);
        SuiteGroupOutput output = compatibilityOutput;
        if (canonicalV03) {
            SuiteAggregationService.AggregatedArtifacts aggregated = suiteAggregationService.aggregate(
                    suiteManifest,
                    runDir,
                    definition.suiteId(),
                    batchId,
                    runId,
                    requestedProfile,
                    java.time.Instant.ofEpochMilli(startedAt),
                    java.time.Instant.now(),
                    childResults.stream().map(child -> new SuiteAggregationService.ChildArtifact(
                            child.id(), child.ref(), child.resultJson(), child.summaryJson())).toList());
            status = aggregated.status();
            output = new SuiteGroupOutput(
                    aggregated.resultJson(), aggregated.summaryJson(), aggregated.summaryYaml(),
                    compatibilityOutput.allureResultsDir());
        }

        out.println("run_status: " + status);
        out.println("suite_id: " + definition.suiteId());
        out.println("batch_id: " + batchId);
        out.println("run_id: " + runId);
        out.println("profile: " + requestedProfile);
        out.println("test_count: " + childResults.size());
        out.println("passed_count: " + passedCount);
        out.println("failed_count: " + failedCount);
        out.println("expected_failure_count: " + expectedFailureCount);
        out.println("expected_failed_observed_count: " + expectedFailedObservedCount);
        if (output.resultJson() != null) {
            out.println("result_json: " + output.resultJson());
        }
        out.println("suite_summary_json: " + output.summaryJson());
        out.println("suite_summary_yaml: " + output.summaryYaml());
        out.println("allure_results_dir: " + output.allureResultsDir());
        printContractFindings(out, findings);
        return failedCount == 0 ? 0 : 1;
    }

    private SuiteGroupChildResult runSuiteGroupChild(
            SuiteGroupChild child,
            Path childOutputRoot,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery,
            SuiteExecutionContext parentContext) {
        ValidationResult validation = contractBaselineService.validateSuite(child.suiteManifest());
        if (!validation.valid()) {
            return SuiteGroupChildResult.fromBlocked(child, validation.suiteId(), validation.findings());
        }
        SuiteRuntimeResult result = suiteRuntimeDispatcher.dispatch(
                child.suiteManifest(),
                child.ref(),
                child.profile(),
                childOutputRoot,
                driverDiscovery,
                parentContext.forChildProfile(child.profile()));
        return SuiteGroupChildResult.fromSuiteRuntime(child, result);
    }

    private final class SuiteRuntimeDispatcher {

        SuiteRuntimeResult dispatch(
                Path suiteManifest,
                String suiteRef,
                String profile,
                Path outputRoot,
                JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
            return dispatchSuiteRuntimeInternal(suiteManifest, suiteRef, profile, outputRoot, driverDiscovery);
        }

        SuiteRuntimeResult dispatch(
                Path suiteManifest,
                String suiteRef,
                String profile,
                Path outputRoot,
                JdbcDriverDiscovery.DiscoveryResult driverDiscovery,
                SuiteExecutionContext parentContext) {
            return dispatchSuiteRuntimeInternal(
                    suiteManifest, suiteRef, profile, outputRoot, driverDiscovery, parentContext);
        }
    }

    private SuiteGroupOutput writeSuiteGroupOutput(
            SuiteGroupDefinition definition,
            String profile,
            String batchId,
            String runId,
            String status,
            long startedAt,
            long stoppedAt,
            Path runDir,
            List<SuiteGroupChildResult> childResults,
            int expectedFailureCount,
            int expectedFailedObservedCount,
            boolean canonicalV03) {
        try {
            Files.createDirectories(runDir);
            Path allureResultsDir = runDir.resolve("allure-results");
            Files.createDirectories(allureResultsDir);
            List<String> allureChildren = new ArrayList<>();
            for (SuiteGroupChildResult child : childResults) {
                allureChildren.add(writeAllureResult(definition, profile, runId, startedAt, stoppedAt, allureResultsDir, child));
            }
            writeAllureContainer(definition, startedAt, stoppedAt, allureResultsDir, allureChildren);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("suite_id", definition.suiteId());
            summary.put("profile", profile);
            summary.put("batch_id", batchId);
            summary.put("run_id", runId);
            summary.put("status", status);
            summary.put("test_count", childResults.size());
            summary.put("passed_count", childResults.stream().filter(SuiteGroupChildResult::passed).count());
            summary.put("failed_count", childResults.stream().filter(result -> !result.passed()).count());
            summary.put("expected_failure_count", expectedFailureCount);
            summary.put("expected_failed_observed_count", expectedFailedObservedCount);
            summary.put("expected_failed_missing_count", childResults.stream()
                    .filter(result -> "expected_failed_missing".equals(result.statusTaxonomy()))
                    .count());
            summary.put("unsupported_count", childResults.stream()
                    .filter(result -> "unsupported".equals(result.statusTaxonomy()))
                    .count());
            summary.put("blocked_count", childResults.stream()
                    .filter(result -> "blocked".equals(result.statusTaxonomy()))
                    .count());
            summary.put("allure_results_dir", allureResultsDir.toString());
            summary.put("children", childResults.stream().map(SuiteGroupChildResult::toSummary).toList());

            Path summaryJson = runDir.resolve(canonicalV03
                    ? "suite_summary.compatibility.json" : "suite_summary.json");
            Path summaryYaml = runDir.resolve(canonicalV03
                    ? "suite_summary.compatibility.yaml" : "suite_summary.yaml");
            Files.writeString(summaryJson, toJson(summary));
            Files.writeString(summaryYaml, new Yaml().dump(summary));
            return new SuiteGroupOutput(null, summaryJson, summaryYaml, allureResultsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write suite group output: " + runDir, e);
        }
    }

    private String writeAllureResult(
            SuiteGroupDefinition definition,
            String profile,
            String runId,
            long startedAt,
            long stoppedAt,
            Path allureResultsDir,
            SuiteGroupChildResult child) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(
                (runId + ":" + child.id()).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        result.put("historyId", UUID.nameUUIDFromBytes(child.id().getBytes(StandardCharsets.UTF_8)).toString());
        result.put("testCaseId", child.id());
        result.put("fullName", definition.suiteId() + "." + child.id());
        result.put("name", child.id());
        result.put("status", child.passed() ? "passed" : "failed");
        result.put("start", startedAt);
        result.put("stop", stoppedAt);
        result.put("labels", List.of(
                allureLabel("suite", definition.suiteId()),
                allureLabel("framework", "spec-driven-auto-regression"),
                allureLabel("profile", profile),
                allureLabel("child_suite_id", child.childSuiteId()),
                allureLabel("expected_status", child.expectedStatus()),
                allureLabel("observed_status", child.observedStatus()),
                allureLabel("provider_types", String.join(",", child.providerTypes()))));
        result.put("steps", List.of(allureStep("execute child suite", child.passed() ? "passed" : "failed", startedAt, stoppedAt)));
        Files.writeString(allureResultsDir.resolve(uuid + "-result.json"), toJson(result));
        return uuid;
    }

    private void writeAllureContainer(
            SuiteGroupDefinition definition,
            long startedAt,
            long stoppedAt,
            Path allureResultsDir,
            List<String> children) throws IOException {
        String uuid = UUID.nameUUIDFromBytes(
                (definition.suiteId() + ":container:" + startedAt).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("uuid", uuid);
        container.put("name", definition.suiteId());
        container.put("children", children);
        container.put("start", startedAt);
        container.put("stop", stoppedAt);
        container.put("befores", List.of());
        container.put("afters", List.of());
        Files.writeString(allureResultsDir.resolve(uuid + "-container.json"), toJson(container));
    }

    private Map<String, Object> allureLabel(String name, String value) {
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("name", name);
        label.put("value", value);
        return label;
    }

    private Map<String, Object> allureStep(String name, String status, long start, long stop) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("status", status);
        step.put("start", start);
        step.put("stop", stop);
        return step;
    }

    private boolean isSuiteGroupManifest(Path suiteManifest) {
        if (!Files.isRegularFile(suiteManifest)) {
            return false;
        }
        try {
            Object loaded = new Yaml().load(Files.readString(suiteManifest));
            return loaded instanceof Map<?, ?> map
                    && (map.containsKey("child_suites")
                            || ("suite_group".equals(stringValue(map.get("suite_type")))
                                    && map.containsKey("test_cases")));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private SuiteGroupDefinition suiteGroupDefinition(
            Path suiteManifest,
            Map<String, Object> manifest,
            List<ContractFinding> findings) {
        String suiteId = stringValue(manifest.get("suite_id"));
        if (suiteId.isBlank()) {
            findings.add(finding(suiteManifest, "suite_id", "missing_required_field", "Set suite_id on the suite group manifest."));
        }
        String profile = stringValue(manifest.get("profile"));
        if (profile.isBlank()) {
            findings.add(finding(suiteManifest, "profile", "missing_required_field", "Set the default suite group profile."));
        }
        String childrenField = manifest.containsKey("child_suites") ? "child_suites" : "test_cases";
        Object childrenValue = manifest.get(childrenField);
        if (!(childrenValue instanceof List<?> entries) || entries.isEmpty()) {
            findings.add(finding(
                    suiteManifest,
                    childrenField,
                    "missing_required_field",
                    "List at least one child suite manifest."));
            return new SuiteGroupDefinition(suiteId, profile, List.of());
        }
        Path suiteRoot = suiteDirectory(suiteManifest);
        List<SuiteGroupChild> children = new ArrayList<>();
        List<String> seenChildIds = new ArrayList<>();
        int index = 0;
        for (Object entry : entries) {
            String fieldPath = childrenField + "[" + index + "]";
            if (!(entry instanceof Map<?, ?> map)) {
                findings.add(finding(suiteManifest, fieldPath, "invalid_field_type", "Use a map for each child suite entry."));
                index++;
                continue;
            }
            String id = stringValue(map.get("id"));
            String ref = stringValue(map.get("ref"));
            String childProfile = stringValue(map.get("profile"));
            String expectedStatus = stringValue(map.get("expected_status"));
            if (expectedStatus.isBlank()) {
                expectedStatus = "passed";
            }
            if (id.isBlank()) {
                findings.add(finding(suiteManifest, fieldPath + ".id", "missing_required_field", "Set a stable child test id."));
            } else if (seenChildIds.contains(id)) {
                findings.add(finding(
                        suiteManifest,
                        fieldPath + ".id",
                        "duplicate_child_id",
                        "Use a unique child test id so suite summaries and Allure results cannot overwrite each other."));
            } else {
                seenChildIds.add(id);
            }
            if (ref.isBlank()) {
                findings.add(finding(suiteManifest, fieldPath + ".ref", "missing_required_field", "Set child suite manifest ref."));
            }
            if (childProfile.isBlank()) {
                findings.add(finding(suiteManifest, fieldPath + ".profile", "missing_required_field", "Set child execution profile."));
            }
            if (!List.of("passed", "failed").contains(expectedStatus)) {
                findings.add(finding(
                        suiteManifest,
                        fieldPath + ".expected_status",
                        "unsupported_expected_status",
                        "Use expected_status passed or failed."));
            }
            if (ref.isBlank()) {
                index++;
                continue;
            }
            Path childSuite = suiteRoot.resolve(ref).normalize();
            if (!childSuite.startsWith(suiteRoot)) {
                findings.add(finding(
                        suiteManifest,
                        fieldPath + ".ref",
                        "invalid_child_suite_ref",
                        "Keep child suite refs under the aggregation manifest directory."));
                index++;
                continue;
            }
            children.add(new SuiteGroupChild(id, ref, childSuite, childProfile, expectedStatus, fieldPath));
            index++;
        }
        return new SuiteGroupDefinition(suiteId, profile, List.copyOf(children));
    }

    private void addUnique(List<String> target, List<String> values) {
        for (String value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private Path suiteDirectory(Path suiteManifest) {
        Path normalized = suiteManifest.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    private ContractFinding finding(Path file, String fieldPath, String reason, String ownerAction) {
        return new ContractFinding(file.toString(), fieldPath, reason, "", "", "", "", ownerAction);
    }

    private String safeFileName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isBlank() ? "suite_group" : safe;
    }

    private boolean supportsWireMockHttpRequestSample(List<String> providerTypes) {
        return providerTypes.size() == 2
                && providerTypes.contains("wiremock_http_mock")
                && providerTypes.contains("rest_client");
    }

    private boolean supportsV03RuntimeService(ValidationResult validation) {
        return isV03Validation(validation);
    }

    private boolean isV03Validation(ValidationResult validation) {
        return validation.providerContractsUsed().stream().anyMatch(contract -> contract.endsWith(".v0.3"));
    }

    private boolean supportsContractBaselineMixedSample(List<String> providerTypes) {
        return providerTypes.size() == 3
                && providerTypes.contains("wiremock_http_mock")
                && providerTypes.contains("jdbc")
                && providerTypes.contains("nats");
    }

    private boolean supportsSoapMockSample(List<String> providerTypes) {
        return providerTypes.size() == 2
                && providerTypes.contains("soap_mock")
                && providerTypes.contains("rest_client");
    }

    private boolean supportsGrpcMockSample(List<String> providerTypes) {
        return providerTypes.size() == 2
                && providerTypes.contains("grpc_mock")
                && providerTypes.contains("grpc_client");
    }

    private boolean supportsMessagingClientSample(List<String> providerTypes, String suite) {
        return (!providerTypes.isEmpty() && providerTypes.stream().allMatch(List.of("kafka", "ibm_mq")::contains))
                || suite.contains("provider_capability/kafka")
                || suite.contains("provider_capability/ibm_mq");
    }

    private void printContractFindings(PrintStream out, List<ContractFinding> findings) {
        out.println("findings:");
        if (findings.isEmpty()) {
            out.println("  []");
            return;
        }
        for (ContractFinding finding : findings) {
            out.println("  - file_path: " + finding.filePath());
            out.println("    field_path: " + finding.fieldPath());
            out.println("    reason: " + finding.reason());
            out.println("    failure_code: " + finding.failureCode());
            out.println("    category: " + finding.category());
            if (!finding.providerId().isBlank()) {
                out.println("    provider_id: " + finding.providerId());
            }
            if (!finding.providerType().isBlank()) {
                out.println("    provider_type: " + finding.providerType());
            }
            if (!finding.profile().isBlank()) {
                out.println("    profile: " + finding.profile());
            }
            if (!finding.operation().isBlank()) {
                out.println("    operation: " + finding.operation());
            }
            if (!finding.originalCause().isBlank()) {
                out.println("    original_cause: " + finding.originalCause());
            }
            out.println("    owner_action: " + finding.ownerAction());
        }
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

    private List<Path> dependencyOrderedApprovedTests(
            GeneratedRuntimeContext runtimeContext,
            List<Path> approvedTests) {
        return approvedTests.stream()
                .sorted((left, right) -> {
                    int leftOrder = runtimeContext.order(targetRuId(left), adapterName(left));
                    int rightOrder = runtimeContext.order(targetRuId(right), adapterName(right));
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
        Map<String, Object> testCase = readYamlMap(approvedTest);
        ParameterSetResolution parameterSetResolution = parameterSetResolver.resolve(approvedTest, testCase);
        if (parameterSetResolution.parameterized() && parameterSetResolution.gaps().isEmpty()) {
            return parameterSetResolution.cases().isEmpty()
                    ? List.of(new ParameterCase("", "", Map.of()))
                    : parameterSetResolution.cases();
        }

        Object parametersValue = testCase.get("parameters");
        if (!(parametersValue instanceof Map<?, ?> parameters)
                || !"explicit_cases".equals(stringValue(parameters.get("strategy")))
                || !(parameters.get("cases") instanceof List<?> cases)
                || cases.isEmpty()) {
            return List.of(new ParameterCase("", "", Map.of()));
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
            parameterCases.add(new ParameterCase(caseId, "", copiedValues));
        }
        return parameterCases.isEmpty() ? List.of(new ParameterCase("", "", Map.of())) : List.copyOf(parameterCases);
    }

    private Path parameterizedTestCase(Path approvedTest, ParameterCase parameterCase) {
        if (parameterCase.caseId().isBlank()) {
            return approvedTest;
        }
        Map<String, Object> resolved = new LinkedHashMap<>(readYamlMap(approvedTest));
        resolved.put("parameter_case_id", parameterCase.caseId());
        resolved.put("resolved_parameters", parameterCase.values());
        resolved.replaceAll((key, value) -> resolveParameterReferences(value, parameterCase.values()));
        if (!parameterCase.bindAs().isBlank()) {
            resolved.replaceAll((key, value) ->
                    parameterSetResolver.resolveReferences(value, parameterCase.bindAs(), parameterCase.values()));
        }
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
            GeneratedRuntimeContext runtimeContext,
            Path approvedTest,
            String targetRuId,
            String adapter,
            List<String> dependencies,
            Map<String, String> targetStatuses) {
        for (String dependency : dependencies) {
            String dependencyStatus = targetStatuses.get(dependency);
            if (dependencyStatus != null && !"passed".equals(dependencyStatus)) {
                String failureDetail = failureDetail(
                        "Planning and Binding",
                        dependencyFieldPath(runtimeContext, targetRuId, adapter, dependency),
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

    private PreflightResult runPreflight(
            Path packageRoot,
            String requestedEnv,
            boolean dryRun,
            PrintStream out,
            GeneratedRuntimeContext runtimeContext,
            List<Path> approvedTests,
            List<SelectionGap> selectionGaps) {
        ExecutionEnvironmentReport environmentReport = executionEnvironmentResolver.resolve(runtimeContext);
        List<String> failureDetails = new java.util.ArrayList<>();
        Map<Path, List<String>> blockedTestFailureDetails = new LinkedHashMap<>();
        boolean globalBlocked = !environmentReport.ready() || approvedTests.isEmpty();
        boolean blocked = globalBlocked;

        if (dryRun) {
            out.println("provider_runtime_started: false");
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
            if (selectionGaps.isEmpty()) {
                out.println("  - ap: Definition and Validation");
                out.println("    field_path: tests/approved");
                out.println("    reason: " + failureReason("Definition and Validation", "tests/approved"));
                out.println("    owner_action: Add approved_for_regression DSL test cases before run.");
                failureDetails.add(failureDetail(
                        "Definition and Validation",
                        "tests/approved",
                        "Add approved_for_regression DSL test cases before run."));
            }
            for (SelectionGap gap : selectionGaps) {
                out.println("  - ap: Definition and Validation");
                out.println("    field_path: " + gap.fieldPath());
                out.println("    reason: " + failureReason("Definition and Validation", gap.fieldPath()));
                out.println("    owner_action: " + gap.ownerAction());
                failureDetails.add(failureDetail(
                        "Definition and Validation",
                        gap.fieldPath(),
                        gap.ownerAction()));
            }
        }

        Map<Path, DslValidationReport> dslReports = new LinkedHashMap<>();
        out.println("dsl_gaps:");
        for (Path approvedTest : approvedTests) {
            List<String> testFailureDetails = new java.util.ArrayList<>();
            DslValidationReport dslReport = dslTestCaseValidator.validate(approvedTest);
            List<DslValidationGap> dslGaps = new java.util.ArrayList<>(dslReport.gaps());
            DslValidationGap profileGap = profileCompatibilityGap(approvedTest, requestedEnv);
            if (profileGap != null) {
                dslGaps.add(profileGap);
            }
            DslValidationReport effectiveDslReport = new DslValidationReport(
                    dslGaps.isEmpty(),
                    dslReport.testCaseId(),
                    dslReport.acId(),
                    List.copyOf(dslGaps));
            dslReports.put(approvedTest, effectiveDslReport);
            blocked = blocked || !effectiveDslReport.ready();
            for (DslValidationGap gap : effectiveDslReport.gaps()) {
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

            ProviderContractResolutionReport providerReport = providerContractResolver.resolveGenerated(
                    packageRoot,
                    requestedEnv,
                    targetRuId(approvedTest),
                    adapterName(approvedTest),
                    bindingReport.resolvedBindings().stream().map(ResolvedBinding::bindingType).toList(),
                    fixtureReport.fixtureProviders());
            List<ProviderContractGap> providerGaps = new java.util.ArrayList<>(providerReport.gaps());
            providerGaps.addAll(requestResponseTestContextGaps(
                    packageRoot,
                    requestedEnv,
                    approvedTest,
                    bindingReport));
            providerGaps.addAll(messagingTestContextGaps(
                    packageRoot,
                    requestedEnv,
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
                out.println("    provider_contract_kind: " + contract.providerFamily());
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
                out.println("    provider_contract_kind: " + gap.providerFamily());
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
                        "provider_contract_kind: " + gap.providerFamily(),
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
            Path packageRoot,
            String requestedEnv,
            Path approvedTest,
            BindingResolutionReport bindingReport) {
        AdapterContractContext context = adapterContractContext(
                packageRoot,
                requestedEnv,
                targetRuId(approvedTest),
                adapterName(approvedTest));
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
            Object actionValue = actions.get(actionName);
            if (!(actionValue instanceof Map<?, ?> action)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName,
                        "provider",
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
                        "provider",
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
                        "provider",
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
            Path packageRoot,
            String requestedEnv,
            Path approvedTest,
            BindingResolutionReport bindingReport) {
        AdapterContractContext context = adapterContractContext(
                packageRoot,
                requestedEnv,
                targetRuId(approvedTest),
                adapterName(approvedTest));
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
            Object actionValue = actions.get(actionName);
            if (!(actionValue instanceof Map<?, ?> action)) {
                gaps.add(new ProviderContractGap(
                        context.contractPath() + ".actions." + actionName,
                        "provider",
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
                        "provider",
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
                        "provider",
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
                        "provider",
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
                            "provider",
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
                            "provider",
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
                            "provider",
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
                        "provider",
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
                        "provider",
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

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = stringValue(value);
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
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

    private AdapterContractContext adapterContractContext(
            Path packageRoot,
            String requestedEnv,
            String targetRuId,
            String adapter) {
        GeneratedRuntimeContext runtimeContext = generatedRuntimeArtifacts.resolve(packageRoot, requestedEnv);
        GeneratedRuntimeTarget target = runtimeContext.target(targetRuId, adapter);
        if (target == null) {
            return AdapterContractContext.empty(adapter);
        }
        Map<String, Object> adapterContract = providerContractResolver.generatedAdapterContract(
                packageRoot,
                requestedEnv,
                targetRuId,
                adapter);
        String contractPath = generatedRuntimeArtifacts.contractPath(
                target.providerContractRef(),
                adapterContract);
        return new AdapterContractContext(
                target.order(),
                target.targetId(),
                adapter,
                stringValue(adapterContract.get("provider_contract_kind")),
                stringValue(adapterContract.get("provider_type")),
                adapterContract,
                contractPath);
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
        if (fieldPath.startsWith("run.selection.") || "compatible_profiles".equals(fieldPath)) {
            return "suite_selection_failed";
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

    private TestSelection selectApprovedTests(Path packageRoot, Map<String, String> options) {
        String requestedTestCase = options.getOrDefault("--test-case", "").trim();
        String requestedTag = options.getOrDefault("--tag", "").trim();
        String requestedSuite = options.getOrDefault("--suite", "").trim();
        Set<String> suiteTestIds = requestedSuite.isBlank() ? Set.of() : suiteTestIds(packageRoot, requestedSuite);
        List<Path> approvedTests = approvedTests(packageRoot);
        List<Path> selected = approvedTests.stream()
                .filter(path -> requestedSuite.isBlank() || suiteTestIds.contains(testCaseId(path)))
                .filter(path -> requestedTestCase.isBlank() || requestedTestCase.equals(testCaseId(path)))
                .filter(path -> requestedTag.isBlank() || testCaseTags(path).contains(requestedTag))
                .toList();
        if (selected.isEmpty()
                && !approvedTests.isEmpty()
                && (!requestedSuite.isBlank() || !requestedTestCase.isBlank() || !requestedTag.isBlank())) {
            return new TestSelection(selected, List.of(selectionGap(requestedSuite, requestedTestCase, requestedTag)));
        }
        return new TestSelection(selected, List.of());
    }

    private SelectionGap selectionGap(String requestedSuite, String requestedTestCase, String requestedTag) {
        if (!requestedTestCase.isBlank()) {
            return new SelectionGap(
                    "run.selection.test_case",
                    "Select a test case ID that matches one approved DSL test case.");
        }
        if (!requestedTag.isBlank()) {
            return new SelectionGap(
                    "run.selection.tag",
                    "Select a tag that matches at least one approved DSL test case.");
        }
        return new SelectionGap(
                "run.selection.suite",
                "Select a suite ID whose generated suite manifest lists at least one approved DSL test case.");
    }

    private Set<String> suiteTestIds(Path packageRoot, String requestedSuite) {
        Map<String, Object> suiteManifest = readYamlMap(packageRoot.resolve("generated-framework/suite_manifest.yaml"));
        if (!requestedSuite.equals(stringValue(suiteManifest.get("suite_id")))) {
            return Set.of();
        }
        Object testsValue = suiteManifest.get("tests");
        if (!(testsValue instanceof List<?> tests)) {
            return Set.of();
        }
        return tests.stream()
                .map(this::stringValue)
                .map(this::testCaseIdFromSuitePath)
                .filter(id -> !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private String testCaseIdFromSuitePath(String suitePath) {
        if (suitePath.isBlank()) {
            return "";
        }
        String fileName = Path.of(suitePath).getFileName().toString();
        return fileName.endsWith(".yaml") ? fileName.substring(0, fileName.length() - ".yaml".length()) : fileName;
    }

    private List<String> testCaseTags(Path testCasePath) {
        Object tagsValue = testCaseMap(testCasePath).get("tags");
        if (!(tagsValue instanceof List<?> tags)) {
            return List.of();
        }
        return tags.stream()
                .map(this::stringValue)
                .filter(tag -> !tag.isBlank())
                .toList();
    }

    private DslValidationGap profileCompatibilityGap(Path testCasePath, String requestedProfile) {
        List<String> compatibleProfiles = compatibleProfiles(testCasePath);
        if (compatibleProfiles.isEmpty() || compatibleProfiles.contains(requestedProfile)) {
            return null;
        }
        return new DslValidationGap(
                testCaseId(testCasePath),
                acId(testCasePath),
                "profile",
                "compatible_profiles",
                "",
                "Select a compatible run profile or update compatible_profiles before execution.");
    }

    private List<String> compatibleProfiles(Path testCasePath) {
        Object profilesValue = testCaseMap(testCasePath).get("compatible_profiles");
        if (!(profilesValue instanceof List<?> profiles)) {
            return List.of();
        }
        return profiles.stream()
                .map(this::stringValue)
                .filter(profile -> !profile.isBlank())
                .toList();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String adapterName(Path testCasePath) {
        Object executionTarget = testCaseMap(testCasePath).get("execution_target");
        if (executionTarget instanceof Map<?, ?> target) {
            return firstText(target, "provider", "runner");
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

    private List<String> targetDependencies(
            GeneratedRuntimeContext runtimeContext,
            String targetRuId,
            String adapter) {
        return runtimeContext.dependencies(targetRuId, adapter);
    }

    private String dependencyFieldPath(
            GeneratedRuntimeContext runtimeContext,
            String targetRuId,
            String adapter,
            String dependencyRuId) {
        GeneratedRuntimeTarget target = runtimeContext.target(targetRuId, adapter);
        String targetId = target == null ? targetRuId : target.targetId();
        return "generated-framework/run_plan.yaml#target_dependencies." + targetId + " -> " + dependencyRuId;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escapeJson(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(entry.getKey())).append(": ").append(toJson(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    json.append(", ");
                }
                first = false;
                json.append(toJson(item));
            }
            return json.append("]").toString();
        }
        return toJson(String.valueOf(value));
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record SuiteGroupDefinition(String suiteId, String profile, List<SuiteGroupChild> children) {
    }

    private record SuiteGroupValidation(
            SuiteGroupDefinition definition,
            List<String> providerInstances,
            List<String> providerTypes,
            List<ContractFinding> findings) {

        boolean valid() {
            return findings.isEmpty();
        }
    }

    private record SuiteGroupChild(
            String id,
            String ref,
            Path suiteManifest,
            String profile,
            String expectedStatus,
            String fieldPath) {
    }

    private record SuiteGroupOutput(Path resultJson, Path summaryJson, Path summaryYaml, Path allureResultsDir) {
    }

    private record StatusCounts(int passedCount, int failedCount) {
    }

    private record SuiteRuntimeResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            boolean providerRuntimeExecuted,
            List<String> providerIds,
            List<String> providerTypes,
            Path resultJson,
            Path summaryJson,
            Path evidenceDir,
            String evidenceClassification,
            List<String> outputLines,
            List<ContractFinding> findings) {

        static SuiteRuntimeResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new SuiteRuntimeResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
                    profile,
                    false,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    "framework_provider_capability_only",
                    List.of(),
                    List.copyOf(findings));
        }

        static SuiteRuntimeResult fromMixed(MixedRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromContractBaseline(ContractBaselineRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    result.evidenceClassification(),
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromSoap(SoapRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromGrpc(GrpcRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_types: " + String.join(",", result.providerTypes()),
                            "provider_ids: " + String.join(",", result.providerIds())),
                    result.findings());
        }

        static SuiteRuntimeResult fromRest(RestClientRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "product_release_evidence_candidate",
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId()),
                    result.findings());
        }

        static SuiteRuntimeResult fromWireMock(WireMockRunResult result) {
            List<String> outputLines = new ArrayList<>();
            outputLines.add("provider_type: " + result.providerType());
            outputLines.add("provider_id: " + result.providerId());
            if (!result.baseUrl().isBlank()) {
                outputLines.add("base_url: " + result.baseUrl());
            }
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    outputLines,
                    result.findings());
        }

        static SuiteRuntimeResult fromJdbc(JdbcRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId(),
                            "runtime_mode: " + result.runtimeMode(),
                            "dialect: " + result.dialect()),
                    result.findings());
        }

        static SuiteRuntimeResult fromMessaging(MessagingClientRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    result.providerOutputLines(),
                    result.findings());
        }

        static SuiteRuntimeResult fromNats(NatsRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    result.evidenceClassification(),
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId(),
                            "runtime_mode: " + result.runtimeMode(),
                            "subject: " + result.subject()),
                    result.findings());
        }

        static SuiteRuntimeResult fromCommonVerify(CommonVerifyRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    blankListFiltered(result.providerId()),
                    blankListFiltered(result.providerType()),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_provider_capability_only",
                    List.of(
                            "provider_type: " + result.providerType(),
                            "provider_id: " + result.providerId()),
                    result.findings());
        }

        static SuiteRuntimeResult fromV03(V03RuntimeRunResult result) {
            List<String> outputLines = new ArrayList<>();
            result.providerTypes().forEach(providerType -> outputLines.add("provider_type: " + providerType));
            if (!result.targets().isEmpty()) {
                outputLines.add("targets: " + String.join(",", result.targets()));
            }
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.providerRuntimeExecuted(),
                    result.targets(),
                    result.providerTypes(),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    result.evidenceClassification(),
                    outputLines,
                    result.findings());
        }

        static SuiteRuntimeResult fromGolden(GoldenRunResult result) {
            return new SuiteRuntimeResult(
                    result.passed(),
                    result.status(),
                    result.suiteId(),
                    result.batchId(),
                    result.runId(),
                    result.testCaseId(),
                    result.testCount(),
                    result.profile(),
                    result.fakeProviderExecuted(),
                    result.fakeProviderExecuted() ? List.of("sample-fake-runtime") : List.of(),
                    List.of("sample_fake_provider"),
                    result.resultJson(),
                    null,
                    result.evidenceDir(),
                    "framework_verification_only",
                    List.of("fake_provider_executed: " + result.fakeProviderExecuted()),
                    result.findings());
        }

        SuiteRuntimeResult withSummaryJson(Path summaryJson) {
            return new SuiteRuntimeResult(
                    passed, status, suiteId, batchId, runId, testCaseId, testCount, profile,
                    providerRuntimeExecuted, providerIds, providerTypes, resultJson, summaryJson,
                    evidenceDir, evidenceClassification, outputLines, findings);
        }

        private static List<String> blankListFiltered(String value) {
            return value == null || value.isBlank() ? List.of() : List.of(value);
        }
    }

    private record SuiteGroupChildResult(
            String id,
            String ref,
            String childSuiteId,
            String profile,
            String expectedStatus,
            String observedStatus,
            boolean passed,
            String batchId,
            String runId,
            List<String> providerIds,
            List<String> providerTypes,
            Path resultJson,
            Path summaryJson,
            Path evidenceDir,
            List<ContractFinding> findings) {

        static SuiteGroupChildResult fromSuiteRuntime(SuiteGroupChild child, SuiteRuntimeResult result) {
            return new SuiteGroupChildResult(
                    child.id(),
                    child.ref(),
                    result.suiteId(),
                    result.profile(),
                    child.expectedStatus(),
                    result.status(),
                    child.expectedStatus().equals(result.status()),
                    result.batchId(),
                    result.runId(),
                    result.providerIds(),
                    result.providerTypes(),
                    result.resultJson(),
                    result.summaryJson(),
                    result.evidenceDir(),
                    result.findings());
        }

        static SuiteGroupChildResult fromBlocked(
                SuiteGroupChild child,
                String childSuiteId,
                List<ContractFinding> findings) {
            return new SuiteGroupChildResult(
                    child.id(),
                    child.ref(),
                    childSuiteId,
                    child.profile(),
                    child.expectedStatus(),
                    "blocked",
                    false,
                    "",
                    "",
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    List.copyOf(findings));
        }

        Map<String, Object> toSummary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", id);
            summary.put("ref", ref);
            summary.put("child_suite_id", childSuiteId);
            summary.put("profile", profile);
            summary.put("expected_status", expectedStatus);
            summary.put("observed_status", observedStatus);
            summary.put("status_taxonomy", statusTaxonomy());
            summary.put("status", passed ? "passed" : "failed");
            summary.put("batch_id", batchId);
            summary.put("run_id", runId);
            summary.put("provider_ids", providerIds);
            summary.put("provider_types", providerTypes);
            summary.put("result_json", resultJson == null ? "" : resultJson.toString());
            summary.put("evidence_dir", evidenceDir == null ? "" : evidenceDir.toString());
            summary.put("finding_count", findings.size());
            return summary;
        }

        String statusTaxonomy() {
            if (findings.stream().anyMatch(finding ->
                    finding.reason().contains("unsupported") || finding.failureCode().contains("UNSUPPORTED"))) {
                return "unsupported";
            }
            if ("blocked".equals(observedStatus)) {
                return "blocked";
            }
            if ("failed".equals(expectedStatus) && "failed".equals(observedStatus)) {
                return "expected_failed_observed";
            }
            if ("failed".equals(expectedStatus)) {
                return "expected_failed_missing";
            }
            if ("passed".equals(observedStatus)) {
                return "passed";
            }
            return "failed";
        }
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

    private record TestSelection(List<Path> tests, List<SelectionGap> gaps) {
    }

    private record SelectionGap(String fieldPath, String ownerAction) {
    }

    private record DependencyBlock(boolean blocked, String failureDetail) {
    }

    private record AdapterContractContext(
            int index,
            String ruId,
            String providerName,
            String providerFamily,
            String providerType,
            Map<String, Object> contract,
            String contractPath) {

        static AdapterContractContext empty(String providerName) {
            return new AdapterContractContext(
                    -1,
                    "",
                    providerName,
                    "",
                    "",
                    Map.of(),
                    "generated-framework/provider_contracts.providers." + providerName);
        }
    }

    private Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                putOption(options, args[i], args[i + 1]);
                i++;
            } else if (args[i].startsWith("--")) {
                putOption(options, args[i], VALUE_OPTIONS.contains(args[i]) ? "" : "true");
            } else if ("-h".equals(args[i])) {
                options.put(args[i], "true");
            }
        }
        return options;
    }

    private JdbcDriverDiscovery.DiscoveryResult jdbcDriverDiscovery(Path root, Map<String, String> options) {
        String driverPath = options.getOrDefault("--driver-path", "");
        List<String> driverPaths = driverPath.isBlank() ? List.of() : List.of(driverPath);
        return new JdbcDriverDiscovery(root, System::getenv)
                .discover(driverPaths, options.getOrDefault("--driver-dir", ""));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void putOption(Map<String, String> options, String name, String value) {
        if ("--driver-path".equals(name) && options.containsKey(name) && !options.get(name).isBlank()) {
            options.put(name, options.get(name) + System.getProperty("path.separator") + value);
            return;
        }
        options.put(name, value);
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
