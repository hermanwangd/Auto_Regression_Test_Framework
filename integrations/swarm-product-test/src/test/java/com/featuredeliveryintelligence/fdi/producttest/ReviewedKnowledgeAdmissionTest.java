package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent module postcondition over pinned PUBLIC_SYNTHETIC metadata, without source/provider operations. */
class ReviewedKnowledgeAdmissionTest {
    @TempDir Path root;
    ObjectNode handoff,provenance;ProductTestRequest request;String sutPin;
    static final String CASE="fixture:product@"+"a".repeat(40)+":tests/sit/cases/one.yaml#TC-1",OTHER="fixture:product@"+"a".repeat(40)+":tests/sit/cases/other.yaml#TC-2",PK="fixture:pk@"+"c".repeat(40)+":knowledge.md#PK1";
    static ObjectNode obj(Object...pairs){ObjectNode n=Json.MAPPER.createObjectNode();for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],Json.MAPPER.valueToTree(pairs[i+1]));return n;}
    @Test void exactRecordedSourceBindingsAndNonselectedGraphRemainValid()throws Exception{fixture();assertThatCode(this::verify).doesNotThrowAnyException();}
    @ParameterizedTest @ValueSource(strings={"cleanup_owner","source_owner","commit","path","omit","extra","selected","unselected"})
    void canonicalResealCannotChangeD2ExecutedIdentityOrSelection(String mutation)throws Exception{
        fixture();ObjectNode binding=(ObjectNode)provenance.path("executed_source_bindings").get(0);
        switch(mutation){case "cleanup_owner"->binding.put("cleanup_owner_ref","fixture:wrong-owner");case "source_owner"->binding.put("source_owner_ref","fixture:wrong-owner");case "commit"->binding.put("source_commit","d".repeat(40));case "path"->binding.put("reviewed_path","cases/other.yaml");case "omit"->provenance.putArray("executed_source_bindings");case "extra"->provenance.withArray("executed_source_bindings").add(binding.deepCopy());case "selected"->((ObjectNode)provenance.path("source_case_selection").get(0)).put("state","NOT_SELECTED");case "unselected"->((ObjectNode)provenance.path("source_case_selection").get(1)).put("state","SELECTED");}
        assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SOURCE_");
    }
    private void verify()throws Exception{ReviewedKnowledgeAdmission.after(request,handoff,root,provenance,sutPin,Instant.parse("2000-01-01T00:00:01Z"));}
    private void fixture()throws Exception{
        var revisions=Map.of("fixture:product","b".repeat(40),"fixture:pk","c".repeat(40));request=new ProductTestRequest("product-test-request.v1","product-test","1","mission","request","execution","workspace","project",revisions,Map.of(),"integration","regression","local","required",root.toString(),root.resolve("suite.yaml").toString(),"SUITE","local",root.resolve("handoff/reviewed-tests.json").toString(),"a".repeat(64));
        ObjectNode selected=obj("qualified_source_ref",PK,"valid_until","2000-01-01T00:05:00Z");ObjectNode proof=obj("schema_version","knowledge-selection-proof.v1","provider_version","UNVERIFIED_BY_HTTP_CLIENT","scope",Map.of("mission_ref","mission","workspace_ref","workspace","consumer_project_ref","project","repository_revisions",revisions),"valid_until","2000-01-01T00:05:00Z");proof.putArray("selected").add(selected);for(String field:List.of("private_original_receipt_sha256","selection_receipt_sha256","source_snapshot_sha256","candidate_search_sha256","query_sha256","context_sha256"))proof.put(field,"2".repeat(64));ObjectNode proofLocator=write("handoff/proof.json",proof);
        ObjectNode artifact=artifact(CASE,"cases/one.yaml","TC-1"),other=artifact(OTHER,"cases/other.yaml","TC-2");ObjectNode metadata=obj("schema_version","runner-source-bindings.v1","source_binding_state","ADMITTED","execution_binding_state","NOT_EVALUATED","validator",Map.of("identity_state","JAR_OBSERVED","artifact_sha256","3".repeat(64),"schema_sha256","4".repeat(64)),"lock",Map.of("sha256","5".repeat(64),"composition_root_version","b".repeat(40)),"trusted_root_binding_sha256","6".repeat(64),"repositories",List.of(Map.of("source_repo","fixture:product","source_testrepo_version","a".repeat(40))));metadata.putArray("artifacts").add(artifact).add(other);ObjectNode external=write("handoff/sources.json",metadata);external.put("producer_jar_sha256","3".repeat(64)).put("producer_schema_sha256","4".repeat(64)).put("lock_sha256","5".repeat(64)).put("trusted_roots_sha256","6".repeat(64));external.putArray("file_bindings").add(obj("reviewed_path","cases/one.yaml","artifact_id",CASE));
        ObjectNode runtime=obj("schema_version","runner-runtime-binding.v1","evidence_classification","framework_verification_only","correlation_id","request","run_id","sut-run","expected_sut_revision","fixture-sut-v1","observed_sut_revision","fixture-sut-v1");ObjectNode runtimeLocator=write("handoff/runtime.json",runtime);sutPin=runtimeLocator.path("sha256").asText();runtimeLocator.put("sut_id","fixture:sut");
        ObjectNode oracle=obj("qualified_ref","fixture:oracle","sha256","7".repeat(64),"review_ref","fixture:review","reviewed_by","fixture:reviewer");ObjectNode trace=obj("qualified_case_ref",CASE,"test_case_id","TC-1","oracle",oracle);for(String key:List.of("requirement_refs","acceptance_refs","risk_refs","api_refs","code_refs"))trace.putArray(key);trace.putArray("citations").add(obj("qualified_source_ref",PK,"content_sha256","8".repeat(64)));
        handoff=obj("contract_version","product-test-reviewed-handoff.v2","preparation_provenance",Map.of("mission_ref","mission","request_ref","request","sut_run_ref","sut-run"),"knowledge_selection_proof",proofLocator,"external_artifact_bindings",external,"sut_revision_evidence",runtimeLocator,"expected_sut_revisions",Map.of("fixture:sut","fixture-sut-v1"));handoff.putArray("proposal_trace").add(trace);
        provenance=obj("mission_ref","mission","request_ref","request","selection_proof_raw_sha256",proofLocator.path("sha256"),"source_bindings_raw_sha256",external.path("sha256"),"sut_revision_binding_sha256",sutPin,"knowledge_sources",proof.path("selected"),"source_lock",metadata.path("lock"),"source_repository_versions",metadata.path("repositories"));for(String field:List.of("private_original_receipt_sha256","selection_receipt_sha256","source_snapshot_sha256","candidate_search_sha256","query_sha256","context_sha256"))provenance.set(field,proof.path(field));
        ObjectNode row=trace.deepCopy();row.remove("citations");row.put("qualified_source_ref",PK).put("content_sha256","8".repeat(64)).put("context_sha256","2".repeat(64));provenance.putArray("knowledge_trace").add(row);ObjectNode bound=artifact.deepCopy();bound.put("reviewed_path","cases/one.yaml");provenance.putArray("executed_source_bindings").add(bound);provenance.putArray("source_case_selection").add(obj("qualified_case_ref",CASE,"source_test_id","TC-1","state","SELECTED")).add(obj("qualified_case_ref",OTHER,"source_test_id","TC-2","state","NOT_SELECTED"));
    }
    private ObjectNode write(String path,JsonNode json)throws Exception{Path p=root.resolve(path);Files.createDirectories(p.getParent());byte[]raw=Json.MAPPER.writeValueAsBytes(json);Files.write(p,raw);return obj("path",path,"sha256",LocalFiles.sha256(raw));}
    private static ObjectNode artifact(String id,String path,String test){return obj("artifact_id",id,"role","case","source_repo","fixture:product","source_commit","a".repeat(40),"source_path","tests/sit/"+path,"source_id",test,"source_testrepo_version","a".repeat(40),"sha256","9".repeat(64),"bytes",100,"source_owner_ref","fixture:owner","cleanup_owner_ref","fixture:cleanup-owner","required",true);}
}
