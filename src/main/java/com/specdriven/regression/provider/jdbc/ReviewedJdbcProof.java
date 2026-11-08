package com.specdriven.regression.provider.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Pure historical checks. Never resolves credentials, reads Docker or requires a live owner. */
public final class ReviewedJdbcProof {
    public static final String PATH="provider-evidence/jdbc/reviewed-readonly.json";
    private static final String SQL="SELECT probe_id, probe_value, (SELECT COUNT(*) FROM swarm_probe.read_only_rows) AS total_rows FROM swarm_probe.read_only_rows ORDER BY probe_id LIMIT 3";
    private static final String PROFILE="owned-mariadb-readonly.v1",DRIVER="85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706",IMAGE="ec1794c8cd442f795b838f60379d7851acf8fef04cc39a8ecf74e7b1d6a3b6d7";
    private static final Set<String> FIELDS=Set.of("version","profile","owner_run_id","container_id","image_digest","container_started_at","owner_pid","owner_start_instant","db_pid","db_start_instant","endpointIPv4","port","schema","query_sha256","driver_sha256","issued_at","expires_at");
    private ReviewedJdbcProof() {}
    public static void validateLease(JsonNode lease) {
        require(lease!=null&&lease.isObject()&&lease.size()==FIELDS.size(),"exact lease fields required");lease.fieldNames().forEachRemaining(k->require(FIELDS.contains(k),"unknown lease field"));
        require("reviewed-mariadb-lease.v1".equals(text(lease,"version"))&&PROFILE.equals(text(lease,"profile")),"finite lease profile required");
        require(text(lease,"owner_run_id").matches("[a-f0-9]{32}")&&text(lease,"container_id").matches("[a-f0-9]{64}"),"owner identity required");
        require(IMAGE.equals(text(lease,"image_digest"))&&DRIVER.equals(text(lease,"driver_sha256"))&&querySha().equals(text(lease,"query_sha256")),"source pin mismatch");
        require(privateIp(text(lease,"endpointIPv4"))&&lease.path("port").isInt()&&lease.path("port").intValue()==3306&&"swarm_probe".equals(text(lease,"schema")),"finite endpoint/schema required");
        require(positivePid(lease.path("owner_pid"))&&positivePid(lease.path("db_pid"))&&lease.path("owner_pid").longValue()!=lease.path("db_pid").longValue(),"distinct owned process identities required");
        try {for(String k:List.of("container_started_at","owner_start_instant","db_start_instant"))Instant.parse(text(lease,k));Instant issue=Instant.parse(text(lease,"issued_at")),expiry=Instant.parse(text(lease,"expires_at"));require(expiry.isAfter(issue)&&Duration.between(issue,expiry).compareTo(Duration.ofMinutes(10))<=0,"lease TTL exceeds ten minutes");}
        catch(DateTimeException e){throw invalid("invalid lease instant");}
    }
    private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxStringLength(16384).maxNestingDepth(32).build()).build()).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PROOF_FIELDS=Set.of("evidence_type","provider_type","provider_id","operation","fixture_lease_sha256","owner_run_id","endpoint","schema","profile","query_sha256","driver_sha256","driver_connect_attempted","connected","sql_attempted","cleanup_confirmed","status","stage","sql_state","vendor_code","result","invocation_id","process_pid","process_start_instant","operation_ms","cleanup_ms","total_ms","operation_started_at","operation_ended_at","fixture_admitted_at");
    public static JsonNode parseProof(byte[] bytes){require(bytes!=null&&bytes.length<=65536,"proof exceeds64KiB");try{return JSON.readTree(bytes);}catch(java.io.IOException e){throw invalid("strict proof JSON invalid");}}
    /** The digest is over retained original bytes, never over a serialization of the parsed snapshot. */
    public static JsonNode boundLease(JsonNode parsed,String encoded,String pin) {
        require(pin!=null&&pin.matches("[a-f0-9]{64}")&&encoded!=null&&encoded.length()<=21848,"raw lease witness missing or unbounded");
        try {byte[] raw=Base64.getDecoder().decode(encoded);require(raw.length<=16384&&pin.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))),"raw lease digest mismatch");var lease=JSON.readTree(raw);validateLease(lease);require(lease.equals(parsed),"parsed lease differs from pinned raw bytes");return lease;}
        catch(java.io.IOException|java.security.NoSuchAlgorithmException|IllegalArgumentException e){throw invalid("raw lease witness invalid");}
    }
    public static void validate(JsonNode lease,String leaseSha,JsonNode proof,boolean passed,String canonicalPayload,String providerId,String operation,Instant runStart,Instant runEnd) {
        validateLease(lease);require(leaseSha!=null&&leaseSha.matches("[a-f0-9]{64}"),"independent lease pin required");
        require(proof!=null&&proof.isObject()&&proof.size()==PROOF_FIELDS.size(),"exact proof fields required");proof.fieldNames().forEachRemaining(k->require(PROOF_FIELDS.contains(k),"unknown proof field"));
        require("jdbc_query".equals(text(proof,"evidence_type"))&&"jdbc".equals(text(proof,"provider_type"))&&providerId!=null&&!providerId.isBlank()&&providerId.equals(text(proof,"provider_id"))&&"db_query".equals(operation)&&operation.equals(text(proof,"operation")),"canonical provider/operation identity mismatch");
        require(leaseSha.equals(text(proof,"fixture_lease_sha256"))&&text(lease,"owner_run_id").equals(text(proof,"owner_run_id"))&&(text(lease,"endpointIPv4")+":3306").equals(text(proof,"endpoint")),"fixture identity mismatch");
        for(String field:List.of("schema","profile","query_sha256","driver_sha256"))require(text(lease,field).equals(text(proof,field)),"source/profile identity mismatch");
        for(String field:List.of("driver_connect_attempted","connected","sql_attempted","cleanup_confirmed"))require(proof.path(field).isBoolean(),"attempt/cleanup flags must be boolean");
        boolean attempted=proof.path("driver_connect_attempted").booleanValue(),connected=proof.path("connected").booleanValue(),sql=proof.path("sql_attempted").booleanValue(),clean=proof.path("cleanup_confirmed").booleanValue();
        require((!connected||attempted)&&(!sql||connected),"contradictory connection/SQL observation");
        require(text(proof,"invocation_id").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")&&positivePid(proof.path("process_pid")),"child invocation identity required");
        String status=text(proof,"status"),stage=text(proof,"stage");
        require(Set.of("OK","DEADLINE_EXCEEDED","CONNECT_FAILED","QUERY_FAILED","RESULT_LIMIT","RESULT_TYPE","DRIVER_INVALID","CREDENTIAL_REJECTED","CLEANUP_UNCONFIRMED","OPERATION_FAILED","SECRET_OUTPUT_BLOCKED").contains(status),"unknown status");
        require(Set.of("PREFLIGHT","SOURCE","DRIVER","CONNECT","QUERY","RESULT","CLEANUP","COMPLETE").contains(stage),"unknown stage");
        require(proof.path("sql_state").isTextual()&&(proof.path("sql_state").textValue().isEmpty()||proof.path("sql_state").textValue().matches("[A-Z0-9]{5}"))&&proof.path("vendor_code").isInt(),"typed SQL failure observation required");
        require(proof.path("result").isTextual(),"typed normalized result required");require(!passed||"OK".equals(status),"passed result requires an OK query");
        require(clean==!"CLEANUP_UNCONFIRMED".equals(status),"cleanup status contradicts observation");
        switch(status){
            case "OK"->require(stage.equals("COMPLETE")&&attempted&&connected&&sql&&clean&&proof.path("sql_state").textValue().isEmpty()&&proof.path("vendor_code").intValue()==0,"OK requires complete typed successful operation");
            case "CLEANUP_UNCONFIRMED"->require(stage.equals("CLEANUP"),"cleanup failure stage mismatch");
            case "CONNECT_FAILED"->require(stage.equals("CONNECT")&&attempted&&!connected&&!sql,"connection failure stage mismatch");
            case "QUERY_FAILED"->require(Set.of("CONNECT","QUERY","RESULT").contains(stage)&&connected,"query failure stage mismatch");
            case "DRIVER_INVALID"->require(stage.equals("DRIVER")&&!attempted,"driver failure stage mismatch");
            case "CREDENTIAL_REJECTED"->require(stage.equals("SOURCE")&&!attempted,"credential failure stage mismatch");
            case "RESULT_LIMIT","RESULT_TYPE","SECRET_OUTPUT_BLOCKED"->require(stage.equals("RESULT")&&sql,"result failure stage mismatch");
            default->require(!stage.equals("CLEANUP")&&(!stage.equals("COMPLETE")||status.equals("DEADLINE_EXCEEDED")),"failure stage mismatch");
        }
        long op=millis(proof,"operation_ms"),cleanup=millis(proof,"cleanup_ms"),total=millis(proof,"total_ms");long sum;try{sum=Math.addExact(op,cleanup);}catch(ArithmeticException e){throw invalid("duration phase overflow");}require(total==sum||sum<Long.MAX_VALUE&&total==sum+1,"duration phases contradict total");
        require(op<=5000||Set.of("DEADLINE_EXCEEDED","CLEANUP_UNCONFIRMED").contains(status),"operation budget contradiction");
        require((cleanup<=2000&&total<=7000)||status.equals("CLEANUP_UNCONFIRMED"),"cleanup budget contradiction");
        try {
            Instant child=Instant.parse(text(proof,"process_start_instant")),start=Instant.parse(text(proof,"operation_started_at")),end=Instant.parse(text(proof,"operation_ended_at"));
            require(runStart!=null&&runEnd!=null&&!runEnd.isBefore(runStart)&&!end.isBefore(start)&&!child.isAfter(start)&&!end.isBefore(runStart)&&!end.isAfter(runEnd)&&!start.isAfter(runEnd)&&Duration.between(start,end).toMillis()==total,"historical operation/run timing mismatch");
            require(proof.path("fixture_admitted_at").isTextual(),"typed fixture admission required");String admission=proof.path("fixture_admitted_at").textValue();
            require(!admission.isEmpty()||(!attempted&&!Set.of("SOURCE","DRIVER").contains(stage)),"attempt lacks fixture admission");
            if(!admission.isEmpty()){Instant admitted=Instant.parse(admission);require(!admitted.isBefore(start)&&!admitted.isAfter(end)&&!admitted.isBefore(runStart)&&!admitted.isAfter(runEnd)&&!admitted.isBefore(Instant.parse(text(lease,"issued_at")))&&admitted.isBefore(Instant.parse(text(lease,"expires_at"))),"invocation admission outside frozen lease/run");}
        }catch(DateTimeException|ArithmeticException e){throw invalid("historical timing invalid");}
        if(status.equals("OK")){
            require(canonicalPayload!=null&&canonicalPayload.equals(proof.path("result").textValue()),"normalized result differs from canonical output");validatePayload(canonicalPayload);
        }else require(proof.path("result").textValue().isEmpty(),"failed proof cannot carry a successful payload");
    }
    private static long millis(JsonNode proof,String field){var value=proof.path(field);require(value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=0,"typed nonnegative int64 duration required");return value.longValue();}
    private static void exact(JsonNode object,Set<String> fields){require(object.isObject()&&object.size()==fields.size(),"normalized exact fields required");object.fieldNames().forEachRemaining(k->require(fields.contains(k),"normalized unexpected field"));}
    private static void validatePayload(String text){
        require(text.getBytes(StandardCharsets.UTF_8).length<=16384,"normalized result exceeds bound");
        try {
            var payload=JSON.readTree(text);require(payload!=null,"normalized JSON required");exact(payload,Set.of("row_count","columns","rows"));
            require(payload.path("row_count").isInt()&&payload.path("row_count").intValue()>=0&&payload.path("row_count").intValue()<=2&&payload.path("rows").isArray()&&payload.path("rows").size()==payload.path("row_count").intValue()&&payload.path("columns").isArray()&&payload.path("columns").size()==3,"normalized result shape invalid");
            String[] names={"probe_id","probe_value","total_rows"};int[] types={4,12,-5};
            for(int i=0;i<3;i++){var column=payload.path("columns").get(i);exact(column,Set.of("name","jdbc_type"));require(names[i].equals(text(column,"name"))&&column.path("jdbc_type").isInt()&&column.path("jdbc_type").intValue()==types[i],"normalized columns invalid");}
            for(var row:payload.path("rows")){exact(row,Set.of("probe_id","probe_value","total_rows","null_columns"));require(row.path("probe_id").isIntegralNumber()&&row.path("probe_id").canConvertToInt()&&row.path("total_rows").isIntegralNumber()&&row.path("total_rows").canConvertToLong(),"normalized row types invalid");var value=row.path("probe_value");require(value.isNull()||value.isTextual()&&value.textValue().getBytes(StandardCharsets.UTF_8).length<=4096,"normalized cell invalid");var nulls=row.path("null_columns");require(nulls.isArray()&&(value.isNull()?nulls.size()==1&&nulls.get(0).isTextual()&&"probe_value".equals(nulls.get(0).textValue()):nulls.isEmpty()),"normalized null flags invalid");}
        }catch(java.io.IOException e){throw invalid("strict normalized JSON invalid");}
    }
    public static String querySha(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SQL.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static boolean positivePid(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>0;}
    private static boolean privateIp(String s){String[] p=s.split("\\.",-1);if(p.length!=4)return false;int[] n=new int[4];for(int i=0;i<4;i++){if(!p[i].matches("0|[1-9][0-9]{0,2}"))return false;n[i]=Integer.parseInt(p[i]);if(n[i]>255)return false;}return n[3]>0&&n[3]<255&&(n[0]==10||n[0]==172&&n[1]>=16&&n[1]<=31||n[0]==192&&n[1]==168);}
    private static String text(JsonNode node,String field){require(node!=null&&node.path(field).isTextual()&&!node.path(field).textValue().isBlank(),"missing typed "+field);return node.path(field).textValue();}
    private static void require(boolean ok,String reason){if(!ok)throw invalid(reason);}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("JDBC proof: "+reason);}
}
