package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;

/** Verifies the typed external v2 projection against canonical bytes already accepted by this module. */
final class ReviewedCoverageProjection {
    private static final Set<String> TRACE_FIELDS=Set.of("qualified_source_ref","governed_record_key","record_version","source_valid_until","content_sha256","context_sha256","selection_proof_sha256","source_bindings_sha256","qualified_case_ref","test_case_id","requirement_refs","acceptance_refs","risk_refs","api_refs","code_refs","oracle","binding_state","selection_state");
    private ReviewedCoverageProjection(){}
    static void verify(JsonNode coverage,Path canonical,JsonNode result,ProductTestRequest request)throws Exception{
        ReviewedOracleCoverageProjection.verify(coverage,canonical,result,request);
        if(!"product-test-reviewed-handoff.v2".equals(result.path("product_test").path("contract_version").asText())){
            require(!coverage.has("knowledge_trace")||coverage.path("knowledge_trace").isArray()&&coverage.path("knowledge_trace").isEmpty(),"Unexpected legacy knowledge trace");return;
        }
        require("integration".equals(request.testLevel())&&"required".equals(request.knowledgeMode()),"Required native route mismatch");
        Path path=LocalFiles.contained(canonical.getParent(),canonical.getParent().resolve("product-test/reviewed-handoff.json"));byte[] raw=LocalFiles.read(path,2*1024*1024);
        require(LocalFiles.sha256(raw).equals(Json.required(result.path("product_test"),"provenance_sha256")),"Canonical provenance pin mismatch");JsonNode provenance=Json.readMetadataUtf8(raw);
        require("product-test-reviewed-handoff.v2".equals(provenance.path("contract_version").asText()),"Canonical provenance version mismatch");
        Map<String,JsonNode>cases=new HashMap<>(),tests=new HashMap<>();for(JsonNode c:coverage.path("cases"))require(cases.putIfAbsent(Json.required(c,"test_case_id"),c)==null,"Ambiguous projected case");for(JsonNode t:result.path("test_results"))tests.put(Json.required(t,"test_case_id"),t);
        require(provenance.path("knowledge_trace").isArray()&&!provenance.path("knowledge_trace").isEmpty()&&provenance.path("knowledge_trace").size()<=7680&&coverage.path("knowledge_trace").isArray(),"Missing typed trace");
        Map<List<String>,JsonNode>expected=new HashMap<>();Set<String>tracedCases=new HashSet<>();String resultSha=LocalFiles.sha256(canonical);
        for(JsonNode row:provenance.path("knowledge_trace")){
            exactFields(row,TRACE_FIELDS);exactFields(row.path("oracle"),Set.of("qualified_ref","sha256","review_ref","reviewed_by"));String id=Json.required(row,"test_case_id");JsonNode test=tests.get(id),projectedCase=cases.get(id);require(test!=null&&projectedCase!=null,"Trace case missing");
            require(Json.required(row,"qualified_case_ref").equals(Json.required(projectedCase,"qualified_case_ref"))&&"VALID".equals(projectedCase.path("evidence_state").asText()),"Qualified case/evidence mismatch");tracedCases.add(id);
            ObjectNode projected=(ObjectNode)row.deepCopy();projected.put("run_id",Json.required(result,"run_id")).put("batch_id",Json.required(result,"batch_id")).put("source_result",canonical.toString()).put("source_result_sha256",resultSha).put("canonical_outcome",outcome(Json.required(test,"status"))).put("evidence_state","VALID").put("historical_context_state","VALID_AT_INVOCATION").put("new_invocation_eligibility","NOT_EVALUATED_REQUIRES_FRESH_REVIEW");
            for(String field:List.of("knowledge_context_scope","source_lock","source_repository_versions"))projected.set(field,provenance.path(field).deepCopy());projected.set("scope",coverage.path("subject").deepCopy());projected.set("evidence_refs",projectedCase.path("evidence_refs").deepCopy());
            require(expected.putIfAbsent(key(row),projected)==null,"Duplicate canonical trace");
        }
        require(tracedCases.equals(tests.keySet()),"Incomplete canonical trace");
        for(JsonNode row:coverage.path("knowledge_trace")){JsonNode match=expected.remove(key(row));require(match!=null&&match.equals(row),"Trace differs from pinned canonical projection");}require(expected.isEmpty(),"Missing projected trace");
        require(provenance.path("source_case_selection").isArray()&&provenance.path("source_case_selection").size()<=128&&coverage.path("source_case_selection").isArray(),"Source selection missing");Map<String,JsonNode>selections=new HashMap<>();
        for(JsonNode row:provenance.path("source_case_selection")){exactFields(row,Set.of("qualified_case_ref","source_test_id","state"));ObjectNode projected=(ObjectNode)row.deepCopy();projected.put("run_id",Json.required(result,"run_id")).put("batch_id",Json.required(result,"batch_id")).put("source_result",canonical.toString()).put("source_result_sha256",resultSha);require(selections.putIfAbsent(Json.required(row,"qualified_case_ref"),projected)==null,"Duplicate canonical selection");}
        for(JsonNode row:coverage.path("source_case_selection")){JsonNode match=selections.remove(Json.required(row,"qualified_case_ref"));require(match!=null&&match.equals(row),"Source selection differs from pinned canonical projection");}require(selections.isEmpty(),"Missing projected source selection");
    }
    private static List<String>key(JsonNode row){return List.of(Json.required(row,"qualified_source_ref"),Json.required(row,"qualified_case_ref"),Json.required(row,"test_case_id"));}
    private static void exactFields(JsonNode value,Set<String>expected){Set<String>actual=new HashSet<>();value.fieldNames().forEachRemaining(actual::add);require(value.isObject()&&actual.equals(expected),"Unexpected private or untyped trace fields");}
    private static String outcome(String value){return switch(value){case "passed"->"PASS";case "failed"->"FAIL";case "blocked"->"BLOCKED";case "skipped"->"SKIPPED";default->"NOT_RUN";};}
    private static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException("v2 coverage: "+reason);}
}
