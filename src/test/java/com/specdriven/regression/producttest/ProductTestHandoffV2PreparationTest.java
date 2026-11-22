package com.specdriven.regression.producttest;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Field dispatch is tested before capability rejection; no native provider or receipt is substituted. */
class ProductTestHandoffV2PreparationTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    private Path review(String version)throws Exception{
        Path source=Path.of("samples/41-product-testing/reviewed_local");try(var files=Files.walk(source)){for(Path p:files.toList()){Path dest=root.resolve(source.relativize(p));if(Files.isDirectory(p))Files.createDirectories(dest);else Files.copy(p,dest);}}
        Path input=root.resolve("review-input.json");Map<String,Object>h=json.readValue(input.toFile(),LinkedHashMap.class);h.put("contract_version",version);h.put("knowledge_selection_proof",Map.of("path","handoff/proof.json","sha256","a".repeat(64)));h.put("expected_sut_revisions",Map.of("fixture:sut","fixture:revision"));h.put("sut_revision_evidence",Map.of("path","handoff/runtime.json","sha256","b".repeat(64),"sut_id","fixture:sut"));json.writeValue(input.toFile(),h);return input;
    }
    private void prepare(Path review){new ProductTestHandoffService(Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC)).prepare(root.resolve("suite_manifest.yaml"),"local_v03",review,null,root.resolve("handoff/reviewed-tests.json"));}
    @Test void v2FieldsReachTheExistingStrictNativeRequiredCapabilityGate()throws Exception{
        Path input=review("product-test-reviewed-handoff.v2");assertThatThrownBy(()->prepare(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v2_capability").hasMessageNotContaining("unknown field");
    }
    @Test void legacyV1StillRejectsEveryV2OnlyField()throws Exception{
        Path input=review("product-test-reviewed-handoff.v1");assertThatThrownBy(()->prepare(input)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("review_input").hasMessageContaining("unknown field");
    }

    /** Reuses the existing finite REST fixture's source builder only; no server/runtime/provider is invoked. */
    private Path parserFixture()throws Exception {
        Class<?> type=Class.forName("com.specdriven.regression.contract.v03.ReviewedLoopbackRestTest");
        var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object fixture=ctor.newInstance();
        var field=type.getDeclaredField("temporary");field.setAccessible(true);field.set(fixture,root);
        var method=type.getDeclaredMethod("fixture",String.class,String.class,boolean.class);method.setAccessible(true);
        return (Path)method.invoke(fixture,"http://127.0.0.1:1","GET",true);
    }
    private ProductTestHandoffService.Snapshot parserSnapshot(Path suite)throws Exception {
        var service=new ProductTestHandoffService(Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"),ZoneOffset.UTC));
        service.prepare(suite.resolve("suite_manifest.yaml"),"local_native",suite.resolve("review-input.json"),null,suite.resolve("handoff/reviewed.json"));
        return service.snapshot(suite.resolve("suite_manifest.yaml"),"local_native",new ProductTestHandoffService.InvocationRequirement(Files.readString(suite.resolve("handoff/reviewed.json.sha256")).trim()));
    }
    @Test void parserExportsExplicitOperandEdgeWithoutChangingLegacyOracleSelectionOrRunningAnything()throws Exception {
        Path suite=parserFixture();try(var snapshot=parserSnapshot(suite)) {
            Map<String,Object> derived=parserMetadata(suite,snapshot);
            var relation=json.valueToTree(derived);
            assertThat(relation.path("state").asText()).isEqualTo("COMPLETE");
            assertThat(relation.path("assertions").size()).isEqualTo(2);
            var literal=relation.path("assertions").get(0);var ref=relation.path("assertions").get(1);
            assertThat(literal.path("expected_operand").asText()).isEqualTo("EXPECTED");
            assertThat(literal.path("raw_expected_ref").isNull()).isTrue();
            assertThat(ref.path("assertion_id").asText()).isEqualTo("exact_items");
            assertThat(ref.path("raw_expected_ref").asText()).isEqualTo("artifact://expected/items.json");
            assertThat(ref.path("normalized_expected_path").asText()).isEqualTo("expected/items.json");
            assertThat(ref.path("case_sha256").asText()).isEqualTo(hash(suite.resolve("test_cases/items.yaml")));
            assertThat(ref.path("expected_artifact_sha256").asText()).isEqualTo(hash(suite.resolve("expected/items.json")));
            assertThat(relation.path("manifest_sha256").asText()).isEqualTo(hash(suite.resolve("suite_manifest.yaml")));
            assertThat(snapshot.provenance().get("requirement_mapping").toString()).contains("items.yaml#status_ok").doesNotContain("#exact_items");
            assertThat(ref.toString()).doesNotContain("completed", "public fixture", "response.body", "\"expected\":");
            assertThat(Files.exists(suite.resolve("runs"))).isFalse();
            Map<String,Object> exported=derived;
            assertThatThrownBy(()->exported.put("state","TAMPER")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /** Direct actual closure/export unit boundary, not proof of whole v2 producer admission. */
    private Map<String,Object> parserMetadata(Path suite,ProductTestHandoffService.Snapshot snapshot)throws Exception {return parserMetadata(suite,snapshot,2,false);}
    private Map<String,Object> parserMetadata(Path suite,ProductTestHandoffService.Snapshot snapshot,int count,boolean longIds)throws Exception {
        Class<?> service=ProductTestHandoffService.class;
        Class<?> inputs=Class.forName(service.getName()+"$Inputs"),selection=Class.forName(service.getName()+"$Selection"),entry=Class.forName(service.getName()+"$FileEntry");
        var constructor=inputs.getDeclaredConstructor(Path.class);constructor.setAccessible(true);Object in=constructor.newInstance(suite.resolve("suite_manifest.yaml"));
        var select=service.getDeclaredMethod("select",inputs,String.class);select.setAccessible(true);Object selected=select.invoke(null,in,"local_native");
        var create=entry.getDeclaredConstructor(String.class,String.class,byte[].class);create.setAccessible(true);Map<String,Object> files=new LinkedHashMap<>();
        var provenance=json.valueToTree(snapshot.provenance());List<Map<String,Object>> bound=new ArrayList<>();String repo="fixture:product",a="a".repeat(40);
        for(var file:provenance.path("reviewed_files")) {
            String path=file.path("path").asText(),role=file.path("role").asText(),id=role.equals("test")?"TC-HTTP":role.toUpperCase(Locale.ROOT);
            files.put(path,create.newInstance(path,role,Files.readAllBytes(suite.resolve(path))));
            bound.add(Map.of("reviewed_path",path,"role",role.equals("test")?"case":role,"sha256",hash(suite.resolve(path)),"artifact_id",repo+"@"+a+":tests/sit/"+path+"#"+id));
        }
        var closure=service.getDeclaredMethod("validateClosure",inputs,selection,Map.class,Set.class);closure.setAccessible(true);Object validated=closure.invoke(null,in,selected,files,Set.of("rest_client.v0.3"));
        if(count>2) {
            var accessor=validated.getClass().getDeclaredMethod("parserAssertions");accessor.setAccessible(true);Object collector=accessor.invoke(validated);
            var add=collector.getClass().getDeclaredMethod("add",String.class,String.class,String.class,String.class,String.class,Map.class,Map.class);add.setAccessible(true);
            for(int i=2;i<count;i++)add.invoke(collector,"test_cases/items.yaml",hash(suite.resolve("test_cases/items.yaml")),"TC-HTTP","extra_"+i+(longIds?"x".repeat(900):""),"equals",Map.of("expected",200),Map.of("expected","expected/"));
        }
        java.lang.reflect.Method export;
        try { export=service.getDeclaredMethod("parserRelations",String.class,validated.getClass(),Map.class,Map.class); }
        catch(NoSuchMethodException absent) {return Map.of();} // Baseline has no exported relationship.
        export.setAccessible(true);return (Map<String,Object>)export.invoke(null,"suite_manifest.yaml",validated,files,Map.of("executed_source_bindings",bound));
    }

    /** PUBLIC_SYNTHETIC current contract input: real prepare/import/closure, no provider, agent or live PK. */
    private Path v2SourceFixture()throws Exception {
        Path suite=parserFixture();Files.createDirectories(suite.resolve("cases"));Files.createDirectories(suite.resolve("config"));Files.createDirectories(suite.resolve("data"));
        Files.writeString(suite.resolve("suite_manifest.yaml"),"{\n  \"manifest_version\": \"v0.3\",\n  \"suite_id\": \"P2-PUBLIC-TOTAL\",\n  \"default_profile\": \"local\",\n  \"artifact_roots\": {\n    \"expected_results\": \"data/\",\n    \"fixtures\": \"data/\"\n  },\n  \"targets\": {\n    \"public_api\": {\n      \"provider_contract\": \"rest_client.v0.3\"\n    }\n  },\n  \"env_profiles\": {\n    \"local\": {\n      \"ref\": \"config/local.yaml\"\n    }\n  },\n  \"tests\": [\n    {\n      \"ref\": \"cases/total.yaml\"\n    }\n  ]\n}\n");
        Files.writeString(suite.resolve("cases/total.yaml"),"dsl_version: v0.3\ntest_case_id: P2-TOTAL-1\ntitle: Independently frozen total of the public 5 and 7 values\nexecute:\n  - id: total\n    target: public_api\n    op: http_request\n    with:\n      request.method: GET\n      request.path: /total\n      request.timeout: PT3S\nverify:\n  - id: status\n    type: assertion\n    assert:\n      actual: step://total/response.status\n      operator: equals\n      expected: 200\n  - id: total_oracle\n    type: assertion\n    assert:\n      actual: step://total/response.body\n      operator: json_match\n      expected_ref: artifact://expected_results/independent-oracle.json\n");
        Files.writeString(suite.resolve("data/independent-oracle.json"),"{\"total\":12}\n");
        var yaml=new org.yaml.snakeyaml.Yaml();Map<String,Object> profile=yaml.load(Files.readString(suite.resolve("env_profiles/local_native.yaml")));
        profile.put("profile_id","local");profile.put("targets",Map.of("public_api",Map.of("runtime_mode","native","bindings",Map.of("base_url","http://127.0.0.1:1"))));
        Map<String,Object> policy=(Map<String,Object>)profile.get("product_test");policy.put("knowledge_mode","required");policy.put("provider_modes",Map.of("public_api","real"));
        Files.writeString(suite.resolve("config/local.yaml"),yaml.dump(profile));
        Map<String,Object> h=json.readValue(suite.resolve("review-input.json").toFile(),LinkedHashMap.class);h.remove("suite_id");h.remove("profile");
        String repo="fixture:product",pk="fixture:pk",a="a".repeat(40),x="b".repeat(40),p="c".repeat(40),req=repo+"@"+a+":spec.md#REQ1",caseRef=repo+"@"+a+":tests/sit/cases/total.yaml#P2-TOTAL-1",oracleRef=repo+"@"+a+":tests/sit/data/independent-oracle.json#ORACLE1",pkRef=pk+"@"+p+":knowledge.md#PK1";
        String review="2026-11-07T00:00:00Z",expiry="2026-11-07T00:05:00Z";
        h.put("contract_version","product-test-reviewed-handoff.v2");h.put("knowledge_mode","required");h.put("workspace_ref","fixture-workspace");h.put("project_ref","fixture-project");h.put("reviewed_at",review);h.put("valid_until",expiry);h.put("repository_revisions",Map.of(repo,x,pk,p));
        h.put("cognee_source",Map.of("version","1.6.2","commit","ba3631f2ed363a6ea50d649c34c56885af6b36fe","runtime_version_verified",false));
        List<Map<String,Object>> files=List.of(row("path","suite_manifest.yaml","role","manifest"),row("path","config/local.yaml","role","profile"),row("path","cases/total.yaml","role","test"),row("path","data/independent-oracle.json","role","oracle"));h.put("reviewed_files",files);
        h.put("requirement_mapping",List.of(row("requirement_ref",req,"test_case_ids",List.of("P2-TOTAL-1"),"oracle_refs",List.of("cases/total.yaml#status","data/independent-oracle.json"),"review_ref",h.get("review_ref"))));
        Map<String,Object> scope=row("mission_ref","fixture:mission","workspace_ref","fixture-workspace","knowledge_project_ref","fixture:knowledge-project","consumer_project_ref","fixture-project","repository_revisions",h.get("repository_revisions"),"requirement_refs",List.of(req));
        Map<String,Object> selected=row("qualified_source_ref",pkRef,"governed_record_key","fixture:record","record_version",1,"provider_revision","fixture:provider","source_ref","fixture:source","content_sha256","4".repeat(64),"owner_ref","fixture:pk-owner","knowledge_ref","fixture:knowledge","proposal_digest","5".repeat(64),"decision_ref","fixture:decision","decision_record_key","fixture:record","decision_record_version",1,"decision_proposal_digest","5".repeat(64),"observed_at",review,"valid_until",expiry,"document_id","fixture:document","chunk_count",1,"dataset_id","00000000-0000-7000-8000-000000000001");
        Map<String,Object> proof=row("schema_version","knowledge-selection-proof.v1","private_original_receipt_sha256","1".repeat(64),"selection_receipt_sha256","2".repeat(64),"source_snapshot_sha256","2".repeat(64),"candidate_search_sha256","2".repeat(64),"query_sha256","2".repeat(64),"context_sha256","2".repeat(64),"provider_version","UNVERIFIED_BY_HTTP_CLIENT","scope",scope,"fetched_at",review,"evaluated_at",review,"valid_until",expiry,"selected",List.of(selected),"excluded",List.of());
        h.put("preparation_provenance",row("mission_ref","fixture:mission","request_ref","fixture:request","sut_run_ref","fixture:sut-run","private_original_receipt_sha256","1".repeat(64),"context_sha256","2".repeat(64),"proposal_sha256","2".repeat(64),"source_binding_version","runner-source-bindings.v1","classification","framework_verification_only"));
        List<Map<String,Object>> artifacts=new ArrayList<>(),bindings=new ArrayList<>(),all=new ArrayList<>(files);all.add(row("path","contracts/rest_client_v0_3.yaml","role","contract"));
        for(Map<String,Object> file:all){String path=(String)file.get("path"),role=(String)file.get("role"),id=role.equals("test")?"P2-TOTAL-1":role.equals("oracle")?"ORACLE1":role.toUpperCase(Locale.ROOT),kind=switch(role){case "test"->"case";case "manifest"->"suite";case "profile"->"config_template";case "contract"->"provider_contract";default->role;},ref=repo+"@"+a+":tests/sit/"+path+"#"+id;artifacts.add(row("artifact_id",ref,"role",kind,"source_repo",repo,"source_commit",a,"source_path","tests/sit/"+path,"source_id",id,"source_testrepo_version",a,"sha256",hash(suite.resolve(path)),"bytes",Files.size(suite.resolve(path)),"source_owner_ref","fixture:owner","cleanup_owner_ref","fixture:cleanup","required",true));bindings.add(row("reviewed_path",path,"artifact_id",ref));}
        Map<String,Object> lock=row("lock_id","fixture:lock","lock_version","1","sha256","3".repeat(64),"source_repo",repo,"source_testrepo_version",x,"composition_root_version",x,"artifact_source_testrepo_version",a,"source_path","tests/sit/dependencies.lock");
        Map<String,Object> source=row("schema_version","runner-source-bindings.v1","binding_id","00000000-0000-4000-8000-000000000001","source_binding_state","ADMITTED","execution_binding_state","NOT_EVALUATED","trusted_root_binding_sha256","7".repeat(64),"lock",lock,"validator",row("version",null,"identity_state","JAR_OBSERVED","artifact_sha256","8".repeat(64),"schema_sha256","9".repeat(64),"source_commit",null,"source_tree",null),"repositories",List.of(row("source_repo",repo,"source_testrepo_version",a,"commit_algorithm","git-sha1","source_owner_ref","fixture:owner")),"artifacts",artifacts,"relations",List.of(),"snapshot",Map.of(),"gaps",List.of(),"sut_expectations",List.of(row("sut_id","fixture:sut","owner_ref","fixture:sut-owner","cleanup_owner_ref","fixture:cleanup","expected_deployed_revision","fixture-deployed-v1","readiness_binding_ref","fixture:readiness")));
        Map<String,Object> runtime=row("schema_version","runner-runtime-binding.v1","binding_id","fixture:runtime","runtime_type","contract_test","owner_nonce","fixture:nonce","run_id","fixture:sut-run","correlation_id","fixture:request","evidence_classification","framework_verification_only","collection_spec_sha256","a".repeat(64),"bundle_sha256","b".repeat(64),"expected_sut_revision","fixture-deployed-v1","observed_sut_revision","fixture-deployed-v1","readiness_evidence_sha256","c".repeat(64),"runtime_identity","fixture:owned-sut","maximum_run_ms",30000);
        json.writeValue(suite.resolve("handoff/proof.json").toFile(),proof);json.writeValue(suite.resolve("handoff/sources.json").toFile(),source);json.writeValue(suite.resolve("handoff/runtime.json").toFile(),runtime);
        h.put("knowledge_selection_proof",Map.of("path","handoff/proof.json","sha256",hash(suite.resolve("handoff/proof.json"))));h.put("external_artifact_bindings",row("path","handoff/sources.json","sha256",hash(suite.resolve("handoff/sources.json")),"producer_jar_sha256","8".repeat(64),"producer_schema_sha256","9".repeat(64),"lock_sha256","3".repeat(64),"trusted_roots_sha256","7".repeat(64),"file_bindings",bindings));
        h.put("expected_sut_revisions",Map.of("fixture:sut","fixture-deployed-v1"));h.put("sut_revision_evidence",Map.of("path","handoff/runtime.json","sha256",hash(suite.resolve("handoff/runtime.json")),"sut_id","fixture:sut"));
        h.put("proposal_trace",List.of(row("test_case_id","P2-TOTAL-1","qualified_case_ref",caseRef,"case_sha256",hash(suite.resolve("cases/total.yaml")),"requirement_refs",List.of(req),"acceptance_refs",List.of(repo+"@"+a+":spec.md#AC1"),"risk_refs",List.of(),"api_refs",List.of(),"code_refs",List.of(),"citations",List.of(row("qualified_source_ref",pkRef,"content_sha256","4".repeat(64))),"oracle",row("qualified_ref",oracleRef,"sha256",hash(suite.resolve("data/independent-oracle.json")),"review_ref","fixture:oracle-review","reviewed_by","fixture:oracle-reviewer"))));
        Path input=suite.resolve("review-input.json"),output=suite.resolve("handoff/reviewed.json");json.writeValue(input.toFile(),h);
        return suite;
    }
    @Test void actualPinnedSourceTupleSurvivesPureV2PreparationAndImport()throws Exception {
        Path suite=v2SourceFixture(),input=suite.resolve("review-input.json"),output=suite.resolve("handoff/reviewed.json");
        String caseRef="fixture:product@"+"a".repeat(40)+":tests/sit/cases/total.yaml#P2-TOTAL-1",oracleRef="fixture:product@"+"a".repeat(40)+":tests/sit/data/independent-oracle.json#ORACLE1";
        var service=new ProductTestHandoffService(Clock.fixed(Instant.parse("2026-11-07T00:01:00Z"),ZoneOffset.UTC));assertThat(service.prepare(suite.resolve("suite_manifest.yaml"),"local",input,null,output).get("status")).isEqualTo("prepared_not_approved");
        try(var snapshot=service.snapshot(suite.resolve("suite_manifest.yaml"),"local",new ProductTestHandoffService.InvocationRequirement(hash(output),null,hash(suite.resolve("handoff/runtime.json"))))) {
            var section=json.valueToTree(snapshot.provenance()).path("parser_expected_artifact_relations");assertThat(section.path("state").asText()).isEqualTo("COMPLETE");assertThat(section.path("assertions").size()).isEqualTo(2);
            var literal=section.path("assertions").get(0);var relation=section.path("assertions").get(1);
            assertThat(literal.path("assertion_id").asText()).isEqualTo("status");assertThat(literal.path("expected_operand").asText()).isEqualTo("EXPECTED");assertThat(literal.path("normalized_expected_path").isNull()).isTrue();
            assertThat(relation.path("assertion_id").asText()).isEqualTo("total_oracle");assertThat(relation.path("raw_expected_ref").asText()).isEqualTo("artifact://expected_results/independent-oracle.json");assertThat(relation.path("normalized_expected_path").asText()).isEqualTo("data/independent-oracle.json");assertThat(relation.path("qualified_case_ref").asText()).isEqualTo(caseRef);assertThat(relation.path("qualified_expected_artifact_ref").asText()).isEqualTo(oracleRef);
            assertThat(relation.path("case_sha256").asText()).isEqualTo("c151fdd76d9a4143fb2e1f25f227aa0e4ef58a767b56a927702fb3950b4570a9");assertThat(section.path("manifest_sha256").asText()).isEqualTo("13cb1d9c2bd39d8374e07b81ca04aa53280685e7f24c593b48883abdfed0bc7b");assertThat(relation.path("expected_artifact_sha256").asText()).isEqualTo("d5ca16277c84d2d67af7f83eac59c58cb35dce1d240636bdcd51b20970cf6385");
            var baseline=com.specdriven.regression.contract.ContractBaselineService.fromReviewedSnapshot(snapshot);
            var plan=new com.specdriven.regression.contract.v03.V03ExecutionPlanBuilder(baseline,snapshot.contractDirectory()).build(snapshot.suiteManifest(),"local");
            var step=plan.steps().stream().filter(value->value.id().equals("total_oracle")).findFirst().orElseThrow();
            assertThat(step.inputs().get("expected_ref")).isEqualTo(relation.path("raw_expected_ref").asText());
            assertThat(plan.artifactRoots().get("expected_results")).isEqualTo(snapshot.suiteManifest().getParent().resolve("data").toAbsolutePath().normalize());
            assertThat(snapshot.provenance().get("requirement_mapping").toString()).contains("#status").doesNotContain("#total_oracle");assertThat(Files.exists(suite.resolve("runs"))).isFalse();assertThat(snapshot.provenance()).doesNotContainKey("canonical_outcome");
        }
    }

    private ProductTestHandoffService.Snapshot v2Snapshot(Path suite)throws Exception {
        var service=new ProductTestHandoffService(Clock.fixed(Instant.parse("2026-11-07T00:01:00Z"),ZoneOffset.UTC));Path out=suite.resolve("handoff/reviewed.json");
        service.prepare(suite.resolve("suite_manifest.yaml"),"local",suite.resolve("review-input.json"),null,out);
        return service.snapshot(suite.resolve("suite_manifest.yaml"),"local",new ProductTestHandoffService.InvocationRequirement(hash(out),null,hash(suite.resolve("handoff/runtime.json"))));
    }
    /** Reseal synthetic test-owned source metadata only; never alters a protected external pin. */
    private void resealCase(Path suite)throws Exception {
        var h=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(suite.resolve("review-input.json").toFile());
        var sources=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(suite.resolve("handoff/sources.json").toFile());
        for(var artifact:sources.path("artifacts"))if(artifact.path("role").asText().equals("case"))((com.fasterxml.jackson.databind.node.ObjectNode)artifact).put("sha256",hash(suite.resolve("cases/total.yaml"))).put("bytes",Files.size(suite.resolve("cases/total.yaml")));
        json.writeValue(suite.resolve("handoff/sources.json").toFile(),sources);((com.fasterxml.jackson.databind.node.ObjectNode)h.path("external_artifact_bindings")).put("sha256",hash(suite.resolve("handoff/sources.json")));((com.fasterxml.jackson.databind.node.ObjectNode)h.path("proposal_trace").get(0)).put("case_sha256",hash(suite.resolve("cases/total.yaml")));
        var refs=((com.fasterxml.jackson.databind.node.ObjectNode)h.path("requirement_mapping").get(0)).putArray("oracle_refs");refs.add("data/independent-oracle.json");Map<String,Object> test=new org.yaml.snakeyaml.Yaml().load(Files.readString(suite.resolve("cases/total.yaml")));
        for(var raw:(List<Map<String,Object>>)test.get("verify"))if(!((Map<?,?>)raw.get("assert")).containsKey("expected_ref"))refs.add("cases/total.yaml#"+raw.get("id"));
        json.writeValue(suite.resolve("review-input.json").toFile(),h);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={256,257})
    void privateDerivedCollectorOmitsWholeSectionAtRowLimit(int count)throws Exception {
        Path suite=parserFixture();try(var snapshot=parserSnapshot(suite)){Map<String,Object> section=parserMetadata(suite,snapshot,count,false);if(count==256)assertThat(json.valueToTree(section).path("assertions").size()).isEqualTo(256);else assertThat(section).isNull();}
    }
    @Test void privateDerivedCollectorOmitsWholeSectionAtUtf8Budget()throws Exception {
        Path suite=parserFixture();try(var snapshot=parserSnapshot(suite)){assertThat(parserMetadata(suite,snapshot,256,true)).isNull();}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={128,129})
    void originalVerifyListLimitIsNotRelaxedForOptionalParserExport(int count)throws Exception {
        Path suite=v2SourceFixture();StringBuilder source=new StringBuilder(Files.readString(suite.resolve("cases/total.yaml")));
        for(int i=2;i<count;i++)source.append("  - id: extra_"+i+"\n    type: assertion\n    assert: {actual: step://total/response.status, operator: equals, expected: 200}\n");
        Files.writeString(suite.resolve("cases/total.yaml"),source);resealCase(suite);
        if(count==129)assertThatThrownBy(()->v2Snapshot(suite)).hasMessageContaining("bounded list");
        else try(var snapshot=v2Snapshot(suite)){assertThat(json.valueToTree(snapshot.provenance()).at("/parser_expected_artifact_relations/assertions").size()).isEqualTo(128);}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"artifact://unknown/independent-oracle.json","artifact://expected_results/../independent-oracle.json","artifact://expected_results/%2e%2e/independent-oracle.json"})
    void sourceNamespaceErrorsCannotProduceAParserRelation(String uri)throws Exception {
        Path suite=v2SourceFixture();Files.writeString(suite.resolve("cases/total.yaml"),Files.readString(suite.resolve("cases/total.yaml")).replace("artifact://expected_results/independent-oracle.json",uri));resealCase(suite);
        assertThatThrownBy(()->v2Snapshot(suite)).isInstanceOf(IllegalArgumentException.class);assertThat(Files.exists(suite.resolve("handoff/reviewed.json"))).isFalse();assertThat(Files.exists(suite.resolve("runs"))).isFalse();
    }
    @Test void callerCannotInjectDerivedParserMetadataIntoV2ReviewInput()throws Exception {
        Path suite=v2SourceFixture();var h=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(suite.resolve("review-input.json").toFile());h.putObject("parser_expected_artifact_relations").put("state","COMPLETE");json.writeValue(suite.resolve("review-input.json").toFile(),h);
        assertThatThrownBy(()->v2Snapshot(suite)).hasMessageContaining("unknown field");
    }
    private static Map<String,Object> row(Object... values){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
