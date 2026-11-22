package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Pure historical joins, shared verbatim with the Module except for its package. Never observes a process. */
public final class ReviewedA01CatalogProof {
    public static final String PROFILE="microservices-demo.getproduct.v1", VERSION="reviewed-a01-catalog-proof.v1", LEASE_VERSION="reviewed-a01-catalog-lease.v1";
    private static final String REV="c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712";
    private static final Map<String,String> PINS=Map.of(
        "upstream_revision",REV,
        "canonical_proto_sha256","b1f2fa42c0a9305211bf51fba7d8b5832cd3defd7b8855c327ebd5b1f0f86884",
        "generated_proto_sha256","799971d468d1e66e0d7fce8930ce74f8f1c894bf8425c50cad44c2a9b40cd9af",
        "data_sha256","766cd816fb1721ec0ce8d9261214fb5e10f6b89ce70600e2d20f149c7b5afffa",
        "descriptor_fdp_sha256","8f6b5761a04e7d699c0e40c2776d94900581f2706a112544fa80ba71169f5d54",
        "descriptor_sha256","285bd375bc4bff89d44ed51a0ddaf205670b35ff070860311b1fc0f4bdab1921");
    private static final String JAVA_SHA="1a7e0b17c39003c75b457d686775ccb032e56fa9c8afe3c492d8eb3715e8951a";
    private static final String NORMAL_SHA="079ffb4c48534e807f1bf07060f840290751bd4f011a94542d3b4f42eb8b98f7", ABSENT_SHA="ac6b8a0d364523bd13a27b5fdb894ede0b3fec3f6cce891b8ccaaae3f2424dbf";
    private static final Set<String> LEASE_FIELDS=Set.of("version","profile","endpoint","owner_run_id","issued_at","expires_at","supervisor","child","listener","owner_launch","source_manifest_sha256","runtime_manifest_sha256","build_manifest_sha256","owner_launch_sha256","upstream_revision","canonical_proto_sha256","generated_proto_sha256","descriptor_fdp_sha256","descriptor_sha256","request_sha256","data_sha256");
    public static final Set<String> PROOF_FIELDS=Set.of("version","evidence_type","provider_type","provider_id","operation","run_id","batch_id","test_case_id","step_id","profile","service","method","input_type","output_type","fixture_lease_sha256","owner_run_id","endpoint","upstream_revision","source_manifest_sha256","runtime_manifest_sha256","build_manifest_sha256","owner_launch_sha256","canonical_proto_sha256","generated_proto_sha256","descriptor_fdp_sha256","descriptor_sha256","request_sha256","data_sha256","supervisor","child","listener","owner_launch","request","live_checked_at","rpc_started_at","rpc_finished_at","channel_created","rpc_attempted","status","body","error","operation_status","failure_code","diagnostic","duration_ms","cleanup_confirmed","masking_applied");
    private static final Map<String,String> SOURCE_PINS=Map.of(
        "server.go","2d52d47db2a00c743470b94b1f7b970b190e9d45f9e4b76d0a345f5d00272b5a",
        "product_catalog.go","045a50900ba49382d920c644a6bee04c6372073761b2867ecf202428ce0c6545",
        "catalog_loader.go","c37a169b74faf6be29f1ae9999ea2a22daf8f2d7dd676b4706459b36cf95a4a4",
        "go.mod","b891e7545c990ad13307d416678a9719e5f667f45e1b11bcf3e367b2713a6e8d",
        "go.sum","68f514d3a43c025b8c6abd1979bcb3ef6794d7b66cb866a317f35dded082c8c2",
        "products.json","766cd816fb1721ec0ce8d9261214fb5e10f6b89ce70600e2d20f149c7b5afffa",
        "genproto/demo.pb.go","799971d468d1e66e0d7fce8930ce74f8f1c894bf8425c50cad44c2a9b40cd9af",
        "genproto/demo_grpc.pb.go","70bb93b42e93399bf30d9971b6568244d0fa2eb1cb4757f967354810601dc916");
    private ReviewedA01CatalogProof() { }
    public static boolean owns(String profile){return PROFILE.equals(profile);}
    public static void validateLease(JsonNode lease,JsonNode source,JsonNode runtime,String sourceSha,String runtimeSha,String requestSha) {
        identity(lease);
        validateArtifacts(source,runtime,sourceSha,runtimeSha);
        Path root=absolute(text(runtime,"owner_root"));
        eq(lease,"source_manifest_sha256",sourceSha);eq(lease,"runtime_manifest_sha256",runtimeSha);eq(lease,"request_sha256",requestSha);
        require(Set.of(NORMAL_SHA,ABSENT_SHA).contains(requestSha),"unreviewed request");eq(lease,"build_manifest_sha256",text(runtime,"build_manifest_sha256"));
        JsonNode child=lease.path("child"),supervisor=lease.path("supervisor");
        eq(child,"cwd",root.toString());eq(child,"executable_path",text(runtime,"binary_path"));eq(child,"executable_sha256",text(runtime,"binary_sha256"));
        eq(lease,"owner_launch_sha256",text(runtime,"owner_launch_sha256"));
        eq(supervisor,"executable_path",text(lease.path("owner_launch"),"java_path"));
        eq(supervisor,"executable_path",text(runtime,"supervisor_executable_path"));eq(supervisor,"executable_sha256",JAVA_SHA);
    }
    public static void validateArtifacts(JsonNode source,JsonNode runtime,String sourceSha,String runtimeSha) {
        require(sourceSha.matches("[a-f0-9]{64}")&&runtimeSha.matches("[a-f0-9]{64}"),"independent artifact digest format");
        exact(source,Set.of("version","upstream_revision","canonical_proto_sha256","generated_proto_sha256","data_sha256","descriptor_fdp_sha256","descriptor_sha256","loopback_source_sha256","source_files"));
        eq(source,"version","a01-catalog-source.v1");for(var pin:PINS.entrySet())eq(source,pin.getKey(),pin.getValue());
        eq(source,"loopback_source_sha256","db5cfbb858f0d9b94c5d3b3e6334e1d2821f2e740edf925aee1f58d76c9de530");
        exact(source.path("source_files"),Set.of("server.go","product_catalog.go","catalog_loader.go","go.mod","go.sum","products.json","genproto/demo.pb.go","genproto/demo_grpc.pb.go"));
        for(var member:SOURCE_PINS.entrySet())eq(source.path("source_files"),member.getKey(),member.getValue());
        exact(runtime,Set.of("version","owner_root","binary_path","binary_sha256","supervisor_executable_path","supervisor_executable_sha256","build_manifest_sha256","owner_launch_sha256","source_manifest_sha256","data_sha256"));
        eq(runtime,"version","a01-catalog-runtime.v1");eq(runtime,"source_manifest_sha256",sourceSha);eq(runtime,"data_sha256",PINS.get("data_sha256"));eq(runtime,"supervisor_executable_sha256",JAVA_SHA);
        for(String key:Set.of("binary_sha256","build_manifest_sha256","owner_launch_sha256"))sha(runtime,key);
        Path root=absolute(text(runtime,"owner_root")); require(absolute(text(runtime,"binary_path")).equals(root.resolve("productcatalogservice")),"runtime binary containment");
    }
    public static void validate(JsonNode proof,JsonNode result,JsonNode handoff) {
        exact(proof,PROOF_FIELDS);JsonNode lease=handoff.path("fixture_lease");identity(lease);
        eq(proof,"version",VERSION);eq(proof,"profile",PROFILE);eq(proof,"evidence_type","grpc_request_response");
        eq(proof,"provider_type","grpc_client");eq(proof,"operation","unary_call");eq(proof,"service","hipstershop.ProductCatalogService");eq(proof,"method","GetProduct");
        eq(proof,"input_type","hipstershop.GetProductRequest");eq(proof,"output_type","hipstershop.Product");
        for(String key:Set.of("owner_run_id","endpoint","upstream_revision","source_manifest_sha256","runtime_manifest_sha256","build_manifest_sha256","owner_launch_sha256","canonical_proto_sha256","generated_proto_sha256","descriptor_fdp_sha256","descriptor_sha256","request_sha256","data_sha256"))eq(proof,key,text(lease,key));
        for(String key:Set.of("supervisor","child","listener","owner_launch"))require(proof.path(key).equals(lease.path(key)),"owner observation differs: "+key);
        eq(proof,"run_id",text(result,"run_id"));eq(proof,"batch_id",text(result,"batch_id"));
        require(text(proof,"run_id").matches("RUN-(?:[A-Za-z0-9]+-)?[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"),"run identity format");
        require(REV.equals(handoff.path("repository_revisions").path("microservices-demo").asText()),"source revision not bound");
        sha(handoff,"fixture_lease_sha256");eq(proof,"fixture_lease_sha256",text(handoff,"fixture_lease_sha256"));
        Map<String,String> closure=Map.of("fixtures/lease.json",text(handoff,"fixture_lease_sha256"),"fixtures/catalog-source.json",text(lease,"source_manifest_sha256"),
            "fixtures/catalog-runtime.json",text(lease,"runtime_manifest_sha256"),"fixtures/catalog.desc.b64",text(lease,"descriptor_sha256"),"fixtures/request.json",text(lease,"request_sha256"),
            "fixtures/products.json",text(lease,"data_sha256"),"fixtures/demo.proto",text(lease,"canonical_proto_sha256"),"fixtures/generated-demo.pb.go",text(lease,"generated_proto_sha256"),"fixtures/catalog-owner-launch.json",text(lease,"owner_launch_sha256"));
        for(var pin:closure.entrySet()) {int count=0;for(JsonNode file:handoff.path("reviewed_files"))if(pin.getKey().equals(file.path("path").asText())){eq(file,"role","fixture");eq(file,"sha256",pin.getValue());count++;}require(count==1,"reviewed fixture missing/ambiguous: "+pin.getKey());}
        String expectedRequest=NORMAL_SHA.equals(text(lease,"request_sha256"))?"{\"id\":\"OLJCESPC7Z\"}\n":"{\"id\":\"A01-NO-SUCH-PRODUCT\"}\n";
        eq(proof,"request",expectedRequest);
        require(result.path("step_results").isArray()&&result.path("step_results").size()==1&&result.path("provider_results").isArray()&&result.path("provider_results").size()==1,"one actual provider step required");
        JsonNode step=result.path("step_results").get(0),provider=result.path("provider_results").get(0);
        eq(proof,"step_id",text(step,"id"));eq(proof,"test_case_id",text(step,"test_case_id"));eq(proof,"provider_id",text(step,"target"));
        eq(proof,"test_case_id",text(result,"test_case_id"));
        require(provider.path("resolved_operation_results").isArray()&&provider.path("resolved_operation_results").size()==1,"one resolved provider operation required");
        JsonNode resolved=provider.path("resolved_operation_results").get(0);eq(resolved,"test_case_id",text(proof,"test_case_id"));eq(resolved,"operation","unary_call");
        eq(provider,"target",text(proof,"provider_id"));eq(provider,"provider_contract","grpc_client.v0.3");eq(provider,"provider_type","grpc_client");eq(provider,"operation","unary_call");eq(step,"phase","execute");eq(step,"operation","unary_call");
        for(String flag:Set.of("channel_created","rpc_attempted","cleanup_confirmed","masking_applied"))require(proof.path(flag).isBoolean(),"missing boolean: "+flag);
        require(proof.path("masking_applied").booleanValue(),"masking flag");
        require(proof.path("duration_ms").isIntegralNumber()&&proof.path("duration_ms").canConvertToLong()&&proof.path("duration_ms").longValue()>=0,"duration");
        Instant checked=instant(proof,"live_checked_at"),issue=instant(lease,"issued_at"),expiry=instant(lease,"expires_at"),start=instant(result,"start_time"),end=instant(result,"end_time");
        require(!checked.isBefore(issue)&&checked.isBefore(expiry)&&!checked.isBefore(start)&&!checked.isAfter(end),"live check outside lease/run");
        if(proof.path("rpc_attempted").booleanValue()) {
            require(proof.path("channel_created").booleanValue(),"attempt without channel");Instant rpcStart=instant(proof,"rpc_started_at"),rpcEnd=instant(proof,"rpc_finished_at");
            require(!rpcStart.isBefore(checked)&&!rpcEnd.isBefore(rpcStart)&&!rpcEnd.isAfter(end)&&rpcStart.isBefore(expiry),"RPC times differ");
        } else {empty(proof,"rpc_started_at");empty(proof,"rpc_finished_at");}
        String status=text(proof,"status");require(proof.path("body").isTextual()&&proof.path("error").isTextual()&&proof.path("diagnostic").isTextual()&&proof.path("failure_code").isTextual(),"response text fields");
        require(status.equals(step.path("outputs").path("response.status").asText()),"canonical response status differs");
        require(proof.path("duration_ms").equals(step.path("outputs").path("response.duration_ms")),"canonical duration differs");
        if(status.equals("NOT_FOUND")) {
            require(proof.path("rpc_attempted").booleanValue() && proof.path("channel_created").booleanValue() && proof.path("cleanup_confirmed").booleanValue(),"NOT_FOUND requires actual RPC, channel and cleanup");
            instant(proof,"rpc_started_at");instant(proof,"rpc_finished_at");
        }
        if(status.equals("OK")) {
            eq(proof,"operation_status","passed");eq(step,"status","passed");eq(provider,"status","passed");empty(proof,"error");empty(proof,"diagnostic");empty(proof,"failure_code");
            require(proof.path("rpc_attempted").booleanValue()&&proof.path("cleanup_confirmed").booleanValue(),"OK requires actual attempt and cleanup");
            require(proof.path("body").equals(step.path("outputs").path("response.message")),"canonical body differs");
        } else {
            eq(proof,"operation_status","failed");eq(step,"status","failed");eq(provider,"status","failed");eq(result,"status","failed");empty(proof,"body");
            String failure=status.equals("DEADLINE_EXCEEDED")?"PROVIDER_TIMEOUT":"OPERATION_FAILED";
            eq(proof,"failure_code",failure);eq(step,"failure_code",failure);eq(result.path("failure"),"code",failure);
            String diagnostic="Reviewed gRPC unary failed: "+status+" "+proof.path("error").asText();eq(proof,"diagnostic",diagnostic);eq(result.path("failure"),"reason",diagnostic);
        }
        eq(resolved,"status",text(proof,"operation_status"));
        require(resolved.path("outputs").equals(step.path("outputs")),"resolved outputs differ from canonical step");
        require(!resolved.has("failure_code"),"resolved operation schema has no failure_code field");
        if(!status.equals("OK"))eq(provider,"failure_code",text(proof,"failure_code"));
        if("passed".equals(result.path("status").asText()))require(status.equals("OK")&&proof.path("rpc_attempted").booleanValue()&&proof.path("cleanup_confirmed").booleanValue(),"passed result lacks actual clean OK");
    }
    public static void validateLaunch(JsonNode launch) {
        exact(launch,Set.of("version","java_path","java_sha256","suite_root","classpath"));
        eq(launch,"version","a01-owner-launch.v1");eq(launch,"java_sha256",JAVA_SHA);
        absolute(text(launch,"java_path"));absolute(text(launch,"suite_root"));
        require(launch.path("classpath").isArray()&&launch.path("classpath").size()>0&&launch.path("classpath").size()<=8,"bounded ordered classpath required");
        Set<String> paths=new HashSet<>();long bytes=0;
        for(JsonNode member:launch.path("classpath")) {
            exact(member,Set.of("path","sha256","bytes"));String path=text(member,"path");absolute(path);
            require(path.endsWith(".jar")&&!path.contains(":")&&paths.add(path),"unique regular JAR classpath required");sha(member,"sha256");positive(member,"bytes");
            require(member.path("bytes").asLong()<=32*1024*1024,"JAR bound");bytes+=member.path("bytes").asLong();
        }
        require(bytes<=32*1024*1024,"classpath aggregate bound");
    }
    public static byte[] launchBytes(JsonNode launch) {
        validateLaunch(launch);
        Map<String,Object> fields=new java.util.LinkedHashMap<>();
        for(String key:java.util.List.of("version","java_path","java_sha256","suite_root"))fields.put(key,launch.path(key).asText());
        var classpath=new java.util.ArrayList<Map<String,Object>>();
        for(JsonNode member:launch.path("classpath")){Map<String,Object> entry=new java.util.LinkedHashMap<>();entry.put("path",member.path("path").asText());entry.put("sha256",member.path("sha256").asText());entry.put("bytes",member.path("bytes").asLong());classpath.add(entry);}
        fields.put("classpath",classpath);
        try{return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(fields);}catch(java.io.IOException impossible){throw new IllegalArgumentException("reviewed_a01: launch encoding failed",impossible);}
    }
    public static String launchDigest(JsonNode launch) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(launchBytes(launch)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void empty(JsonNode node,String field){require(node.path(field).isTextual()&&node.path(field).asText().isEmpty(),"expected empty "+field);}
    private static void identity(JsonNode lease) {
        exact(lease,LEASE_FIELDS);eq(lease,"version",LEASE_VERSION);eq(lease,"profile",PROFILE);
        for(var pin:PINS.entrySet())eq(lease,pin.getKey(),pin.getValue());
        for(String key:Set.of("source_manifest_sha256","runtime_manifest_sha256","build_manifest_sha256","owner_launch_sha256","request_sha256"))sha(lease,key);
        require(Set.of(NORMAL_SHA,ABSENT_SHA).contains(text(lease,"request_sha256")),"request pin");
        String owner=text(lease,"owner_run_id");require(owner.matches("[a-f0-9]{32}"),"owner format");
        Instant issue=instant(lease,"issued_at"),expiry=instant(lease,"expires_at");require(expiry.isAfter(issue)&&Duration.between(issue,expiry).compareTo(Duration.ofMinutes(10))<=0,"lease lifetime");
        JsonNode parent=lease.path("supervisor"),child=lease.path("child"),listener=lease.path("listener");
        validateLaunch(lease.path("owner_launch"));
        eq(lease,"owner_launch_sha256",launchDigest(lease.path("owner_launch")));
        exact(parent,Set.of("pid","process_start_instant","executable_path","executable_sha256","nonce_argument"));
        exact(child,Set.of("pid","process_start_instant","ppid","executable_path","executable_sha256","cwd"));
        exact(listener,Set.of("family","address","port","inode","network_namespace_inode"));
        for(JsonNode process:new JsonNode[]{parent,child}) {positive(process,"pid");require(!instant(process,"process_start_instant").isAfter(issue),"process starts after lease");absolute(text(process,"executable_path"));sha(process,"executable_sha256");}
        eq(parent,"executable_sha256",JAVA_SHA);eq(parent,"nonce_argument","-Dswarm.grpc.owner_nonce="+owner);
        positive(child,"ppid");require(child.path("ppid").longValue()==parent.path("pid").longValue()&&child.path("pid").longValue()!=parent.path("pid").longValue(),"parent relation");absolute(text(child,"cwd"));
        eq(listener,"family","ipv4");eq(listener,"address","127.0.0.1");positive(listener,"port");require(listener.path("port").longValue()<=65535,"port range");positive(listener,"inode");positive(listener,"network_namespace_inode");
        eq(lease,"endpoint","grpc://127.0.0.1:"+listener.path("port").intValue());
    }
    static void exact(JsonNode node,Set<String> expected){require(node.isObject(),"expected object");Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);require(actual.equals(expected),"unexpected fields");}
    static String text(JsonNode node,String field){require(node.path(field).isTextual()&&!node.path(field).asText().isBlank(),"missing text "+field);return node.path(field).asText();}
    static void eq(JsonNode node,String field,String value){require(value.equals(text(node,field)),"identity differs: "+field);}
    static void sha(JsonNode node,String field){require(text(node,field).matches("[a-f0-9]{64}"),"digest format: "+field);}
    static Instant instant(JsonNode node,String field){return Instant.parse(text(node,field));}
    static void positive(JsonNode node,String field){require(node.path(field).isIntegralNumber()&&node.path(field).canConvertToLong()&&node.path(field).longValue()>0,"positive integer: "+field);}
    static Path absolute(String value){Path path=Path.of(value);require(path.isAbsolute()&&path.normalize().equals(path),"absolute normalized path");return path;}
    static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException("reviewed_a01: "+reason);}
}
