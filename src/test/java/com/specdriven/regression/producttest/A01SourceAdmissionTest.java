package com.specdriven.regression.producttest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.specdriven.regression.provider.grpc.ReviewedA01CatalogPolicy;
import static org.junit.jupiter.api.Assertions.*;

/** Real offline handoff/closure checks. No owner, provider, RPC or live-acceptance claim. */
class A01SourceAdmissionTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final ProductTestHandoffService service = new ProductTestHandoffService(
            Clock.fixed(Instant.parse("2026-11-09T22:00:01Z"), ZoneOffset.UTC));
    private static final String PROTO_SHA = "b1f2fa42c0a9305211bf51fba7d8b5832cd3defd7b8855c327ebd5b1f0f86884";
    private static final String GO_SHA = "799971d468d1e66e0d7fce8930ce74f8f1c894bf8425c50cad44c2a9b40cd9af";

    @Test void preparesOriginalRawSourceBytesThroughCompleteHandoffWithoutExecutingThem() throws Exception {
        Path root = fixture();
        Map<String,Object> result = assertDoesNotThrow(() -> prepare(root));
        assertEquals("prepared_not_approved", result.get("status"));
        assertEquals(PROTO_SHA, digest(root.resolve("fixtures/demo.proto")));
        assertEquals(GO_SHA, digest(root.resolve("fixtures/generated-demo.pb.go")));
        var prepared = read(root.resolve("handoff/reviewed.json"));
        assertEquals(GO_SHA, file(prepared, "fixtures/generated-demo.pb.go").get("sha256"));
        assertEquals("fixture", file(prepared, "fixtures/generated-demo.pb.go").get("role"));
        assertEquals(digest(root.resolve("handoff/reviewed.json")), result.get("sha256"));
        assertFalse(Files.exists(root.resolve("runs")));
    }

    @ParameterizedTest @ValueSource(strings={"demo.proto", "generated-demo.pb.go"})
    void rejectsChangedRawBytesEvenWhenPrepareRehashesTheLocalManifest(String name) throws Exception {
        Path root=fixture();
        Files.writeString(root.resolve("fixtures/"+name), "valid: yaml\n");
        // prepare computes the matching local SHA itself; the independent upstream pin must still reject.
        blocked(root,"a01_source_digest");
    }

    @Test void doesNotGrantRawAdmissionToAnArbitraryGoExtensionOrPath() throws Exception {
        Path root=fixture();
        Files.move(root.resolve("fixtures/generated-demo.pb.go"),root.resolve("fixtures/other.go"));
        var h=read(root.resolve("review-input.json"));
        file(h,"fixtures/generated-demo.pb.go").put("path","fixtures/other.go");
        write(root.resolve("review-input.json"),h);
        blocked(root,"reviewed_file_coverage");
    }

    @Test void sourceProvenanceCannotBeRelabeledAsAnOracle() throws Exception {
        Path root=fixture();var h=read(root.resolve("review-input.json"));
        file(h,"fixtures/generated-demo.pb.go").put("role","oracle");
        write(root.resolve("review-input.json"),h);
        blocked(root,"reviewed_file_role_mismatch");
    }

    @Test void profileIdA01DoesNotAuthorizeAnotherFiniteGrpcOperation() throws Exception {
        Path root=fixture();var test=read(root.resolve("test_cases/case.yaml"));
        var input=map(map(list(test.get("execute")).get(0)).get("with"));
        input.put("grpc.service","swarm.producttest.EchoService");input.put("grpc.method","Echo");
        input.put("grpc.descriptor_ref","artifact://fixtures/echo.desc.b64");
        Files.move(root.resolve("fixtures/catalog.desc.b64"),root.resolve("fixtures/echo.desc.b64"));
        var h=read(root.resolve("review-input.json"));file(h,"fixtures/catalog.desc.b64").put("path","fixtures/echo.desc.b64");
        write(root.resolve("review-input.json"),h);write(root.resolve("test_cases/case.yaml"),test);
        blocked(root,"reviewed_file_coverage");
    }

    @ParameterizedTest @ValueSource(strings={"duplicate", "alias", "unsafe_tag"})
    void keepsSafeYamlGuardsForOrdinaryRequestFixture(String variant) throws Exception {
        Path root=fixture();String input=switch(variant) {
            case "duplicate" -> "id: first\nid: second\n";
            case "alias" -> "first: &data [one]\nsecond: *data\n";
            default -> "!!java.net.URL [https://example.invalid]\n";
        };
        Files.writeString(root.resolve("fixtures/request.json"),input);
        blocked(root,"invalid_yaml");
    }

    @Test void preservesStaticReferenceRejectionOutsidePinnedRawSources() throws Exception {
        Path root=fixture();Files.writeString(root.resolve("fixtures/request.json"),"\"env://UNREVIEWED\"\n");
        blocked(root,"unsupported_reference");
    }

    @Test void acceptsAnExactExecutionCopyWithoutChangingTheOwnerLaunchRoot() throws Exception {
        Path root=fixture(),copy=executionCopy(root);
        byte[] originalLaunch=Files.readAllBytes(root.resolve("fixtures/catalog-owner-launch.json"));
        var admitted=assertDoesNotThrow(()->ReviewedA01CatalogPolicy.fixture(copy,bindings(),false));
        assertEquals("hipstershop.ProductCatalogService",admitted.method().getService().getFullName());
        assertArrayEquals(originalLaunch,Files.readAllBytes(copy.resolve("fixtures/catalog-owner-launch.json")));
        assertEquals(root.toString(),read(copy.resolve("fixtures/catalog-owner-launch.json")).get("suite_root"));
        assertDoesNotThrow(()->prepare(root)); // Public prepare also materializes and checks its private snapshot.
    }

    @ParameterizedTest @ValueSource(strings={"original", "snapshot"})
    void rejectsDivergenceBetweenOriginalAndExecutionCopy(String changed) throws Exception {
        Path root=fixture(),copy=executionCopy(root);
        Path target=changed.equals("original")?root:copy;
        Files.writeString(target.resolve("fixtures/products.json"),"{\"changed\":true}\n");
        var e=assertThrows(IllegalArgumentException.class,()->ReviewedA01CatalogPolicy.fixture(copy,bindings(),false));
        assertTrue(e.getMessage().contains("execution copy differs"),e.getMessage());
    }

    @Test void rejectsAnAbsentDeclaredOwnerRootRatherThanAuthenticatingTheCopyName() throws Exception {
        Path root=fixture();rebindLaunchRoot(root,temp.resolve("missing-owner-root"));
        Path copy=executionCopy(root);
        var e=assertThrows(IllegalArgumentException.class,()->ReviewedA01CatalogPolicy.fixture(copy,bindings(),false));
        assertTrue(e.getMessage().contains("source root"),e.getMessage());
    }

    @Test void rejectsSymlinkedOriginalAndExecutionRoots() throws Exception {
        Path root=fixture(),copy=executionCopy(root),alias=temp.resolve("copy-link");
        Files.createSymbolicLink(alias,copy);
        var executionError=assertThrows(IllegalArgumentException.class,()->ReviewedA01CatalogPolicy.fixture(alias,bindings(),false));
        assertTrue(executionError.getMessage().contains("execution root"),executionError.getMessage());
        Path sourceAlias=temp.resolve("source-link");Files.createSymbolicLink(sourceAlias,root);
        rebindLaunchRoot(root,sourceAlias);Path secondCopy=executionCopy(root);
        var sourceError=assertThrows(IllegalArgumentException.class,()->ReviewedA01CatalogPolicy.fixture(secondCopy,bindings(),false));
        assertTrue(sourceError.getMessage().contains("source root"),sourceError.getMessage());
    }

    @Test void publicSnapshotStillRequiresLiveAdmissionAfterOfflinePreparation() throws Exception {
        Path root=fixture();var lease=read(root.resolve("fixtures/lease.json"));
        lease.put("issued_at","2000-01-01T00:00:00Z");lease.put("expires_at","2000-01-01T00:01:00Z");
        map(lease.get("supervisor")).put("process_start_instant","1999-12-31T23:59:59Z");
        map(lease.get("child")).put("process_start_instant","1999-12-31T23:59:59Z");
        write(root.resolve("fixtures/lease.json"),lease);
        assertDoesNotThrow(()->prepare(root));
        String handoffPin=digest(root.resolve("handoff/reviewed.json")),leasePin=digest(root.resolve("fixtures/lease.json"));
        var e=assertThrows(IllegalArgumentException.class,()->service.snapshot(root.resolve("suite_manifest.yaml"),"a01",new ProductTestHandoffService.InvocationRequirement(handoffPin,leasePin)));
        assertTrue(e.getMessage().contains("outside live interval"),e.getMessage());
    }

    private Map<String,Object> bindings(){return Map.of("target","grpc://127.0.0.1:3550","owner_run_id","1".repeat(32));}
    private Path executionCopy(Path source)throws Exception {
        Path copy=Files.createTempDirectory(temp,"private-copy-").toRealPath();Files.createDirectory(copy.resolve("fixtures"));
        for(String name:List.of("demo.proto","generated-demo.pb.go","products.json","catalog-source.json","catalog-runtime.json","catalog-owner-launch.json","lease.json","request.json","catalog.desc.b64"))
            Files.copy(source.resolve("fixtures/"+name),copy.resolve("fixtures/"+name));
        return copy;
    }
    private void rebindLaunchRoot(Path root,Path declaredRoot)throws Exception {
        var launch=read(root.resolve("fixtures/catalog-owner-launch.json"));launch.put("suite_root",declaredRoot.toString());
        byte[] launchBytes=json.writeValueAsBytes(launch);String launchSha=hash(launchBytes);
        Files.write(root.resolve("fixtures/catalog-owner-launch.json"),launchBytes);
        var runtime=read(root.resolve("fixtures/catalog-runtime.json"));runtime.put("owner_launch_sha256",launchSha);write(root.resolve("fixtures/catalog-runtime.json"),runtime);
        var lease=read(root.resolve("fixtures/lease.json"));lease.put("owner_launch",launch);lease.put("owner_launch_sha256",launchSha);lease.put("runtime_manifest_sha256",digest(root.resolve("fixtures/catalog-runtime.json")));write(root.resolve("fixtures/lease.json"),lease);
    }

    private Map<String,Object> prepare(Path root) {
        return service.prepare(root.resolve("suite_manifest.yaml"),"a01",root.resolve("review-input.json"),null,root.resolve("handoff/reviewed.json"));
    }
    private void blocked(Path root,String reason) {
        var e=assertThrows(IllegalArgumentException.class,()->prepare(root));
        assertTrue(e.getMessage().contains(reason),e.getMessage());
        assertFalse(Files.exists(root.resolve("handoff/reviewed.json")));
    }
    private Path fixture() throws Exception {
        Path root=Files.createTempDirectory(temp,"suite-").toRealPath();
        for(String folder:List.of("fixtures","test_cases","env_profiles","contracts","handoff"))Files.createDirectory(root.resolve(folder));
        for(String name:List.of("demo.proto","generated-demo.pb.go","products.json","catalog.desc.b64","request.json")) {
            try(var in=getClass().getResourceAsStream("/a01/fixtures/"+name)) {
                assertNotNull(in);Files.copy(in,root.resolve("fixtures/"+name));
            }
        }
        ObjectNode f;
        try(var in=getClass().getResourceAsStream("/a01/catalog-proof-contract.json")){assertNotNull(in);f=(ObjectNode)json.readTree(in);}
        ObjectNode launch=(ObjectNode)f.path("lease").path("owner_launch");launch.put("suite_root",root.toString());
        byte[] launchBytes=json.writeValueAsBytes(launch);String launchSha=hash(launchBytes);
        ((ObjectNode)f.path("lease")).put("owner_launch_sha256",launchSha);
        ((ObjectNode)f.path("runtime")).put("owner_launch_sha256",launchSha);
        byte[] runtime=json.writeValueAsBytes(f.path("runtime")),source=json.writeValueAsBytes(f.path("source"));
        ((ObjectNode)f.path("lease")).put("runtime_manifest_sha256",hash(runtime)).put("source_manifest_sha256",hash(source));
        Files.write(root.resolve("fixtures/catalog-owner-launch.json"),launchBytes);
        Files.write(root.resolve("fixtures/catalog-runtime.json"),runtime);
        Files.write(root.resolve("fixtures/catalog-source.json"),source);
        Files.write(root.resolve("fixtures/lease.json"),json.writeValueAsBytes(f.path("lease")));
        Map<String,Object> policy=Map.of("contract_version","product-test.v1","dependency_modes",Map.of(),"environment_stage","local","knowledge_mode","not_used","provider_modes",Map.of("catalog","real"),"reviewed_handoff_ref","handoff/reviewed.json","test_level","integration","test_type","regression");
        write(root.resolve("env_profiles/a01.yaml"),Map.of("profile_id","a01","execution_mode","local","isolation","per_run","evidence_classification","framework_verification_only","downstream_release_evidence",false,"product_test",policy,"targets",Map.of("catalog",Map.of("runtime_mode","native","bindings",Map.of("target","grpc://127.0.0.1:3550","owner_run_id","1".repeat(32))))));
        write(root.resolve("suite_manifest.yaml"),Map.of("manifest_version","v0.3","suite_id","A01","default_profile","a01","artifact_roots",Map.of("fixtures","fixtures/"),"env_profiles",Map.of("a01",Map.of("ref","env_profiles/a01.yaml")),"targets",Map.of("catalog",Map.of("provider_contract","grpc_client.v0.3")),"tests",List.of(Map.of("ref","test_cases/case.yaml"))));
        write(root.resolve("test_cases/case.yaml"),Map.of("dsl_version","v0.3","test_case_id","TC-A01-SG01-NORMAL","title","Offline source provenance admission","execute",List.of(Map.of("id","get_product","op","unary_call","target","catalog","with",Map.of("grpc.service","hipstershop.ProductCatalogService","grpc.method","GetProduct","grpc.descriptor_ref","artifact://fixtures/catalog.desc.b64","grpc.message","artifact://fixtures/request.json","grpc.timeout","PT5S"))),"verify",List.of(Map.of("id","status_oracle","type","assertion","assert",Map.of("actual","step://get_product/response.status","operator","equals","expected","OK")))));
        // Handoff admission validates these contract identity fields; this test never runs a provider.
        write(root.resolve("contracts/grpc.yaml"),Map.of("provider_contract","grpc_client.v0.3","provider_type","grpc_client","contract_version","v0.3"));
        var entries=new ArrayList<Map<String,Object>>();
        for(var pair:Map.of("suite_manifest.yaml","manifest","env_profiles/a01.yaml","profile","test_cases/case.yaml","test").entrySet())
            entries.add(new LinkedHashMap<>(Map.of("path",pair.getKey(),"role",pair.getValue())));
        for(String name:List.of("demo.proto","generated-demo.pb.go","products.json","catalog.desc.b64","request.json","catalog-source.json","catalog-runtime.json","catalog-owner-launch.json","lease.json"))
            entries.add(new LinkedHashMap<>(Map.of("path","fixtures/"+name,"role","fixture")));
        var h=new LinkedHashMap<String,Object>();
        h.put("contract_version","product-test-reviewed-handoff.v1");h.put("framework_baseline","0.3.2");h.put("review_ref","a01-source-admission-unit");h.put("reviewed_by","independent-unit-oracle");
        h.put("reviewed_at","2026-11-09T22:00:00Z");h.put("valid_until","2026-11-09T23:00:00Z");h.put("workspace_ref","owned-unit-temp");h.put("project_ref","a01-offline-unit");
        h.put("repository_revisions",Map.of("Auto_Regression_Test_Framework","6d65b300a673adac70821fd53206dde96b399dfe","microservices-demo","c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712"));
        h.put("swarm_source",Map.of("base_revision","6d65b300a673adac70821fd53206dde96b399dfe","files",Map.of("fixtures/demo.proto",PROTO_SHA,"fixtures/generated-demo.pb.go",GO_SHA)));
        h.put("reviewed_files",entries);h.put("reviewed_contracts",List.of(Map.of("path","contracts/grpc.yaml","provider_contract","grpc_client.v0.3")));
        h.put("requirement_mapping",List.of(Map.of("requirement_ref","A01-SG01","review_ref","a01-source-admission-unit","test_case_ids",List.of("TC-A01-SG01-NORMAL"),"oracle_refs",List.of("test_cases/case.yaml#status_oracle"))));
        write(root.resolve("review-input.json"),h);return root;
    }
    private Map<String,Object> read(Path path)throws Exception{return json.readValue(Files.readAllBytes(path),Map.class);}
    private void write(Path path,Object value)throws Exception{Files.write(path,json.writeValueAsBytes(value));}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String digest(Path path)throws Exception{return hash(Files.readAllBytes(path));}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return (List<Object>)value;}
    private static Map<String,Object> file(Map<String,Object> h,String path){return list(h.get("reviewed_files")).stream().map(A01SourceAdmissionTest::map).filter(f->path.equals(f.get("path"))).findFirst().orElseThrow();}
}
