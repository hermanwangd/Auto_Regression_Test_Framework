package com.specdriven.regression.provider.grpc;

import com.google.protobuf.Descriptors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** A separate offline admission capability. It does not authorize an RPC or a lease. */
public final class ReviewedGrpcDescriptorPolicy {
    public static final String CONTRACT = "grpc_descriptor_admission.v0.3";
    public static final String TYPE = "grpc_descriptor_admission";
    public static final String SOURCE_SHA = "9557ee6d002e4b2ccfb48324c30304f40bfd0922cb096142ac87c4c06c69cb21";
    public static final String DESCRIPTOR_SHA = "e2a26040ccdbb5edf9c250074ab3fdb5e4188cdd8a868e1752a0bd4c6165ad0f";
    public static final String ENCODED_SHA = "81429e989f7d6a6b7474c5dfbd453c4cde7694a041158c433f205a81c10021a8";
    public static final String REQUEST_SHA = "1b0882758a0cee8a359fea127c7dbda94126e2f6a9595c8e3832af1c25eb8471";
    public static final String SOURCE_REVISION = "a3f7fd0c1fd788871c460088446099700f6679e4";
    public static final Map<String,String> FILES = Map.of("fixtures/service.proto", SOURCE_SHA,
            "fixtures/service.desc.b64", ENCODED_SHA, "fixtures/request.json", REQUEST_SHA);
    private static final Map<String,Object> INPUTS = Map.of("grpc.service", "service.Test", "grpc.method", "GetThingVersions",
            "grpc.descriptor_ref", "artifact://fixtures/service.desc.b64", "grpc.message", "artifact://fixtures/request.json");
    private ReviewedGrpcDescriptorPolicy() {}
    public static void validateBindings(Map<String,?> bindings) {
        require(Map.of("admission_mode", "descriptor_only", "source_revision", SOURCE_REVISION).equals(bindings),
                "only exact descriptor_only/source_revision bindings are allowed; endpoint/owner/lease are prohibited");
    }
    public static void validateInputs(Map<String,?> inputs) {
        require(INPUTS.equals(inputs), "only the pinned P04 server-stream descriptor/request operation is allowed");
    }
    public static Descriptors.MethodDescriptor fixture(Path root, Map<String,?> bindings) {
        validateBindings(bindings);
        try {
            for (var entry : FILES.entrySet()) require(digest(read(root, entry.getKey(), 4096)).equals(entry.getValue()), "fixture digest differs: " + entry.getKey());
            require(!Files.exists(root.resolve("fixtures/lease.json"), LinkOption.NOFOLLOW_LINKS), "lease is forbidden for descriptor-only admission");
            byte[] encoded=read(root, "fixtures/service.desc.b64", 1024);
            String base64=new String(encoded, StandardCharsets.US_ASCII);
            require(base64.matches("[A-Za-z0-9+/]*={0,2}"), "descriptor must be strict base64");
            byte[] bytes=Base64.getDecoder().decode(base64);
            require(bytes.length==587 && digest(bytes).equals(DESCRIPTOR_SHA), "descriptor identity differs");
            var method=GrpcDescriptorSupport.method(bytes, "service.Test", "GetThingVersions");
            require(!method.isClientStreaming() && method.isServerStreaming()
                    && method.getInputType().getFullName().equals("service.GetThingRequest")
                    && method.getOutputType().getFullName().equals("service.GetThingResponse"), "descriptor cardinality/type differs");
            return method;
        } catch (IOException error) { throw new IllegalArgumentException("reviewed_descriptor: fixture unavailable", error); }
    }
    private static byte[] read(Path root, String relative, int limit) throws IOException {
        Path base=root.toRealPath(), file=base.resolve(relative);
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
                && file.toRealPath().equals(file) && ((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,
                "fixture must be a contained single-link regular file");
        try(var input=Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=input.readNBytes(limit+1); require(bytes.length>0 && bytes.length<=limit,"fixture exceeds bound"); return bytes;
        }
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void require(boolean condition,String reason) { if(!condition)throw new IllegalArgumentException("reviewed_descriptor: "+reason); }
}
