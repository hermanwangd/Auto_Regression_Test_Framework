package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/** Frozen semantic fixtures only: no process, socket, current-clock lease admission or SUT execution. */
class ReviewedGrpcProofIdentityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String PROOF="provider-evidence/grpc-client/reviewed-unary.json", HANDOFF="product-test/reviewed-handoff.json", POLICY="product-test/policy.json";
    static final String PIN="d".repeat(64), OWNER="a".repeat(32), ENDPOINT="grpc://127.0.0.1:12345";
    @TempDir Path root;
    ObjectNode result,provenance,policy,proof,index;
    Path resultFile;
    static ObjectNode obj(Object... fields) {ObjectNode n=JSON.createObjectNode();for(int i=0;i<fields.length;i+=2)n.set((String)fields[i],JSON.valueToTree(fields[i+1]));return n;}
    @BeforeEach void validHistoricalFixture() throws Exception {
        resultFile=root.resolve("result.json");
        result=obj("framework_version","0.3.2","dsl_version","v0.3","result_contract_version","v0.3","suite_id","HISTORICAL-GRPC","batch_id","BATCH-1","run_id","RUN-1","test_case_id","TC-1","test_count",1,"profile","local","environment","local","status","passed","start_time","2000-01-01T00:00:01Z","end_time","2000-01-01T00:00:02Z","duration_ms",1000,"timestamps",Map.of("started_at","2000-01-01T00:00:01Z","finished_at","2000-01-01T00:00:02Z"),"completion_status","complete","termination_reason",null,"suite_summary_ref","suite_summary.json","plan_digest","c".repeat(64),"labels",Map.of("evidence_classification","framework_verification_only","downstream_release_evidence",false),"failure",Map.of());
        result.putArray("test_results").addObject().put("test_case_id","TC-1").put("profile","local").put("status","passed");
        ObjectNode provider=result.putArray("provider_results").addObject().put("provider_id","grpc").put("target","grpc").put("provider_type","grpc_client").put("provider_contract","grpc_client.v0.3").put("runtime_mode","native").put("profile","local").put("operation","unary_call").put("status","passed").put("cleanup_status","not_required").put("release_evidence_eligible",false);
        provider.putArray("operations").add("unary_call");provider.putArray("resolved_operation_results").addObject().put("operation","unary_call").put("status","passed");provider.putArray("evidence_refs").add(PROOF);
        result.putArray("provider_summary").addObject().put("provider_id","grpc").put("provider_type","grpc_client").put("target","grpc").put("runtime_mode","native");
        result.putArray("step_results").addObject().put("target","grpc").put("phase","execute").put("operation","unary_call").put("status","passed");result.set("steps",result.path("step_results").deepCopy());result.putArray("verify_results");
        result.put("evidence_index_ref","evidence_index.yaml");result.putArray("evidence_refs").add(PROOF).add(HANDOFF).add(POLICY).add("execution.log").add("batch.json");result.putArray("provider_evidence_refs").add(PROOF);
        provenance=obj("contract_version","product-test-reviewed-handoff.v1","suite_id","HISTORICAL-GRPC","profile","local","batch_id","BATCH-1","run_id","RUN-1","plan_digest","c".repeat(64),"handoff_sha256","b".repeat(64),"knowledge_mode","not_used","test_level","integration","test_type","regression","environment_stage","local","workspace_ref","fixture-workspace","project_ref","fixture-project","repository_revisions",Map.of("framework","fixture-revision"),"provider_modes",Map.of("grpc","real"),"observed_provider_modes",Map.of("grpc","real"),"dependency_modes",Map.of(),"observed_dependency_modes",Map.of(),"evidence_classification","framework_verification_only","downstream_release_evidence",false,"fixture_lease_sha256",PIN);
        provenance.set("fixture_lease",obj("version","reviewed-grpc-lease.v1","profile","echo.v1","owner_run_id",OWNER,"endpoint",ENDPOINT,"pid",99999999,"process_start_instant","1999-12-31T23:59:59Z","issued_at","2000-01-01T00:00:00Z","expires_at","2000-01-01T00:05:00Z","request_sha256","e".repeat(64),"descriptor_sha256","f".repeat(64)));
        provenance.putArray("reviewed_files").addObject().put("path","fixtures/lease.json").put("role","fixture").put("sha256",PIN);
        provenance.putArray("reviewed_contracts").addObject().put("provider_contract","grpc_client.v0.3").put("path","contracts/grpc.yaml").put("sha256","e".repeat(64));
        policy=obj("suite_id","HISTORICAL-GRPC","profile","local","batch_id","BATCH-1","run_id","RUN-1","status","passed");
        proof=obj("evidence_type","grpc_request_response","provider_type","grpc_client","provider_id","grpc","service","swarm.producttest.EchoService","method","Echo","fixture_lease_sha256",PIN,"owner_run_id",OWNER,"endpoint",ENDPOINT,"rpc_attempted",true,"cleanup_confirmed",true,"status","OK","body","{\"message\":\"ok\"}","request","{\"message\":\"hello\"}","masking_applied",true);
        index=obj("evidence_index_version","v0.3","suite_id","HISTORICAL-GRPC","profile","local","batch_id","BATCH-1","run_id","RUN-1","test_case_id","TC-1");index.putArray("entries");
        entry(PROOF,"grpc_request_response",true);entry(HANDOFF,"reviewed_handoff",false);entry(POLICY,"product_test_policy",false);entry("execution.log","execution_log",false);entry("batch.json","batch_summary",false);
        write("suite_summary.json",obj());write("execution.log",obj());write("batch.json",obj());save();
        assertThat(validate().valid()).as("control fixture must pass before any mutation: %s",validate().findings()).isTrue();
    }
    void entry(String path,String type,boolean provider) {
        ObjectNode e=index.withArray("entries").addObject().put("evidence_id","id-"+index.path("entries").size()).put("file_path",path).put("evidence_type",type).put("produced_by",provider?"provider":"framework").put("test_case_id","TC-1").put("run_id","RUN-1").put("batch_id","BATCH-1").put("content_type","application/json").put("status","passed").put("created_at","2000-01-01T00:00:02Z").put("masking_applied",true).put("linked_result_field","evidence_refs");
        if(provider)e.put("provider_type","grpc_client").put("provider_id","grpc");
    }
    void write(String relative,JsonNode node) throws Exception {Path p=root.resolve(relative);Files.createDirectories(p.getParent());JSON.writeValue(p.toFile(),node);}
    void save() throws Exception {
        write(HANDOFF,provenance);write(POLICY,policy);write(PROOF,proof);
        ObjectNode binding=result.putObject("product_test");for(String field:List.of("contract_version","handoff_sha256","knowledge_mode","test_level","test_type","environment_stage","workspace_ref","project_ref","repository_revisions","provider_modes","dependency_modes"))binding.set(field,provenance.path(field).deepCopy());
        binding.put("provenance_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(HANDOFF)))));
        write("result.json",result);Files.writeString(root.resolve("evidence_index.yaml"),new Yaml().dump(JSON.convertValue(index,Map.class)));
    }
    EvidenceHardeningService.EvidenceValidationResult validate(){return new EvidenceHardeningService().validateResult(resultFile);}
    static Stream<String> mutations(){return Stream.of("owner_zero","owner_other","owner_missing","owner_number","endpoint","endpoint_missing","lease_digest","lease_digest_missing","service","service_missing","method","method_missing","profile","profile_switch","profile_missing","version","lease_missing","lease_pin_missing","closure_missing","closure_duplicate","closure_role","closure_digest","proof_missing","proof_unindexed","proof_duplicate","proof_unreferenced","proof_wrong_type","proof_nonobject","proof_malformed","attempt_type","attempt_missing","cleanup_type","cleanup_missing","attempt_false","cleanup_false","status_nonok");}
    @ParameterizedTest @MethodSource("mutations") void rejectsBrokenFrozenProofJoin(String mutation) throws Exception {
        switch(mutation){
            case "owner_zero"->proof.put("owner_run_id","0".repeat(32));case "owner_other"->proof.put("owner_run_id","f".repeat(32));case "owner_missing"->proof.remove("owner_run_id");case "owner_number"->proof.put("owner_run_id",123);
            case "endpoint"->proof.put("endpoint","grpc://127.0.0.1:1");case "endpoint_missing"->proof.remove("endpoint");case "lease_digest"->proof.put("fixture_lease_sha256","1".repeat(64));case "lease_digest_missing"->proof.remove("fixture_lease_sha256");
            case "service"->proof.put("service","wrong.Service");case "service_missing"->proof.remove("service");case "method"->proof.put("method","Wrong");case "method_missing"->proof.remove("method");
            case "profile"->((ObjectNode)provenance.path("fixture_lease")).put("profile","other.v1");case "profile_switch"->((ObjectNode)provenance.path("fixture_lease")).put("profile","greeter.v1");case "profile_missing"->((ObjectNode)provenance.path("fixture_lease")).remove("profile");case "version"->((ObjectNode)provenance.path("fixture_lease")).put("version","other.v1");case "lease_missing"->provenance.remove("fixture_lease");case "lease_pin_missing"->provenance.remove("fixture_lease_sha256");
            case "closure_missing"->provenance.putArray("reviewed_files");case "closure_duplicate"->provenance.withArray("reviewed_files").add(provenance.path("reviewed_files").get(0).deepCopy());case "closure_role"->((ObjectNode)provenance.path("reviewed_files").get(0)).put("role","test");case "closure_digest"->((ObjectNode)provenance.path("reviewed_files").get(0)).put("sha256","1".repeat(64));
            case "proof_unindexed"->index.withArray("entries").remove(0);case "proof_duplicate"->{ObjectNode duplicate=(ObjectNode)index.path("entries").get(0).deepCopy();duplicate.put("evidence_id","duplicate-path");index.withArray("entries").add(duplicate);}
            case "proof_unreferenced"->{result.withArray("evidence_refs").remove(0);result.putArray("provider_evidence_refs");((ObjectNode)result.path("provider_results").get(0)).putArray("evidence_refs");}
            case "proof_wrong_type"->((ObjectNode)index.path("entries").get(0)).put("evidence_type","execution_log");
            case "attempt_type"->proof.put("rpc_attempted","true");case "attempt_missing"->proof.remove("rpc_attempted");case "cleanup_type"->proof.put("cleanup_confirmed","true");case "cleanup_missing"->proof.remove("cleanup_confirmed");case "attempt_false"->proof.put("rpc_attempted",false);case "cleanup_false"->proof.put("cleanup_confirmed",false);case "status_nonok"->proof.put("status","UNAVAILABLE");
            default->{}
        }
        save();if(mutation.equals("proof_missing"))Files.delete(root.resolve(PROOF));if(mutation.equals("proof_nonobject"))Files.writeString(root.resolve(PROOF),"[]");if(mutation.equals("proof_malformed"))Files.writeString(root.resolve(PROOF),"{bad");
        assertThat(validate().valid()).as("mutation %s must invalidate evidence",mutation).isFalse();
    }
    @Test void historicalExpiredLeaseRequiresNoLiveOwnerOrCurrentClock() {assertThat(validate().valid()).isTrue();assertThat(root.resolve("fixtures/lease.json")).doesNotExist();}
    @Test void matchingZeroNonceIsNotRejectedAsASpecialValue() throws Exception {((ObjectNode)provenance.path("fixture_lease")).put("owner_run_id","0".repeat(32));proof.put("owner_run_id","0".repeat(32));save();assertThat(validate().valid()).isTrue();}
    @ParameterizedTest @ValueSource(strings={"echo.v1","greeter.v1"}) void finiteProfilesRemainValid(String profile) throws Exception {((ObjectNode)provenance.path("fixture_lease")).put("profile",profile);proof.put("service",profile.equals("echo.v1")?"swarm.producttest.EchoService":"helloworld.Greeter").put("method",profile.equals("echo.v1")?"Echo":"SayHello");save();assertThat(validate().valid()).isTrue();}
    void failed(String status,boolean attempt,boolean cleanup) throws Exception {
        result.put("status","failed");((ObjectNode)result.path("test_results").get(0)).put("status","failed");((ObjectNode)result.path("provider_results").get(0)).put("status","failed").put("failure_code","OPERATION_FAILED");proof.put("status",status).put("rpc_attempted",attempt).put("cleanup_confirmed",cleanup);ObjectNode e=(ObjectNode)index.path("entries").get(0);e.put("status","failed").put("failure_code","OPERATION_FAILED");save();
    }
    @ParameterizedTest @ValueSource(strings={"OK","UNAVAILABLE","DEADLINE_EXCEEDED","CLEANUP_UNCONFIRMED"}) void failedEvidenceKeepsTypedFlagsAndBusinessFailure(String status) throws Exception {failed(status,false,false);assertThat(validate().valid()).as(validate().findings().toString()).isTrue();assertThat(result.path("status").asText()).isEqualTo("failed");}
    @ParameterizedTest @ValueSource(strings={"owner_run_id","endpoint","fixture_lease_sha256"})
    void failedCanonicalStillRejectsWrongIdentity(String field) throws Exception {
        failed("UNAVAILABLE",true,true);proof.put(field,field.equals("owner_run_id")?"0".repeat(32):field.equals("endpoint")?"grpc://127.0.0.1:1":"1".repeat(64));save();assertThat(validate().valid()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"ascii","escaped"}) void legalProviderPayloadIsStillReportable(String encoding) throws Exception {
        proof.put("body",encoding.equals("ascii")?"x".repeat(768 * 1024):"\"".repeat(500_000));save();assertThat(validate().valid()).as(validate().findings().toString()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","over_string","over_bytes"}) void strictProofParserRejectsUnsafeDocuments(String kind) throws Exception {
        String json=JSON.writeValueAsString(proof);
        String invalid=switch(kind){case "duplicate"->json.substring(0,json.length()-1)+",\"owner_run_id\":\""+OWNER+"\"}";case "trailing"->json+" {}";case "over_string"->JSON.writeValueAsString(proof.put("body","x".repeat(1024*1024+1)));default->json+" ".repeat(2*1024*1024);};
        Files.writeString(root.resolve(PROOF),invalid);assertThat(validate().valid()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"failed","blocked"}) void zeroProviderPreflightCanOmitProof(String status) throws Exception {
        result.put("status",status);((ObjectNode)result.path("test_results").get(0)).put("status",status);result.putArray("provider_results");result.putArray("provider_summary");result.putArray("step_results");result.putArray("steps");result.withArray("evidence_refs").remove(0);result.putArray("provider_evidence_refs");index.withArray("entries").remove(0);provenance.putObject("observed_provider_modes");policy.put("status","blocked");((ObjectNode)index.path("entries").get(1)).put("status","blocked");save();Files.delete(root.resolve(PROOF));assertThat(validate().valid()).as(validate().findings().toString()).isTrue();
    }
    @Test void changingProvenanceWithoutCanonicalPinStillFails() throws Exception {((ObjectNode)provenance.path("fixture_lease")).put("endpoint","grpc://127.0.0.1:1");write(HANDOFF,provenance);assertThat(validate().valid()).isFalse();}
    void routeGuideFixture() throws Exception {
        ((ObjectNode)provenance.path("fixture_lease")).put("profile","routeguide.getfeature.v1");
        proof.put("service","routeguide.RouteGuide").put("method","GetFeature")
                .put("request","{\"latitude\":407838351,\"longitude\":-746143763}")
                .put("body","{\"name\":\"recorded contract fixture\",\"location\":{\"latitude\":407838351,\"longitude\":-746143763}}");
        save();
    }
    @Test void routeGuideFullFeatureProofIsValidatedHistoricallyWithoutOwnerReplay() throws Exception {
        routeGuideFixture();
        assertThat(validate().valid()).as(validate().findings().toString()).isTrue();
        assertThat(root.resolve("fixtures/lease.json")).doesNotExist();
    }
    @ParameterizedTest @ValueSource(strings={"service","stream_method","owner","attempt","cleanup"})
    void routeGuideProofCannotBorrowAnotherServiceMethodOrOwnership(String mutation) throws Exception {
        routeGuideFixture();
        switch(mutation) {
            case "service" -> proof.put("service","helloworld.Greeter");
            case "stream_method" -> proof.put("method","ListFeatures");
            case "owner" -> proof.put("owner_run_id","0".repeat(32));
            case "attempt" -> proof.put("rpc_attempted",false);
            case "cleanup" -> proof.put("cleanup_confirmed",false);
            default -> throw new AssertionError(mutation);
        }
        save();assertThat(validate().valid()).isFalse();
    }

}
