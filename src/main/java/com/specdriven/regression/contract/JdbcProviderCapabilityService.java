package com.specdriven.regression.contract;

import com.specdriven.regression.contract.ContractBaselineService.ContractFinding;
import com.specdriven.regression.provider.jdbc.JdbcDriverDiscovery;
import com.specdriven.regression.summary.SuiteExecutionContext;
import java.nio.file.Path;
import java.util.List;

/** Compatibility entry points for the retired v0.2 database runner. */
public class JdbcProviderCapabilityService {

    public JdbcRunResult run(Path suiteManifest, String requestedProfile, Path outputBase) {
        return retired(suiteManifest, requestedProfile);
    }

    public JdbcRunResult run(
            Path suiteManifest,
            String requestedProfile,
            Path outputBase,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
        return retired(suiteManifest, requestedProfile);
    }

    public JdbcRunResult run(
            Path suiteManifest, String requestedProfile, SuiteExecutionContext executionContext) {
        return run(suiteManifest, requestedProfile, executionContext, null);
    }

    public JdbcRunResult run(
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

    private JdbcRunResult retired(Path suiteManifest, String profile) {
        return JdbcRunResult.blocked("", profile, List.of(ContractBaselineService.retiredJdbcFinding(suiteManifest)));
    }

    public record JdbcRunResult(
            boolean passed,
            String status,
            String suiteId,
            String batchId,
            String runId,
            String testCaseId,
            int testCount,
            String profile,
            String providerId,
            String providerType,
            String runtimeMode,
            String dialect,
            Path resultJson,
            Path evidenceDir,
            boolean providerRuntimeExecuted,
            List<ContractFinding> findings) {

        static JdbcRunResult blocked(String suiteId, String profile, List<ContractFinding> findings) {
            return new JdbcRunResult(
                    false,
                    "blocked",
                    suiteId,
                    "",
                    "",
                    "",
                    0,
                    profile,
                    "",
                    "jdbc",
                    "",
                    "",
                    null,
                    null,
                    false,
                    List.copyOf(findings));
        }
    }
}
