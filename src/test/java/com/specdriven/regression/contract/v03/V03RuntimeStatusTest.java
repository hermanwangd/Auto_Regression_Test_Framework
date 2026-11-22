package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.contract.ContractBaselineService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class V03RuntimeStatusTest {

    @Test
    void doesNotExposeRawOutputMapsToProviderAdapters() {
        assertThat(java.util.Arrays.stream(V03ExecutionContext.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("outputsByStep", "generatedOutputsByTarget", "producedOutputs");
    }

    @TempDir
    Path tempDir;

    @Test
    void appliesCanonicalStatusPrecedence() {
        assertThat(V03RuntimeExecutionService.canonicalStatus(List.of("passed", "skipped"))).isEqualTo("passed");
        assertThat(V03RuntimeExecutionService.canonicalStatus(List.of("skipped", "skipped"))).isEqualTo("skipped");
        assertThat(V03RuntimeExecutionService.canonicalStatus(List.of("failed", "blocked", "passed")))
                .isEqualTo("blocked");
        assertThat(V03RuntimeExecutionService.canonicalStatus(List.of("failed", "passed", "skipped")))
                .isEqualTo("failed");
    }

    @Test
    void rejectsUnknownCanonicalStatusesInsteadOfDefaultingToSkipped() {
        for (String status : new String[] {null, "", " ", "unknown", "timeout"}) {
            assertThatThrownBy(() -> V03RuntimeExecutionService.canonicalStatus(Arrays.asList(status)))
                    .as("test status %s", status)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("passed, failed, blocked, or skipped");
        }
        assertThatThrownBy(() -> V03RuntimeExecutionService.canonicalStatus(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planCompilationFailureReturnsBlockedResultForRequestedProfile() throws Exception {
        Path source = Path.of("samples/20-provider-capability-p0/messaging/kafka");
        Path suiteRoot = tempDir.resolve("kafka-missing-external-env");
        copyDirectory(source, suiteRoot);
        Path profile = suiteRoot.resolve("env_profiles/external_kafka.yaml");
        String missingEnv = "REGRESS_TEST_MISSING_"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Files.writeString(profile, Files.readString(profile).replace(
                "env://KAFKA_BOOTSTRAP_SERVERS", "env://" + missingEnv));

        V03RuntimeExecutionService.V03RuntimeRunResult run = new V03RuntimeExecutionService().run(
                suiteRoot.resolve("suite_manifest.yaml"),
                "external_kafka",
                tempDir.resolve("missing-env-run"));

        assertThat(run.status()).isEqualTo("blocked");
        assertThat(run.profile()).isEqualTo("external_kafka");
        assertThat(run.providerRuntimeExecuted()).isFalse();
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.reason()).isEqualTo("v03_plan_compilation_failed");
            assertThat(finding.ownerAction()).contains("missing_environment_value").contains(missingEnv);
        });
    }

    @Test
    void runtimePassesExplicitProfileTargetToProviderAndResult() throws Exception {
        List<V03ExecutionStep> executed = new ArrayList<>();
        V03ProviderRuntimeAdapter adapter = new V03ProviderRuntimeAdapter() {
            @Override public String providerType() { return "kafka"; }
            @Override public boolean supports(String contract, String operation) { return true; }

            @Override
            public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                executed.add(step);
                assertThat(context.targets().get(step.target()).profile()).isEqualTo("external_kafka");
                assertThat(context.targets().get(step.target()).runtimeMode()).isEqualTo("native");
                assertThat(context.targets().get(step.target()).bindings())
                        .containsEntry("bootstrap_servers", "contract-validation-host:9092");
                return new V03StepResult(step.id(), "passed", Map.of(), List.of(), "", "");
            }
        };
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(adapter)));

        var run = service.run(
                materializedKafkaSuiteForRuntime(), "external_kafka", tempDir.resolve("external-kafka-run"));

        assertThat(run.status()).isEqualTo("passed");
        assertThat(executed).allSatisfy(step -> {
            assertThat(step.profile()).isEqualTo("external_kafka");
            assertThat(step.runtimeMode()).isEqualTo("native");
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new Yaml().load(Files.readString(run.resultJson()));
        assertThat(result).containsEntry("profile", "external_kafka");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> providerResults = (List<Map<String, Object>>) result.get("provider_results");
        assertThat(providerResults).isNotEmpty().allSatisfy(providerResult -> {
            assertThat(providerResult).containsEntry("profile", "external_kafka");
            assertThat(providerResult).containsEntry("runtime_mode", "native");
        });
    }

    @Test
    void preservesRuntimeSuppliedSkippedAtTestStatusMappingSeam() {
        Map<String, String> testStatuses = new LinkedHashMap<>(Map.of("TC-1", "passed"));

        V03RuntimeExecutionService.applyOutcomeStatus(testStatuses, "TC-1", "skipped");

        assertThat(testStatuses).containsEntry("TC-1", "skipped");
    }

    @Test
    void blockedAdapterOutcomeReachesTestResultsAndSuiteStatus() throws Exception {
        List<String> executedPhases = new ArrayList<>();
        V03ProviderRuntimeAdapter blockedAdapter = new V03ProviderRuntimeAdapter() {
            @Override
            public String providerType() {
                return "sample_fake_provider";
            }

            @Override
            public boolean supports(String providerContract, String operation) {
                return true;
            }

            @Override
            public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                executedPhases.add(step.phase());
                if ("setup".equals(step.phase())) {
                    return new V03StepResult(step.id(), "blocked", Map.of(), List.of(),
                            "PROVIDER_UNAVAILABLE", "Provider is unavailable.");
                }
                return new V03StepResult(step.id(), "passed", Map.of(), List.of(), "", "");
            }
        };
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(blockedAdapter)));

        var run = service.run(
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"),
                "local_v03",
                tempDir);

        assertThat(run.status()).isEqualTo("blocked");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new Yaml().load(Files.readString(run.resultJson()));
        assertThat(result).doesNotContainKey("result_contract_version");
        assertThat(result.get("status")).isEqualTo("blocked");
        assertThat((List<Map<String, Object>>) result.get("test_results"))
                .extracting(entry -> entry.get("status"))
                .containsOnly("blocked");
        assertThat(executedPhases).containsExactly("setup", "cleanup");
    }

    @Test
    void primaryFailureSurvivesBlockedCleanupAndCleanupStillExecutes() throws Exception {
        List<String> executedPhases = new ArrayList<>();
        V03ProviderRuntimeAdapter adapter = statusAdapter(executedPhases, Map.of(
                "setup", "failed",
                "cleanup", "blocked"));
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(adapter)));

        var run = service.run(
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"),
                "local_v03",
                tempDir.resolve("failed-cleanup-blocked"));

        assertThat(run.status()).isEqualTo("failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) new Yaml().load(Files.readString(run.resultJson()));
        assertThat((List<Map<String, Object>>) result.get("test_results"))
                .extracting(entry -> entry.get("status"))
                .containsOnly("failed");
        assertThat(executedPhases).containsExactly("setup", "cleanup");
        assertThat((List<?>) result.get("cleanup_failures")).hasSize(1);
        assertThat(((Map<?, ?>) result.get("failure")).get("code")).isEqualTo("SETUP_FAILED");
        @SuppressWarnings("unchecked")
        Map<String, Object> providerResult = ((List<Map<String, Object>>) result.get("provider_results")).get(0);
        assertThat(providerResult).containsEntry("status", "failed").containsEntry("cleanup_status", "blocked");
        assertThat((List<?>) providerResult.get("cleanup_failures")).hasSize(1);
    }

    @Test
    void redactsSensitiveRuntimeOutputsAndProviderEvidenceBeforeWritingRunArtifacts() throws Exception {
        V03ProviderRuntimeAdapter adapter = new V03ProviderRuntimeAdapter() {
            @Override
            public String providerType() {
                return "sample_fake_provider";
            }

            @Override
            public boolean supports(String providerContract, String operation) {
                return true;
            }

            @Override
            public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                Path evidence = context.runDir().resolve("provider-evidence/raw-runtime.txt");
                try {
                    Files.createDirectories(evidence.getParent());
                    Files.writeString(evidence, "authorization=Bearer raw-runtime-token password=raw-password");
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
                Map<String, Object> outputs = "execute".equals(step.phase())
                        ? Map.of("actual_json", Map.of("token", "raw-runtime-token", "status", "OK"))
                        : Map.of();
                return new V03StepResult(step.id(), "passed", outputs,
                        List.of("provider-evidence/raw-runtime.txt"), "", "");
            }
        };
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(adapter)));

        var run = service.run(
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"),
                "local_v03",
                tempDir.resolve("redaction"));

        assertThat(Files.readString(run.resultJson()))
                .doesNotContain("raw-runtime-token")
                .doesNotContain("raw-password");
        assertThat(Files.readString(run.evidenceDir().resolve("provider-evidence/raw-runtime.txt")))
                .doesNotContain("raw-runtime-token")
                .doesNotContain("raw-password")
                .contains(V03OutputRedactor.MASKED);
    }

    @Test
    void rejectsProviderEvidenceOutsideTheRunDirectory() {
        V03ProviderRuntimeAdapter adapter = new V03ProviderRuntimeAdapter() {
            @Override public String providerType() { return "sample_fake_provider"; }
            @Override public boolean supports(String providerContract, String operation) { return true; }
            @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                return new V03StepResult(step.id(), "passed", Map.of(), List.of("../outside.txt"), "", "");
            }
        };
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(adapter)));

        assertThatThrownBy(() -> service.run(
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"), "local_v03",
                tempDir.resolve("escaped-evidence")))
                .hasMessageContaining("invalid_evidence_ref");
    }

    @Test
    void rejectsUndeclaredProviderOutputsBeforeTheyReachExecutionContext() {
        V03ProviderRuntimeAdapter adapter = new V03ProviderRuntimeAdapter() {
            @Override public String providerType() { return "sample_fake_provider"; }
            @Override public boolean supports(String providerContract, String operation) { return true; }
            @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                return new V03StepResult(step.id(), "passed", Map.of("unexpected", "raw-value"), List.of(), "", "");
            }
        };
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(adapter)));

        assertThatThrownBy(() -> service.run(
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"), "local_v03",
                tempDir.resolve("undeclared-output")))
                .hasMessageContaining("undeclared_provider_output");
    }

    @Test
    void rejectsFailureCodesNotDeclaredByTheProviderContract() {
        V03ProviderRuntimeAdapter adapter = new V03ProviderRuntimeAdapter() {
            @Override public String providerType() { return "sample_fake_provider"; }
            @Override public boolean supports(String providerContract, String operation) { return true; }
            @Override public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                return new V03StepResult(step.id(), "failed", Map.of(), List.of(), "NOT_DECLARED", "unexpected");
            }
        };
        V03RuntimeExecutionService service = new V03RuntimeExecutionService(
                new ContractBaselineService(), new V03ProviderRuntimeRegistry(List.of(adapter)));

        assertThatThrownBy(() -> service.run(
                Path.of("samples/00-getting-started/golden_e2e/suite_manifest.yaml"), "local_v03",
                tempDir.resolve("undeclared-failure-code")))
                .hasMessageContaining("undeclared_provider_failure_code");
    }

    @Test
    void sampleSetupOutputCanBeConsumedByCleanupInTheSameTestCase() throws Exception {
        Path source = Path.of("samples/00-getting-started/golden_e2e");
        Path suiteRoot = tempDir.resolve("sample-output-binding");
        copyDirectory(source, suiteRoot);

        var run = new V03RuntimeExecutionService().run(
                suiteRoot.resolve("suite_manifest.yaml"), "local_v03", tempDir.resolve("sample-output-run"));

        assertThat(run.status()).isEqualTo("passed");
    }

    private void copyDirectory(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private Path materializedKafkaSuiteForRuntime() throws Exception {
        Path source = Path.of("samples/20-provider-capability-p0/messaging/kafka");
        Path suiteRoot = tempDir.resolve("kafka-explicit-profile-runtime");
        copyDirectory(source, suiteRoot);
        Path profile = suiteRoot.resolve("env_profiles/external_kafka.yaml");
        Files.writeString(profile, Files.readString(profile)
                .replace("env://KAFKA_BOOTSTRAP_SERVERS", "contract-validation-host:9092"));
        return suiteRoot.resolve("suite_manifest.yaml");
    }

    private V03ProviderRuntimeAdapter statusAdapter(List<String> executedPhases, Map<String, String> statuses) {
        return new V03ProviderRuntimeAdapter() {
            @Override
            public String providerType() {
                return "sample_fake_provider";
            }

            @Override
            public boolean supports(String providerContract, String operation) {
                return true;
            }

            @Override
            public V03StepResult execute(V03ExecutionStep step, V03ExecutionContext context) {
                executedPhases.add(step.phase());
                String status = statuses.getOrDefault(step.phase(), "passed");
                String failureCode = "passed".equals(status) ? "" : step.phase().toUpperCase() + "_FAILED";
                return new V03StepResult(step.id(), status, Map.of(), List.of(), failureCode,
                        "passed".equals(status) ? "" : step.phase() + " did not complete.");
            }
        };
    }
}
