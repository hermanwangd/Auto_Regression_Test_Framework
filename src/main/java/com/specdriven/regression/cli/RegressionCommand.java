package com.specdriven.regression.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specdriven.regression.application.SuiteExecution;
import com.specdriven.regression.application.SuiteExecution.StatusCounts;
import com.specdriven.regression.application.SuiteExecution.SuiteRuntimeResult;
import com.specdriven.regression.application.SuiteReporting;
import com.specdriven.regression.application.SuiteValidation;
import com.specdriven.regression.application.SuiteValidation.SuiteGroupChild;
import com.specdriven.regression.application.SuiteValidation.SuiteGroupValidation;
import com.specdriven.regression.binding.BindingResolver;
import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.contract.ContractBaselineService.DryRunResult;
import com.specdriven.regression.contract.ContractBaselineService.ResolvedTarget;
import com.specdriven.regression.contract.v03.V03DryRunRenderer;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.environment.ExecutionEnvironmentResolver;
import com.specdriven.regression.evidence.EvidenceHardeningService.EvidenceValidationResult;
import com.specdriven.regression.evidence.EvidenceWriter;
import com.specdriven.regression.execution.ExecutionEngine;
import com.specdriven.regression.expectedresult.ExpectedResultService;
import com.specdriven.regression.fixture.FixtureLifecycleService;
import com.specdriven.regression.mapping.RpRuMappingService;
import com.specdriven.regression.oracle.OracleReadinessService;
import com.specdriven.regression.productrepo.ProductRepoService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.producttest.ProductTestHandoffService.InvocationRequirement;
import com.specdriven.regression.provider.ProviderContractResolver;
import com.specdriven.regression.readiness.AcIntakeService;
import com.specdriven.regression.report.CoverageReportService;
import com.specdriven.regression.testcase.TestCaseLifecycleService;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RegressionCommand {

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
            "--test-case", "--review-input", "--selection-receipt", "--out");

    private final LegacyProjectCommands project;
    private final SuiteValidation validation = new SuiteValidation();
    private final SuiteExecution execution;
    private final SuiteReporting reporting;
    private final ReportOutput reportOutput = new ReportOutput();
    private final V03DryRunRenderer dryRunRenderer = new V03DryRunRenderer();

    public RegressionCommand(ProductRepoService productRepoService, ReleasePackageService releasePackageService) {
        this(productRepoService, releasePackageService, System.getenv());
    }

    public RegressionCommand(ProductRepoService productRepoService, ReleasePackageService releasePackageService,
            Map<String, String> invocationEnvironment) {
        this(new LegacyProjectCommands(productRepoService, releasePackageService), new CoverageReportService(),
                InvocationRequirement.fromEnvironment(invocationEnvironment));
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
        this(new LegacyProjectCommands(productRepoService, releasePackageService, acIntakeService, rpRuMappingService,
                testCaseLifecycleService, expectedResultService, executionEnvironmentResolver, bindingResolver,
                providerContractResolver, fixtureLifecycleService, executionEngine, oracleReadinessService,
                evidenceWriter, coverageReportService), coverageReportService, InvocationRequirement.fromEnvironment(System.getenv()));
    }

    private RegressionCommand(LegacyProjectCommands project, CoverageReportService coverage,
            InvocationRequirement requirement) {
        this.project = project;
        this.execution = new SuiteExecution(validation, requirement);
        this.reporting = new SuiteReporting(coverage);
    }

    void enableLegacyRpModeForCompatibilityTests() {
        project.enableLegacyRpModeForCompatibilityTests();
    }

    public int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            printGeneralUsage(out);
            return 0;
        }
        String command = args[0];
        if ("build-info".equals(command)) {
            var info = com.specdriven.regression.build.RunnerBuildVariant.describe();
            try { out.println(new ObjectMapper().writeValueAsString(info)); }
            catch (java.io.IOException error) { err.println("Cannot render runner build information."); return 2; }
            return "VALID".equals(info.get("descriptor_status")) ? 0 : 2;
        }
        if (command.startsWith("runner-")) return new com.specdriven.regression.runner.RunnerCommand().execute(args, out, err);
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

        if ("run".equals(command) && options.containsKey("--suite")
                && java.util.Arrays.stream(args).filter(arg -> arg.equals("--test-case") || arg.startsWith("--test-case=")).count() > 1) {
            err.println("Specify --test-case only once.");
            return 2;
        }
        return switch (command) {
            case "init-product-repo" -> project.initProductRepo(root, out);
            case "check-readiness" -> project.checkReadiness(root, options, out, err);
            case "init-rp" -> project.initReleasePackage(root, options, out, err);
            case "check-rp" -> project.checkReleasePackage(root, options, out, err);
            case "generate-tests" -> project.generateTests(root, options, out, err);
            case "draft-expected-results" -> project.draftExpectedResults(root, options, out, err);
            case "validate" -> validate(root, options, out, err);
            case "validate-evidence" -> validateEvidence(root, options, out, err);
            case "prepare-product-test-handoff" -> prepareProductTestHandoff(root, options, out, err);
            case "run" -> runRegression(root, options, out, err);
            case "report" -> options.containsKey("--result") ? reportResult(root, options, out, err)
                    : project.report(root, options, out, err);
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
        out.println("  build-info --format json");
        out.println("  validate --suite <suite_manifest> [--profile <profile>]");
        out.println("  run --suite <suite_manifest> --profile <profile> [--test-case <id>] [--driver-path <jar>] [--driver-dir <dir>]");
        out.println("  run --suite <suite_manifest> --dry-run [--profile <profile>] [--test-case <id>]");
        out.println("  report --result <result_json> [--format text|yaml|json] [--coverage-dir <dir>] [--coverage-catalog <json>] [--coverage-selection <json>] [--coverage-authority <json> --expected-coverage-authority-sha256 <sha256>]");
        out.println("  validate-evidence --result <result_json>");
        out.println("  doctor drivers [--driver-path <jar>] [--driver-dir <dir>]");
        out.println("  prepare-product-test-handoff --suite <manifest> --profile <id> --review-input <json> --out <json> [--selection-receipt <json>]");
    }

    private void printCommandUsage(String command, PrintStream out) {
        switch (command) {
            case "run" -> {
                out.println("usage: regress run --suite <suite_manifest> --profile <profile> [--test-case <id>] [--driver-path <jar>] [--driver-dir <dir>]");
                out.println("       regress run --suite <suite_manifest> --dry-run [--profile <profile>] [--test-case <id>]");
            }
            case "validate" -> out.println("usage: regress validate --suite <suite_manifest> [--profile <profile>]");
            case "report" -> out.println("usage: regress report --result <result_json> [--format text|yaml|json] [--coverage-dir <dir>] [--coverage-catalog <json>] [--coverage-selection <json>] [--coverage-authority <json> --expected-coverage-authority-sha256 <sha256>]");
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
        return DriverDiagnostics.doctorDrivers(root, options, out);
    }
    private int validate(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String suite = requiredOption(options, "--suite", err);
        if (suite == null) {
            return 2;
        }
        Path suiteManifest = root.resolve(suite).normalize();
        if (validation.isSuiteGroupManifest(suiteManifest)) {
            return printGroupPlan(validation.checkGroup(suiteManifest, options.get("--profile")), false, out);
        }
        var leaf = validation.checkLeaf(suiteManifest, options.get("--profile"), null);
        var result = leaf.baseline();
        var compiled = leaf.plan();
        var findings = leaf.findings();
        boolean valid = findings.isEmpty();
        out.println("validation_status: " + (valid ? "passed" : "failed"));
        out.println("suite_id: " + result.suiteId());
        if (compiled != null) {
            out.println("plan_digest: " + compiled.planDigest());
        }
        if (!SuiteValidation.isV03Validation(result)) {
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
        printContractFindings(out, findings);
        return valid ? 0 : 1;
    }

    private int validateEvidence(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String resultPath = requiredOption(options, "--result", err);
        if (resultPath == null) {
            return 2;
        }
        EvidenceValidationResult result = reporting.validateEvidence(root.resolve(resultPath).normalize());
        reportOutput.printEvidenceValidationSummary(out, result, "evidence_validation_status");
        printContractFindings(out, result.findings());
        return result.valid() ? 0 : 1;
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
        var observation = reporting.report(root, resolvedResult, new SuiteReporting.CoverageInputs(
                options.get("--coverage-dir"), options.get("--coverage-catalog"), options.get("--coverage-selection"),
                options.get("--coverage-authority"), options.get("--expected-coverage-authority-sha256")));
        return reportOutput.render(observation, resolvedResult, format, out, err);
    }

    private void printReviewedRouteBlocked(PrintStream out) {
        out.println("run_status: blocked");
        out.println("provider_runtime_invoked: false");
        out.println("failure_code: REVIEWED_HANDOFF_REQUIRED");
        out.println("owner_action: A present invocation pin requires its reviewed v0.3 leaf, selected profile and product_test policy; groups, legacy and package routes are unsupported.");
    }

    private int prepareProductTestHandoff(Path root, Map<String,String> options, PrintStream out, PrintStream err) {
        for (String key : List.of("--suite", "--profile", "--review-input", "--out")) {
            if (requiredOption(options, key, err) == null) return 2;
        }
        try {
            Map<String,Object> prepared = new ProductTestHandoffService().prepare(
                    root.resolve(options.get("--suite")), options.get("--profile"),
                    root.resolve(options.get("--review-input")), options.containsKey("--selection-receipt")
                            ? root.resolve(options.get("--selection-receipt")) : null,
                    root.resolve(options.get("--out")));
            out.println("preparation_status: prepared");
            out.println("approval_status: not_approved");
            prepared.forEach((key,value) -> out.println(key + ": " + value));
            return 0;
        } catch (RuntimeException error) {
            err.println("preparation_status: blocked");
            err.println("owner_action: Correct the reviewed input bundle: " + error.getMessage());
            return 1;
        }
    }

    private int runRegression(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        if (execution.reviewedRouteBlocked(root, options.get("--suite"), options.get("--profile"),
                options.containsKey("--rp-id"), options.containsKey("--dry-run"))) {
            printReviewedRouteBlocked(out);
            return 1;
        }
        if (options.containsKey("--suite") && !options.containsKey("--rp-id")) {
            return runSuite(root, options, out, err);
        }
        return project.runRegression(root, options, out, err);
    }

    private int runSuite(Path root, Map<String, String> options, PrintStream out, PrintStream err) {
        String suite = requiredOption(options, "--suite", err);
        if (suite == null) {
            return 2;
        }
        Path suiteManifest = root.resolve(suite).normalize();
        String selectedTestCase = options.get("--test-case");
        if (selectedTestCase != null && selectedTestCase.isBlank()) {
            err.println("Specify a case ID after --test-case.");
            return 2;
        }
        if (options.containsKey("--tag")) {
            err.println("run --suite supports selection by --test-case on v0.3 leaf suites.");
            return 2;
        }
        boolean group = validation.isSuiteGroupManifest(suiteManifest);
        if (selectedTestCase != null && group) {
            err.println("Use --test-case with a v0.3 leaf suite manifest.");
            return 2;
        }
        if (!options.containsKey("--dry-run")) {
            String profile = requiredOption(options, "--profile", err);
            if (profile == null) {
                return 2;
            }
            var jdbcDrivers = DriverDiagnostics.forSuite(root, options);
            if (group) {
                return printGroupRun(execution.runGroup(
                        suiteManifest,
                        profile,
                        root.resolve("target/regression").normalize(),
                        jdbcDrivers), out);
            }
            SuiteRuntimeResult result = execution.runLeaf(
                    suiteManifest,
                    suite,
                    profile,
                    root.resolve("target/regression").normalize(), selectedTestCase, jdbcDrivers);
            printSuiteRuntimeResult(out, result);
            return result.passed() ? 0 : 1;
        }
        if (group) {
            return printGroupPlan(validation.checkGroup(suiteManifest, options.get("--profile")), true, out);
        }
        var dryRun = execution.dryRun(suiteManifest, options.get("--profile"), selectedTestCase);
        var compiled = dryRun.leaf().plan();
        var findings = dryRun.leaf().findings();
        boolean ready = findings.isEmpty();
        out.println("provider_runtime_invoked: false");
        out.println("run_status: " + (ready ? "dry_run_ready" : "blocked"));
        if (compiled != null) {
            dryRunRenderer.render(out, compiled);
        } else if (dryRun.compatibility() != null) {
            DryRunResult result = dryRun.compatibility();
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
        printContractFindings(out, findings);
        return ready ? 0 : 1;
    }

    private void printSuiteRuntimeResult(PrintStream out, SuiteRuntimeResult result) {
        out.println("run_status: " + result.status());
        out.println("suite_id: " + result.suiteId());
        if (result.resultJson() != null) {
            out.println("batch_id: " + result.batchId());
            out.println("run_id: " + result.runId());
            out.println("test_case_id: " + result.testCaseId());
            out.println("test_count: " + result.testCount());
            StatusCounts statusCounts = execution.suiteRuntimeStatusCounts(result);
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
        Map<String, Object> failure = execution.failureDetails(result);
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
    }

    private int printGroupPlan(SuiteGroupValidation validation, boolean dryRun, PrintStream out) {
        var definition = validation.definition();
        boolean valid = validation.valid();
        if (dryRun) {
            out.println("provider_runtime_invoked: false");
            out.println("run_status: " + (valid ? "dry_run_ready" : "blocked"));
        } else {
            out.println("validation_status: " + (valid ? "passed" : "failed"));
        }
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
            if (dryRun) out.println("    profile: " + child.profile());
            out.println("    expected_status: " + child.expectedStatus());
        }
        printContractFindings(out, validation.findings());
        return valid ? 0 : 1;
    }

    private int printGroupRun(SuiteExecution.GroupRun run, PrintStream out) {
        out.println("run_status: " + run.status());
        out.println("suite_id: " + run.suiteId());
        if (run.output() == null) {
            out.println("test_count: " + run.testCount());
            printContractFindings(out, run.findings());
            return 1;
        }
        var output = run.output();
        out.println("batch_id: " + run.batchId());
        out.println("run_id: " + run.runId());
        out.println("profile: " + run.profile());
        out.println("test_count: " + run.testCount());
        out.println("passed_count: " + run.passed());
        out.println("failed_count: " + run.failed());
        out.println("expected_failure_count: " + run.expectedFailureCount());
        out.println("expected_failed_observed_count: " + run.expectedFailedObservedCount());
        if (output.resultJson() != null) {
            out.println("result_json: " + output.resultJson());
        }
        out.println("suite_summary_json: " + output.summaryJson());
        out.println("suite_summary_yaml: " + output.summaryYaml());
        out.println("allure_results_dir: " + output.allureResultsDir());
        printContractFindings(out, run.findings());
        return run.failed() == 0 ? 0 : 1;
    }

    static void printContractFindings(PrintStream out, List<ContractFinding> findings) {
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

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--test-case=")) {
                putOption(options, "--test-case", args[i].substring("--test-case=".length()));
            } else if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
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

    private void putOption(Map<String, String> options, String name, String value) {
        if ("--driver-path".equals(name) && options.containsKey(name) && !options.get(name).isBlank()) {
            options.put(name, options.get(name) + System.getProperty("path.separator") + value);
            return;
        }
        options.put(name, value);
    }

    static String requiredOption(Map<String, String> options, String name, PrintStream err) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            err.println("Missing required option: " + name);
            return null;
        }
        return value;
    }
}
