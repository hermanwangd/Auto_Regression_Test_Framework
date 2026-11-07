package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.v03.adapter.SampleFakeProviderV03Adapter;
import com.specdriven.regression.sit.*;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class SitReadinessRuntimeTest {
    @TempDir Path temp;

    @Test
    void nonReadyGatesEveryPhaseAndLinksActionableEvidenceToTheRun() throws Exception {
        for (String state : List.of("MISSING", "INVALID", "UNKNOWN", "BLOCKED")) {
            Path suite = suite(state, "fixture", false);
            var run = new V03RuntimeExecutionService().run(suite, "local_v03", temp.resolve("runs-" + state));
            assertThat(run.status()).isEqualTo("blocked");
            assertThat(run.providerRuntimeExecuted()).isFalse();
            Map<String, Object> result = read(run.resultJson());
            assertThat((List<?>) result.get("step_results")).isEmpty();
            assertThat((List<?>) result.get("cleanup_failures")).isEmpty();
            assertThat((List<?>) result.get("provider_results")).isEmpty();
            assertThat((List<String>) result.get("evidence_refs")).contains("readiness/sit-readiness.json");
            Map<String, Object> report = read(run.evidenceDir().resolve("readiness/sit-readiness.json"));
            assertThat(report).containsEntry("status", state).containsEntry("run_id", run.runId())
                    .containsEntry("profile", "local_v03").containsEntry("suite_id", run.suiteId());
            assertThat(report.toString()).contains("owner_action", "adapter_version=1", "simulated=true");
            assertThat(Files.readString(run.evidenceDir().resolve("evidence_index.yaml")))
                    .contains("evidence_type: sit_readiness", "status: blocked", "linked_result_field: evidence_refs");
            var validation = new EvidenceHardeningService().validateResult(run.resultJson());
            assertThat(validation.valid()).as(validation.findings().toString()).isTrue();
        }
    }

    @Test
    void localReadyKeepsExistingRunnerAndFixtureScope() throws Exception {
        var run = new V03RuntimeExecutionService().run(suite("READY", "fixture", false), "local_v03", temp.resolve("ready"));
        assertThat(run.status()).isEqualTo("passed");
        assertThat(run.providerRuntimeExecuted()).isTrue();
        assertThat(run.evidenceClassification()).isEqualTo("framework_verification_only");
        assertThat(read(run.evidenceDir().resolve("readiness/sit-readiness.json")))
                .containsEntry("status", "READY");
        var validation = new EvidenceHardeningService().validateResult(run.resultJson());
        assertThat(validation.valid()).as(validation.findings().toString()).isTrue();
    }

    @Test
    void unknownAdapterMalformedConfigAndReleaseFixtureStayBlocked() throws Exception {
        var unknown = new V03RuntimeExecutionService().run(suite("READY", "not-installed", false), "local_v03", temp.resolve("unknown"));
        assertThat(unknown.status()).isEqualTo("blocked");
        assertThat(Files.readString(unknown.evidenceDir().resolve("readiness/sit-readiness.json"))).contains("ADAPTER_UNAVAILABLE");
        Path malformed = suite("READY", "fixture", false);
        Path profile = malformed.getParent().resolve("env_profiles/local_v03.yaml");
        Map<String, Object> document = read(profile);
        document.put("sit_readiness", null);
        Files.writeString(profile, new Yaml().dump(document));
        var invalid = new V03RuntimeExecutionService().run(malformed, "local_v03", temp.resolve("invalid"));
        assertThat(invalid.status()).isEqualTo("blocked");
        assertThat(Files.readString(invalid.evidenceDir().resolve("readiness/sit-readiness.json"))).contains("INVALID_CONFIGURATION");
        var release = new V03RuntimeExecutionService().run(suite("READY", "fixture", true), "local_v03", temp.resolve("release"));
        assertThat(release.status()).isEqualTo("blocked");
        assertThat(release.providerRuntimeExecuted()).isFalse();
        assertThat(Files.readString(release.evidenceDir().resolve("readiness/sit-readiness.json"))).contains("SIMULATED_EVIDENCE_FORBIDDEN");
    }

    @Test
    void readinessConfigurationParticipatesInPlanIdentity() throws Exception {
        Path suite = suite("READY", "fixture", false);
        var before = new V03ExecutionPlanBuilder().build(suite, "local_v03");
        Path profile = suite.getParent().resolve("env_profiles/local_v03.yaml");
        Files.writeString(profile, Files.readString(profile).replace("READY", "MISSING"));
        var after = new V03ExecutionPlanBuilder().build(suite, "local_v03");
        assertThat(after.planDigest()).isNotEqualTo(before.planDigest());
    }

    @Test
    void adapterEvidenceIsSanitizedAndMissingEvidenceIsDetected() throws Exception {
        var adapter = new SitReadinessAdapter() {
            public String id() { return "custom"; }
            public String version() { return "test-1"; }
            public boolean simulated() { return true; }
            public SitReadinessObservation check(SitReadinessRequest request) {
                return new SitReadinessObservation(SitReadinessState.READY, "v1", Instant.now(),
                        Map.of("password", "private-value-123", "dataset", "isolated-orders"), "");
            }
        };
        var service = new V03RuntimeExecutionService(new ContractBaselineService(),
                new V03ProviderRuntimeRegistry(List.of(new SampleFakeProviderV03Adapter())),
                new SitReadinessService(new SitReadinessRegistry(List.of(adapter))));
        var run = service.run(suite("READY", "custom", false), "local_v03", temp.resolve("redacted"));
        assertThat(run.status()).isEqualTo("passed");
        Path evidence = run.evidenceDir().resolve("readiness/sit-readiness.json");
        assertThat(Files.readString(evidence)).doesNotContain("private-value-123").contains("MASKED");
        var validation = new EvidenceHardeningService().validateResult(run.resultJson());
        assertThat(validation.valid()).as(validation.findings().toString()).isTrue();
        Files.writeString(evidence, Files.readString(evidence).replace("[MASKED]", "[MASKED]still-secret"));
        assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isFalse();
        Files.delete(evidence);
        assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isFalse();
    }

    @Test
    void maskedTextMarkerRequiresAnExactTokenBoundary() throws Exception {
        var run = new V03RuntimeExecutionService().run(suite("READY", "fixture", false), "local_v03", temp.resolve("text-masking"));
        Path log = run.evidenceDir().resolve("logs/execution.log");
        String original = Files.readString(log);
        for (String prefix : List.of("password=", "authorization: ")) {
            Files.writeString(log, original + "\n" + prefix + "[MASKED]\n");
            assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isTrue();
            Files.writeString(log, original + "\n" + prefix + "[MASKED]still-secret\n");
            assertThat(new EvidenceHardeningService().validateResult(run.resultJson()).valid()).isFalse();
        }
    }

    @Test
    void legacyProfileCannotSilentlyIgnoreReadiness() throws Exception {
        Path root = copySuite(Path.of("samples/90-compatibility/legacy-v0.2/00-getting-started/golden_e2e"));
        Path manifest = root.resolve("suite_manifest.yaml");
        var validator = new ContractBaselineService();
        assertThat(validator.validateSuite(manifest).valid()).isTrue();
        Path profile = root.resolve("env_profiles/local_golden.yaml");
        Files.writeString(profile, Files.readString(profile) + "\nsit_readiness: {}\n");
        var validation = validator.validateSuite(manifest);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.findings()).anySatisfy(finding ->
                assertThat(finding.reason()).isEqualTo("unsupported_sit_readiness_version"));
    }

    private Path suite(String state, String adapter, boolean release) throws Exception {
        Path root = copySuite(Path.of("samples/00-getting-started/golden_e2e"));
        Path profile = root.resolve("env_profiles/local_v03.yaml");
        Map<String, Object> document = read(profile);
        document.put("downstream_release_evidence", release);
        document.put("sit_readiness", Map.of("timeout_ms", 500, "checks", List.of(Map.of(
                "id", "orders", "kind", "test_data", "adapter", adapter, "expected_version", "v1",
                "config", Map.of("state", state, "observed_version", "v1")))));
        Files.writeString(profile, new Yaml().dump(document));
        return root.resolve("suite_manifest.yaml");
    }

    private Path copySuite(Path source) throws Exception {
        Path root = Files.createTempDirectory(temp, "suite-");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = root.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        return root;
    }

    private Map<String, Object> read(Path path) throws Exception { return new Yaml().load(Files.readString(path)); }
}
