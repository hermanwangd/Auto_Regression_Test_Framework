package com.featuredeliveryintelligence.fdi.producttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
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
        verify(coverage,canonical,result,request,null);
    }
    static void verify(JsonNode coverage,Path canonical,JsonNode result,ProductTestRequest request,Authority authority)throws Exception {
        require(authority!=null||!coverage.has("coverage_authority"),"Unbound authority claim");
        if(!coverage.has("oracle_trace")) {
            require(authority==null,"Bound authority requires oracle section");
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
        parserRelations(provenance);
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
        if(authority!=null){ObjectNode expectedDocument=(ObjectNode)coverage.deepCopy();ArrayNode rows=expectedDocument.putArray("oracle_trace");expected.values().forEach(rows::add);authority.apply(expectedDocument,Map.of(canonical.toString(),provenance));require(expectedDocument.path("coverage_authority").equals(coverage.path("coverage_authority")),"Authority output pin/reference mismatch");}
        Set<String> seen=new HashSet<>();
        for(JsonNode row:actual) {
            rowSchema(row,authority!=null);String key=text(row,"trace_id");require(seen.add(key),"Duplicate oracle row");
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
    private static void rowSchema(JsonNode row,boolean authority) {
        Set<String> fields=new HashSet<>(ROW);if(authority&&row.has("reviewed_binding"))fields.add("reviewed_binding");exact(row,fields);metadata(row,0);
        for(String key:List.of("trace_id","test_case_id","source_result","source_result_sha256","run_id","batch_id","canonical_outcome","evidence_state","expected_ref_state","binding_state"))text(row,key);
        for(String key:List.of("qualified_case_ref","mapping_review_ref","expected_ref","expected_ref_reason","binding_reason"))nullable(row,key);
        require(text(row,"trace_id").matches("[a-f0-9]{64}")&&text(row,"source_result_sha256").matches("[a-f0-9]{64}"),"Invalid oracle result/trace digest");
        for(String key:List.of("requirement_refs","acceptance_criteria_refs","declared_oracle_refs"))strings(row.path(key),false);
        exact(row.path("oracle"),ORACLE);for(String key:ORACLE)nullable(row.path("oracle"),key);
        JsonNode hash=row.path("oracle").path("sha256");require(hash.isNull()||hash.asText().matches("[a-f0-9]{64}"),"Invalid oracle hash");
        if(!authority){exact(row.path("independence"),Set.of("state","reason"));require(row.path("independence").equals(obj("state","UNKNOWN","reason","independence_attestation_not_in_contract")),"Invented independence authority");}
        Set<JsonNode> seen=new HashSet<>();
        for(JsonNode ref:array(row,"knowledge_refs",7680,false)) {
            exact(ref,KNOWLEDGE);require(seen.add(ref),"Duplicate public knowledge reference");text(ref,"qualified_source_ref");
            require(ref.path("record_version").isIntegralNumber()&&ref.path("record_version").canConvertToInt()&&ref.path("record_version").asInt()>0,"Invalid public record version");
            for(String key:List.of("content_sha256","selection_proof_sha256","context_sha256","source_bindings_sha256"))require(text(ref,key).matches("[a-f0-9]{64}"),"Invalid public knowledge digest");
        }
    }

    /** A pinned parser declaration is source metadata, never execution or independent oracle authority. */
    private static JsonNode parserRelations(JsonNode p) throws Exception {
        if(!p.has("parser_expected_artifact_relations"))return null;
        JsonNode section=p.path("parser_expected_artifact_relations");
        parserExact(section,Set.of("schema_version","state","manifest_path","manifest_sha256","assertions"));
        parserRequire("product-test-reviewed-handoff.v2".equals(p.path("contract_version").asText()),"v2 source required");
        parserRequire("parser-expected-artifact-relations.v1".equals(parserText(section,"schema_version"))&&"COMPLETE".equals(parserText(section,"state")),"version/state");
        parserRequire(Json.MAPPER.writeValueAsBytes(section).length<=262144,"byte limit");
        String manifest=parserPath(parserText(section,"manifest_path"));
        parserReviewed(p,manifest,"manifest",parserText(section,"manifest_sha256"));
        parserArtifact(p,null,manifest,"suite",parserText(section,"manifest_sha256"),null);
        JsonNode assertions=section.path("assertions");parserRequire(assertions.isArray()&&assertions.size()<=256,"assertion limit/type");
        Set<List<String>> seen=new HashSet<>();
        for(JsonNode row:assertions) {
            parserExact(row,Set.of("qualified_case_ref","case_path","case_sha256","test_case_id","assertion_id","assertion_operator","expected_operand","raw_expected_ref","normalized_expected_path","expected_artifact_sha256","qualified_expected_artifact_ref"));
            String qualified=parserText(row,"qualified_case_ref"),path=parserPath(parserText(row,"case_path")),id=parserText(row,"test_case_id");
            parserRequire(seen.add(List.of(qualified,parserText(row,"assertion_id"))),"duplicate assertion identity");
            parserRequire(Set.of("equals","not_equals","gt","gte","lt","lte","exists","not_exists","json_match").contains(parserText(row,"assertion_operator")),"operator");
            parserReviewed(p,path,"test",parserText(row,"case_sha256"));parserArtifact(p,qualified,path,"case",parserText(row,"case_sha256"),id);
            int selected=0;for(JsonNode selection:p.path("source_case_selection"))if(qualified.equals(selection.path("qualified_case_ref").asText())&&id.equals(selection.path("source_test_id").asText())&&"SELECTED".equals(selection.path("state").asText()))selected++;
            parserRequire(selected==1,"selected case source");
            String operand=parserText(row,"expected_operand");parserRequire(Set.of("EXPECTED_REF","EXPECTED","ABSENT").contains(operand),"operand");
            if("EXPECTED_REF".equals(operand)) {
                String raw=parserText(row,"raw_expected_ref");parserRequire(raw.startsWith("artifact://"),"static artifact URI");
                String uri=raw.substring(11);int slash=uri.indexOf('/');parserRequire(slash>0,"artifact namespace");parserPath(uri);
                String normalized=parserPath(parserText(row,"normalized_expected_path")),suffix=uri.substring(slash+1);
                parserRequire(normalized.equals(suffix)||normalized.endsWith("/"+suffix),"artifact URI/path suffix");
                parserReviewed(p,normalized,"oracle",parserText(row,"expected_artifact_sha256"));
                parserArtifact(p,parserText(row,"qualified_expected_artifact_ref"),normalized,"oracle",parserText(row,"expected_artifact_sha256"),null);
            } else for(String field:List.of("raw_expected_ref","normalized_expected_path","expected_artifact_sha256","qualified_expected_artifact_ref"))parserRequire(row.path(field).isNull(),"literal/absent operand has artifact fields");
        }
        return section;
    }
    private static void parserReviewed(JsonNode p,String path,String role,String sha) {
        parserRequire(sha.matches("[a-f0-9]{64}"),"invalid source digest");int found=0;
        for(JsonNode file:p.path("reviewed_files"))if(path.equals(file.path("path").asText())){found++;parserRequire(role.equals(file.path("role").asText())&&sha.equals(file.path("sha256").asText()),"reviewed role/hash join");}
        parserRequire(found==1,"reviewed source identity");
    }
    private static void parserArtifact(JsonNode p,String qualified,String path,String role,String sha,String id) {
        JsonNode found=null;for(JsonNode source:p.path("executed_source_bindings"))if(path.equals(source.path("reviewed_path").asText())){parserRequire(found==null,"ambiguous artifact path");found=source;}
        parserRequire(found!=null,"source artifact missing");
        String repo=parserText(found,"source_repo"),commit=parserText(found,"source_commit"),sourcePath=parserPath(parserText(found,"source_path")),sourceId=parserText(found,"source_id");
        parserRequire(commit.matches("[a-f0-9]{40}(?:[a-f0-9]{24})?")&&commit.equals(found.path("source_testrepo_version").asText())&&role.equals(found.path("role").asText())&&sha.equals(found.path("sha256").asText()),"artifact role/hash/version");
        String ref=repo+"@"+commit+":"+sourcePath+"#"+sourceId;parserRequire(ref.equals(found.path("artifact_id").asText())&&(qualified==null||qualified.equals(ref))&&(id==null||id.equals(sourceId)),"qualified artifact identity");
        int versions=0;for(JsonNode version:p.path("source_repository_versions"))if(repo.equals(version.path("source_repo").asText())){versions++;parserRequire(commit.equals(version.path("source_testrepo_version").asText()),"artifact A/source version");}parserRequire(versions==1,"repository source version");
    }
    private static String parserPath(String path) {parserRequire(!path.startsWith("/")&&!path.contains("\\")&&!path.contains("%")&&!path.contains("#")&&!path.contains("$")&&Arrays.stream(path.split("/",-1)).noneMatch(part->part.isEmpty()||part.equals(".")||part.equals("..")),"unsafe source reference");return path;}
    private static String parserText(JsonNode node,String field){JsonNode value=node.path(field);parserRequire(value.isTextual()&&!value.asText().isBlank()&&value.asText().length()<=4096&&!value.asText().codePoints().anyMatch(Character::isISOControl),"text field");return value.asText();}
    private static void parserExact(JsonNode node,Set<String> fields){Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);parserRequire(node.isObject()&&actual.equals(fields),"closed fields");}
    private static void parserRequire(boolean value,String reason){if(!value)throw new IllegalArgumentException("parser expected artifact metadata: "+reason);}

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
static final class Authority {
    private final ObjectMapper json;
    private final Path path;
    private final JsonNode input;
    private final String digest;
    private final Map<Path,String> snapshots=new LinkedHashMap<>();
    private final Map<Path,byte[]> consumed=new LinkedHashMap<>();
    private final Map<String,JsonNode> statements=new LinkedHashMap<>();
    private int bytes;
    private Path retainedRoot;
    private static final Set<String> BINDING=Set.of("qualified_case_ref","case_sha256","assertion_ref","expected_ref","qualified_oracle_ref","oracle_sha256","source_repo","source_commit","review_ref");
    Authority(Path path,String expected,ObjectMapper json)throws Exception {
        this.json=json;
        try {
        require(path!=null&&expected!=null&&expected.matches("[a-f0-9]{64}"),"independent authority path and pin required");
        this.path=regular(path);byte[] raw=consume(this.path);digest=hash(raw);require(expected.equals(digest),"independent authority pin mismatch");input=object(raw);
        exact(input,Set.of("schema_version","scope","authority","catalog","assertion_bindings","independent_attestations"));require("coverage_authority.v1".equals(text(input,"schema_version")),"unsupported authority schema");
        exact(input.path("scope"),Set.of("domain","project_ref","workspace_ref","repository_revisions"));
        for(String f:List.of("domain","project_ref","workspace_ref"))text(input.path("scope"),f);
        JsonNode revisions=input.path("scope").path("repository_revisions");require(revisions.isObject()&&!revisions.isEmpty(),"authority revisions missing");for(var it=revisions.fields();it.hasNext();)require(it.next().getValue().isTextual(),"invalid authority revision");
        JsonNode approval=input.path("authority");exact(approval,Set.of("review_ref","reviewed_by","approved_at"));text(approval,"review_ref");text(approval,"reviewed_by");instant(approval,"approved_at");
        if(!input.path("catalog").isNull()){exact(input.path("catalog"),Set.of("path","sha256"));Path catalog=artifact(input.path("catalog"));validateCatalogInput(object(consumed.get(catalog)));}
        Set<String> unique=new HashSet<>();
        for(JsonNode binding:array(input,"assertion_bindings")){validateBinding(binding);require(unique.add(key(binding)),"duplicate or ambiguous assertion binding");}
        require(input.path("assertion_bindings").size()+input.path("independent_attestations").size()<=10000,"authority row limit");
        for(JsonNode admission:array(input,"independent_attestations")) {
            exact(admission,Set.of("path","sha256","attestor","review_ref"));text(admission,"review_ref");Path p=artifact(admission);JsonNode statement=object(consumed.get(p));
            exact(statement,Set.of("schema_version","binding","attestor","issued_at","expires_at","origin_statement","origin_evidence_refs"));require("oracle_independence_attestation.v1".equals(text(statement,"schema_version")),"unsupported attestation schema");
            validateBinding(statement.path("binding"));require(text(admission,"attestor").equals(text(statement,"attestor")),"attestation attestor mismatch");text(statement,"origin_statement");
            Set<String> refs=new HashSet<>();JsonNode evidence=statement.path("origin_evidence_refs");require(evidence.isArray()&&!evidence.isEmpty()&&evidence.size()<=10000,"origin evidence references missing");for(JsonNode ref:evidence)require(ref.isTextual()&&!ref.asText().isBlank()&&refs.add(ref.asText()),"invalid origin evidence reference");
            Instant issued=instant(statement,"issued_at");if(!statement.path("expires_at").isNull())require(instant(statement,"expires_at").isAfter(issued),"invalid attestation time ordering");
            String key=key(statement.path("binding"));require(unique.contains(key),"attestation has no admitted assertion binding");JsonNode binding=null;for(JsonNode b:input.path("assertion_bindings"))if(key(b).equals(key))binding=b;
            require(statement.path("binding").equals(binding),"attestation binding mismatch");require(statements.putIfAbsent(key,obj("admission",admission,"statement",statement,"path",p.toString()))==null,"duplicate attestation binding");
        }
        } catch(Exception failure) {throw rejection(failure);}
    }
    Path catalogPath(){return input.path("catalog").isNull()?null:resolve(text(input.path("catalog"),"path"));}
    boolean active(Instant asOf){return !instant(input.path("authority"),"approved_at").isAfter(asOf);}
    boolean catalogActive(Instant asOf){if(!active(asOf))return false;try{return catalogPath()==null||!instant(object(consumed.get(catalogPath())).path("authority"),"approved_at").isAfter(asOf);}catch(RuntimeException e){throw e;}catch(Exception e){throw rejection(e);}}
    void verifyCatalogTiming(Instant asOf)throws Exception {
        if(catalogPath()==null||!catalogActive(asOf))return;
        for(JsonNode ac:array(object(consumed.get(catalogPath())),"acceptance_criteria")) {
            if(!Set.of("manual_only","waived").contains(ac.path("classification").asText()))continue;
            JsonNode exclusion=ac.path("exclusion");boolean complete=exclusion.isObject();
            for(String field:List.of("reason","review_ref","approved_by","approved_at"))complete&=exclusion.path(field).isTextual()&&!exclusion.path(field).asText().isBlank();
            if(!complete)continue;
            Instant approved;try{approved=Instant.parse(exclusion.path("approved_at").asText());}catch(java.time.format.DateTimeParseException unapproved){continue;}
            if(approved.isAfter(asOf))throw new Rejected("COVERAGE_AUTHORITY_EXCLUSION_FUTURE");
        }
    }
    void verifyScope(JsonNode subject){for(String field:List.of("domain","project_ref","workspace_ref","repository_revisions"))require(input.path("scope").path(field).equals(subject.path(field)),"authority scope/revision mismatch: "+field);}
    void apply(ObjectNode report,Map<String,JsonNode> provenance)throws Exception {
        verifyScope(report.path("subject"));
        Instant asOf=instant(report.path("selection"),"as_of");verifyCatalogTiming(asOf);boolean active=active(asOf);
        ObjectNode published=obj("state",active?"BOUND_REVIEWED":"UNKNOWN","reason",active?null:"authority_approval_future","path",path.toString(),"sha256",digest,"scope",input.path("scope"),"authority",input.path("authority"),"catalog",input.path("catalog"));
        ArrayNode files=published.putArray("artifacts");for(var e:snapshots.entrySet())files.add(obj("path",e.getKey().toString(),"sha256",e.getValue()));report.set("coverage_authority",published);
        Map<String,JsonNode> rowBindings=new HashMap<>();
        for(JsonNode relation:input.path("assertion_bindings")) {
            int matches=0;
            for(JsonNode value:report.path("oracle_trace")) {
                if(!text(relation,"qualified_case_ref").equals(value.path("qualified_case_ref").asText()))continue;
                if(!contains(value.path("declared_oracle_refs"),text(relation,"assertion_ref"))||!contains(value.path("declared_oracle_refs"),text(relation,"expected_ref"))||!text(relation,"qualified_oracle_ref").equals(value.path("oracle").path("qualified_ref").asText()))continue;
                JsonNode p=provenance.get(text(value,"source_result"));require(p!=null,"authority canonical provenance missing");JsonNode c=null;
                for(JsonNode source:p.path("executed_source_bindings"))if(text(relation,"qualified_case_ref").equals(source.path("artifact_id").asText())){require(c==null,"ambiguous authority case source");c=source;}
                require(c!=null&&"case".equals(c.path("role").asText())&&text(relation,"case_sha256").equals(c.path("sha256").asText()),"authority case/source hash mismatch");
                String assertion=text(relation,"assertion_ref");require(assertion.startsWith(text(c,"reviewed_path")+"#")&&assertion.length()>text(c,"reviewed_path").length()+1,"assertion is not in admitted case source");
                JsonNode oracle=value.path("oracle");for(String[] fields:List.of(new String[]{"qualified_oracle_ref","qualified_ref"},new String[]{"expected_ref","path"},new String[]{"oracle_sha256","sha256"},new String[]{"source_repo","source_repo"},new String[]{"source_commit","source_commit"}))require(text(relation,fields[0]).equals(oracle.path(fields[1]).asText()),"authority oracle/source identity mismatch: "+fields[0]);
                // Case qualified identity carries its own artifact revision; oracle source fields never become root composition X.
                matches++;ObjectNode row=(ObjectNode)value;JsonNode prior=rowBindings.putIfAbsent(text(row,"trace_id"),relation);require(prior==null||prior.equals(relation),"ambiguous reviewed assertion bindings for projected row");
                if(!active||!"VALID".equals(row.path("evidence_state").asText())){row.set("independence",obj("state","UNKNOWN","reason",!active?"authority_approval_future":"selected_evidence_"+row.path("evidence_state").asText().toLowerCase(Locale.ROOT)));continue;}
                require("SOURCE_BOUND".equals(row.path("binding_state").asText()),"authority cannot promote unbound source");
                row.put("expected_ref",text(relation,"expected_ref")).put("expected_ref_state","DECLARED_REVIEWED").putNull("expected_ref_reason");row.set("reviewed_binding",relation.deepCopy());
                JsonNode entry=statements.get(key(relation));
                if(entry==null){row.set("independence",obj("state","UNKNOWN","reason","independence_attestation_missing"));continue;}
                JsonNode statement=entry.path("statement"),admission=entry.path("admission");String reason=instant(statement,"issued_at").isAfter(asOf)?"attestation_issued_in_future":!statement.path("expires_at").isNull()&&!asOf.isBefore(instant(statement,"expires_at"))?"attestation_expired":null;
                row.set("independence",reason==null?obj("state","REVIEW_ATTESTED","reason",null,"attestation_ref",entry.get("path"),"attestation_sha256",admission.get("sha256"),"attestor",admission.get("attestor"),"review_ref",admission.get("review_ref"),"issued_at",statement.get("issued_at"),"expires_at",statement.get("expires_at"),"qualification","Accepted reviewed origin statement only; not execution or proof of business correctness."):obj("state","UNKNOWN","reason",reason));
            }
            require(matches>0,"authority binding has no admitted canonical case/oracle operands");
        }
        verifyUnchanged();
    }
    void verifyRetainedClosure(Path root)throws Exception {
        root=root.toAbsolutePath().normalize();require(path.getParent().equals(root),"authority snapshot root mismatch");int count=0;Set<Path>seen=new HashSet<>();try(var walk=Files.walk(root)){var iterator=walk.iterator();while(iterator.hasNext()){Path entry=iterator.next();require(++count<=1153,"authority retained path count limit");require(!Files.isSymbolicLink(entry),"authority retained symlink");if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)){boolean ancestor=false;for(Path expected:snapshots.keySet())if(expected.startsWith(entry)){ancestor=true;break;}require(ancestor,"unexpected authority snapshot directory");}else{require(snapshots.containsKey(entry),"unreferenced authority snapshot file");require(snapshots.get(entry).equals(hash(bounded(regular(entry)))),"authority snapshot digest mismatch");seen.add(entry);}}}require(seen.equals(snapshots.keySet()),"authority snapshot reference missing");
    }
    void verifyUnchanged()throws Exception {if(retainedRoot!=null)verifyRetainedClosure(retainedRoot);for(var e:snapshots.entrySet())require(e.getValue().equals(hash(bounded(regular(e.getKey())))),"authority input changed while consumed");}
    Map<Path,String> snapshots(){return Collections.unmodifiableMap(snapshots);}
    private void validateCatalogInput(JsonNode catalog){
        require(!catalog.has("code_coverage_artifacts"),"code_coverage_artifacts unsupported by coverage_authority.v1 inventory profile; use the existing separate pinned code coverage input");
        closedCatalog(catalog,Set.of("schema_version","catalog_revision","scope","authority","requirements","acceptance_criteria"),Set.of());
        closedCatalog(catalog.path("scope"),Set.of("domain","project_ref","workspace_ref","repository_revisions"),Set.of());
        closedCatalog(catalog.path("authority"),Set.of("kind","source_ref","source_sha256","review_ref","approved_by","approved_at"),Set.of());
        for(JsonNode requirement:array(catalog,"requirements"))closedCatalog(requirement,Set.of("id","ac_ids"),Set.of());
        for(JsonNode criterion:array(catalog,"acceptance_criteria")){
            closedCatalog(criterion,Set.of("id","requirement_id","classification"),Set.of("test_case_ids","review_ref","exclusion"));
            if(criterion.hasNonNull("review_ref"))text(criterion,"review_ref");
            if(criterion.hasNonNull("exclusion")){JsonNode exclusion=criterion.path("exclusion");closedCatalog(exclusion,Set.of(),Set.of("reason","review_ref","approved_by","approved_at"));for(var fields=exclusion.fieldNames();fields.hasNext();)text(exclusion,fields.next());}
        }
        require("coverage-catalog.v1".equals(text(catalog,"schema_version")),"catalog schema mismatch");text(catalog,"catalog_revision");JsonNode approval=catalog.path("authority");require("reviewed_inventory".equals(text(approval,"kind")),"catalog authority kind mismatch");for(String f:List.of("source_ref","review_ref","approved_by"))text(approval,f);require(text(approval,"source_sha256").matches("[a-f0-9]{64}"),"catalog authority hash invalid");instant(approval,"approved_at");
        for(String f:List.of("domain","project_ref","workspace_ref","repository_revisions"))require(catalog.path("scope").path(f).equals(input.path("scope").path(f)),"catalog scope mismatch: "+f);
        Map<String,JsonNode> requirements=new LinkedHashMap<>(),criteria=new LinkedHashMap<>();for(JsonNode row:array(catalog,"requirements"))require(requirements.putIfAbsent(text(row,"id"),row)==null,"catalog duplicate requirement");for(JsonNode row:array(catalog,"acceptance_criteria"))require(criteria.putIfAbsent(text(row,"id"),row)==null,"catalog duplicate AC");
        for(var entry:criteria.entrySet()){JsonNode ac=entry.getValue();require(requirements.containsKey(text(ac,"requirement_id")),"catalog orphan AC");require(Set.of("automatable","partial","manual_only","waived").contains(text(ac,"classification")),"catalog classification invalid");if(!inputStrings(ac.path("test_case_ids"),true).isEmpty())text(ac,"review_ref");}
        for(var entry:requirements.entrySet()){List<String> ids=inputStrings(entry.getValue().path("ac_ids"),false);for(String id:ids)require(criteria.containsKey(id)&&entry.getKey().equals(criteria.get(id).path("requirement_id").asText()),"catalog requirement/AC linkage mismatch");for(var ac:criteria.entrySet())if(entry.getKey().equals(ac.getValue().path("requirement_id").asText()))require(ids.contains(ac.getKey()),"catalog missing child AC linkage");}
    }
    private static void closedCatalog(JsonNode node,Set<String> required,Set<String> optional){Set<String> actual=new HashSet<>(),allowed=new HashSet<>(required);node.fieldNames().forEachRemaining(actual::add);allowed.addAll(optional);require(node.isObject()&&actual.containsAll(required)&&allowed.containsAll(actual),"catalog fields invalid for inventory profile");}
    private static List<String> inputStrings(JsonNode node,boolean optional){if(optional&&node.isMissingNode())return List.of();require(node.isArray()&&node.size()<=10000,"catalog string array invalid");List<String> values=new ArrayList<>();for(JsonNode value:node)require(value.isTextual()&&!value.asText().isBlank()&&!values.contains(value.asText())&&values.add(value.asText()),"catalog duplicate/invalid string");return values;}
    private void validateBinding(JsonNode b){exact(b,BINDING);for(String f:BINDING)text(b,f);for(String f:List.of("case_sha256","oracle_sha256"))require(text(b,f).matches("[a-f0-9]{64}"),"invalid binding hash");relative(text(b,"expected_ref"));String a=text(b,"assertion_ref");require(a.indexOf('#')>0&&a.indexOf('#')==a.lastIndexOf('#'),"invalid assertion reference");relative(a.substring(0,a.indexOf('#')));}
    private String key(JsonNode b){return text(b,"qualified_case_ref")+"\n"+text(b,"assertion_ref");}
    private Path artifact(JsonNode entry)throws Exception{Path p=regular(resolve(text(entry,"path")));require(text(entry,"sha256").matches("[a-f0-9]{64}")&&text(entry,"sha256").equals(hash(consume(p))),"authority artifact hash mismatch");return p;}
    private Path resolve(String ref){relative(ref);return path.getParent().resolve(ref).normalize();}
    private static void relative(String ref){try{Path p=Path.of(ref);require(!p.isAbsolute()&&!ref.contains("\\")&&p.normalize().toString().equals(ref)&&!ref.startsWith("../")&&!ref.equals("..")&&!ref.equals(".")&&p.getNameCount()<=8,"unsafe authority relative reference");}catch(Rejected safe){throw rejection(safe);}catch(Exception invalid){throw new Rejected("COVERAGE_AUTHORITY_INVALID_REFERENCE");}}
    private static byte[] bounded(Path p)throws Exception{require(Files.size(p)<=2*1024*1024,"authority metadata byte limit");try(var in=Files.newInputStream(p)){byte[] raw=in.readNBytes(2*1024*1024+1);require(raw.length<=2*1024*1024,"authority metadata byte limit");return raw;}}
    private byte[] consume(Path p)throws Exception{if(consumed.containsKey(p))return consumed.get(p);require(consumed.size()<128,"authority artifact count limit");require(Files.size(p)<=2*1024*1024&&bytes+Files.size(p)<=2*1024*1024,"authority closure byte limit");byte[] raw;try(var in=Files.newInputStream(p)){raw=in.readNBytes(2*1024*1024+1);}bytes+=raw.length;require(bytes<=2*1024*1024,"authority closure byte limit");snapshots.put(p,hash(raw));consumed.put(p,raw);return raw;}
    private static Path regular(Path path)throws Exception {Path p=path.toAbsolutePath().normalize();require(p.toString().length()<=1024,"authority path length limit");for(Path q=p;q!=null;q=q.getParent())require(!Files.isSymbolicLink(q),"authority symlink prohibited");require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"authority regular file missing");require(((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,"authority hardlink prohibited");return p;}
    private JsonNode object(byte[] raw)throws Exception {
        try {
            String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            JsonNode n=json.readTree(decoded);require(n!=null&&n.isObject(),"authority JSON object required");metadata(n,0);return n;
        } catch(Rejected safe) {throw rejection(safe);}
        catch(Exception invalid) {throw new Rejected("COVERAGE_AUTHORITY_INVALID_JSON");}
    }

    private static void metadata(JsonNode n,int depth){require(depth<=80,"authority metadata depth");if(n.isTextual())require(n.asText().length()<=4096,"authority metadata string limit");else if(n.isContainerNode()){if(n.isObject())for(var keys=n.fieldNames();keys.hasNext();)require(keys.next().length()<=4096,"authority metadata key limit");for(JsonNode v:n)metadata(v,depth+1);}}
    private ObjectNode obj(Object...values){ObjectNode n=json.createObjectNode();for(int i=0;i<values.length;i+=2)n.set((String)values[i],json.valueToTree(values[i+1]));return n;}
    private static List<JsonNode> array(JsonNode n,String f){JsonNode a=n.path(f);require(a.isArray()&&a.size()<=10000,"invalid authority array: "+f);List<JsonNode> out=new ArrayList<>();a.forEach(out::add);return out;}
    private static boolean contains(JsonNode values,String target){for(JsonNode v:values)if(v.isTextual()&&target.equals(v.asText()))return true;return false;}
    private static String text(JsonNode n,String f){JsonNode v=n.path(f);require(v.isTextual()&&!v.asText().isBlank()&&v.asText().length()<=4096,"invalid authority text: "+f);return v.asText();}
    private static Instant instant(JsonNode n,String f){try{return Instant.parse(text(n,f));}catch(Rejected safe){throw rejection(safe);}catch(Exception invalid){throw new Rejected("COVERAGE_AUTHORITY_INVALID_TIMESTAMP");}}
    private static void exact(JsonNode n,Set<String> fields){Set<String> actual=new HashSet<>();n.fieldNames().forEachRemaining(actual::add);require(n.isObject()&&actual.equals(fields),"invalid/untyped authority fields");}
    private static String hash(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    static IllegalArgumentException rejection(Exception failure){return new Rejected(failure instanceof Rejected?failure.getMessage():"COVERAGE_AUTHORITY_INPUT_REJECTED");}
    private static final class Rejected extends IllegalArgumentException {private Rejected(String message){super(message,null);}}
    private static void require(boolean condition,String message){if(!condition)throw new Rejected("COVERAGE_AUTHORITY_REJECTED: coverage authority: "+message);}
    void verifyCatalog(JsonNode report)throws Exception {
        verifyCatalogTiming(instant(report.path("selection"),"as_of"));
        if(catalogPath()==null||!catalogActive(instant(report.path("selection"),"as_of"))){require("UNKNOWN".equals(report.path("inventory").path("state").asText()),"catalog absent/inactive cannot establish inventory");require(report.path("entities").isArray()&&report.path("entities").isEmpty(),"catalog absent/inactive cannot establish entities");for(String kind:List.of("requirements","acceptance_criteria"))for(String field:List.of("denominator","mapped","executed","satisfied","mapped_percent","executed_percent","satisfied_percent"))require(report.path("metrics").path(kind).path(field).isNull(),"catalog absent/inactive cannot establish metrics");return;}
        JsonNode c=object(consumed.get(catalogPath())),approval=c.path("authority");require("coverage-catalog.v1".equals(text(c,"schema_version"))&&"reviewed_inventory".equals(text(approval,"kind")),"catalog version/authority mismatch");for(String f:List.of("source_ref","review_ref","approved_by"))text(approval,f);require(text(approval,"source_sha256").matches("[a-f0-9]{64}"),"catalog source digest invalid");instant(approval,"approved_at");for(String field:List.of("domain","project_ref","workspace_ref","repository_revisions"))require(c.path("scope").path(field).equals(input.path("scope").path(field)),"catalog scope mismatch");
        Map<String,JsonNode> reqs=uniqueCatalog(c,"requirements"),acs=uniqueCatalog(c,"acceptance_criteria"),cases=new LinkedHashMap<>(),entities=new LinkedHashMap<>();
        for(JsonNode test:report.path("cases")){String key=test.hasNonNull("qualified_case_ref")?text(test,"qualified_case_ref"):text(test,"test_case_id");require(cases.putIfAbsent(key,test)==null,"catalog ambiguous case");}
        for(JsonNode entity:report.path("entities"))require(entities.putIfAbsent(text(entity,"kind")+"\n"+text(entity,"id"),entity)==null,"catalog duplicate entity");require(entities.size()==reqs.size()+acs.size(),"catalog entity count mismatch");
        ObjectNode inventory=obj("state",reqs.isEmpty()&&acs.isEmpty()?"KNOWN_EMPTY":"KNOWN","completeness","REVIEWED_INVENTORY","catalog_revision",text(c,"catalog_revision"),"catalog_sha256",snapshots.get(catalogPath()),"authority",approval,"scope",c.path("scope"),"reason",reqs.isEmpty()&&acs.isEmpty()?"zero_denominator":null);require(inventory.equals(report.path("inventory")),"catalog inventory projection mismatch");
        Map<String,ObjectNode> expectedAC=new LinkedHashMap<>(),expectedReq=new LinkedHashMap<>();
        for(var entry:acs.entrySet()){
            String id=entry.getKey();JsonNode ac=entry.getValue();String parent=text(ac,"requirement_id"),classification=text(ac,"classification");require(reqs.containsKey(parent)&&Set.of("automatable","partial","manual_only","waived").contains(classification),"catalog AC classification/parent invalid");List<String> ids=catalogStrings(ac.path("test_case_ids"),true);String review=ac.hasNonNull("review_ref")?text(ac,"review_ref"):null;require(ids.isEmpty()||review!=null,"catalog mapping review missing");boolean excluded=Set.of("manual_only","waived").contains(classification)&&approvedExclusion(ac.path("exclusion"));boolean mapped=!ids.isEmpty()&&ids.stream().allMatch(cases::containsKey);boolean executed=mapped&&ids.stream().allMatch(t->"VALID".equals(cases.get(t).path("evidence_state").asText())&&Set.of("PASS","FAIL").contains(cases.get(t).path("canonical_outcome").asText()));boolean pass=executed&&ids.stream().allMatch(t->"PASS".equals(cases.get(t).path("canonical_outcome").asText()));String satisfied=excluded?"EXCLUDED":!Set.of("automatable","partial").contains(classification)?"UNSATISFIED":classification.equals("partial")?"UNKNOWN":pass?"SATISFIED":"UNSATISFIED";String reason=excluded?text(ac.path("exclusion"),"reason"):classification.equals("partial")?"partial_remainder_not_evidenced":!Set.of("automatable","partial").contains(classification)?"unapproved_exclusion":!mapped?"unmapped_ac":!pass?"mapped_tests_not_satisfied":null;
            ArrayNode refs=json.createArrayNode();for(String caseId:ids)if(cases.containsKey(caseId))for(JsonNode link:cases.get(caseId).path("evidence_refs"))refs.add(link);
            ObjectNode e=obj("kind","acceptance_criterion","id",id,"parent_requirement_id",parent,"classification",classification,"state",excluded?"EXCLUDED":mapped?"MAPPED":"UNMAPPED","reason",reason,"test_case_ids",ids,"mapping_refs",ids.isEmpty()?List.of():List.of(review),"evidence_refs",refs,"execution_state",executed?"EXECUTED":"NOT_RUN","satisfaction_state",satisfied,"mapped_test_satisfaction_state",pass?"SATISFIED":"UNSATISFIED");expectedAC.put(id,e);catalogEntity(entities.get("acceptance_criterion\n"+id),e);
        }
        for(var entry:reqs.entrySet()){
            String id=entry.getKey();List<String> ids=catalogStrings(entry.getValue().path("ac_ids"),false);for(String ac:ids)require(acs.containsKey(ac)&&id.equals(acs.get(ac).path("requirement_id").asText()),"catalog requirement/AC linkage mismatch");for(var ac:acs.entrySet())if(id.equals(ac.getValue().path("requirement_id").asText()))require(ids.contains(ac.getKey()),"catalog missing child linkage");List<ObjectNode> eligible=ids.stream().map(expectedAC::get).filter(e->!"EXCLUDED".equals(e.path("state").asText())).toList();boolean excluded=!ids.isEmpty()&&eligible.isEmpty(),mapped=eligible.stream().anyMatch(e->"MAPPED".equals(e.path("state").asText())),executed=!eligible.isEmpty()&&eligible.stream().allMatch(e->"EXECUTED".equals(e.path("execution_state").asText())),satisfied=!eligible.isEmpty()&&eligible.stream().allMatch(e->"SATISFIED".equals(e.path("satisfaction_state").asText())),unknown=mapped&&eligible.stream().anyMatch(e->"UNKNOWN".equals(e.path("satisfaction_state").asText()))&&eligible.stream().noneMatch(e->"UNSATISFIED".equals(e.path("satisfaction_state").asText()));
            ObjectNode e=obj("kind","requirement","id",id,"ac_ids",ids,"state",excluded?"EXCLUDED":mapped?"MAPPED":"UNMAPPED","reason",excluded?"all_child_ac_approved_excluded":!mapped?"unmapped_requirement":unknown?"partial_remainder_not_evidenced":null,"test_case_ids",List.of(),"mapping_refs",List.of(),"evidence_refs",List.of(),"execution_state",executed?"EXECUTED":"NOT_RUN","satisfaction_state",excluded?"EXCLUDED":satisfied?"SATISFIED":unknown?"UNKNOWN":"UNSATISFIED");expectedReq.put(id,e);catalogEntity(entities.get("requirement\n"+id),e);
        }
        require(catalogMetric(expectedAC.values()).equals(report.path("metrics").path("acceptance_criteria"))&&catalogMetric(expectedReq.values()).equals(report.path("metrics").path("requirements")),"catalog metric projection mismatch");
    }
    private Map<String,JsonNode> uniqueCatalog(JsonNode c,String field){Map<String,JsonNode> out=new LinkedHashMap<>();for(JsonNode row:array(c,field))require(out.putIfAbsent(text(row,"id"),row)==null,"catalog duplicate ID");return out;}
    private static List<String> catalogStrings(JsonNode array,boolean optional){if(optional&&array.isMissingNode())return List.of();require(array.isArray()&&array.size()<=10000,"catalog string array invalid");List<String> out=new ArrayList<>();for(JsonNode v:array)require(v.isTextual()&&!v.asText().isBlank()&&!out.contains(v.asText())&&out.add(v.asText()),"catalog duplicate/invalid string");return out;}
    private static boolean approvedExclusion(JsonNode e){try{for(String f:List.of("reason","review_ref","approved_by"))text(e,f);instant(e,"approved_at");return true;}catch(RuntimeException invalid){return false;}}
    private void catalogEntity(JsonNode actual,ObjectNode expected){require(actual!=null,"catalog entity missing");ObjectNode checked=(ObjectNode)actual.deepCopy();checked.remove("oracle_trace_refs");require(expected.equals(checked),"catalog entity projection mismatch");}
    private ObjectNode catalogMetric(Collection<ObjectNode> rows){int d=0,m=0,e=0,s=0;for(JsonNode row:rows)if(!"EXCLUDED".equals(row.path("state").asText())){d++;if("MAPPED".equals(row.path("state").asText()))m++;if("EXECUTED".equals(row.path("execution_state").asText()))e++;if("SATISFIED".equals(row.path("satisfaction_state").asText()))s++;}return obj("denominator",d,"mapped",m,"executed",e,"satisfied",s,"mapped_percent",d==0?null:100.0*m/d,"executed_percent",d==0?null:100.0*e/d,"satisfied_percent",d==0?null:100.0*s/d);}






}

}
