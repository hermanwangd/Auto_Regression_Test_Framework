package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.specdriven.regression.adapter.AdapterExecutionResult;
import com.specdriven.regression.assertion.AssertionEvaluation;
import com.specdriven.regression.binding.ResolvedBinding;
import com.specdriven.regression.provider.ResolvedProviderContract;
import java.nio.file.Files;
import java.nio.file.Path;
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
                .contains("status: passed");
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
                        "cleanup", List.of(Map.of(
                                "provider", "relational_db",
                                "action", "cleanup_by_test_run_id"))));
    }
}
