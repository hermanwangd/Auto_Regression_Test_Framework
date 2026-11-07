package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalEvidenceTest {
    @TempDir Path root;
    Path run; ProductTestRequest request; String stdout; Instant started; ObjectNode result;
    @BeforeEach void fixture() throws Exception {
        root = root.toRealPath(); started = Instant.now();
        String runId = "RUN-V03-" + UUID.randomUUID();
        run = Files.createDirectories(root.resolve("target/regression/SUITE/BATCH-1/" + runId));
        request = new ProductTestRequest("product-test-request.v1", "product-test", "1", "mission:1", "request:1", "rev-17",
                "workspace:1", "project:1", Map.of("example", "a".repeat(40)), Map.of("REQ-1", List.of("TC-1")),
                "component", "regression", "local", "not_used", root.toString(), root.resolve("suite.yaml").toString(),
                "SUITE", "local", root.resolve("handoff.json").toString(), "b".repeat(64));
        result = Json.MAPPER.createObjectNode();
        result.put("suite_id", "SUITE").put("profile", "local").put("batch_id", "BATCH-1").put("run_id", runId)
                .put("status", "passed").put("dsl_version", "v0.3").put("result_contract_version", "v0.3")
                .put("start_time", started.toString()).put("end_time", Instant.now().toString()).put("plan_digest", "c".repeat(64))
                .put("evidence_index_ref", "evidence_index.yaml").put("suite_summary_ref", "suite_summary.json");
        result.putObject("labels").put("evidence_classification", "framework_verification_only").put("downstream_release_evidence", false);
        result.putArray("evidence_refs").add("product-test/reviewed-handoff.json");
        result.putArray("provider_results").addObject().put("release_evidence_eligible", false);
        Files.createDirectories(run.resolve("product-test"));
        var provenance = result.deepCopy();
        provenance.put("handoff_sha256", request.expectedHandoffSha256());
        provenance.put("knowledge_mode", "not_used");
        provenance.put("test_level", "component").put("test_type", "regression").put("environment_stage", "local");
        provenance.put("workspace_ref", request.workspaceRef()).put("project_ref", request.projectRef());
        provenance.put("evidence_classification", "framework_verification_only").put("downstream_release_evidence", false);
        provenance.set("repository_revisions", Json.MAPPER.valueToTree(request.repositoryRevisions()));
        provenance.putObject("provider_modes").put("sample_runtime", "simulated");
        provenance.putObject("observed_provider_modes").put("sample_runtime", "simulated");
        provenance.putObject("dependency_modes"); provenance.putObject("observed_dependency_modes");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(), result);
        Files.writeString(run.resolve("evidence_index.yaml"), """
                suite_id: SUITE
                profile: local
                batch_id: BATCH-1
                run_id: %s
                entries:
                  - run_id: %s
                    batch_id: BATCH-1
                    file_path: product-test/reviewed-handoff.json
                """.formatted(runId, runId));
        save();
        stdout = "run_id: " + runId + "\nsuite_id: SUITE\nprofile: local\nbatch_id: BATCH-1\nresult_json: " + run.resolve("result.json") + "\n";
    }
    void save() throws Exception { Json.MAPPER.writeValue(run.resolve("result.json").toFile(), result); }

    @Test void importsVerbatimStatusAndContentDigests() throws Exception {
        for (String status : List.of("passed", "failed", "blocked", "skipped")) {
            result.put("status", status); save();
            var summary = (ObjectNode) Json.read(run.resolve("suite_summary.json")); summary.put("status", status);
            Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(), summary);
            var evidence = CanonicalEvidence.inspect(request, root, stdout, started);
            assertThat(evidence.verdict()).isEqualTo(status);
            assertThat(evidence.artifacts().get("result").sha256()).isEqualTo(ProductTestingModuleTest.hash(run.resolve("result.json")));
        }
    }
    @Test void wrongRunIdentityRejected() throws Exception {
        result.put("run_id", "RUN-V03-" + UUID.randomUUID()); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("identity");
    }
    @Test void oldResultTimestampRejected() throws Exception {
        result.put("start_time", started.minusSeconds(60).toString()); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("fresh");
    }
    @Test void traversalRefRejectedBeforeFrameworkCanReadIt() throws Exception {
        result.put("evidence_index_ref", "../../outside.yaml"); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("relative");
    }
    @Test void symlinkEvidenceRejected() throws Exception {
        Files.delete(run.resolve("product-test/reviewed-handoff.json"));
        Files.writeString(root.resolve("outside.json"), "{}");
        Files.createSymbolicLink(run.resolve("product-test/reviewed-handoff.json"), root.resolve("outside.json"));
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("Symlink");
    }
    @Test void fixtureCannotBeRelabeledAsReleaseEvidence() throws Exception {
        ((ObjectNode) result.path("labels")).put("downstream_release_evidence", true); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("release");
    }
    @Test void mismatchedHandoffPinRejected() throws Exception {
        var provenance = (ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.put("handoff_sha256", "d".repeat(64));
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("handoff");
    }
    @Test void modesCannotBeRelabeledAsLive() throws Exception {
        var provenance = (ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.putObject("observed_provider_modes").put("sample_runtime", "live");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("mode");
    }
    @Test void changedEvidenceAfterValidationRejected() throws Exception {
        var evidence = CanonicalEvidence.inspect(request, root, stdout, started);
        Files.writeString(run.resolve("product-test/reviewed-handoff.json"), "{}");
        assertThatThrownBy(evidence::verifyUnchanged).hasMessageContaining("changed");
    }
    @Test void mismatchedReviewedScopeRejected() throws Exception {
        var provenance = (ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.put("workspace_ref", "unrelated-workspace");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("scope");
    }
    @Test void blockedBeforeObservationRetainsValidatedBlockedVerdict() throws Exception {
        result.put("status", "blocked"); save();
        var summary = (ObjectNode) Json.read(run.resolve("suite_summary.json")); summary.put("status", "blocked");
        Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(), summary);
        var provenance = (ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.putObject("observed_provider_modes");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        assertThat(CanonicalEvidence.inspect(request, root, stdout, started).verdict()).isEqualTo("blocked");
    }
    @Test void passStillRequiresCompleteObservedModes() throws Exception {
        var provenance = (ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.putObject("observed_provider_modes");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started)).hasMessageContaining("mode");
    }

    @Test void importsNativeHttpEvidenceOnlyForMatchingIntegrationTuple() throws Exception {
        nativeFixture();
        assertThat(CanonicalEvidence.inspect(request,root,stdout,started).verdict()).isEqualTo("passed");
    }

    @Test void nativeEvidenceCannotUseFakeRuntimeMetadata() throws Exception {
        nativeFixture();
        ((ObjectNode)result.path("provider_results").get(0)).put("runtime_mode","stub"); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("Native provider");
    }

    @Test void nativeEvidenceCannotRelabelTheReviewedTuple() throws Exception {
        nativeFixture();
        var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.put("test_level","component");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("tuple");
    }

    @Test void nativeEvidenceCannotHideItsActualMode() throws Exception {
        nativeFixture();
        var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.putObject("observed_provider_modes");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("mode");
    }

    private void nativeFixture() throws Exception {
        var value=(ObjectNode)Json.MAPPER.valueToTree(request); value.put("test_level","integration");
        request=Json.MAPPER.treeToValue(value,ProductTestRequest.class);
        var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.put("test_level","integration");
        provenance.putObject("provider_modes").put("todo_api","real");
        provenance.putObject("observed_provider_modes").put("todo_api","real");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
        result.putArray("provider_results").addObject().put("target","todo_api").put("provider_type","rest_client")
                .put("provider_contract","rest_client.v0.3").put("runtime_mode","native").put("release_evidence_eligible",false);
        result.putArray("step_results").addObject().put("target","todo_api").put("phase","execute").put("operation","http_request");
        save();
    }
}
