package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.assertion.AssertionEvaluation;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.execution.ExecutionResult;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void simpleExecutionRunOverloadWritesEmptyResolutionSections() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-001");
        AdapterExecutionResult adapterResult = adapterResult(runDir);

        Path written = new EvidenceWriter().writeExecutionRun(
                runDir,
                "BATCH-001",
                "RUN-001",
                testCaseWithCleanup(),
                "passed",
                adapterResult,
                null);

        assertThat(written).isEqualTo(runDir);
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("run_id: RUN-001")
                .contains("resolved_dependencies:\n  []")
                .contains("resolved_bindings:\n  []")
                .contains("provider_contracts_used:\n  []")
                .contains("cleanup: cleanup.yaml")
                .contains("cleanup_result: cleanup.yaml")
                .contains("assertion_status: not_run");
        assertThat(Files.readString(runDir.resolve("cleanup.yaml")))
                .contains("provider: relational_db")
                .contains("action: cleanup_by_test_run_id")
                .contains("status: passed")
                .doesNotContain("not-a-cleanup-action");
    }

    @Test
    void bindingAndProviderContractOverloadWritesResolvedProviderEvidence() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-002");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("messaging.yaml"), "status: passed\n");
        Path assertionEvidence = runDir.resolve("assertions.yaml");
        Files.writeString(assertionEvidence, "assertions: []\n");

        Path written = new EvidenceWriter().writeExecutionRun(
                runDir,
                "BATCH-001",
                "RUN-002",
                testCase(),
                "passed",
                adapterResult(runDir),
                new AssertionEvaluation(
                        true,
                        "passed",
                        "file_diff",
                        "expected-results/approved/ER-001.yaml",
                        "expected/output.txt",
                        "actual/output.txt",
                        "exact",
                        "matched",
                        assertionEvidence),
                List.of(new ResolvedBinding("api_payload", "api_payload", "fixtures/request.json")),
                List.of(new ResolvedProviderContract(
                        "adapter",
                        "request_response",
                        "ru",
                        "request_response",
                        "rest",
                        "supported",
                        "supported",
                        "RU-api",
                        "request_response",
                        "release_units[0].provider_contracts.adapters.request_response")));

        assertThat(written).isEqualTo(runDir);
        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("binding_name: api_payload")
                .contains("provider_name: request_response")
                .contains("provider_family: request_response")
                .contains("provider_type: rest")
                .contains("messaging: messaging.yaml")
                .contains("assertion_status: passed")
                .contains("assertions: assertions.yaml");
    }

    @Test
    void executionRunWritesV02RuntimeSectionsWithEmptyOutputs() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-V02");

        new EvidenceWriter().writeExecutionRun(
                runDir,
                "BATCH-V02",
                "RUN-V02",
                v02TestCaseWithEmptyOutputs(),
                "failed",
                adapterResult(runDir),
                null);

        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("dsl_runtime:")
                .contains("dsl_version: v0.2")
                .contains("execute_steps:")
                .contains("outputs:\n        []")
                .contains("expected_results:\n    []")
                .contains("evidence_required:\n    []")
                .contains("runtime:\n    []");
    }

    @Test
    void executionRunWritesV02OutputMapFallbackWhenOutputRefIsAbsent() throws Exception {
        Path runDir = tempDir.resolve("evidence/runs/RUN-V02-OUTPUT-FALLBACK");
        Map<String, Object> testCase = v02TestCaseWithEmptyOutputs();
        testCase.put("execute", List.of(Map.of(
                "id", "call_api",
                "target", "RU-api",
                "operation", "call_api",
                "outputs", Map.of("actual", Map.of("path", "actual/output.json")))));

        new EvidenceWriter().writeExecutionRun(
                runDir,
                "BATCH-V02",
                "RUN-V02-OUTPUT-FALLBACK",
                testCase,
                "passed",
                adapterResult(runDir),
                null);

        assertThat(Files.readString(runDir.resolve("run.yaml")))
                .contains("outputs:")
                .contains("actual: {path=actual/output.json}");
    }

    @Test
    void executionBatchWritesEmptyRunsWhenNoApprovedTestsWereExecuted() throws Exception {
        Path batchDir = new EvidenceWriter().writeExecutionBatch(
                tempDir,
                "BATCH-EMPTY",
                "ci_ephemeral",
                "ci://api",
                "2026-06-28T00:00:00Z",
                "blocked",
                List.of());

        assertThat(Files.readString(batchDir.resolve("batch.yaml")))
                .contains("batch_id: BATCH-EMPTY")
                .contains("runs:\n  []");
    }

    @Test
    void blockedRunUsesApprovedTestExecutionContextWhenArgumentsAreBlank() throws Exception {
        Path approved = tempDir.resolve("tests/approved/TC-BLOCKED.yaml");
        Files.createDirectories(approved.getParent());
        Files.writeString(approved, """
                test_case_id: TC-BLOCKED
                ac_id: AC-BLOCKED
                rp_id: RP-BLOCKED
                parameter_case_id: CASE-001
                resolved_parameters:
                  payment_id: P-001
                execution_target:
                  ru_id: RU-api
                  execution_mode: sit_deployed
                  environment_ref: sit://rp/api
                """);

        ExecutionResult result = new EvidenceWriter().writeBlockedRun(
                tempDir,
                "BATCH-BLOCKED",
                "RUN-BLOCKED",
                List.of(approved),
                List.of("provider contract missing\nowner action required"),
                "",
                "",
                List.of("RU-auth"));

        String runYaml = Files.readString(result.runDir().resolve("run.yaml"));
        assertThat(result.status()).isEqualTo("blocked");
        assertThat(runYaml)
                .contains("execution_mode: sit_deployed")
                .contains("environment_ref: sit://rp/api")
                .contains("parameter_case_id: CASE-001")
                .contains("payment_id: P-001")
                .contains("resolved_dependencies:\n  - RU-auth");
        assertThat(Files.readString(result.runDir().resolve("failure_details.yaml")))
                .contains("provider contract missing\n    owner action required");
    }

    @Test
    void blockedRunTreatsNonMapApprovedTestAsEmptySourceArtifact() throws Exception {
        Path approved = tempDir.resolve("tests/approved/TC-NON-MAP.yaml");
        Files.createDirectories(approved.getParent());
        Files.writeString(approved, """
                - not
                - a
                - map
                """);

        ExecutionResult result = new EvidenceWriter().writeBlockedRun(
                tempDir,
                "BATCH-NON-MAP",
                "RUN-NON-MAP",
                List.of(approved),
                List.of("not executable"),
                "ci_ephemeral",
                "ci://api");

        assertThat(result.testCaseId()).isEmpty();
        assertThat(Files.readString(result.runDir().resolve("run.yaml")))
                .contains("test_case_id: \n")
                .contains("execution_mode: ci_ephemeral")
                .contains("environment_ref: ci://api");
    }

    @Test
    void throwsUncheckedIoWhenExecutionRunDirectoryCannotBeCreated() throws Exception {
        Path blockedParent = tempDir.resolve("blocked-run-parent");
        Files.writeString(blockedParent, "not a directory");
        Path runDir = blockedParent.resolve("RUN-IO");

        assertThatThrownBy(() -> new EvidenceWriter().writeExecutionRun(
                        runDir,
                        "BATCH-IO",
                        "RUN-IO",
                        testCase(),
                        "failed",
                        adapterResult(runDir),
                        null))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write execution run evidence.");
    }

    @Test
    void throwsUncheckedIoWhenExecutionBatchDirectoryCannotBeCreated() throws Exception {
        Path packageRoot = tempDir.resolve("batch-root");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("evidence"), "not a directory");

        assertThatThrownBy(() -> new EvidenceWriter().writeExecutionBatch(
                        packageRoot,
                        "BATCH-IO",
                        "ci_ephemeral",
                        "ci://api",
                        "2026-06-28T00:00:00Z",
                        "failed",
                        List.of()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write execution batch evidence.");
    }

    @Test
    void throwsUncheckedIoWhenBlockedRunDirectoryCannotBeCreated() throws Exception {
        Path packageRoot = tempDir.resolve("blocked-root");
        Files.createDirectories(packageRoot);
        Files.writeString(packageRoot.resolve("evidence"), "not a directory");

        assertThatThrownBy(() -> new EvidenceWriter().writeBlockedRun(
                        packageRoot,
                        "BATCH-BLOCKED-IO",
                        "RUN-BLOCKED-IO",
                        List.of(),
                        List.of("blocked"),
                        "ci_ephemeral",
                        "ci://api"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to write blocked run evidence.");
    }

    @Test
    void throwsUncheckedIoWhenBlockedRunSourceArtifactCannotBeRead() {
        assertThatThrownBy(() -> new EvidenceWriter().writeBlockedRun(
                        tempDir,
                        "BATCH-MISSING",
                        "RUN-MISSING",
                        List.of(tempDir.resolve("tests/approved/missing.yaml")),
                        List.of("missing"),
                        "ci_ephemeral",
                        "ci://api"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("Failed to read evidence source artifact:");
    }

    private AdapterExecutionResult adapterResult(Path runDir) {
        return new AdapterExecutionResult(
                0,
                false,
                runDir.resolve("logs/stdout.log"),
                runDir.resolve("logs/stderr.log"),
                runDir.resolve("actual/output.txt"));
    }

    private Map<String, Object> testCase() {
        return Map.of(
                "rp_id", "RP-001",
                "test_case_id", "TC-001",
                "ac_id", "AC-001",
                "execution_target", Map.of(
                        "ru_id", "RU-api",
                        "execution_mode", "ci_ephemeral",
                        "environment_ref", "ci://api"));
    }

    private Map<String, Object> testCaseWithCleanup() {
        return Map.of(
                "rp_id", "RP-001",
                "test_case_id", "TC-001",
                "ac_id", "AC-001",
                "execution_target", Map.of(
                        "ru_id", "RU-api",
                        "execution_mode", "ci_ephemeral",
                        "environment_ref", "ci://api"),
                "fixture", Map.of(
                        "cleanup", List.of(
                                "not-a-cleanup-action",
                                Map.of(
                                        "provider", "relational_db",
                                        "action", "cleanup_by_test_run_id"))));
    }

    private Map<String, Object> v02TestCaseWithEmptyOutputs() {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("dsl_version", "v0.2");
        testCase.put("test_case_id", "TC-V02");
        testCase.put("ac_id", "AC-V02");
        testCase.put("rp_id", "RP-V02");
        testCase.put("execution_target", Map.of(
                "ru_id", "RU-api",
                "execution_mode", "ci_ephemeral",
                "environment_ref", "ci://api"));
        testCase.put("targets", Map.of(
                "RU-api", Map.of(
                        "type", "application",
                        "runner", "request_response",
                        "environment", "ci://api")));
        testCase.put("setup", Map.of("fixtures", Map.of()));
        testCase.put("execute", List.of(Map.of(
                "id", "call_api",
                "target", "RU-api",
                "operation", "call_api",
                "outputs", Map.of())));
        testCase.put("expected_results", Map.of());
        testCase.put("verify", List.of());
        testCase.put("evidence", Map.of("required", List.of()));
        testCase.put("runtime", Map.of());
        return testCase;
    }
}
