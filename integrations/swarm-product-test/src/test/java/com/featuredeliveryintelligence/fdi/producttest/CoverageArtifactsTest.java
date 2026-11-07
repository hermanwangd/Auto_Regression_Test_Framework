package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract evidence for module consumption, not a native-agent execution test. */
class CoverageArtifactsTest {
    @TempDir Path root;
    Path result,report,coverage,markdown;
    ObjectNode document;
    boolean requiredV2;String handoffPin="a".repeat(64);
    private ProductTestRequest request() {
        return new ProductTestRequest("product-test-request.v1","product-test","1","mission","request","revision","workspace","project",Map.of("framework","revision"),Map.of(),requiredV2?"integration":"component","regression","local",requiredV2?"required":"not_used",root.toString(),root.resolve("suite.yaml").toString(),"SUITE","local",root.resolve("handoff.json").toString(),handoffPin);
    }
    private void fixture() throws Exception {
        root=root.toRealPath(); Path canonical=Files.createDirectories(root.resolve("run")),derived=Files.createDirectories(root.resolve("coverage"));
        result=canonical.resolve("result.json");report=root.resolve("framework-report.json");coverage=derived.resolve("coverage.json");markdown=derived.resolve("coverage.md");
        ObjectNode canonicalDoc=Json.MAPPER.createObjectNode().put("suite_id","SUITE").put("profile","local").put("run_id","RUN-1").put("batch_id","BATCH-1").put("status","failed");
        canonicalDoc.putArray("test_results").addObject().put("test_case_id","TC-1").put("status","failed");Json.MAPPER.writeValue(result.toFile(),canonicalDoc);
        document=Json.MAPPER.createObjectNode().put("schema_version","coverage_report.v1").put("report_status","INCOMPLETE").put("generation_status","GENERATED").put("publication_status","PUBLISHED").put("evidence_classification","framework_verification_only").put("downstream_release_evidence",false).put("canonical_outcome","FAIL");
        ObjectNode subject=document.putObject("subject").put("suite_id","SUITE").put("profile","local").put("run_id","RUN-1").put("batch_id","BATCH-1").put("project_ref","project").put("workspace_ref","workspace");subject.set("repository_revisions",Json.MAPPER.valueToTree(request().repositoryRevisions()));
        document.putObject("inventory").put("state","UNKNOWN");ObjectNode metrics=document.putObject("metrics");for(String k:List.of("requirements","acceptance_criteria"))metrics.putObject(k).putNull("denominator").putNull("satisfied_percent");
        document.putArray("sources").addObject().put("path",result.toString()).put("sha256",LocalFiles.sha256(result)).put("selection_state","SELECTED");
        document.putArray("evidence_links").addObject().put("path",result.toString()).put("sha256",LocalFiles.sha256(result));
        document.putArray("cases").addObject().put("test_case_id","TC-1").put("source_result",result.toString()).put("canonical_outcome","FAIL");
        Files.writeString(markdown,"# Coverage\n\nUNKNOWN inventory; canonical FAIL.\n");save();
    }
    private void v2Fixture()throws Exception {
        fixture();requiredV2=true;ObjectNode handoff=Json.MAPPER.createObjectNode();handoff.putArray("reviewed_contracts").addObject().put("provider_contract","rest_client.v0.3");Json.MAPPER.writeValue(root.resolve("handoff.json").toFile(),handoff);handoffPin=LocalFiles.sha256(root.resolve("handoff.json"));
        ObjectNode provenance=Json.MAPPER.createObjectNode().put("contract_version","product-test-reviewed-handoff.v2");
        provenance.putObject("knowledge_context_scope").put("mission_ref","mission");provenance.putObject("source_lock").put("composition_root_version","b".repeat(40)).put("artifact_source_testrepo_version","a".repeat(40));provenance.putArray("source_repository_versions").addObject().put("source_repo","fixture:product").put("source_testrepo_version","a".repeat(40));
        String caseRef="fixture:product@"+"a".repeat(40)+":tests/sit/cases/test.yaml#TC-1";
        ObjectNode row=provenance.putArray("knowledge_trace").addObject().put("test_case_id","TC-1").put("qualified_case_ref",caseRef).put("qualified_source_ref","fixture:pk@"+"c".repeat(40)+":knowledge.md#PK1").put("context_sha256","2".repeat(64)).put("selection_proof_sha256","3".repeat(64)).put("source_bindings_sha256","4".repeat(64)).put("content_sha256","5".repeat(64)).put("binding_state","VALID").put("selection_state","SELECTED");row.put("governed_record_key","fixture:record").put("record_version",1).put("source_valid_until","2000-01-01T00:05:00Z");for(String field:List.of("requirement_refs","acceptance_refs","risk_refs","api_refs","code_refs"))row.putArray(field).add("fixture:"+field);row.putObject("oracle").put("qualified_ref","fixture:product@"+"a".repeat(40)+":tests/sit/data/oracle.json#ORACLE1").put("sha256","6".repeat(64)).put("review_ref","fixture:review").put("reviewed_by","fixture:reviewer");
        provenance.putArray("source_case_selection").addObject().put("qualified_case_ref",caseRef).put("source_test_id","TC-1").put("state","SELECTED");Path path=Files.createDirectories(result.getParent().resolve("product-test")).resolve("reviewed-handoff.json");Json.MAPPER.writeValue(path.toFile(),provenance);
        ObjectNode canonical=(ObjectNode)Json.read(result);canonical.putObject("product_test").put("contract_version","product-test-reviewed-handoff.v2").put("provenance_sha256",LocalFiles.sha256(path));Json.MAPPER.writeValue(result.toFile(),canonical);
        ((ObjectNode)document.path("sources").get(0)).put("sha256",LocalFiles.sha256(result));((ObjectNode)document.path("evidence_links").get(0)).put("sha256",LocalFiles.sha256(result));document.withArray("evidence_links").addObject().put("path",path.toString()).put("sha256",LocalFiles.sha256(path));
        ObjectNode test=(ObjectNode)document.path("cases").get(0);test.put("qualified_case_ref",caseRef).put("evidence_state","VALID");test.set("evidence_refs",document.path("evidence_links").deepCopy());
        ObjectNode projected=row.deepCopy();projected.put("run_id","RUN-1").put("batch_id","BATCH-1").put("source_result",result.toString()).put("source_result_sha256",LocalFiles.sha256(result)).put("canonical_outcome","FAIL").put("evidence_state","VALID").put("historical_context_state","VALID_AT_INVOCATION").put("new_invocation_eligibility","NOT_EVALUATED_REQUIRES_FRESH_REVIEW");for(String field:List.of("knowledge_context_scope","source_lock","source_repository_versions"))projected.set(field,provenance.path(field).deepCopy());projected.set("scope",document.path("subject").deepCopy());projected.set("evidence_refs",test.path("evidence_refs").deepCopy());document.putArray("knowledge_trace").add(projected);
        ObjectNode selected=(ObjectNode)provenance.path("source_case_selection").get(0).deepCopy();selected.put("run_id","RUN-1").put("batch_id","BATCH-1").put("source_result",result.toString()).put("source_result_sha256",LocalFiles.sha256(result));document.putArray("source_case_selection").add(selected);save();
    }
    @Test void qualifiedV2CoverageExactlyMatchesPinnedCanonicalProjection()throws Exception {v2Fixture();assertThatCode(this::verify).doesNotThrowAnyException();}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"source","case","oracle","context","run","outcome","extra","missing","scope","lock","private_body","selection","result_pin"})
    void resealedV2CoverageCannotAlterPinnedTraceOrExposePrivateBody(String mutation)throws Exception {
        v2Fixture();ObjectNode row=(ObjectNode)document.path("knowledge_trace").get(0);
        switch(mutation){case "source"->row.put("qualified_source_ref","fixture:wrong-source");case "case"->((ObjectNode)document.path("cases").get(0)).put("qualified_case_ref","fixture:wrong-case");case "oracle"->((ObjectNode)row.path("oracle")).put("sha256","0".repeat(64));case "context"->row.put("context_sha256","0".repeat(64));case "run"->row.put("run_id","OTHER");case "outcome"->row.put("canonical_outcome","PASS");case "extra"->document.withArray("knowledge_trace").add(row.deepCopy());case "missing"->document.putArray("knowledge_trace");case "scope"->((ObjectNode)row.path("scope")).put("project_ref","OTHER");case "lock"->((ObjectNode)row.path("source_lock")).put("composition_root_version","0".repeat(40));case "private_body"->row.put("raw_context","PRIVATE-CANARY");case "selection"->((ObjectNode)document.path("source_case_selection").get(0)).put("state","NOT_SELECTED");case "result_pin"->row.put("source_result_sha256","0".repeat(64));}
        save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v2 coverage");
    }
    /** PUBLIC_SYNTHETIC already-admitted canonical-boundary fixtures; no provider or oracle evaluation. */
    private void oracleFixture(boolean v2,String mode)throws Exception {
        if(v2)v2Fixture();else fixture();
        ObjectNode canonical=(ObjectNode)Json.read(result);
        canonical.put("start_time","2000-01-01T00:00:00Z").put("end_time","2000-01-01T00:00:02Z");
        Path p=Files.createDirectories(result.getParent().resolve("product-test")).resolve("reviewed-handoff.json");
        ObjectNode provenance=v2?(ObjectNode)Json.read(p):Json.MAPPER.createObjectNode().put("contract_version","product-test-reviewed-handoff.v1");
        provenance.put("reviewed_by","fixture:reviewer");
        String a="a".repeat(40),repo="fixture:product",caseRef=repo+"@"+a+":tests/sit/cases/test.yaml#TC-1",oracleRef=repo+"@"+a+":tests/sit/data/oracle.json#ORACLE1",requirement=v2?repo+"@"+a+":spec.md#REQ1":"fixture:requirement";
        provenance.putArray("reviewed_files").addObject().put("path","case.yaml").put("role","test").put("sha256","8".repeat(64));
        if(!mode.equals("unbound"))provenance.withArray("reviewed_files").addObject().put("path","oracle.json").put("role","oracle").put("sha256","6".repeat(64));
        var declared=Json.MAPPER.createArrayNode();
        if(!mode.equals("missing"))declared.add(mode.equals("fragment")?"case.yaml#verify-1":"oracle.json");
        if(mode.equals("ambiguous"))declared.add("case.yaml#verify-1");
        provenance.putArray("requirement_mapping");
        if(!mode.equals("missing")){
            ObjectNode mapping=provenance.withArray("requirement_mapping").addObject().put("requirement_ref",requirement).put("review_ref","fixture:review");mapping.putArray("test_case_ids").add("TC-1");mapping.set("oracle_refs",declared.deepCopy());
        }
        if(v2){
            ((ObjectNode)provenance.path("knowledge_trace").get(0)).putArray("requirement_refs").add(requirement);
            provenance.putArray("executed_source_bindings").add(source(caseRef,"case","cases/test.yaml","TC-1","case.yaml","8".repeat(64))).add(source(oracleRef,"oracle","data/oracle.json","ORACLE1","oracle.json","6".repeat(64)));
        }
        Json.MAPPER.writeValue(p.toFile(),provenance);canonical.putObject("product_test").put("contract_version",provenance.path("contract_version").asText()).put("provenance_sha256",LocalFiles.sha256(p));Json.MAPPER.writeValue(result.toFile(),canonical);
        ((ObjectNode)document.path("sources").get(0)).put("sha256",LocalFiles.sha256(result));
        ((ObjectNode)document.path("evidence_links").get(0)).put("sha256",LocalFiles.sha256(result));
        if(!v2)document.withArray("evidence_links").addObject().put("path",p.toString()).put("sha256",LocalFiles.sha256(p));else{
            ((ObjectNode)document.path("evidence_links").get(1)).put("sha256",LocalFiles.sha256(p));
            ObjectNode projected=(ObjectNode)document.path("knowledge_trace").get(0);provenance.path("knowledge_trace").get(0).properties().forEach(entry->projected.set(entry.getKey(),entry.getValue().deepCopy()));projected.put("source_result_sha256",LocalFiles.sha256(result));projected.set("evidence_refs",document.path("evidence_links").deepCopy());
            ((ObjectNode)document.path("source_case_selection").get(0)).put("source_result_sha256",LocalFiles.sha256(result));
        }
        boolean stale=mode.equals("stale");ObjectNode selection=document.putObject("selection");selection.put("policy",stale?"latest_compatible_per_case":"explicit_result_only").put("as_of",stale?"2000-01-01T00:00:10Z":"2000-01-01T00:00:02Z");if(stale)selection.put("max_age_seconds",1);else selection.putNull("max_age_seconds");
        ObjectNode test=(ObjectNode)document.path("cases").get(0);test.set("evidence_refs",document.path("evidence_links").deepCopy());test.put("run_id","RUN-1").put("batch_id","BATCH-1").put("evidence_state",stale?"STALE":"VALID");test.putArray("oracle_trace_refs");document.putArray("entities");var traces=document.putArray("oracle_trace");
        List<String>pointers=new ArrayList<>();declared.forEach(d->pointers.add(d.asText()));if(pointers.isEmpty())pointers.add(null);
        for(String pointer:pointers){
            ObjectNode oracle=Json.MAPPER.createObjectNode();for(String key:List.of("qualified_ref","path","sha256","source_repo","source_commit","source_testrepo_version","review_ref","reviewed_by"))oracle.putNull(key);
            String binding="MISSING",reason=pointer==null?"oracle_reference_missing":null,expected=null;
            if(v2){oracle.put("qualified_ref",oracleRef).put("path","oracle.json").put("sha256","6".repeat(64)).put("source_repo",repo).put("source_commit",a).put("source_testrepo_version",a).put("review_ref","fixture:review").put("reviewed_by","fixture:reviewer");binding="SOURCE_BOUND";}
            else if(pointer!=null&&!pointer.contains("#")){
                if(mode.equals("unbound"))binding="DECLARED_ONLY";else{oracle.put("path","oracle.json").put("sha256","6".repeat(64)).put("review_ref","fixture:review").put("reviewed_by","fixture:reviewer");binding="SOURCE_BOUND";}
            }else if(pointer!=null)binding="DECLARED_ONLY";
            if(pointer!=null){if(declared.size()!=1)reason="mapping_case_oracle_join_ambiguous";else if(pointer.contains("#"))reason="declared_assertion_relation_not_exported";else if(binding.equals("SOURCE_BOUND"))expected=pointer;else reason="oracle_source_binding_missing";}
            if(stale){binding="STALE";expected=null;reason="selected_evidence_stale";}
            String req=mode.equals("missing")?null:requirement,review=mode.equals("missing")?null:"fixture:review";
            String traceId=LocalFiles.sha256(Json.MAPPER.writeValueAsBytes(Arrays.asList(LocalFiles.sha256(result),"project","workspace",v2?caseRef:"TC-1",req,review,pointer)));
            ObjectNode row=traces.addObject().put("trace_id",traceId).put("test_case_id","TC-1").put("source_result",result.toString()).put("source_result_sha256",LocalFiles.sha256(result)).put("run_id","RUN-1").put("batch_id","BATCH-1").put("canonical_outcome","FAIL").put("evidence_state",stale?"STALE":"VALID");
            if(v2)row.put("qualified_case_ref",caseRef);else row.putNull("qualified_case_ref");var reqs=row.putArray("requirement_refs");if(req!=null)reqs.add(req);row.putArray("acceptance_criteria_refs");if(review!=null)row.put("mapping_review_ref",review);else row.putNull("mapping_review_ref");row.set("declared_oracle_refs",declared.deepCopy());row.set("oracle",oracle);
            if(expected==null)row.putNull("expected_ref");else row.put("expected_ref",expected);row.put("expected_ref_state",expected==null?"UNKNOWN":"BOUND_DECLARED_REFERENCE");if(expected==null)row.put("expected_ref_reason",reason);else row.putNull("expected_ref_reason");row.put("binding_state",binding);if(reason==null)row.putNull("binding_reason");else row.put("binding_reason",reason);
            row.putObject("independence").put("state","UNKNOWN").put("reason","independence_attestation_not_in_contract");var refs=row.putArray("knowledge_refs");
            if(v2){var k=refs.addObject();copyPublicFields(provenance.path("knowledge_trace").get(0),k,List.of("qualified_source_ref","record_version","content_sha256","selection_proof_sha256","context_sha256","source_bindings_sha256"));}
            test.withArray("oracle_trace_refs").add(traceId);
        }
        save();
    }
    private static void copyPublicFields(com.fasterxml.jackson.databind.JsonNode source,ObjectNode target,List<String>keys){for(String key:keys)target.set(key,source.get(key).deepCopy());}
    private static ObjectNode source(String id,String role,String path,String sourceId,String reviewed,String sha){
        return Json.MAPPER.createObjectNode().put("artifact_id",id).put("role",role).put("source_repo","fixture:product").put("source_commit","a".repeat(40)).put("source_path","tests/sit/"+path).put("source_id",sourceId).put("source_testrepo_version","a".repeat(40)).put("reviewed_path",reviewed).put("sha256",sha).put("bytes",1).put("source_owner_ref","fixture:owner").put("cleanup_owner_ref","fixture:cleanup").put("required",true);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void admittedOracleSourcePointerIsAcceptedWithUnknownIndependenceAndUnchangedFail(boolean v2)throws Exception {
        oracleFixture(v2,"bound");String before=LocalFiles.sha256(result);assertThat(verify()).hasSize(2);assertThat(LocalFiles.sha256(result)).isEqualTo(before);assertThat(Json.read(result).path("status").asText()).isEqualTo("failed");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"missing","unbound","ambiguous","fragment","stale"})
    void legalLegacyNullableOracleEvidenceDoesNotInventAnExecutedAssertion(String mode)throws Exception {oracleFixture(false,mode);assertThatCode(this::verify).doesNotThrowAnyException();for(var row:document.path("oracle_trace"))assertThat(row.path("expected_ref").isNull()).isTrue();}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"expected","path","hash","qualified","source_version","source_commit","source_repo","case","run","batch","result_sha","outcome","trace_id","independence","context","selection_hash","record_version","extra","missing","duplicate","case_refs","missing_refs","missing_section","private_body","oracle_extra","unknown_reason","entity_refs","wrong_type","oversize_string","rows_limit","malformed_section","role_join"})
    void resealedOracleProjectionCannotChangeCanonicalSemanticBindings(String mutation)throws Exception {
        oracleFixture(true,"bound");ObjectNode row=(ObjectNode)document.path("oracle_trace").get(0),oracle=(ObjectNode)row.path("oracle"),knowledge=(ObjectNode)row.path("knowledge_refs").get(0);
        switch(mutation){
            case "expected"->row.put("expected_ref","other.json");case "path"->oracle.put("path","other.json");case "hash"->oracle.put("sha256","0".repeat(64));case "qualified"->oracle.put("qualified_ref","fixture:other@"+"a".repeat(40)+":oracle.json#ORACLE1");case "source_version"->oracle.put("source_testrepo_version","b".repeat(40));case "source_commit"->oracle.put("source_commit","b".repeat(40));case "source_repo"->oracle.put("source_repo","fixture:sut");
            case "case"->row.put("test_case_id","OTHER");case "run"->row.put("run_id","OTHER");case "batch"->row.put("batch_id","OTHER");case "result_sha"->row.put("source_result_sha256","0".repeat(64));case "outcome"->row.put("canonical_outcome","PASS");case "trace_id"->row.put("trace_id","0".repeat(64));case "independence"->((ObjectNode)row.path("independence")).put("state","SATISFIED");
            case "context"->knowledge.put("context_sha256","0".repeat(64));case "selection_hash"->knowledge.put("selection_proof_sha256","0".repeat(64));case "record_version"->knowledge.put("record_version",2);case "extra"->{ObjectNode extra=row.deepCopy();extra.put("trace_id","0".repeat(64));document.withArray("oracle_trace").add(extra);}case "missing"->document.putArray("oracle_trace");case "duplicate"->document.withArray("oracle_trace").add(row.deepCopy());
            case "case_refs"->((ObjectNode)document.path("cases").get(0)).withArray("oracle_trace_refs").add("0".repeat(64));case "missing_refs"->((ObjectNode)document.path("cases").get(0)).remove("oracle_trace_refs");case "missing_section"->document.remove("oracle_trace");case "private_body"->row.put("raw_context","PRIVATE-CANARY");case "oracle_extra"->oracle.put("private_receipt","PRIVATE-CANARY");case "unknown_reason"->row.put("expected_ref_reason","invented");
            case "entity_refs"->document.withArray("entities").addObject().put("kind","requirement").put("id","fixture:other").putArray("oracle_trace_refs").add(row.path("trace_id").asText());case "wrong_type"->row.put("expected_ref",42);case "oversize_string"->row.put("mapping_review_ref","x".repeat(4097));case "rows_limit"->{for(int i=0;i<10000;i++)document.withArray("oracle_trace").addObject().put("trace_id","0".repeat(64));}case "malformed_section"->document.putObject("oracle_trace");case "role_join"->{oracle.put("path","case.yaml").put("sha256","8".repeat(64)).put("qualified_ref",document.at("/cases/0/qualified_case_ref").asText());row.put("expected_ref","case.yaml");}
        }
        save();assertThatThrownBy(this::verify).as(mutation).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oracle coverage");
    }
    @Test void absentOptionalSectionWithNoClaimsRetainsExistingV2Compatibility()throws Exception {v2Fixture();assertThatCode(this::verify).doesNotThrowAnyException();}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"missing","unbound","ambiguous","fragment","stale"})
    void nullableLegacyPointerCannotBePromotedByResealing(String mode)throws Exception {
        oracleFixture(false,mode);ObjectNode row=(ObjectNode)document.path("oracle_trace").get(0);row.put("expected_ref","oracle.json").put("expected_ref_state","BOUND_DECLARED_REFERENCE").putNull("expected_ref_reason");row.put("binding_state","SOURCE_BOUND");save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oracle coverage");
    }
    @Test void staleV2RetainsTheExistingWholeArtifactRejectionBoundary()throws Exception {oracleFixture(true,"stale");assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v2 coverage");}

    @Test void exactCaseAndEntityReferencesPreserveCatalogLinkConsistencyOnly()throws Exception {
        oracleFixture(true,"bound");ObjectNode row=(ObjectNode)document.path("oracle_trace").get(0);String req=row.path("requirement_refs").get(0).asText(),ac="fixture:product@"+"a".repeat(40)+":spec.md#AC1",trace=row.path("trace_id").asText();
        ((ObjectNode)document.path("inventory")).put("state","KNOWN");row.withArray("acceptance_criteria_refs").add(ac);
        ObjectNode criterion=document.withArray("entities").addObject().put("kind","acceptance_criterion").put("id",ac).put("parent_requirement_id",req);criterion.putArray("test_case_ids").add(document.at("/cases/0/qualified_case_ref").asText());criterion.putArray("oracle_trace_refs").add(trace);
        document.withArray("entities").addObject().put("kind","requirement").put("id",req).putArray("oracle_trace_refs").add(trace);save();
        assertThat(verify()).hasSize(2);criterion.remove("oracle_trace_refs");save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("oracle coverage");
    }
    @Test void unknownInventoryCannotAcquireAuthoritativeACLinksFromResealedEntities()throws Exception {
        oracleFixture(false,"bound");ObjectNode row=(ObjectNode)document.path("oracle_trace").get(0);String req=row.path("requirement_refs").get(0).asText(),trace=row.path("trace_id").asText();row.withArray("acceptance_criteria_refs").add("AC");
        ObjectNode criterion=document.withArray("entities").addObject().put("kind","acceptance_criterion").put("id","AC").put("parent_requirement_id",req);criterion.putArray("test_case_ids").add("TC-1");criterion.putArray("oracle_trace_refs").add(trace);save();
        assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UNKNOWN inventory");
    }
    @Test void rawProvenancePinCannotBeReplacedByRehashingDerivedReferences()throws Exception {
        oracleFixture(false,"bound");Path path=result.getParent().resolve("product-test/reviewed-handoff.json");ObjectNode p=(ObjectNode)Json.read(path);p.put("reviewed_by","fixture:other");Json.MAPPER.writeValue(path.toFile(),p);((ObjectNode)document.path("evidence_links").get(1)).put("sha256",LocalFiles.sha256(path));save();
        assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provenance digest");
    }
    @Test void recordedStalePolicyCannotBeUpgradedByChangingBothCaseAndOracleClaims()throws Exception {
        oracleFixture(false,"stale");((ObjectNode)document.path("cases").get(0)).put("evidence_state","VALID");ObjectNode row=(ObjectNode)document.path("oracle_trace").get(0);row.put("evidence_state","VALID").put("binding_state","SOURCE_BOUND").put("expected_ref","oracle.json").put("expected_ref_state","BOUND_DECLARED_REFERENCE").putNull("expected_ref_reason").putNull("binding_reason");save();
        assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("qualification mismatch");
    }
    @Test void pureOracleGuardAcceptsHistoricalStaleV2ButExistingWholeGuardStillRejects()throws Exception {
        oracleFixture(true,"stale");String before=LocalFiles.sha256(result);
        assertThatCode(()->ReviewedOracleCoverageProjection.verify(document,result,Json.readCanonical(result,request()),request())).doesNotThrowAnyException();
        assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v2 coverage");assertThat(LocalFiles.sha256(result)).isEqualTo(before);
    }
    @Test void pureHistoricalOracleGuardDoesNotReopenTheOriginalHandoff()throws Exception {
        oracleFixture(true,"bound");var admitted=Json.readCanonical(result,request());var input=request();Files.delete(Path.of(input.reviewedHandoff()));
        assertThatCode(()->ReviewedOracleCoverageProjection.verify(document,result,admitted,input)).doesNotThrowAnyException();
    }
    private void save() throws Exception {
        Json.MAPPER.writeValue(coverage.toFile(),document);
        var summary=Json.MAPPER.createObjectNode().put("coverage_json",coverage.toString()).put("coverage_md",markdown.toString()).put("coverage_report_status",document.path("report_status").asText()).put("coverage_json_sha256",LocalFiles.sha256(coverage)).put("coverage_md_sha256",LocalFiles.sha256(markdown));
        Json.MAPPER.writeValue(report.toFile(),summary);
    }
    @SuppressWarnings("unchecked") private Map<String,ProductTestReceipt.Artifact> verify() throws Exception {
        Class<?> type=assertDoesNotThrow(() -> Class.forName("com.featuredeliveryintelligence.fdi.producttest.CoverageArtifacts"),"Coverage artifact validation must be implemented");
        var method=type.getDeclaredMethod("verify",Path.class,Path.class,Path.class,ProductTestRequest.class);method.setAccessible(true);
        try{return (Map<String,ProductTestReceipt.Artifact>)method.invoke(null,coverage.getParent(),report,result,request());}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception ex)throw ex;throw e;}
    }
    @Test void incompleteUnknownCoverageIsPinnedWithoutRewritingFailedVerdict() throws Exception {
        fixture();String before=LocalFiles.sha256(result);var artifacts=verify();
        assertThat(artifacts.keySet()).containsExactlyInAnyOrder("coverage_json","coverage_md");assertThat(artifacts.get("coverage_md").sha256()).isEqualTo(LocalFiles.sha256(markdown));assertThat(LocalFiles.sha256(result)).isEqualTo(before);
    }
    @Test void missingPairMemberPreventsCompletion() throws Exception {fixture();Files.delete(markdown);assertThatThrownBy(this::verify).isInstanceOf(Exception.class).hasMessageContaining("regular file");}
    @Test void tamperedMarkdownFailsTrustedReportDigest() throws Exception {fixture();Files.writeString(markdown,"changed");assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");}
    @Test void recomputedDigestCannotHideIdentityMismatch() throws Exception {fixture();((ObjectNode)document.path("subject")).put("run_id","OTHER");save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");}
    @Test void externalReferenceAndTamperedCanonicalSourceAreRejected() throws Exception {
        fixture();Path outside=root.resolve("outside.txt");Files.writeString(outside,"unapproved");document.withArray("evidence_links").addObject().put("path",outside.toString()).put("sha256",LocalFiles.sha256(outside));save();assertThatThrownBy(this::verify).isInstanceOf(Exception.class).hasMessageContaining("directory");
        fixture();((ObjectNode)document.path("sources").get(0)).put("sha256","b".repeat(64));save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
    }
    @Test void invalidCoverageOrInventedUnknownDenominatorIsRejected() throws Exception {
        fixture();document.put("report_status","INVALID");save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("status");
        fixture();((ObjectNode)document.at("/metrics/requirements")).put("denominator",100);save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UNKNOWN");
    }
}
