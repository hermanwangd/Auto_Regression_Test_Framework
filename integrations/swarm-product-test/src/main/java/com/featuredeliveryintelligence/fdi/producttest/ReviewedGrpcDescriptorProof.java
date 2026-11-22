package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Historical proof checker. No live process, filesystem, descriptor compiler or network access. */
public final class ReviewedGrpcDescriptorProof {
    public static final String PATH="provider-evidence/grpc-descriptor/admission.json";
    public static final String DIAGNOSTIC="gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method.";
    private static final Map<String,String> PINS=Map.of(
            "fixtures/service.proto","9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21",
            "fixtures/service.desc.b64","81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8",
            "fixtures/request.json","1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471");
    private static final Set<String> FIELDS=Set.of("schema","evidence_type","provider_type","provider_id","operation","run_id","test_case_id","step_id",
            "source_revision","source_sha256","descriptor_sha256","request_sha256","service","method","input_type","output_type",
            "client_streaming","server_streaming","status","operation_status","failure_code","diagnostic","rpc_attempted","channel_created","cleanup_confirmed","descriptor_generation","compiler");
    private ReviewedGrpcDescriptorProof() {}
    public static void validate(JsonNode proof,JsonNode result,JsonNode handoff) {
        require(proof.isObject(),"proof must be an object");var fields=new HashSet<String>();proof.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(FIELDS),"proof fields differ");
        for(var entry:Map.of("schema","reviewed-grpc-descriptor-rejection.v1","evidence_type","grpc_request_response",
                "provider_type","grpc_descriptor_admission","operation","unary_call","service","service.Test","method","GetThingVersions",
                "input_type","service.GetThingRequest","output_type","service.GetThingResponse","source_revision","a3f7fd0c1fd788871c460088446099700f6679e4").entrySet())
            require(entry.getValue().equals(text(proof,entry.getKey())),"wrong "+entry.getKey());
        require(PINS.get("fixtures/service.proto").equals(text(proof,"source_sha256"))
                && PINS.get("fixtures/request.json").equals(text(proof,"request_sha256"))
                && "e2a26040ccdbb5edf9c250074ab3fdb5e4188cdd8a868e1752a0bd4c6165ad0f".equals(text(proof,"descriptor_sha256")),"wrong input pins");
        for(String name:Set.of("client_streaming","rpc_attempted","channel_created"))require(proof.path(name).isBoolean()&&!proof.path(name).booleanValue(),"nonzero "+name);
        for(String name:Set.of("server_streaming","cleanup_confirmed"))require(proof.path(name).isBoolean()&&proof.path(name).booleanValue(),"missing "+name);
        require("UNKNOWN".equals(text(proof,"status"))&&"failed".equals(text(proof,"operation_status"))
                &&"OPERATION_FAILED".equals(text(proof,"failure_code"))&&DIAGNOSTIC.equals(text(proof,"diagnostic")),"not the exact streaming rejection");
        require("reviewed_descriptor_assembly".equals(text(proof,"descriptor_generation"))&&proof.path("compiler").isNull(),"wrong descriptor provenance");
        require("failed".equals(text(result,"status"))&&text(result,"run_id").equals(text(proof,"run_id")),"wrong run/outcome");
        handoff.fieldNames().forEachRemaining(field->require(!field.startsWith("fixture_lease"),"lease claim forbidden"));
        require("not_used".equals(text(handoff,"knowledge_mode"))
                &&"a3f7fd0c1fd788871c460088446099700f6679e4".equals(handoff.path("repository_revisions").path("python-betterproto").asText()),"source revision is not bound");
        for(var pin:PINS.entrySet()) {
            int count=0;for(var file:handoff.path("reviewed_files"))if(pin.getKey().equals(file.path("path").asText())) {
                require("fixture".equals(file.path("role").asText())&&pin.getValue().equals(file.path("sha256").asText()),"reviewed fixture pin mismatch");count++;
            } require(count==1,"reviewed fixture missing or ambiguous");
        }
        for(var file:handoff.path("reviewed_files"))require(!"fixtures/lease.json".equals(file.path("path").asText()),"lease file forbidden");
        require(result.path("step_results").isArray()&&result.path("step_results").size()==1
                &&result.path("provider_results").isArray()&&result.path("provider_results").size()==1,"one actual provider operation is required");
        var step=result.path("step_results").get(0);var provider=result.path("provider_results").get(0);
        require(text(proof,"step_id").equals(text(step,"id"))&&text(proof,"test_case_id").equals(text(step,"test_case_id"))
                &&text(proof,"provider_id").equals(text(step,"target"))&&"failed".equals(text(step,"status"))
                &&"OPERATION_FAILED".equals(text(step,"failure_code"))&&"UNKNOWN".equals(step.path("outputs").path("response.status").asText()),"step identity/outcome differs");
        require(text(proof,"provider_id").equals(text(provider,"target"))&&"failed".equals(text(provider,"status"))
                &&"grpc_descriptor_admission.v0.3".equals(text(provider,"provider_contract"))
                &&"grpc_descriptor_admission".equals(text(provider,"provider_type")),"provider identity differs");
        require("OPERATION_FAILED".equals(result.path("failure").path("code").asText())
                &&DIAGNOSTIC.equals(result.path("failure").path("reason").asText()),"canonical diagnostic differs");
    }
    private static String text(JsonNode node,String key){require(node.path(key).isTextual()&&!node.path(key).asText().isBlank(),"missing "+key);return node.path(key).asText();}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalArgumentException("reviewed_descriptor_proof: "+reason);}
}
