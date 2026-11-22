package com.specdriven.regression.provider.grpc;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class A01CatalogProfileTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    static Map<String,Object> input(String service,String method,String descriptor) {
        return Map.of("grpc.service",service,"grpc.method",method,"grpc.descriptor_ref","artifact://fixtures/"+descriptor,
            "grpc.message","artifact://fixtures/request.json","grpc.timeout","PT5S");
    }
    @Test void admitsOnlyExactCatalogOperation() {
        assertDoesNotThrow(()->ReviewedLoopbackGrpcPolicy.validateInputs(input("hipstershop.ProductCatalogService","GetProduct","catalog.desc.b64")));
        assertThrows(IllegalArgumentException.class,()->ReviewedLoopbackGrpcPolicy.validateInputs(input("hipstershop.CartService","AddItem","catalog.desc.b64")));
        assertThrows(IllegalArgumentException.class,()->ReviewedLoopbackGrpcPolicy.validateInputs(input("hipstershop.ProductCatalogService","ListProducts","catalog.desc.b64")));
        assertThrows(IllegalArgumentException.class,()->ReviewedLoopbackGrpcPolicy.validateInputs(input("hipstershop.ProductCatalogService","GetProduct","echo.desc.b64")));
    }
    @Test void preservesOriginalFiniteProfiles() {
        assertDoesNotThrow(()->ReviewedLoopbackGrpcPolicy.validateInputs(input("routeguide.RouteGuide","GetFeature","routeguide.desc.b64")));
        assertDoesNotThrow(()->ReviewedLoopbackGrpcPolicy.validateInputs(input("service.Test","DoThing","service.desc.b64")));
        assertDoesNotThrow(()->ReviewedLoopbackGrpcPolicy.validateInputs(input("helloworld.Greeter","SayHello","greeter.desc.b64")));
        assertDoesNotThrow(()->ReviewedLoopbackGrpcPolicy.validateInputs(input("swarm.producttest.EchoService","Echo","echo.desc.b64")));
    }
    private java.nio.file.Path fixture() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.JsonNode f;
        try(var in=getClass().getResourceAsStream("/a01/catalog-proof-contract.json")){f=json.readTree(in);}
        var root=temporary.resolve("suite");java.nio.file.Files.createDirectories(root.resolve("fixtures"));
        for(String file:java.util.List.of("demo.proto","generated-demo.pb.go","products.json","catalog.desc.b64","request.json"))
            try(var in=getClass().getResourceAsStream("/a01/fixtures/"+file)){assertNotNull(in);java.nio.file.Files.copy(in,root.resolve("fixtures/"+file));}
        var launch=(com.fasterxml.jackson.databind.node.ObjectNode)f.path("lease").path("owner_launch");launch.put("suite_root",root.toRealPath().toString());
        byte[] launchBytes=json.writeValueAsBytes(launch);String launchSha=ReviewedA01CatalogPolicy.hash(launchBytes);
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.path("lease")).put("owner_launch_sha256",launchSha);
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.path("runtime")).put("owner_launch_sha256",launchSha);
        byte[] runtimeBytes=json.writeValueAsBytes(f.path("runtime")),sourceBytes=json.writeValueAsBytes(f.path("source"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.path("lease")).put("runtime_manifest_sha256",ReviewedA01CatalogPolicy.hash(runtimeBytes)).put("source_manifest_sha256",ReviewedA01CatalogPolicy.hash(sourceBytes));
        java.nio.file.Files.write(root.resolve("fixtures/catalog-owner-launch.json"),launchBytes);
        for(var pair:Map.of("source","catalog-source.json","runtime","catalog-runtime.json","lease","lease.json").entrySet())
            java.nio.file.Files.write(root.resolve("fixtures/"+pair.getValue()),json.writeValueAsBytes(f.path(pair.getKey())));
        return root;
    }
    private Map<String,Object> bindings(){return Map.of("target","grpc://127.0.0.1:3550","owner_run_id","1".repeat(32));}
    @Test void selectsActualCatalogMethodFromFullGeneratedSchemaWithoutLiveAcceptance() throws Exception {
        var result=ReviewedLoopbackGrpcPolicy.fixture(fixture(),bindings(),false);
        assertEquals("hipstershop.ProductCatalogService",result.method().getService().getFullName());assertEquals("GetProduct",result.method().getName());
        assertEquals("hipstershop.Product",result.method().getOutputType().getFullName());
        assertEquals("github.com/GoogleCloudPlatform/miscroservices-demo/hipstershop",result.method().getFile().getOptions().getGoPackage());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"demo.proto","generated-demo.pb.go","products.json","catalog.desc.b64","request.json"})
    void rejectsChangedFixtureBytesBeforeLiveObservation(String file)throws Exception {
        var root=fixture();java.nio.file.Files.writeString(root.resolve("fixtures/"+file),"changed");
        assertThrows(IllegalArgumentException.class,()->ReviewedLoopbackGrpcPolicy.fixture(root,bindings(),false));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"nonce","parent","child_executable","cwd","listener","namespace","request_pin","extra"})
    void rejectsWrongLeaseIdentityBeforeLiveObservation(String variant)throws Exception {
        var root=fixture();var json=new com.fasterxml.jackson.databind.ObjectMapper();var path=root.resolve("fixtures/lease.json");var lease=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(path.toFile());
        switch(variant) {
            case "nonce" -> ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("supervisor")).put("nonce_argument","wrong");
            case "parent" -> ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("child")).put("ppid",999);
            case "child_executable" -> ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("child")).put("executable_sha256","c".repeat(64));
            case "cwd" -> ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("child")).put("cwd","/tmp/wrong");
            case "listener" -> ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("listener")).put("address","0.0.0.0");
            case "namespace" -> ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("listener")).put("network_namespace_inode",0);
            case "request_pin" -> lease.put("request_sha256","c".repeat(64));
            default -> lease.put("extra",true);
        }
        json.writeValue(path.toFile(),lease);assertThrows(IllegalArgumentException.class,()->ReviewedLoopbackGrpcPolicy.fixture(root,bindings(),false));
    }
    @Test void rejectsExpiredLiveLeaseBeforeAnyProcessObservation()throws Exception {
        var root=fixture();var json=new com.fasterxml.jackson.databind.ObjectMapper();var path=root.resolve("fixtures/lease.json");var lease=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(path.toFile());
        lease.put("issued_at","2000-01-01T00:00:00Z");lease.put("expires_at","2000-01-01T00:01:00Z");
        ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("supervisor")).put("process_start_instant","1999-12-31T23:59:59Z");
        ((com.fasterxml.jackson.databind.node.ObjectNode)lease.path("child")).put("process_start_instant","1999-12-31T23:59:59Z");json.writeValue(path.toFile(),lease);
        var failure=assertThrows(IllegalArgumentException.class,()->ReviewedLoopbackGrpcPolicy.fixture(root,bindings(),true));assertTrue(failure.getMessage().contains("outside live interval"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"agent","bootclasspath","classpath","main","nonce","extra"})
    void rejectsUnreviewedOwnerLaunchArguments(String mutation)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();com.fasterxml.jackson.databind.JsonNode f;
        try(var in=getClass().getResourceAsStream("/a01/catalog-proof-contract.json")){f=json.readTree(in);}
        var lease=f.path("lease");String pin=lease.path("owner_launch_sha256").asText();var launch=lease.path("owner_launch");
        var args=new java.util.ArrayList<>(java.util.List.of("-Xmx256m","-XX:MaxMetaspaceSize=128m","-Dswarm.grpc.owner_nonce="+lease.path("owner_run_id").asText(),"-Dswarm.grpc.a01.owner_launch_sha256="+pin,"-cp",launch.path("classpath").get(0).path("path").asText(),"com.specdriven.regression.provider.grpc.A01CatalogOwner",launch.path("suite_root").asText(),lease.path("runtime_manifest_sha256").asText(),lease.path("source_manifest_sha256").asText(),lease.path("build_manifest_sha256").asText()));
        assertDoesNotThrow(()->ReviewedA01CatalogPolicy.validateArguments(args.toArray(String[]::new),lease,pin));
        switch(mutation){case "agent" -> args.add(0,"-javaagent:/tmp/other.jar");case "bootclasspath" -> args.add(0,"-Xbootclasspath/a:/tmp/other.jar");case "classpath" -> args.set(5,"/tmp/same-FQCN-unreviewed.jar");case "main" -> args.set(6,"other.Main");case "nonce" -> args.set(2,"-Dswarm.grpc.owner_nonce=wrong");default -> args.add("extra");}
        assertThrows(IllegalArgumentException.class,()->ReviewedA01CatalogPolicy.validateArguments(args.toArray(String[]::new),lease,pin));
    }
}
