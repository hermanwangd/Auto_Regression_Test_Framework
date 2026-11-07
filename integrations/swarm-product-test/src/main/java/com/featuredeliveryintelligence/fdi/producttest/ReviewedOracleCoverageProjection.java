package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Checks historical identity/declaration only. No oracle execution, inventory or independence authority. */
final class ReviewedOracleCoverageProjection {
    private static final int LIMIT=10000;
    private static final Set<String> ROW=Set.of("trace_id","test_case_id","qualified_case_ref","source_result","source_result_sha256","run_id","batch_id","canonical_outcome","evidence_state","requirement_refs","acceptance_criteria_refs","mapping_review_ref","declared_oracle_refs","oracle","expected_ref","expected_ref_state","expected_ref_reason","binding_state","binding_reason","independence","knowledge_refs");
    private static final Set<String> ORACLE=Set.of("qualified_ref","path","sha256","source_repo","source_commit","source_testrepo_version","review_ref","reviewed_by");
    private static final Set<String> KNOWLEDGE=Set.of("qualified_source_ref","record_version","content_sha256","selection_proof_sha256","context_sha256","source_bindings_sha256");
    private ReviewedOracleCoverageProjection(){}

    static void verify(JsonNode coverage,Path canonical,JsonNode result,ProductTestRequest request)throws Exception {
        if(!coverage.has("oracle_trace")) {
            for(String section:List.of("cases","entities"))for(JsonNode value:coverage.path(section))
                require(!value.has("oracle_trace_refs")||value.path("oracle_trace_refs").isArray()&&value.path("oracle_trace_refs").isEmpty(),"Oracle claims without optional section");
            return;
        }
        List<JsonNode> actual=array(coverage,"oracle_trace",LIMIT,false);
        String resultSha=LocalFiles.sha256(canonical);
        // The existing caller already parsed/admitted result with its legal payload budget.
        // Recheck raw bytes against that source claim without reopening invocation inputs.
        List<JsonNode> sources=array(coverage,"sources",1,false);
        require(sources.size()==1&&canonical.toString().equals(text(sources.get(0),"path"))
                &&resultSha.equals(text(sources.get(0),"sha256")),"Canonical result changed before oracle validation");
        JsonNode provenance=Json.MAPPER.createObjectNode();Path provenancePath=null;String provenanceSha=null;
        if(result.has("product_test")) {
            provenancePath=LocalFiles.contained(canonical.getParent(),canonical.getParent().resolve("product-test/reviewed-handoff.json"));
            byte[] raw=LocalFiles.read(provenancePath,2*1024*1024);provenanceSha=LocalFiles.sha256(raw);
            require(provenanceSha.equals(text(result.path("product_test"),"provenance_sha256")),"Canonical provenance digest mismatch");
            provenance=Json.readMetadataUtf8(raw);
            require(Set.of("product-test-reviewed-handoff.v1","product-test-reviewed-handoff.v2").contains(text(provenance,"contract_version"))
                    && provenance.path("contract_version").equals(result.path("product_test").path("contract_version")),"Canonical provenance version mismatch");
        }
        boolean v2="product-test-reviewed-handoff.v2".equals(provenance.path("contract_version").asText());
        String evidence=evidenceState(coverage,result);
        Map<String,JsonNode> tests=new LinkedHashMap<>(),cases=new LinkedHashMap<>();
        for(JsonNode test:array(result,"test_results",LIMIT,false))require(tests.putIfAbsent(text(test,"test_case_id"),test)==null,"Duplicate canonical case");
        for(JsonNode c:array(coverage,"cases",LIMIT,false))require(cases.putIfAbsent(text(c,"test_case_id"),c)==null,"Duplicate projected case");
        require(cases.keySet().equals(tests.keySet()),"Projected case set mismatch");
        List<JsonNode> entities=array(coverage,"entities",LIMIT,false);
        Map<String,JsonNode> expected=new LinkedHashMap<>();
        for(var entry:tests.entrySet()) {
            String id=entry.getKey(),qualified=v2?qualifiedCase(provenance,id):null;
            JsonNode c=cases.get(id);
            require(evidence.equals(text(c,"evidence_state")),"Recorded evidence qualification mismatch");
            require(Objects.equals(qualified,nullable(c,"qualified_case_ref")),"Qualified case mismatch");
            for(JsonNode row:expectedRows(coverage,provenance,entry.getValue(),canonical,result,resultSha,evidence,qualified,entities,v2)) {
                String key=text(row,"trace_id");JsonNode old=expected.putIfAbsent(key,row);
                require(old==null||old.equals(row),"Conflicting canonical oracle membership");
                require(expected.size()<=LIMIT,"Oracle row limit");
            }
        }
        Set<String> seen=new HashSet<>();
        for(JsonNode row:actual) {
            rowSchema(row);String key=text(row,"trace_id");require(seen.add(key),"Duplicate oracle row");
            require(row.equals(expected.get(key)),"Row differs from pinned canonical declaration/source");
        }
        require(seen.equals(expected.keySet()),"Missing or extra canonical oracle row");
        for(var entry:cases.entrySet()) {
            Set<String> refs=new LinkedHashSet<>();
            for(JsonNode row:expected.values())if(entry.getKey().equals(text(row,"test_case_id")))refs.add(text(row,"trace_id"));
            require(refs.equals(new LinkedHashSet<>(strings(entry.getValue().path("oracle_trace_refs"),false))),"Case oracle references mismatch");
        }
        Set<List<String>> entityKeys=new HashSet<>();
        for(JsonNode entity:entities) {
            String kind=text(entity,"kind"),id=text(entity,"id");
            require(Set.of("requirement","acceptance_criterion").contains(kind)&&entityKeys.add(List.of(kind,id)),"Invalid or duplicate oracle entity");
            String field=kind.equals("requirement")?"requirement_refs":"acceptance_criteria_refs";Set<String> refs=new LinkedHashSet<>();
            for(JsonNode row:expected.values())if(contains(row.path(field),id))refs.add(text(row,"trace_id"));
            require(refs.equals(new LinkedHashSet<>(strings(entity.path("oracle_trace_refs"),false))),"Entity oracle references mismatch");
        }
        require(resultSha.equals(LocalFiles.sha256(canonical)),"Canonical result changed during oracle validation");
        if(provenancePath!=null)require(provenanceSha.equals(LocalFiles.sha256(provenancePath)),"Canonical provenance changed during oracle validation");
    }

