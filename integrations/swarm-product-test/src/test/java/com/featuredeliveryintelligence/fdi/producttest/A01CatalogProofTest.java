package com.featuredeliveryintelligence.fdi.producttest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
/** Synthetic historical contract checks only: no process, server or network. */
class A01CatalogProofTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode fixture() throws Exception {
        try(var in=getClass().getResourceAsStream("/a01/catalog-proof-contract.json")){assertNotNull(in);return (ObjectNode)json.readTree(in);}
    }
    private void check(ObjectNode f){ReviewedA01CatalogProof.validate(f.path("proof"),f.path("result"),f.path("handoff"));}
    @Test void acceptsExactHistoricalProofAndLeaseWithoutLiveProbe() throws Exception {
        var f=fixture();assertDoesNotThrow(()->check(f));
        assertDoesNotThrow(()->ReviewedA01CatalogProof.validateLease(f.path("lease"),f.path("source"),f.path("runtime"),f.path("lease").path("source_manifest_sha256").asText(),f.path("lease").path("runtime_manifest_sha256").asText(),f.path("lease").path("request_sha256").asText()));
    }
    @ParameterizedTest @ValueSource(strings={"run_id","batch_id","test_case_id","step_id","provider_id","owner_run_id","endpoint","source_manifest_sha256","runtime_manifest_sha256","build_manifest_sha256","descriptor_sha256","request_sha256","data_sha256","service","method","body"})
    void rejectsWrongProofJoin(String field) throws Exception {var f=fixture();((ObjectNode)f.path("proof")).put(field,"wrong");assertThrows(IllegalArgumentException.class,()->check(f));}
    @ParameterizedTest @ValueSource(strings={"cleanup_confirmed","rpc_attempted","channel_created","masking_applied"})
    void rejectsFalsePositiveFlag(String field)throws Exception {var f=fixture();((ObjectNode)f.path("proof")).put(field,false);assertThrows(IllegalArgumentException.class,()->check(f));}
    @Test void rejectsProviderFreePreflightAsApplicationEvidence()throws Exception {var f=fixture();((ObjectNode)f.path("result")).putArray("provider_results");assertThrows(IllegalArgumentException.class,()->check(f));}
    @Test void rejectsWrongChildObservation()throws Exception {var f=fixture();((ObjectNode)f.path("proof").path("child")).put("pid",125);assertThrows(IllegalArgumentException.class,()->check(f));}
    @Test void rejectsWrongListenerObservation()throws Exception {var f=fixture();((ObjectNode)f.path("proof").path("listener")).put("inode",70001);assertThrows(IllegalArgumentException.class,()->check(f));}
    private ObjectNode absentFixture()throws Exception {
        var f=fixture();var proof=(ObjectNode)f.path("proof");var lease=(ObjectNode)f.path("handoff").path("fixture_lease");
        String requestSha="ac6b8a0d364523bd13a27b5fdb894ede0b3fec3f6cce891b8ccaaae3f2424dbf";
        proof.put("request_sha256",requestSha);lease.put("request_sha256",requestSha);proof.put("request","{\"id\":\"A01-NO-SUCH-PRODUCT\"}\n");
        // This synthetic fixture re-pins its changed frozen lease, never an operational allowance.
        String leaseSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(lease)));
        proof.put("fixture_lease_sha256",leaseSha);((ObjectNode)f.path("handoff")).put("fixture_lease_sha256",leaseSha);
        for(var file:f.path("handoff").path("reviewed_files")){if(file.path("path").asText().equals("fixtures/request.json"))((ObjectNode)file).put("sha256",requestSha);if(file.path("path").asText().equals("fixtures/lease.json"))((ObjectNode)file).put("sha256",leaseSha);}
        String error="NOT_FOUND: no product with ID A01-NO-SUCH-PRODUCT",diagnostic="Reviewed gRPC unary failed: NOT_FOUND "+error;
        proof.put("status","NOT_FOUND");proof.put("body","");proof.put("error",error);proof.put("operation_status","failed");proof.put("failure_code","OPERATION_FAILED");proof.put("diagnostic",diagnostic);
        var result=(ObjectNode)f.path("result");result.put("status","failed");((ObjectNode)result.path("failure")).put("code","OPERATION_FAILED").put("reason",diagnostic);
        var step=(ObjectNode)result.path("step_results").get(0);step.put("status","failed");step.put("failure_code","OPERATION_FAILED");((ObjectNode)step.path("outputs")).put("response.status","NOT_FOUND").remove("response.message");((ObjectNode)result.path("provider_results").get(0)).put("status","failed");
        var resolved=(ObjectNode)result.path("provider_results").get(0).path("resolved_operation_results").get(0);
        resolved.put("status","failed");((ObjectNode)result.path("provider_results").get(0)).put("failure_code","OPERATION_FAILED");resolved.set("outputs",step.path("outputs").deepCopy());
        return f;
    }
    @Test void keepsUnknownProductAsFailedCanonicalOperation()throws Exception {var f=absentFixture();assertDoesNotThrow(()->check(f));assertEquals("failed",f.path("result").path("status").asText());}
    @ParameterizedTest @ValueSource(strings={"rpc_attempted","channel_created","cleanup_confirmed"})
    void rejectsNotFoundWithoutActualRpc(String flag)throws Exception {var f=absentFixture();assertDoesNotThrow(()->check(f));((ObjectNode)f.path("proof")).put(flag,false);assertThrows(IllegalArgumentException.class,()->check(f));}
    @ParameterizedTest @ValueSource(strings={"rpc_started_at","rpc_finished_at"})
    void rejectsNotFoundWithoutRpcTime(String field)throws Exception {var f=absentFixture();assertDoesNotThrow(()->check(f));((ObjectNode)f.path("proof")).put(field,"");assertThrows(RuntimeException.class,()->check(f));}
    @Test void rejectsNotFoundWithAllRpcFactsRemoved()throws Exception {var f=absentFixture();var p=(ObjectNode)f.path("proof");p.put("rpc_attempted",false).put("channel_created",false).put("cleanup_confirmed",false).put("rpc_started_at","").put("rpc_finished_at","");assertThrows(IllegalArgumentException.class,()->check(f));}
    @ParameterizedTest @ValueSource(strings={"status","failure_code","response.status","response.message","response.duration_ms"})
    void rejectsNotFoundResolvedOperationMutation(String field)throws Exception {
        var f=absentFixture();assertDoesNotThrow(()->check(f));var op=(ObjectNode)f.path("result").path("provider_results").get(0).path("resolved_operation_results").get(0);
        if(field.startsWith("response."))((ObjectNode)op.path("outputs")).put(field,"wrong");else op.put(field,"wrong");
        assertThrows(IllegalArgumentException.class,()->check(f));
    }
    @Test void rejectsWrongLaunchIdentityJoin()throws Exception {var f=fixture();((ObjectNode)f.path("proof")).put("owner_launch_sha256","0".repeat(64));assertThrows(IllegalArgumentException.class,()->check(f));}
    @Test void rejectsUnboundedLaunchClasspath()throws Exception {var f=fixture();var l=(ObjectNode)f.path("lease").path("owner_launch");((ObjectNode)l.path("classpath").get(0)).put("bytes",33554433);assertThrows(IllegalArgumentException.class,()->ReviewedA01CatalogProof.validateLaunch(l));}
}
