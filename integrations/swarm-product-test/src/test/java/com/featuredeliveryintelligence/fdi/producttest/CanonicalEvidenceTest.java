package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test void importsCliOnlyWhenPinnedContractMatches() throws Exception {
        nativeFixture();
        selectNativeContract("cli_process.v0.3");
        ((ObjectNode) result.path("provider_results").get(0)).put("provider_type", "cli_process").put("provider_contract", "cli_process.v0.3");
        ((ObjectNode) result.path("step_results").get(0)).put("operation", "run_command");
        setOperationMetadata("run_command"); save();
        assertThat(CanonicalEvidence.inspect(request,root,stdout,started).verdict()).isEqualTo("passed");
    }

    @Test void importsNatsOnlyWhenPinnedContractAndOperationMatch() throws Exception {
        nativeFixture(); selectNativeContract("nats.v0.3");
        ((ObjectNode) result.path("provider_results").get(0)).put("provider_type", "nats").put("provider_contract", "nats.v0.3");
        ((ObjectNode) result.path("step_results").get(0)).put("operation", "nats_publish");
        setOperationMetadata("nats_publish"); save();
        assertThat(CanonicalEvidence.inspect(request,root,stdout,started).verdict()).isEqualTo("passed");
        ((ObjectNode) result.path("step_results").get(0)).put("operation", "http_request"); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("operation");
    }

    @Test void providerCannotSwitchFromReviewedHttpToCli() throws Exception {
        nativeFixture();
        ((ObjectNode) result.path("provider_results").get(0)).put("provider_type", "cli_process").put("provider_contract", "cli_process.v0.3");
        ((ObjectNode) result.path("step_results").get(0)).put("operation", "run_command"); save();
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("Native provider");
    }

    @Test void changedProvenanceCannotReplacePinnedProviderContract() throws Exception {
        nativeFixture();
        var provenance=(ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        ((ObjectNode)provenance.path("reviewed_contracts").get(0)).put("provider_contract", "cli_process.v0.3");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(), provenance);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("contract");
    }

    @Test void inconsistentProviderOperationMetadataIsRejected() throws Exception {
        for (String field : List.of("operation", "operations", "resolved_operation_results")) {
            nativeFixture();
            var provider = (ObjectNode) result.path("provider_results").get(0);
            switch(field) {
                case "operation" -> provider.put(field, "run_command");
                case "operations" -> provider.putArray(field).add("run_command");
                default -> ((ObjectNode) provider.path(field).get(0)).put("operation", "run_command");
            }
            save();
            assertThatThrownBy(() -> CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("operation");
        }
    }

    @Test void grpcRequiresIndependentLeaseIdentityAndActualUnaryProof() throws Exception {
        String pin=grpcFixture();
        assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("passed");
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("Independent fixture lease pin");
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started,"0".repeat(64))).hasMessageContaining("lease pin mismatch");
        var proof=(ObjectNode)Json.read(run.resolve("provider-evidence/grpc-client/reviewed-unary.json"));
        proof.put("rpc_attempted",false);Json.MAPPER.writeValue(run.resolve("provider-evidence/grpc-client/reviewed-unary.json").toFile(),proof);
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started,pin)).hasMessageContaining("actual RPC");
    }
    @Test void grpcProvenanceAndProofCannotReplaceIndependentlyPinnedLeaseBytes() throws Exception {
        String pin=grpcFixture();
        var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));
        ((ObjectNode)provenance.path("fixture_lease")).put("endpoint","grpc://127.0.0.1:2");
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
        var proof=(ObjectNode)Json.read(run.resolve("provider-evidence/grpc-client/reviewed-unary.json"));
        proof.put("endpoint","grpc://127.0.0.1:2");Json.MAPPER.writeValue(run.resolve("provider-evidence/grpc-client/reviewed-unary.json").toFile(),proof);
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started,pin)).hasMessageContaining("lease bytes");
    }
    @ParameterizedTest
    @ValueSource(strings = {"owner_zero", "owner_other", "endpoint", "lease_sha", "service", "method",
            "attempt_type", "cleanup_type", "attempt_false", "cleanup_false", "status_nonok"})
    void grpcProofMustMatchFrozenLeaseAndPassedAttemptSemantics(String mutation) throws Exception {
        String pin = grpcFixture();
        Path proofPath = run.resolve("provider-evidence/grpc-client/reviewed-unary.json");
        var proof = (ObjectNode) Json.read(proofPath);
        String diagnostic;
        switch (mutation) {
            case "owner_zero" -> { proof.put("owner_run_id", "0".repeat(32)); diagnostic = "fixture identity"; }
            case "owner_other" -> { proof.put("owner_run_id", "b".repeat(32)); diagnostic = "fixture identity"; }
            case "endpoint" -> { proof.put("endpoint", "grpc://127.0.0.1:12346"); diagnostic = "fixture identity"; }
            case "lease_sha" -> { proof.put("fixture_lease_sha256", "f".repeat(64)); diagnostic = "fixture identity"; }
            case "service" -> { proof.put("service", "helloworld.Greeter"); diagnostic = "finite service/method"; }
            case "method" -> { proof.put("method", "SayHello"); diagnostic = "finite service/method"; }
            case "attempt_type" -> { proof.put("rpc_attempted", "true"); diagnostic = "attempt/cleanup"; }
            case "cleanup_type" -> { proof.put("cleanup_confirmed", 0); diagnostic = "attempt/cleanup"; }
            case "attempt_false" -> { proof.put("rpc_attempted", false); diagnostic = "actual RPC"; }
            case "cleanup_false" -> { proof.put("cleanup_confirmed", false); diagnostic = "actual RPC"; }
            case "status_nonok" -> { proof.put("status", "UNAVAILABLE"); diagnostic = "actual RPC"; }
            default -> throw new AssertionError(mutation);
        }
        Json.MAPPER.writeValue(proofPath.toFile(), proof);
        assertThatThrownBy(() -> CanonicalEvidence.inspect(request, root, stdout, started, pin))
                .hasMessageContaining(diagnostic);
    }

    @Test void failedGrpcProofPreservesTypedFalseAttemptAndCleanup() throws Exception {
        String pin = grpcFixture();
        result.put("status", "failed"); save();
        var summary = (ObjectNode) Json.read(run.resolve("suite_summary.json"));
        summary.put("status", "failed"); Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(), summary);
        Path proofPath = run.resolve("provider-evidence/grpc-client/reviewed-unary.json");
        var proof = (ObjectNode) Json.read(proofPath);
        proof.put("rpc_attempted", false).put("cleanup_confirmed", false).put("status", "CLEANUP_UNCONFIRMED");
        Json.MAPPER.writeValue(proofPath.toFile(), proof);
        assertThat(CanonicalEvidence.inspect(request, root, stdout, started, pin).verdict()).isEqualTo("failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "blocked"})
    void grpcPreflightWithoutProviderKeepsNoProofOutcome(String verdict) throws Exception {
        String pin = grpcFixture();
        result.put("status", verdict); result.putArray("provider_results"); result.putArray("step_results");
        var refs = result.withArray("evidence_refs");
        for (int i = refs.size() - 1; i >= 0; i--) {
            if ("provider-evidence/grpc-client/reviewed-unary.json".equals(refs.get(i).asText())) refs.remove(i);
        }
        save();
        var summary = (ObjectNode) Json.read(run.resolve("suite_summary.json"));
        summary.put("status", verdict); Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(), summary);
        String proofEntry = "  - run_id: " + result.path("run_id").asText()
                + "\n    batch_id: BATCH-1\n    file_path: provider-evidence/grpc-client/reviewed-unary.json\n";
        String index = Files.readString(run.resolve("evidence_index.yaml"));
        assertThat(index).contains(proofEntry);
        Files.writeString(run.resolve("evidence_index.yaml"), index.replace(proofEntry, ""));
        Files.delete(run.resolve("provider-evidence/grpc-client/reviewed-unary.json"));
        assertThat(CanonicalEvidence.inspect(request, root, stdout, started, pin).verdict()).isEqualTo(verdict);
    }

    @Test void admittedGrpcAsciiResponseKeepsCanonicalBusinessVerdict() throws Exception {
        assertLargeGrpcCanonical("x".repeat(768*1024));
    }
    @Test void admittedEscapedGrpcResponseCanHaveCanonicalResultAboveFourMiB() throws Exception {
        assertLargeGrpcCanonical("\"".repeat(500000));
        assertThat(Files.size(run.resolve("result.json"))).isGreaterThan(4L*1024*1024);
    }
    private void assertLargeGrpcCanonical(String message) throws Exception {
        String pin=grpcFixture();
        String payload=Json.MAPPER.writeValueAsString(Map.of("message",message));
        ((ObjectNode)result.path("step_results").get(0)).putObject("outputs").put("response.message",payload);
        result.putArray("steps").add(result.path("step_results").get(0).deepCopy());
        ((ObjectNode)result.path("provider_results").get(0).path("resolved_operation_results").get(0)).putObject("outputs").put("response.message",payload);
        result.put("status","failed");save();
        var summary=(ObjectNode)Json.read(run.resolve("suite_summary.json"));summary.put("status","failed");Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(),summary);
        var proof=(ObjectNode)Json.read(run.resolve("provider-evidence/grpc-client/reviewed-unary.json"));proof.put("body",payload);Json.MAPPER.writeValue(run.resolve("provider-evidence/grpc-client/reviewed-unary.json").toFile(),proof);
        assertThatCode(()->assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("failed")).doesNotThrowAnyException();
    }
    @Test void ordinaryMetadataAndLegacyCanonicalLimitsRemainSmall() throws Exception {
        Path metadata=root.resolve("metadata.json");Json.MAPPER.writeValue(metadata.toFile(),Map.of("message","x".repeat(300000)));
        assertThatThrownBy(()->Json.read(metadata)).isInstanceOf(java.io.IOException.class);
        result.put("untrusted_large_field","x".repeat(300000));save();
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started)).isInstanceOf(java.io.IOException.class);
    }

    private String grpcFixture() throws Exception {
        nativeFixture(); selectNativeContract("grpc_client.v0.3");
        ((ObjectNode)result.path("provider_results").get(0)).put("provider_type","grpc_client").put("provider_contract","grpc_client.v0.3");
        ((ObjectNode)result.path("step_results").get(0)).put("operation","unary_call");setOperationMetadata("unary_call");
        var lease=Json.MAPPER.createObjectNode().put("version","reviewed-grpc-lease.v1").put("profile","echo.v1").put("endpoint","grpc://127.0.0.1:12345").put("owner_run_id","a".repeat(32));
        Files.createDirectories(root.resolve("fixtures"));Json.MAPPER.writeValue(root.resolve("fixtures/lease.json").toFile(),lease);
        String pin=LocalFiles.sha256(root.resolve("fixtures/lease.json"));
        var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.set("fixture_lease",lease);provenance.put("fixture_lease_sha256",pin);
        var input=(ObjectNode)Json.read(Path.of(request.reviewedHandoff()));
        input.putArray("reviewed_files").addObject().put("path","fixtures/lease.json").put("role","fixture").put("sha256",pin);
        Json.MAPPER.writeValue(Path.of(request.reviewedHandoff()).toFile(),input);
        var requestValue=(ObjectNode)Json.MAPPER.valueToTree(request);requestValue.put("expected_handoff_sha256",LocalFiles.sha256(Path.of(request.reviewedHandoff())));
        request=Json.MAPPER.treeToValue(requestValue,ProductTestRequest.class);provenance.put("handoff_sha256",request.expectedHandoffSha256());
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
        Path proofPath=run.resolve("provider-evidence/grpc-client/reviewed-unary.json");Files.createDirectories(proofPath.getParent());
        Json.MAPPER.writeValue(proofPath.toFile(),Map.of("fixture_lease_sha256",pin,"owner_run_id","a".repeat(32),"endpoint","grpc://127.0.0.1:12345","rpc_attempted",true,"cleanup_confirmed",true,"status","OK","service","swarm.producttest.EchoService","method","Echo"));
        result.withArray("evidence_refs").add("provider-evidence/grpc-client/reviewed-unary.json");
        String runId=result.path("run_id").asText();Files.writeString(run.resolve("evidence_index.yaml"),"  - run_id: "+runId+"\n    batch_id: BATCH-1\n    file_path: provider-evidence/grpc-client/reviewed-unary.json\n",java.nio.file.StandardOpenOption.APPEND);
        save();return pin;
    }

    private void setOperationMetadata(String op) {
        var provider = (ObjectNode) result.path("provider_results").get(0);
        provider.put("operation", op); provider.putArray("operations").add(op);
        provider.putArray("resolved_operation_results").addObject().put("operation", op);
    }

    private void selectNativeContract(String contract) throws Exception {
        var provenance=(ObjectNode) Json.read(run.resolve("product-test/reviewed-handoff.json"));
        provenance.putArray("reviewed_contracts").addObject().put("provider_contract",contract).put("path","contracts/provider.yaml").put("sha256","d".repeat(64));
        var input=Json.MAPPER.createObjectNode(); input.set("reviewed_contracts",provenance.path("reviewed_contracts"));
        Json.MAPPER.writeValue(Path.of(request.reviewedHandoff()).toFile(),input);
        var value=(ObjectNode)Json.MAPPER.valueToTree(request); value.put("expected_handoff_sha256", LocalFiles.sha256(Path.of(request.reviewedHandoff())));
        request=Json.MAPPER.treeToValue(value,ProductTestRequest.class);
        provenance.put("handoff_sha256",request.expectedHandoffSha256());
        Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
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
        setOperationMetadata("http_request"); save();
        selectNativeContract("rest_client.v0.3");
    }
}
