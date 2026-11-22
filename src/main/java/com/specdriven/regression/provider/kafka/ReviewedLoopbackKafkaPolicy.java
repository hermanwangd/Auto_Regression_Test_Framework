package com.specdriven.regression.provider.kafka;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;

/** Finite publish declaration. A pinned declaration is not live network/ownership authority. */
public final class ReviewedLoopbackKafkaPolicy {
    public static final String PAYLOAD_REF="artifact://fixtures/payload.json", LEASE_PATH="fixtures/lease.json";
    public static final int MAX_PAYLOAD_BYTES=65536;
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(4096).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> LEASE=Set.of("version","owner_run_id","nonce","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode","client_network_container_id","advertised_listener","controller_listener","partitions","initial_offset","issued_at","expires_at");
    private ReviewedLoopbackKafkaPolicy(){}
    public static void validateBindings(Map<String,?> b){
        require(b!=null&&b.keySet().equals(Set.of("bootstrap_servers","topic","consumer_group")),"bindings");
        endpoint(b.get("bootstrap_servers"));String owner=owner(b);
        require(("sit."+owner+".group").equals(b.get("consumer_group")),"consumer_group");
    }
    public static String owner(Map<String,?> b){Object topic=b.get("topic");require(topic instanceof String s&&s.matches("sit\\.[a-f0-9]{32}\\.publish"),"topic");return ((String)topic).substring(4,36);}
    static void endpoint(Object v){require(v instanceof String s&&s.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}"),"endpoint");int port=Integer.parseInt(((String)v).substring(10));require(port<=65535,"endpoint_port");}
    public static void validateInputs(Map<String,?> i){
        require(i!=null&&i.keySet().containsAll(Set.of("payload_ref","key"))&&Set.of("payload_ref","key","timeout").containsAll(i.keySet()),"inputs");
        require(PAYLOAD_REF.equals(i.get("payload_ref")),"payload_ref");require(i.get("key") instanceof String k&&k.matches("run\\.[a-f0-9]{32}"),"key");
        require(!i.containsKey("timeout")||i.get("timeout")!=null,"timeout_null");timeout(i.get("timeout"));
    }
    public static Duration timeout(Object v){if(v==null)return Duration.ofSeconds(5);require(v instanceof String s&&s.length()<=32,"timeout");try{Duration d=Duration.parse((String)v);require(d.toNanos()>=1000000&&d.compareTo(Duration.ofSeconds(5))<=0,"timeout_range");return d;}catch(DateTimeException|ArithmeticException e){throw new IllegalArgumentException("reviewed_kafka: timeout_format");}}
    public record Fixture(String payloadJson,Map<String,Object> lease,String leaseSha256,String leaseBase64){}
    public static Fixture fixture(Path root,Map<String,?> b){
        validateBindings(b);
        try{
            byte[] raw=read(root,LEASE_PATH,16384),payload=read(root,"fixtures/payload.json",MAX_PAYLOAD_BYTES);JsonNode l=parse(raw);exact(l,LEASE);
            JsonNode body=parse(payload);require(body.isObject(),"payload_object");validateLease(l,b,digest(payload));
            @SuppressWarnings("unchecked") Map<String,Object> lease=JSON.convertValue(l,Map.class);
            return new Fixture(utf8(payload),Map.copyOf(lease),digest(raw),Base64.getEncoder().encodeToString(raw));
        }catch(java.io.IOException|DateTimeException e){throw new IllegalArgumentException("reviewed_kafka: invalid_fixture");}
    }
    public static void validateHistoricalLease(JsonNode l){
        exact(l,LEASE);require(text(l,"payload_sha256").matches("[a-f0-9]{64}"),"payload_digest");
        Map<String,Object> b=Map.of("bootstrap_servers",text(l,"endpoint"),"topic",text(l,"topic"),"consumer_group",text(l,"consumer_group"));validateBindings(b);validateLease(l,b,text(l,"payload_sha256"));
    }
    private static void validateLease(JsonNode l,Map<String,?> b,String payloadSha){
        String owner=owner(b);
            require("reviewed-kafka-lease.v1".equals(text(l,"version")),"lease_version");
            for(String field:List.of("owner_run_id","nonce"))require(owner.equals(text(l,field)),"lease_owner");
            require(b.get("bootstrap_servers").equals(text(l,"endpoint"))&&b.get("topic").equals(text(l,"topic"))&&b.get("consumer_group").equals(text(l,"consumer_group")),"lease_bindings");
            require(("run."+owner).equals(text(l,"key")),"lease_key");require(payloadSha.equals(text(l,"payload_sha256")),"payload_digest");
            require(text(l,"runtime_version").matches("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"),"runtime_version");
            for(String f:List.of("image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id"))require(text(l,f).matches("[a-f0-9]{64}"),"lease_digest");
            require("none".equals(text(l,"network_mode"))&&text(l,"broker_container_id").equals(text(l,"client_network_container_id")),"network_namespace_declaration");
            require(text(l,"endpoint").equals(text(l,"advertised_listener")),"advertised_listener");endpoint(text(l,"controller_listener"));require(!text(l,"endpoint").equals(text(l,"controller_listener")),"controller_listener");
            require(l.path("partitions").isIntegralNumber()&&l.path("partitions").canConvertToInt()&&l.path("partitions").asInt()==1&&l.path("initial_offset").isIntegralNumber()&&l.path("initial_offset").canConvertToLong()&&l.path("initial_offset").asLong()==0,"fresh_single_partition");
            Instant issue=Instant.parse(text(l,"issued_at")),expiry=Instant.parse(text(l,"expires_at"));require(expiry.isAfter(issue)&&Duration.between(issue,expiry).compareTo(Duration.ofMinutes(10))<=0,"lease_time");
    }
    public static Fixture admit(Path root,Map<String,?> b,Map<String,?> i,String independentPin,Instant now){
        require(independentPin!=null&&independentPin.matches("[a-f0-9]{64}"),"independent_lease_pin");validateInputs(i);Fixture f=fixture(root,b);
        require(independentPin.equals(f.leaseSha256()),"lease_pin_mismatch");require(f.lease().get("key").equals(i.get("key")),"input_key_join");
        Instant issue=Instant.parse((String)f.lease().get("issued_at")),expiry=Instant.parse((String)f.lease().get("expires_at"));require(now!=null&&!now.isBefore(issue)&&now.isBefore(expiry),"lease_time");return f;
    }
    public static byte[] read(Path root,String ref,int limit)throws java.io.IOException{
        Path file=root.toRealPath().resolve(ref);require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file)&&file.toRealPath().equals(file),"unsafe_fixture");
        try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] raw=in.readNBytes(limit+1);require(raw.length>0&&raw.length<=limit,"fixture_byte_bound");return raw;}
    }
    public static JsonNode parse(byte[] raw)throws java.io.IOException {JsonNode n=JSON.readTree(utf8(raw));require(n!=null,"empty_json");return n;}
    private static String utf8(byte[] raw)throws java.nio.charset.CharacterCodingException{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();}
    public static String digest(byte[] raw){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String text(JsonNode n,String field){JsonNode v=n.path(field);require(v.isTextual()&&!v.asText().isBlank()&&v.asText().length()<=4096,"text_field");return v.asText();}
    public static void exact(JsonNode n,Set<String> fields){Set<String> keys=new HashSet<>();n.fieldNames().forEachRemaining(keys::add);require(n.isObject()&&keys.equals(fields),"exact_fields");}
    public static void require(boolean v,String code){if(!v)throw new IllegalArgumentException("reviewed_kafka: "+code);}
}
