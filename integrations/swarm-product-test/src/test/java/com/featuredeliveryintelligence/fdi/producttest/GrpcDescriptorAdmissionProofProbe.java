package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;

/** Runs the actual Module proof consumer on retained real Framework evidence. */
public final class GrpcDescriptorAdmissionProofProbe {
    public static void main(String[] args)throws Exception {
        Path result=Path.of(args[0]);
        int count=check(Json.MAPPER.readTree(result.getParent().resolve(ReviewedGrpcDescriptorProof.PATH).toFile()),
                Json.MAPPER.readTree(result.toFile()),Json.MAPPER.readTree(result.getParent().resolve("product-test/reviewed-handoff.json").toFile()));
        System.out.println("Module descriptor proof checks passed: "+count);
    }
    static int check(JsonNode proof,JsonNode result,JsonNode handoff) {
        int count=1;ReviewedGrpcDescriptorProof.validate(proof,result,handoff);
        for(String field:List.of("schema","provider_type","operation","run_id","provider_id","test_case_id","step_id","source_revision","source_sha256","descriptor_sha256","request_sha256","service","method","input_type","output_type","status","operation_status","failure_code","diagnostic","descriptor_generation")) {
            ObjectNode copy=proof.deepCopy();copy.put(field,"wrong");rejects(()->ReviewedGrpcDescriptorProof.validate(copy,result,handoff));count++;
        }
        for(String field:List.of("rpc_attempted","channel_created","client_streaming","server_streaming","cleanup_confirmed")) {
            ObjectNode copy=proof.deepCopy();copy.put(field,!proof.path(field).booleanValue());rejects(()->ReviewedGrpcDescriptorProof.validate(copy,result,handoff));count++;
        }
        ObjectNode absent=proof.deepCopy();absent.remove("channel_created");rejects(()->ReviewedGrpcDescriptorProof.validate(absent,result,handoff));count++;
        ObjectNode compiler=proof.deepCopy();compiler.put("compiler","protoc");rejects(()->ReviewedGrpcDescriptorProof.validate(compiler,result,handoff));count++;
        ObjectNode passed=result.deepCopy();passed.put("status","passed");rejects(()->ReviewedGrpcDescriptorProof.validate(proof,passed,handoff));count++;
        ObjectNode lease=handoff.deepCopy();lease.putObject("fixture_lease");rejects(()->ReviewedGrpcDescriptorProof.validate(proof,result,lease));count++;
        ObjectNode changed=handoff.deepCopy();for(var file:changed.path("reviewed_files"))if("fixtures/service.proto".equals(file.path("path").asText()))((ObjectNode)file).put("sha256","0".repeat(64));rejects(()->ReviewedGrpcDescriptorProof.validate(proof,result,changed));count++;
        ObjectNode rawLease=handoff.deepCopy();rawLease.put("fixture_lease_raw_base64","e30=");rejects(()->ReviewedGrpcDescriptorProof.validate(proof,result,rawLease));count++;
        return count;
    }
    static void rejects(Runnable check){try{check.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Corrupt descriptor proof accepted");}
}
