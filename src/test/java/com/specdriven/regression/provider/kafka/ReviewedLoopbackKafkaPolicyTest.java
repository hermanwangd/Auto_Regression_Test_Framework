package com.specdriven.regression.provider.kafka;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PUBLIC_SYNTHETIC lease declarations; no runtime route authority. */
class ReviewedLoopbackKafkaPolicyTest {
    @TempDir Path root;
    static final ObjectMapper JSON=new ObjectMapper();
    static final String OWNER="b".repeat(32),TOPIC="sit."+OWNER+".publish";
    static Map<String,Object> bindings(){return new LinkedHashMap<>(Map.of("bootstrap_servers","127.0.0.1:19092","topic",TOPIC,"consumer_group","sit."+OWNER+".group"));}
    static Map<String,Object> inputs(){return new LinkedHashMap<>(Map.of("payload_ref","artifact://fixtures/payload.json","key","run."+OWNER,"timeout","PT0.2S"));}
    static String make(Path root)throws Exception {
        Files.createDirectories(root.resolve("fixtures"));byte[] payload="{\"id\":\"public\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(root.resolve("fixtures/payload.json"),payload);
        var l=new LinkedHashMap<String,Object>();l.put("version","reviewed-kafka-lease.v1");l.put("owner_run_id",OWNER);l.put("nonce",OWNER);l.put("endpoint","127.0.0.1:19092");l.put("topic",TOPIC);l.put("consumer_group","sit."+OWNER+".group");l.put("key","run."+OWNER);l.put("payload_sha256",sha(payload));l.put("runtime_version","3.9.2");for(String f:List.of("image_child_sha256","image_config_sha256","advertised_listener_sha256"))l.put(f,"c".repeat(64));l.put("broker_container_id","d".repeat(64));l.put("network_mode","none");l.put("client_network_container_id","d".repeat(64));l.put("advertised_listener","127.0.0.1:19092");l.put("controller_listener","127.0.0.1:19093");l.put("partitions",1);l.put("initial_offset",0);l.put("issued_at","2000-01-01T00:00:00Z");l.put("expires_at","2000-01-01T00:05:00Z");JSON.writeValue(root.resolve("fixtures/lease.json").toFile(),l);return sha(Files.readAllBytes(root.resolve("fixtures/lease.json")));
    }
    static String sha(byte[] b)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));}
    @Test void historicalFixtureAndIndependentlyPinnedCurrentAdmissionRemainDistinct()throws Exception {
        String pin=make(root);assertThat(ReviewedLoopbackKafkaPolicy.fixture(root,bindings()).leaseSha256()).isEqualTo(pin);
        assertThatCode(()->ReviewedLoopbackKafkaPolicy.admit(root,bindings(),inputs(),pin,Instant.parse("2000-01-01T00:01:00Z"))).doesNotThrowAnyException();
        assertThatThrownBy(()->ReviewedLoopbackKafkaPolicy.admit(root,bindings(),inputs(),pin,Instant.now())).hasMessageContaining("lease_time");
    }
    @ParameterizedTest @ValueSource(strings={"remote","dns","ipv6","multi","uri","port","extra","env","topic","group"})
    void malformedBindingsAreRejected(String v){var b=bindings();switch(v){case "remote"->b.put("bootstrap_servers","192.0.2.1:19092");case "dns"->b.put("bootstrap_servers","localhost:19092");case "ipv6"->b.put("bootstrap_servers","[::1]:19092");case "multi"->b.put("bootstrap_servers","127.0.0.1:19092,127.0.0.1:19093");case "uri"->b.put("bootstrap_servers","kafka://127.0.0.1:19092");case "port"->b.put("bootstrap_servers","127.0.0.1:65536");case "extra"->b.put("sasl.jaas.config","SECRET");case "env"->b.put("bootstrap_servers",Map.of("secret_ref","env://BROKER"));case "topic"->b.put("topic","unowned");case "group"->b.put("consumer_group","unowned");}assertThatThrownBy(()->ReviewedLoopbackKafkaPolicy.validateBindings(b)).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"payload","key","missing","extra","zero","too_long","null","fraction"})
    void malformedInputsAreRejected(String v){var i=inputs();switch(v){case "payload"->i.put("payload_ref","artifact://fixtures/other.json");case "key"->i.put("key","unowned");case "missing"->i.remove("key");case "extra"->i.put("topic","override");case "zero"->i.put("timeout","PT0S");case "too_long"->i.put("timeout","PT5.001S");case "null"->i.put("timeout",null);case "fraction"->i.put("timeout","PT0.000999999S");}assertThatThrownBy(()->ReviewedLoopbackKafkaPolicy.validateInputs(i)).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"owner_run_id","nonce","endpoint","topic","consumer_group","key","payload_sha256","runtime_version","image_child_sha256","image_config_sha256","advertised_listener_sha256","broker_container_id","network_mode","client_network_container_id","advertised_listener","controller_listener","partitions","initial_offset","expires_at","extra"})
    void leaseIdentityAndDeclaredFenceCannotBeChanged(String field)throws Exception{make(root);var path=root.resolve("fixtures/lease.json");var l=JSON.readValue(path.toFile(),Map.class);switch(field){case "partitions"->l.put(field,2);case "initial_offset"->l.put(field,1);case "expires_at"->l.put(field,"2000-01-01T00:20:00Z");case "controller_listener"->l.put(field,"127.0.0.1:19092");default->l.put(field,"INVALID");}JSON.writeValue(path.toFile(),l);assertThatThrownBy(()->ReviewedLoopbackKafkaPolicy.fixture(root,bindings())).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","array","utf16","oversize","symlink","parent_symlink"})
    void unsafePayloadNeverBecomesASend(String v)throws Exception{make(root);Path path=root.resolve("fixtures/payload.json");switch(v){case "duplicate"->Files.writeString(path,"{\"id\":1,\"id\":2}");case "trailing"->Files.writeString(path,"{} {}");case "array"->Files.writeString(path,"[]");case "utf16"->Files.write(path,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_16));case "oversize"->Files.writeString(path,"x".repeat(65537));case "symlink"->{Files.delete(path);Files.createSymbolicLink(path,root.resolve("other"));}case "parent_symlink"->{Files.move(root.resolve("fixtures"),root.resolve("real"));Files.createSymbolicLink(root.resolve("fixtures"),root.resolve("real"));}}assertThatThrownBy(()->ReviewedLoopbackKafkaPolicy.fixture(root,bindings())).isInstanceOf(IllegalArgumentException.class);}
    @ParameterizedTest @ValueSource(strings={"missing","wrong","changed"})
    void independentlySuppliedRawPinCannotBeSelfReplaced(String v)throws Exception{String pin=make(root);if(v.equals("missing"))pin=null;if(v.equals("wrong"))pin="0".repeat(64);if(v.equals("changed"))Files.writeString(root.resolve("fixtures/lease.json"),Files.readString(root.resolve("fixtures/lease.json"))+" ");String p=pin;assertThatThrownBy(()->ReviewedLoopbackKafkaPolicy.admit(root,bindings(),inputs(),p,Instant.parse("2000-01-01T00:01:00Z"))).isInstanceOf(IllegalArgumentException.class);}
}
