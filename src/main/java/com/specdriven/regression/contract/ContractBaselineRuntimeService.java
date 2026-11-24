package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.Path;
import java.util.List;

/** Compatibility entry points for the retired v0.2 database runner. */
public class ContractBaselineRuntimeService {

    public ContractBaselineRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        return retired(suiteManifest, requestedProfile);
    }

    public ContractBaselineRunResult run(
            Path suiteManifest,
            String requestedProfile,
            Path outputBase,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
        return retired(suiteManifest, requestedProfile);
    }

    public ContractBaselineRunResult run(
            Path suiteManifest, String requestedProfile, SuiteExecutionContext executionContext) {
        return run(suiteManifest, requestedProfile, executionContext, null);
    }

    public ContractBaselineRunResult run(
            Path suiteManifest,
            String requestedProfile,
            SuiteExecutionContext executionContext,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
        if (!executionContext.matchesRequestedProfile(requestedProfile)) {
            throw new IllegalArgumentException("Execution context profile mismatch: context=" + executionContext.profile()
                    + ", requested=" + requestedProfile + ". Use the parent suite profile for all child runtimes.");
        }
        return retired(suiteManifest, requestedProfile);
    }

    private ContractBaselineRunResult retired(Path suiteManifest, String profile) {
        return ContractBaselineRunResult.blocked("", profile, List.of(ContractBaselineService.retiredJdbcFinding(suiteManifest)));
    }

    public record ContractBaselineRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            String profile,
            List<String> providerIds,
            List<String> providerTypes,
            Path resultJson,
            Path evidenceDir,
            int testCount,
            String evidenceClassification,
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static ContractBaselineRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new ContractBaselineRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    profile,
                    List.of(),
                    List.of("wiremock_http_mock", "jdbc", "nats"),
                    null,
                    null,
                    0,
                    "framework_provider_capability_only",
                    false,
                    List.copyOf(findings));
        }
    }
}