    private static String evidenceState(JsonNode coverage,JsonNode result) {
        JsonNode selection=coverage.path("selection");String policy=text(selection,"policy");
        Instant end=Instant.parse(text(result,"end_time")),asOf=Instant.parse(text(selection,"as_of"));
        if(policy.equals("explicit_result_only")) {
            require(end.equals(asOf)&&selection.has("max_age_seconds")&&selection.path("max_age_seconds").isNull(),"Explicit selection qualification mismatch");return "VALID";
        }
        require(policy.equals("latest_compatible_per_case")&&!asOf.isBefore(end),"Unsupported recorded selection");
        JsonNode age=selection.path("max_age_seconds");require(age.isIntegralNumber()&&age.canConvertToLong()&&age.asLong()>=0,"Invalid recorded age");
        return Duration.between(end,asOf).getSeconds()>age.asLong()?"STALE":"VALID";
    }

    private static List<JsonNode> expectedRows(JsonNode coverage,JsonNode p,JsonNode test,Path path,JsonNode result,String digest,
            String evidence,String qualified,List<JsonNode> entities,boolean v2)throws Exception {
        String id=text(test,"test_case_id");List<JsonNode> mappings=new ArrayList<>();
        for(JsonNode m:array(p,"requirement_mapping",LIMIT,true))if(strings(m.path("test_case_ids"),false).contains(id))mappings.add(m);
        if(mappings.isEmpty())mappings.add(Json.MAPPER.createObjectNode());List<JsonNode> rows=new ArrayList<>();
        for(JsonNode mapping:mappings) {
            List<String> declared=strings(mapping.path("oracle_refs"),true);
            List<String> pointers=declared.isEmpty()?Collections.singletonList(null):declared;
            String requirement=nullable(mapping,"requirement_ref");ArrayNode knowledge=Json.MAPPER.createArrayNode();
            Map<String,JsonNode> oracles=new LinkedHashMap<>();
            if(v2)for(JsonNode trace:array(p,"knowledge_trace",7680,false)) {
                if(!id.equals(text(trace,"test_case_id"))||!qualified.equals(text(trace,"qualified_case_ref"))
                        ||requirement!=null&&!strings(trace.path("requirement_refs"),false).contains(requirement))continue;
                ObjectNode ref=obj("qualified_source_ref",trace.get("qualified_source_ref"),"record_version",trace.get("record_version"),
                        "content_sha256",trace.get("content_sha256"),"selection_proof_sha256",trace.get("selection_proof_sha256"),
                        "context_sha256",trace.get("context_sha256"),"source_bindings_sha256",trace.get("source_bindings_sha256"));
                if(!containsNode(knowledge,ref))knowledge.add(ref);
                JsonNode oracle=trace.path("oracle");exact(oracle,Set.of("qualified_ref","sha256","review_ref","reviewed_by"));
                oracles.putIfAbsent(text(oracle,"qualified_ref"),oracle);
            }
            for(String pointer:pointers) {
                String binding="MISSING",reason=pointer==null?"oracle_reference_missing":null;ObjectNode oracle=emptyOracle();
                JsonNode artifact=null,selected=null;
                if(v2) {
                    for(JsonNode o:oracles.values()) {
                        JsonNode a=artifact(p,text(o,"qualified_ref"),"oracle",null);
                        require(text(a,"sha256").equals(text(o,"sha256")),"Canonical oracle hash join mismatch");
                        if(pointer!=null&&pointer.equals(text(a,"reviewed_path"))) {
                            require(artifact==null,"Ambiguous canonical pointer binding");artifact=a;selected=o;
                        }
                    }
                    if(artifact==null&&oracles.size()==1){selected=oracles.values().iterator().next();artifact=artifact(p,text(selected,"qualified_ref"),"oracle",null);}
                    if(artifact!=null) {
                        oracle=obj("qualified_ref",selected.get("qualified_ref"),"path",artifact.get("reviewed_path"),"sha256",selected.get("sha256"),
                                "source_repo",artifact.get("source_repo"),"source_commit",artifact.get("source_commit"),"source_testrepo_version",artifact.get("source_testrepo_version"),"review_ref",selected.get("review_ref"),"reviewed_by",selected.get("reviewed_by"));binding="SOURCE_BOUND";
                    } else if(oracles.size()>1)binding="AMBIGUOUS";
                } else if(pointer!=null&&!pointer.contains("#")) {
                    JsonNode reviewed=reviewed(p,pointer,"oracle");
                    if(reviewed!=null){oracle=obj("qualified_ref",null,"path",pointer,"sha256",reviewed.get("sha256"),"source_repo",null,"source_commit",null,"source_testrepo_version",null,"review_ref",nullable(mapping,"review_ref"),"reviewed_by",nullable(p,"reviewed_by"));binding="SOURCE_BOUND";}
                    else binding="DECLARED_ONLY";
                } else if(pointer!=null)binding="DECLARED_ONLY";
                String expectedRef=null;
                if(pointer!=null) {
                    if(!mapping.path("test_case_ids").isArray()||mapping.path("test_case_ids").size()!=1||declared.size()!=1)reason="mapping_case_oracle_join_ambiguous";
                    else if(pointer.contains("#"))reason="declared_assertion_relation_not_exported";
                    else if(nullable(mapping,"review_ref")==null)reason="mapping_review_missing";
                    else if(binding.equals("SOURCE_BOUND")&&pointer.equals(oracle.path("path").asText()))expectedRef=pointer;
                    else reason="oracle_source_binding_missing";
                }
                if(evidence.equals("STALE")){binding="STALE";expectedRef=null;reason="selected_evidence_stale";}
                ArrayNode reqs=Json.MAPPER.createArrayNode(),acs=Json.MAPPER.createArrayNode();if(requirement!=null)reqs.add(requirement);
                for(JsonNode entity:entities)if("acceptance_criterion".equals(entity.path("kind").asText())
                        &&Objects.equals(requirement,nullable(entity,"parent_requirement_id"))
                        &&strings(entity.path("test_case_ids"),true).contains(qualified==null?id:qualified))acs.add(text(entity,"id"));
                if("UNKNOWN".equals(coverage.path("inventory").path("state").asText()))require(acs.isEmpty(),"UNKNOWN inventory has authoritative AC oracle claims");
                String traceId=LocalFiles.sha256(Json.MAPPER.writeValueAsBytes(Arrays.asList(digest,nullable(coverage.path("subject"),"project_ref"),nullable(coverage.path("subject"),"workspace_ref"),qualified==null?id:qualified,requirement,nullable(mapping,"review_ref"),pointer)));
                rows.add(obj("trace_id",traceId,"test_case_id",id,"qualified_case_ref",qualified,"source_result",path.toString(),"source_result_sha256",digest,
                        "run_id",result.get("run_id"),"batch_id",result.get("batch_id"),"canonical_outcome",outcome(text(test,"status")),"evidence_state",evidence,
                        "requirement_refs",reqs,"acceptance_criteria_refs",acs,"mapping_review_ref",nullable(mapping,"review_ref"),"declared_oracle_refs",declared,"oracle",oracle,
                        "expected_ref",expectedRef,"expected_ref_state",expectedRef==null?"UNKNOWN":"BOUND_DECLARED_REFERENCE","expected_ref_reason",expectedRef==null?reason:null,
                        "binding_state",binding,"binding_reason",reason,"independence",obj("state","UNKNOWN","reason","independence_attestation_not_in_contract"),"knowledge_refs",knowledge));
                require(rows.size()<=LIMIT,"Oracle row limit");
            }
        }
        return rows;
    }

