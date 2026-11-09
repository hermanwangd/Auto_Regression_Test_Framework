package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Historical proof checker. No live process, filesystem, descriptor compiler or network access. */
public final class ReviewedGrpcStreamingRejectionProof {
    public static final String PATH="provider-evidence/grpc-client/reviewed-unary.json";
    public static final String DIAGNOSTIC="gRPC unary_call failed: Unsupported streaming descriptor for gRPC unary_call; select a unary method.";
    private static final Map<String,String> PINS=Map.of(
            "fixtures/service.proto","9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21",
            "fixtures/service.desc.b64","81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8",
            "fixtures/request.json","1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471");
    public static final String PROFILE="betterproto.getthingversions-reject.v1";
    private static final Set<String> FIELDS=Set.of("evidence_type","provider_type","provider_id","service","method","fixture_lease_sha256","owner_run_id","endpoint","rpc_attempted","cleanup_confirmed","request","status","body","duration_ms","error","masking_applied","schema","operation","run_id","test_case_id","step_id","source_revision","source_sha256","descriptor_sha256","request_sha256","input_type","output_type","client_streaming","server_streaming","operation_status","failure_code","diagnostic","guard_rejection_observed","channel_created","lease_checked_at","descriptor_generation","compiler");
    public static final String UNARY_PROFILE="betterproto.dothing-unary.v1";
    public static final String UNARY_BODY="{\"names\":[\"my milkshakes\"]}";
    private record Profile(String id,String method,String input,String output,boolean client,boolean server) {}
    private static final Map<String,Profile> PROFILES=Map.of(
        UNARY_PROFILE,new Profile(UNARY_PROFILE,"DoThing","service.DoThingRequest","service.DoThingResponse",false,false),
        "betterproto.domanythings-reject.v1",new Profile("betterproto.domanythings-reject.v1","DoManyThings","service.DoThingRequest","service.DoThingResponse",true,false),
        PROFILE,new Profile(PROFILE,"GetThingVersions","service.GetThingRequest","service.GetThingResponse",false,true),
        "betterproto.getdifferentthings-reject.v1",new Profile("betterproto.getdifferentthings-reject.v1","GetDifferentThings","service.GetThingRequest","service.GetThingResponse",true,true));
    public static boolean ownsProfile(String profile){return PROFILES.containsKey(profile);}
    private ReviewedGrpcStreamingRejectionProof() {}
    /** This entry is rejection-only. A unary/PASS proof must never be accepted here. */
    public static void validate(JsonNode proof,JsonNode result,JsonNode handoff) {
        Profile profile=profile(handoff);require(profile.client||profile.server,"unary is not a rejection proof");
        common(proof,result,handoff,profile,"reviewed-grpc-owned-streaming-rejection.v1");
        for(String name:Set.of("rpc_attempted","channel_created"))flag(proof,name,false);
        flag(proof,"guard_rejection_observed",true);
        require("UNKNOWN".equals(text(proof,"status"))&&"failed".equals(text(proof,"operation_status"))
                &&"OPERATION_FAILED".equals(text(proof,"failure_code"))&&DIAGNOSTIC.equals(text(proof,"diagnostic")),"not the exact streaming rejection");
        require(proof.path("body").isTextual()&&proof.path("body").textValue().isEmpty(),"rejection must have empty body");
        require(DIAGNOSTIC.substring("gRPC unary_call failed: ".length()).equals(text(proof,"error")),"wrong shared guard cause");
        var step=result.path("step_results").get(0);var provider=result.path("provider_results").get(0);
        require("failed".equals(text(result,"status"))&&"failed".equals(text(step,"status"))&&"failed".equals(text(provider,"status"))
                &&"OPERATION_FAILED".equals(text(step,"failure_code"))&&"UNKNOWN".equals(step.path("outputs").path("response.status").asText()),"rejection outcome differs");
        require("OPERATION_FAILED".equals(result.path("failure").path("code").asText())&&DIAGNOSTIC.equals(result.path("failure").path("reason").asText()),"canonical diagnostic differs");
    }
    /** Separate exact unary-admission schema; not a relaxation of rejection validation. */
    public static void validateUnary(JsonNode proof,JsonNode result,JsonNode handoff) {
        Profile profile=profile(handoff);require(UNARY_PROFILE.equals(profile.id),"not the exact unary profile");
        common(proof,result,handoff,profile,"reviewed-grpc-owned-unary.v1");
        for(String name:Set.of("rpc_attempted","channel_created"))flag(proof,name,true);
        flag(proof,"guard_rejection_observed",false);
        for(String name:Set.of("failure_code","diagnostic","error"))require(proof.path(name).isTextual()&&proof.path(name).textValue().isEmpty(),"unary success must have no failure");
        require("OK".equals(text(proof,"status"))&&"passed".equals(text(proof,"operation_status"))&&UNARY_BODY.equals(text(proof,"body")),"unary fixture outcome differs");
        var step=result.path("step_results").get(0);var provider=result.path("provider_results").get(0);
        require("passed".equals(text(result,"status"))&&"passed".equals(text(step,"status"))&&"passed".equals(text(provider,"status"))
                &&"OK".equals(step.path("outputs").path("response.status").asText())&&UNARY_BODY.equals(step.path("outputs").path("response.message").asText()),"canonical unary result differs");
        var failure=result.path("failure");
        require(failure.isObject(),"missing canonical no-failure object");
        for(String field:Set.of("code","classification","reason","owner_action"))require(failure.path(field).isNull(),"passed unary carries canonical failure");
        require(failure.path("cleanup_failures").isArray()&&failure.path("cleanup_failures").isEmpty(),"unary cleanup failure");
    }
    private static Profile profile(JsonNode handoff) {
        Profile profile=PROFILES.get(text(handoff.path("fixture_lease"),"profile"));require(profile!=null,"unknown exact profile");return profile;
    }
    private static void common(JsonNode proof,JsonNode result,JsonNode handoff,Profile profile,String schema) {
        require(proof.isObject(),"proof must be an object");var fields=new HashSet<String>();proof.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(FIELDS),"proof fields differ");
        for(var entry:Map.of("schema",schema,"evidence_type","grpc_request_response",
                "provider_type","grpc_client","operation","unary_call","service","service.Test","method",profile.method,
                "input_type",profile.input,"output_type",profile.output,"source_revision","a3f7fd0c1fd788871c460088446099700f6679e4").entrySet())
            require(entry.getValue().equals(text(proof,entry.getKey())),"wrong "+entry.getKey());
        require(PINS.get("fixtures/service.proto").equals(text(proof,"source_sha256"))&&PINS.get("fixtures/request.json").equals(text(proof,"request_sha256"))
                &&"e2a26040ccdbb5edf9c250074ab3fdb5e4188cdd8a868e1752a0bd4c6165ad0f".equals(text(proof,"descriptor_sha256")),"wrong input pins");
        flag(proof,"client_streaming",profile.client);flag(proof,"server_streaming",profile.server);
        flag(proof,"cleanup_confirmed",true);flag(proof,"masking_applied",true);
        require("reviewed_descriptor_assembly".equals(text(proof,"descriptor_generation"))&&proof.path("compiler").isNull(),"wrong descriptor provenance");
        require(text(result,"run_id").equals(text(proof,"run_id")),"wrong run identity");
        handoff.fieldNames().forEachRemaining(field->require(!field.startsWith("fixture_lease")||Set.of("fixture_lease","fixture_lease_sha256").contains(field),"unexpected alternate lease claim"));
        require("not_used".equals(text(handoff,"knowledge_mode"))&&"a3f7fd0c1fd788871c460088446099700f6679e4".equals(handoff.path("repository_revisions").path("python-betterproto").asText()),"source revision is not bound");
        for(var pin:PINS.entrySet()) {
            int count=0;for(var file:handoff.path("reviewed_files"))if(pin.getKey().equals(file.path("path").asText())) {
                require("fixture".equals(file.path("role").asText())&&pin.getValue().equals(file.path("sha256").asText()),"reviewed fixture pin mismatch");count++;
            }require(count==1,"reviewed fixture missing or ambiguous");
        }
        validateLease(proof,result,handoff,profile);
        require("{\"name\":\"my milkshakes\"}\n".equals(text(proof,"request")),"request differs");
        require(proof.path("duration_ms").isIntegralNumber()&&proof.path("duration_ms").canConvertToLong()&&proof.path("duration_ms").longValue()>=0,"invalid duration");
        require(result.path("step_results").isArray()&&result.path("step_results").size()==1&&result.path("provider_results").isArray()&&result.path("provider_results").size()==1,"one actual provider operation is required");
        var step=result.path("step_results").get(0);var provider=result.path("provider_results").get(0);
        require(text(proof,"step_id").equals(text(step,"id"))&&text(proof,"test_case_id").equals(text(step,"test_case_id"))&&text(proof,"provider_id").equals(text(step,"target")),"step identity differs");
        require(text(proof,"provider_id").equals(text(provider,"target"))&&"grpc_client.v0.3".equals(text(provider,"provider_contract"))
                &&"grpc_client".equals(text(provider,"provider_type"))&&"unary_call".equals(text(provider,"operation"))
                &&"execute".equals(text(step,"phase"))&&"unary_call".equals(text(step,"operation")),"provider identity differs");
    }
    private static void flag(JsonNode node,String name,boolean expected){require(node.path(name).isBoolean()&&node.path(name).booleanValue()==expected,"wrong "+name);}
    private static void validateLease(JsonNode proof,JsonNode result,JsonNode handoff,Profile profile) {
        var lease=handoff.path("fixture_lease");var names=new HashSet<String>();lease.fieldNames().forEachRemaining(names::add);
        require(lease.isObject() && names.equals(Set.of("version","profile","endpoint","owner_run_id","pid","process_start_instant","issued_at","expires_at","request_sha256","descriptor_sha256")),"lease fields differ");
        String pin=text(handoff,"fixture_lease_sha256");
        require(pin.matches("[a-f0-9]{64}") && pin.equals(text(proof,"fixture_lease_sha256")),"lease pin differs");
        require("reviewed-grpc-lease.v1".equals(text(lease,"version")) && profile.id.equals(text(lease,"profile")),"wrong lease profile/version");
        String owner=text(lease,"owner_run_id"),endpoint=text(lease,"endpoint");
        require(owner.matches("[a-f0-9]{32}") && owner.equals(text(proof,"owner_run_id")) && endpoint.equals(text(proof,"endpoint")),"owner/endpoint differs");
        require(endpoint.matches("grpc://127\\.0\\.0\\.1:[1-9][0-9]{0,4}") && java.net.URI.create(endpoint).getPort()<=65535,"nonloopback lease endpoint");
        require(lease.path("pid").isIntegralNumber() && lease.path("pid").canConvertToLong() && lease.path("pid").longValue()>0,"invalid owner pid");
        require(PINS.get("fixtures/service.desc.b64").equals(text(lease,"descriptor_sha256")) && PINS.get("fixtures/request.json").equals(text(lease,"request_sha256")),"lease input digest differs");
        var issue=instant(lease,"issued_at");var expiry=instant(lease,"expires_at");var checked=instant(proof,"lease_checked_at");
        require(expiry.isAfter(issue) && java.time.Duration.between(issue,expiry).compareTo(java.time.Duration.ofMinutes(10))<=0,"lease TTL differs");
        require(!instant(lease,"process_start_instant").isAfter(issue) && !checked.isBefore(issue) && checked.isBefore(expiry),"lease was not valid at the recorded check");
        require(!checked.isBefore(instant(result,"start_time")) && !checked.isAfter(instant(result,"end_time")),"lease check outside invocation");
        int count=0;for(var file:handoff.path("reviewed_files"))if("fixtures/lease.json".equals(file.path("path").asText())) {
            require("fixture".equals(file.path("role").asText()) && pin.equals(file.path("sha256").asText()),"lease closure mismatch");count++;
        }require(count==1,"lease closure missing/ambiguous");
    }
    private static java.time.Instant instant(JsonNode node,String key) {
        try{return java.time.Instant.parse(text(node,key));}
        catch(java.time.DateTimeException error){throw new IllegalArgumentException("reviewed_grpc_streaming_proof: invalid "+key,error);}
    }
    private static String text(JsonNode node,String key){require(node.path(key).isTextual()&&!node.path(key).asText().isBlank(),"missing "+key);return node.path(key).asText();}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalArgumentException("reviewed_grpc_streaming_proof: "+reason);}
}
