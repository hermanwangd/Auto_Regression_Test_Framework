package com.specdriven.regression.evidence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** PUBLIC_SYNTHETIC frozen metadata only. No producer, SUT, socket, live owner or agent run. */
final class KnowledgeV2Fixture {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String REPO="fixture:product",PK="fixture:pk",A="a".repeat(40),X="b".repeat(40),P="c".repeat(40);
    static final String SOURCE=PK+"@"+P+":knowledge.md#PK1",REQ=REPO+"@"+A+":spec.md#REQ1",CASE=REPO+"@"+A+":tests/sit/test_cases/case.yaml#TC-1",ORACLE=REPO+"@"+A+":tests/sit/expected_results/oracle.json#ORACLE1";
    static ObjectNode obj(Object...fields){ObjectNode n=JSON.createObjectNode();for(int i=0;i<fields.length;i+=2)n.set((String)fields[i],JSON.valueToTree(fields[i+1]));return n;}
    static void augment(ReviewedGrpcProofIdentityTest fixture)throws Exception {
        ObjectNode p=fixture.provenance;p.put("contract_version","product-test-reviewed-handoff.v2").put("knowledge_mode","required").put("mission_ref","fixture:mission").put("request_ref","fixture:request");
        p.put("reviewed_at","2000-01-01T00:00:00Z").put("valid_until","2000-01-01T00:05:00Z").put("knowledge_fetched_at","2000-01-01T00:00:00Z").put("knowledge_evaluated_at","2000-01-01T00:00:00Z").put("knowledge_valid_until","2000-01-01T00:05:00Z").put("provider_version","UNVERIFIED_BY_HTTP_CLIENT");
        p.set("repository_revisions",JSON.valueToTree(Map.of(REPO,X,PK,P)));
        p.set("cognee_source",obj("version","1.6.2","commit","ba3631f2ed363a6ea50d649c34c56885af6b36fe","runtime_version_verified",false));
        for(String key:List.of("selection_proof_raw_sha256","selection_receipt_sha256","source_snapshot_sha256","candidate_search_sha256","query_sha256","context_sha256","source_bindings_raw_sha256","source_producer_jar_sha256","source_producer_schema_sha256","proposal_sha256","sut_revision_binding_sha256"))p.put(key,"2".repeat(64));p.put("private_original_receipt_sha256","1".repeat(64));
        p.set("knowledge_context_scope",obj("mission_ref","fixture:mission","workspace_ref","fixture-workspace","knowledge_project_ref","fixture:knowledge-project","consumer_project_ref","fixture-project","repository_revisions",Map.of(REPO,X,PK,P),"requirement_refs",List.of(REQ)));
        p.set("source_lock",obj("lock_id","fixture-lock","lock_version","1","sha256","3".repeat(64),"source_repo",REPO,"source_testrepo_version",X,"composition_root_version",X,"artifact_source_testrepo_version",A,"source_path","tests/sit/dependencies.lock"));
        p.putArray("source_repository_versions").add(obj("source_repo",REPO,"source_testrepo_version",A,"commit_algorithm","git-sha1","source_owner_ref","fixture:product-owner"));
        p.putArray("knowledge_sources").add(obj("qualified_source_ref",SOURCE,"governed_record_key","fixture:record","record_version",1,"provider_revision","fixture:provider-v1","source_ref","fixture:source","content_sha256","4".repeat(64),"owner_ref","fixture:pk-owner","knowledge_ref","fixture:knowledge","proposal_digest","5".repeat(64),"decision_ref","fixture:decision","decision_record_key","fixture:record","decision_record_version",1,"decision_proposal_digest","5".repeat(64),"observed_at","1999-12-31T23:59:59Z","valid_until","2000-01-01T00:05:00Z","document_id","fixture:document","chunk_count",1,"dataset_id","00000000-0000-7000-8000-000000000001"));
        ArrayNode files=p.withArray("reviewed_files");files.add(obj("path","suite.yaml","role","manifest","sha256","6".repeat(64))).add(obj("path","config/local.yaml","role","profile","sha256","7".repeat(64))).add(obj("path","test_cases/case.yaml","role","test","sha256","8".repeat(64))).add(obj("path","expected_results/oracle.json","role","oracle","sha256","9".repeat(64)));
        ArrayNode bound=p.putArray("executed_source_bindings");
        for(JsonNode f:files){String path=f.path("path").asText(),role=f.path("role").asText();String sourceId=role.equals("test")?"TC-1":role.equals("oracle")?"ORACLE1":role.equals("fixture")?"LEASE1":role.toUpperCase(Locale.ROOT);String kind=switch(role){case "manifest"->"suite";case "profile"->"config_template";case "test"->"case";case "oracle"->"oracle";default->"sanitized_data";};bound.add(artifact(path,sourceId,kind,f.path("sha256").asText()));}
        for(JsonNode contract:p.path("reviewed_contracts"))bound.add(artifact(contract.path("path").asText(),"CONTRACT1","provider_contract",contract.path("sha256").asText()));
        p.putArray("requirement_mapping").add(obj("requirement_ref",REQ,"test_case_ids",List.of("TC-1"),"oracle_refs",List.of("expected_results/oracle.json"),"review_ref","fixture:review"));
        p.putArray("knowledge_trace").add(obj("qualified_source_ref",SOURCE,"governed_record_key","fixture:record","record_version",1,"source_valid_until","2000-01-01T00:05:00Z","content_sha256","4".repeat(64),"context_sha256","2".repeat(64),"selection_proof_sha256","2".repeat(64),"source_bindings_sha256","2".repeat(64),"qualified_case_ref",CASE,"test_case_id","TC-1","requirement_refs",List.of(REQ),"acceptance_refs",List.of(REPO+"@"+A+":spec.md#AC1"),"risk_refs",List.of(),"api_refs",List.of(),"code_refs",List.of(),"oracle",obj("qualified_ref",ORACLE,"sha256","9".repeat(64),"review_ref","fixture:review","reviewed_by","fixture:reviewer"),"binding_state","VALID","selection_state","SELECTED"));
        p.putArray("source_case_selection").add(obj("qualified_case_ref",CASE,"source_test_id","TC-1","state","SELECTED"));p.set("sut_revisions",obj("fixture:sut",obj("expected","fixture-deployed-v1","observed","fixture-deployed-v1","state","MATCHED")));fixture.save();
    }
    private static ObjectNode artifact(String path,String id,String role,String sha){return obj("artifact_id",REPO+"@"+A+":tests/sit/"+path+"#"+id,"role",role,"source_repo",REPO,"source_commit",A,"source_path","tests/sit/"+path,"source_id",id,"source_testrepo_version",A,"sha256",sha,"bytes",1,"source_owner_ref","fixture:product-owner","cleanup_owner_ref","fixture:cleanup-owner","required",true,"reviewed_path",path);}
}
