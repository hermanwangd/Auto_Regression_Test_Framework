package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;

/** Runs the actual Module proof consumer on retained real Framework evidence. */
public final class GrpcOwnedStreamingProofProbe {
    public static void main(String[] args)throws Exception {
        if(args.length==0){System.out.println("Module synthetic historical fixture checks passed: "+synthetic());return;}
        Path result=Path.of(args[0]);
        int count=check(Json.MAPPER.readTree(result.getParent().resolve(ReviewedGrpcStreamingRejectionProof.PATH).toFile()),
                Json.MAPPER.readTree(result.toFile()),Json.MAPPER.readTree(result.getParent().resolve("product-test/reviewed-handoff.json").toFile()));
        System.out.println("Module owned-streaming proof checks passed: "+count);
    }
    static int check(JsonNode proof,JsonNode result,JsonNode handoff)throws Exception {
        int count=1;ReviewedGrpcStreamingRejectionProof.validate(proof,result,handoff);
        for(String field:List.of("schema","provider_type","operation","run_id","provider_id","test_case_id","step_id","source_revision","source_sha256","descriptor_sha256","request_sha256","service","method","input_type","output_type","status","operation_status","failure_code","diagnostic","descriptor_generation","fixture_lease_sha256","owner_run_id","endpoint","lease_checked_at")) {
            ObjectNode copy=proof.deepCopy();copy.put(field,"wrong");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(copy,result,handoff));count++;
        }
        for(String field:List.of("rpc_attempted","channel_created","client_streaming","server_streaming","cleanup_confirmed","guard_rejection_observed")) {
            ObjectNode copy=proof.deepCopy();copy.put(field,!proof.path(field).booleanValue());rejects(()->ReviewedGrpcStreamingRejectionProof.validate(copy,result,handoff));count++;
        }
        ObjectNode absent=proof.deepCopy();absent.remove("channel_created");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(absent,result,handoff));count++;
        ObjectNode compiler=proof.deepCopy();compiler.put("compiler","protoc");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(compiler,result,handoff));count++;
        ObjectNode passed=result.deepCopy();passed.put("status","passed");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,passed,handoff));count++;
        ObjectNode lease=handoff.deepCopy();lease.putObject("fixture_lease");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,lease));count++;
        ObjectNode changed=handoff.deepCopy();for(var file:changed.path("reviewed_files"))if("fixtures/service.proto".equals(file.path("path").asText()))((ObjectNode)file).put("sha256","0".repeat(64));rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,changed));count++;
        ObjectNode rawLease=handoff.deepCopy();rawLease.put("fixture_lease_raw_base64","e30=");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,rawLease));count++;
        for(String field:List.of("profile","pid","process_start_instant","issued_at","expires_at","owner_run_id","endpoint","descriptor_sha256")) {
            ObjectNode copy=handoff.deepCopy();((ObjectNode)copy.path("fixture_lease")).put(field,"wrong");rejects(()->ReviewedGrpcStreamingRejectionProof.validate(proof,result,copy));count++;
        }
        String text=Json.MAPPER.writeValueAsString(proof);
        for(String invalid:List.of("{\"schema\":\"duplicate\","+text.substring(1),text+" {}")) {
            try {Json.MAPPER.readTree(invalid);throw new AssertionError("ambiguous proof JSON accepted");}catch(com.fasterxml.jackson.core.JsonProcessingException expected){count++;}
        }
        return count;
    }
    static int synthetic()throws Exception {
  var proof=Json.MAPPER.readTree("""
{"evidence_type":"grpc_request_response","provider_type":"grpc_client","provider_id":"betterproto","service":"service.Test","method":"GetThingVersions","fixture_lease_sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","owner_run_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","endpoint":"grpc://127.0.0.1:12345","rpc_attempted":false,"cleanup_confirmed":true,"request":"{\\"name\\":\\"my milkshakes\\"}\\n","status":"UNKNOWN","body":"","duration_ms":1,"error":"Unsupported streaming descriptor for gRPC unary_call; select a unary method.","masking_applied":true,"schema":"reviewed-grpc-owned-streaming-rejection.v1","operation":"unary_call","run_id":"RUN-unit-proof","test_case_id":"TC-unit-proof","step_id":"reject_streaming","source_revision":"a3f7fd0c1fd788871c460088446099700f6679e4","source_sha256":"9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21","descriptor_sha256":"e2a26040ccdbb5edf9c250074ab3fdb5e4188cdd8a868e1752a0bd4c6165ad0f","request_sha256":"1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471","input_type":"service.GetThingRequest","output_type":"service.GetThingResponse","client_streaming":false,"server_streaming":true,"operation_status":"failed","failure_code":"OPERATION_FAILED","diagnostic":"gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method.","guard_rejection_observed":true,"channel_created":false,"lease_checked_at":"2026-01-01T00:00:02Z","descriptor_generation":"reviewed_descriptor_assembly","compiler":null}
""");
  var result=Json.MAPPER.readTree("""
{"status":"failed","failure":{"code":"OPERATION_FAILED","classification":"PROVIDER_RUNTIME_ERROR","reason":"gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method.","owner_action":"Review provider evidence for step `reject_streaming`.","cleanup_failures":[]},"step_results":[{"id":"reject_streaming","test_case_id":"TC-unit-proof","target":"betterproto","status":"failed","failure_code":"OPERATION_FAILED","outputs":{"response.duration_ms":3,"response.status":"UNKNOWN"},"phase":"execute","operation":"unary_call"}],"provider_results":[{"target":"betterproto","status":"failed","provider_contract":"grpc_client.v0.3","provider_type":"grpc_client","operation":"unary_call"}],"run_id":"RUN-unit-proof","start_time":"2026-01-01T00:00:01Z","end_time":"2026-01-01T00:00:03Z"}
""");
  var handoff=Json.MAPPER.readTree("""
{"knowledge_mode":"not_used","repository_revisions":{"python-betterproto":"a3f7fd0c1fd788871c460088446099700f6679e4"},"fixture_lease":{"version":"reviewed-grpc-lease.v1","owner_run_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","issued_at":"2026-01-01T00:00:00Z","request_sha256":"1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471","descriptor_sha256":"81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8","expires_at":"2026-01-01T00:02:00Z","profile":"betterproto.getthingversions-reject.v1","process_start_instant":"2026-01-01T00:00:00Z","pid":1234,"endpoint":"grpc://127.0.0.1:12345"},"fixture_lease_sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","reviewed_files":[{"role":"fixture","path":"fixtures/service.proto","sha256":"9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21"},{"role":"fixture","path":"fixtures/service.desc.b64","sha256":"81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8"},{"role":"fixture","path":"fixtures/request.json","sha256":"1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471"},{"role":"fixture","path":"fixtures/lease.json","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}
""");
        return check(proof,result,handoff);
    }
    static void rejects(Runnable check){try{check.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Corrupt descriptor proof accepted");}
}
