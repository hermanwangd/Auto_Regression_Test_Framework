package com.specdriven.regression.provider.grpc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Finite, separately leased local unary fixture; never accepts arbitrary services or connection options. */
public final class ReviewedLoopbackGrpcPolicy {
    public static final int MAX_DESCRIPTOR_ENCODED = 350 * 1024, MAX_DESCRIPTOR_BYTES = 256 * 1024;
    public static final int MAX_REQUEST_BYTES = 65536, MAX_RESPONSE_BYTES = 1024 * 1024;
    public static final String DESCRIPTOR_REF = "artifact://fixtures/echo.desc.b64";
    public static final String REQUEST_REF = "artifact://fixtures/request.json";
    public static final String LEASE_PATH = "fixtures/lease.json";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> INPUTS = Set.of("grpc.service", "grpc.method", "grpc.descriptor_ref", "grpc.message", "grpc.timeout");
    private static final Set<String> LEASE_FIELDS = Set.of("version", "profile", "endpoint", "owner_run_id", "pid", "process_start_instant", "issued_at", "expires_at", "request_sha256", "descriptor_sha256");
    public enum Profile {
        ECHO("echo.v1","swarm.producttest","EchoService","Echo","EchoRequest","EchoReply","message","echo.desc.b64"),
        GREETER("greeter.v1","helloworld","Greeter","SayHello","HelloRequest","HelloReply","name","greeter.desc.b64");
        final String id,pkg,service,method,request,response,requestField,descriptor;
        Profile(String id,String pkg,String service,String method,String request,String response,String requestField,String descriptor) {
            this.id=id;this.pkg=pkg;this.service=service;this.method=method;this.request=request;this.response=response;this.requestField=requestField;this.descriptor=descriptor;
        }
        public String descriptorPath(){return "fixtures/"+descriptor;}
    }
    public static Profile profile(Map<String,?> values) {
        for(var profile:Profile.values()) if((profile.pkg+"."+profile.service).equals(values.get("grpc.service"))
                && profile.method.equals(values.get("grpc.method")) && ("artifact://"+profile.descriptorPath()).equals(values.get("grpc.descriptor_ref")))return profile;
        throw new IllegalArgumentException("reviewed_grpc: only finite Echo/Echo or Greeter/SayHello profiles are supported");
    }
    private ReviewedLoopbackGrpcPolicy() { }

