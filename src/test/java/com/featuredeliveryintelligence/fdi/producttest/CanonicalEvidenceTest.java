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

    @Test void kafkaPublishWithoutIndependentLeaseIsRejectedByItsOwnGuard() throws Exception {
        nativeFixture();selectNativeContract("kafka.v0.3");
        ((ObjectNode)result.path("provider_results").get(0)).put("provider_type","kafka").put("provider_contract","kafka.v0.3");
        ((ObjectNode)result.path("step_results").get(0)).put("operation","kafka_publish");setOperationMetadata("kafka_publish");save();
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started)).hasMessageContaining("Independent Kafka fixture lease pin");
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

    @Test void kafkaHistoricalImportJoinsPinnedLeaseAndPreservesWrongOracleFail() throws Exception {
        String pin=kafkaFixture();assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("passed");
        result.put("status","failed");save();Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(),result);assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("failed");
    }
    @ParameterizedTest @ValueSource(strings={"run_id","batch_id","step_id","provider_id","operation","fixture_lease_sha256","owner_run_id","endpoint","topic","consumer_group","key","payload_sha256","client_version","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode","attempted","acknowledged","cleanup_confirmed","partition","offset","status","extra","missing","raw_lease","lease_bytes","payload_closure","output"})
    void kafkaProofIdentityCannotBeChangedAfterResealing(String mutation)throws Exception {
        String pin=kafkaFixture();Path proofFile=run.resolve("provider-evidence/kafka/reviewed-publish.json");var proof=(ObjectNode)Json.read(proofFile);
        switch(mutation){
            case "attempted","acknowledged","cleanup_confirmed"->proof.put(mutation,false);
            case "partition","offset"->proof.put(mutation,1);
            case "extra"->proof.put("private_body","PRIVATE-CANARY");
            case "missing"->{Files.delete(proofFile);returnKafkaReject(pin);return;}
            case "raw_lease"->{var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));((ObjectNode)provenance.path("fixture_lease")).put("endpoint","127.0.0.1:2");Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);proof.put("endpoint","127.0.0.1:2");}
            case "lease_bytes"->Files.writeString(root.resolve("fixtures/lease.json"),Files.readString(root.resolve("fixtures/lease.json"))+" ");
            case "payload_closure"->{var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));for(var f:provenance.path("reviewed_files"))if(f.path("path").asText().equals("fixtures/payload.json"))((ObjectNode)f).put("sha256","0".repeat(64));Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);}
            case "output"->((ObjectNode)result.path("step_results").get(0).path("outputs")).put("offset",1);
            default->proof.put(mutation,"ALTERED");
        }
        Json.MAPPER.writeValue(proofFile.toFile(),proof);save();returnKafkaReject(pin);
    }
    private void returnKafkaReject(String pin){assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started,pin)).isInstanceOf(Exception.class);}
    @ParameterizedTest @ValueSource(strings={"lease_expired","lease_future","client_before_run","client_after_run","client_after_lease"})
    void kafkaHistoricalTimeJoinsMatchCoreAdmission(String variant)throws Exception {
        kafkaFixture();String pin=resealKafkaTime(variant);assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started,pin)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void kafkaHistoricalLeaseExpiredNowDoesNotRequireCurrentReadiness()throws Exception {
        kafkaFixture();String pin=resealKafkaTime("historic");var lease=Json.read(root.resolve("fixtures/lease.json"));assertThat(Instant.parse(lease.path("expires_at").asText()).isBefore(Instant.now())).isTrue();assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("passed");
    }
    @Test void kafkaNoProviderPreflightRetainsValidHistoricalLeaseWithoutInventedProof()throws Exception {
        String pin=kafkaFixture();result.put("status","blocked");result.putArray("provider_results");result.putArray("step_results");var refs=(com.fasterxml.jackson.databind.node.ArrayNode)result.path("evidence_refs");for(int i=refs.size()-1;i>=0;i--)if(refs.get(i).asText().equals("provider-evidence/kafka/reviewed-publish.json"))refs.remove(i);Files.delete(run.resolve("provider-evidence/kafka/reviewed-publish.json"));
        var yaml=new org.yaml.snakeyaml.Yaml();Map<String,Object> index=yaml.load(Files.readString(run.resolve("evidence_index.yaml")));((List<Map<String,Object>>)index.get("entries")).removeIf(e->"provider-evidence/kafka/reviewed-publish.json".equals(e.get("file_path")));Files.writeString(run.resolve("evidence_index.yaml"),yaml.dump(index));var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));provenance.putObject("observed_provider_modes");Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);save();Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(),result);assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("blocked");
    }
    @Test void kafkaFailedSendRetainsValidUnacknowledgedHistoricalProof()throws Exception {
        String pin=kafkaFixture();result.put("status","failed");var step=(ObjectNode)result.path("step_results").get(0);step.put("status","failed");((ObjectNode)step.path("outputs")).put("partition",-1).put("offset",-1);var proof=(ObjectNode)Json.read(run.resolve("provider-evidence/kafka/reviewed-publish.json"));proof.put("status","CLIENT_FAILED").put("attempted",true).put("acknowledged",false).put("cleanup_confirmed",true).put("partition",-1).put("offset",-1);Json.MAPPER.writeValue(run.resolve("provider-evidence/kafka/reviewed-publish.json").toFile(),proof);save();Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(),result);assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("failed");
    }
    private String resealKafkaTime(String variant)throws Exception {
        var lease=(ObjectNode)Json.read(root.resolve("fixtures/lease.json"));var proof=(ObjectNode)Json.read(run.resolve("provider-evidence/kafka/reviewed-publish.json"));var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));Instant start=Instant.parse(result.path("start_time").asText()),end=Instant.parse(result.path("end_time").asText());
        switch(variant){case "lease_expired"->lease.put("issued_at",start.minusSeconds(300).toString()).put("expires_at",start.minusNanos(1).toString());case "lease_future"->lease.put("issued_at",start.plusNanos(1).toString()).put("expires_at",start.plusSeconds(300).toString());case "client_before_run"->proof.put("client_started_at",start.minusNanos(1).toString());case "client_after_run"->proof.put("client_finished_at",end.plusNanos(1).toString());case "client_after_lease"->lease.put("expires_at",Instant.parse(proof.path("client_finished_at").asText()).minusNanos(1).toString());case "historic"->{start=start.minusSeconds(60);end=end.minusSeconds(60);started=started.minusSeconds(60);result.put("start_time",start.toString()).put("end_time",end.toString());proof.put("client_started_at",start.toString()).put("client_finished_at",end.toString());lease.put("issued_at",start.minusSeconds(1).toString()).put("expires_at",end.plusSeconds(1).toString());}default->throw new AssertionError(variant);}
        byte[] raw=Json.MAPPER.writeValueAsBytes(lease);Files.write(root.resolve("fixtures/lease.json"),raw);String pin=LocalFiles.sha256(raw);provenance.set("fixture_lease",lease);provenance.put("fixture_lease_sha256",pin).put("fixture_lease_base64",java.util.Base64.getEncoder().encodeToString(raw));for(var f:provenance.path("reviewed_files"))if(f.path("path").asText().equals("fixtures/lease.json"))((ObjectNode)f).put("sha256",pin);
        var input=(ObjectNode)Json.read(Path.of(request.reviewedHandoff()));for(var f:input.path("reviewed_files"))if(f.path("path").asText().equals("fixtures/lease.json"))((ObjectNode)f).put("sha256",pin);Json.MAPPER.writeValue(Path.of(request.reviewedHandoff()).toFile(),input);var req=(ObjectNode)Json.MAPPER.valueToTree(request);req.put("expected_handoff_sha256",LocalFiles.sha256(Path.of(request.reviewedHandoff())));request=Json.MAPPER.treeToValue(req,ProductTestRequest.class);provenance.put("handoff_sha256",request.expectedHandoffSha256());Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);proof.put("fixture_lease_sha256",pin);Json.MAPPER.writeValue(run.resolve("provider-evidence/kafka/reviewed-publish.json").toFile(),proof);save();Json.MAPPER.writeValue(run.resolve("suite_summary.json").toFile(),result);return pin;
    }
    private String kafkaFixture()throws Exception {
        nativeFixture();selectNativeContract("kafka.v0.3");String owner="b".repeat(32),topic="sit."+owner+".publish";
        ((ObjectNode)result.path("provider_results").get(0)).put("provider_type","kafka").put("provider_contract","kafka.v0.3");
        var step=(ObjectNode)result.path("step_results").get(0);step.put("id","publish").put("operation","kafka_publish");step.putObject("outputs").put("partition",0).put("offset",0).put("key","run."+owner).put("topic",topic);setOperationMetadata("kafka_publish");
        Files.createDirectories(root.resolve("fixtures"));Files.writeString(root.resolve("fixtures/payload.json"),"{\"id\":\"public\"}");
        var lease=Json.MAPPER.createObjectNode().put("version","reviewed-kafka-lease.v1").put("owner_run_id",owner).put("nonce",owner).put("endpoint","127.0.0.1:19092").put("topic",topic).put("consumer_group","sit."+owner+".group").put("key","run."+owner).put("payload_sha256",LocalFiles.sha256(root.resolve("fixtures/payload.json"))).put("runtime_version","3.9.2");
        for(String f:List.of("image_child_sha256","image_config_sha256","advertised_listener_sha256"))lease.put(f,"c".repeat(64));lease.put("broker_container_id","d".repeat(64)).put("network_mode","none").put("client_network_container_id","d".repeat(64)).put("advertised_listener","127.0.0.1:19092").put("controller_listener","127.0.0.1:19093").put("partitions",1).put("initial_offset",0).put("issued_at",started.minusSeconds(1).toString()).put("expires_at",started.plusSeconds(300).toString());
        Json.MAPPER.writeValue(root.resolve("fixtures/lease.json").toFile(),lease);byte[] raw=Files.readAllBytes(root.resolve("fixtures/lease.json"));String pin=LocalFiles.sha256(raw);
        var provenance=(ObjectNode)Json.read(run.resolve("product-test/reviewed-handoff.json"));provenance.set("fixture_lease",lease);provenance.put("fixture_lease_sha256",pin).put("fixture_lease_base64",java.util.Base64.getEncoder().encodeToString(raw));var input=(ObjectNode)Json.read(Path.of(request.reviewedHandoff()));var files=input.putArray("reviewed_files");files.addObject().put("path","fixtures/lease.json").put("role","fixture").put("sha256",pin);files.addObject().put("path","fixtures/payload.json").put("role","fixture").put("sha256",LocalFiles.sha256(root.resolve("fixtures/payload.json")));provenance.set("reviewed_files",files.deepCopy());Json.MAPPER.writeValue(Path.of(request.reviewedHandoff()).toFile(),input);var value=(ObjectNode)Json.MAPPER.valueToTree(request);value.put("expected_handoff_sha256",LocalFiles.sha256(Path.of(request.reviewedHandoff())));request=Json.MAPPER.treeToValue(value,ProductTestRequest.class);provenance.put("handoff_sha256",request.expectedHandoffSha256());Json.MAPPER.writeValue(run.resolve("product-test/reviewed-handoff.json").toFile(),provenance);
        var proof=Json.MAPPER.createObjectNode();for(String f:List.of("owner_run_id","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode"))proof.set(f,lease.get(f));proof.put("version","reviewed-kafka-publish.v1").put("evidence_type","kafka_event").put("provider_type","kafka").put("run_id",result.path("run_id").asText()).put("batch_id","BATCH-1").put("step_id","publish").put("provider_id","todo_api").put("operation","kafka_publish").put("fixture_lease_sha256",pin).put("client_version","3.9.2").put("attempted",true).put("acknowledged",true).put("cleanup_confirmed",true).put("status","ACK").put("partition",0).put("offset",0).put("client_started_at",result.path("start_time").asText()).put("client_finished_at",result.path("end_time").asText());
        Path path=run.resolve("provider-evidence/kafka/reviewed-publish.json");Files.createDirectories(path.getParent());Json.MAPPER.writeValue(path.toFile(),proof);result.withArray("evidence_refs").add("provider-evidence/kafka/reviewed-publish.json");Files.writeString(run.resolve("evidence_index.yaml"),"  - run_id: "+result.path("run_id").asText()+"\n    batch_id: BATCH-1\n    file_path: provider-evidence/kafka/reviewed-publish.json\n",java.nio.file.StandardOpenOption.APPEND);save();return pin;
    }

    private String grpcFixture() throws Exception { return grpcFixture("echo.v1"); }
    private String grpcFixture(String profile) throws Exception {
        nativeFixture(); selectNativeContract("grpc_client.v0.3");
        ((ObjectNode)result.path("provider_results").get(0)).put("provider_type","grpc_client").put("provider_contract","grpc_client.v0.3");
        ((ObjectNode)result.path("step_results").get(0)).put("operation","unary_call");setOperationMetadata("unary_call");
        var lease=Json.MAPPER.createObjectNode().put("version","reviewed-grpc-lease.v1").put("profile",profile).put("endpoint","grpc://127.0.0.1:12345").put("owner_run_id","a".repeat(32));
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
        Json.MAPPER.writeValue(proofPath.toFile(),Map.of("fixture_lease_sha256",pin,"owner_run_id","a".repeat(32),"endpoint","grpc://127.0.0.1:12345","rpc_attempted",true,"cleanup_confirmed",true,"status","OK","service",Map.of("echo.v1","swarm.producttest.EchoService","greeter.v1","helloworld.Greeter","routeguide.getfeature.v1","routeguide.RouteGuide").get(profile),"method",Map.of("echo.v1","Echo","greeter.v1","SayHello","routeguide.getfeature.v1","GetFeature").get(profile)));
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
    @ParameterizedTest @ValueSource(strings={"greeter.v1","routeguide.getfeature.v1"})
    void additionalFiniteProfilesKeepIndependentRawLeaseAndCanonicalVerdict(String profile) throws Exception {
        String pin=grpcFixture(profile);
        assertThat(CanonicalEvidence.inspect(request,root,stdout,started,pin).verdict()).isEqualTo("passed");
    }
    @ParameterizedTest @ValueSource(strings={"service","stream_method","owner","attempt","cleanup"})
    void routeGuideProofStillRequiresExactFiniteTupleAndOwnership(String mutation) throws Exception {
        String pin=grpcFixture("routeguide.getfeature.v1");
        Path path=run.resolve("provider-evidence/grpc-client/reviewed-unary.json");
        var proof=(ObjectNode)Json.read(path);
        switch(mutation) {
            case "service" -> proof.put("service","helloworld.Greeter");
            case "stream_method" -> proof.put("method","ListFeatures");
            case "owner" -> proof.put("owner_run_id","0".repeat(32));
            case "attempt" -> proof.put("rpc_attempted",false);
            case "cleanup" -> proof.put("cleanup_confirmed",false);
            default -> throw new AssertionError(mutation);
        }
        Json.MAPPER.writeValue(path.toFile(),proof);
        assertThatThrownBy(()->CanonicalEvidence.inspect(request,root,stdout,started,pin)).isInstanceOf(IllegalArgumentException.class);
    }

}