    private static String qualifiedCase(JsonNode p,String id) {
        Set<String> refs=new LinkedHashSet<>();
        for(JsonNode trace:array(p,"knowledge_trace",7680,false))if(id.equals(text(trace,"test_case_id")))refs.add(text(trace,"qualified_case_ref"));
        require(refs.size()==1,"Missing or ambiguous qualified case");String qualified=refs.iterator().next();
        artifact(p,qualified,"case",id);return qualified;
    }
    private static JsonNode artifact(JsonNode p,String qualified,String role,String testId) {
        JsonNode found=null;
        for(JsonNode a:array(p,"executed_source_bindings",128,false))if(qualified.equals(text(a,"artifact_id"))) {
            require(found==null&&role.equals(text(a,"role")),"Missing/ambiguous source role");found=a;
        }
        require(found!=null,"Missing qualified source binding");
        String repo=text(found,"source_repo"),commit=text(found,"source_commit"),sourcePath=relative(text(found,"source_path")),sourceId=text(found,"source_id");
        require(qualified.equals(repo+"@"+commit+":"+sourcePath+"#"+sourceId)&&commit.equals(text(found,"source_testrepo_version")),"Qualified source identity/version mismatch");
        if(testId!=null)require(testId.equals(sourceId),"Selected case source ID mismatch");
        String version=null;
        for(JsonNode v:array(p,"source_repository_versions",16,false))if(repo.equals(text(v,"source_repo"))) {
            require(version==null,"Ambiguous source repository version");version=text(v,"source_testrepo_version");
        }
        require(commit.equals(version),"Source artifact version differs from admitted repository version");
        JsonNode file=reviewed(p,relative(text(found,"reviewed_path")),role.equals("case")?"test":"oracle");
        require(file!=null&&text(file,"sha256").equals(text(found,"sha256")),"Source reviewed role/hash mismatch");return found;
    }
    private static JsonNode reviewed(JsonNode p,String path,String role) {
        JsonNode found=null;
        for(JsonNode file:array(p,"reviewed_files",128,true))if(path.equals(text(file,"path"))) {
            require(found==null,"Duplicate reviewed source path");found=file;
        }
        if(found==null||!role.equals(text(found,"role")))return null;
        require(text(found,"sha256").matches("[a-f0-9]{64}"),"Invalid reviewed source hash");return found;
    }
    private static void rowSchema(JsonNode row) {
        exact(row,ROW);metadata(row,0);
        for(String key:List.of("trace_id","test_case_id","source_result","source_result_sha256","run_id","batch_id","canonical_outcome","evidence_state","expected_ref_state","binding_state"))text(row,key);
        for(String key:List.of("qualified_case_ref","mapping_review_ref","expected_ref","expected_ref_reason","binding_reason"))nullable(row,key);
        require(text(row,"trace_id").matches("[a-f0-9]{64}")&&text(row,"source_result_sha256").matches("[a-f0-9]{64}"),"Invalid oracle result/trace digest");
        for(String key:List.of("requirement_refs","acceptance_criteria_refs","declared_oracle_refs"))strings(row.path(key),false);
        exact(row.path("oracle"),ORACLE);for(String key:ORACLE)nullable(row.path("oracle"),key);
        JsonNode hash=row.path("oracle").path("sha256");require(hash.isNull()||hash.asText().matches("[a-f0-9]{64}"),"Invalid oracle hash");
        exact(row.path("independence"),Set.of("state","reason"));require(row.path("independence").equals(obj("state","UNKNOWN","reason","independence_attestation_not_in_contract")),"Invented independence authority");
        Set<JsonNode> seen=new HashSet<>();
        for(JsonNode ref:array(row,"knowledge_refs",7680,false)) {
            exact(ref,KNOWLEDGE);require(seen.add(ref),"Duplicate public knowledge reference");text(ref,"qualified_source_ref");
            require(ref.path("record_version").isIntegralNumber()&&ref.path("record_version").canConvertToInt()&&ref.path("record_version").asInt()>0,"Invalid public record version");
            for(String key:List.of("content_sha256","selection_proof_sha256","context_sha256","source_bindings_sha256"))require(text(ref,key).matches("[a-f0-9]{64}"),"Invalid public knowledge digest");
        }
    }
    private static ObjectNode emptyOracle(){ObjectNode o=Json.MAPPER.createObjectNode();for(String key:ORACLE)o.putNull(key);return o;}
    private static ObjectNode obj(Object...pairs){ObjectNode node=Json.MAPPER.createObjectNode();for(int i=0;i<pairs.length;i+=2)node.set((String)pairs[i],Json.MAPPER.valueToTree(pairs[i+1]));return node;}
    private static List<JsonNode> array(JsonNode parent,String field,int limit,boolean optional) {
        JsonNode a=parent.path(field);if(optional&&a.isMissingNode())return List.of();require(a.isArray()&&a.size()<=limit,"Invalid bounded array: "+field);List<JsonNode> values=new ArrayList<>();a.forEach(values::add);return values;
    }
    private static List<String> strings(JsonNode a,boolean optional) {
        if(optional&&a.isMissingNode())return List.of();require(a.isArray()&&a.size()<=LIMIT,"Invalid string array");Set<String> values=new LinkedHashSet<>();
        for(JsonNode value:a)require(value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=4096&&values.add(value.asText()),"Invalid/duplicate metadata string");return new ArrayList<>(values);
    }
    private static String text(JsonNode node,String field){JsonNode value=node.path(field);require(value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=4096,"Missing/invalid text: "+field);return value.asText();}
    private static String nullable(JsonNode node,String field){JsonNode value=node.path(field);if(value.isMissingNode()||value.isNull())return null;return text(node,field);}
    private static void exact(JsonNode node,Set<String> fields){Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);require(node.isObject()&&actual.equals(fields),"Private/untyped or missing oracle fields");}
    private static void metadata(JsonNode node,int depth){require(depth<=80,"Oracle metadata depth");if(node.isTextual())require(node.asText().length()<=4096,"Oracle metadata string limit");else if(node.isContainerNode())for(JsonNode child:node)metadata(child,depth+1);}
    private static String relative(String value){require(!value.startsWith("/")&&!value.contains("\\")&&Arrays.stream(value.split("/",-1)).noneMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")),"Unsafe reviewed source path");return value;}
    private static boolean contains(JsonNode a,String v){for(JsonNode n:a)if(n.isTextual()&&n.asText().equals(v))return true;return false;}
    private static boolean containsNode(JsonNode a,JsonNode v){for(JsonNode n:a)if(n.equals(v))return true;return false;}
    private static String outcome(String v){return switch(v){case "passed"->"PASS";case "failed"->"FAIL";case "blocked"->"BLOCKED";case "skipped"->"SKIPPED";default->"NOT_RUN";};}
    private static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException("oracle coverage: "+reason);}
}