    public static void validateBindings(Map<String,?> values) {
        require(values != null && values.keySet().equals(Set.of("target", "owner_run_id")), "bindings require exactly target and owner_run_id");
        require(values.get("target") instanceof String target && target.matches("grpc://127\\.0\\.0\\.1:[1-9][0-9]{0,4}"), "target must be literal grpc://127.0.0.1:<port>");
        int port = java.net.URI.create((String) values.get("target")).getPort();
        require(port >= 1 && port <= 65535, "port out of range");
        require(values.get("owner_run_id") instanceof String id && id.matches("[a-f0-9]{32}"), "owner_run_id requires 32 lowercase random hex");
    }
    public static void validateInputs(Map<String,?> values) {
        require(values != null && INPUTS.containsAll(values.keySet()) && values.keySet().containsAll(Set.of("grpc.service", "grpc.method", "grpc.descriptor_ref", "grpc.message")), "unsupported or missing unary input");
        profile(values);
        require(REQUEST_REF.equals(values.get("grpc.message")), "only fixed reviewed request fixture is supported");
        if (values.containsKey("grpc.timeout")) require(values.get("grpc.timeout") != null, "timeout cannot be null");
        timeout(values.get("grpc.timeout"));
    }
    public static Duration timeout(Object raw) {
        if (raw == null) return Duration.ofSeconds(5);
        require(raw instanceof String text && text.length() <= 32, "timeout must be bounded ISO duration");
        try {
            Duration duration=Duration.parse((String)raw);
            require(duration.toNanos() >= 1_000_000 && duration.compareTo(Duration.ofSeconds(5)) <= 0, "timeout must be 1ms..5s");
            return duration;
        } catch (java.time.DateTimeException | ArithmeticException e) { throw new IllegalArgumentException("reviewed_grpc: invalid timeout",e); }
    }
    public record Fixture(Descriptors.MethodDescriptor method, String requestJson, Map<String,Object> lease, String leaseSha256) { }
    public static Fixture fixture(Path root, Map<String,?> bindings, boolean live) {
        validateBindings(bindings);
        try {
            byte[] leaseBytes=read(root,LEASE_PATH,16384);
            JsonNode leaseDoc=JSON.readTree(leaseBytes);
            require(leaseDoc!=null && leaseDoc.isObject() && leaseDoc.size()==LEASE_FIELDS.size(), "lease fields must be exact");
            leaseDoc.fieldNames().forEachRemaining(key->require(LEASE_FIELDS.contains(key),"unknown lease field"));
            Profile selected=null;
            for(var candidate:Profile.values())if(candidate.id.equals(leaseDoc.path("profile").asText()))selected=candidate;
            require(selected!=null,"unsupported lease profile");
            Profile profile=selected;
            byte[] encoded=read(root,profile.descriptorPath(),MAX_DESCRIPTOR_ENCODED);
            String base64=StandardCharsets.US_ASCII.newDecoder().decode(ByteBuffer.wrap(encoded)).toString();
            require(base64.matches("[A-Za-z0-9+/]*={0,2}"), "descriptor must be strict base64 without whitespace");
            byte[] bytes=Base64.getDecoder().decode(base64);
            require(bytes.length > 0 && bytes.length <= MAX_DESCRIPTOR_BYTES, "decoded descriptor exceeds limit");
            var set=DescriptorProtos.FileDescriptorSet.parseFrom(bytes);
            require(set.getFileCount()==1 && set.getUnknownFields().asMap().isEmpty(), "one descriptor file is required");
            var proto=set.getFile(0);
            require(proto.getSyntax().equals("proto3") && proto.getPackage().equals(profile.pkg)
                    && proto.getDependencyCount()==0 && proto.getServiceCount()==1 && proto.getMessageTypeCount()==2
                    && proto.getExtensionCount()==0 && proto.getEnumTypeCount()==0 && validFileOptions(proto,profile)
                    && proto.getUnknownFields().asMap().isEmpty(), "descriptor must be self-contained finite proto3 Echo schema");
            var service=proto.getService(0);
            require(service.getName().equals(profile.service) && service.getMethodCount()==1 && !service.hasOptions()
                    && service.getUnknownFields().asMap().isEmpty(), "only one Echo service/method is accepted");
            var rpc=service.getMethod(0);
            require(rpc.getName().equals(profile.method) && !rpc.getClientStreaming() && !rpc.getServerStreaming()
                    && !rpc.hasOptions() && rpc.getUnknownFields().asMap().isEmpty(), "only unary Echo is accepted");
            require(rpc.getInputType().equals("."+profile.pkg+"."+profile.request) && rpc.getOutputType().equals("."+profile.pkg+"."+profile.response),"method must use exact profile request/reply types");
            for (var message:proto.getMessageTypeList()) {
                require(message.getName().equals(profile.request) || message.getName().equals(profile.response),"unknown profile message");
                String fieldName=message.getName().equals(profile.request)?profile.requestField:"message";
                require(message.getFieldCount()==1 && message.getNestedTypeCount()==0 && message.getEnumTypeCount()==0
                        && message.getExtensionCount()==0 && message.getOneofDeclCount()==0 && message.getExtensionRangeCount()==0
                        && message.getReservedRangeCount()==0 && message.getReservedNameCount()==0 && !message.hasOptions()
                        && message.getUnknownFields().asMap().isEmpty(), "each message must contain one string field");
                var field=message.getField(0);
                require(field.getName().equals(fieldName) && field.getNumber()==1 && field.getType()==DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING
                        && field.getLabel()==DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL && !field.hasOptions()
                        && !field.hasOneofIndex() && !field.hasTypeName() && (!field.hasJsonName() || field.getJsonName().equals(fieldName)) && !field.hasDefaultValue() && !field.hasExtendee() && !field.getProto3Optional()
                        && field.getUnknownFields().asMap().isEmpty(), "message must be optional string message=1");
            }
            var descriptor=Descriptors.FileDescriptor.buildFrom(proto,new Descriptors.FileDescriptor[0]);
            var method=descriptor.getServices().get(0).getMethods().get(0);
            require(!method.getInputType().equals(method.getOutputType()) && method.getInputType().getFile()==descriptor
                    && method.getOutputType().getFile()==descriptor, "request and response must be the two local messages");
            byte[] request=read(root,"fixtures/request.json",MAX_REQUEST_BYTES);
            String payload=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(request)).toString();
            JsonNode requestDoc=JSON.readTree(payload);
            require(requestDoc!=null && requestDoc.isObject() && requestDoc.size()==1 && requestDoc.path(profile.requestField).isTextual(), "request must be exactly one string message");
            if(profile==Profile.GREETER)require(requestDoc.path("name").textValue().equals("swarm-probe"),"Greeter request must be exact swarm-probe");
            require(leaseDoc.path("version").asText().equals("reviewed-grpc-lease.v1"), "unsupported lease version");
            require(bindings.get("target").equals(leaseDoc.path("endpoint").textValue()) && bindings.get("owner_run_id").equals(leaseDoc.path("owner_run_id").textValue()), "lease endpoint/run identity mismatch");
            require(digest(encoded).equals(leaseDoc.path("descriptor_sha256").textValue()) && digest(request).equals(leaseDoc.path("request_sha256").textValue()), "lease descriptor/request digest mismatch");
            require(leaseDoc.path("pid").isIntegralNumber() && leaseDoc.path("pid").canConvertToLong() && leaseDoc.path("pid").longValue()>0, "lease requires positive PID");
            Instant issue=Instant.parse(leaseDoc.path("issued_at").asText()), expiry=Instant.parse(leaseDoc.path("expires_at").asText());
            require(expiry.isAfter(issue) && Duration.between(issue,expiry).compareTo(Duration.ofMinutes(10))<=0, "lease TTL exceeds 10 minutes");
            Instant start=Instant.parse(leaseDoc.path("process_start_instant").asText());
            if (live) {
                Instant now=Instant.now();
                require(!now.isBefore(issue) && now.isBefore(expiry), "lease expired or not yet issued");
                ProcessHandle owner=ProcessHandle.of(leaseDoc.path("pid").longValue()).orElseThrow(()->new IllegalArgumentException("reviewed_grpc: owner missing"));
                require(owner.isAlive() && owner.info().startInstant().filter(start::equals).isPresent(), "owner PID/start mismatch");
                String nonce="-Dswarm.grpc.owner_nonce="+bindings.get("owner_run_id");
                require(java.util.Arrays.asList(owner.info().arguments().orElse(new String[0])).contains(nonce), "owner nonce argument mismatch");
            }
            @SuppressWarnings("unchecked") Map<String,Object> lease=JSON.convertValue(leaseDoc,Map.class);
            return new Fixture(method,payload,Map.copyOf(lease),digest(leaseBytes));
        } catch (IOException | Descriptors.DescriptorValidationException e) { throw new IllegalArgumentException("reviewed_grpc: invalid bounded fixture",e); }
    }
    private static boolean validFileOptions(DescriptorProtos.FileDescriptorProto proto,Profile profile) {
        if(!proto.hasOptions())return true;
        if(profile!=Profile.GREETER)return false;
        var expected=DescriptorProtos.FileOptions.newBuilder().setGoPackage("google.golang.org/grpc/examples/helloworld/helloworld")
                .setJavaMultipleFiles(true).setJavaPackage("io.grpc.examples.helloworld").setJavaOuterClassname("HelloWorldProto").build();
        return proto.getOptions().equals(expected);
    }
    private static byte[] read(Path root,String ref,int limit) throws IOException {
        Path file=root.toRealPath().resolve(ref);
        require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) && file.toRealPath().equals(file), "fixture must be contained regular nonsymlink file");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1);
            require(bytes.length>0 && bytes.length<=limit,"fixture exceeds bounded size"); return bytes;
        }
    }
    public static String digest(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException("reviewed_grpc: "+message);}
}
