package com.specdriven.regression.provider.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.specdriven.regression.provider.kafka.ReviewedLoopbackKafkaPolicy.*;

/** Historical immutable identity joins, never live network authority or consumer-delivery proof. */
public final class ReviewedKafkaPublishProof {
    public static final String PATH="provider-evidence/kafka/reviewed-publish.json";
    public static final Set<String> LEASE_IDENTITIES=Set.of("owner_run_id","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode");
    public static final Set<String> FIELDS;
    static {var fields=new HashSet<>(LEASE_IDENTITIES);fields.addAll(Set.of("version","evidence_type","provider_type","run_id","batch_id","step_id","provider_id","operation","fixture_lease_sha256","client_version","attempted","acknowledged","cleanup_confirmed","status","partition","offset","client_started_at","client_finished_at"));FIELDS=Set.copyOf(fields);}
    private ReviewedKafkaPublishProof(){}
    static Map<String,Object> create(ReviewedLoopbackKafkaPolicy.Fixture f,String run,String batch,String step,String provider,ReviewedKafkaProducer.Outcome o,Instant start,Instant end){
        Map<String,Object> p=new LinkedHashMap<>();for(String field:LEASE_IDENTITIES)p.put(field,f.lease().get(field));
        p.put("version","reviewed-kafka-publish.v1");p.put("evidence_type","kafka_event");p.put("provider_type","kafka");p.put("run_id",run);p.put("batch_id",batch);p.put("step_id",step);p.put("provider_id",provider);p.put("operation","kafka_publish");p.put("fixture_lease_sha256",f.leaseSha256());p.put("client_version",org.apache.kafka.common.utils.AppInfoParser.getVersion());
        p.put("attempted",o.attempted());p.put("acknowledged",o.acknowledged());p.put("cleanup_confirmed",o.cleanupConfirmed());p.put("status",o.status());p.put("partition",o.partition());p.put("offset",o.offset());p.put("client_started_at",start.toString());p.put("client_finished_at",end.toString());return p;
    }
    public static JsonNode frozenLease(JsonNode provenance)throws java.io.IOException {
        require(provenance.path("fixture_lease_base64").isTextual(),"lease_encoding");String raw64=provenance.path("fixture_lease_base64").textValue();require(raw64.length()<=21848&&raw64.matches("[A-Za-z0-9+/]*={0,2}"),"lease_encoding");byte[] raw;
        try{raw=Base64.getDecoder().decode(raw64);}catch(IllegalArgumentException e){throw new IllegalArgumentException("reviewed_kafka: lease_encoding");}
        require(raw.length>0&&raw.length<=16384&&Base64.getEncoder().encodeToString(raw).equals(raw64),"lease_encoding");
        require(digest(raw).equals(text(provenance,"fixture_lease_sha256")),"frozen_lease_digest");JsonNode lease=parse(raw);require(lease.equals(provenance.path("fixture_lease")),"frozen_lease_bytes");validateHistoricalLease(lease);return lease;
    }
    /** Admission time is the recorded invocation start, never the current clock or a live owner. */
    public static void verifyInvocation(JsonNode lease,JsonNode result){
        Instant start=instant(result,"start_time"),end=instant(result,"end_time");
        Instant issue=instant(lease,"issued_at"),expiry=instant(lease,"expires_at");
        require(!end.isBefore(start),"invocation_interval");
        require(!start.isBefore(issue)&&start.isBefore(expiry),"lease_invocation_time");
    }
    private static Instant instant(JsonNode node,String field){
        try{return Instant.parse(text(node,field));}
        catch(java.time.DateTimeException e){throw new IllegalArgumentException("reviewed_kafka: historical_time_format");}
    }
    public static void verify(JsonNode proof,JsonNode lease,String pin,JsonNode result){
        verifyInvocation(lease,result);
        exact(proof,FIELDS);require("reviewed-kafka-publish.v1".equals(text(proof,"version"))&&"kafka_event".equals(text(proof,"evidence_type"))&&"kafka".equals(text(proof,"provider_type"))&&"kafka_publish".equals(text(proof,"operation")),"proof_profile");
        require(pin!=null&&pin.matches("[a-f0-9]{64}")&&pin.equals(text(proof,"fixture_lease_sha256")),"proof_lease_pin");
        for(String field:LEASE_IDENTITIES)require(lease.path(field).equals(proof.path(field)),"proof_lease_identity");
        require("3.9.2".equals(text(proof,"client_version")),"client_version");
        for(String field:List.of("run_id","batch_id"))require(text(result,field).equals(text(proof,field)),"proof_run_identity");
        JsonNode providers=result.path("provider_results"),steps=result.path("step_results");require(providers.isArray()&&providers.size()==1&&steps.isArray()&&steps.size()==1,"proof_operation_count");JsonNode step=steps.get(0),provider=providers.get(0);
        require(text(step,"id").equals(text(proof,"step_id"))&&text(provider,"target").equals(text(proof,"provider_id"))&&text(step,"target").equals(text(proof,"provider_id")),"proof_step_identity");
        for(String f:List.of("attempted","acknowledged","cleanup_confirmed"))require(proof.path(f).isBoolean(),"proof_boolean");
        boolean attempted=proof.path("attempted").booleanValue(),ack=proof.path("acknowledged").booleanValue(),clean=proof.path("cleanup_confirmed").booleanValue();
        String status=text(proof,"status");require(Set.of("ACK","CLIENT_FAILED","DEADLINE_EXCEEDED","INTERRUPTED","CLEANUP_UNCERTAIN","PREFLIGHT_BLOCKED").contains(status),"proof_status");
        require(proof.path("partition").isIntegralNumber()&&proof.path("partition").canConvertToInt()&&proof.path("offset").isIntegralNumber()&&proof.path("offset").canConvertToLong(),"proof_metadata_type");
        int partition=proof.path("partition").intValue();long offset=proof.path("offset").longValue();
        require(!ack||attempted&&partition==0&&offset==0,"proof_ack_metadata");require(ack||partition==-1&&offset==-1,"proof_unacked_metadata");
        require(!status.equals("ACK")||attempted&&ack&&clean,"proof_ack_state");require(!status.equals("PREFLIGHT_BLOCKED")||!attempted&&!ack&&clean,"proof_preflight_state");
        require(!"passed".equals(text(result,"status"))||status.equals("ACK"),"passed_without_ack");
        if(ack){JsonNode outputs=step.path("outputs");for(String f:List.of("partition","offset"))require(outputs.path(f).isIntegralNumber()&&outputs.path(f).canConvertToLong()&&outputs.path(f).longValue()==proof.path(f).longValue(),"proof_output_join");for(String f:List.of("key","topic"))require(outputs.path(f).equals(proof.path(f)),"proof_output_join");}
        Instant clientStart=instant(proof,"client_started_at"),clientEnd=instant(proof,"client_finished_at");
        require(!clientEnd.isBefore(clientStart)&&!clientStart.isBefore(instant(result,"start_time"))&&!clientEnd.isAfter(instant(result,"end_time")),"client_invocation_interval");
        require(!clientStart.isBefore(instant(lease,"issued_at"))&&clientStart.isBefore(instant(lease,"expires_at"))&&!clientEnd.isAfter(instant(lease,"expires_at")),"client_lease_interval");
    }
}
