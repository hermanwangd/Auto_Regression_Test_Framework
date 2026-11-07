package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Independent module boundary for reviewed v2 metadata. No producer observation, source lookup or retrieval here. */
final class ReviewedKnowledgeAdmission {
    private ReviewedKnowledgeAdmission(){}
    static boolean v2(JsonNode h){return "product-test-reviewed-handoff.v2".equals(h.path("contract_version").asText());}
    static void before(ProductTestRequest request,JsonNode h,Path suiteRoot,String independentSutPin)throws Exception{
        before(request,h,suiteRoot,independentSutPin,Instant.now());
    }
    private static void before(ProductTestRequest request,JsonNode h,Path suiteRoot,String independentSutPin,Instant evaluationTime)throws Exception{
        require(v2(h)&&"integration".equals(request.testLevel())&&"required".equals(request.knowledgeMode()),"V2_REQUIRED_NATIVE_ROUTE");
        require(!h.has("selection_receipt")&&!h.has("knowledge_sources"),"PRIVATE_OR_LEGACY_RECEIPT_FORBIDDEN");
        require(independentSutPin!=null&&independentSutPin.matches("[a-f0-9]{64}"),"INDEPENDENT_SUT_BINDING_REQUIRED");
        JsonNode proof=external(suiteRoot,h.path("knowledge_selection_proof"),262144);
        require("knowledge-selection-proof.v1".equals(proof.path("schema_version").asText())&&"UNVERIFIED_BY_HTTP_CLIENT".equals(proof.path("provider_version").asText()),"KNOWLEDGE_PROOF_VERSION");
        require(proof.path("selected").isArray()&&!proof.path("selected").isEmpty()&&proof.path("selected").size()<=15,"REQUIRED_CONTEXT_MISSING");
        JsonNode scope=proof.path("scope"),preparation=h.path("preparation_provenance");
        require(request.missionRef().equals(scope.path("mission_ref").asText())&&request.missionRef().equals(preparation.path("mission_ref").asText())&&request.requestRef().equals(preparation.path("request_ref").asText()),"KNOWLEDGE_CORRELATION_MISMATCH");
        require(request.workspaceRef().equals(scope.path("workspace_ref").asText())&&request.projectRef().equals(scope.path("consumer_project_ref").asText())&&Json.MAPPER.valueToTree(request.repositoryRevisions()).equals(scope.path("repository_revisions")),"KNOWLEDGE_SCOPE_MISMATCH");
        Instant now=evaluationTime;require(Instant.parse(Json.required(proof,"valid_until")).isAfter(now),"KNOWLEDGE_EXPIRED");
        for(JsonNode source:proof.path("selected"))require(Instant.parse(Json.required(source,"valid_until")).isAfter(now),"SELECTED_SOURCE_EXPIRED");
        JsonNode metadata=external(suiteRoot,h.path("external_artifact_bindings"),2*1024*1024),expected=h.path("external_artifact_bindings"),producer=metadata.path("validator");
        require("runner-source-bindings.v1".equals(metadata.path("schema_version").asText())&&"ADMITTED".equals(metadata.path("source_binding_state").asText())&&"NOT_EVALUATED".equals(metadata.path("execution_binding_state").asText()),"SOURCE_BINDINGS_INVALID");
        require("JAR_OBSERVED".equals(producer.path("identity_state").asText())&&Json.required(expected,"producer_jar_sha256").equals(Json.required(producer,"artifact_sha256"))&&Json.required(expected,"producer_schema_sha256").equals(Json.required(producer,"schema_sha256")),"SOURCE_PRODUCER_PIN_MISMATCH");
        require(Json.required(expected,"lock_sha256").equals(Json.required(metadata.path("lock"),"sha256"))&&Json.required(expected,"trusted_roots_sha256").equals(Json.required(metadata,"trusted_root_binding_sha256")),"SOURCE_LOCK_ROOT_PIN_MISMATCH");
        require(independentSutPin.equals(Json.required(h.path("sut_revision_evidence"),"sha256")),"SUT_BINDING_PIN_MISMATCH");JsonNode sut=external(suiteRoot,h.path("sut_revision_evidence"),2*1024*1024);
        require("runner-runtime-binding.v1".equals(sut.path("schema_version").asText())&&"framework_verification_only".equals(sut.path("evidence_classification").asText()),"SUT_BINDING_VERSION");
        require(request.requestRef().equals(sut.path("correlation_id").asText())&&preparation.path("sut_run_ref").asText().equals(sut.path("run_id").asText()),"SUT_CORRELATION_MISMATCH");
        String id=Json.required(h.path("sut_revision_evidence"),"sut_id");JsonNode revisions=h.path("expected_sut_revisions");require(revisions.isObject()&&revisions.size()==1&&revisions.path(id).isTextual(),"ONE_SUT_LEAF_REQUIRED");String revision=revisions.path(id).asText();
        require(!revision.isBlank()&&!revision.toUpperCase(Locale.ROOT).contains("UNBOUND")&&revision.equals(sut.path("expected_sut_revision").asText())&&revision.equals(sut.path("observed_sut_revision").asText()),"SUT_VERSION_MISMATCH");
    }
    static void after(ProductTestRequest request,JsonNode h,Path suiteRoot,JsonNode provenance,String independentSutPin,Instant invocationStart)throws Exception{
        before(request,h,suiteRoot,independentSutPin,invocationStart);JsonNode proof=external(suiteRoot,h.path("knowledge_selection_proof"),262144),metadata=external(suiteRoot,h.path("external_artifact_bindings"),2*1024*1024);
        require(Json.required(h.path("knowledge_selection_proof"),"sha256").equals(Json.required(provenance,"selection_proof_raw_sha256"))&&Json.required(h.path("external_artifact_bindings"),"sha256").equals(Json.required(provenance,"source_bindings_raw_sha256")),"KNOWLEDGE_PROVENANCE_PIN_MISMATCH");
        for(String field:List.of("private_original_receipt_sha256","selection_receipt_sha256","source_snapshot_sha256","candidate_search_sha256","query_sha256","context_sha256"))require(Json.required(proof,field).equals(Json.required(provenance,field)),"KNOWLEDGE_DIGEST_MISMATCH");
        require(proof.path("selected").equals(provenance.path("knowledge_sources"))&&request.missionRef().equals(provenance.path("mission_ref").asText())&&request.requestRef().equals(provenance.path("request_ref").asText()),"KNOWLEDGE_SELECTION_MISMATCH");
        require(independentSutPin.equals(provenance.path("sut_revision_binding_sha256").asText()),"SUT_PROVENANCE_PIN_MISMATCH");
        Map<List<String>,JsonNode>rows=new HashMap<>();require(provenance.path("knowledge_trace").isArray(),"KNOWLEDGE_TRACE_MISSING");
        for(JsonNode row:provenance.path("knowledge_trace")){var key=List.of(Json.required(row,"qualified_source_ref"),Json.required(row,"qualified_case_ref"),Json.required(row,"test_case_id"));require(rows.putIfAbsent(key,row)==null,"AMBIGUOUS_KNOWLEDGE_TRACE");}
        for(JsonNode trace:h.path("proposal_trace"))for(JsonNode citation:trace.path("citations")){
            var key=List.of(Json.required(citation,"qualified_source_ref"),Json.required(trace,"qualified_case_ref"),Json.required(trace,"test_case_id"));JsonNode row=rows.remove(key);require(row!=null,"KNOWLEDGE_TRACE_OMITTED");
            require(citation.path("content_sha256").equals(row.path("content_sha256"))&&trace.path("oracle").equals(row.path("oracle"))&&proof.path("context_sha256").equals(row.path("context_sha256")),"KNOWLEDGE_TRACE_IDENTITY_MISMATCH");
            for(String field:List.of("requirement_refs","acceptance_refs","risk_refs","api_refs","code_refs"))require(refs(trace.path(field)).equals(refs(row.path(field))),"KNOWLEDGE_TRACE_RELATION_MISMATCH");
        }require(rows.isEmpty(),"EXTRA_KNOWLEDGE_TRACE");
        require(metadata.path("lock").equals(provenance.path("source_lock"))&&metadata.path("repositories").equals(provenance.path("source_repository_versions")),"COMPOSITION_ARTIFACT_VERSION_MISMATCH");
        sourceBindings(h,metadata,provenance);
    }
    private static void sourceBindings(JsonNode handoff,JsonNode metadata,JsonNode provenance){
        JsonNode artifacts=metadata.path("artifacts"),fileBindings=handoff.path("external_artifact_bindings").path("file_bindings"),executed=provenance.path("executed_source_bindings");
        require(artifacts.isArray()&&!artifacts.isEmpty()&&artifacts.size()<=128&&fileBindings.isArray()&&!fileBindings.isEmpty()&&fileBindings.size()<=128&&executed.isArray()&&executed.size()<=128,"SOURCE_BINDINGS_REQUIRED");
        Map<String,JsonNode>sourceById=new HashMap<>(),expectedPaths=new HashMap<>();for(JsonNode source:artifacts)require(sourceById.putIfAbsent(Json.required(source,"artifact_id"),source)==null,"SOURCE_ARTIFACT_AMBIGUOUS");
        for(JsonNode binding:fileBindings){String path=Json.required(binding,"reviewed_path");JsonNode source=sourceById.get(Json.required(binding,"artifact_id"));require(source!=null&&expectedPaths.putIfAbsent(path,source)==null,"SOURCE_FILE_BINDING_MISMATCH");}
        Set<String>selectedCases=new HashSet<>();for(JsonNode binding:executed){String path=Json.required(binding,"reviewed_path");JsonNode expected=expectedPaths.remove(path);require(binding.isObject(),"SOURCE_BINDING_OBJECT_REQUIRED");var actual=binding.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)actual).remove("reviewed_path");require(expected!=null&&expected.equals(actual),"SOURCE_EXECUTED_IDENTITY_MISMATCH");if("case".equals(actual.path("role").asText()))selectedCases.add(Json.required(actual,"artifact_id"));}require(expectedPaths.isEmpty(),"SOURCE_EXECUTED_BINDING_MISSING");
        Map<String,JsonNode>selections=new HashMap<>();for(JsonNode source:artifacts)if("case".equals(source.path("role").asText())){String id=Json.required(source,"artifact_id");selections.put(id,Json.MAPPER.valueToTree(Map.of("qualified_case_ref",id,"source_test_id",Json.required(source,"source_id"),"state",selectedCases.contains(id)?"SELECTED":"NOT_SELECTED")));}
        JsonNode actualSelections=provenance.path("source_case_selection");require(actualSelections.isArray()&&actualSelections.size()<=128,"SOURCE_CASE_SELECTION_REQUIRED");for(JsonNode row:actualSelections){JsonNode expected=selections.remove(Json.required(row,"qualified_case_ref"));require(expected!=null&&expected.equals(row),"SOURCE_CASE_SELECTION_MISMATCH");}require(selections.isEmpty(),"SOURCE_CASE_SELECTION_MISSING");
    }
    private static Set<String>refs(JsonNode values){require(values.isArray(),"TRACE_REFS_REQUIRED");var result=new HashSet<String>();for(JsonNode value:values)require(value.isTextual()&&result.add(value.asText()),"TRACE_REFS_INVALID");return result;}
    private static JsonNode external(Path suiteRoot,JsonNode locator,int limit)throws Exception{
        String ref=Json.required(locator,"path");Path relative=Path.of(ref);require(!relative.isAbsolute()&&relative.normalize().equals(relative)&&!relative.startsWith(".."),"METADATA_PATH_INVALID");byte[] raw=LocalFiles.read(LocalFiles.contained(suiteRoot,suiteRoot.resolve(relative)),limit);require(LocalFiles.sha256(raw).equals(Json.required(locator,"sha256")),"METADATA_DIGEST_MISMATCH");return Json.readMetadataUtf8(raw);
    }
    private static void require(boolean condition,String code){if(!condition)throw new IllegalArgumentException(code);}
}
